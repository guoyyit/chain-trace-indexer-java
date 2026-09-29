package com.github.guoyyit.chaintraceindexerjava.index;

import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;

import java.util.List;

/**
 * Immutable live (non archived) segment data visible to snapshots.
 */
record SegmentDataView(long height,
                       String blockHash,
                       List<TraceFrame> frames,
                       List<StateChange> changes) {
}
