package com.github.guoyyit.chaintraceindexerjava.api;

import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;

import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record RawTransactionDto(String hash, String from, long value, List<String> code) {
    }

    public record RawBlockDto(long height, String hash, String prevHash,
                              List<AllocationDto> allocations,
                              List<RawTransactionDto> transactions) {
    }

    public record AllocationDto(String address, long balance) {
    }

    public record ErrorResponse(String error, String message) {
    }

    public static RawBlock toModel(RawBlockDto dto) {
        List<RawBlock.Allocation> allocations = dto.allocations() == null
                ? List.of()
                : dto.allocations().stream()
                        .map(allocation -> new RawBlock.Allocation(
                                allocation.address(), allocation.balance()))
                        .toList();
        List<RawTransaction> transactions = dto.transactions() == null
                ? List.of()
                : dto.transactions().stream()
                        .map(tx -> new RawTransaction(
                                tx.hash(), tx.from(), tx.value(),
                                tx.code() == null ? List.of() : List.copyOf(tx.code())))
                        .toList();
        return new RawBlock(dto.height(), dto.hash(), dto.prevHash(),
                allocations, transactions);
    }
}
