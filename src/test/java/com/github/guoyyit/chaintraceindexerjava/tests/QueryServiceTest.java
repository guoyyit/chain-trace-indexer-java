package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.Page;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.query.QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryServiceTest {

    private ChainIndexer seed(Path dir, long blocks) {
        ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024);
        indexer.submitBlock(TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 100_000_000L))));
        for (long height = 1; height <= blocks; height++) {
            indexer.submitBlock(TestData.block(height, "h" + height, "h" + (height - 1),
                    TestData.tx("tx-" + height, "A", 1,
                            List.of("call B 1", "  sset k" + (height % 3) + " v" + height, "  end"))));
        }
        return indexer;
    }

    @Test
    void queriesAreStableAcrossIndexAdvance(@TempDir Path dir) {
        ChainIndexer indexer = seed(dir, 10);
        QueryService queries = new QueryService(indexer);

        Page<StateChange> before = queries.queryChangesByAddress("B", 0, 10, 0, 1000);
        List<StateChange> snapshotRows = new ArrayList<>(before.items());
        long beforeTotal = before.total();

        for (long height = 11; height <= 30; height++) {
            indexer.submitBlock(TestData.block(height, "h" + height, "h" + (height - 1),
                    TestData.tx("tx-" + height, "A", 1,
                            List.of("call C 1", "end"))));
        }

        Page<StateChange> sameRangeAfter = queries.queryChangesByAddress("B", 0, 10, 0, 1000);
        assertEquals(beforeTotal, sameRangeAfter.total());
        assertEquals(snapshotRows.size(), sameRangeAfter.items().size());
        for (int i = 0; i < snapshotRows.size(); i++) {
            assertEquals(snapshotRows.get(i).newValue(),
                    sameRangeAfter.items().get(i).newValue());
        }

        assertNull(queries.queryTraceByHash("missing", 0, 10, 0, 10));
    }

    @Test
    void paginationReturnsAllRowsInOrder(@TempDir Path dir) {
        ChainIndexer indexer = seed(dir, 20);
        QueryService queries = new QueryService(indexer);

        List<StateChange> aggregated = new ArrayList<>();
        long offset = 0;
        int limit = 3;
        boolean hasMore = true;
        while (hasMore) {
            Page<StateChange> page = queries.queryChangesByAddress("A", 0, 20, offset, limit);
            aggregated.addAll(page.items());
            hasMore = page.hasMore();
            offset += page.items().size();
            if (hasMore) {
                assertTrue(page.items().size() <= limit);
            }
        }

        Page<StateChange> full = queries.queryChangesByAddress("A", 0, 20, 0, 1000);
        assertEquals(full.total(), aggregated.size());
        assertEquals(aggregated, full.items());

        Page<TransactionResult> txPage = queries.queryTracesByAddress("B", 5, 15, 0, 4);
        assertTrue(txPage.hasMore());
        assertEquals(4, txPage.items().size());
        assertEquals(11, txPage.total());

        QueryService.TraceByHashView view = queries.queryTraceByHash("tx-7", 0, 1, 0, 1);
        assertEquals(1, view.tracePage().items().size());
        assertTrue(view.tracePage().hasMore());
        assertEquals(1, view.changes().items().size());

        Page<StateChange> beyond = queries.queryChangesByAddress("A", 0, 20, 999_999, 10);
        assertTrue(beyond.items().isEmpty());
        assertFalse(beyond.hasMore());
        indexer.close();
    }
}
