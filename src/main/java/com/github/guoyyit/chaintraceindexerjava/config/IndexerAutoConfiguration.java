package com.github.guoyyit.chaintraceindexerjava.config;

import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveService;
import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.query.QueryService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(IndexerProperties.class)
public class IndexerAutoConfiguration {

    @Bean(destroyMethod = "close")
    public ChainIndexer chainIndexer(IndexerProperties properties) {
        return new ChainIndexer(Path.of(properties.dataDir()),
                properties.walSegmentBytes(), properties.compactionThresholdBytes());
    }

    @Bean
    public QueryService queryService(ChainIndexer chainIndexer) {
        return new QueryService(chainIndexer);
    }

    @Bean
    public ArchiveService archiveService(ChainIndexer chainIndexer) {
        return new ArchiveService(chainIndexer);
    }
}
