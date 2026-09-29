package com.github.guoyyit.chaintraceindexerjava.model;

/**
 * One call frame of a transaction (or a genesis entry). Frames are ordered by
 * pre-order DFS traversal of the internal call tree.
 */
public record TraceFrame(String txHash,
                         long blockHeight,
                         String blockHash,
                         int txIndex,
                         int frameIndex,
                         int depth,
                         String type,
                         String from,
                         String to,
                         long value,
                         boolean success,
                         String error,
                         String input,
                         String output,
                         long gasUsed,
                         boolean rolledBack) {
}
