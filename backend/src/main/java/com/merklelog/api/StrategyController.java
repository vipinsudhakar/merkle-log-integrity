package com.merklelog.api;

import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.chunking.ChunkingStrategyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** The available strategies, and the committed benchmark results. */
@RestController
@RequestMapping("/api")
public class StrategyController {

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "fixed-size", "Cuts every N entries. Uniform and predictable, but blind to content and time.",
            "time-window", "Cuts when an entry falls outside the current time window.",
            "entropy", "Cuts where the rolling Shannon entropy of recent payload bytes crosses a threshold.",
            "resource-aware", "The base paper's method (Yağız et al. 2026, Eq. 1–2): batch size from memory pressure.",
            "caac", "Ours: the paper's memory-aware size range, cut where an entry's leaf hash matches a bit pattern.");

    /** Copied onto the classpath from docs/benchmarks/ at build time (see pom.xml). */
    private static final String BENCHMARK_RESULTS = "benchmarks/results.json";

    @GetMapping("/strategies")
    public List<Dto.StrategyInfo> strategies() {
        return ChunkingStrategyFactory.availableStrategies().stream()
                .map(name -> {
                    ChunkingStrategy strategy = ChunkingStrategyFactory.create(name);
                    return new Dto.StrategyInfo(name, DESCRIPTIONS.getOrDefault(name, ""), strategy.parameters());
                })
                .toList();
    }

    /**
     * The benchmark results committed in {@code docs/benchmarks/results.json}. Served from the
     * committed file rather than recomputed: a free-tier server would produce misleading timings,
     * and the full run takes minutes.
     */
    @GetMapping(value = "/benchmarks", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> benchmarks() throws IOException {
        ClassPathResource results = new ClassPathResource(BENCHMARK_RESULTS);
        if (!results.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(results.getContentAsString(StandardCharsets.UTF_8));
    }
}
