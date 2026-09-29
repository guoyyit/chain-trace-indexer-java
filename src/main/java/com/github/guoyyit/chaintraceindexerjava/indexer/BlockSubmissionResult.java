package com.github.guoyyit.chaintraceindexerjava.indexer;

import java.util.List;

public record BlockSubmissionResult(
        String action,
        long fromHeight,
        long toHeight,
        List<IndexedBlock> chain
) {
    public BlockSubmissionResult {
        chain = chain == null ? List.of() : List.copyOf(chain);
    }
}
