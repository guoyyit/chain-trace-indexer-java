package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

public record TransactionResult(
        String hash,
        boolean applied,
        boolean success,
        String revertReason,
        CallFrame trace,
        List<StateChange> changes
) {
    public TransactionResult {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
