package com.github.guoyyit.chaintraceindexerjava.model;

import java.util.List;

public record CallFrame(
        String from,
        String to,
        long value,
        int depth,
        boolean success,
        String revertReason,
        List<CallFrame> children
) {
    public CallFrame {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
