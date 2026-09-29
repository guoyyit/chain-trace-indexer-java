package com.github.guoyyit.chaintraceindexerjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared JSON mapper used for all durable files. Serialization is deterministic
 * for replay stability (fixed key order from the record accessors).
 */
final class JsonCodecs {

    private JsonCodecs() {
    }

    static ObjectMapper mapper() {
        return new ObjectMapper();
    }
}
