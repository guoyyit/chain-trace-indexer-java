package com.github.guoyyit.chaintraceindexerjava.index;

import com.github.guoyyit.chaintraceindexerjava.model.ChainStatus;
import com.github.guoyyit.chaintraceindexerjava.model.PageResult;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable query view of one committed index generation.
 *
 * <p>A snapshot owns an immutable list of live segments (derived data for
 * non archived blocks) and a reference to the immutable archive catalog at the
 * time of creation. Holding a snapshot guarantees stable, repeatable results
 * unaffected by later indexing, reorgs or archiving.
 *
 * <p>Ordering is fully deterministic:
 * <ul>
 *   <li>state changes: {@code (height, seq)}</li>
 *   <li>trace frames: {@code (height, txIndex, frameIndex)}</li>
 * </ul>
 */
public final class Snapshot {

    static final Comparator<StateChange> CHANGE_ORDER =
            Comparator.comparingLong(StateChange::blockHeight).thenComparingInt(StateChange::seq);

    static final Comparator<TraceFrame> FRAME_ORDER =
            Comparator.comparingLong(TraceFrame::blockHeight)
                    .thenComparingInt(TraceFrame::txIndex)
                    .thenComparingInt(TraceFrame::frameIndex);

    private final List<SegmentDataView> live;
    private final ArchiveReadView archives;
    private final ChainStatus status;

    Snapshot(List<SegmentDataView> live, ArchiveReadView archives, ChainStatus status) {
        this.live = List.copyOf(live);
        this.archives = archives;
        this.status = status;
    }

    public ChainStatus status() {
        return status;
    }

    public PageResult<StateChange> queryChanges(String address,
                                                String txHash,
                                                Long fromHeight,
                                                Long toHeight,
                                                long offset,
                                                int limit) {
        long from = fromHeight == null ? Long.MIN_VALUE : fromHeight;
        long to = toHeight == null ? Long.MAX_VALUE : toHeight;
        String normalizedAddress = address == null ? null : address.trim().toLowerCase();

        List<StateChange> rows = new ArrayList<>();
        if (archives != null) {
            rows.addAll(archives.findChanges(normalizedAddress, txHash, from, to));
        }
        for (SegmentDataView segment : live) {
            if (segment.height() < from || segment.height() > to) {
                continue;
            }
            for (StateChange change : segment.changes()) {
                if (matchesAddress(change.address(), normalizedAddress)
                        && matchesTx(change.txHash(), txHash)) {
                    rows.add(change);
                }
            }
        }
        rows.sort(CHANGE_ORDER);
        return page(rows, offset, limit);
    }

    public PageResult<TraceFrame> queryFrames(String txHash,
                                              String address,
                                              Long fromHeight,
                                              Long toHeight,
                                              long offset,
                                              int limit) {
        long from = fromHeight == null ? Long.MIN_VALUE : fromHeight;
        long to = toHeight == null ? Long.MAX_VALUE : toHeight;
        String normalizedAddress = address == null ? null : address.trim().toLowerCase();

        List<TraceFrame> rows = new ArrayList<>();
        if (archives != null) {
            rows.addAll(archives.findFrames(txHash, normalizedAddress, from, to));
        }
        for (SegmentDataView segment : live) {
            if (segment.height() < from || segment.height() > to) {
                continue;
            }
            for (TraceFrame frame : segment.frames()) {
                if (matchesTx(frame.txHash(), txHash)
                        && (normalizedAddress == null
                        || normalizedAddress.equals(frame.from())
                        || normalizedAddress.equals(frame.to()))) {
                    rows.add(frame);
                }
            }
        }
        rows.sort(FRAME_ORDER);
        return page(rows, offset, limit);
    }

    private static boolean matchesAddress(String actual, String wanted) {
        return wanted == null || wanted.equals(actual);
    }

    private static boolean matchesTx(String actual, String wanted) {
        return wanted == null || wanted.equals(actual);
    }

    private static <T> PageResult<T> page(List<T> all, long offset, int limit) {
        int safeLimit = Math.max(1, limit);
        long total = all.size();
        long start = Math.max(0, offset);
        long end = Math.min(total, start + safeLimit);
        List<T> items = start >= total ? List.of()
                : new ArrayList<>(all.subList((int) start, (int) end));
        long nextOffset = end >= total ? -1L : end;
        return new PageResult<>(Collections.unmodifiableList(items), total, start, safeLimit, nextOffset);
    }
}
