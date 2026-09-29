package com.github.guoyyit.chaintraceindexerjava.store;

import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.model.CallFrame;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable point-in-time view of the index.
 *
 * <p>Snapshots are copy-on-write: {@code TraceIndexer.applyBlock/undoBlock} build a new
 * Snapshot and atomically publish it. A query holds its own Snapshot for its whole
 * duration, so it never observes a half-applied block and is never blocked by indexing.
 * Archived blocks contribute only lightweight handles; full payloads are loaded from
 * {@link ArchiveStore} on demand.
 */
public final class Snapshot implements AutoCloseable {

    final ImmutableState state;
    private final TraceIndexer owner;

    Snapshot(ImmutableState state, TraceIndexer owner) {
        this.state = state;
        this.owner = owner;
    }

    public long tipHeight() {
        return state.tipHeight;
    }

    public String blockHash(long height) {
        ArchiveStore.ArchivedBlockHandle handle = state.archived.get(height);
        if (handle != null) {
            return handle.blockHash();
        }
        BlockResult block = state.blocks.get(height);
        return block == null ? null : block.hash();
    }

    public List<Long> heights() {
        return List.copyOf(state.heights);
    }

    public boolean containsHeight(long height) {
        return state.blocks.containsKey(height) || state.archived.containsKey(height);
    }

    public BlockResult block(long height) {
        BlockResult hot = state.blocks.get(height);
        if (hot != null) {
            return hot;
        }
        ArchiveStore.ArchivedBlockHandle handle = state.archived.get(height);
        return handle == null ? null : owner.archiveStore.readBlock(handle);
    }

    public List<TxRef> transactionsByHash(String txHash) {
        return List.copyOf(state.txByHash.getOrDefault(txHash, Collections.emptyList()));
    }

    public List<TxRef> transactionsByAddress(String address, long fromHeight, long toHeight) {
        List<TxRef> result = new ArrayList<>();
        for (TxRef ref : state.txByAddress.getOrDefault(address, Collections.emptyList())) {
            if (ref.height() >= fromHeight && ref.height() <= toHeight) {
                result.add(ref);
            }
        }
        return result;
    }

    public List<ChangeRef> changes(String address, long fromHeight, long toHeight) {
        List<ChangeRef> result = new ArrayList<>();
        for (ChangeRef ref : state.changesByAddress.getOrDefault(address, Collections.emptyList())) {
            if (ref.height() >= fromHeight && ref.height() <= toHeight) {
                result.add(ref);
            }
        }
        return result;
    }

    public List<ChangeRef> changesByTx(String txHash) {
        return List.copyOf(state.changesByTxHash.getOrDefault(txHash, Collections.emptyList()));
    }

    public int txCount(String address, long fromHeight, long toHeight) {
        return transactionsByAddress(address, fromHeight, toHeight).size();
    }

    public int changeCount(String address, long fromHeight, long toHeight) {
        return changes(address, fromHeight, toHeight).size();
    }

    public long changeCountByTx(String txHash) {
        return changesByTx(txHash).size();
    }

    public List<ChangeRef> changesPage(String address, long fromHeight, long toHeight,
                                       long offset, int limit) {
        return slice(changes(address, fromHeight, toHeight), offset, limit);
    }

    public List<TxRef> transactionsPage(String address, long fromHeight, long toHeight,
                                        long offset, int limit) {
        return slice(transactionsByAddress(address, fromHeight, toHeight), offset, limit);
    }

    public List<ChangeRef> changesByTxPage(String txHash, long offset, int limit) {
        return slice(changesByTx(txHash), offset, limit);
    }

    public BlockResult blockForTx(String txHash) {
        List<TxRef> refs = transactionsByHash(txHash);
        return refs.isEmpty() ? null : block(refs.get(0).height());
    }

    public TransactionResult transaction(String txHash) {
        List<TxRef> refs = transactionsByHash(txHash);
        if (refs.isEmpty()) {
            return null;
        }
        TxRef ref = refs.get(0);
        BlockResult block = block(ref.height());
        return block == null ? null : block.transactions().get(ref.txIndex());
    }

    public List<CallFrame> flattenTrace(String txHash) {
        TransactionResult tx = transaction(txHash);
        if (tx == null || tx.trace() == null) {
            return List.of();
        }
        List<CallFrame> frames = new ArrayList<>();
        collectFrames(tx.trace(), frames);
        return List.copyOf(frames);
    }

    private static void collectFrames(CallFrame frame, List<CallFrame> out) {
        out.add(frame);
        for (CallFrame child : frame.children()) {
            collectFrames(child, out);
        }
    }

    public List<CallFrame> flattenTracePage(String txHash, long offset, int limit) {
        return slice(flattenTrace(txHash), offset, limit);
    }

    public long traceFrameCount(String txHash) {
        return flattenTrace(txHash).size();
    }

    public StateChange changeAt(ChangeRef ref) {
        BlockResult block = block(ref.height());
        if (ref.txIndex() < 0) {
            return block.allocations().get(ref.changeIndex());
        }
        return block.transactions().get(ref.txIndex()).changes().get(ref.changeIndex());
    }

    public ArchiveStore.ArchivedBlockHandle archivedHandle(long height) {
        return state.archived.get(height);
    }

    private static <T> List<T> slice(List<T> source, long offset, int limit) {
        if (offset < 0 || offset >= source.size()) {
            return List.of();
        }
        int from = (int) offset;
        int to = (int) Math.min(source.size(), offset + (long) limit);
        return List.copyOf(source.subList(from, to));
    }

    @Override
    public void close() {
        owner.releaseSnapshot(this);
    }

    public record TxRef(long height, int txIndex, String txHash) {
    }

    public record ChangeRef(long height, int txIndex, int changeIndex, String txHash) {
    }
}
