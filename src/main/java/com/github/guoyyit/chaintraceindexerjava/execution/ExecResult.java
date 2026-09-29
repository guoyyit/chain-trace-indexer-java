package com.github.guoyyit.chaintraceindexerjava.execution;

import com.github.guoyyit.chaintraceindexerjava.model.SkippedTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;

import java.util.List;

/**
 * Deterministic replay output for one block.
 */
public record ExecResult(List<TraceFrame> frames,
                         List<StateChange> changes,
                         List<SkippedTransaction> skipped) {
}
