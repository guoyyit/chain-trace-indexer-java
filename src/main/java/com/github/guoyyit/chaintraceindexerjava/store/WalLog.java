package com.github.guoyyit.chaintraceindexerjava.store;


import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Append-only write-ahead log split into rolling segment files.
 *
 * <p>Frame layout: {@code crc32(payload):4 | length:4 | payload | '\n':1}, little endian.
 * A torn trailing write after a crash is detected and truncated on open. Segment files
 * are named {@code seg-NNNNNNNN.log}; {@code manifest} atomically records the committed
 * segment range, so only fully committed segments are read during recovery.
 */
public final class WalLog implements AutoCloseable {

    private static final String MANIFEST = "manifest";
    private static final String SEGMENT_PREFIX = "seg-";
    private static final String SEGMENT_SUFFIX = ".log";
    private static final int HEADER_BYTES = 8;

    private final Path directory;
    private final long segmentBytes;

    private int firstSegment;
    private int nextSegment;
    private Path activePath;
    private FileChannel activeChannel;
    private OutputStream activeOutput;
    private long activeSize;

    public WalLog(Path directory, long segmentBytes) {
        this.directory = directory;
        this.segmentBytes = segmentBytes;
        open();
    }

    private void open() {
        try {
            Files.createDirectories(directory);
            Path manifest = directory.resolve(MANIFEST);
            if (Files.exists(manifest)) {
                List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
                firstSegment = Integer.parseInt(lines.get(0).trim());
                nextSegment = Integer.parseInt(lines.get(1).trim());
            } else {
                firstSegment = 0;
                nextSegment = 1;
                activePath = directory.resolve(segmentName(0));
                writeManifest();
            }
            cleanupOrphanedFiles();
            truncatePartialTails();
            openActiveSegment();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to open WAL at " + directory, ex);
        }
    }

    /**
     * Removes files left behind by a crashed compaction or segment roll:
     * temporary files and segment files outside the committed range
     * {@code [firstSegment, nextSegment)}. The manifest is the single commit
     * point, so any segment it does not reference is unreferenced garbage.
     */
    private void cleanupOrphanedFiles() throws IOException {
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    Files.deleteIfExists(path);
                    continue;
                }
                if (name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX)) {
                    int index = Integer.parseInt(name.substring(SEGMENT_PREFIX.length(),
                            name.length() - SEGMENT_SUFFIX.length()));
                    if (index < firstSegment || index >= nextSegment) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private void openActiveSegment() throws IOException {
        int index = nextSegment - 1;
        activePath = directory.resolve(segmentName(index));
        activeSize = Files.exists(activePath) ? Files.size(activePath) : 0L;
        activeChannel = FileChannel.open(activePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        activeOutput = new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(activeChannel));
    }

    private String segmentName(int index) {
        return SEGMENT_PREFIX + String.format("%08d", index) + SEGMENT_SUFFIX;
    }

    public synchronized void append(LogRecord record) {
        try {
            byte[] payload = encode(record);
            byte[] frame = frame(payload);
            if (activeSize > 0 && activeSize + frame.length > segmentBytes) {
                activeOutput.flush();
                activeChannel.force(false);
                activeChannel.close();
                activePath = directory.resolve(segmentName(nextSegment));
                nextSegment++;
                activeSize = 0;
                writeManifest();
                openActiveSegment();
            }
            activeOutput.write(frame);
            activeOutput.flush();
            activeChannel.force(false);
            activeSize += frame.length;
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to append WAL record", ex);
        }
    }

    private byte[] frame(byte[] payload) {
        byte[] frame = new byte[HEADER_BYTES + payload.length + 1];
        CRC32 crc = new CRC32();
        crc.update(payload);
        writeLittleEndian(frame, 0, (int) crc.getValue());
        writeLittleEndian(frame, 4, payload.length);
        System.arraycopy(payload, 0, frame, HEADER_BYTES, payload.length);
        frame[frame.length - 1] = '\n';
        return frame;
    }

    private static void writeLittleEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    public synchronized List<LogRecord> readAll() {
        List<LogRecord> records = new ArrayList<>();
        for (int segment = firstSegment; segment < nextSegment; segment++) {
            Path path = directory.resolve(segmentName(segment));
            if (Files.exists(path)) {
                records.addAll(readSegment(path, false));
            }
        }
        return List.copyOf(records);
    }

    private List<LogRecord> readSegment(Path path, boolean tolerateTruncation) {
        List<LogRecord> records = new ArrayList<>();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read WAL segment " + path, ex);
        }
        int offset = 0;
        while (offset < bytes.length) {
            if (bytes.length - offset < HEADER_BYTES) {
                return handleTail(path, bytes, offset, tolerateTruncation, "truncated header");
            }
            int expectedCrc = readLittleEndian(bytes, offset);
            int length = readLittleEndian(bytes, offset + 4);
            int frameLength = HEADER_BYTES + length + 1;
            if (length < 0 || length > segmentBytes * 4L
                    || offset + frameLength > bytes.length) {
                return handleTail(path, bytes, offset, tolerateTruncation, "invalid frame");
            }
            byte[] payload = new byte[length];
            System.arraycopy(bytes, offset + HEADER_BYTES, payload, 0, length);
            if (bytes[offset + HEADER_BYTES + length] != '\n') {
                return handleTail(path, bytes, offset, tolerateTruncation, "missing trailer");
            }
            CRC32 crc = new CRC32();
            crc.update(payload);
            if ((int) crc.getValue() != expectedCrc) {
                return handleTail(path, bytes, offset, tolerateTruncation, "crc mismatch");
            }
            records.add(decode(payload));
            offset += frameLength;
        }
        return records;
    }

    private List<LogRecord> handleTail(Path path, byte[] bytes, int offset,
                                       boolean tolerateTruncation, String reason) {
        if (!tolerateTruncation) {
            throw new CorruptWalException(reason + " in " + path);
        }
        try {
            truncateTo(path, offset);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return List.of();
    }

    private static int readLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private void truncatePartialTails() throws IOException {
        for (int segment = firstSegment; segment < nextSegment; segment++) {
            Path path = directory.resolve(segmentName(segment));
            if (Files.exists(path)) {
                readSegment(path, true);
            }
        }
    }

    private void truncateTo(Path path, long length) throws IOException {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(path,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.truncate(length);
        }
    }

    public synchronized void compactTo(List<LogRecord> retained) {
        try {
            activeOutput.flush();
            activeChannel.force(false);
            activeOutput.close();
            int newSegment = nextSegment;
            Path temp = directory.resolve("compact-" + System.nanoTime() + ".tmp");
            long written = 0;
            try (OutputStream output = new BufferedOutputStream(
                    Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE))) {
                for (LogRecord record : retained) {
                    byte[] frame = frame(encode(record));
                    output.write(frame);
                    written += frame.length;
                }
                output.flush();
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            Path target = directory.resolve(segmentName(newSegment));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fsyncDirectory();
            // The manifest update is the atomic commit point. Only after it is
            // durable do we drop the old segments; a crash in any earlier window
            // leaves the manifest pointing at intact old segments.
            firstSegment = newSegment;
            nextSegment = newSegment + 1;
            writeManifest();
            // Reopen the active segment on the compacted file.
            activePath = target;
            activeSize = written;
            activeChannel = FileChannel.open(target,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            activeOutput = new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(activeChannel));
            // Old segments are now unreferenced; safe to delete after commit.
            deleteSegmentsBefore(newSegment);
            fsyncDirectory();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to compact WAL", ex);
        }
    }

    private void deleteSegmentsBefore(int boundary) throws IOException {
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX)) {
                    int index = Integer.parseInt(
                            name.substring(SEGMENT_PREFIX.length(),
                                    name.length() - SEGMENT_SUFFIX.length()));
                    if (index < boundary) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private void writeManifest() throws IOException {
        Path temp = directory.resolve(MANIFEST + ".tmp");
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temp,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)))) {
            output.writeBytes(Integer.toString(firstSegment) + System.lineSeparator());
            output.writeBytes(Integer.toString(nextSegment) + System.lineSeparator());
            output.flush();
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temp, directory.resolve(MANIFEST),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        fsyncDirectory();
    }

    private void fsyncDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ex) {
            // Some platforms do not support fsync on a directory handle; the
            // atomic move plus file fsync still provides the ordering guarantee.
        }
    }

    static byte[] encode(LogRecord record) {
        try {
            return WalCodec.MAPPER.writeValueAsBytes(record);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static LogRecord decode(byte[] payload) {
        try {
            return WalCodec.MAPPER.readValue(payload, LogRecord.class);
        } catch (IOException ex) {
            throw new CorruptWalException("malformed WAL record: " + ex.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (activeOutput != null) {
                activeOutput.flush();
                activeChannel.force(false);
                activeChannel.close();
                activeOutput = null;
                activeChannel = null;
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
