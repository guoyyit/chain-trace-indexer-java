package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

public record RawTransaction(
        String hash,
        String from,
        long value,
        List<String> instructions
) {
    public RawTransaction {
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("transaction hash must not be blank");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("transaction from must not be blank");
        }
        if (value < 0) {
            throw new IllegalArgumentException("transaction value must be non-negative");
        }
        instructions = instructions == null ? List.of() : List.copyOf(instructions);
    }
}
