package com.merklelog.chunking;

import com.merklelog.core.LogEntry;
import com.merklelog.demo.SyntheticLogGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The base paper's adaptive chunking (Yağız et al. 2026, §3.4, Algorithm 1), used as the baseline.
 */
@DisplayName("ResourceAwareChunking — the paper's method")
class ResourceAwareChunkingTest {

    @Test
    @DisplayName("under steady pressure every batch is the Eq. 1–2 size, except the remainder")
    void steadyPressureGivesUniformBatches() {
        List<Chunk> chunks = new ResourceAwareChunking().chunk(SyntheticLogGenerator.generate(1_000));

        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).size()).as("chunk %d", i).isEqualTo(70);
        }
        assertThat(chunks.get(chunks.size() - 1).size()).isEqualTo(1_000 % 70);
    }

    @Test
    @DisplayName("batches shrink under stress and recover afterwards, as in the paper's Fig. 4")
    void adaptsToThePaperStressProfile() {
        ResourceAwareChunking strategy = new ResourceAwareChunking(
                new ResourceAwareSizer(), MemoryPressureProfile.paperStressTest());
        List<Chunk> chunks = strategy.chunk(SyntheticLogGenerator.generate(10_000));

        int position = 0;
        for (Chunk chunk : chunks) {
            double pressure = MemoryPressureProfile.paperStressTest().pressureAt(position);
            int expected = new ResourceAwareSizer().chunkSize(pressure);
            if (position + chunk.size() < 10_000) {   // the final remainder may be short
                assertThat(chunk.size()).as("chunk starting at %d", position).isEqualTo(expected);
            }
            position += chunk.size();
        }
        // 9-entry batches under stress, 70 at baseline: many more batches than a steady run.
        assertThat(chunks.size()).isGreaterThan(new ResourceAwareChunking().chunk(
                SyntheticLogGenerator.generate(10_000)).size() * 3);
    }

    @Test
    @DisplayName("boundaries are a running count, so one insertion shifts every later boundary")
    void insertionShiftsLaterBoundaries() {
        // Stated as a property of the paper's method, not hidden: this is the gap CAAC closes.
        List<LogEntry> original = SyntheticLogGenerator.generate(2_000);
        List<LogEntry> inserted = ChunkLocality.insertAt(original, 1_000);

        ResourceAwareChunking strategy = new ResourceAwareChunking();
        int changed = ChunkLocality.chunkRootsChanged(strategy, original, inserted);
        int chunksAfterInsertion = (2_000 - 1_000) / 70;

        assertThat(changed).isGreaterThanOrEqualTo(chunksAfterInsertion);
    }

    @Test
    @DisplayName("it reports the paper's parameters and the pressure profile")
    void reportsParameters() {
        assertThat(new ResourceAwareChunking().parameters())
                .containsKeys("totalMemory", "targetUtilisation", "scalingK", "minChunk", "maxChunk",
                        "pressureProfile", "pressureWindow")
                .containsEntry("pressureProfile", "0.25");
    }
}
