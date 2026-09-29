package com.github.guoyyit.chaintraceindexerjava.index;

import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;

import java.util.List;

/**
 * Read only view of archived finalized data captured by a snapshot generation.
 */
interface ArchiveReadView {

    List<TraceFrame> findFrames(String txHash, String address, long fromHeight, long toHeight);

    List<StateChange> findChanges(String address, String txHash, long fromHeight, long toHeight);
}
