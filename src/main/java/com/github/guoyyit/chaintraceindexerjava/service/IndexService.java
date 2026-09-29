package com.github.guoyyit.chaintraceindexerjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.chaintraceindexerjava.execution.AccountState;
import com.github.guoyyit.chaintraceindexerjava.execution.ExecResult;
import com.github.guoyyit.chaintraceindexerjava.execution.VirtualMachine;
import com.github.guoyyit.chaintraceindexerjava.index.ArchiveManager;
import com.github.guoyyit.chaintraceindexerjava.index.LiveIndex;
import com.github.guoyyit.chaintraceindexerjava.index.Snapshot;
import com.github.guoyyit.chaintraceindexerjava.model.ArchiveResult;
import com.github.guoyyit.chaintraceindexerjava.model.BlockPayload;
import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.github.guoyyit.chaintraceindexerjava.model.SkippedTransaction;
import com.github.guoyyit.chaintraceindexerjava.model.SubmitResult;
import com.github.guoyyit.chaintraceindexerjava.storage.BlockStore;
import com.github.guoyyit.chaintraceindexerjava.storage.SegmentData;
import com.github.guoyyit.chaintraceindexerjava.storage.WalEntry;
import com.github.guoyyit.chaintraceindexerjava.storage.WalStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single writer orchestrator for submission, incremental indexing, reorg,
 * crash recovery and archiving.
 *
 * <p><b>Commit protocol.</b> A block becomes visible exactly once:
 * <ol>
 *   <li>the raw block is durably stored in {@link BlockStore},</li>
 *   <li>the derived segment is written via temp-file + atomic rename,</li>
 *   <li>only then a {@code COMMIT} entry is fsynced and the snapshot published.</li>
 * </ol>
 * A crash between 2 and 3 leaves an orphan file without a COMMIT marker;
 * recovery ignores it and resumes from the last committed height.
 *
 * <p><b>Reorg.</b> A branch disagreement is handled by locating the common
 * ancestor, discarding the old suffix and executing the new branch. One
 * {@code RESET} entry fences the whole switch, so a crash mid-reorg resolves to
 * one complete chain. Deep reorgs truncate affected archives and deterministically
 * replay raw blocks from genesis.
 *
 * <p><b>Concurrency.</b> Mutations are {@code synchronized}; queries take an
 * immutable {@link Snapshot} and run without the writer lock.
 */
public class IndexService {

    private final BlockStore blockStore;
    private final WalStore walStore;
    private final LiveIndex index;
    private final ArchiveManager archives;
    private final VirtualMachine vm = new VirtualMachine();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<Long, String> canonicalHashes = new LinkedHashMap<>();
    private AccountState headState = new AccountState();

    public IndexService(BlockStore blockStore, WalStore walStore, LiveIndex index, ArchiveManager archives) {
        this.blockStore = blockStore;
        this.walStore = walStore;
        this.index = index;
        this.archives = archives;
    }

    public void recover() {
        walStore.cleanTempArtifacts();
        List<WalEntry> entries = walStore.replay();

        int lastReset = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (WalStore.TYPE_RESET.equals(entries.get(i).type())) {
                lastReset = i;
            }
        }

        if (lastReset >= 0) {
            ResetDetail detail = parseReset(entries.get(lastReset).detail());
            long archiveTo = detail.archiveTo();
            archives.load(archiveTo);
            canonicalHashes.clear();
            for (int height = 0; height < detail.hashes().size(); height++) {
                canonicalHashes.put((long) height, detail.hashes().get(height));
            }
            long head = canonicalHashes.size() - 1L;
            walStore.deleteSegmentsAbove(head);

            Map<Long, SegmentData> recovered = new LinkedHashMap<>();
            for (long height = archiveTo + 1; height <= head; height++) {
                walStore.removeNonCanonicalSegment(height, canonicalHashes.get(height));
                SegmentData segment = walStore.readSegmentAt(height);
                if (segment == null || !segment.blockHash().equals(canonicalHashes.get(height))) {
                    segment = regenerateSegment(height);
                }
                recovered.put(height, segment);
            }
            recovered.values().forEach(index::loadSegment);

            for (long height = archiveTo + 1; height <= head; height++) {
                long finalHeight = height;
                boolean committed = entries.subList(lastReset + 1, entries.size()).stream()
                        .anyMatch(entry -> WalStore.TYPE_COMMIT.equals(entry.type())
                                && entry.height() == finalHeight);
                if (!committed) {
                    SegmentData segment = recovered.get(height);
                    walStore.append(new WalEntry(WalStore.TYPE_COMMIT, height,
                            segment.blockHash(), segment.parentHash(), null));
                }
            }
        } else {
            archives.load(Long.MAX_VALUE);
            long head = -1L;
            for (WalEntry entry : entries) {
                if (WalStore.TYPE_COMMIT.equals(entry.type())) {
                    canonicalHashes.put(entry.height(), entry.blockHash());
                    head = Math.max(head, entry.height());
                }
            }
            for (long height = archives.archivedTo() + 1; height <= head; height++) {
                walStore.removeNonCanonicalSegment(height, canonicalHashes.get(height));
                SegmentData segment = walStore.readSegmentAt(height);
                if (segment == null || !segment.blockHash().equals(canonicalHashes.get(height))) {
                    segment = regenerateSegment(height);
                }
                index.loadSegment(segment);
            }
        }
        headState = rebuildState(headHeightInternal());
    }

    private ResetDetail parseReset(String detail) {
        try {
            return mapper.readValue(detail, ResetDetail.class);
        } catch (Exception ex) {
            throw new IllegalStateException("corrupt RESET entry", ex);
        }
    }

    private String writeReset(ResetDetail detail) {
        try {
            return mapper.writeValueAsString(detail);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Deterministically rebuilds one segment from raw blocks by replaying the
     * canonical prefix through the target height.
     */
    private SegmentData regenerateSegment(long height) {
        RawBlock target = blockStore.load(canonicalHashes.get(height));
        if (target == null) {
            throw new IllegalStateException("missing canonical block at height " + height);
        }
        AccountState state = rebuildState(height - 1);
        ExecResult result = target.height() == 0
                ? vm.executeGenesis(target, state)
                : vm.executeBlock(target, state);
        SegmentData segment = new SegmentData(target.height(), target.hash(),
                target.parentHash(), result.frames(), result.changes());
        walStore.writeSegment(segment);
        return segment;
    }

    /**
     * @return a fresh world state reproduced by replaying canonical blocks
     * genesis..{@code toHeight}
     */
    private AccountState rebuildState(long toHeight) {
        AccountState state = new AccountState();
        for (long current = 0; current <= toHeight; current++) {
            RawBlock block = blockStore.load(canonicalHashes.get(current));
            if (block == null) {
                throw new IllegalStateException("missing canonical block at height " + current);
            }
            if (current == 0) {
                vm.executeGenesis(block, state);
            } else {
                vm.executeBlock(block, state);
            }
        }
        return state;
    }

    public synchronized SubmitResult submit(BlockPayload payload) {
        if (payload == null || payload.blocks() == null || payload.blocks().isEmpty()) {
            throw new IllegalArgumentException("blocks must not be empty");
        }
        List<RawBlock> incoming = new ArrayList<>(payload.blocks());
        incoming.sort(Comparator.comparingLong(RawBlock::height));
        for (RawBlock block : incoming) {
            validate(block);
            blockStore.save(block);
        }

        long head = headHeightInternal();
        long firstIncoming = incoming.get(0).height();
        long lastIncoming = incoming.get(incoming.size() - 1).height();

        // Simple fast path: pure extension above the current head.
        if (firstIncoming == head + 1) {
            return applyExtension(incoming);
        }
        // Submission entirely within known heights and identical: idempotent.
        if (lastIncoming <= head && allMatchCanonical(incoming)) {
            return new SubmitResult(List.of(), head, canonicalHashes.get(head), List.of());
        }
        // Otherwise it is (or contains) a reorg.
        long forkHeight = findForkHeight(incoming);
        return reorg(incoming, forkHeight);
    }

    private SubmitResult applyExtension(List<RawBlock> incoming) {
        List<Long> applied = new ArrayList<>();
        List<SkippedTransaction> skipped = new ArrayList<>();
        long expected = headHeightInternal() + 1;
        for (RawBlock block : incoming) {
            if (block.height() < expected) {
                if (block.hash().equals(canonicalHashes.get(block.height()))) {
                    continue;
                }
                long forkHeight = findForkHeight(incoming);
                return reorg(incoming, forkHeight);
            }
            if (block.height() != expected) {
                throw new IllegalArgumentException("non contiguous block at height " + block.height());
            }
            if (block.height() > 0
                    && !block.parentHash().equals(canonicalHashes.get(block.height() - 1))) {
                throw new IllegalArgumentException(
                        "parentHash mismatch at height " + block.height());
            }
            ExecResult result = applyBlock(block);
            applied.add(block.height());
            skipped.addAll(result.skipped());
            expected++;
        }
        return new SubmitResult(applied, headHeightInternal(),
                canonicalHashes.get(headHeightInternal()), skipped);
    }

    private ExecResult applyBlock(RawBlock block) {
        ExecResult result = block.height() == 0
                ? vm.executeGenesis(block, headState)
                : vm.executeBlock(block, headState);
        SegmentData segment = new SegmentData(block.height(), block.hash(), block.parentHash(),
                result.frames(), result.changes());
        walStore.writeSegment(segment);
        walStore.append(new WalEntry(WalStore.TYPE_COMMIT, block.height(),
                block.hash(), block.parentHash(), null));
        canonicalHashes.put(block.height(), block.hash());
        index.addBlock(segment);
        return result;
    }

    private boolean allMatchCanonical(List<RawBlock> incoming) {
        return incoming.stream()
                .allMatch(block -> block.hash().equals(canonicalHashes.get(block.height())));
    }

    private void validate(RawBlock block) {
        if (block.hash() == null || block.hash().isBlank()) {
            throw new IllegalArgumentException("block hash is required");
        }
        if (block.height() < 0) {
            throw new IllegalArgumentException("block height must be non negative");
        }
        if (block.height() > 0 && (block.parentHash() == null || block.parentHash().isBlank())) {
            throw new IllegalArgumentException(
                    "non genesis block requires parentHash at height " + block.height());
        }
    }

    private long findForkHeight(List<RawBlock> incoming) {
        // Walk from the lowest incoming block down its parentHash chain until
        // the hash matches the canonical chain at the same height.
        RawBlock cursor = incoming.stream()
                .min(Comparator.comparingLong(RawBlock::height))
                .orElseThrow();
        while (cursor != null) {
            if (cursor.hash().equals(canonicalHashes.get(cursor.height()))) {
                return cursor.height();
            }
            if (cursor.height() == 0) {
                break;
            }
            cursor = blockStore.load(cursor.parentHash());
        }
        throw new IllegalArgumentException(
                "submitted branch has no common ancestor with the canonical chain");
    }

    public Snapshot snapshot() {
        return index.current();
    }

    public synchronized ArchiveResult archive(long toHeight) {
        long head = headHeightInternal();
        if (toHeight > head) {
            throw new IllegalArgumentException("cannot archive beyond chain head");
        }
        if (toHeight <= archives.archivedTo()) {
            return new ArchiveResult(archives.archivedTo(), 0, 0L);
        }
        long fromHeight = archives.archivedTo() + 1;
        List<ArchiveManager.PathAndSegment> segments = new ArrayList<>();
        long sourceBytes = 0L;
        for (long height = fromHeight; height <= toHeight; height++) {
            WalStore.LocatedSegment located = walStore.locateSegment(height);
            if (located == null) {
                throw new IllegalStateException("missing live segment at height " + height);
            }
            if (!located.data().blockHash().equals(canonicalHashes.get(height))) {
                throw new IllegalStateException("segment not canonical at height " + height);
            }
            segments.add(new ArchiveManager.PathAndSegment(height, located.path(), located.data()));
            sourceBytes += located.path().toFile().length();
        }
        archives.compress(fromHeight, toHeight, segments);
        walStore.append(new WalEntry(WalStore.TYPE_ARCHIVE, toHeight,
                canonicalHashes.get(toHeight), null, Long.toString(fromHeight)));
        index.evictArchived(toHeight);
        return new ArchiveResult(toHeight, 1, sourceBytes);
    }

    private SubmitResult reorg(List<RawBlock> incoming, long forkHeight) {
        RawBlock forkBlock = incoming.stream()
                .filter(block -> block.height() == forkHeight)
                .findFirst()
                .orElse(null);
        if (forkBlock != null && !forkBlock.hash().equals(canonicalHashes.get(forkHeight))) {
            throw new IllegalArgumentException(
                    "branch does not connect at fork height " + forkHeight);
        }

        List<RawBlock> newBlocks = incoming.stream()
                .filter(block -> block.height() > forkHeight)
                .sorted(Comparator.comparingLong(RawBlock::height))
                .toList();
        if (newBlocks.isEmpty()) {
            long head = headHeightInternal();
            return new SubmitResult(List.of(), head, canonicalHashes.get(head), List.of());
        }

        // Deep reorg: finalized archives covering the fork must be expanded
        // back into live segments before the switch.
        boolean deep = forkHeight <= archives.archivedTo();
        if (deep) {
            archives.truncateAbove(forkHeight);
        }

        Map<Long, String> targetHashes = new LinkedHashMap<>();
        for (long height = 0; height <= forkHeight; height++) {
            targetHashes.put(height, canonicalHashes.get(height));
        }

        // Deep reorg: replay genesis..forkHeight once, re-expanding the fork
        // range into live segments as we go. Shallow reorg: take a copy of the
        // current head state, which is already at or above the fork.
        AccountState state;
        long liveStart = archives.archivedTo() + 1;
        if (deep) {
            state = new AccountState();
            for (long height = 0; height <= forkHeight; height++) {
                RawBlock prefixBlock = blockStore.load(targetHashes.get(height));
                if (prefixBlock == null) {
                    throw new IllegalStateException("missing canonical block at height " + height);
                }
                ExecResult prefixResult = height == 0
                        ? vm.executeGenesis(prefixBlock, state)
                        : vm.executeBlock(prefixBlock, state);
                if (height >= liveStart) {
                    SegmentData segment = new SegmentData(height, prefixBlock.hash(),
                            prefixBlock.parentHash(), prefixResult.frames(), prefixResult.changes());
                    walStore.writeSegment(segment);
                }
            }
        } else if (forkHeight < headHeightInternal()) {
            state = rebuildStateTo(forkHeight, targetHashes);
        } else {
            state = headState.copy();
        }

        List<SegmentData> newSegments = new ArrayList<>();
        List<SkippedTransaction> skipped = new ArrayList<>();
        long expected = forkHeight + 1;
        for (RawBlock block : newBlocks) {
            if (block.height() != expected) {
                throw new IllegalArgumentException(
                        "non contiguous branch block at height " + block.height());
            }
            if (!block.parentHash().equals(targetHashes.get(block.height() - 1))) {
                throw new IllegalArgumentException(
                        "parentHash mismatch at height " + block.height());
            }
            ExecResult result = block.height() == 0
                    ? vm.executeGenesis(block, state)
                    : vm.executeBlock(block, state);
            skipped.addAll(result.skipped());
            SegmentData segment = new SegmentData(block.height(), block.hash(),
                    block.parentHash(), result.frames(), result.changes());
            walStore.writeSegment(segment);
            newSegments.add(segment);
            targetHashes.put(block.height(), block.hash());
            expected++;
        }

        ResetDetail detail = new ResetDetail(forkHeight, List.copyOf(targetHashes.values()),
                archives.archivedTo());
        walStore.append(new WalEntry(WalStore.TYPE_RESET, forkHeight,
                canonicalHashes.get(forkHeight), null, writeReset(detail)));

        canonicalHashes.clear();
        canonicalHashes.putAll(targetHashes);
        walStore.deleteSegmentsAbove(forkHeight);
        long retainedArchiveTo = archives.archivedTo();
        index.truncateAbove(retainedArchiveTo);
        // Rebuild the common live prefix exactly once: the deep-reorg path has
        // already (re)written these segments; for a shallow reorg they simply
        // survive on disk and are reloaded here.
        for (long height = retainedArchiveTo + 1; height <= forkHeight; height++) {
            SegmentData kept = walStore.readSegmentAt(height);
            if (kept == null || !kept.blockHash().equals(targetHashes.get(height))) {
                kept = regenerateSegment(height);
            }
            walStore.append(new WalEntry(WalStore.TYPE_COMMIT, kept.height(),
                    kept.blockHash(), kept.parentHash(), null));
            index.addBlock(kept);
        }
        for (SegmentData segment : newSegments) {
            walStore.append(new WalEntry(WalStore.TYPE_COMMIT, segment.height(),
                    segment.blockHash(), segment.parentHash(), null));
            index.addBlock(segment);
        }

        List<Long> applied = newSegments.stream().map(SegmentData::height).toList();
        headState = state;
        return new SubmitResult(applied, headHeightInternal(),
                canonicalHashes.get(headHeightInternal()), skipped);
    }

    private AccountState rebuildStateTo(long height, Map<Long, String> hashes) {
        AccountState state = new AccountState();
        for (long current = 0; current <= height; current++) {
            RawBlock block = blockStore.load(hashes.get(current));
            if (block == null) {
                throw new IllegalStateException("missing canonical block at height " + current);
            }
            if (current == 0) {
                vm.executeGenesis(block, state);
            } else {
                vm.executeBlock(block, state);
            }
        }
        return state;
    }

    private long headHeightInternal() {
        return canonicalHashes.isEmpty() ? -1L
                : canonicalHashes.keySet().stream().max(Long::compare).orElse(-1L);
    }
}
