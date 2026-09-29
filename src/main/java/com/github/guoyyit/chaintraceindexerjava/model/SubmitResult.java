package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

/**
 * Result of a block submission: newly applied canonical blocks and the
 * resulting chain head.
 */
public record SubmitResult(List<Long> appliedHeights,
                           long headHeight,
                           String headHash,
                           List<SkippedTransaction> skipped) {
}
