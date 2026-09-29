package com.github.guoyyit.chaintraceindexerjava.storage;

import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;

import java.util.List;

/**
 * Per block durable derived data (one segment file per committed block).
 */
public record SegmentData(long height,
                          String blockHash,
                          String parentHash,
                          List<TraceFrame> frames,
                          List<StateChange> changes) {
}
