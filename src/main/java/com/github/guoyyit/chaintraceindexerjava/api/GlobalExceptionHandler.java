package com.github.guoyyit.chaintraceindexerjava.api;

import com.github.guoyyit.chaintraceindexerjava.indexer.ChainIndexerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ChainIndexerException.MissingParent.class)
    public ResponseEntity<Dtos.ErrorResponse> missingParent(ChainIndexerException.MissingParent ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Dtos.ErrorResponse("missing_parent", ex.getMessage()));
    }

    @ExceptionHandler(ChainIndexerException.NonSequentialHeight.class)
    public ResponseEntity<Dtos.ErrorResponse> nonSequential(
            ChainIndexerException.NonSequentialHeight ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new Dtos.ErrorResponse("non_sequential_height", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dtos.ErrorResponse> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorResponse("invalid_request", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorResponse> handle(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorResponse("internal_error", ex.getMessage()));
    }
}
