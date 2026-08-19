package com.merklelog.demo;

import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.chunking.ChunkingStrategyFactory;
import com.merklelog.core.Hashing;
import com.merklelog.core.LogEntry;
import com.merklelog.core.MerkleForest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generator only produces demo data, but two of its properties are load-bearing and are
 * therefore pinned here rather than left to chance.
 *
 * <p><b>Reproducibility.</b> Benchmark numbers and the hashes printed during the viva are
 * only meaningful if the same input comes back every run. If the seed ever stopped being
 * honoured, the failure would be silent — the demo would still print a root, just a different
 * one each time.
 *
 * <p><b>Usefulness for the comparison.</b> The whole contribution is that the three chunking
 * strategies behave differently. They only can if the stream is uneven in both time and
 * payload entropy; a uniform stream would make all three collapse onto the same answer and
 * quietly render the comparison meaningless.
 */
class SyntheticLogGeneratorTest {

    @Nested
    @DisplayName("Reproducibility")
    class Reproducibility {

        @Test
        @DisplayName("the same seed produces a byte-identical stream")
        void sameSeedProducesIdenticalStream() {
            assertThat(SyntheticLogGenerator.generate(200))
                    .isEqualTo(SyntheticLogGenerator.generate(200));
        }

        @Test
        @DisplayName("the same seed produces the same Merkle super-root")
        void sameSeedProducesSameSuperRoot() {
            ChunkingStrategy strategy = ChunkingStrategyFactory.create("fixed-size");

            byte[] first = MerkleForest.build(SyntheticLogGenerator.generate(200), strategy).superRoot();
            byte[] second = MerkleForest.build(SyntheticLogGenerator.generate(200), strategy).superRoot();

            assertThat(Hashing.equal(first, second)).isTrue();
        }

        @Test
        @DisplayName("a different seed produces a different stream")
        void differentSeedProducesDifferentStream() {
            assertThat(SyntheticLogGenerator.generate(200, 1L))
                    .isNotEqualTo(SyntheticLogGenerator.generate(200, 2L));
        }

        @Test
        @DisplayName("a prefix of a longer stream is the shorter stream")
        void generationIsPrefixStable() {
            assertThat(SyntheticLogGenerator.generate(100))
                    .isEqualTo(SyntheticLogGenerator.generate(250).subList(0, 100));
        }
    }

    @Nested
    @DisplayName("Shape")
    class Shape {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 63, 64, 65, 500})
        @DisplayName("produces exactly the requested number of entries")
        void producesRequestedCount(int count) {
            assertThat(SyntheticLogGenerator.generate(count)).hasSize(count);
        }

        @Test
        @DisplayName("rejects a negative count")
        void rejectsNegativeCount() {
            assertThatThrownBy(() -> SyntheticLogGenerator.generate(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ids run 1..n with no gaps")
        void idsAreContiguous() {
            List<LogEntry> entries = SyntheticLogGenerator.generate(300);

            assertThat(entries).extracting(LogEntry::id)
                    .containsExactlyElementsOf(
                            IntStream.rangeClosed(1, 300).mapToObj(Long::valueOf).toList());
        }

        @Test
        @DisplayName("timestamps are strictly increasing, as time-window chunking assumes")
        void timestampsIncreaseStrictly() {
            List<LogEntry> entries = SyntheticLogGenerator.generate(500);

            for (int i = 1; i < entries.size(); i++) {
                assertThat(entries.get(i).timestamp())
                        .as("entry %d must be later than entry %d", i, i - 1)
                        .isAfter(entries.get(i - 1).timestamp());
            }
        }

        @Test
        @DisplayName("the returned list is immutable")
        void listIsImmutable() {
            List<LogEntry> entries = SyntheticLogGenerator.generate(5);

            assertThatThrownBy(() -> entries.add(entries.get(0)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Variety the strategy comparison depends on")
    class Variety {

        @Test
        @DisplayName("the stream mixes low- and high-entropy payloads")
        void payloadsVaryInEntropy() {
            List<LogEntry> entries = SyntheticLogGenerator.generate(500);

            // Routine traffic is repetitive short text; anomalies carry a long encoded token.
            // Entropy chunking needs both present, or there is no boundary signal at all.
            assertThat(entries).anyMatch(entry -> entry.message().length() < 30);
            assertThat(entries).anyMatch(entry -> entry.message().length() > 30);
            assertThat(entries).extracting(LogEntry::level).contains("INFO", "WARN", "ERROR");
        }

        @Test
        @DisplayName("arrivals are bursty, so time-window chunking yields more than one window")
        void arrivalsAreBursty() {
            List<LogEntry> entries = SyntheticLogGenerator.generate(500);
            ChunkingStrategy timeWindow = ChunkingStrategyFactory.create("time-window");

            assertThat(timeWindow.chunk(entries)).hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("the three strategies do not all partition the stream identically")
        void strategiesDisagree() {
            List<LogEntry> entries = SyntheticLogGenerator.generate(500);

            List<Integer> chunkCounts = ChunkingStrategyFactory.allWithDefaults().stream()
                    .map(strategy -> strategy.chunk(entries).size())
                    .toList();

            // If every strategy landed on the same partition the comparison would be vacuous.
            assertThat(Set.copyOf(chunkCounts)).hasSizeGreaterThan(1);
        }
    }
}
