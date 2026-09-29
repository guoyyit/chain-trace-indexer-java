package com.github.guoyyit.chaintraceindexerjava.model;

/**
 * Result of an archive run.
 */
public record ArchiveResult(long archivedToHeight, int segments, long compressedBytes) {
}
