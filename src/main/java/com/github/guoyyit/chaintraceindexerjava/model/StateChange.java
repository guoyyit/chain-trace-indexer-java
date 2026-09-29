package com.github.guoyyit.chaintraceindexerjava.model;

public record StateChange(
        String address,
        ChangeType type,
        String key,
        String oldValue,
        String newValue,
        int depth
) {
    public StateChange {
        if (address == null) {
            throw new IllegalArgumentException("change address must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("change type must not be null");
        }
    }
}
