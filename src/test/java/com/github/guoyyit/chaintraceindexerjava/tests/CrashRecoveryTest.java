package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.BlockSubmissionResult;
import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.store.LogRecord;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-safety tests for reorg and WAL compaction. Each test simulates a process
 * kill at a specific window by leaving the on-disk WAL in the state the real
 * fsync ordering would produce, then reopens the indexer and verifies recovery.
 */
class CrashRecoveryTest {

    private static final long SMALL_COMPACTION_THRESHOLD = 1L;

    private RawBlock genesis() {
        return TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 10_000L)));
    }

    private RawBlock block(long height, String hash, String prev) {
        return TestData.block(height, hash, prev,
                TestData.tx("tx-" + height, "A", 1, List.of("transfer B 1")));
    }

    /**
     * A reorg writes {@code BlockUndone} before {@code BlockCommitted}. A kill
     * between the two fsyncs leaves an undo record with no matching commit.
     * Recovery must end at the last committed position (the undone tip is gone),
     * and resubmitting the fork must extend cleanly.
     */
    @Test
    void crashDuringReorgBeforeCommitResumesFromLastCommitted(@TempDir Path dir) {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(block(1, "h1", "g"));
            indexer.submitBlock(block(2, "h2", "h1"));
            // Simulate the first fsynced write of a reorg: undo the tip.
            indexer.walLog().append(new LogRecord.BlockUndone(2));
        }

        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            // The undone tip was never recommitted, so the head is at height 1.
            assertEquals(1, reopened.tipHeight());
            try (Snapshot snapshot = reopened.snapshot()) {
                assertTrue(snapshot.containsHeight(1));
                assertFalse(snapshot.containsHeight(2));
            }
            // Resubmit the fork tip: it extends from the recovered head.
            RawBlock fork = TestData.block(2, "f2", "h1",
                    TestData.tx("fork-tx", "A", 1, List.of("transfer C 1")));
            BlockSubmissionResult result = reopened.submitBlock(fork);
            assertEquals("extended", result.action());
            assertEquals(2, reopened.tipHeight());
            assertEquals("f2", reopened.tipHash());
        }
    }

    /**
     * A reorg that includes a failing transaction must still be recoverable.
     * The failing tx produces no trace/changes; undo+redo across a crash window
     * must not resurrect or drop data.
     */
    @Test
    void crashDuringReorgWithFailedTxRecoversAndContinues(@TempDir Path dir) {
        RawBlock failingTip = TestData.block(2, "h2", "h1",
                TestData.tx("fail", "A", 100_000L, List.of()));
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(block(1, "h1", "g"));
            indexer.submitBlock(failingTip);
            // Undo the failing tip (first fsync of the reorg).
            indexer.walLog().append(new LogRecord.BlockUndone(2));
        }

        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(1, reopened.tipHeight());
            // Resubmit a healthy fork tip.
            RawBlock fork = TestData.block(2, "f2", "h1",
                    TestData.tx("ok", "A", 1, List.of("transfer C 1")));
            BlockSubmissionResult result = reopened.submitBlock(fork);
            assertEquals("extended", result.action());
            assertEquals(2, reopened.tipHeight());
            try (Snapshot snapshot = reopened.snapshot()) {
                assertTrue(snapshot.transactionsByHash("fail").isEmpty(),
                        "failing tx was in the undone block and must not reappear");
                assertFalse(snapshot.transactionsByHash("ok").isEmpty());
            }
        }
    }

    /**
     * Compaction triggered on every block (tiny threshold). After recovery the
     * index must match a full replay of the same block sequence row by row.
     */
    @Test
    void compactionThenRecoveryMatchesFullReplay(@TempDir Path compactDir,
                                                @TempDir Path fullDir) {
        List<RawBlock> blocks = buildChain();
        try (ChainIndexer indexer = new ChainIndexer(compactDir, 1024 * 1024,
                SMALL_COMPACTION_THRESHOLD)) {
            for (RawBlock block : blocks) {
                indexer.submitBlock(block);
            }
            assertEquals(blocks.size() - 1, indexer.tipHeight());
        }

        try (ChainIndexer full = new ChainIndexer(fullDir, 1024 * 1024, 1024 * 1024)) {
            for (RawBlock block : blocks) {
                full.submitBlock(block);
            }

            try (ChainIndexer recovered = new ChainIndexer(compactDir, 1024 * 1024,
                    SMALL_COMPACTION_THRESHOLD)) {
                IncrementalConsistencyTest.assertIndexEquivalent(recovered, full,
                        0, blocks.size() - 1);
                // Continue appending after recovery.
                RawBlock next = block(blocks.size(), "h" + blocks.size(),
                        "h" + (blocks.size() - 1));
                recovered.submitBlock(next);
                assertEquals(blocks.size(), recovered.tipHeight());
            }
        }
    }

    /**
     * Crash window: compacted segment written, manifest not yet updated.
     * Recovery must read the intact old segments and discard the orphaned
     * compacted file.
     */
    @Test
    void crashBeforeManifestCommitReadsOldSegments(@TempDir Path dir) throws Exception {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            for (long h = 1; h <= 5; h++) {
                indexer.submitBlock(block(h, "h" + h, "h" + (h - 1)));
            }
        }

        Path walDir = dir.resolve("wal");
        Path seg0 = walDir.resolve("seg-00000000.log");
        Path seg1 = walDir.resolve("seg-00000001.log");
        // Compacted segment is in place but the manifest still points at seg-0.
        Files.copy(seg0, seg1);

        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(5, reopened.tipHeight());
            assertTrue(Files.exists(seg0));
            assertFalse(Files.exists(seg1), "orphaned compacted segment must be removed");
            for (long h = 1; h <= 5; h++) {
                RawBlock next = block(5 + h, "h" + (5 + h), "h" + (4 + h));
                reopened.submitBlock(next);
            }
            assertEquals(10, reopened.tipHeight());
        }
    }

    /**
     * Crash window: manifest updated to the compacted segment, old segments not
     * yet deleted. Recovery must read the compacted segment and discard the
     * orphaned old segments.
     */
    @Test
    void crashAfterManifestCommitReadsCompactedSegment(@TempDir Path dir) throws Exception {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            for (long h = 1; h <= 5; h++) {
                indexer.submitBlock(block(h, "h" + h, "h" + (h - 1)));
            }
        }

        Path walDir = dir.resolve("wal");
        Path seg0 = walDir.resolve("seg-00000000.log");
        Path seg1 = walDir.resolve("seg-00000001.log");
        // Manifest commits to the compacted segment; old segment is left behind.
        Files.copy(seg0, seg1);
        Files.writeString(walDir.resolve("manifest"), "1\n2\n");

        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(5, reopened.tipHeight());
            assertFalse(Files.exists(seg0), "orphaned old segment must be removed");
            assertTrue(Files.exists(seg1));
            for (long h = 1; h <= 5; h++) {
                RawBlock next = block(5 + h, "h" + (5 + h), "h" + (4 + h));
                reopened.submitBlock(next);
            }
            assertEquals(10, reopened.tipHeight());
        }
    }

    /**
     * Crash window: temporary files (compaction temp, manifest temp) left behind.
     * Recovery must delete them and read the committed segments.
     */
    @Test
    void crashLeavingTempFilesCleansThemUp(@TempDir Path dir) throws Exception {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(block(1, "h1", "g"));
        }

        Path walDir = dir.resolve("wal");
        Files.writeString(walDir.resolve("compact-12345.tmp"), "partial");
        Files.writeString(walDir.resolve("manifest.tmp"), "partial");

        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(1, reopened.tipHeight());
            assertFalse(Files.exists(walDir.resolve("compact-12345.tmp")));
            assertFalse(Files.exists(walDir.resolve("manifest.tmp")));
        }
    }

    private List<RawBlock> buildChain() {
        List<RawBlock> chain = new java.util.ArrayList<>();
        chain.add(genesis());
        String prev = "g";
        for (long h = 1; h <= 8; h++) {
            String hash = "h" + h;
            chain.add(block(h, hash, prev));
            prev = hash;
        }
        return chain;
    }
}
