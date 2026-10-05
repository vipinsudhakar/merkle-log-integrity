package com.merklelog.chunking;

import com.merklelog.core.LogEntry;
import com.merklelog.demo.SyntheticLogGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content-Anchored Adaptive Chunking (CAAC), the project's contribution.
 *
 * <p>The general chunking contract (partition, no empty chunks, determinism) is checked for CAAC
 * by {@code ChunkingInvariantTest}. This class checks what is specific to CAAC: the size range
 * comes from the paper's rule, cuts land on content anchors, and changes stay local.
 */
@DisplayName("ContentAnchoredChunking — CAAC")
class ContentAnchoredChunkingTest {

    private static final List<LogEntry> STREAM = SyntheticLogGenerator.generate(5_000);

    @Nested
    @DisplayName("size range and anchors")
    class SizeRangeAndAnchors {

        @Test
        @DisplayName("the size range is derived from the paper's target size T")
        void sizeRangeFromTarget() {
            // T = 70 at the paper's baseline pressure: min = 17, max = 140,
            // b = round(log2(70 - 17)) = 6, so the anchor mask is 63 and chunks average about 81.
            ContentAnchoredChunking.SizeRange range = ContentAnchoredChunking.SizeRange.forTarget(70);

            assertThat(range.min()).isEqualTo(17);
            assertThat(range.max()).isEqualTo(140);
            assertThat(range.anchorMask()).isEqualTo(63);
            assertThat(range.expectedLength()).isEqualTo(81.0);
        }

        @Test
        @DisplayName("a tiny target still gives a valid range: every entry is an anchor at T = 1")
        void degenerateTarget() {
            ContentAnchoredChunking.SizeRange range = ContentAnchoredChunking.SizeRange.forTarget(1);

            assertThat(range.min()).isEqualTo(1);
            assertThat(range.max()).isEqualTo(2);
            assertThat(range.anchorMask()).isZero();
        }

        @Test
        @DisplayName("every chunk is within [min, max]; every cut below max lands on an anchor")
        void chunksRespectTheRangeAndEndOnAnchors() {
            List<Chunk> chunks = new ContentAnchoredChunking().chunk(STREAM);
            ContentAnchoredChunking.SizeRange range = ContentAnchoredChunking.SizeRange.forTarget(70);

            for (int i = 0; i < chunks.size() - 1; i++) {   // the final remainder may be short
                Chunk chunk = chunks.get(i);
                assertThat(chunk.size()).as("chunk %d", i).isBetween(range.min(), range.max());

                if (chunk.size() < range.max()) {
                    LogEntry last = chunk.entries().get(chunk.size() - 1);
                    assertThat(ContentAnchoredChunking.isAnchor(last.leafHash(), range.anchorMask()))
                            .as("chunk %d must end on an anchor", i).isTrue();
                }
            }
        }

        @Test
        @DisplayName("the average chunk size is close to the paper's target")
        void averageSizeTracksTheTarget() {
            List<Chunk> chunks = new ContentAnchoredChunking().chunk(STREAM);
            double average = STREAM.size() / (double) chunks.size();

            // Expected about 81 (see sizeRangeFromTarget); allow sampling noise.
            assertThat(average).isBetween(55.0, 110.0);
        }

        @Test
        @DisplayName("the anchor test reads the low bits of the hash")
        void anchorTestReadsLowBits() {
            byte[] hash = new byte[32];
            hash[31] = 0b0100_0000;   // low 6 bits zero, bit 6 set

            assertThat(ContentAnchoredChunking.isAnchor(hash, 63)).isTrue();
            assertThat(ContentAnchoredChunking.isAnchor(hash, 127)).isFalse();
            assertThat(ContentAnchoredChunking.isAnchor(hash, 0)).isTrue();
        }
    }

    @Nested
    @DisplayName("edit locality — the contribution")
    class EditLocality {

        @ParameterizedTest(name = "insert at {0}")
        @ValueSource(ints = {1, 250, 1_000, 2_500, 4_999})
        @DisplayName("inserting one entry changes only the chunk roots around it")
        void insertionStaysLocal(int position) {
            List<LogEntry> inserted = ChunkLocality.insertAt(STREAM, position);

            int changed = ChunkLocality.chunkRootsChanged(new ContentAnchoredChunking(), STREAM, inserted);

            assertThat(changed).as("chunk roots changed by an insertion at %d", position).isBetween(1, 3);
        }

        @ParameterizedTest(name = "edit at {0}")
        @ValueSource(ints = {0, 777, 2_500, 4_999})
        @DisplayName("editing one entry changes only the chunk roots around it")
        void editStaysLocal(int position) {
            List<LogEntry> edited = ChunkLocality.editAt(STREAM, position);

            int changed = ChunkLocality.chunkRootsChanged(new ContentAnchoredChunking(), STREAM, edited);

            assertThat(changed).as("chunk roots changed by an edit at %d", position).isBetween(1, 3);
        }

        @Test
        @DisplayName("count-based strategies change every later chunk; CAAC does not")
        void comparedWithCountBasedStrategies() {
            List<LogEntry> inserted = ChunkLocality.insertAt(STREAM, 1_000);

            int caac = ChunkLocality.chunkRootsChanged(new ContentAnchoredChunking(), STREAM, inserted);
            int fixed = ChunkLocality.chunkRootsChanged(new FixedSizeChunking(), STREAM, inserted);
            int paper = ChunkLocality.chunkRootsChanged(new ResourceAwareChunking(), STREAM, inserted);

            // 4,000 entries follow the insertion: about 62 fixed-size chunks and 57 paper batches.
            assertThat(fixed).isGreaterThan(50);
            assertThat(paper).isGreaterThan(50);
            assertThat(caac).isLessThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("still adaptive — the paper's property is kept")
    class StillAdaptive {

        @Test
        @DisplayName("chunks shrink under the paper's stress profile and recover afterwards")
        void shrinksUnderStress() {
            List<LogEntry> stream = SyntheticLogGenerator.generate(10_000);
            ContentAnchoredChunking strategy = new ContentAnchoredChunking(
                    new ResourceAwareSizer(), MemoryPressureProfile.paperStressTest());

            double baselineAverage = averageSizeBetween(strategy.chunk(stream), 0, 2_000);
            double stressAverage = averageSizeBetween(strategy.chunk(stream), 2_000, 8_000);
            double recoveryAverage = averageSizeBetween(strategy.chunk(stream), 8_000, 10_000);

            assertThat(stressAverage).isLessThan(baselineAverage / 3);
            assertThat(recoveryAverage).isGreaterThan(stressAverage * 3);
        }

        @Test
        @DisplayName("the same content and profile always give the same boundaries")
        void deterministicUnderAProfile() {
            ContentAnchoredChunking strategy = new ContentAnchoredChunking(
                    new ResourceAwareSizer(), MemoryPressureProfile.paperStressTest());
            List<LogEntry> stream = SyntheticLogGenerator.generate(10_000);

            assertThat(strategy.chunk(stream).stream().map(Chunk::size).toList())
                    .isEqualTo(strategy.chunk(stream).stream().map(Chunk::size).toList());
        }

        private double averageSizeBetween(List<Chunk> chunks, int from, int to) {
            int position = 0;
            int count = 0;
            int entries = 0;
            for (Chunk chunk : chunks) {
                if (position >= from && position < to) {
                    count++;
                    entries += chunk.size();
                }
                position += chunk.size();
            }
            return entries / (double) count;
        }
    }

    @Test
    @DisplayName("it reports the paper's parameters and the pressure profile")
    void reportsParameters() {
        assertThat(new ContentAnchoredChunking().parameters())
                .containsKeys("totalMemory", "targetUtilisation", "scalingK", "minChunk", "maxChunk",
                        "pressureProfile", "pressureWindow");
        assertThat(new ContentAnchoredChunking().name()).isEqualTo("caac");
    }
}
