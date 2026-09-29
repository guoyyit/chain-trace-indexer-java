package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

public record RawBlock(
        long height,
        String hash,
        String prevHash,
        List<Allocation> allocations,
        List<RawTransaction> transactions
) {
    public RawBlock {
        if (height < 0) {
            throw new IllegalArgumentException("block height must be non-negative");
        }
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("block hash must not be blank");
        }
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
    }

    public record Allocation(String address, long balance) {
        public Allocation {
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException("allocation address must not be blank");
            }
            if (balance < 0) {
                throw new IllegalArgumentException("allocation balance must be non-negative");
            }
        }
    }
}
