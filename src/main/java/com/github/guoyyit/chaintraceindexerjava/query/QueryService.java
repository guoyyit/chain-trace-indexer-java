package com.github.guoyyit.chaintraceindexerjava.query;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.CallFrame;
import com.github.guoyyit.chaintraceindexerjava.model.Page;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-side service. Every query opens a fresh immutable {@link Snapshot} and closes it
 * when done, so a query evaluates a single committed version: results are unaffected by
 * blocks indexed afterwards, and slow readers never block writers.
 *
 * <p>Stable ordering: (height ascending, transaction index ascending, change index
 * ascending). Trace frames are ordered by depth-first pre-order traversal.
 */
public final class QueryService {

    private final ChainIndexer indexer;

    public QueryService(ChainIndexer indexer) {
        this.indexer = indexer;
    }

    public Page<TransactionResult> queryTracesByAddress(String address, long fromHeight,
                                                        long toHeight, long offset, int limit) {
        try (Snapshot snapshot = indexer.snapshot()) {
            List<Snapshot.TxRef> refs = snapshot.transactionsByAddress(
                    address, fromHeight, toHeight);
            List<TransactionResult> traceable = new ArrayList<>();
            for (Snapshot.TxRef ref : refs) {
                TransactionResult tx = snapshot.transaction(ref.txHash());
                if (tx != null && tx.trace() != null) {
                    traceable.add(tx);
                }
            }
            long total = traceable.size();
            return page(sliceRefs(traceable, offset, limit), limit, offset, total);
        }
    }

    public Page<StateChange> queryChangesByAddress(String address, long fromHeight,
                                                   long toHeight, long offset, int limit) {
        try (Snapshot snapshot = indexer.snapshot()) {
            List<Snapshot.ChangeRef> refs = snapshot.changes(address, fromHeight, toHeight);
            long total = refs.size();
            List<StateChange> items = new ArrayList<>();
            for (Snapshot.ChangeRef ref : sliceRefs(refs, offset, limit)) {
                items.add(snapshot.changeAt(ref));
            }
            return page(items, limit, offset, total);
        }
    }

    public TraceByHashView queryTraceByHash(String txHash, long traceOffset, int traceLimit,
                                            long changeOffset, int changeLimit) {
        try (Snapshot snapshot = indexer.snapshot()) {
            List<Snapshot.TxRef> refs = snapshot.transactionsByHash(txHash);
            if (refs.isEmpty()) {
                return null;
            }
            Snapshot.TxRef ref = refs.get(0);
            TransactionResult tx = snapshot.transaction(txHash);
            if (tx == null) {
                return null;
            }

            List<CallFrame> allFrames = snapshot.flattenTrace(txHash);
            List<CallFrame> framePage = sliceRefs(allFrames, traceOffset, traceLimit);
            Page<CallFrame> tracePage = new Page<>(framePage, traceLimit, traceOffset,
                    allFrames.size(), traceOffset + framePage.size() < allFrames.size());

            List<Snapshot.ChangeRef> changeRefs = snapshot.changesByTx(txHash);
            List<StateChange> changeItems = new ArrayList<>();
            for (Snapshot.ChangeRef changeRef : sliceRefs(changeRefs, changeOffset, changeLimit)) {
                changeItems.add(snapshot.changeAt(changeRef));
            }
            Page<StateChange> changes = new Page<>(changeItems, changeLimit, changeOffset,
                    changeRefs.size(),
                    changeOffset + changeItems.size() < changeRefs.size());

            return new TraceByHashView(ref.height(), tx, tracePage, changes);
        }
    }

    private static <T> List<T> sliceRefs(List<T> source, long offset, int limit) {
        if (offset < 0 || offset >= source.size()) {
            return List.of();
        }
        int from = (int) offset;
        int to = (int) Math.min(source.size(), offset + (long) limit);
        return source.subList(from, to);
    }

    private static <T> Page<T> page(List<T> items, int limit, long offset, long total) {
        boolean hasMore = offset + items.size() < total;
        return new Page<>(List.copyOf(items), limit, offset, total, hasMore);
    }

    public record TraceByHashView(
            long height,
            TransactionResult transaction,
            Page<CallFrame> tracePage,
            Page<StateChange> changes
    ) {
    }
}
