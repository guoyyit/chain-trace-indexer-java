package com.github.guoyyit.chaintraceindexerjava.archive;

import java.util.List;

public record ArchiveResult(
        long fromHeight,
        long toHeight,
        int archivedBlocks,
        List<Long> heights,
        int garbageCollectedFiles
) {
    public ArchiveResult {
        heights = heights == null ? List.of() : List.copyOf(heights);
    }
}
