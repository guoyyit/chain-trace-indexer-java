package com.github.guoyyit.chaintraceindexerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chain-trace")
public record IndexerProperties(
        String dataDir,
        long walSegmentBytes,
        long compactionThresholdBytes,
        int defaultPageLimit,
        int maxPageLimit
) {
    public IndexerProperties {
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "data/chain-trace";
        }
        if (walSegmentBytes <= 0) {
            walSegmentBytes = 64L * 1024 * 1024;
        }
        if (compactionThresholdBytes <= 0) {
            compactionThresholdBytes = 4L * walSegmentBytes;
        }
        if (defaultPageLimit <= 0) {
            defaultPageLimit = 50;
        }
        if (maxPageLimit <= 0) {
            maxPageLimit = 1000;
        }
    }
}
