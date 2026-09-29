package com.github.guoyyit.chaintraceindexerjava.service;

import java.util.List;

/**
 * Payroll of a RESET WAL entry: the fork height, the full canonical block hash
 * list (index 0 = genesis) and the archive water mark retained after a reorg.
 */
public record ResetDetail(long forkHeight, List<String> hashes, long archiveTo) {
}
