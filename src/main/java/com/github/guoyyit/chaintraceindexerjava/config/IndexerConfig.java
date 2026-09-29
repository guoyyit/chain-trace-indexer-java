package com.github.guoyyit.chaintraceindexerjava.config;

import com.github.guoyyit.chaintraceindexerjava.index.ArchiveManager;
import com.github.guoyyit.chaintraceindexerjava.index.LiveIndex;
import com.github.guoyyit.chaintraceindexerjava.service.IndexService;
import com.github.guoyyit.chaintraceindexerjava.storage.BlockStore;
import com.github.guoyyit.chaintraceindexerjava.storage.WalStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
@EnableConfigurationProperties(IndexerProperties.class)
public class IndexerConfig {

    @Bean
    public ArchiveManager archiveManager(IndexerProperties props) {
        return new ArchiveManager(Paths.get(props.getDataDir()));
    }

    @Bean
    public BlockStore blockStore(IndexerProperties props) {
        return new BlockStore(Paths.get(props.getDataDir()));
    }

    @Bean
    public WalStore walStore(IndexerProperties props) {
        return new WalStore(Paths.get(props.getDataDir()));
    }

    @Bean
    public LiveIndex liveIndex(ArchiveManager archiveManager) {
        return new LiveIndex(archiveManager);
    }

    @Bean
    public IndexService indexService(BlockStore blockStore,
                                    WalStore walStore,
                                    LiveIndex liveIndex,
                                    ArchiveManager archiveManager) {
        IndexService service = new IndexService(blockStore, walStore, liveIndex, archiveManager);
        service.recover();
        return service;
    }
}
