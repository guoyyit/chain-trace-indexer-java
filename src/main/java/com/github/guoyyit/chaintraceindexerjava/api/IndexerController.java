package com.github.guoyyit.chaintraceindexerjava.api;

import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveResult;
import com.github.guoyyit.chaintraceindexerjava.archive.ArchiveService;
import com.github.guoyyit.chaintraceindexerjava.indexer.BlockSubmissionResult;
import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexer;
import com.github.guoyyit.chaintraceindexerjava.model.Page;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.TransactionResult;
import com.github.guoyyit.chaintraceindexerjava.query.QueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1")
public class IndexerController {

    private final ChainIndexer chainIndexer;
    private final QueryService queryService;
    private final ArchiveService archiveService;
    private final int maxPageLimit;

    public IndexerController(ChainIndexer chainIndexer, QueryService queryService,
                             ArchiveService archiveService,
                             com.github.guoyyit.chaintraceindexerjava.config.IndexerProperties properties) {
        this.chainIndexer = chainIndexer;
        this.queryService = queryService;
        this.archiveService = archiveService;
        this.maxPageLimit = properties.maxPageLimit();
    }

    @PostMapping("/blocks")
    public BlockSubmissionResult submitBlock(@RequestBody Dtos.RawBlockDto block) {
        return chainIndexer.submitBlock(Dtos.toModel(block));
    }

    @GetMapping("/addresses/{address}/traces")
    public Page<TransactionResult> tracesByAddress(@PathVariable String address,
                                                   @RequestParam long fromHeight,
                                                   @RequestParam long toHeight,
                                                   @RequestParam(defaultValue = "0") long offset,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return queryService.queryTracesByAddress(address, fromHeight, toHeight,
                offset, bounded(limit));
    }

    @GetMapping("/addresses/{address}/changes")
    public Page<StateChange> changesByAddress(@PathVariable String address,
                                              @RequestParam long fromHeight,
                                              @RequestParam long toHeight,
                                              @RequestParam(defaultValue = "0") long offset,
                                              @RequestParam(defaultValue = "50") int limit) {
        return queryService.queryChangesByAddress(address, fromHeight, toHeight,
                offset, bounded(limit));
    }

    @GetMapping("/transactions/{txHash}")
    public QueryService.TraceByHashView transaction(@PathVariable String txHash,
                                                    @RequestParam(defaultValue = "0") long traceOffset,
                                                    @RequestParam(defaultValue = "1000") int traceLimit,
                                                    @RequestParam(defaultValue = "0") long changeOffset,
                                                    @RequestParam(defaultValue = "1000") int changeLimit) {
        QueryService.TraceByHashView view = queryService.queryTraceByHash(
                txHash, traceOffset, bounded(traceLimit),
                changeOffset, bounded(changeLimit));
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "transaction not found: " + txHash);
        }
        return view;
    }

    @PostMapping("/archive")
    public ArchiveResult archive(@RequestParam long toHeight) {
        return archiveService.archiveUpTo(toHeight);
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(chainIndexer.tipHeight(), chainIndexer.tipHash());
    }

    private int bounded(int requested) {
        if (requested <= 0) {
            return 50;
        }
        return Math.min(requested, maxPageLimit);
    }

    public record HealthResponse(long tipHeight, String tipHash) {
    }
}
