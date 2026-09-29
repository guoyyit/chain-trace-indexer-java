package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveResult;
import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReorgTest {

    private RawBlock canonicalBlock(long height, String prev) {
        return TestData.block(height, "c" + height, prev,
                TestData.tx("c-tx-" + height, "A", 1,
                        List.of("sset canonical v" + height)));
    }

    private RawBlock forkBlock(long height, String prev) {
        return TestData.block(height, "f" + height, prev,
                TestData.tx("f-tx-" + height, "A", 1,
                        List.of("sset fork v" + height)));
    }

    private RawBlock genesis() {
        return TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 100_000L)));
    }

    @Test
    void reorgUndoesBranchAndKeepsSharedPrefix(@TempDir Path dir) {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(canonicalBlock(1, "g"));
            indexer.submitBlock(canonicalBlock(2, "c1"));
            indexer.submitBlock(canonicalBlock(3, "c2"));

            BlockSubmissionResult result = indexer.submitBlock(forkBlock(3, "c2"));
            assertEquals("reorganized", result.action());
            assertEquals(3, indexer.tipHeight());
            assertEquals("f3", indexer.tipHash());

            try (Snapshot snapshot = indexer.snapshot()) {
                assertEquals("c2", snapshot.blockHash(2));
                assertEquals("f3", snapshot.blockHash(3));
                assertEquals("c2", snapshot.blockHash(2));
                assertFalse(snapshot.containsHeight(99));
                assertTrue(snapshot.transactionsByHash("c-tx-3").isEmpty());
                assertFalse(snapshot.transactionsByHash("f-tx-3").isEmpty());
                assertFalse(snapshot.transactionsByHash("c-tx-2").isEmpty());
            }

            indexer.submitBlock(forkBlock(4, "f3"));
            try (Snapshot snapshot = indexer.snapshot()) {
                assertEquals("f4", snapshot.blockHash(4));
                assertTrue(snapshot.transactionsByHash("c-tx-3").isEmpty());
            }
        }
    }

    @Test
    void fullArchiveThenGenesisReorg(@TempDir Path dir) {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            for (long height = 1; height <= 4; height++) {
                indexer.submitBlock(canonicalBlock(height, "c" + (height - 1)));
            }
            new ArchiveService(indexer).archiveUpTo(4);

            RawBlock newGenesis = TestData.blockWithAllocations(0, "g2", "genesis",
                    List.of(TestData.alloc("A", 100_000L), TestData.alloc("Z", 1L)));
            indexer.submitBlock(newGenesis);

            try (Snapshot snapshot = indexer.snapshot()) {
                assertEquals(0, snapshot.tipHeight());
                assertEquals("g2", snapshot.blockHash(0));
                assertTrue(snapshot.transactionsByHash("c-tx-1").isEmpty());
            }

            indexer.submitBlock(forkBlock(1, "g2"));
            try (Snapshot snapshot = indexer.snapshot()) {
                assertEquals(1, snapshot.tipHeight());
                assertEquals("f1", snapshot.blockHash(1));
                assertTrue(snapshot.transactionsByHash("c-tx-1").isEmpty());
                assertFalse(snapshot.transactionsByHash("f-tx-1").isEmpty());
            }
        }
    }

    @Test
    void reorgAcrossArchivedBoundaryIsCorrect(@TempDir Path dir) {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            for (long height = 1; height <= 5; height++) {
                indexer.submitBlock(canonicalBlock(height, "c" + (height - 1)));
            }
            ArchiveService archive = new ArchiveService(indexer);
            ArchiveResult archived = archive.archiveUpTo(2);
            assertEquals(3, archived.archivedBlocks());

            try (Snapshot snapshot = indexer.snapshot()) {
                assertTrue(snapshot.archivedHandle(0) != null);
                assertTrue(snapshot.archivedHandle(1) != null);
                assertTrue(snapshot.archivedHandle(2) != null);
                assertEquals("v2", snapshot.block(2).transactions().get(0)
                        .changes().get(3).newValue());
            }

            indexer.submitBlock(canonicalBlock(2, "c1"));
            indexer.submitBlock(forkBlock(3, "c2"));
            indexer.submitBlock(forkBlock(4, "f3"));
            indexer.submitBlock(forkBlock(5, "f4"));
            indexer.submitBlock(forkBlock(6, "f5"));

            try (Snapshot snapshot = indexer.snapshot()) {
                assertEquals(6, snapshot.tipHeight());
                assertEquals("g", snapshot.blockHash(0));
                assertEquals("c2", snapshot.blockHash(2));
                assertEquals("f5", snapshot.blockHash(5));
                assertEquals("f6", snapshot.blockHash(6));
                assertEquals("f3", snapshot.blockHash(3));
                assertEquals("c2", snapshot.blockHash(2));
                assertTrue(snapshot.transactionsByHash("c-tx-5").isEmpty());
                assertFalse(snapshot.transactionsByHash("f-tx-5").isEmpty());
                assertEquals("v2", snapshot.block(2).transactions().get(0)
                        .changes().get(3).newValue());
            }
        }
    }
}
