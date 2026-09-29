package com.github.guoyyit.chaintraceindexerjava.indexer;

public class ChainIndexerException extends RuntimeException {
    public ChainIndexerException(String message) {
        super(message);
    }

    public static final class MissingParent extends ChainIndexerException {
        public MissingParent(String message) {
            super(message);
        }
    }

    public static final class NonSequentialHeight extends ChainIndexerException {
        public NonSequentialHeight(String message) {
            super(message);
        }
    }
}
