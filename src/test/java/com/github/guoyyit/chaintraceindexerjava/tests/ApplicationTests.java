package com.github.guoyyit.chaintraceindexerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;

/**
 * @author Vincent
 * @version 1.0
 */
@SpringBootTest
@TestPropertySource(properties = "chain-indexer.data-dir=${java.io.tmpdir}/chain-indexer-context-test")
public class ApplicationTests {
    @Test
    public void contentLoad() {
        // Ensures the temp directory is available; the indexer creates its own
        // subdirectories on startup.
        Files.exists(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
    }
}
