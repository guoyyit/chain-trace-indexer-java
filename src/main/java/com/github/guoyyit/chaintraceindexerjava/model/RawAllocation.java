package com.github.guoyyit.chaintraceindexerjava.model;

/**
 * Genesis allocation item: sets the initial balance of an address.
 */
public record RawAllocation(String address, long balance) {
}
