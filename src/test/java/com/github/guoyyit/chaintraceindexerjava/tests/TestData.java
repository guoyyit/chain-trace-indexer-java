package com.github.guoyyit.chaintraceindexerjava.tests;

import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;

import java.util.Arrays;
import java.util.List;

final class TestData {

    private TestData() {
    }

    static RawTransaction tx(String hash, String from, long value, List<String> code) {
        return new RawTransaction(hash, from, value, code);
    }

    static RawBlock block(long height, String hash, String prevHash, RawTransaction... txs) {
        return new RawBlock(height, hash, prevHash, List.of(), Arrays.asList(txs));
    }

    static RawBlock blockWithAllocations(long height, String hash, String prevHash,
                                         List<RawBlock.Allocation> allocations,
                                         RawTransaction... txs) {
        return new RawBlock(height, hash, prevHash, allocations, Arrays.asList(txs));
    }

    static RawBlock.Allocation alloc(String address, long balance) {
        return new RawBlock.Allocation(address, balance);
    }
}
