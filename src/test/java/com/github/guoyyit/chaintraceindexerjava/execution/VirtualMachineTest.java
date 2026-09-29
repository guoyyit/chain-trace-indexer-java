package com.github.guoyyit.chaintraceindexerjava.execution;

import com.github.guoyyit.chaintraceindexerjava.model.RawAllocation;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualMachineTest {

    private final VirtualMachine vm = new VirtualMachine();

    private RawBlock genesis(long balance) {
        return new RawBlock(0, "g", "",
                List.of(new RawAllocation("alice", balance)), List.of());
    }

    @Test
    void nestedCallsProducePreOrderFramesAndCrossTransactionDependency() {
        AccountState state = new AccountState();
        vm.executeGenesis(genesis(1_000_000L), state);

        // Root EOA call deploys a contract (nested CREATE) then calls it.
        String program = String.join("\n",
                "DEPLOY 0 100000 {",
                "SSTORE counter 2",
                "}",
                "CALL c1_0_1 0 100000 {",
                "SLOAD counter",
                "}");
        RawTransaction tx = new RawTransaction("tx1", "alice", "alice", 0, 500_000, program);
        RawBlock block = new RawBlock(1, "b1", "g", null, List.of(tx));
        ExecResult result = vm.executeBlock(block, state);

        assertThat(result.frames()).extracting(TraceFrame::frameIndex)
                .containsExactly(0, 1, 2);
        assertThat(result.frames()).extracting(TraceFrame::depth)
                .containsExactly(0, 1, 1);
        assertThat(result.frames()).extracting(TraceFrame::type)
                .containsExactly("CALL", "CREATE", "CALL");
        assertThat(state.getStorage("c1_0_1", "counter")).isEqualTo("2");

        // Second transaction observes the state left by the first.
        RawTransaction caller = new RawTransaction("tx2", "alice", "c1_0_1", 0, 100_000,
                "");
        RawBlock block2 = new RawBlock(2, "b2", "b1", null, List.of(caller));
        ExecResult result2 = vm.executeBlock(block2, state);
        assertThat(result2.frames()).hasSize(1);
        assertThat(result2.frames().get(0).type()).isEqualTo("CALL");
        assertThat(result2.frames().get(0).to()).isEqualTo("c1_0_1");
    }

    @Test
    void failedInnerCallRollsBackOnlyItsOwnWrites() {
        AccountState state = new AccountState();
        vm.executeGenesis(genesis(1_000_000L), state);

        String program = String.join("\n",
                "TRANSFER bob 100",
                "CALL carol 50 100000 {",
                "TRANSFER dave 50",
                "SSTORE temp yes",
                "FAIL boom",
                "}",
                "SSTORE kept 1");
        RawTransaction tx = new RawTransaction("tx1", "alice", "alice", 0, 500_000, program);
        ExecResult result = vm.executeBlock(new RawBlock(1, "b1", "g", null, List.of(tx)), state);

        TraceFrame child = result.frames().get(1);
        assertThat(child.success()).isFalse();
        assertThat(child.rolledBack()).isTrue();
        assertThat(child.error()).isEqualTo("boom");
        // The parent's own writes survive.
        assertThat(state.getBalance("bob")).isEqualTo(100L);
        assertThat(state.getStorage("alice", "kept")).isEqualTo("1");
        // The child's writes are gone.
        assertThat(state.getBalance("carol")).isZero();
        assertThat(state.getBalance("dave")).isZero();
        assertThat(state.getStorage("alice", "temp")).isNull();
        assertThat(result.changes()).noneMatch(change -> "dave".equals(change.address()));
        assertThat(result.changes()).anyMatch(change -> "bob".equals(change.address())
                && AccountState.FIELD_BALANCE.equals(change.field()));
    }

    @Test
    void failedRootRollsBackApplicationWritesButKeepsNonceAndChargesGas() {
        AccountState state = new AccountState();
        vm.executeGenesis(genesis(1_000_000L), state);

        RawTransaction tx = new RawTransaction("tx1", "alice", "bob", 100, 500_000,
                "FAIL revert-please");
        ExecResult result = vm.executeBlock(new RawBlock(1, "b1", "g", null, List.of(tx)), state);

        assertThat(result.frames()).hasSize(1);
        assertThat(result.frames().get(0).success()).isFalse();
        assertThat(state.getBalance("bob")).isZero();
        // no application transfer change indexed
        assertThat(result.changes()).filteredOn(change -> "bob".equals(change.address())).isEmpty();
        // nonce increment and gas charge remain
        assertThat(state.getNonce("alice")).isEqualTo(1L);
        assertThat(state.getBalance("alice")).isEqualTo(1_000_000L - 500_000L);
        assertThat(result.changes()).extracting(StateChange::field)
                .containsExactly(AccountState.FIELD_NONCE, AccountState.FIELD_BALANCE);
    }

    @Test
    void duplicateTransactionHashIsSkippedAndNotApplied() {
        AccountState state = new AccountState();
        vm.executeGenesis(genesis(1_000_000L), state);

        RawTransaction first = new RawTransaction("dup", "alice", "bob", 100, 100_000, "");
        RawTransaction second = new RawTransaction("dup", "alice", "carol", 200, 100_000, "");
        RawBlock block = new RawBlock(1, "b1", "g", null, List.of(first, second));
        ExecResult result = vm.executeBlock(block, state);

        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).reason()).contains("duplicate");
        assertThat(state.getBalance("bob")).isEqualTo(100L);
        assertThat(state.getBalance("carol")).isZero();
    }

    @Test
    void replayIsDeterministicAcrossRuns() {
        AccountState first = new AccountState();
        AccountState second = new AccountState();
        vm.executeGenesis(genesis(1_000_000L), first);
        vm.executeGenesis(genesis(1_000_000L), second);

        String program = "CALL bob 10 100000 {\nSSTORE k v\n}\nSSTORE root r";
        List<RawBlock> chain = List.of(
                new RawBlock(1, "b1", "g", null,
                        List.of(new RawTransaction("t1", "alice", null, 5, 200_000, program))),
                new RawBlock(2, "b2", "b1", null,
                        List.of(new RawTransaction("t2", "alice", "bob", 1, 100_000, "LOG done"))));

        ExecResult a1 = vm.executeBlock(chain.get(0), first);
        ExecResult a2 = vm.executeBlock(chain.get(1), first);
        ExecResult b1 = vm.executeBlock(chain.get(0), second);
        ExecResult b2 = vm.executeBlock(chain.get(1), second);

        assertThat(a1.frames()).usingRecursiveFieldByFieldElementComparator().isEqualTo(b1.frames());
        assertThat(a1.changes()).usingRecursiveFieldByFieldElementComparator().isEqualTo(b1.changes());
        assertThat(a2.frames()).usingRecursiveFieldByFieldElementComparator().isEqualTo(b2.frames());
        assertThat(a2.changes()).usingRecursiveFieldByFieldElementComparator().isEqualTo(b2.changes());
    }
}
