package com.github.guoyyit.chaintraceindexerjava.execution;

import com.github.guoyyit.chaintraceindexerjava.model.RawAllocation;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.SkippedTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, deterministic replay engine. No network, no clocks: the same block
 * sequence always produces identical frames and state changes.
 */
public class VirtualMachine {

    /**
     * Trace script instruction reference (each {@code input} is trimmed and
     * split into non blank lines):
     * <pre>
     * TRANSFER &lt;to&gt; &lt;amount&gt;        move value out of the running frame actor
     * SSTORE   &lt;key&gt; &lt;value&gt;       write the callee contract storage
     * SLOAD    &lt;key&gt;               read storage, appended to frame output
     * LOG      &lt;text&gt;              append text to frame output
     * CALL     &lt;to&gt; &lt;value&gt; &lt;gas&gt;  nested call; runs the code already stored at &lt;to&gt;
     * DEPLOY   &lt;value&gt; &lt;gas&gt; | &lt;code line&gt; | ...  create a deterministic contract
     * FAIL     [reason]            fail this frame and roll back its writes
     * </pre>
     * A root CALL without code at the target executes the transaction input as
     * the contract program, so simple scripts need no prior deployment. A
     * nested CALL to an address without code is a plain (successful) value
     * transfer. Contract addresses created by DEPLOY are deterministic:
     * {@code c<height>-<txIndex>-<createSeq>}.
     */
    public ExecResult executeGenesis(RawBlock block, AccountState state) {
        List<TraceFrame> frames = new ArrayList<>();
        List<StateChange> changes = new ArrayList<>();
        int seq = 0;
        if (block.allocations() != null) {
            for (RawAllocation allocation : block.allocations()) {
                String address = AccountState.normalizeAddress(allocation.address());
                long oldBalance = state.getBalance(address);
                state.setBalance(address, allocation.balance());
                int frameIndex = frames.size();
                frames.add(new TraceFrame(null, block.height(), block.hash(), -1, frameIndex, 0,
                        "GENESIS", null, address, allocation.balance(), true,
                        null, null, null, 0L, false));
                changes.add(new StateChange(null, block.height(), block.hash(), -1,
                        frameIndex, seq++, address, AccountState.FIELD_BALANCE,
                        null, Long.toString(oldBalance), Long.toString(allocation.balance())));
            }
        }
        return new ExecResult(frames, changes, List.of());
    }

    public ExecResult executeBlock(RawBlock block, AccountState state) {
        List<TraceFrame> frames = new ArrayList<>();
        List<StateChange> changes = new ArrayList<>();
        List<SkippedTransaction> skipped = new ArrayList<>();
        if (block.transactions() != null) {
            int txIndex = 0;
            for (RawTransaction tx : block.transactions()) {
                new TransactionExecutor(this, block, state).run(tx, txIndex, frames, changes, skipped);
                txIndex++;
            }
        }
        return new ExecResult(frames, changes, skipped);
    }
}
