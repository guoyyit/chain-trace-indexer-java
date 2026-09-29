package com.github.guoyyit.chaintraceindexerjava.web;

import com.github.guoyyit.chaintraceindexerjava.model.ArchiveResult;
import com.github.guoyyit.chaintraceindexerjava.model.BlockPayload;
import com.github.guoyyit.chaintraceindexerjava.model.ChainStatus;
import com.github.guoyyit.chaintraceindexerjava.model.PageResult;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.SubmitResult;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;
import com.github.guoyyit.chaintraceindexerjava.service.IndexService;
import com.github.guoyyit.chaintraceindexerjava.config.IndexerProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IndexController {

    private final IndexService indexService;
    private final IndexerProperties properties;

    public IndexController(IndexService indexService, IndexerProperties properties) {
        this.indexService = indexService;
        this.properties = properties;
    }

    private int boundedLimit(int requested) {
        return Math.min(Math.max(1, requested), properties.getMaxQueryLimit());
    }

    @PostMapping("/blocks")
    public SubmitResult submit(@RequestBody BlockPayload payload) {
        return indexService.submit(payload);
    }

    @GetMapping("/traces")
    public PageResult<TraceFrame> traces(@RequestParam(required = false) String txHash,
                                         @RequestParam(required = false) String address,
                                         @RequestParam(required = false) Long fromHeight,
                                         @RequestParam(required = false) Long toHeight,
                                         @RequestParam(defaultValue = "0") long offset,
                                         @RequestParam(defaultValue = "50") int limit) {
        return indexService.snapshot()
                .queryFrames(txHash, address, fromHeight, toHeight,
                        Math.max(0, offset), boundedLimit(limit));
    }

    @GetMapping("/changes")
    public PageResult<StateChange> changes(@RequestParam(required = false) String address,
                                           @RequestParam(required = false) String txHash,
                                           @RequestParam(required = false) Long fromHeight,
                                           @RequestParam(required = false) Long toHeight,
                                           @RequestParam(defaultValue = "0") long offset,
                                           @RequestParam(defaultValue = "50") int limit) {
        return indexService.snapshot()
                .queryChanges(address, txHash, fromHeight, toHeight,
                        Math.max(0, offset), boundedLimit(limit));
    }

    @GetMapping("/status")
    public ChainStatus status() {
        return indexService.snapshot().status();
    }

    @PostMapping("/archive")
    public ArchiveResult archive(@RequestParam long toHeight) {
        return indexService.archive(toHeight);
    }
}
