package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

public record BlockResult(
        long height,
        String hash,
        List<StateChange> allocations,
        List<TransactionResult> transactions
) {
    public BlockResult {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
    }
}
