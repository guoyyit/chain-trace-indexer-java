package com.github.guoyyit.chaintraceindexerjava.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared JSON mapper for WAL/archive persistence.
 */
final class WalCodec {

    static final ObjectMapper MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private WalCodec() {
    }
}
