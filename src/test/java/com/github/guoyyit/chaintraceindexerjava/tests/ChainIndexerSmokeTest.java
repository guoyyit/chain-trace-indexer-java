package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.BlockSubmissionResult;
import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainIndexerSmokeTest {

    @Test
    void submitThenQueryAndRecover(@TempDir Path dir) {
        RawBlock b1 = TestData.blockWithAllocations(0, "g", "0xgenesis",
                List.of(TestData.alloc("A", 1_000L)),
                TestData.tx("t1", "A", 100, List.of("call B 40", "end")));
        RawBlock b2 = TestData.block(1, "h1", "g",
                TestData.tx("t2", "B", 0, List.of("transfer C 10")),
                TestData.tx("t1", "A", 1, List.of("transfer C 5")));

        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            BlockSubmissionResult r1 = indexer.submitBlock(b1);
            assertEquals("extended", r1.action());
            BlockSubmissionResult r2 = indexer.submitBlock(b2);
            assertEquals("extended", r2.action());
            assertEquals(1, indexer.tipHeight());

            try (Snapshot snapshot = indexer.snapshot()) {
                BlockResult block1 = snapshot.block(1);
                TransactionResult duplicate = block1.transactions().get(1);
                assertFalse(duplicate.applied());
                assertTrue(block1.transactions().get(0).applied());
            }
        }

        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(1, reopened.tipHeight());
            assertEquals("h1", reopened.tipHash());
            try (Snapshot snapshot = reopened.snapshot()) {
                BlockResult block1 = snapshot.block(1);
                assertFalse(block1.transactions().get(1).applied());
            }
        }
    }
}
