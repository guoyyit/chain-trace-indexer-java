package com.github.guoyyit.chaintraceindexerjava.indexer;

import com.github.guoyyit.chaintraceindexerjava.exec.ReplayEngine;
import com.github.guoyyit.chaintraceindexerjava.exec.WorldState;
import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.github.guoyyit.chaintraceindexerjava.model.ChangeType;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.RawTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.store.ArchiveStore;
import com.github.guoyyit.chaintraceindexerjava.store.ArchivedMarker;
import com.github.guoyyit.chaintraceindexerjava.store.LogRecord;
import com.github.guoyyit.chaintraceindexerjava.store.Snapshot;
import com.github.guoyyit.chaintraceindexerjava.store.TraceIndexer;
import com.github.guoyyit.chaintraceindexerjava.store.WalLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the chain head, write-ahead log, replay engine and queryable index.
 *
 * <p>Submission semantics:
 * <ul>
 *   <li>{@code height == tip+1} extends the canonical chain.</li>
 *   <li>{@code height <= tip} with a different hash triggers a reorg: shared prefix is
 *       kept, diverging blocks are undone, then the new branch is applied.</li>
 *   <li>same height + same hash is an idempotent no-op.</li>
 * </ul>
 * Every mutation is logged and fsynced before the in-memory index advances, so recovery
 * rebuilds exactly the last committed position. Methods are synchronized against writers;
 * readers use immutable snapshots and never block on indexing.
 */
public final class ChainIndexer implements AutoCloseable {

    private final Path dataDirectory;
    private final WalLog wal;
    private final ArchiveStore archiveStore;
    private final TraceIndexer traceIndexer;
    private final ReplayEngine replayEngine;
    private final WorldState worldState;
    private final long compactionThresholdBytes;

    private final List<BlockResult> chain = new ArrayList<>();
    private final Set<String> appliedTxHashes = new HashSet<>();

    public ChainIndexer(Path dataDirectory, long walSegmentBytes,
                        long compactionThresholdBytes) {
        this.dataDirectory = dataDirectory;
        this.compactionThresholdBytes = compactionThresholdBytes;
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        this.wal = new WalLog(dataDirectory.resolve("wal"), walSegmentBytes);
        this.archiveStore = new ArchiveStore(dataDirectory.resolve("archive"));
        this.traceIndexer = new TraceIndexer(archiveStore);
        this.worldState = new WorldState();
        this.replayEngine = new ReplayEngine(worldState);
        recover();
    }

    private void recover() {
        java.util.List<ArchivedMarker> archivedMarkers = new ArrayList<>();
        for (LogRecord record : wal.readAll()) {
            if (record instanceof LogRecord.BlockCommitted committed) {
                applyCommittedToState(committed.block(), false);
            } else if (record instanceof LogRecord.BlockUndone undone) {
                undoCommittedFromState(undone.height());
            } else if (record instanceof LogRecord.BlockArchived archived) {
                archivedMarkers.add(archived.marker());
            }
        }
        rebuildWorldState();
        for (ArchivedMarker marker : archivedMarkers) {
            recoverArchivedMarker(marker);
        }
    }

    private void recoverArchivedMarker(ArchivedMarker marker) {
        Snapshot snapshot = traceIndexer.snapshot();
        try {
            if (snapshot.archivedHandle(marker.height()) != null) {
                return;
            }
            BlockResult block = snapshot.block(marker.height());
            if (block == null) {
                return;
            }
            String contentHash = archiveStore.putBlock(marker.height(), block,
                    marker.addressChangeCounts().stream()
                            .map(ArchivedMarker.AddressChangeCount::address).toList());
            traceIndexer.markArchived(marker, contentHash);
        } finally {
            snapshot.close();
        }
    }

    public synchronized BlockSubmissionResult submitBlock(RawBlock block) {
        long tip = chain.isEmpty() ? -1L : chain.get(chain.size() - 1).height();

        if (block.height() <= tip) {
            BlockResult existing = chain.get((int) block.height());
            if (existing.hash().equals(block.hash())) {
                if (block.height() < tip) {
                    return rollbackTo(block.height());
                }
                return new BlockSubmissionResult("ignored_duplicate_block",
                        block.height(), tip, currentChainView());
            }
            if (block.height() == 0) {
                return reorganize(block);
            }
            if (block.height() != tip) {
                throw new ChainIndexerException.MissingParent(
                        "reorg submission at height " + block.height()
                                + " is not the current tip " + tip
                                + "; resubmit the new tip block (one block at a time)");
            }
                return reorganize(block);
        }
        if (block.height() != tip + 1) {
            throw new ChainIndexerException.NonSequentialHeight(
                    "expected height " + (tip + 1) + " but got " + block.height());
        }

        BlockResult result = executeAndDetectDuplicates(block);
        wal.append(new LogRecord.BlockCommitted(result));
        applyCommittedToState(result, false);
        maybeCompact();
        return new BlockSubmissionResult("extended", block.height(),
                traceIndexer.tipHeight(), currentChainView());
    }

    private synchronized BlockSubmissionResult rollbackTo(long anchorHeight) {
        long oldTip = chain.get(chain.size() - 1).height();
        for (long height = oldTip; height > anchorHeight; height--) {
            wal.append(new LogRecord.BlockUndone(height));
            undoCommittedFromState(height);
        }
        maybeCompact();
        return new BlockSubmissionResult("rolled_back", anchorHeight,
                traceIndexer.tipHeight(), currentChainView());
    }

    private synchronized BlockSubmissionResult reorganize(RawBlock newTipBlock) {
        long height = newTipBlock.height();
        if (height == 0) {
            return replaceFromGenesis(newTipBlock);
        }
        BlockResult parent = chain.get((int) height - 1);
        if (!parent.hash().equals(newTipBlock.prevHash())) {
            throw new ChainIndexerException.MissingParent(
                    "block " + newTipBlock.hash() + " at height " + height
                            + " declares prevHash " + newTipBlock.prevHash()
                            + " but current parent at that height is " + parent.hash()
                            + "; undo deeper blocks first");
        }

        wal.append(new LogRecord.BlockUndone(height));
        undoCommittedFromState(height);
        BlockResult result = executeAndDetectDuplicates(newTipBlock);
        wal.append(new LogRecord.BlockCommitted(result));
        applyCommittedToState(result, false);
        maybeCompact();
        return new BlockSubmissionResult("reorganized", height,
                traceIndexer.tipHeight(), currentChainView());
    }

    private synchronized BlockSubmissionResult replaceFromGenesis(RawBlock genesis) {
        for (long height = chain.get(chain.size() - 1).height(); height >= 0; height--) {
            wal.append(new LogRecord.BlockUndone(height));
            undoCommittedFromState(height);
        }
        BlockResult result = executeAndDetectDuplicates(genesis);
        wal.append(new LogRecord.BlockCommitted(result));
        applyCommittedToState(result, false);
        maybeCompact();
        return new BlockSubmissionResult("reorganized", 0,
                traceIndexer.tipHeight(), currentChainView());
    }

    private BlockResult executeAndDetectDuplicates(RawBlock block) {
        List<com.github.guoyyit.chaintraceindexerjava.model.StateChange> allocationChanges =
                new ArrayList<>();
        for (RawBlock.Allocation allocation : block.allocations()) {
            long oldBalance = worldState.getBalance(allocation.address());
            worldState.putBalance(allocation.address(), allocation.balance());
            allocationChanges.add(new StateChange(allocation.address(),
                    ChangeType.BALANCE, null, Long.toString(oldBalance),
                    Long.toString(allocation.balance()), -1));
        }
        List<TransactionResult> results = new ArrayList<>();
        for (RawTransaction raw : block.transactions()) {
            if (appliedTxHashes.contains(raw.hash())) {
                results.add(replayEngine.skipDuplicate(raw));
            } else {
                TransactionResult result = replayEngine.executeTransaction(raw);
                appliedTxHashes.add(raw.hash());
                results.add(result);
            }
        }
        return new BlockResult(block.height(), block.hash(),
                List.copyOf(allocationChanges), List.copyOf(results));
    }

    private void applyCommittedToState(BlockResult block, boolean recovery) {
        chain.add(block);
        for (TransactionResult tx : block.transactions()) {
            if (tx.applied()) {
                appliedTxHashes.add(tx.hash());
            }
        }
        traceIndexer.applyBlock(block);
    }

    private void undoCommittedFromState(long height) {
        int index = (int) height;
        BlockResult removed = chain.remove(index);
        for (TransactionResult tx : removed.transactions()) {
            if (tx.applied()) {
                appliedTxHashes.remove(tx.hash());
            }
        }
        traceIndexer.undoBlock(height, removed.hash());
        rebuildWorldState();
    }

    private void rebuildWorldState() {
        clearWorldState();
        Snapshot snapshot = traceIndexer.snapshot();
        try {
            for (long height : snapshot.heights()) {
                BlockResult block = snapshot.block(height);
                for (StateChange allocation : block.allocations()) {
                    if (allocation.newValue() != null
                            && allocation.type() == ChangeType.BALANCE) {
                        worldState.putBalance(allocation.address(),
                                Long.parseLong(allocation.newValue()));
                    }
                }
                for (TransactionResult tx : block.transactions()) {
                    if (!tx.applied() || !tx.success() || tx.trace() == null) {
                        continue;
                    }
                    for (StateChange change : tx.changes()) {
                        if (change.newValue() == null) {
                            continue;
                        }
                        switch (change.type()) {
                            case BALANCE -> worldState.putBalance(change.address(),
                                    Long.parseLong(change.newValue()));
                            case NONCE -> worldState.putNonce(change.address(),
                                    Long.parseLong(change.newValue()));
                            case STORAGE -> worldState.putStorage(change.address(),
                                    change.key(), change.newValue());
                        }
                    }
                }
            }
        } finally {
            snapshot.close();
        }
    }

    private void clearWorldState() {
        worldState.reset();
    }

    private List<IndexedBlock> currentChainView() {
        List<IndexedBlock> view = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            BlockResult block = chain.get(i);
            String prev = i == 0 ? null : chain.get(i - 1).hash();
            view.add(new IndexedBlock(block.height(), block.hash(), prev));
        }
        return List.copyOf(view);
    }

    private void maybeCompact() {
        try {
            long size = walSizeBytes();
            if (size < compactionThresholdBytes) {
                return;
            }
            wal.compactTo(wal.readAll());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private long walSizeBytes() throws IOException {
        Path walDirectory = dataDirectory.resolve("wal");
        long total = 0;
        if (!Files.isDirectory(walDirectory)) {
            return 0;
        }
        try (var stream = Files.list(walDirectory)) {
            for (Path path : stream.toList()) {
                total += Files.size(path);
            }
        }
        return total;
    }

    public Snapshot snapshot() {
        return traceIndexer.snapshot();
    }

    public TraceIndexer traceIndexer() {
        return traceIndexer;
    }

    public ArchiveStore archiveStore() {
        return archiveStore;
    }

    public WalLog walLog() {
        return wal;
    }

    public synchronized long tipHeight() {
        return traceIndexer.tipHeight();
    }

    public synchronized String tipHash() {
        if (chain.isEmpty()) {
            return null;
        }
        return chain.get(chain.size() - 1).hash();
    }

    public java.util.Set<String> referencedArchiveHashes() {
        return traceIndexer.referencedContentHashes();
    }

    /**
     * Archives the contiguous range of hot blocks up to {@code toHeight}.
     * Already-archived heights are skipped. The mutator is invoked while holding the
     * writer monitor so archive/undo/apply never interleave.
     */
    public synchronized <T> ArchiveRange<T> runArchive(long toHeight,
                                                       java.util.function.Function<Long, T> mutator) {
        List<Long> archivedHeights = new ArrayList<>();
        long tip = chain.isEmpty() ? -1L : chain.get(chain.size() - 1).height();
        long bounded = Math.min(toHeight, tip);
        for (long height = 0; height <= bounded; height++) {
            Snapshot snapshot = traceIndexer.snapshot();
            boolean alreadyArchived;
            try {
                alreadyArchived = snapshot.archivedHandle(height) != null;
            } finally {
                snapshot.close();
            }
            if (alreadyArchived) {
                continue;
            }
            T outcome = mutator.apply(height);
            if (outcome != null) {
                archivedHeights.add(height);
            }
        }
        return new ArchiveRange<>(0,
                archivedHeights.isEmpty() ? -1L : archivedHeights.get(archivedHeights.size() - 1),
                List.copyOf(archivedHeights));
    }

    public record ArchiveRange<T>(long fromHeight, long toHeight, java.util.List<Long> heights) {
    }

    @Override
    public synchronized void close() {
        wal.close();
        archiveStore.close();
    }
}
