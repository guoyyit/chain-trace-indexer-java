package com.github.guoyyit.chaintraceindexerjava.web;

import com.github.guoyyit.chaintraceindexerjava.index.Snapshot;
import com.github.guoyyit.chaintraceindexerjava.model.ArchiveResult;
import com.github.guoyyit.chaintraceindexerjava.model.BlockPayload;
import com.github.guoyyit.chaintraceindexerjava.model.ChainStatus;
import com.github.guoyyit.chaintraceindexerjava.model.PageResult;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;
import com.github.guoyyit.chaintraceindexerjava.model.SubmitResult;
import com.github.guoyyit.chaintraceindexerjava.model.TraceFrame;
import com.github.guoyyit.chaintraceindexerjava.config.IndexerProperties;
import com.github.guoyyit.chaintraceindexerjava.service.IndexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IndexController.class)
@EnableConfigurationProperties(IndexerProperties.class)
class IndexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IndexService indexService;

    @Test
    void submitsBlocksAndReturnsResult() throws Exception {
        when(indexService.submit(any())).thenReturn(
                new SubmitResult(List.of(0L, 1L), 1L, "h1", List.of()));
        String body = """
                {"blocks":[
                  {"height":0,"hash":"g","parentHash":"","allocations":[
                    {"address":"alice","balance":1000}],"transactions":[]},
                  {"height":1,"hash":"h1","parentHash":"g","transactions":[]}
                ]}
                """;
        mockMvc.perform(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headHash").value("h1"))
                .andExpect(jsonPath("$.appliedHeights[1]").value(1));

        ArgumentCaptor<BlockPayload> captor = ArgumentCaptor.forClass(BlockPayload.class);
        org.mockito.Mockito.verify(indexService).submit(captor.capture());
        assertThat(captor.getValue().blocks()).hasSize(2);
    }

    @Test
    void queriesTracesWithPagingParameters() throws Exception {
        Snapshot snapshot = org.mockito.Mockito.mock(Snapshot.class);
        when(indexService.snapshot()).thenReturn(snapshot);
        TraceFrame frame = new TraceFrame("t1", 3L, "h3", 0, 0, 0, "CALL",
                "alice", "bob", 10L, true, null, "", "", 21000L, false);
        when(snapshot.queryFrames(eq("t1"), eq("alice"), eq(1L), eq(9L), eq(0L), eq(20)))
                .thenReturn(new PageResult<>(List.of(frame), 1L, 0L, 20, -1L));

        mockMvc.perform(get("/api/traces")
                        .param("txHash", "t1")
                        .param("address", "alice")
                        .param("fromHeight", "1")
                        .param("toHeight", "9")
                        .param("offset", "0")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].to").value("bob"))
                .andExpect(jsonPath("$.nextOffset").value(-1));
    }

    @Test
    void queriesChangesAndStatusAndTriggersArchive() throws Exception {
        Snapshot snapshot = org.mockito.Mockito.mock(Snapshot.class);
        when(indexService.snapshot()).thenReturn(snapshot);
        when(snapshot.status()).thenReturn(new ChainStatus(7L, "h7", 2L));
        when(snapshot.queryChanges(any(), any(),
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PageResult<StateChange>(List.of(), 0L, 0L, 50, -1L));
        when(indexService.archive(5L)).thenReturn(new ArchiveResult(5L, 1, 1234L));

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headHeight").value(7))
                .andExpect(jsonPath("$.archivedToHeight").value(2));
        mockMvc.perform(get("/api/changes").param("address", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mockMvc.perform(post("/api/archive").param("toHeight", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedToHeight").value(5));
    }
}
