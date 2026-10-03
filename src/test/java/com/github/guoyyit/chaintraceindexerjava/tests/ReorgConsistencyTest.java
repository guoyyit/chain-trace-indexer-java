package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.BlockSubmissionResult;
import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveService;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.store.LogRecord;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reorg correctness: failed transactions, storage deletions, and deep reorgs
 * must produce an index identical to a full replay of the surviving chain.
 */
class ReorgConsistencyTest {

    private RawBlock genesis() {
        return TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 1000L)));
    }

    /**
     * A reorg that crosses a block containing a failing transaction must end
     * with exactly the same index as a full replay of the surviving chain.
     */
    @Test
    void reorgWithFailedTxMatchesFullReplay(@TempDir Path reorgDir,
                                            @TempDir Path fullDir) {
        RawBlock block1 = TestData.block(1, "b1", "g",
                TestData.tx("fail", "A", 10_000L, List.of()));
        RawBlock block2 = TestData.block(2, "b2", "b1",
                TestData.tx("ok", "A", 1, List.of("transfer C 1")));
        RawBlock fork2 = TestData.block(2, "f2", "b1",
                TestData.tx("fork-ok", "A", 1, List.of("transfer D 1")));

        try (ChainIndexer reorg = new ChainIndexer(reorgDir, 1024 * 1024, 1024 * 1024)) {
            reorg.submitBlock(genesis());
            reorg.submitBlock(block1);
            reorg.submitBlock(block2);
            BlockSubmissionResult result = reorg.submitBlock(fork2);
            assertEquals("reorganized", result.action());
        }

        try (ChainIndexer full = new ChainIndexer(fullDir, 1024 * 1024, 1024 * 1024)) {
            full.submitBlock(genesis());
            full.submitBlock(block1);
            full.submitBlock(fork2);
        }

        try (ChainIndexer reorg = new ChainIndexer(reorgDir, 1024 * 1024, 1024 * 1024);
             ChainIndexer full = new ChainIndexer(fullDir, 1024 * 1024, 1024 * 1024)) {
            IncrementalConsistencyTest.assertIndexEquivalent(reorg, full, 0, 2);
        }
    }

    /**
     * A storage deletion in the shared prefix must be replayed during world-state
     * rebuild after the reorg. A write to the same key in the new branch must
     * observe the deleted value (null), not the stale pre-deletion value.
     */
    @Test
    void storageDeleteReorgObservesCorrectPreValue(@TempDir Path dir) {
        RawBlock block1 = TestData.block(1, "b1", "g",
                TestData.tx("set", "A", 0, List.of("sset k v1")));
        RawBlock block2 = TestData.block(2, "b2", "b1",
                TestData.tx("del", "A", 0, List.of("sdel k")));
        RawBlock block3 = TestData.block(3, "b3", "b2",
                TestData.tx("set3", "A", 0, List.of("sset k v3")));
        RawBlock fork3 = TestData.block(3, "f3", "b2",
                TestData.tx("set3b", "A", 0, List.of("sset k v3b")));

        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(block1);
            indexer.submitBlock(block2);
            indexer.submitBlock(block3);
            indexer.submitBlock(fork3);

            try (Snapshot snapshot = indexer.snapshot()) {
                TransactionResult set3b = snapshot.transaction("set3b");
                StateChange change = null;
                for (StateChange c : set3b.changes()) {
                    if (c.type() == com.github.guoyyit.chaintraceindexerjava.model.ChangeType.STORAGE) {
                        change = c;
                        break;
                    }
                }
                assertTrue(change != null, "storage change must exist");
                assertEquals("k", change.key());
                assertNull(change.oldValue(),
                        "deleted key must not leave a stale value after rebuild");
                assertEquals("v3b", change.newValue());
            }
        }
    }

    /**
     * After a crash during a reorg, recovery must resume from the last committed
     * position. A subsequent deep reorg (rollback to a lower height, then extend)
     * must work and match a full replay of the surviving chain.
     */
    @Test
    void deepReorgAfterRecoveryMatchesFullReplay(@TempDir Path reorgDir,
                                                 @TempDir Path fullDir) {
        RawBlock[] blocks = new RawBlock[6];
        blocks[0] = genesis();
        String prev = "g";
        for (int h = 1; h <= 5; h++) {
            blocks[h] = TestData.block(h, "h" + h, prev,
                    TestData.tx("tx-" + h, "A", 1, List.of("transfer B 1")));
            prev = "h" + h;
        }

        try (ChainIndexer reorg = new ChainIndexer(reorgDir, 1024 * 1024, 1024 * 1024)) {
            for (RawBlock block : blocks) {
                reorg.submitBlock(block);
            }
            // Simulate a crash during a reorg at the tip: the undo fsync landed.
            reorg.walLog().append(new LogRecord.BlockUndone(5));
        }

        RawBlock fork4 = TestData.block(4, "f4", "h3",
                TestData.tx("ftx-4", "A", 1, List.of("transfer C 1")));
        RawBlock fork5 = TestData.block(5, "f5", "f4",
                TestData.tx("ftx-5", "A", 1, List.of("transfer C 1")));

        try (ChainIndexer full = new ChainIndexer(fullDir, 1024 * 1024, 1024 * 1024)) {
            full.submitBlock(blocks[0]);
            full.submitBlock(blocks[1]);
            full.submitBlock(blocks[2]);
            full.submitBlock(blocks[3]);
            full.submitBlock(fork4);
            full.submitBlock(fork5);
        }

        try (ChainIndexer reorg = new ChainIndexer(reorgDir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(4, reorg.tipHeight(), "recovered head is at the undone tip-1");
            // Deep reorg: roll back to height 3, then extend the new branch.
            BlockSubmissionResult rollback = reorg.submitBlock(blocks[3]);
            assertEquals("rolled_back", rollback.action());
            reorg.submitBlock(fork4);
            reorg.submitBlock(fork5);
            assertEquals(5, reorg.tipHeight());
            assertEquals("f5", reorg.tipHash());

            try (Snapshot snapshot = reorg.snapshot()) {
                assertTrue(snapshot.transactionsByHash("tx-4").isEmpty());
                assertTrue(snapshot.transactionsByHash("tx-5").isEmpty());
                assertTrue(snapshot.transactionsByHash("ftx-4").isEmpty() == false);
                assertTrue(snapshot.transactionsByHash("ftx-5").isEmpty() == false);
            }
        }

        try (ChainIndexer reorg = new ChainIndexer(reorgDir, 1024 * 1024, 1024 * 1024);
             ChainIndexer full = new ChainIndexer(fullDir, 1024 * 1024, 1024 * 1024)) {
            IncrementalConsistencyTest.assertIndexEquivalent(reorg, full, 0, 5);
        }
    }

    /**
     * A reorg that undoes a hot block while an archived block in the shared prefix
     * contains a failing transaction must not throw. The archived failing tx remains
     * queryable (it is still in the surviving chain).
     */
    @Test
    void reorgAcrossArchivedBlockWithFailedTx(@TempDir Path dir) {
        RawBlock block1 = TestData.block(1, "b1", "g",
                TestData.tx("fail", "A", 10_000L, List.of()));
        RawBlock block2 = TestData.block(2, "b2", "b1",
                TestData.tx("ok", "A", 1, List.of("transfer C 1")));
        RawBlock fork2 = TestData.block(2, "f2", "b1",
                TestData.tx("fork-ok", "A", 1, List.of("transfer D 1")));

        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(block1);
            indexer.submitBlock(block2);
            // Archive the shared prefix (includes the failing tx in block 1).
            new ArchiveService(indexer).archiveUpTo(1);

            BlockSubmissionResult result = indexer.submitBlock(fork2);
            assertEquals("reorganized", result.action());
            assertEquals(2, indexer.tipHeight());
            assertEquals("f2", indexer.tipHash());

            try (Snapshot snapshot = indexer.snapshot()) {
                // The failing tx in the archived shared prefix is still present.
                assertTrue(snapshot.transactionsByHash("fail").isEmpty() == false);
                // The old branch tx is gone.
                assertTrue(snapshot.transactionsByHash("ok").isEmpty());
                // The new branch tx is present.
                assertTrue(snapshot.transactionsByHash("fork-ok").isEmpty() == false);
            }
        }
    }
}
