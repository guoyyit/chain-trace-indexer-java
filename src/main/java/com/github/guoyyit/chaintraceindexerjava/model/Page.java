package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

public record Page<T>(
        List<T> items,
        int limit,
        long offset,
        long total,
        boolean hasMore
) {
    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
