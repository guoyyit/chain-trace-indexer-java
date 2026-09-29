package com.github.guoyyit.chaintraceindexerjava.service;

import com.github.guoyyit.chaintraceindexerjava.index.ArchiveManager;
import com.github.guoyyit.chaintraceindexerjava.index.LiveIndex;
import com.github.guoyyit.chaintraceindexerjava.index.Snapshot;
import com.github.guoyyit.chaintraceindexerjava.model.ArchiveResult;
import com.github.guoyyit.chaintraceindexerjava.model.BlockPayload;
import com.github.guoyyit.chaintraceindexerjava.model.RawAllocation;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;
import com.github.guoyyit.chaintraceindexerjava.storage.BlockStore;
import com.github.guoyyit.chaintraceindexerjava.storage.WalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveAndRecoveryTest {

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

    private List<RawBlock> chainWith(int blocks) {
        List<RawBlock> chain = new ArrayList<>();
        chain.add(new RawBlock(0, "g", "",
                List.of(new RawAllocation("alice", 10_000_000L)), List.of()));
        String parent = "g";
        for (int height = 1; height <= blocks; height++) {
            String hash = "h" + height;
            String program = "TRANSFER bob " + height + "\nSSTORE key" + height + " val" + height;
            RawTransaction tx = new RawTransaction("t" + height, "alice", "alice",
                    0, 100_000, program);
            chain.add(new RawBlock(height, hash, parent, null, List.of(tx)));
            parent = hash;
        }
        return chain;
    }

    @Test
    void archivedDataRemainsQueryableAndConsistentWithLiveSuffix() {
        IndexService service = newService();
        service.submit(new BlockPayload(chainWith(6)));

        ArchiveResult result = service.archive(3);
        assertThat(result.archivedToHeight()).isEqualTo(3L);

        Snapshot snapshot = service.snapshot();
        assertThat(snapshot.status().archivedToHeight()).isEqualTo(3L);
        assertThat(snapshot.queryFrames(null, null, null, null, 0, 1000).items())
                .hasSize(7);
        // Archived frames reachable.
        assertThat(snapshot.queryFrames("t1", null, null, null, 0, 100).items()).isNotEmpty();
        // Live suffix reachable.
        assertThat(snapshot.queryFrames("t5", null, null, null, 0, 100).items()).isNotEmpty();
        // Archived address changes reachable.
        List<StateChange> bobArchived = snapshot.queryChanges("bob", null, 1L, 3L, 0, 100).items();
        assertThat(bobArchived).hasSize(3);
        List<StateChange> bobLive = snapshot.queryChanges("bob", null, 4L, 6L, 0, 100).items();
        assertThat(bobLive).hasSize(3);
    }

    @Test
    void onlyContiguousPrefixCanBeArchived() {
        IndexService service = newService();
        service.submit(new BlockPayload(chainWith(6)));
        service.archive(2);
        assertThatThrownBy(() -> service.archive(10))
                .isInstanceOf(IllegalArgumentException.class);
        // Re-archiving an already archived range is a no-op, not an error.
        assertThat(service.archive(2).archivedToHeight()).isEqualTo(2L);
        ArchiveResult next = service.archive(5);
        assertThat(next.archivedToHeight()).isEqualTo(5L);
    }

    @Test
    void deepReorgInsideArchivedRangeRebuildsAndStillAnswersQueries() {
        IndexService service = newService();
        service.submit(new BlockPayload(chainWith(6)));
        service.archive(4);

        RawTransaction forkTx = new RawTransaction("fork1", "alice", "zoe", 0, 100_000,
                "TRANSFER zoe 42");
        RawBlock fork3 = new RawBlock(3, "h3f", "h2", null, List.of(forkTx));
        RawBlock fork4 = new RawBlock(4, "h4f", "h3f", null,
                List.of(new RawTransaction("fork2", "alice", "zoe", 0, 100_000,
                        "SSTORE branch yes")));
        service.submit(new BlockPayload(List.of(fork3, fork4)));

        Snapshot snapshot = service.snapshot();
        assertThat(snapshot.status().headHash()).isEqualTo("h4f");
        // The archive covering the fork point is expanded back to live
        // segments; no finalized data is lost.
        assertThat(snapshot.status().archivedToHeight()).isEqualTo(-1L);
        // Re expanded fork range is queryable.
        assertThat(snapshot.queryFrames("fork1", null, null, null, 0, 100).items()).isNotEmpty();
        // Archived common prefix h1/h2 still queryable.
        assertThat(snapshot.queryFrames("t2", null, null, null, 0, 100).items()).isNotEmpty();
        // Old fork data gone.
        assertThat(snapshot.queryFrames("t3", null, null, null, 0, 100).items()).isEmpty();

        // Recovery after the deep reorg reproduces exactly the same index.
        IndexService restarted = newService();
        Snapshot after = restarted.snapshot();
        List<TraceFrame> before = snapshot.queryFrames(null, null, null, null, 0, 1000).items();
        List<TraceFrame> afterFrames = after.queryFrames(null, null, null, null, 0, 1000).items();
        assertThat(afterFrames).usingRecursiveFieldByFieldElementComparator().isEqualTo(before);
    }

    @Test
    void concurrentQueriesDoNotBlockOrLoseDataWhileIndexing() throws Exception {
        IndexService service = newService();
        service.submit(new BlockPayload(chainWith(2)));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> readers = new ArrayList<>();
        for (int reader = 0; reader < 3; reader++) {
            readers.add(pool.submit(() -> {
                start.await();
                long lastTotal = -1L;
                for (int i = 0; i < 50; i++) {
                    Snapshot snapshot = service.snapshot();
                    long total = snapshot.queryFrames(null, null, null, null, 0, 1000).total();
                    if (total < lastTotal) {
                        return false;
                    }
                    // A single snapshot view is internally consistent.
                    long paged = 0L;
                    long offset = 0L;
                    while (true) {
                        var page = snapshot.queryFrames(null, null, null, null, offset, 2);
                        paged += page.items().size();
                        if (page.nextOffset() < 0) {
                            break;
                        }
                        offset = page.nextOffset();
                    }
                    if (paged != total) {
                        return false;
                    }
                    lastTotal = total;
                }
                return true;
            }));
        }
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            for (int height = 3; height <= 20; height++) {
                String program = "TRANSFER bob " + height;
                RawBlock block = new RawBlock(height, "h" + height, "h" + (height - 1),
                        null, List.of(new RawTransaction("t" + height, "alice", "alice",
                        0, 100_000, program)));
                // Use the already persisted chain; parent blocks exist on disk.
                service.submit(new BlockPayload(List.of(block)));
            }
        });
        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<Boolean> reader : readers) {
            assertThat(reader.get(30, TimeUnit.SECONDS)).isTrue();
        }
        pool.shutdown();

        Snapshot finalSnapshot = service.snapshot();
        assertThat(finalSnapshot.status().headHash()).isEqualTo("h20");
        assertThat(finalSnapshot.queryFrames(null, null, null, null, 0, 1000).total())
                .isEqualTo(21L);
    }

    @Test
    void orphanTempFilesFromACrashDoNotCorruptRecovery() throws Exception {
        IndexService service = newService();
        service.submit(new BlockPayload(chainWith(3)));
        // Simulate a half finished segment write and a leftover temp file.
        Path segments = tempDir.resolve("segments");
        Files.createDirectories(segments);
        Path junk = segments.resolve("seg-0000000000000000099-junk.json.tmp");
        Files.writeString(junk, "{not json");

        IndexService restarted = newService();
        assertThat(restarted.snapshot().status().headHash()).isEqualTo("h3");
        assertThat(Files.exists(junk)).isFalse();
        assertThat(restarted.snapshot().queryFrames(null, null, null, null, 0, 1000).items())
                .hasSize(4);
    }
}
