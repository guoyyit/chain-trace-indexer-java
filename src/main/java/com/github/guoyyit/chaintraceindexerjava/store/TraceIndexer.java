package com.github.guoyyit.chaintraceindexerjava.store;

import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.model.CallFrame;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Mutable live index that publishes immutable {@link Snapshot}s.
 *
 * <p>All mutating operations copy the affected persistent structures and atomically swap
 * the published state, providing serializable per-block isolation for concurrent readers.
 * Outstanding snapshots are reference-counted: archive garbage collection keeps every
 * content object that any live snapshot can still reach.
 */
public final class TraceIndexer {

    final ArchiveStore archiveStore;

    private volatile ImmutableState current = ImmutableState.empty();
    private final Object monitor = new Object();
    private final Set<Snapshot> liveSnapshots = new HashSet<>();

    public TraceIndexer(ArchiveStore archiveStore) {
        this.archiveStore = archiveStore;
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            Snapshot snapshot = new Snapshot(current, this);
            liveSnapshots.add(snapshot);
            return snapshot;
        }
    }

    void releaseSnapshot(Snapshot snapshot) {
        synchronized (monitor) {
            liveSnapshots.remove(snapshot);
        }
    }

    public long tipHeight() {
        return current.tipHeight;
    }

    public void applyBlock(BlockResult block) {
        synchronized (monitor) {
            current = buildWithBlock(current, block);
        }
    }

    private static ImmutableState buildWithBlock(ImmutableState base, BlockResult block) {
        TreeMap<Long, BlockResult> blocks = new TreeMap<>(base.blocks);
        TreeMap<Long, ArchiveStore.ArchivedBlockHandle> archived =
                new TreeMap<>(base.archived);
        TreeSet<Long> heights = new TreeSet<>(base.heights);
        Map<String, List<Snapshot.TxRef>> txByHash = deepCopyRefs(base.txByHash);
        Map<String, List<Snapshot.ChangeRef>> changesByTxHash =
                deepCopyChangeRefs(base.changesByTxHash);
        Map<String, List<Snapshot.TxRef>> txByAddress = deepCopyRefs(base.txByAddress);
        Map<String, List<Snapshot.ChangeRef>> changesByAddress =
                deepCopyChangeRefs(base.changesByAddress);

        blocks.put(block.height(), block);
        heights.add(block.height());

        indexAllocations(block, changesByAddress);
        for (int txIndex = 0; txIndex < block.transactions().size(); txIndex++) {
            TransactionResult tx = block.transactions().get(txIndex);
            if (!tx.applied()) {
                continue;
            }
            Snapshot.TxRef txRef = new Snapshot.TxRef(block.height(), txIndex, tx.hash());
            txByHash.computeIfAbsent(tx.hash(), ignored -> new ArrayList<>()).add(txRef);

            Set<String> touched = new HashSet<>();
            if (tx.trace() != null) {
                collectFrameAddresses(tx.trace(), touched);
            }
            for (String address : touched) {
                txByAddress.computeIfAbsent(address, ignored -> new ArrayList<>()).add(txRef);
            }

            List<Snapshot.ChangeRef> txChanges =
                    changesByTxHash.computeIfAbsent(tx.hash(), ignored -> new ArrayList<>());
            Map<String, List<Snapshot.ChangeRef>> byAddressBuckets = new HashMap<>();
            for (int changeIndex = 0; changeIndex < tx.changes().size(); changeIndex++) {
                StateChange change = tx.changes().get(changeIndex);
                Snapshot.ChangeRef ref = new Snapshot.ChangeRef(
                        block.height(), txIndex, changeIndex, tx.hash());
                txChanges.add(ref);
                byAddressBuckets.computeIfAbsent(change.address(), ignored -> new ArrayList<>())
                        .add(ref);
            }
            for (Map.Entry<String, List<Snapshot.ChangeRef>> entry : byAddressBuckets.entrySet()) {
                changesByAddress.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .addAll(entry.getValue());
            }
        }

        return new ImmutableState(block.height(),
                freeze(blocks), freezeHandles(archived),
                heights,
                freeze(txByHash), freezeChanges(changesByTxHash),
                freeze(txByAddress), freezeChanges(changesByAddress));
    }

    private static void collectFrameAddresses(CallFrame frame, Set<String> addresses) {
        addresses.add(frame.from());
        addresses.add(frame.to());
        for (CallFrame child : frame.children()) {
            collectFrameAddresses(child, addresses);
        }
    }

    private static void indexAllocations(BlockResult block,
                                        Map<String, List<Snapshot.ChangeRef>> changesByAddress) {
        for (int changeIndex = 0; changeIndex < block.allocations().size(); changeIndex++) {
            StateChange change = block.allocations().get(changeIndex);
            Snapshot.ChangeRef ref = new Snapshot.ChangeRef(
                    block.height(), -1, changeIndex, null);
            changesByAddress.computeIfAbsent(change.address(), ignored -> new ArrayList<>())
                    .add(ref);
        }
    }

    public void undoBlock(long height, String blockHash) {
        synchronized (monitor) {
            current = rebuildWithout(current, height);
        }
    }

    private ImmutableState rebuildWithout(ImmutableState base, long removedHeight) {
        TreeMap<Long, BlockResult> blocks = new TreeMap<>();
        TreeMap<Long, ArchiveStore.ArchivedBlockHandle> archived = new TreeMap<>();
        TreeSet<Long> heights = new TreeSet<>();
        long tip = -1L;

        for (long height : base.heights) {
            if (height == removedHeight) {
                continue;
            }
            BlockResult hot = base.blocks.get(height);
            if (hot != null) {
                blocks.put(height, hot);
            } else {
                archived.put(height, base.archived.get(height));
            }
            heights.add(height);
            tip = height;
        }

        Map<String, List<Snapshot.TxRef>> txByHash = new LinkedHashMap<>();
        Map<String, List<Snapshot.ChangeRef>> changesByTxHash = new LinkedHashMap<>();
        Map<String, List<Snapshot.TxRef>> txByAddress = new LinkedHashMap<>();
        Map<String, List<Snapshot.ChangeRef>> changesByAddress = new LinkedHashMap<>();

        for (long height : heights) {
            BlockResult block = hotOrArchivedBlock(base, height);
            if (block == null) {
            indexArchivedHandle(base.archived.get(height),
                    txByHash, txByAddress, changesByTxHash, changesByAddress);
                continue;
            }
            indexAllocations(block, changesByAddress);
            indexHotBlock(block, txByHash, txByAddress, changesByTxHash, changesByAddress);
        }

        return new ImmutableState(tip,
                freeze(blocks), freezeHandles(archived), heights,
                freeze(txByHash), freezeChanges(changesByTxHash),
                freeze(txByAddress), freezeChanges(changesByAddress));
    }

    private static BlockResult hotOrArchivedBlock(ImmutableState state, long height) {
        BlockResult hot = state.blocks.get(height);
        if (hot != null) {
            return hot;
        }
        return null;
    }

    private static void indexHotBlock(BlockResult block,
                                      Map<String, List<Snapshot.TxRef>> txByHash,
                                      Map<String, List<Snapshot.TxRef>> txByAddress,
                                      Map<String, List<Snapshot.ChangeRef>> changesByTxHash,
                                      Map<String, List<Snapshot.ChangeRef>> changesByAddress) {
        for (int txIndex = 0; txIndex < block.transactions().size(); txIndex++) {
            TransactionResult tx = block.transactions().get(txIndex);
            if (!tx.applied()) {
                continue;
            }
            Snapshot.TxRef txRef = new Snapshot.TxRef(block.height(), txIndex, tx.hash());
            txByHash.computeIfAbsent(tx.hash(), ignored -> new ArrayList<>()).add(txRef);
            Set<String> touched = new HashSet<>();
            collectFrameAddresses(tx.trace(), touched);
            for (String address : touched) {
                txByAddress.computeIfAbsent(address, ignored -> new ArrayList<>()).add(txRef);
            }
            List<Snapshot.ChangeRef> txChanges =
                    changesByTxHash.computeIfAbsent(tx.hash(), ignored -> new ArrayList<>());
            Map<String, List<Snapshot.ChangeRef>> bucket = new HashMap<>();
            for (int changeIndex = 0; changeIndex < tx.changes().size(); changeIndex++) {
                StateChange change = tx.changes().get(changeIndex);
                Snapshot.ChangeRef ref = new Snapshot.ChangeRef(
                        block.height(), txIndex, changeIndex, tx.hash());
                txChanges.add(ref);
                bucket.computeIfAbsent(change.address(), ignored -> new ArrayList<>()).add(ref);
            }
            for (Map.Entry<String, List<Snapshot.ChangeRef>> entry : bucket.entrySet()) {
                changesByAddress.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .addAll(entry.getValue());
            }
        }
    }

    private void indexArchivedHandle(ArchiveStore.ArchivedBlockHandle handle,
                                    Map<String, List<Snapshot.TxRef>> txByHash,
                                    Map<String, List<Snapshot.TxRef>> txByAddress,
                                    Map<String, List<Snapshot.ChangeRef>> changesByTxHash,
                                    Map<String, List<Snapshot.ChangeRef>> changesByAddress) {
        for (ArchivedMarker.Tx tx : handle.txs()) {
            if (!tx.applied()) {
                continue;
            }
            Snapshot.TxRef txRef = new Snapshot.TxRef(handle.height(), tx.index(), tx.hash());
            txByHash.computeIfAbsent(tx.hash(), ignored -> new ArrayList<>()).add(txRef);
            Set<String> addresses = new HashSet<>();
            addresses.add(tx.from());
            collectFrameAddresses(tx.trace(), addresses);
            for (String address : addresses) {
                txByAddress.computeIfAbsent(address, ignored -> new ArrayList<>()).add(txRef);
            }
            BlockResult block = archiveStore.readBlock(handle);
            TransactionResult txResult = block.transactions().get(tx.index());
            List<Snapshot.ChangeRef> txChanges =
                    changesByTxHash.computeIfAbsent(tx.hash(), ignored -> new ArrayList<>());
            Map<String, List<Snapshot.ChangeRef>> bucket = new HashMap<>();
            for (int changeIndex = 0; changeIndex < txResult.changes().size(); changeIndex++) {
                StateChange change = txResult.changes().get(changeIndex);
                Snapshot.ChangeRef ref = new Snapshot.ChangeRef(
                        handle.height(), tx.index(), changeIndex, tx.hash());
                txChanges.add(ref);
                bucket.computeIfAbsent(change.address(), ignored -> new ArrayList<>()).add(ref);
            }
            for (Map.Entry<String, List<Snapshot.ChangeRef>> entry : bucket.entrySet()) {
                changesByAddress.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .addAll(entry.getValue());
            }
        }
    }

    public void markArchived(ArchivedMarker marker, String contentHash) {
        synchronized (monitor) {
            if (!current.blocks.containsKey(marker.height())) {
                return;
            }
            TreeMap<Long, BlockResult> blocks = new TreeMap<>(current.blocks);
            blocks.remove(marker.height());
            TreeMap<Long, ArchiveStore.ArchivedBlockHandle> archived =
                    new TreeMap<>(current.archived);
            archived.put(marker.height(), new ArchiveStore.ArchivedBlockHandle(
                    marker.height(), marker.blockHash(), contentHash, marker.txs(),
                    marker.addressChangeCounts().stream()
                            .map(ArchivedMarker.AddressChangeCount::address).toList(),
                    marker.addressChangeCounts()));
            current = new ImmutableState(current.tipHeight,
                    freeze(blocks), freezeHandles(archived),
                    new TreeSet<>(current.heights),
                    current.txByHash, current.changesByTxHash,
                    current.txByAddress, current.changesByAddress);
        }
    }

    public Set<String> referencedContentHashes() {
        synchronized (monitor) {
            Set<String> hashes = new HashSet<>();
            for (ArchiveStore.ArchivedBlockHandle handle : current.archived.values()) {
                hashes.add(handle.contentHash());
            }
            for (Snapshot snapshot : liveSnapshots) {
                for (ArchiveStore.ArchivedBlockHandle handle : snapshot.state.archived.values()) {
                    hashes.add(handle.contentHash());
                }
            }
            return hashes;
        }
    }

    private static Map<String, List<Snapshot.TxRef>> deepCopyRefs(
            Map<String, List<Snapshot.TxRef>> source) {
        Map<String, List<Snapshot.TxRef>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Snapshot.TxRef>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private static Map<String, List<Snapshot.ChangeRef>> deepCopyChangeRefs(
            Map<String, List<Snapshot.ChangeRef>> source) {
        Map<String, List<Snapshot.ChangeRef>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Snapshot.ChangeRef>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private static TreeMap<Long, BlockResult> freeze(TreeMap<Long, BlockResult> map) {
        return map;
    }

    private static TreeMap<Long, ArchiveStore.ArchivedBlockHandle> freezeHandles(
            TreeMap<Long, ArchiveStore.ArchivedBlockHandle> map) {
        return map;
    }

    private static Map<String, List<Snapshot.TxRef>> freeze(
            Map<String, List<Snapshot.TxRef>> map) {
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<Snapshot.ChangeRef>> freezeChanges(
            Map<String, List<Snapshot.ChangeRef>> map) {
        return Collections.unmodifiableMap(map);
    }
}
