package com.github.guoyyit.chaintraceindexerjava.index;

import com.github.guoyyit.chaintraceindexerjava.model.ChainStatus;
import com.github.guoyyit.chaintraceindexerjava.storage.SegmentData;

import java.util.ArrayList;
import java.util.List;

/**
 * Copy on write in memory index of the live (non archived) canonical suffix.
 *
 * <p>Every mutation rebuilds and publishes one immutable {@link Snapshot}
 * atomically. Readers take {@link #current()} without locks and keep querying
 * the generation they started from, so slow queries never block indexing and
 * uncommitted intermediate states are never observable.
 */
public final class LiveIndex {

    private final ArchiveManager archives;

    private final List<SegmentDataView> live = new ArrayList<>();
    private volatile Snapshot current;

    public LiveIndex(ArchiveManager archives) {
        this.archives = archives;
        publish();
    }

    public Snapshot current() {
        return current;
    }

    public synchronized void addBlock(SegmentData segment) {
        live.add(toView(segment));
        publish();
    }

    /**
     * Loads a recovered segment without re-executing it.
     */
    public synchronized void loadSegment(SegmentData segment) {
        live.add(toView(segment));
        publish();
    }

    /**
     * Removes live blocks with height strictly above {@code height}.
     */
    public synchronized void truncateAbove(long height) {
        live.removeIf(segment -> segment.height() > height);
        publish();
    }

    /**
     * Drops live blocks up to and including {@code toHeight} after they have
     * been compressed into an archive.
     */
    public synchronized void evictArchived(long toHeight) {
        live.removeIf(segment -> segment.height() <= toHeight);
        publish();
    }

    public synchronized long headHeight() {
        return live.isEmpty() ? archives.archivedTo() : live.get(live.size() - 1).height();
    }

    public synchronized String headHash() {
        if (live.isEmpty()) {
            return null;
        }
        return live.get(live.size() - 1).blockHash();
    }

    private SegmentDataView toView(SegmentData segment) {
        return new SegmentDataView(segment.height(), segment.blockHash(),
                List.copyOf(segment.frames()), List.copyOf(segment.changes()));
    }

    private void publish() {
        long headHeight = headHeightInternal();
        String headHash = headHashInternal();
        current = new Snapshot(List.copyOf(live), archives.freeze(),
                new ChainStatus(headHeight, headHash, archives.archivedTo()));
    }

    private long headHeightInternal() {
        return live.isEmpty() ? archives.archivedTo() : live.get(live.size() - 1).height();
    }

    private String headHashInternal() {
        if (live.isEmpty()) {
            return null;
        }
        return live.get(live.size() - 1).blockHash();
    }
}
