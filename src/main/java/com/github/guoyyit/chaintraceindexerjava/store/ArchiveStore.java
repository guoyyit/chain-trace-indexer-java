package com.github.guoyyit.chaintraceindexerjava.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Content-addressed cold storage for finalized blocks.
 *
 * <p>Each archived block is serialized to {@code blocks/<sha256>.json} via an atomic
 * temp-file move, so crash windows never leave a partially visible object. The lightweight
 * {@link ArchivedBlockHandle} in the live index points to content hashes; reads reconstruct
 * full traces/changes from the object on demand.
 */
public final class ArchiveStore implements AutoCloseable {

    private final Path blockDirectory;

    public ArchiveStore(Path directory) {
        this.blockDirectory = directory.resolve("blocks");
        try {
            Files.createDirectories(blockDirectory);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to open archive store", ex);
        }
    }

    public String putBlock(long height, BlockResult block, List<String> touchedAddresses) {
        ArchivedBlockFile file = new ArchivedBlockFile(height, block.hash(), touchedAddresses, block);
        byte[] payload;
        try {
            payload = WalCodec.MAPPER.writeValueAsBytes(file);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
        String contentHash = sha256(payload);
        Path target = objectPath(contentHash);
        if (!Files.exists(target)) {
            Path temp = blockDirectory.resolve(".tmp-" + contentHash + "-" + System.nanoTime());
            try {
                Files.write(temp, payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                throw new UncheckedIOException("failed to write archived block " + height, ex);
            }
        }
        return contentHash;
    }

    public BlockResult readBlock(ArchivedBlockHandle handle) {
        Path path = objectPath(handle.contentHash());
        if (!Files.exists(path)) {
            return null;
        }
        try {
            ArchivedBlockFile file = WalCodec.MAPPER.readValue(
                    Files.readAllBytes(path), ArchivedBlockFile.class);
            return file.block();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read archived block " + handle.height(), ex);
        }
    }

    public void deleteUnreferenced(Set<String> referencedContentHashes) {
        try (var stream = Files.list(blockDirectory)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".json") && !referencedContentHashes.contains(
                        name.substring(0, name.length() - ".json".length()))) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("archive gc failed", ex);
        }
    }

    public int objectCount() {
        try (var stream = Files.list(blockDirectory)) {
            return (int) stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .count();
        } catch (IOException ex) {
            throw new UncheckedIOException("archive listing failed", ex);
        }
    }

    private Path objectPath(String contentHash) {
        return blockDirectory.resolve(contentHash + ".json");
    }

    private static String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void close() {
    }

    private record ArchivedBlockFile(long height, String blockHash,
                                     List<String> touchedAddresses, BlockResult block) {
        ArchivedBlockFile {
            touchedAddresses = touchedAddresses == null ? List.of() : List.copyOf(touchedAddresses);
        }
    }

    public record ArchivedBlockHandle(long height, String blockHash, String contentHash,
                                      List<ArchivedMarker.Tx> txs,
                                      List<String> touchedAddresses,
                                      List<ArchivedMarker.AddressChangeCount> addressChangeCounts) {
        public ArchivedBlockHandle {
            txs = txs == null ? List.of() : List.copyOf(txs);
            touchedAddresses = touchedAddresses == null ? List.of() : List.copyOf(touchedAddresses);
            addressChangeCounts = addressChangeCounts == null
                    ? List.of() : List.copyOf(addressChangeCounts);
        }
    }
}
