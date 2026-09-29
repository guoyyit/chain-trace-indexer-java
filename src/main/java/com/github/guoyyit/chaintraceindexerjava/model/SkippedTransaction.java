package com.github.guoyyit.chaintraceindexerjava.model;

/**
 * A transaction that was deterministically skipped during replay (for example a
 * duplicate hash, or a nonce/balance failure at the root frame).
 */
public record SkippedTransaction(int txIndex, String txHash, String reason) {
}
