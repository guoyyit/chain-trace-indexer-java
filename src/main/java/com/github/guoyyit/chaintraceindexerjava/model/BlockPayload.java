package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

/**
 * Request body for block submission.
 */
public record BlockPayload(List<RawBlock> blocks) {
}
