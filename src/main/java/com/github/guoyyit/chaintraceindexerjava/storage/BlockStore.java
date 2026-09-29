package com.github.guoyyit.chaintraceindexerjava.storage;

import com.github.guoyyit.chaintraceindexerjava.model.RawBlock;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Content addressed raw block storage. Files are never mutated or deleted
 * (including blocks of reorged away branches), so any chain can always be
 * replayed.
 */
public class BlockStore {

    private final Path blocksDir;
    private final ObjectMapper mapper = JsonCodecs.mapper();

    public BlockStore(Path dataDir) {
        this.blocksDir = dataDir.resolve("blocks");
        try {
            Files.createDirectories(blocksDir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public void save(RawBlock block) {
        Path target = pathFor(block.hash());
        if (Files.exists(target)) {
            return;
        }
        try {
            Path tmp = Files.createTempFile(blocksDir, "block-", ".json.tmp");
            Files.writeString(tmp, mapper.writeValueAsString(block));
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public RawBlock load(String hash) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        Path target = pathFor(hash);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(target), RawBlock.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public boolean exists(String hash) {
        return hash != null && !hash.isBlank() && Files.exists(pathFor(hash));
    }

    private Path pathFor(String hash) {
        return blocksDir.resolve(hash.trim().toLowerCase() + ".json");
    }
}
