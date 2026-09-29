package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

/**
 * Stable, offset based page. {@code total} is computed over the same committed
 * snapshot that produced {@code items}.
 */
public record PageResult<T>(List<T> items, long total, long offset, int limit, long nextOffset) {
}
