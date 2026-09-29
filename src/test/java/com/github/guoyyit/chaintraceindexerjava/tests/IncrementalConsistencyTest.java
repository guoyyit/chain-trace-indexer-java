package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IncrementalConsistencyTest {

    private List<RawBlock> buildChain() {
        List<RawBlock> chain = new ArrayList<>();
        chain.add(TestData.blockWithAllocations(0, "b0", "genesis",
                List.of(TestData.alloc("A", 10_000L), TestData.alloc("B", 5_000L)),
                TestData.tx("t0", "A", 100,
                        List.of("call B 40", "end"))));
        String prev = "b0";
        for (long height = 1; height <= 20; height++) {
            String hash = "b" + height;
            RawTransaction tx1 = TestData.tx("t" + height + "a", "A", 10,
                    List.of("call B " + (height % 5), "  sset k v" + height, "  end"));
            RawTransaction tx2 = TestData.tx("t" + height + "b", "B", 2,
                    List.of("transfer C 1"));
            chain.add(TestData.block(height, hash, prev, tx1, tx2));
            prev = hash;
        }
        return chain;
    }

    @Test
    void incrementalEqualsFullReplayRowByRow(@TempDir Path incrementalDir,
                                             @TempDir Path fullDir) {
        List<RawBlock> blocks = buildChain();

        ChainIndexer full = new ChainIndexer(fullDir, 1024 * 1024, 1024 * 1024);
        ChainIndexer incremental = new ChainIndexer(incrementalDir, 1024 * 1024, 1024 * 1024);
        for (RawBlock block : blocks) {
            incremental.submitBlock(block);
        }
        for (RawBlock block : blocks) {
            full.submitBlock(block);
        }

        assertIndexEquivalent(incremental, full, 0, 20);
        full.close();
        incremental.close();
    }

    static void assertIndexEquivalent(ChainIndexer left, ChainIndexer right,
                                      long fromHeight, long toHeight) {
        try (Snapshot a = left.snapshot(); Snapshot b = right.snapshot()) {
            assertEquals(toHeight, a.tipHeight());
            assertEquals(toHeight, b.tipHeight());
            for (long height = fromHeight; height <= toHeight; height++) {
                BlockResult ba = a.block(height);
                BlockResult bb = b.block(height);
                assertEquals(ba.hash(), bb.hash());
                assertEquals(ba.allocations().size(), bb.allocations().size());
                assertEquals(ba.transactions().size(), bb.transactions().size());
                for (int txIndex = 0; txIndex < ba.transactions().size(); txIndex++) {
                    TransactionResult ta = ba.transactions().get(txIndex);
                    TransactionResult tb = bb.transactions().get(txIndex);
                    assertEquals(ta.hash(), tb.hash());
                    assertEquals(ta.applied(), tb.applied());
                    assertEquals(ta.success(), tb.success());
                    assertEquals(ta.changes().size(), tb.changes().size());
                    for (int c = 0; c < ta.changes().size(); c++) {
                        StateChange ca = ta.changes().get(c);
                        StateChange cb = tb.changes().get(c);
                        assertEquals(ca.address(), cb.address());
                        assertEquals(ca.type(), cb.type());
                        assertEquals(ca.oldValue(), cb.oldValue());
                        assertEquals(ca.newValue(), cb.newValue());
                        assertEquals(ca.depth(), cb.depth());
                    }
                    assertEquals(stringify(ta.trace()), stringify(tb.trace()));
                }
            }
            for (String address : List.of("A", "B", "C", "0x0")) {
                assertEquals(a.txCount(address, fromHeight, toHeight),
                        b.txCount(address, fromHeight, toHeight), "tx count " + address);
                assertEquals(a.changeCount(address, fromHeight, toHeight),
                        b.changeCount(address, fromHeight, toHeight), "change count " + address);
            }
        }
    }

    static String stringify(Object value) {
        return value == null ? "null" : value.toString();
    }
}
