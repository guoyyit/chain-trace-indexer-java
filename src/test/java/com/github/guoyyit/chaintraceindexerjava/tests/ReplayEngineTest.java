package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.exec.ReplayEngine;
import com.github.guoyyit.chaintraceindexerjava.exec.WorldState;
import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.model.CallFrame;
import com.github.guoyyit.chaintraceindexerjava.model.ChangeType;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayEngineTest {

    @SuppressWarnings("unchecked")
    private static void seedBalance(WorldState state, String address, long balance) {
        try {
            Field field = WorldState.class.getDeclaredField("balances");
            field.setAccessible(true);
            ((Map<String, Long>) field.get(state)).put(address, balance);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ReplayEngine engineWith(String address, long balance) {
        WorldState state = new WorldState();
        seedBalance(state, address, balance);
        return new ReplayEngine(state);
    }

    @Test
    void nestedCallsAreTraced() {
        ReplayEngine engine = engineWith("A", 1_000L);
        RawTransaction tx = TestData.tx("t1", "A", 100, List.of(
                "call B 50",
                "  call C 20",
                "  end",
                "end"));
        RawBlock block = TestData.block(1, "h1", "h0", tx);

        BlockResult result = engine.executeBlock(block);
        TransactionResult txResult = result.transactions().get(0);

        assertTrue(txResult.success());
        CallFrame root = txResult.trace();
        assertEquals(0, root.depth());
        assertEquals(1, root.children().size());
        CallFrame callB = root.children().get(0);
        assertEquals("A", callB.from());
        assertEquals("B", callB.to());
        assertEquals(50, callB.value());
        assertEquals(1, callB.depth());
        assertTrue(callB.success());
        assertEquals(1, callB.children().size());
        CallFrame callC = callB.children().get(0);
        assertEquals("C", callC.to());
        assertEquals(20, callC.value());
        assertEquals(2, callC.depth());

        assertEquals(850, engine.worldState().getBalance("A"));
        assertEquals(30, engine.worldState().getBalance("B"));
        assertEquals(20, engine.worldState().getBalance("C"));
        assertEquals(1, engine.worldState().getNonce("A"));
    }

    @Test
    void failingInnerCallRollsBackItsChanges() {
        ReplayEngine engine = engineWith("A", 1_000L);
        RawTransaction tx = TestData.tx("t1", "A", 100, List.of(
                "call B 50",
                "  call C 20",
                "    sset gone v",
                "    revert boom",
                "  end",
                "end"));
        BlockResult result = engine.executeBlock(TestData.block(1, "h1", "h0", tx));
        TransactionResult txResult = result.transactions().get(0);

        assertFalse(txResult.success());
        assertEquals("boom", txResult.revertReason());
        assertTrue(txResult.changes().isEmpty());
        assertNull(txResult.trace());

        assertEquals(1_000L, engine.worldState().getBalance("A"));
        assertEquals(0L, engine.worldState().getBalance("B"));
        assertEquals(0L, engine.worldState().getBalance("C"));
        assertNull(engine.worldState().getStorage("C", "gone"));
        assertEquals(0L, engine.worldState().getNonce("A"));
    }

    @Test
    void topLevelRevertRollsBackEntireTransaction() {
        ReplayEngine engine = engineWith("A", 1_000L);
        RawTransaction tx = TestData.tx("t1", "A", 100, List.of(
                "call B 50", "end",
                "revert nope"));
        BlockResult result = engine.executeBlock(TestData.block(1, "h1", "h0", tx));
        TransactionResult txResult = result.transactions().get(0);

        assertFalse(txResult.success());
        assertEquals("nope", txResult.revertReason());
        assertTrue(txResult.changes().isEmpty());
        assertNull(txResult.trace());
        assertEquals(1_000L, engine.worldState().getBalance("A"));
        assertEquals(0L, engine.worldState().getBalance("B"));
        assertEquals(0L, engine.worldState().getNonce("A"));
    }

    @Test
    void failedInnerFrameRollsBackButOuterCanContinue() {
        ReplayEngine engine = engineWith("A", 1_000L);
        RawTransaction tx = TestData.tx("t1", "A", 100, List.of(
                "call B 50",
                "  call C 10",
                "    sset inner yes",
                "  end",
                "  sset ok 1",
                "end"));
        BlockResult result = engine.executeBlock(TestData.block(1, "h1", "h0", tx));
        TransactionResult txResult = result.transactions().get(0);

        assertTrue(txResult.success());
        CallFrame callB = txResult.trace().children().get(0);
        assertTrue(callB.success());
        assertTrue(callB.children().get(0).success());
        assertEquals("1", engine.worldState().getStorage("B", "ok"));
        assertEquals("yes", engine.worldState().getStorage("C", "inner"));
        assertEquals(10L, engine.worldState().getBalance("C"));
    }

    @Test
    void duplicateTransactionIsNotApplied() {
        ReplayEngine engine = engineWith("A", 1_000L);
        RawTransaction tx = TestData.tx("dup", "A", 100, List.of());

        TransactionResult first = engine.executeTransaction(tx);
        TransactionResult duplicate = engine.skipDuplicate(tx);

        assertTrue(first.applied());
        assertFalse(duplicate.applied());
        assertTrue(duplicate.changes().isEmpty());
        assertEquals(1, engine.worldState().getNonce("A"));
        assertEquals(900, engine.worldState().getBalance("A"));
    }

    @Test
    void crossTransactionStateIsShared() {
        ReplayEngine engine = engineWith("A", 1_000L);
        RawTransaction first = TestData.tx("t1", "A", 100, List.of(
                "call B 40", "end"));
        RawTransaction second = TestData.tx("t2", "B", 0, List.of(
                "call C 30", "end"));
        BlockResult result = engine.executeBlock(
                TestData.block(1, "h1", "h0", first, second));

        assertEquals(2, result.transactions().size());
        assertEquals(860, engine.worldState().getBalance("A"));
        assertEquals(10, engine.worldState().getBalance("B"));
        assertEquals(30, engine.worldState().getBalance("C"));
        assertEquals(1, engine.worldState().getNonce("A"));
        assertEquals(1, engine.worldState().getNonce("B"));

        List<StateChange> secondChanges = result.transactions().get(1).changes();
        assertTrue(secondChanges.stream().anyMatch(change ->
                change.type() == ChangeType.BALANCE && change.address().equals("C")));
    }
}
