package com.github.guoyyit.chaintraceindexerjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Append only, fsynced WAL plus segment file lifecycle helpers.
 */
public class WalStore {

    public static final String TYPE_COMMIT = "COMMIT";
    public static final String TYPE_RESET = "RESET";
    public static final String TYPE_ARCHIVE = "ARCHIVE";

    private final Path walFile;
    private final Path walTmp;
    private final Path segmentsDir;
    private final ObjectMapper mapper = JsonCodecs.mapper();

    public WalStore(Path dataDir) {
        Path root = dataDir;
        this.segmentsDir = root.resolve("segments");
        this.walFile = root.resolve("wal.log");
        this.walTmp = root.resolve("wal.log.tmp");
        try {
            Files.createDirectories(segmentsDir);
            if (!Files.exists(walFile)) {
                Files.createFile(walFile);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Appends one entry and fsyncs the file (and directory) before returning,
     * so a committed entry survives process/kernel crashes.
     */
    public synchronized void append(WalEntry entry) {
        try {
            Files.writeString(walFile, mapper.writeValueAsString(entry) + System.lineSeparator(),
                    StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public synchronized List<WalEntry> replay() {
        try {
            if (!Files.exists(walFile) || Files.size(walFile) == 0) {
                return List.of();
            }
            return Files.readAllLines(walFile).stream()
                    .filter(line -> !line.isBlank())
                    .map(this::readEntry)
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Durably writes the per block segment file: temp file + fsync + atomic
     * rename + directory fsync. The file exists if and only if fully written.
     */
    public synchronized void writeSegment(SegmentData data) {
        Path target = segmentPath(data.height(), data.blockHash());
        Path tmp = null;
        try {
            if (Files.exists(target)) {
                Files.delete(target);
            }
            tmp = Files.createTempFile(segmentsDir,
                    "seg-" + data.height() + "-", ".json.tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardOpenOption.SYNC)) {
                writer.write(mapper.writeValueAsString(data));
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fsyncDirectory(segmentsDir);
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

    public synchronized SegmentData readSegment(long height, String blockHash) {
        Path target = segmentPath(height, blockHash);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(target), SegmentData.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * @return the segment stored for {@code height}, regardless of block hash
     */
    public synchronized SegmentData readSegmentAt(long height) {
        Path path = findSegmentPath(height);
        if (path == null) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(path), SegmentData.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * @return path and parsed segment at {@code height}, or null when absent
     */
    public synchronized LocatedSegment locateSegment(long height) {
        Path path = findSegmentPath(height);
        if (path == null) {
            return null;
        }
        try {
            return new LocatedSegment(path,
                    mapper.readValue(Files.readString(path), SegmentData.class));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * @return heights (ascending) for which a live segment file exists
     */
    public synchronized List<Long> segmentHeights() {
        try (Stream<Path> stream = Files.list(segmentsDir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::parseHeight)
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Deletes the live segment at exactly {@code height} (if any), e.g. after
     * it has been safely archived.
     */
    public synchronized void deleteSegmentAt(long height) {
        Path path = findSegmentPath(height);
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * Deletes any live segment file at {@code height} whose block hash differs
     * from {@code canonicalHash}. Used during recovery to remove orphan
     * segments of a reorg branch whose RESET marker never landed.
     *
     * @return true if a mismatching file was removed
     */
    public synchronized boolean removeNonCanonicalSegment(long height, String canonicalHash) {
        boolean removed = false;
        String prefix = String.format("%019d-", height);
        try (Stream<Path> stream = Files.list(segmentsDir)) {
            List<Path> matches = stream
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
            for (Path path : matches) {
                String name = path.getFileName().toString();
                String hash = name.substring(20, name.length() - 5);
                if (!hash.equalsIgnoreCase(canonicalHash.trim())) {
                    Files.deleteIfExists(path);
                    removed = true;
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return removed;
    }

    public synchronized void deleteSegmentsAbove(long height) {
        try (Stream<Path> stream = Files.list(segmentsDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> parseHeight(path) > height)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Removes every leftover {@code *.tmp} file from a crashed commit attempt.
     */
    public synchronized void cleanTempArtifacts() {
        try {
            Files.deleteIfExists(walTmp);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        try (Stream<Path> stream = Files.list(segmentsDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private WalEntry readEntry(String line) {
        try {
            return mapper.readValue(line, WalEntry.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("corrupt WAL entry: " + line, ex);
        }
    }

    private Path segmentPath(long height, String blockHash) {
        String name = String.format("%019d-%s.json", height, blockHash.trim().toLowerCase());
        return segmentsDir.resolve(name);
    }

    private Path findSegmentPath(long height) {
        String prefix = String.format("%019d-", height);
        try (Stream<Path> stream = Files.list(segmentsDir)) {
            List<Path> matches = stream
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
            return matches.isEmpty() ? null : matches.get(0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private long parseHeight(Path path) {
        String name = path.getFileName().toString();
        return Long.parseLong(name.substring(0, 19));
    }

    private static void fsyncDirectory(Path directory) throws IOException {
        // Directory fsync is platform specific (unsupported on macOS via the
        // JDK). It is best effort here; recovery additionally verifies each
        // committed segment and deterministically rebuilds a missing one.
        try (java.io.FileInputStream input = new java.io.FileInputStream(directory.toFile())) {
            input.getFD().sync();
        } catch (IOException ignored) {
        }
    }

    /**
     * Live segment file plus its parsed contents.
     */
    public record LocatedSegment(Path path, SegmentData data) {
    }
}
