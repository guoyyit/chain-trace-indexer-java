package com.github.guoyyit.chaintraceindexerjava.archive;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.store.ArchivedMarker;
import com.github.guoyyit.chaintraceindexerjava.store.LogRecord;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves finalized, safely below the reorg-safe depth blocks from the hot in-memory index
 * into content-addressed archive files.
 *
 * <p>Order per block guarantees crash safety: (1) write the immutable archive object
 * (atomic temp+fsync+rename), (2) append {@code BlockArchived} to the WAL, (3) publish the
 * marker to the live index. Garbage collection deletes only objects unreachable from the
 * current state and from every outstanding snapshot. Archived history remains queryable
 * through the same snapshot API.
 */
public final class ArchiveService {

    private final ChainIndexer indexer;

    public ArchiveService(ChainIndexer indexer) {
        this.indexer = indexer;
    }

    public synchronized ArchiveResult archiveUpTo(long toHeight) {
        ChainIndexer.ArchiveRange<ArchiveOutcome> range =
                indexer.runArchive(toHeight, this::archiveOne);
        int before = countArchiveFiles();
        garbageCollect();
        int removed = before - countArchiveFiles();
        return new ArchiveResult(range.fromHeight(), range.toHeight(),
                range.heights().size(), range.heights(), Math.max(0, removed));
    }

    ArchiveOutcome archiveOne(long height) {
        BlockResult block;
        try (Snapshot snapshot = indexer.snapshot()) {
            block = snapshot.block(height);
        }
        if (block == null) {
            return null;
        }
        List<String> touched = touchedAddresses(block);
        String contentHash = indexer.archiveStore().putBlock(height, block, touched);
        ArchivedMarker marker = ArchivedMarker.from(block);
        indexer.walLog().append(new LogRecord.BlockArchived(marker));
        indexer.traceIndexer().markArchived(marker, contentHash);
        return new ArchiveOutcome(marker, contentHash);
    }

    void garbageCollect() {
        indexer.archiveStore().deleteUnreferenced(indexer.referencedArchiveHashes());
    }

    private int countArchiveFiles() {
        return indexer.archiveStore().objectCount();
    }

    private static List<String> touchedAddresses(BlockResult block) {
        List<String> touched = new ArrayList<>();
        for (var allocation : block.allocations()) {
            if (!touched.contains(allocation.address())) {
                touched.add(allocation.address());
            }
        }
        for (var tx : block.transactions()) {
            for (var change : tx.changes()) {
                if (!touched.contains(change.address())) {
                    touched.add(change.address());
                }
            }
        }
        return List.copyOf(touched);
    }

    record ArchiveOutcome(ArchivedMarker marker, String contentHash) {
    }
}
