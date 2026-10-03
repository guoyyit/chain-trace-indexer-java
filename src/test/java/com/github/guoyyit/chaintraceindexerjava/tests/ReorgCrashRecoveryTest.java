package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import com.github.guoyyit.chaintraceindexerjava.store.WalLog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Kill at every WAL append boundary of a deep reorg crossing blocks that contain failed
 * transactions, then reopen and drive the same fork to completion. The final index must be
 * record-for-record identical to a fresh full replay of the winning chain, with no
 * duplicates and no missing blocks.
 */
class ReorgCrashRecoveryTest {

    private static final int SEGMENT_BYTES = 1024 * 1024;

    private RawBlock genesis(String hash) {
        return TestData.blockWithAllocations(0, hash, "genesis",
                List.of(TestData.alloc("A", 1_000_000L),
                        TestData.alloc("B", 500_000L)));
    }

    private RawBlock block(long height, String hash, String prev, String prefix) {
        RawTransaction[] txs;
        if (height == 2 || height == 4) {
            // failed transaction must leave nothing committed, plus a successful tx
            txs = new RawTransaction[]{
                    TestData.tx(prefix + "-fail-" + height, "A", 1,
                            List.of("call C 10", "  revert boom", "  end")),
                    TestData.tx(prefix + "-ok-" + height, "A", 1,
                            List.of("sset " + prefix + " v" + height))
            };
        } else {
            txs = new RawTransaction[]{
                    TestData.tx(prefix + "-ok-" + height, "A", 1,
                            List.of("call B 3", "  sset " + prefix + " v" + height, "  end"))
            };
        }
        return TestData.block(height, hash, prev, txs);
    }

    private List<RawBlock> canonicalChain() {
        List<RawBlock> chain = new ArrayList<>();
        chain.add(genesis("g"));
        for (long height = 1; height <= 5; height++) {
            chain.add(block(height, "c" + height,
                    height == 1 ? "g" : "c" + (height - 1), "c"));
        }
        return chain;
    }

    /** Deep fork anchored at height 1: submit same-hash block at 1, then fork blocks 2..5. */
    private List<RawBlock> winningForkSubmissions() {
        List<RawBlock> submissions = new ArrayList<>();
        submissions.add(block(1, "c1", "g", "c")); // anchor: rolled_back
        for (long height = 2; height <= 5; height++) {
            String prev = height == 2 ? "c1" : "f" + (height - 1);
            submissions.add(block(height, "f" + height, prev, "f"));
        }
        return submissions;
    }

    @ParameterizedTest(name = "kill after {0} reorg appends")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    void killAtEveryReorgBoundaryResumesExactly(long killAfter, @TempDir Path dir) {
        List<RawBlock> canonical = canonicalChain();
        List<RawBlock> fork = winningForkSubmissions();

        try (ChainIndexer indexer = new ChainIndexer(dir, SEGMENT_BYTES, Long.MAX_VALUE)) {
            for (RawBlock raw : canonical) {
                indexer.submitBlock(raw);
            }
            indexer.walLog().armKillAfterAppends(killAfter);
            assertThrows(WalLog.SimulatedProcessKill.class,
                    () -> submitFork(indexer, fork));
        }

        assertDoesNotThrow(() -> {
            try (ChainIndexer reopened = new ChainIndexer(dir, SEGMENT_BYTES, Long.MAX_VALUE)) {
                finishFork(reopened, fork);

                assertEquals(5, reopened.tipHeight());
                assertEquals("f5", reopened.tipHash());

                List<RawBlock> expectedChain = new ArrayList<>();
                expectedChain.add(genesis("g"));
                expectedChain.add(block(1, "c1", "g", "c"));
                for (long height = 2; height <= 5; height++) {
                    String prev = height == 2 ? "c1" : "f" + (height - 1);
                    expectedChain.add(block(height, "f" + height, prev, "f"));
                }
                assertIndexMatchesFullReplay(reopened, expectedChain);

                // continue indexing on the same data directory
                reopened.submitBlock(block(6, "f6", "f5", "f"));
                assertEquals(6, reopened.tipHeight());
            }
        });
    }

    private void submitFork(ChainIndexer indexer, List<RawBlock> fork) {
        for (RawBlock raw : fork) {
            indexer.submitBlock(raw);
        }
    }

   /**
     * Resumes an interrupted fork. The client resends the fork sequence from its start:
     * same-hash blocks at/below the tip are idempotent anchors that roll the chain back,
     * and once the tip catches up the remaining blocks extend the fork. The loop repeats
     * because blocks ahead of the current tip are skipped early on.
     */
   private void finishFork(ChainIndexer indexer, List<RawBlock> fork) {
        for (int pass = 0; pass <= fork.size(); pass++) {
            if (indexer.tipHeight() == 5 && "f5".equals(indexer.tipHash())) {
                return;
            }
            for (RawBlock raw : fork) {
                try {
                    indexer.submitBlock(raw);
                } catch (com.github.guoyyit.chaintraceindexerjava.indexer
                        .ChainIndexerException.MissingParent
                        | com.github.guoyyit.chaintraceindexerjava.indexer
                        .ChainIndexerException.NonSequentialHeight ex) {
                    // too far ahead of the current tip; earlier fork blocks run first
                }
            }
        }
        assertEquals("f5", indexer.tipHash(), "fork failed to converge");
    }

    private void assertIndexMatchesFullReplay(ChainIndexer indexer, List<RawBlock> expected) {
        Path temp;
        try {
            temp = java.nio.file.Files.createTempDirectory("reorg-replay");
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
        temp.toFile().deleteOnExit();
        List<String> expectedTraces;
        List<String> expectedChanges;
        try (ChainIndexer fresh = new ChainIndexer(temp, SEGMENT_BYTES, Long.MAX_VALUE)) {
            for (RawBlock raw : expected) {
                fresh.submitBlock(raw);
            }
            try (Snapshot snapshot = fresh.snapshot()) {
                expectedTraces = traceLines(snapshot);
                expectedChanges = changeLines(snapshot);
            }
        }
        try (Snapshot snapshot = indexer.snapshot()) {
            assertEquals(expectedTraces, traceLines(snapshot), "trace mismatch");
            assertEquals(expectedChanges, changeLines(snapshot), "change mismatch");
            // no duplicate transaction records
            for (long height : snapshot.heights()) {
                var block = snapshot.block(height);
                var hashes = block.transactions().stream().map(TransactionResult::hash).toList();
                assertEquals(hashes.size(), new java.util.HashSet<>(hashes).size(),
                        "duplicate tx at height " + height);
            }
        }
    }

    private List<String> traceLines(Snapshot snapshot) {
        List<String> lines = new ArrayList<>();
        for (long height : snapshot.heights()) {
            var block = snapshot.block(height);
            for (TransactionResult tx : block.transactions()) {
                lines.add(height + "/" + tx.hash() + ":applied=" + tx.applied()
                        + ":success=" + tx.success()
                        + ":frames=" + snapshot.flattenTrace(tx.hash()).size());
            }
        }
        return List.copyOf(lines);
    }

    private List<String> changeLines(Snapshot snapshot) {
        List<String> lines = new ArrayList<>();
        for (long height : snapshot.heights()) {
            var block = snapshot.block(height);
            for (StateChange change : block.allocations()) {
                lines.add(height + "/alloc:" + change.address() + ":" + change.type()
                        + ":" + change.key() + ":" + change.depth()
                        + ":" + change.oldValue() + "->" + change.newValue());
            }
            for (TransactionResult tx : block.transactions()) {
                for (StateChange change : tx.changes()) {
                    lines.add(height + "/" + tx.hash() + ":" + change.address()
                            + ":" + change.type() + ":" + change.key() + ":" + change.depth()
                            + ":" + change.oldValue() + "->" + change.newValue());
                }
            }
        }
        return List.copyOf(lines);
    }
}
