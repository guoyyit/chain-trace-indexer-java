package com.github.guoyyit.chaintraceindexerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "chain-trace.data-dir=target/api-it-data-${random.uuid}")
class ApiIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void submitAndQueryOverHttp() {
        String genesis = """
                {
                  "height": 0,
                  "hash": "g",
                  "prevHash": "genesis",
                  "allocations": [{"address": "A", "balance": 1000000}],
                  "transactions": []
                }
                """;
        ResponseEntity<Map> genesisResponse = restTemplate.postForEntity(url("/api/v1/blocks"),
                new HttpEntity<>(genesis, jsonHeaders()), Map.class);
        assertEquals(200, genesisResponse.getStatusCode().value());

        String block1 = """
                {
                  "height": 1,
                  "hash": "h1",
                  "prevHash": "g",
                  "transactions": [{
                    "hash": "tx-1",
                    "from": "A",
                    "value": 10,
                    "code": ["call B 4", "  sset k hello", "  end"]
                  }]
                }
                """;
        ResponseEntity<Map> blockResponse = restTemplate.postForEntity(url("/api/v1/blocks"),
                new HttpEntity<>(block1, jsonHeaders()), Map.class);
        assertEquals(200, blockResponse.getStatusCode().value());
        assertEquals("extended", blockResponse.getBody().get("action"));

        ResponseEntity<Map> duplicate = restTemplate.postForEntity(url("/api/v1/blocks"),
                new HttpEntity<>(block1, jsonHeaders()), Map.class);
        assertEquals(200, duplicate.getStatusCode().value());
        assertEquals("ignored_duplicate_block", duplicate.getBody().get("action"));

        ResponseEntity<Map> changes = restTemplate.getForEntity(
                url("/api/v1/addresses/B/changes?fromHeight=0&toHeight=2"), Map.class);
        assertEquals(200, changes.getStatusCode().value());
        assertTrue(((List<?>) changes.getBody().get("items")).size() >= 2);

        ResponseEntity<Map> tx = restTemplate.getForEntity(
                url("/api/v1/transactions/tx-1"), Map.class);
        assertEquals(200, tx.getStatusCode().value());
        assertEquals(1, tx.getBody().get("height"));
        assertNotNull(tx.getBody().get("transaction"));

        ResponseEntity<Map> health = restTemplate.getForEntity(url("/api/v1/health"), Map.class);
        assertEquals(200, health.getStatusCode().value());
        assertEquals(1, health.getBody().get("tipHeight"));

        ResponseEntity<Map> archive = restTemplate.postForEntity(
                url("/api/v1/archive?toHeight=0"), null, Map.class);
        assertEquals(200, archive.getStatusCode().value());
        assertEquals(1, archive.getBody().get("archivedBlocks"));

        ResponseEntity<Map> archivedTx = restTemplate.getForEntity(
                url("/api/v1/transactions/tx-1"), Map.class);
        assertEquals(200, archivedTx.getStatusCode().value());
    }
}
