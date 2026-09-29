package com.github.guoyyit.chaintraceindexerjava.store;

import com.github.guoyyit.chaintraceindexerjava.model.BlockResult;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LogRecord.BlockCommitted.class, name = "committed"),
        @JsonSubTypes.Type(value = LogRecord.BlockUndone.class, name = "undone"),
        @JsonSubTypes.Type(value = LogRecord.BlockArchived.class, name = "archived")
})
public sealed interface LogRecord
        permits LogRecord.BlockCommitted, LogRecord.BlockUndone, LogRecord.BlockArchived {

    record BlockCommitted(BlockResult block) implements LogRecord {
    }

    record BlockUndone(long height) implements LogRecord {
    }

    record BlockArchived(ArchivedMarker marker) implements LogRecord {
    }
}
