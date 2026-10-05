package com.merklelog.api;

import com.merklelog.core.LogEntry;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The presenter key on the public deployment: every database write needs it, nothing else does.
 */
@WebMvcTest(controllers = {StrategyController.class, DatasetController.class, SystemController.class,
        ForestController.class, AnchorController.class})
@Import({ForestService.class, ApiExceptionHandler.class, WebConfig.class, AdminGuard.class})
@TestPropertySource(properties = "app.admin-key=presenter-secret")
@DisplayName("AdminGuard — the presenter key locks database writes")
class AdminGuardTest {

    private static final List<LogEntry> STREAM = SyntheticLogGenerator.generate(500);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DatasetService datasets;

    @MockitoBean
    private RootAnchorRepository anchors;

    @BeforeEach
    void stub() {
        DatasetEntity dataset = new DatasetEntity("demo", SyntheticLogGenerator.DEFAULT_SEED, 500);
        when(datasets.get(1L)).thenReturn(dataset);
        when(datasets.stream(1L)).thenReturn(STREAM);
        when(datasets.list()).thenReturn(List.of(dataset));
        when(datasets.seedDemoData()).thenReturn(List.of());
        when(datasets.create(anyString(), anyInt(), anyLong())).thenReturn(dataset);
        when(datasets.overwriteMessage(anyLong(), anyInt(), anyString())).thenReturn(STREAM.get(3));
        when(datasets.restoreEntry(anyLong(), anyInt())).thenReturn(STREAM.get(3));
        when(anchors.save(any(RootAnchorEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    /** Every endpoint that writes to the database. */
    private List<MockHttpServletRequestBuilder> writes() {
        return List.of(
                post("/api/admin/seed"),
                post("/api/datasets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"size\":10}"),
                put("/api/datasets/1/entries/3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"PAYMENT APPROVED\"}"),
                post("/api/datasets/1/entries/3/restore"),
                post("/api/datasets/1/anchors").param("strategy", "caac"));
    }

    @Nested
    @DisplayName("writes")
    class Writes {

        @Test
        @DisplayName("are refused with 401 when the key is missing")
        void missingKey() throws Exception {
            for (MockHttpServletRequestBuilder write : writes()) {
                mvc.perform(write).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.detail").value(containsString("presenter key")));
            }
        }

        @Test
        @DisplayName("are refused with 401 when the key is wrong")
        void wrongKey() throws Exception {
            for (MockHttpServletRequestBuilder write : writes()) {
                mvc.perform(write.header(AdminGuard.HEADER, "presenter-secreT")).andExpect(status().isUnauthorized());
            }
        }

        @Test
        @DisplayName("go through with the right key")
        void rightKey() throws Exception {
            for (MockHttpServletRequestBuilder write : writes()) {
                mvc.perform(write.header(AdminGuard.HEADER, "presenter-secret"))
                        .andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(200, 299));
            }
        }
    }

    @Nested
    @DisplayName("everything else stays public")
    class Public {

        @Test
        @DisplayName("reads, proofs and the in-memory tamper comparison need no key")
        void readsArePublic() throws Exception {
            mvc.perform(get("/api/datasets")).andExpect(status().isOk());
            mvc.perform(get("/api/datasets/1/entries")).andExpect(status().isOk());
            mvc.perform(get("/api/datasets/1/anchors")).andExpect(status().isOk());
            mvc.perform(get("/api/datasets/1/proof/7").param("strategy", "caac")).andExpect(status().isOk());
            mvc.perform(post("/api/datasets/1/tamper").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"operation\":\"insert\",\"position\":100,\"message\":\"x\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("health and config are public, and config says a key is required")
        void healthAndConfig() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
            mvc.perform(get("/api/config")).andExpect(jsonPath("$.adminRequired").value(true));
        }
    }
}
