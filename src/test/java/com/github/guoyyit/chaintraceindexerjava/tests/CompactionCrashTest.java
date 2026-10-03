package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveService;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import com.github.guoyyit.chaintraceindexerjava.store.WalLog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompactionCrashTest {

    private static final int SEGMENT_BYTES = 1024 * 1024;

    private RawBlock genesis() {
        return TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 1_000_000L),
                        TestData.alloc("B", 500_000L)));
    }

    @ParameterizedTest(name = "crash at {0} with archived history")
    @EnumSource(value = WalLog.CompactionCrashPoint.class,
            names = {"AFTER_REPLACEMENT_WRITTEN", "AFTER_MANIFEST_STAGED",
                    "AFTER_MANIFEST_SWAPPED", "AFTER_OLD_SEGMENTS_DELETED"})
    void crashDuringCompactionWithArchiveLosesNoData(
            WalLog.CompactionCrashPoint point, @TempDir Path dir) {
        List<RawBlock> blocks = canonicalBlocks();
        try (ChainIndexer indexer = new ChainIndexer(dir, SEGMENT_BYTES, Long.MAX_VALUE)) {
            for (RawBlock block : blocks) {
                indexer.submitBlock(block);
            }
            new ArchiveService(indexer).archiveUpTo(3);
            indexer.walLog().setCrashInjection(point);
            assertThrows(WalLog.SimulatedProcessKill.class, indexer::forceCompaction);
        }

        try (ChainIndexer reopened = new ChainIndexer(dir, SEGMENT_BYTES, Long.MAX_VALUE)) {
            assertEquals(8, reopened.tipHeight());
            try (Snapshot snapshot = reopened.snapshot()) {
                for (long height = 0; height <= 3; height++) {
                    assertNotNull(snapshot.archivedHandle(height), "archived " + height);
                }
                IndexDigest expected = IndexDigest.of(blocks);
                expected.assertEqualsSnapshot(snapshot);
            }
        }
    }

    private List<RawBlock> canonicalBlocks() {
        List<RawBlock> blocks = new ArrayList<>();
        blocks.add(genesis());
        for (long height = 1; height <= 8; height++) {
            RawTransaction[] txs;
            if (height % 3 == 0L) {
                // block with a failed transaction followed by a successful one
                txs = new RawTransaction[]{
                        TestData.tx("fail-" + height, "A", 1, List.of("revert boom")),
                        TestData.tx("ok-" + height, "A", 1,
                                List.of("sset k v" + height))
                };
            } else {
                txs = new RawTransaction[]{
                        TestData.tx("ok-" + height, "A", 1,
                                List.of("call B 2", "  sset k v" + height, "  end"))
                };
            }
            blocks.add(TestData.block(height, "h" + height, "h" + (height - 1), txs));
        }
        return blocks;
    }

    @ParameterizedTest(name = "crash at {0}")
    @EnumSource(value = WalLog.CompactionCrashPoint.class,
            names = {"AFTER_REPLACEMENT_WRITTEN", "AFTER_MANIFEST_STAGED",
                    "AFTER_MANIFEST_SWAPPED", "AFTER_OLD_SEGMENTS_DELETED"})
    void crashDuringCompactionLosesNoIndexedData(
            WalLog.CompactionCrashPoint point, @TempDir Path dir) {
        IndexDigest expected;
        try (ChainIndexer indexer = new ChainIndexer(dir, SEGMENT_BYTES, Long.MAX_VALUE)) {
            for (RawBlock block : canonicalBlocks()) {
                indexer.submitBlock(block);
            }
            indexer.walLog().setCrashInjection(point);
            assertThrows(WalLog.SimulatedProcessKill.class, indexer::forceCompaction);
            expected = IndexDigest.of(canonicalBlocks());
        }

        assertDoesNotThrow(() -> {
            try (ChainIndexer reopened = new ChainIndexer(dir, SEGMENT_BYTES, Long.MAX_VALUE)) {
                assertEquals(8, reopened.tipHeight());
                assertEquals("h8", reopened.tipHash());
                try (Snapshot snapshot = reopened.snapshot()) {
                    expected.assertEqualsSnapshot(snapshot);
                }

                // service keeps working: extend, single-block replace, deep reorg
                reopened.submitBlock(TestData.block(9, "h9", "h8",
                        TestData.tx("ok-9", "A", 1, List.of("sset k v9"))));
                assertEquals(9, reopened.tipHeight());

                reopened.submitBlock(TestData.block(9, "r9", "h8",
                        TestData.tx("r-ok-9", "A", 1, List.of("sset k r9"))));
                assertEquals("r9", reopened.tipHash());

                reopened.submitBlock(TestData.block(6, "h6", "h5"));
                assertEquals(6, reopened.tipHeight());
                reopened.submitBlock(TestData.block(7, "f7", "h6",
                        TestData.tx("f-ok-7", "A", 1, List.of("sset k w7"))));
                assertEquals("f7", reopened.tipHash());
            }
        });
    }

    /**
     * Expected digest produced by replaying a block sequence from an empty state.
     */
    private record IndexDigest(long tipHeight, String tipHash, List<String> changeLines,
                               List<String> traceLines) {
        static IndexDigest of(List<RawBlock> blocks) {
            Path temp;
            try {
                temp = java.nio.file.Files.createTempDirectory("digest");
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
            temp.toFile().deleteOnExit();
           try (ChainIndexer fresh = new ChainIndexer(temp, SEGMENT_BYTES, Long.MAX_VALUE)) {
               for (RawBlock block : blocks) {
                   fresh.submitBlock(block);
               }
                try (Snapshot snapshot = fresh.snapshot()) {
                    return capture(snapshot, fresh.tipHeight(), fresh.tipHash());
                }
            }
        }

        void assertEqualsSnapshot(Snapshot snapshot) {
            IndexDigest actual = capture(snapshot, snapshot.tipHeight(), null);
            assertEquals(tipHeight, actual.tipHeight);
            assertEquals(changeLines, actual.changeLines);
            assertEquals(traceLines, actual.traceLines);
        }

        private static IndexDigest capture(Snapshot snapshot, long tipHeight, String tipHash) {
            List<String> changeLines = new ArrayList<>();
            List<String> traceLines = new ArrayList<>();
            for (long height : snapshot.heights()) {
                var block = snapshot.block(height);
                for (int txIndex = 0; txIndex < block.transactions().size(); txIndex++) {
                    TransactionResult tx = block.transactions().get(txIndex);
                    traceLines.add(height + "/" + tx.hash()
                            + ":applied=" + tx.applied()
                            + ":success=" + tx.success()
                            + ":frames=" + snapshot.flattenTrace(tx.hash()).size());
                    for (StateChange change : tx.changes()) {
                        changeLines.add(height + "/" + tx.hash()
                                + ":" + change.address()
                                + ":" + change.type()
                                + ":" + change.key()
                                + ":" + change.depth()
                                + ":" + change.oldValue()
                                + "->" + change.newValue());
                    }
                }
                for (int i = 0; i < block.allocations().size(); i++) {
                    StateChange change = block.allocations().get(i);
                    changeLines.add(height + "/alloc:" + change.address()
                            + ":" + change.type() + ":" + change.key()
                            + ":" + change.depth()
                            + ":" + change.oldValue() + "->" + change.newValue());
                }
            }
            return new IndexDigest(tipHeight, tipHash, List.copyOf(changeLines),
                    List.copyOf(traceLines));
        }
    }
}
