package com.github.guoyyit.chaintraceindexerjava.model;

/**
 * One committed address state change. {@code txHash} is null for genesis
 * allocations. {@code seq} is the global, dense, replay-stable sequence number
 * of the change within its block.
 */
public record StateChange(String txHash,
                          long blockHeight,
                          String blockHash,
                          int txIndex,
                          int frameIndex,
                          int seq,
                          String address,
                          String field,
                          String storageKey,
                          String oldValue,
                          String newValue) {
}
