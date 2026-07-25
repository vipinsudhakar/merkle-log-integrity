package com.merklelog.chunking;

import com.merklelog.core.LogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static com.merklelog.chunking.ChunkingInvariantTest.mixedEntries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FixedSizeChunking")
class FixedSizeChunkingTest {

    @ParameterizedTest(name = "{0} entries at size {1} -> {2} chunks, last of {3}")
    @CsvSource({
            "100, 10, 10, 10",   // exact multiple
            "100, 30,  4, 10",   // remainder
            "100,  1,100,  1",   // one entry per chunk
            "100,200,  1,100",   // chunk larger than the dataset
            "  1, 64,  1,  1",   // single entry
            "  7,  3,  3,  1",   // small remainder
    })
    @DisplayName("splits into the expected number of chunks with the expected remainder")
    void splitsAsExpected(int entryCount, int chunkSize, int expectedChunks, int expectedLastSize) {
        List<Chunk> chunks = new FixedSizeChunking(chunkSize).chunk(mixedEntries(entryCount));

        assertThat(chunks).hasSize(expectedChunks);
        assertThat(chunks.get(chunks.size() - 1).size()).isEqualTo(expectedLastSize);
    }

    @Test
    @DisplayName("every chunk but the last is exactly chunkSize")
    void allButLastAreFullSized() {
        List<Chunk> chunks = new FixedSizeChunking(25).chunk(mixedEntries(263));

        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).size()).as("chunk %d", i).isEqualTo(25);
        }
        assertThat(chunks.get(chunks.size() - 1).size()).isEqualTo(263 % 25);
    }

    @Test
    @DisplayName("uniform chunk sizes are the point — this is the predictability baseline")
    void chunkSizesAreUniform() {
        // The property the other two strategies trade away, asserted explicitly so the
        // comparison in Phase 6 has a documented baseline.
        List<Chunk> chunks = new FixedSizeChunking(50).chunk(mixedEntries(500));

        assertThat(chunks.stream().map(Chunk::size).distinct()).containsExactly(50);
    }

    @Test
    @DisplayName("entries stay in order within and across chunks")
    void preservesOrder() {
        List<LogEntry> input = mixedEntries(97);
        List<Chunk> chunks = new FixedSizeChunking(10).chunk(input);

        assertThat(chunks.get(0).entries().get(0)).isEqualTo(input.get(0));
        assertThat(chunks.get(1).entries().get(0)).isEqualTo(input.get(10));
        assertThat(chunks.get(9).entries().get(6)).isEqualTo(input.get(96));
    }

    @Test
    @DisplayName("the default chunk size is the documented project default")
    void defaultChunkSize() {
        assertThat(new FixedSizeChunking().chunkSize()).isEqualTo(64);
        assertThat(new FixedSizeChunking().parameters()).containsEntry("chunkSize", "64");
    }

    @Test
    @DisplayName("a chunk size below 1 is rejected")
    void rejectsInvalidChunkSize() {
        assertThatThrownBy(() -> new FixedSizeChunking(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
        assertThatThrownBy(() -> new FixedSizeChunking(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
