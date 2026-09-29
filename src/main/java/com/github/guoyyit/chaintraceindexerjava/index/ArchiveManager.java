package com.github.guoyyit.chaintraceindexerjava.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;
import com.github.guoyyit.chaintraceindexerjava.storage.SegmentData;

import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Manages compressed, immutable archive files covering finalized height
 * prefixes. Archived rows remain queryable and stay consistent with the live
 * portion of a {@link Snapshot}.
 */
public class ArchiveManager implements ArchiveReadView {

    private final Path archiveDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile List<ArchiveFile> files = new ArrayList<>();
    private volatile long archivedTo = -1L;

    public ArchiveManager(Path dataDir) {
        this.archiveDir = dataDir.resolve("archives");
        try {
            Files.createDirectories(archiveDir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Loads archive descriptors at startup and removes obsolete archive files.
     * {@code retainedMaxHeight} is the water mark recorded by the newest RESET
     * entry after a reorg (or {@code Long.MAX_VALUE} when no reorg happened).
     */
    public synchronized void load(long retainedMaxHeight) {
        List<ArchiveFile> loaded = new ArrayList<>();
        try (var stream = Files.list(archiveDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted()
                    .forEach(path -> {
                        ArchiveFile file = readManifest(path);
                        if (file.toHeight() <= retainedMaxHeight) {
                            loaded.add(file);
                        } else {
                            deleteQuietly(path);
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Collections.sort(loaded);
        files = loaded;
        archivedTo = loaded.isEmpty() ? -1L : loaded.get(loaded.size() - 1).toHeight();
    }

    public synchronized long getArchivedTo() {
        return archivedTo;
    }

    /**
     * Atomically compresses one finalized, contiguous height prefix. Segments
     * must be given in ascending height order and exactly cover
     * {@code fromHeight..toHeight}. The old live segment files are deleted only
     * after the archive file and its directory entry are durable.
     */
    public synchronized void compress(long fromHeight, long toHeight, List<PathAndSegment> segments) {
        long expectedFrom = archivedTo + 1;
        if (fromHeight != expectedFrom) {
            throw new IllegalArgumentException("archive range must be contiguous: expected "
                    + expectedFrom + " got " + fromHeight);
        }
        Path finalFile = archiveDir.resolve(String.format("archive-%019d-%019d.zip",
                fromHeight, toHeight));
        Path tmp = null;
        try {
            tmp = Files.createTempFile(archiveDir, "archive-", ".zip.tmp");
            long bytes = 0L;
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                Manifest manifest = new Manifest(fromHeight, toHeight,
                        segments.stream().map(PathAndSegment::height).toList());
                zip.write(mapper.writeValueAsBytes(manifest));
                zip.closeEntry();
                for (PathAndSegment segment : segments) {
                    bytes += writeSegmentEntries(zip, segment);
                }
            }
            Files.move(tmp, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;

            for (PathAndSegment segment : segments) {
                Files.deleteIfExists(segment.segmentPath());
            }
            ArchiveFile descriptor = new ArchiveFile(finalFile, fromHeight, toHeight,
                    segments.stream().map(PathAndSegment::height).toList(), bytes);
            List<ArchiveFile> next = new ArrayList<>(files);
            next.add(descriptor);
            Collections.sort(next);
            files = next;
            archivedTo = toHeight;
        } catch (IOException ex) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            throw new UncheckedIOException(ex);
        }
    }

    public List<TraceFrame> findFrames(String txHash, String address, long fromHeight, long toHeight) {
        return findFrames(files, txHash, address, fromHeight, toHeight);
    }

    private List<TraceFrame> findFrames(List<ArchiveFile> scope,
                                        String txHash, String address, long fromHeight, long toHeight) {
        List<TraceFrame> result = new ArrayList<>();
        for (ArchiveFile file : scope) {
            if (file.toHeight() < fromHeight || file.fromHeight() > toHeight) {
                continue;
            }
            for (Long height : file.heights()) {
                if (height < fromHeight || height > toHeight) {
                    continue;
                }
                SegmentPayload payload = readPayload(file, height);
                for (TraceFrame frame : payload.frames()) {
                    if (matchesHeight(frame.blockHeight(), fromHeight, toHeight)
                            && (txHash == null || txHash.equals(frame.txHash()))
                            && (address == null
                            || address.equals(frame.from()) || address.equals(frame.to()))) {
                        result.add(frame);
                    }
                }
            }
        }
        return result;
    }

    public List<StateChange> findChanges(String address, String txHash, long fromHeight, long toHeight) {
        return findChanges(files, address, txHash, fromHeight, toHeight);
    }

    private List<StateChange> findChanges(List<ArchiveFile> scope,
                                          String address, String txHash, long fromHeight, long toHeight) {
        List<StateChange> result = new ArrayList<>();
        for (ArchiveFile file : scope) {
            if (file.toHeight() < fromHeight || file.fromHeight() > toHeight) {
                continue;
            }
            for (Long height : file.heights()) {
                if (height < fromHeight || height > toHeight) {
                    continue;
                }
                SegmentPayload payload = readPayload(file, height);
                for (StateChange change : payload.changes()) {
                    if (matchesHeight(change.blockHeight(), fromHeight, toHeight)
                            && (address == null || address.equals(change.address()))
                            && (txHash == null || txHash.equals(change.txHash()))) {
                        result.add(change);
                    }
                }
            }
        }
        return result;
    }

    public long archivedTo() {
        return archivedTo;
    }

    /**
     * Drops archive files covering heights strictly above {@code toHeight}.
     * Used when a reorg reaches into the archived prefix; the affected blocks
     * are then replayed from raw block storage.
     */
    public synchronized void truncateAbove(long toHeight) {
        List<ArchiveFile> kept = new ArrayList<>();
        for (ArchiveFile file : files) {
            if (file.fromHeight() > toHeight) {
                deleteQuietly(file.path());
            } else if (file.toHeight() > toHeight) {
                deleteQuietly(file.path());
            } else {
                kept.add(file);
            }
        }
        Collections.sort(kept);
        files = kept;
        archivedTo = kept.isEmpty() ? -1L : kept.get(kept.size() - 1).toHeight();
    }

    /**
     * Returns an immutable read view over the currently committed archives.
     * Existing snapshots keep using their own frozen view, so truncating
     * archives for a deep reorg never corrupts an in-flight query.
     */
    public synchronized ArchiveReadView freeze() {
        List<ArchiveFile> frozenFiles = List.copyOf(files);
        long frozenWaterMark = archivedTo;
        return new ArchiveReadView() {
            @Override
            public List<TraceFrame> findFrames(String txHash, String address,
                                               long fromHeight, long toHeight) {
                return ArchiveManager.this.findFrames(frozenFiles, txHash, address, fromHeight, toHeight);
            }

            @Override
            public List<StateChange> findChanges(String address, String txHash,
                                                 long fromHeight, long toHeight) {
                return ArchiveManager.this.findChanges(frozenFiles, address, txHash, fromHeight, toHeight);
            }

            @Override
            public String toString() {
                return "FrozenArchiveView(to=" + frozenWaterMark + ", files=" + frozenFiles.size() + ")";
            }
        };
    }

    private long writeSegmentEntries(ZipOutputStream zip, PathAndSegment segment) throws IOException {
        long size = 0L;
        zip.putNextEntry(new ZipEntry("h" + segment.height() + "/frames.json"));
        byte[] frameBytes = mapper.writeValueAsBytes(segment.data().frames());
        zip.write(frameBytes);
        size += frameBytes.length;
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("h" + segment.height() + "/changes.json"));
        byte[] changeBytes = mapper.writeValueAsBytes(segment.data().changes());
        zip.write(changeBytes);
        size += changeBytes.length;
        zip.closeEntry();
        return size;
    }

    private ArchiveFile readManifest(Path path) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName())) {
                    Manifest manifest = mapper.readValue(zip.readAllBytes(), Manifest.class);
                    long size = Files.size(path);
                    return new ArchiveFile(path, manifest.fromHeight(), manifest.toHeight(),
                            manifest.heights(), size);
                }
            }
            throw new IllegalStateException("missing manifest in " + path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private SegmentPayload readPayload(ArchiveFile file, long height) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file.path()))) {
            List<TraceFrame> frames = List.of();
            List<StateChange> changes = List.of();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("h" + height + "/frames.json")) {
                    frames = mapper.readValue(zip.readAllBytes(), new TypeReference<>() {
                    });
                } else if (entry.getName().equals("h" + height + "/changes.json")) {
                    changes = mapper.readValue(zip.readAllBytes(), new TypeReference<>() {
                    });
                }
            }
            return new SegmentPayload(frames, changes);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static boolean matchesHeight(long height, long fromHeight, long toHeight) {
        return height >= fromHeight && height <= toHeight;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private record Manifest(long fromHeight, long toHeight, List<Long> heights) {
    }

    private record SegmentPayload(List<TraceFrame> frames, List<StateChange> changes) {
    }

    private static final class ArchiveFile implements Comparable<ArchiveFile> {
        private final Path path;
        private final long fromHeight;
        private final long toHeight;
        private final List<Long> heights;
        private final long compressedBytes;

        private ArchiveFile(Path path, long fromHeight, long toHeight,
                            List<Long> heights, long compressedBytes) {
            this.path = path;
            this.fromHeight = fromHeight;
            this.toHeight = toHeight;
            this.heights = List.copyOf(heights);
            this.compressedBytes = compressedBytes;
        }

        Path path() {
            return path;
        }

        long fromHeight() {
            return fromHeight;
        }

        long toHeight() {
            return toHeight;
        }

        List<Long> heights() {
            return heights;
        }

        long compressedBytes() {
            return compressedBytes;
        }

        @Override
        public int compareTo(ArchiveFile other) {
            return Long.compare(fromHeight, other.fromHeight);
        }
    }

    /**
     * One segment offered for archiving together with the live file that must
     * be removed after a successful compression.
     */
    public record PathAndSegment(long height, Path segmentPath, SegmentData data) {
    }
}
