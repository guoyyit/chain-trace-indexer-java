package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.BlockSubmissionResult;
import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedTxReorgReproTest {

    private RawBlock genesis() {
        return TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 1000L)));
    }

    @Test
    void reorgAcrossFailedTxTip(@TempDir Path dir) {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            // block 1 contains a failing tx (value exceeds balance)
            indexer.submitBlock(TestData.block(1, "b1", "g",
                    TestData.tx("fail", "A", 10_000L, List.of())));
            indexer.submitBlock(TestData.block(2, "b2", "b1",
                    TestData.tx("ok", "A", 1, List.of("transfer C 1"))));

            // fork at tip height 2
            RawBlock fork = TestData.block(2, "f2", "b1",
                    TestData.tx("fork-ok", "A", 1, List.of("transfer D 1")));
            BlockSubmissionResult result = indexer.submitBlock(fork);
            assertEquals("reorganized", result.action());
            assertEquals(2, indexer.tipHeight());
            assertEquals("f2", indexer.tipHash());

            try (Snapshot snapshot = indexer.snapshot()) {
                assertFalse(snapshot.transactionsByHash("fail").isEmpty(),
                        "failed tx in shared prefix must remain");
                assertTrue(snapshot.transactionsByHash("ok").isEmpty(),
                        "old branch tx must be gone");
                assertFalse(snapshot.transactionsByHash("fork-ok").isEmpty());
            }
        }
    }

    @Test
    void reorgWhereForkBlockContainsFailedTx(@TempDir Path dir) {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(TestData.block(1, "b1", "g",
                    TestData.tx("ok", "A", 1, List.of("transfer C 1"))));

            // fork block at height 1 contains a failing tx
            RawBlock fork = TestData.block(1, "f1", "g",
                    TestData.tx("fail2", "A", 10_000L, List.of()));
            BlockSubmissionResult result = indexer.submitBlock(fork);
            assertEquals("reorganized", result.action());
            assertEquals(1, indexer.tipHeight());
            assertEquals("f1", indexer.tipHash());

            try (Snapshot snapshot = indexer.snapshot()) {
                assertTrue(snapshot.transactionsByHash("ok").isEmpty());
                assertFalse(snapshot.transactionsByHash("fail2").isEmpty());
            }
        }
    }
}
