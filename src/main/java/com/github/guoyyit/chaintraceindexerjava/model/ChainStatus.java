package com.github.guoyyit.chaintraceindexerjava.model;

/**
 * Current committed chain head and archive water mark.
 */
public record ChainStatus(long headHeight, String headHash, long archivedToHeight) {
}
