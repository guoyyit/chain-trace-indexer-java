package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.Page;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.query.QueryService;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @Test
    void slowQueryDoesNotBlockIndexing(@TempDir Path dir) throws Exception {
        ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024);
        indexer.submitBlock(TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 10_000_000L))));
        indexer.submitBlock(TestData.block(1, "h1", "g",
                TestData.tx("tx-1", "A", 1, List.of("sset snapshot-key kept"))));

        Snapshot longLived = indexer.snapshot();
        try {
            for (long height = 2; height <= 30; height++) {
                indexer.submitBlock(TestData.block(height, "h" + height, "h" + (height - 1),
                        TestData.tx("tx-" + height, "A", 1, List.of())));
            }
            assertEquals(30, indexer.tipHeight());
            assertEquals(1, longLived.tipHeight());
            assertEquals("kept", longLived.block(1).transactions().get(0)
                    .changes().get(3).newValue());
        } finally {
            longLived.close();
        }
        indexer.close();
    }

    @Test
    void readersNeverObserveUncommittedState(@TempDir Path dir) throws Exception {
        ChainIndexer indexer = new ChainIndexer(dir, 1024 * 1024, 1024 * 1024);
        indexer.submitBlock(TestData.blockWithAllocations(0, "g", "genesis",
                List.of(TestData.alloc("A", 10_000_000L))));

        QueryService queries = new QueryService(indexer);
        int writerBlocks = 200;
        int readerCount = 6;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong observedTotal = new AtomicLong(-1);
        AtomicBoolean inconsistency = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);

        pool.submit(() -> {
            try {
                start.await();
                for (long height = 1; height <= writerBlocks; height++) {
                    indexer.submitBlock(TestData.block(height, "h" + height, "h" + (height - 1),
                            TestData.tx("tx-" + height, "A", 1,
                                    List.of("call B 1", "end"))));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                stop.set(true);
            }
        });

        for (int reader = 0; reader < readerCount; reader++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        try (Snapshot snapshot = indexer.snapshot()) {
                            long tip = snapshot.tipHeight();
                            if (tip >= 0 && !snapshot.containsHeight(tip)) {
                                inconsistency.set(true);
                            }
                            Page<StateChange> page = queries.queryChangesByAddress(
                                    "A", 0, Math.max(tip, 0), 0, 1);
                            if (observedTotal.get() >= 0 && tip == snapshot.tipHeight()) {
                                assertEquals(observedTotal.get(),
                                        queries.queryChangesByAddress(
                                                "A", 0, Math.max(tip, 0), 0, 1).total());
                            }
                            observedTotal.set(page.total());
                        }
                    }
                } catch (Exception ex) {
                    inconsistency.set(true);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(writerBlocks, indexer.tipHeight());
        try (Snapshot snapshot = indexer.snapshot()) {
            for (long height = 0; height <= writerBlocks; height++) {
                assertTrue(snapshot.containsHeight(height));
            }
            assertEquals(writerBlocks, snapshot.txCount("B", 0, writerBlocks));
        }
        indexer.close();
    }
}
