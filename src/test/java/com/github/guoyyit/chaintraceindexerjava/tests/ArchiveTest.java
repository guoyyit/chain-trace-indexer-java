package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveResult;
import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveService;
import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.Page;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.query.QueryService;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveTest {

    @Test
    void queriesStillHitArchivedHistory(@TempDir Path dir) {
        ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024);
        indexer.submitBlock(TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 1_000_000L))));
        for (long height = 1; height <= 8; height++) {
            indexer.submitBlock(TestData.block(height, "h" + height, "h" + (height - 1),
                    TestData.tx("tx-" + height, "A", 2,
                            List.of("call B 1", "  sset k v" + height, "  end"))));
        }

        ArchiveResult result = new ArchiveService(indexer).archiveUpTo(5);
        assertEquals(6, result.archivedBlocks());

        QueryService queries = new QueryService(indexer);
        Page<StateChange> changes = queries.queryChangesByAddress("A", 0, 8, 0, 100);
        assertTrue(changes.total() > 0);
        assertEquals(changes.total(),
                queries.queryChangesByAddress("A", 0, 8, 0, 100).total());

        Page<StateChange> coldPage = queries.queryChangesByAddress("B", 0, 5, 0, 100);
        assertTrue(coldPage.total() > 0);

        QueryService.TraceByHashView view = queries.queryTraceByHash("tx-3", 0, 100, 0, 100);
        assertNotNull(view);
        assertEquals(3, view.height());
        assertTrue(view.tracePage().total() >= 2);
        assertTrue(view.changes().total() > 0);

        try (Snapshot snapshot = indexer.snapshot()) {
            assertNotNull(snapshot.archivedHandle(3));
            assertNull(snapshot.archivedHandle(7));
            assertEquals("h3", snapshot.block(3).hash());
        }

        indexer.submitBlock(TestData.block(9, "h9", "h8",
                TestData.tx("tx-9", "A", 1, List.of())));
        Page<TransactionResult> traces =
                queries.queryTracesByAddress("B", 0, 9, 0, 100);
        assertEquals(8, traces.total());

        indexer.close();
    }

    @Test
    void gcKeepsObjectsReachableFromOldSnapshots(@TempDir Path dir) {
        ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024);
        indexer.submitBlock(TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 1_000_000L))));
        indexer.submitBlock(TestData.block(1, "h1", "g",
                TestData.tx("tx-1", "A", 1, List.of("sset old true"))));

        ArchiveService archive = new ArchiveService(indexer);
        archive.archiveUpTo(1);

        Snapshot oldSnapshot = indexer.snapshot();
        try {
            indexer.submitBlock(TestData.block(1, "f1", "g",
                    TestData.tx("tx-x", "A", 1, List.of("sset new true"))));
            indexer.submitBlock(TestData.block(2, "f2", "f1"));
            archive.archiveUpTo(2);

            assertEquals("h1", oldSnapshot.blockHash(1));
            assertNotNull(oldSnapshot.block(1));
        } finally {
            oldSnapshot.close();
        }
        indexer.close();
    }
}
