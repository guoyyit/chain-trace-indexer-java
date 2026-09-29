package com.github.guoyyit.chaintraceindexerjava.execution;

import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.SkippedTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a single raw transaction deterministically and appends its frames
 * and committed state changes.
 *
 * <p>Frames are emitted in pre-order DFS order of the call tree. A failed
 * frame rolls world state back to its entry snapshot; its {@link TraceFrame}
 * is still emitted with {@code success=false} and {@code rolledBack=true}, and
 * none of its state changes are indexed. A failed root frame therefore discards
 * every application write of the transaction, while the sender nonce increment
 * and gas charge remain applied (as on a real chain).
 */
class TransactionExecutor {

    static final long INTRINSIC_GAS = 21_000L;

    private final RawBlock block;
    private final AccountState state;

    private RawTransaction tx;
    private int txIndex;
    private List<TraceFrame> frames;
    private List<StateChange> changes;
    private List<SkippedTransaction> skipped;

    private int nextFrameIndex;
    private int createSeq;
    private TraceFrame[] frameSlots = new TraceFrame[0];

    TransactionExecutor(VirtualMachine vm, RawBlock block, AccountState state) {
        this.block = block;
        this.state = state;
    }

    void run(RawTransaction rawTx,
             int txIndexValue,
             List<TraceFrame> targetFrames,
             List<StateChange> targetChanges,
             List<SkippedTransaction> targetSkipped) {
        this.tx = rawTx;
        this.txIndex = txIndexValue;
        this.frames = targetFrames;
        this.changes = targetChanges;
        this.skipped = targetSkipped;
        this.nextFrameIndex = 0;
        this.createSeq = 0;
        this.frameSlots = new TraceFrame[8];

        if (tx.hash() == null || tx.hash().isBlank()) {
            skipped.add(new SkippedTransaction(txIndex, null, "missing hash"));
            return;
        }
        String sender = AccountState.normalizeAddress(tx.from());
        if (!state.markExecuted(tx.hash())) {
            skipped.add(new SkippedTransaction(txIndex, tx.hash(), "duplicate transaction"));
            return;
        }
        if (state.getBalance(sender) < tx.value()) {
            skipped.add(new SkippedTransaction(txIndex, tx.hash(), "insufficient balance"));
            return;
        }

        boolean creation = tx.to() == null || tx.to().isBlank();
        String rootTo = creation ? null : AccountState.normalizeAddress(tx.to());
        String rootType = creation ? "CREATE" : "CALL";
        long gasAllowance = Math.max(tx.gasLimit(), 0L);

        int txSnapshot = state.snapshot();
        long oldNonce = state.getNonce(sender);
        state.setNonce(sender, oldNonce + 1);

        int rootFrameIndex = nextFrameIndex++;
        placeHolder(rootFrameIndex, 0, rootType, sender, rootTo, tx.value(), tx.input());
        List<ChangeLogEntry> rootChanges = new ArrayList<>();
        FrameOutcome outcome = runFrame(rootType, sender, rootTo, tx.value(), 0,
                rootFrameIndex, tx.input(), gasAllowance, rootChanges);

        boolean rootSuccess = outcome.success;
        if (!rootSuccess) {
            state.rollback(txSnapshot);
            state.setNonce(sender, oldNonce + 1);
        }
        String frameTo = outcome.contractAddress != null ? outcome.contractAddress : rootTo;
        putOutcome(rootFrameIndex, 0, rootType, sender, frameTo, tx.value(), tx.input(),
                outcome, !rootSuccess);

        long gasCharge = rootSuccess
                ? INTRINSIC_GAS + outcome.gasUsed
                : Math.max(gasAllowance, INTRINSIC_GAS);
        long oldBalance = state.getBalance(sender);
        state.setBalance(sender, oldBalance - gasCharge);

        List<StateChange> txChanges = new ArrayList<>();
        txChanges.add(new StateChange(tx.hash(), block.height(), block.hash(), txIndex, 0,
                0, sender, AccountState.FIELD_NONCE, null,
                Long.toString(oldNonce), Long.toString(oldNonce + 1)));
        if (rootSuccess) {
            for (ChangeLogEntry entry : rootChanges) {
                txChanges.add(new StateChange(tx.hash(), block.height(), block.hash(), txIndex,
                        entry.frameIndex(), 0, entry.address(), entry.field(), entry.storageKey(),
                        entry.oldValue(), entry.newValue()));
            }
        }
        txChanges.add(new StateChange(tx.hash(), block.height(), block.hash(), txIndex, 0,
                0, sender, AccountState.FIELD_BALANCE, null,
                Long.toString(oldBalance), Long.toString(oldBalance - gasCharge)));

        int baseSeq = changes.size();
        for (int i = 0; i < txChanges.size(); i++) {
            StateChange change = txChanges.get(i);
            changes.add(new StateChange(change.txHash(), change.blockHeight(), change.blockHash(),
                    change.txIndex(), change.frameIndex(), baseSeq + i, change.address(),
                    change.field(), change.storageKey(), change.oldValue(), change.newValue()));
        }
        for (TraceFrame frameSlot : frameSlots) {
            if (frameSlot != null) {
                frames.add(frameSlot);
            }
        }
    }

    private FrameOutcome runFrame(String type,
                                  String caller,
                                  String to,
                                  long value,
                                  int depth,
                                  int frameIndex,
                                  String inputProgram,
                                  long gasAllowance,
                                  List<ChangeLogEntry> frameChanges) {
        int snapshotId = state.snapshot();
        long gasUsed = 0L;
        List<String> outputs = new ArrayList<>();
        String contractAddress = null;
        String actor;
        List<Instruction> program;

        if ("CREATE".equals(type)) {
            contractAddress = deterministicAddress();
            actor = contractAddress;
            String code = storedCodeFrom(inputProgram);
            String oldCode = state.getCode(contractAddress);
            state.setCode(contractAddress, code);
            frameChanges.add(new ChangeLogEntry(frameIndex, contractAddress,
                    AccountState.FIELD_CODE, null, oldCode, code));
            if (value > 0) {
                moveValue(caller, contractAddress, value, frameIndex, frameChanges);
            }
            // The constructor body (the DEPLOY inline block, or a root CREATE
            // tx input) runs once during deployment.
            program = ProgramParser.parse(inputProgram);
        } else {
            actor = to;
            if (value > 0) {
                moveValue(caller, to, value, frameIndex, frameChanges);
            }
            String code = state.getCode(to);
            if (code != null && !code.isEmpty()) {
                program = ProgramParser.parse(code);
            } else {
                // An address without code executes the supplied script: the
                // transaction input at root, or the CALL inline block.
                program = ProgramParser.parse(inputProgram);
            }
        }

        for (Instruction instruction : program) {
            try {
                switch (instruction.op()) {
                    case "TRANSFER" -> {
                        String target = AccountState.normalizeAddress(instruction.args().get(0));
                        long amount = Long.parseLong(instruction.args().get(1));
                        if (state.getBalance(actor) < amount) {
                            return fail(snapshotId, frameChanges, gasAllowance,
                                    "insufficient balance for transfer", contractAddress, outputs);
                        }
                        moveValue(actor, target, amount, frameIndex, frameChanges);
                        gasUsed += 21_000L;
                    }
                    case "SSTORE" -> {
                        String key = instruction.args().get(0);
                        String newValue = instruction.args().size() > 1
                                ? instruction.args().get(1) : "";
                        String oldValue = state.getStorage(actor, key);
                        state.setStorage(actor, key, newValue);
                        frameChanges.add(new ChangeLogEntry(frameIndex, actor,
                                AccountState.FIELD_STORAGE, key, oldValue, newValue));
                        gasUsed += 20_000L;
                    }
                    case "SLOAD" -> {
                        String key = instruction.args().get(0);
                        outputs.add(key + "=" + state.getStorage(actor, key));
                        gasUsed += 2_100L;
                    }
                    case "LOG" -> {
                        outputs.add(String.join(" ", instruction.args()));
                        gasUsed += 375L;
                    }
                    case "FAIL" -> {
                        return fail(snapshotId, frameChanges, gasAllowance,
                                instruction.args().isEmpty() ? "explicit failure"
                                        : String.join(" ", instruction.args()),
                                contractAddress, outputs);
                    }
                    case "CALL" -> {
                        String childTo = AccountState.normalizeAddress(instruction.args().get(0));
                        long childValue = Long.parseLong(instruction.args().get(1));
                        long childGas = instruction.args().size() > 2
                                ? Long.parseLong(instruction.args().get(2))
                                : gasAllowance - gasUsed;
                        // Inline block is used only when the target has no
                        // deployed code; runFrame chooses it then.
                        String childProgram = instruction.hasBlock()
                                ? serialize(instruction.block())
                                : null;
                        gasUsed += invokeChild("CALL", actor, childTo, childValue, depth + 1,
                                childProgram, childGas, frameChanges);
                    }
                    case "DEPLOY" -> {
                        long childValue = Long.parseLong(instruction.args().get(0));
                        long childGas = instruction.args().size() > 1
                                ? Long.parseLong(instruction.args().get(1))
                                : gasAllowance - gasUsed;
                        String childCode = instruction.hasBlock()
                                ? serialize(instruction.block())
                                : "";
                        gasUsed += invokeChild("CREATE", actor, null, childValue, depth + 1,
                                childCode, childGas, frameChanges);
                    }
                    default -> {
                        return fail(snapshotId, frameChanges, gasUsed,
                                "unknown instruction: " + instruction.op(),
                                contractAddress, outputs);
                    }
                }
            } catch (RuntimeException ex) {
                return fail(snapshotId, frameChanges, gasUsed,
                        "malformed instruction: " + ex.getMessage(),
                        contractAddress, outputs);
            }
            if (gasUsed > gasAllowance) {
                return fail(snapshotId, frameChanges, gasAllowance, "out of gas",
                        contractAddress, outputs);
            }
        }
        return new FrameOutcome(true, null, gasUsed, contractAddress, String.join(";", outputs));
    }

    /**
     * Persistent contract code after deployment: only storage touching
     * instructions ({@code SSTORE}/{@code SLOAD}/{@code LOG}) survive, so a
     * later CALL re runs the contract's observable behavior without repeating
     * one-off deployment steps or nested deployments.
     */
    private String storedCodeFrom(String constructorProgram) {
        if (constructorProgram == null || constructorProgram.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Instruction instruction : ProgramParser.parse(constructorProgram)) {
            switch (instruction.op()) {
                case "SSTORE", "SLOAD", "LOG" ->
                        builder.append(serialize(List.of(instruction)));
                default -> {
                    // CALL / DEPLOY / TRANSFER / FAIL are constructor only.
                }
            }
        }
        return builder.toString();
    }

    private String serialize(List<Instruction> instructions) {
        StringBuilder builder = new StringBuilder();
        for (Instruction instruction : instructions) {
            builder.append(instruction.op());
            for (String arg : instruction.args()) {
                builder.append(' ').append(arg);
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private String deterministicAddress() {
        createSeq++;
        return "c" + block.height() + "_" + txIndex + "_" + createSeq;
    }

    private void moveValue(String from, String to, long amount,
                           int frameIndex, List<ChangeLogEntry> frameChanges) {
        long fromOld = state.getBalance(from);
        state.setBalance(from, fromOld - amount);
        long toOld = state.getBalance(to);
        state.setBalance(to, toOld + amount);
        frameChanges.add(new ChangeLogEntry(frameIndex, from, AccountState.FIELD_BALANCE, null,
                Long.toString(fromOld), Long.toString(fromOld - amount)));
        frameChanges.add(new ChangeLogEntry(frameIndex, to, AccountState.FIELD_BALANCE, null,
                Long.toString(toOld), Long.toString(toOld + amount)));
    }

    private long invokeChild(String type,
                             String caller,
                             String to,
                             long value,
                             int depth,
                             String program,
                             long gasAllowance,
                             List<ChangeLogEntry> parentChanges) {
        int childFrameIndex = nextFrameIndex++;
        placeHolder(childFrameIndex, depth, type, caller,
                "CREATE".equals(type) ? null : to, value, program);
        List<ChangeLogEntry> childChanges = new ArrayList<>();
        FrameOutcome outcome = runFrame(type, caller, to, value, depth,
                childFrameIndex, program, gasAllowance, childChanges);
        String frameTo = outcome.contractAddress() != null ? outcome.contractAddress() : to;
        putOutcome(childFrameIndex, depth, type, caller, frameTo, value, program,
                outcome, !outcome.success());
        long childCost = outcome.gasUsed() + 700L;
        if (outcome.success()) {
            parentChanges.addAll(childChanges);
        }
        return childCost;
    }

    private void placeHolder(int frameIndex,
                             int depth,
                             String type,
                             String from,
                             String to,
                             long value,
                             String input) {
        ensureCapacity(frameIndex);
        frameSlots[frameIndex] = new TraceFrame(tx.hash(), block.height(), block.hash(), txIndex,
                frameIndex, depth, type, from, to, value,
                false, null, input, null, 0L, false);
    }

    private void putOutcome(int frameIndex,
                           int depth,
                           String type,
                           String from,
                           String to,
                           long value,
                           String input,
                           FrameOutcome outcome,
                           boolean rolledBack) {
        ensureCapacity(frameIndex);
        frameSlots[frameIndex] = new TraceFrame(tx.hash(), block.height(), block.hash(), txIndex,
                frameIndex, depth, type, from, to, value,
                outcome.success(), outcome.error(), input, outcome.output(),
                outcome.gasUsed(), rolledBack);
    }

    private void ensureCapacity(int index) {
        if (index < frameSlots.length) {
            return;
        }
        TraceFrame[] grown = new TraceFrame[Math.max(index + 1, frameSlots.length * 2)];
        System.arraycopy(frameSlots, 0, grown, 0, frameSlots.length);
        frameSlots = grown;
    }

    private FrameOutcome fail(int snapshotId,
                              List<ChangeLogEntry> frameChanges,
                              long gasUsed,
                              String error,
                              String contractAddress,
                              List<String> outputs) {
        state.rollback(snapshotId);
        frameChanges.clear();
        return new FrameOutcome(false, error, gasUsed, contractAddress, String.join(";", outputs));
    }

    private record ChangeLogEntry(int frameIndex,
                                  String address,
                                  String field,
                                  String storageKey,
                                  String oldValue,
                                  String newValue) {
    }

    private record FrameOutcome(boolean success,
                                String error,
                                long gasUsed,
                                String contractAddress,
                                String output) {
    }
}
