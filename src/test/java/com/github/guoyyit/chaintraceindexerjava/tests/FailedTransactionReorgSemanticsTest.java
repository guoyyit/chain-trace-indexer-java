package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedTransactionReorgSemanticsTest {

    private RawBlock genesis() {
        return TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 1_000_000L)));
    }

    private RawBlock failedThenOk(long height, String hash, String prev) {
        return TestData.block(height, hash, prev,
                TestData.tx("fail-" + height, "A", 50,
                        List.of("call B 10", "  sset k temp", "  revert boom", "  end")),
                TestData.tx("ok-" + height, "A", 5,
                        List.of("sset keep v" + height)));
    }

    @Test
    void failedTransactionsLeaveNothingBeforeAndAfterReorg(@TempDir Path dir)
            throws Exception {
        Path replay;
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, Long.MAX_VALUE)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(failedThenOk(1, "c1", "g"));
            indexer.submitBlock(failedThenOk(2, "c2", "c1"));
            indexer.submitBlock(failedThenOk(3, "c3", "c2"));

            assertFailedTxInvisible(indexer, "fail-1", "fail-2", "fail-3");

            // deep reorg: anchor at 1, replace blocks 2 and 3 on a fork
            indexer.submitBlock(failedThenOk(1, "c1", "g"));
            indexer.submitBlock(failedThenOk(2, "f2", "c1"));
            indexer.submitBlock(failedThenOk(3, "f3", "f2"));

            assertFailedTxInvisible(indexer, "fail-1", "fail-2", "fail-3");

            try (Snapshot snapshot = indexer.snapshot()) {
                // old branch gone, new branch present
                TransactionResult ok2 = snapshot.transaction("ok-2");
                assertNotNull(ok2);
                assertEquals("f2", snapshot.blockHash(2));
                assertNull(snapshot.transaction("nope"));
                // the successful transaction keeps exactly its own changes
                assertEquals(4, ok2.changes().size(),
                        "nonce + value-to-0x0 + one storage + sender balance?");
            }

            // record-for-record equality against a from-scratch replay of the new chain
            replay = java.nio.file.Files.createTempDirectory("semantics-replay");
            List<RawBlock> winning = List.of(genesis(),
                    failedThenOk(1, "c1", "g"),
                    failedThenOk(2, "f2", "c1"),
                    failedThenOk(3, "f3", "f2"));
            try (ChainIndexer fresh = new ChainIndexer(replay, 1024 * 1024, Long.MAX_VALUE)) {
                for (RawBlock raw : winning) {
                    fresh.submitBlock(raw);
                }
                try (Snapshot expected = fresh.snapshot();
                     Snapshot actual = indexer.snapshot()) {
                    assertEquals(describe(expected), describe(actual));
                }
            }

            // keep indexing on the same directory, then another single-block replace
            indexer.submitBlock(failedThenOk(4, "f4", "f3"));
           indexer.submitBlock(failedThenOk(4, "r4", "f3"));
           assertEquals("r4", indexer.tipHash());
           assertFailedTxInvisible(indexer, "fail-1", "fail-2", "fail-3", "fail-4");
       }

        // restart after reorgs crossing failed transactions must succeed and preserve state
        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, Long.MAX_VALUE)) {
            assertEquals(4, reopened.tipHeight());
            assertEquals("r4", reopened.tipHash());
            assertFailedTxInvisible(reopened, "fail-1", "fail-2", "fail-3", "fail-4");
            try (Snapshot snapshot = reopened.snapshot()) {
                assertEquals("f2", snapshot.blockHash(2));
                assertEquals("f3", snapshot.blockHash(3));
                assertNotNull(snapshot.transaction("ok-2"));
            }
        }
   }

    private void assertFailedTxInvisible(ChainIndexer indexer, String... hashes) {
        try (Snapshot snapshot = indexer.snapshot()) {
            for (String hash : hashes) {
                TransactionResult tx = snapshot.transaction(hash);
                // failed txs are indexed by identity but carry no trace and no changes,
                // and never appear in the address trace listing
                assertNotNull(tx, hash);
                assertTrue(!tx.success(), hash);
                assertNull(tx.trace(), hash);
                assertTrue(tx.changes().isEmpty(), hash);
                assertTrue(snapshot.flattenTrace(hash).isEmpty(), hash);
                assertTrue(snapshot.transactionsByAddress("A", 0, snapshot.tipHeight())
                        .stream().noneMatch(ref -> ref.txHash().equals(hash)), hash);
                assertTrue(snapshot.transactionsByAddress("B", 0, snapshot.tipHeight())
                        .stream().noneMatch(ref -> ref.txHash().equals(hash)), hash);
            }
        }
    }

    private List<String> describe(Snapshot snapshot) {
        List<String> lines = new ArrayList<>();
        for (long height : snapshot.heights()) {
            var block = snapshot.block(height);
            lines.add("block " + height + " " + block.hash());
            for (StateChange change : block.allocations()) {
                lines.add("alloc " + change.address() + " " + change.newValue());
            }
            for (TransactionResult tx : block.transactions()) {
                lines.add("tx " + tx.hash() + " applied=" + tx.applied()
                        + " success=" + tx.success()
                        + " frames=" + snapshot.flattenTrace(tx.hash()).size()
                        + " changes=" + tx.changes().size());
                for (StateChange change : tx.changes()) {
                    lines.add("  " + change.address() + " " + change.type()
                            + " " + change.key() + " " + change.oldValue()
                            + "->" + change.newValue() + " d" + change.depth());
                }
            }
        }
        return List.copyOf(lines);
    }
}
