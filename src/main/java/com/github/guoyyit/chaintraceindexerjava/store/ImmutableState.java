package com.github.guoyyit.chaintraceindexerjava.store;

import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Fully immutable index state backing a {@link Snapshot}.
 *
 * <p>Maps are unmodifiable views of defensive copies captured at publication time.
 * {@code heights} is ordered ascending; per-address and per-hash lists are appended in
 * block/transaction/change order, which gives stable ordering without any timestamp.
 */
final class ImmutableState {

    final long tipHeight;
    final TreeMap<Long, BlockResult> blocks;
    final TreeMap<Long, ArchiveStore.ArchivedBlockHandle> archived;
    final TreeSet<Long> heights;
    final Map<String, java.util.List<Snapshot.TxRef>> txByHash;
    final Map<String, java.util.List<Snapshot.ChangeRef>> changesByTxHash;
    final Map<String, java.util.List<Snapshot.TxRef>> txByAddress;
    final Map<String, java.util.List<Snapshot.ChangeRef>> changesByAddress;

    ImmutableState(long tipHeight,
                   TreeMap<Long, BlockResult> blocks,
                   TreeMap<Long, ArchiveStore.ArchivedBlockHandle> archived,
                   TreeSet<Long> heights,
                   Map<String, java.util.List<Snapshot.TxRef>> txByHash,
                   Map<String, java.util.List<Snapshot.ChangeRef>> changesByTxHash,
                   Map<String, java.util.List<Snapshot.TxRef>> txByAddress,
                   Map<String, java.util.List<Snapshot.ChangeRef>> changesByAddress) {
        this.tipHeight = tipHeight;
        this.blocks = blocks;
        this.archived = archived;
        this.heights = heights;
        this.txByHash = txByHash;
        this.changesByTxHash = changesByTxHash;
        this.txByAddress = txByAddress;
        this.changesByAddress = changesByAddress;
    }

    static ImmutableState empty() {
        return new ImmutableState(-1L, new TreeMap<>(), new TreeMap<>(), new TreeSet<>(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap());
    }

    Set<Map.Entry<Long, ArchiveStore.ArchivedBlockHandle>> archivedEntries() {
        return Collections.unmodifiableSet(archived.entrySet());
    }
}
