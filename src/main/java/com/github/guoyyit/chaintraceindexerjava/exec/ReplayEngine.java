package com.github.guoyyit.chaintraceindexerjava.exec;

import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock.Allocation;
import com.github.guoyyit.chaintraceindexerjava.model.CallFrame;
import com.github.guoyyit.chaintraceindexerjava.model.Instruction;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic replay engine.
 *
 * <p>Executes a block's transactions against a shared {@link WorldState}. Each transaction:
 * <ol>
 *   <li>increments the sender nonce (depth 0),</li>
 *   <li>moves {@code value} from sender to the root execution context (depth 0),</li>
 *   <li>interprets the instruction list; {@code call} creates a nested frame and transfers
 *       value to the callee; a failed/reverted frame rolls back all its state changes.</li>
 * </ol>
 * A duplicate transaction hash is skipped entirely (no state changes, no trace).
 * Output is a deterministic function of the input block sequence and the initial state.
 */
public final class ReplayEngine {

    private final WorldState worldState;

    public ReplayEngine(WorldState worldState) {
        this.worldState = worldState;
    }

    public BlockResult executeBlock(RawBlock block) {
        List<StateChange> allocationChanges = new ArrayList<>();
        for (Allocation allocation : block.allocations()) {
            long oldBalance = worldState.getBalance(allocation.address());
            worldState.putBalance(allocation.address(), allocation.balance());
            allocationChanges.add(new StateChange(allocation.address(),
                    com.github.guoyyit.chaintraceindexerjava.model.ChangeType.BALANCE,
                    null, Long.toString(oldBalance),
                    Long.toString(allocation.balance()), -1));
        }
        List<TransactionResult> results = new ArrayList<>();
        for (RawTransaction transaction : block.transactions()) {
            results.add(executeTransaction(transaction));
        }
        return new BlockResult(block.height(), block.hash(),
                List.copyOf(allocationChanges), List.copyOf(results));
    }

    public TransactionResult executeTransaction(RawTransaction transaction) {
        int before = worldState.checkpoint();
        worldState.incrementNonce(transaction.from(), 0);
        worldState.transfer(transaction.from(), "0x0", transaction.value(), 0);
        List<CallFrame> children = new ArrayList<>();
        String failure = null;
        try {
            for (Instruction instruction : InstructionParser.parse(transaction.instructions())) {
                FrameOutcome outcome = executeInstruction(instruction, transaction.from(), 1);
                if (outcome.frame() != null) {
                    children.add(outcome.frame());
                }
                if (!outcome.success()) {
                    failure = outcome.reason();
                    break;
                }
            }
        } catch (WorldState.InsufficientFundsException ex) {
            failure = ex.getMessage();
        } catch (InstructionParseException ex) {
            throw new InstructionParseException(
                    "transaction " + transaction.hash() + ": " + ex.getMessage());
        }

        boolean success = failure == null;
        if (!success) {
            worldState.rollback(before);
            return new TransactionResult(transaction.hash(), true, false, failure, null, List.of());
        }
        worldState.discard(before);
        worldState.resetJournal();
        List<StateChange> changes = worldState.drainChanges();
        CallFrame root = new CallFrame(transaction.from(), transaction.from(), transaction.value(),
                0, true, null, List.copyOf(children));
        return new TransactionResult(transaction.hash(), true, true, null, root, changes);
    }

    public TransactionResult skipDuplicate(RawTransaction transaction) {
        return new TransactionResult(transaction.hash(), false, true, null, null, List.of());
    }

    private FrameOutcome executeInstruction(Instruction instruction, String executor, int depth) {
        if (instruction instanceof Instruction.Transfer transfer) {
            try {
                worldState.transfer(executor, transfer.to(), transfer.value(), depth);
                return new FrameOutcome(null, true, null);
            } catch (WorldState.InsufficientFundsException ex) {
                return new FrameOutcome(null, false, ex.getMessage());
            }
        }
        if (instruction instanceof Instruction.SetStorage setStorage) {
            worldState.setStorage(executor, setStorage.key(), setStorage.value(), depth);
            return new FrameOutcome(null, true, null);
        }
        if (instruction instanceof Instruction.DelStorage delStorage) {
            worldState.deleteStorage(executor, delStorage.key(), depth);
            return new FrameOutcome(null, true, null);
        }
        if (instruction instanceof Instruction.Revert revert) {
            return new FrameOutcome(null, false, revert.reason());
        }
        Instruction.Call call = (Instruction.Call) instruction;
        return executeCall(call, executor, depth);
    }

    private FrameOutcome executeCall(Instruction.Call call, String caller, int depth) {
        int checkpoint = worldState.checkpoint();
        String failure = null;
        List<CallFrame> children = new ArrayList<>();
        try {
            worldState.transfer(caller, call.to(), call.value(), depth);
            for (Instruction nested : call.body()) {
                FrameOutcome outcome = executeInstruction(nested, call.to(), depth + 1);
                if (outcome.frame() != null) {
                    children.add(outcome.frame());
                }
                if (!outcome.success()) {
                    failure = outcome.reason();
                    break;
                }
            }
        } catch (WorldState.InsufficientFundsException ex) {
            failure = ex.getMessage();
        }

        boolean success = failure == null;
        if (!success) {
            worldState.rollback(checkpoint);
            CallFrame frame = new CallFrame(caller, call.to(), call.value(), depth,
                    false, failure, List.copyOf(children));
            return new FrameOutcome(frame, false, failure);
        }
        CallFrame frame = new CallFrame(caller, call.to(), call.value(), depth,
                true, null, List.copyOf(children));
        return new FrameOutcome(frame, true, null);
    }

    public WorldState worldState() {
        return worldState;
    }

    private record FrameOutcome(CallFrame frame, boolean success, String reason) {
    }
}
