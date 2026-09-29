package com.github.guoyyit.chaintraceindexerjava.store;

import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.model.CallFrame;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight WAL/index footprint of a block whose full payload was moved to archive.
 *
 * <p>Retained data is enough to answer every query path without loading the block:
 * transaction identity/outcome/from plus the trace skeleton (for address appearance),
 * and per-address change counts (for stable pagination totals). Full traces and changes
 * are fetched from {@link ArchiveStore} content objects when a page needs them.
 */
public record ArchivedMarker(
        long height,
        String blockHash,
        List<Tx> txs,
        List<AddressChangeCount> addressChangeCounts
) {
    public ArchivedMarker {
        txs = txs == null ? List.of() : List.copyOf(txs);
        addressChangeCounts = addressChangeCounts == null
                ? List.of() : List.copyOf(addressChangeCounts);
    }

    public record Tx(
            int index,
            String hash,
            boolean applied,
            boolean success,
            String from,
            CallFrame trace
    ) {
    }

    public record AddressChangeCount(String address, int count) {
    }

    public static ArchivedMarker from(BlockResult block) {
        List<Tx> txs = block.transactions().stream()
                .map(tx -> new Tx(block.transactions().indexOf(tx), tx.hash(), tx.applied(),
                        tx.success(), tx.trace() == null ? null : tx.trace().from(), tx.trace()))
                .toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TransactionResult tx : block.transactions()) {
            for (StateChange change : tx.changes()) {
                counts.merge(change.address(), 1, Integer::sum);
            }
        }
        List<AddressChangeCount> addressChangeCounts = counts.entrySet().stream()
                .map(entry -> new AddressChangeCount(entry.getKey(), entry.getValue()))
                .toList();
        return new ArchivedMarker(block.height(), block.hash(), txs, addressChangeCounts);
    }
}
