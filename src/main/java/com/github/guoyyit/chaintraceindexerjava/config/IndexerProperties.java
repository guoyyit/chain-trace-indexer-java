package com.github.guoyyit.chaintraceindexerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code chain-indexer.*} configuration properties.
 */
@ConfigurationProperties(prefix = "chain-indexer")
public class IndexerProperties {

    private String dataDir = "data/chain-indexer";
    private int maxQueryLimit = 1000;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public int getMaxQueryLimit() {
        return maxQueryLimit;
    }

    public void setMaxQueryLimit(int maxQueryLimit) {
        this.maxQueryLimit = maxQueryLimit;
    }
}
