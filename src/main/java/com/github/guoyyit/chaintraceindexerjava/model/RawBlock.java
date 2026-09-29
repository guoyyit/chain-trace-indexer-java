package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

/**
 * Raw block as submitted via the local interface. Height 0 is the genesis
 * block and may carry {@link RawAllocation}s.
 */
public record RawBlock(long height,
                       String hash,
                       String parentHash,
                       List<RawAllocation> allocations,
                       List<RawTransaction> transactions) {
}
