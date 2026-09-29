package com.github.guoyyit.chaintraceindexerjava.model;

/**
 * Raw transaction as submitted via the local interface.
 *
 * <p>{@code to} is null/blank for contract creation; {@code input} is the
 * newline separated instruction program (for calls) or the contract code
 * (for creation).
 */
public record RawTransaction(String hash, String from, String to, long value, long gasLimit, String input) {
}
