package com.github.guoyyit.chaintraceindexerjava.storage;

/**
 * One durable write ahead log entry.
 *
 * <p>Types:
 * <ul>
 *   <li>COMMIT  - one block committed with its segment file</li>
 *   <li>RESET   - a reorg completed; carries the full canonical chain hash
 *                 list and retained archive descriptors</li>
 *   <li>ARCHIVE - a finalized prefix was compressed into an archive file</li>
 * </ul>
 */
public record WalEntry(String type,
                       long height,
                       String blockHash,
                       String parentHash,
                       String detail) {
}
