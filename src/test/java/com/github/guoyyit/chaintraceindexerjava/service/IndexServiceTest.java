package com.github.guoyyit.chaintraceindexerjava.service;

import com.github.guoyyit.chaintraceindexerjava.index.ArchiveManager;
import com.github.guoyyit.chaintraceindexerjava.index.LiveIndex;
import com.github.guoyyit.chaintraceindexerjava.index.Snapshot;
import com.github.guoyyit.chaintraceindexerjava.model.BlockPayload;
import com.github.guoyyit.chaintraceindexerjava.model.PageResult;
import com.github.guoyyit.chaintraceindexerjava.model.RawAllocation;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.SubmitResult;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;
import com.github.guoyyit.chaintraceindexerjava.storage.BlockStore;
import com.github.guoyyit.chaintraceindexerjava.storage.WalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexServiceTest {

    @TempDir
    Path tempDir;

    private IndexService newService() {
        BlockStore blockStore = new BlockStore(tempDir);
        WalStore walStore = new WalStore(tempDir);
        ArchiveManager archiveManager = new ArchiveManager(tempDir);
        LiveIndex liveIndex = new LiveIndex(archiveManager);
        IndexService service = new IndexService(blockStore, walStore, liveIndex, archiveManager);
        service.recover();
        return service;
    }

    private List<RawBlock> sampleChain() {
        RawBlock genesis = new RawBlock(0, "g0", "",
                List.of(new RawAllocation("alice", 1_000_000L)), List.of());
        List<RawBlock> chain = new ArrayList<>();
        chain.add(genesis);
        String parent = "g0";
        for (long height = 1; height <= 6; height++) {
            String hash = "b" + height;
            String program = "TRANSFER bob " + (10 * height) + "\nSSTORE k" + height + " v" + height;
            RawTransaction tx = new RawTransaction("tx" + height, "alice", "alice",
                    0, 200_000, program);
            chain.add(new RawBlock(height, hash, parent, null, List.of(tx)));
            parent = hash;
        }
        return chain;
    }

    private List<RawBlock> fullSubmit(IndexService service, List<RawBlock> chain) {
        List<RawBlock> all = new ArrayList<>();
        for (RawBlock block : chain) {
            service.submit(new BlockPayload(List.of(block)));
            all.add(block);
        }
        return all;
    }

    @Test
    void incrementalAndFullReplayProduceIdenticalResults() {
        IndexService incremental = newService();
        List<RawBlock> chain = sampleChain();
        for (RawBlock block : chain) {
            incremental.submit(new BlockPayload(List.of(block)));
        }

        IndexService bulk = newService();
        bulk.submit(new BlockPayload(chain));

        Snapshot a = incremental.snapshot();
        Snapshot b = bulk.snapshot();
        assertSameFrames(a, b);
        assertSameChanges(a, b);
        assertThat(a.status().headHash()).isEqualTo("b6");
        assertThat(b.status().headHash()).isEqualTo("b6");
    }

    @Test
    void queriesSupportFiltersHeightRangeAndStablePagination() {
        IndexService service = newService();
        List<RawBlock> chain = sampleChain();
        service.submit(new BlockPayload(chain));

        Snapshot snapshot = service.snapshot();
        PageResult<TraceFrame> page1 = snapshot.queryFrames(null, null, 2L, 5L, 0, 2);
        PageResult<TraceFrame> page2 = snapshot.queryFrames(null, null, 2L, 5L, 2, 2);
        assertThat(page1.items()).hasSize(2);
        assertThat(page2.items()).hasSize(2);
        assertThat(page1.nextOffset()).isEqualTo(2L);
        assertThat(page1.total()).isEqualTo(4L);

        List<TraceFrame> all = new ArrayList<>();
        all.addAll(page1.items());
        all.addAll(page2.items());
        assertThat(all).extracting(TraceFrame::blockHeight).containsExactly(2L, 3L, 4L, 5L);

        PageResult<StateChange> bob = snapshot.queryChanges("bob", null, 1L, 6L, 0, 100);
        assertThat(bob.total()).isEqualTo(6L);
        assertThat(bob.items()).allMatch(change -> "bob".equals(change.address()));

        PageResult<StateChange> byTx = snapshot.queryChanges(null, "tx3", null, null, 0, 100);
        assertThat(byTx.items()).isNotEmpty();
        assertThat(byTx.items()).allMatch(change -> "tx3".equals(change.txHash()));
    }

    @Test
    void snapshotStaysStableWhileIndexAdvances() {
        IndexService service = newService();
        List<RawBlock> chain = sampleChain();
        service.submit(new BlockPayload(chain.subList(0, 3)));
        Snapshot old = service.snapshot();

        service.submit(new BlockPayload(chain.subList(3, chain.size())));

        PageResult<TraceFrame> oldPage = old.queryFrames(null, null, null, null, 0, 100);
        PageResult<TraceFrame> newPage = service.snapshot()
                .queryFrames(null, null, null, null, 0, 100);
        assertThat(oldPage.total()).isLessThan(newPage.total());
        assertThat(old.status().headHash()).isEqualTo("b2");
        assertThat(service.snapshot().status().headHash()).isEqualTo("b6");
    }

    @Test
    void duplicateSubmissionIsIdempotent() {
        IndexService service = newService();
        List<RawBlock> chain = sampleChain();
        SubmitResult first = service.submit(new BlockPayload(chain));
        SubmitResult second = service.submit(new BlockPayload(chain));
        assertThat(first.appliedHeights()).hasSize(7);
        assertThat(second.appliedHeights()).isEmpty();
        assertSameFrames(service.snapshot(), service.snapshot());
    }

    @Test
    void reorgReplacesBranchAndKeepsCommonAncestor() {
        IndexService service = newService();
        List<RawBlock> chain = sampleChain();
        service.submit(new BlockPayload(chain));
        long before = service.snapshot().queryChanges(null, null, null, null, 0, 1000).total();

        // Fork at height 3: new blocks 3',4'.
        RawTransaction tx3p = new RawTransaction("tx3p", "alice", "carol", 0, 200_000,
                "TRANSFER carol 77");
        RawBlock b3p = new RawBlock(3, "b3p", "b2", null, List.of(tx3p));
        RawTransaction tx4p = new RawTransaction("tx4p", "alice", "carol", 0, 200_000,
                "SSTORE branch new");
        RawBlock b4p = new RawBlock(4, "b4p", "b3p", null, List.of(tx4p));
        SubmitResult result = service.submit(new BlockPayload(List.of(b3p, b4p)));

        assertThat(result.appliedHeights()).containsExactly(3L, 4L);
        Snapshot snapshot = service.snapshot();
        assertThat(snapshot.status().headHash()).isEqualTo("b4p");
        // Old branch data (b3,b4,b5,b6) gone.
        assertThat(snapshot.queryFrames("tx4", null, null, null, 0, 100).items()).isEmpty();
        assertThat(snapshot.queryFrames("tx5", null, null, null, 0, 100).items()).isEmpty();
        // Common ancestor b1/b2 traces still present.
        assertThat(snapshot.queryFrames("tx2", null, null, null, 0, 100).items()).isNotEmpty();
        // New branch data present.
        assertThat(snapshot.queryFrames("tx3p", null, null, null, 0, 100).items()).isNotEmpty();
        long after = snapshot.queryChanges(null, null, null, null, 0, 1000).total();
        assertThat(after).isLessThan(before);
        // Old branch address carol has changes; bob no longer has b3..b6 transfers.
        PageResult<StateChange> bobChanges = snapshot.queryChanges("bob", null, 3L, 6L, 0, 100);
        assertThat(bobChanges.items()).isEmpty();
    }

    @Test
    void crashRecoveryResumesFromLastCommitWithoutDuplicates() {
        IndexService service = newService();
        List<RawBlock> chain = sampleChain();
        service.submit(new BlockPayload(chain.subList(0, 4)));

        // Simulate process restart with the same data directory.
        IndexService restarted = newService();
        Snapshot snapshotBefore = service.snapshot();
        assertSameFrames(snapshotBefore, restarted.snapshot());

        restarted.submit(new BlockPayload(chain.subList(4, chain.size())));
        assertThat(restarted.snapshot().status().headHash()).isEqualTo("b6");
        assertThat(restarted.snapshot()
                .queryFrames(null, null, null, null, 0, 1000).total())
                .isEqualTo(7L); // genesis frame + six tx root frames
    }

    private void assertSameFrames(Snapshot a, Snapshot b) {
        List<TraceFrame> framesA = a.queryFrames(null, null, null, null, 0, 10_000).items();
        List<TraceFrame> framesB = b.queryFrames(null, null, null, null, 0, 10_000).items();
        assertThat(framesA).usingRecursiveFieldByFieldElementComparator().isEqualTo(framesB);
    }

    private void assertSameChanges(Snapshot a, Snapshot b) {
        List<StateChange> changesA = a.queryChanges(null, null, null, null, 0, 10_000).items();
        List<StateChange> changesB = b.queryChanges(null, null, null, null, 0, 10_000).items();
        assertThat(changesA).usingRecursiveFieldByFieldElementComparator().isEqualTo(changesB);
    }
}
