package com.github.guoyyit.chaintraceindexerjava.store;


import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
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
    private static final String COMPACT_PREFIX = "compact-";
    private static final String TMP_SUFFIX = ".tmp";
    private static final int HEADER_BYTES = 8;
    private static final long MAX_PAYLOAD_BYTES = 256L * 1024 * 1024;

    private final Path directory;
    private final long segmentBytes;

    private int firstSegment;
    private int nextSegment;
   private Path activePath;
   private FileChannel activeChannel;
   private OutputStream activeOutput;
   private long activeSize;
   private volatile CompactionCrashPoint crashInjection;
    private volatile long killAfterAppends = -1L;
    private long appendCount;

    /**
     * Deterministic crash points used by recovery tests. Not used in production.
     */
   public enum CompactionCrashPoint {
        AFTER_REPLACEMENT_WRITTEN,
        AFTER_MANIFEST_STAGED,
        AFTER_MANIFEST_SWAPPED,
        AFTER_OLD_SEGMENTS_DELETED,
        DURING_REORG_APPEND
    }

   public void setCrashInjection(CompactionCrashPoint crashInjection) {
       this.crashInjection = crashInjection;
   }

    /**
     * Kills the process (simulated) after exactly {@code count} subsequent
     * {@link #append(LogRecord)} calls, each of which has already been fsynced. Used by
     * recovery tests to exercise every crash boundary of a multi-record reorg.
     */
    public void armKillAfterAppends(long count) {
        this.killAfterAppends = count;
        this.appendCount = 0L;
    }

    private void crashIfInjected(CompactionCrashPoint point) {
        if (crashInjection == point) {
            throw new SimulatedProcessKill(point);
        }
    }

    /**
     * Simulates {@code SIGKILL} during compaction for recovery tests. It is an {@link Error}
     * (not an {@link IOException}) on purpose: regular exception handling must not run, so
     * the on-disk state is frozen exactly at the crash phase. The abandoned log instance
     * is never closed; a fresh instance reopens the same directory.
     */
    public static final class SimulatedProcessKill extends Error {
        SimulatedProcessKill(CompactionCrashPoint point) {
            super("simulated process kill at " + point);
        }
    }

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
                reconcileCompaction();
               List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
               firstSegment = Integer.parseInt(lines.get(0).trim());
               nextSegment = Integer.parseInt(lines.get(1).trim());
           } else {
               firstSegment = 0;
               nextSegment = 1;
               activePath = directory.resolve(segmentName(0));
               writeManifest();
           }
           truncatePartialTails();
            cleanupStagingFiles();
           openActiveSegment();
       } catch (IOException ex) {
           throw new UncheckedIOException("failed to open WAL at " + directory, ex);
       }
   }

    /**
     * Finishes or rolls back a compaction interrupted by a process kill.
     *
     * <p>A compaction becomes visible only when {@code manifest.tmp} is atomically
     * swapped into place, and that swap happens only after every replacement segment is
     * fully written and fsynced. Consequently:
     * <ul>
     *   <li>no {@code manifest.tmp}: the on-disk manifest is still authoritative and the
     *       pre-compaction segments must be intact; any stray replacement segments or
     *       staging files from a crash before the swap are removed;</li>
     *   <li>a {@code manifest.tmp} whose replacement segments are all complete: finish the
     *       commit (delete old segments, install the manifest, fsync the directory);</li>
     *   <li>a {@code manifest.tmp} with a torn replacement segment: roll back by deleting
     *       the replacement and the staging manifest, keeping the old segments.</li>
     * </ul>
     */
    private void reconcileCompaction() throws IOException {
        Path manifestTmp = directory.resolve(MANIFEST + ".tmp");
        if (!Files.exists(manifestTmp)) {
            deleteStaleReplacementSegments();
            return;
        }
        int[] range;
        try {
            range = readManifest(manifestTmp);
        } catch (IOException | NumberFormatException ex) {
            Files.deleteIfExists(manifestTmp);
            deleteStaleReplacementSegments();
            return;
        }
        int tmpFirst = range[0];
        int tmpNext = range[1];
        boolean replacementComplete = true;
       for (int segment = tmpFirst; segment < tmpNext; segment++) {
            if (!safeSegmentComplete(directory.resolve(segmentName(segment)))) {
               replacementComplete = false;
               break;
           }
       }
        if (!replacementComplete) {
            for (int segment = tmpFirst; segment < tmpNext; segment++) {
                Files.deleteIfExists(directory.resolve(segmentName(segment)));
            }
            Files.deleteIfExists(manifestTmp);
            deleteStaleReplacementSegments();
            return;
        }
        deleteSegmentsBefore(tmpFirst);
        Files.move(manifestTmp, directory.resolve(MANIFEST),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory();
    }

    private int[] readManifest(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            throw new EOFException("manifest must contain two lines");
        }
        return new int[]{
                Integer.parseInt(lines.get(0).trim()),
                Integer.parseInt(lines.get(1).trim())
        };
    }

    /**
     * A segment is complete if it ends on a frame boundary, i.e. every frame parses.
     * A torn tail indicates the replacement was not durable when the process died, so
     * the interrupted compaction must be rolled back.
     */
   private boolean isSegmentComplete(Path path) throws IOException {
       if (!Files.exists(path)) {
           return false;
       }
       byte[] bytes = Files.readAllBytes(path);
       int offset = 0;
       while (offset < bytes.length) {
           if (bytes.length - offset < HEADER_BYTES) {
               return false;
           }
            int expectedCrc = readLittleEndian(bytes, offset);
            int length = readLittleEndian(bytes, offset + 4);
           int frameLength = HEADER_BYTES + length + 1;
           if (length < 0 || (long) length > MAX_PAYLOAD_BYTES
                   || offset + frameLength > bytes.length
                   || bytes[offset + HEADER_BYTES + length] != '\n') {
               return false;
           }
            byte[] payload = new byte[length];
            System.arraycopy(bytes, offset + HEADER_BYTES, payload, 0, length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if ((int) crc.getValue() != expectedCrc) {
                return false;
            }
            decode(payload);
           offset += frameLength;
       }
      return true;
  }

    private boolean safeSegmentComplete(Path path) {
        try {
            return isSegmentComplete(path);
        } catch (IOException | CorruptWalException ex) {
            return false;
        }
    }

    private void deleteStaleReplacementSegments() throws IOException {
        int[] range;
        try {
            range = readManifest(directory.resolve(MANIFEST));
        } catch (IOException | NumberFormatException ex) {
            return;
        }
        int liveNext = range[1];
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX)) {
                    int index = Integer.parseInt(
                            name.substring(SEGMENT_PREFIX.length(),
                                    name.length() - SEGMENT_SUFFIX.length()));
                    if (index >= liveNext) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private void cleanupStagingFiles() throws IOException {
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(COMPACT_PREFIX) && name.endsWith(TMP_SUFFIX)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
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
            if (killAfterAppends >= 0 && ++appendCount >= killAfterAppends) {
                killAfterAppends = -1L;
                throw new SimulatedProcessKill(CompactionCrashPoint.DURING_REORG_APPEND);
            }
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
            if (length < 0 || (long) length > MAX_PAYLOAD_BYTES
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
            activeChannel.close();
            activeChannel = null;
            activeOutput = null;

           int compactFirst = nextSegment;
           int compactNext = writeReplacementSegments(retained, compactFirst);
            crashIfInjected(CompactionCrashPoint.AFTER_REPLACEMENT_WRITTEN);

           // Commit point: stage the new manifest, then atomically swap it in.
           // Old segments are removed only after the new manifest is durable.
           writeManifestRange(compactFirst, compactNext,
                   directory.resolve(MANIFEST + ".tmp"));
            crashIfInjected(CompactionCrashPoint.AFTER_MANIFEST_STAGED);
           Files.move(directory.resolve(MANIFEST + ".tmp"), directory.resolve(MANIFEST),
                   StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
           forceDirectory();
            crashIfInjected(CompactionCrashPoint.AFTER_MANIFEST_SWAPPED);
           deleteSegmentsBefore(compactFirst);
            crashIfInjected(CompactionCrashPoint.AFTER_OLD_SEGMENTS_DELETED);

           firstSegment = compactFirst;
           nextSegment = compactNext;
           openActiveSegment();
      } catch (IOException ex) {
           reopenAfterFailedCompaction();
          throw new UncheckedIOException("failed to compact WAL", ex);
      }
  }

    /**
     * Re-opens the log in a consistent state after a failed compaction attempt.
     * The on-disk manifest is authoritative: if the atomic swap already happened the
     * replacement range is used, otherwise the pre-compaction range is intact.
     */
    private void reopenAfterFailedCompaction() {
        try {
            List<String> lines = Files.readAllLines(directory.resolve(MANIFEST),
                    StandardCharsets.UTF_8);
            firstSegment = Integer.parseInt(lines.get(0).trim());
            nextSegment = Integer.parseInt(lines.get(1).trim());
            truncatePartialTails();
            openActiveSegment();
        } catch (IOException reopenFailure) {
            throw new UncheckedIOException("WAL left unusable after failed compaction",
                    reopenFailure);
        }
    }

    private int writeReplacementSegments(List<LogRecord> retained, int startSegment)
            throws IOException {
        int segment = startSegment;
        long written = 0;
        OutputStream output = null;
        FileChannel channel = null;
        try {
            for (LogRecord record : retained) {
                byte[] frameBytes = frame(encode(record));
                if (output == null) {
                    Path path = directory.resolve(segmentName(segment));
                    channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    output = new BufferedOutputStream(
                            java.nio.channels.Channels.newOutputStream(channel));
                    written = 0;
                }
                if (written > 0 && written + frameBytes.length > segmentBytes) {
                    output.flush();
                    channel.force(false);
                    output.close();
                    channel = null;
                    output = null;
                    segment++;
                    Path path = directory.resolve(segmentName(segment));
                    channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    output = new BufferedOutputStream(
                            java.nio.channels.Channels.newOutputStream(channel));
                    written = 0;
                }
                output.write(frameBytes);
                written += frameBytes.length;
            }
            if (output != null) {
                output.flush();
                channel.force(false);
                output.close();
            } else {
                Path path = directory.resolve(segmentName(segment));
                try (FileChannel empty = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    empty.force(false);
                }
            }
            forceDirectory();
        } finally {
            if (output != null) {
                output.close();
            }
        }
        return segment + 1;
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
        writeManifestRange(firstSegment, nextSegment, directory.resolve(MANIFEST + ".tmp"));
        Files.move(directory.resolve(MANIFEST + ".tmp"), directory.resolve(MANIFEST),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory();
    }

   private void writeManifestRange(int first, int next, Path temp) throws IOException {
       try (DataOutputStream output = new DataOutputStream(
               new BufferedOutputStream(Files.newOutputStream(temp,
                       StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                       StandardOpenOption.WRITE)))) {
            output.writeBytes(Integer.toString(first) + System.lineSeparator());
            output.writeBytes(Integer.toString(next) + System.lineSeparator());
            output.flush();
       }
        forceDirectory();
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
