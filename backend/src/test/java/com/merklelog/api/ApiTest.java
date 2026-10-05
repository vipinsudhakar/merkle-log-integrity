package com.merklelog.api;

import com.merklelog.core.Hashing;
import com.merklelog.core.LogEntry;
import com.merklelog.core.MerkleForest;
import com.merklelog.chunking.ChunkingStrategyFactory;
import com.merklelog.demo.SyntheticLogGenerator;
import com.merklelog.persistence.DatasetEntity;
import com.merklelog.persistence.DatasetService;
import com.merklelog.persistence.RootAnchorEntity;
import com.merklelog.persistence.RootAnchorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST API, with the database mocked and the engine real.
 *
 * <p>No PostgreSQL is needed: {@link DatasetService} returns a generated stream, so these tests
 * check that the API reports exactly what the engine computes. The database path itself (Flyway,
 * entities, batched inserts) is verified by running the application against PostgreSQL.
 */
@WebMvcTest(controllers = {StrategyController.class, DatasetController.class, SystemController.class,
        ForestController.class, AnchorController.class})
@Import({ForestService.class, ApiExceptionHandler.class, WebConfig.class, AdminGuard.class})
@DisplayName("REST API — engine results over HTTP")
class ApiTest {

    private static final List<LogEntry> STREAM = SyntheticLogGenerator.generate(2_000);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DatasetService datasets;

    @MockitoBean
    private RootAnchorRepository anchors;

    @BeforeEach
    void stubDataset() {
        when(datasets.get(1L)).thenReturn(new DatasetEntity("demo-2k", SyntheticLogGenerator.DEFAULT_SEED, 2_000));
        when(datasets.stream(1L)).thenReturn(STREAM);
        when(datasets.stream(99L)).thenThrow(new NoSuchElementException("No dataset with id 99"));
    }

    @Test
    @DisplayName("lists all five strategies with their default parameters")
    void strategies() throws Exception {
        mvc.perform(get("/api/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("fixed-size", "time-window", "entropy", "resource-aware", "caac")))
                .andExpect(jsonPath("$[4].defaults.pressureProfile").value("0.25"));
    }

    @Nested
    @DisplayName("chunks, trees and proofs")
    class ChunksTreesProofs {

        @Test
        @DisplayName("the chunk listing reports the engine's own super-root and partition")
        void chunksMatchTheEngine() throws Exception {
            MerkleForest expected = MerkleForest.build(STREAM, ChunkingStrategyFactory.create("caac"));

            mvc.perform(get("/api/datasets/1/chunks").param("strategy", "caac"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.superRoot").value(expected.superRootHex()))
                    .andExpect(jsonPath("$.chunkCount").value(expected.chunkCount()))
                    .andExpect(jsonPath("$.chunks[0].start").value(0))
                    .andExpect(jsonPath("$.chunks[1].start").value(expected.chunks().get(0).size()));
        }

        @Test
        @DisplayName("strategy parameters come from the query string")
        void parametersFromQuery() throws Exception {
            mvc.perform(get("/api/datasets/1/chunks").param("strategy", "fixed-size").param("chunkSize", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.chunkCount").value(20))
                    .andExpect(jsonPath("$.parameters.chunkSize").value("100"));
        }

        @Test
        @DisplayName("a chunk tree comes back as levels, leaves first, one root on top")
        void chunkTreeAsLevels() throws Exception {
            mvc.perform(get("/api/datasets/1/chunks/0/tree").param("strategy", "fixed-size"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.levels[0]", hasSize(64)))
                    .andExpect(jsonPath("$.levels[6]", hasSize(1)))
                    .andExpect(jsonPath("$.levels[0][0]").value(Hashing.toHex(STREAM.get(0).leafHash())));
        }

        @Test
        @DisplayName("a proof carries both stages with full traces, and verifies")
        void proofWithTrace() throws Exception {
            mvc.perform(get("/api/datasets/1/proof/1234").param("strategy", "caac"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.entry.leafHash").value(Hashing.toHex(STREAM.get(1234).leafHash())))
                    .andExpect(jsonPath("$.entryStage.valid").value(true))
                    .andExpect(jsonPath("$.chunkStage.valid").value(true))
                    .andExpect(jsonPath("$.totalSteps", lessThanOrEqualTo(14)));
        }
    }

    @Nested
    @DisplayName("tamper comparison")
    class Tamper {

        @Test
        @DisplayName("an insertion: CAAC changes a few chunks, fixed-size changes every later one")
        void insertion() throws Exception {
            mvc.perform(post("/api/datasets/1/tamper").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"operation\":\"insert\",\"position\":1000,\"message\":\"injected\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcomes[*].subject").value(contains(
                            "fixed-size", "time-window", "entropy", "resource-aware", "caac", "paper-pipeline")))
                    .andExpect(jsonPath("$.outcomes[4].changedChunks", hasSize(lessThanOrEqualTo(3))))
                    .andExpect(jsonPath("$.outcomes[0].changedChunks", hasSize(17)))
                    .andExpect(jsonPath("$.outcomes[5].rebuildHashOps").value(2_001))
                    .andExpect(jsonPath("$.outcomes[*].detected").value(contains(true, true, true, true, true, true)));
        }

        @Test
        @DisplayName("an edit: every forest rebuilds locally, the paper's pipeline rebuilds n")
        void edit() throws Exception {
            mvc.perform(post("/api/datasets/1/tamper").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"operation\":\"edit\",\"position\":500,\"message\":\"PAYMENT APPROVED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcomes[4].changedChunks", hasSize(1)))
                    .andExpect(jsonPath("$.outcomes[5].rebuildHashOps").value(2_000));
        }
    }

    @Nested
    @DisplayName("anchors")
    class Anchors {

        @Test
        @DisplayName("verification fails when the stored log no longer matches the anchored root")
        void detectsRewrittenLog() throws Exception {
            String describe = ChunkingStrategyFactory.create("caac").describe();
            when(anchors.findFirstByDatasetIdAndStrategyOrderByIdDesc(anyLong(), eq("caac")))
                    .thenReturn(Optional.of(new RootAnchorEntity(1, "caac", describe, 2_000, 30, "0".repeat(64))));

            mvc.perform(get("/api/datasets/1/anchors/verify").param("strategy", "caac"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.matches").value(false));
        }
    }

    @Nested
    @DisplayName("local development: no presenter key configured")
    class NoKeyConfigured {

        @Test
        @DisplayName("writes are allowed without a key, and config says none is required")
        void writesAreOpen() throws Exception {
            when(datasets.seedDemoData()).thenReturn(List.of());

            mvc.perform(post("/api/admin/seed")).andExpect(status().isOk());
            mvc.perform(get("/api/config")).andExpect(jsonPath("$.adminRequired").value(false));
            mvc.perform(get("/api/health")).andExpect(jsonPath("$.status").value("ok"));
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("an unknown dataset is 404")
        void unknownDataset() throws Exception {
            mvc.perform(get("/api/datasets/99/chunks")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an unknown strategy is 400, listing the available ones")
        void unknownStrategy() throws Exception {
            mvc.perform(get("/api/datasets/1/chunks").param("strategy", "nope"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("caac")));
        }

        @Test
        @DisplayName("an out-of-range proof index is 400")
        void badIndex() throws Exception {
            mvc.perform(get("/api/datasets/1/proof/2000")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an unknown tamper operation is 400")
        void badOperation() throws Exception {
            mvc.perform(post("/api/datasets/1/tamper").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"operation\":\"delete\",\"position\":3,\"message\":\"x\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
