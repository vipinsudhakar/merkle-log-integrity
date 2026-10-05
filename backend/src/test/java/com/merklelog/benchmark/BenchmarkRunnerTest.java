package com.merklelog.benchmark;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole benchmark on a small dataset and checks that the numbers it reports are the
 * ones the engine's own tests establish, so the results file cannot drift from the code.
 */
@DisplayName("BenchmarkRunner — end to end on a small dataset")
class BenchmarkRunnerTest {

    private static final int N = 2_000;
    private static Map<String, Object> results;

    @BeforeAll
    static void runOnce() {
        results = new BenchmarkRunner(List.of(N), 1, 1_000).run();
    }

    @Test
    @DisplayName("one result row per subject: five strategies plus the paper's pipeline")
    void oneRowPerSubject() {
        assertThat(rows("results")).extracting(r -> r.get("subject"))
                .containsExactly("fixed-size", "time-window", "entropy", "resource-aware", "caac", "paper-pipeline");
    }

    @Test
    @DisplayName("every forest builds with 2n - 1 hashes; the paper's pipeline pays for each batch rebuild")
    void ingestHashCounts() {
        for (Map<String, Object> row : rows("results")) {
            long ops = ((Number) row.get("ingestHashOps")).longValue();
            if (row.get("subject").equals("paper-pipeline")) {
                assertThat(ops).isGreaterThan(2L * N);
            } else {
                assertThat(ops).as("%s", row.get("subject")).isEqualTo(2L * N - 1);
            }
        }
    }

    @Test
    @DisplayName("the paper's pipeline rebuilds n hashes per edit and n + 1 per insertion")
    void paperPipelineCosts() {
        Map<String, Object> paper = row("paper-pipeline");

        assertThat(mean(paper, "editHashOps")).isEqualTo(N);
        assertThat(mean(paper, "insertHashOps")).isEqualTo(N + 1);
        assertThat(paper.get("insertChunkRootsChanged")).isNull();
    }

    @Test
    @DisplayName("CAAC keeps insertions local; fixed-size does not")
    void insertionLocality() {
        assertThat(mean(row("caac"), "insertChunkRootsChanged")).isLessThanOrEqualTo(3.0);
        assertThat(mean(row("fixed-size"), "insertChunkRootsChanged")).isGreaterThan(5.0);
        assertThat(mean(row("caac"), "insertHashOps")).isLessThan(mean(row("fixed-size"), "insertHashOps"));
        assertThat(mean(row("caac"), "insertHashOps")).isLessThan(mean(row("paper-pipeline"), "insertHashOps"));
    }

    @Test
    @DisplayName("tamper detection is perfect for every subject and ratio, as in the paper's Table 6")
    void tamperDetectionIsPerfect() {
        assertThat(rows("tamper")).hasSize(6 * BenchmarkRunner.TAMPER_RATIOS.length);
        for (Map<String, Object> row : rows("tamper")) {
            assertThat(row.get("detected")).as("%s @ %s", row.get("subject"), row.get("ratio")).isEqualTo(row.get("tampered"));
            assertThat(row.get("f1")).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("the results serialise to JSON")
    void serialisesToJson() {
        String json = Json.write(results);

        assertThat(json).startsWith("{").contains("\"subject\": \"caac\"").contains("\"tamper\"").contains("\"pressure\"");
    }

    @Test
    @DisplayName("sample positions are spread evenly and stay in range")
    void evenlySpacedIndices() {
        assertThat(BenchmarkRunner.evenlySpaced(101, 5, 0)).containsExactly(0, 25, 50, 75, 100);
        assertThat(BenchmarkRunner.evenlySpaced(10, 1, 1)).containsExactly(1);
        assertThat(BenchmarkRunner.evenlySpaced(10, 0, 0)).isEmpty();
    }

    // --- helpers -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(String key) {
        return (List<Map<String, Object>>) results.get(key);
    }

    private static Map<String, Object> row(String subject) {
        return rows("results").stream().filter(r -> r.get("subject").equals(subject)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static double mean(Map<String, Object> row, String metric) {
        return ((Number) ((Map<String, Object>) row.get(metric)).get("mean")).doubleValue();
    }
}
