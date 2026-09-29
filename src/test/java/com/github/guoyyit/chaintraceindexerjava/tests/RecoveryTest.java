package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveService;
import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    private RawBlock genesis() {
        return TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 1_000_000L)));
    }

    @Test
    void restartResumesFromLastCommittedHeight(@TempDir Path dir) {
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            for (long height = 1; height <= 10; height++) {
                indexer.submitBlock(TestData.block(height, "h" + height, "h" + (height - 1),
                        TestData.tx("tx-" + height, "A", 1,
                                List.of("call B 1", "end"))));
            }
            new ArchiveService(indexer).archiveUpTo(4);
            assertEquals(10, indexer.tipHeight());
        }

        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(10, reopened.tipHeight());
            assertEquals("h10", reopened.tipHash());
            try (Snapshot snapshot = reopened.snapshot()) {
                for (long height = 0; height <= 10; height++) {
                    assertTrue(snapshot.containsHeight(height), "height " + height);
                }
                assertEquals("g", snapshot.block(0).hash());
                assertEquals(1, snapshot.block(10).transactions().size());
                assertTrue(snapshot.archivedHandle(4) != null);
            }

            reopened.submitBlock(TestData.block(11, "h11", "h10",
                    TestData.tx("tx-11", "A", 1, List.of())));
            assertEquals(11, reopened.tipHeight());
        }

        try (ChainIndexer third = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(11, third.tipHeight());
            try (Snapshot snapshot = third.snapshot()) {
                assertEquals(1, snapshot.block(11).transactions().size());
            }
        }
    }

    @Test
    void noPartialWritesAfterCrash(@TempDir Path dir) throws Exception {
        Path walDirectory = dir.resolve("wal");
        try (ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            indexer.submitBlock(genesis());
            indexer.submitBlock(TestData.block(1, "h1", "g"));
        }

        Path segment = walDirectory.resolve("seg-00000000.log");
        java.io.File[] files = walDirectory.toFile().listFiles((d, name) ->
                name.startsWith("seg-") && name.endsWith(".log"));
        Path lastSegment = files[files.length - 1].toPath();
        long originalSize = java.nio.file.Files.size(lastSegment);

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lastSegment.toFile(), "rw")) {
            raf.setLength(originalSize - 1);
        }

        try (ChainIndexer reopened = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024)) {
            assertEquals(0, reopened.tipHeight());
            assertEquals("g", reopened.tipHash());
            reopened.submitBlock(TestData.block(1, "h1", "g"));
            assertEquals(1, reopened.tipHeight());
        }
    }
}
