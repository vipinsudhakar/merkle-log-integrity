package com.merklelog.chunking;

import com.merklelog.core.LogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DisplayName("EntropyChunking")
class EntropyChunkingTest {

    private static final Instant BASE = Instant.parse("2026-07-25T10:00:00Z");

    private static List<LogEntry> withMessages(List<String> messages) {
        List<LogEntry> entries = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            entries.add(new LogEntry(i, BASE.plusSeconds(i), "INFO", "device-1", messages.get(i)));
        }
        return entries;
    }

    private static List<LogEntry> repeating(String message, int count) {
        List<String> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            messages.add(message);
        }
        return withMessages(messages);
    }

    @Nested
    @DisplayName("Shannon entropy calculation")
    class EntropyMaths {

        private double entropyOf(String content, int capacity) {
            EntropyChunking.RollingEntropyWindow window = new EntropyChunking.RollingEntropyWindow(capacity);
            window.accept(content.getBytes(StandardCharsets.UTF_8));
            return window.entropyBits();
        }

        @Test
        @DisplayName("a window of one repeated byte has zero entropy")
        void uniformContentHasZeroEntropy() {
            // Only one outcome is possible, so no bits are needed to describe it.
            assertThat(entropyOf("aaaaaaaa", 8)).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("two equally frequent byte values give exactly 1 bit per byte")
        void twoSymbolsGiveOneBit() {
            // H = -(0.5 log2 0.5 + 0.5 log2 0.5) = 1
            assertThat(entropyOf("abababab", 8)).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("four equally frequent byte values give exactly 2 bits per byte")
        void fourSymbolsGiveTwoBits() {
            // H = -4 * (0.25 log2 0.25) = 2
            assertThat(entropyOf("abcdabcd", 8)).isCloseTo(2.0, within(1e-9));
        }

        @Test
        @DisplayName("all-distinct bytes give log2(windowSize) bits — the small-sample ceiling")
        void distinctBytesGiveLog2OfWindowSize() {
            // This IS the documented limitation, asserted rather than described: an 8-byte
            // window cannot report more than 3 bits/byte no matter how random the data is,
            // because it can hold at most 8 of the 256 possible values.
            assertThat(entropyOf("abcdefgh", 8)).isCloseTo(3.0, within(1e-9));
        }

        @Test
        @DisplayName("random data in a small window is still capped well below 8 bits")
        void randomDataIsCappedByWindowSize() {
            byte[] random = new byte[32];
            new Random(42).nextBytes(random); // fixed seed: this assertion must be reproducible

            EntropyChunking.RollingEntropyWindow window = new EntropyChunking.RollingEntropyWindow(32);
            window.accept(random);

            // True entropy of random bytes is 8 bits. A 32-byte window can measure at most 5.
            assertThat(window.entropyBits()).isLessThanOrEqualTo(5.0);
        }

        @Test
        @DisplayName("an empty window reports zero rather than failing")
        void emptyWindowIsZero() {
            assertThat(new EntropyChunking.RollingEntropyWindow(16).entropyBits()).isZero();
        }

        @Test
        @DisplayName("the window slides — old bytes are evicted once it is full")
        void windowEvictsOldBytes() {
            EntropyChunking.RollingEntropyWindow window = new EntropyChunking.RollingEntropyWindow(4);

            window.accept("abcd".getBytes(StandardCharsets.UTF_8));
            assertThat(window.entropyBits()).isCloseTo(2.0, within(1e-9));

            // Push four identical bytes through; the earlier ones must be gone entirely.
            window.accept("zzzz".getBytes(StandardCharsets.UTF_8));
            assertThat(window.size()).isEqualTo(4);
            assertThat(window.entropyBits()).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("reset clears the window completely")
        void resetClearsWindow() {
            EntropyChunking.RollingEntropyWindow window = new EntropyChunking.RollingEntropyWindow(8);
            window.accept("abcdefgh".getBytes(StandardCharsets.UTF_8));

            window.reset();

            assertThat(window.size()).isZero();
            assertThat(window.entropyBits()).isZero();
        }
    }

    @Nested
    @DisplayName("chunk-size guards")
    class Guards {

        @Test
        @DisplayName("a long low-entropy run is force-cut at maxChunkEntries")
        void maxGuardCapsLowEntropyRuns() {
            // The failure this guard prevents: a device emitting one identical heartbeat for
            // an hour never trips the entropy threshold, so without a ceiling the whole run
            // would collapse into a single enormous chunk — a deep tree, long proofs, and an
            // expensive rebuild, distorting every number Phase 6 and 7 report.
            EntropyChunking strategy = new EntropyChunking(32, 4.5, 4, 20);

            List<Chunk> chunks = strategy.chunk(repeating("heartbeat ok", 200));

            assertThat(chunks).allSatisfy(chunk ->
                    assertThat(chunk.size()).as("chunk %d", chunk.index()).isLessThanOrEqualTo(20));
            assertThat(chunks).hasSize(10);
        }

        @Test
        @DisplayName("no chunk is cut below minChunkEntries, except the final remainder")
        void minGuardSuppressesEntropyNoise() {
            // Threshold 0 means "cut at every opportunity", so only the minimum holds the
            // boundaries back. This isolates the guard from the entropy signal entirely.
            EntropyChunking strategy = new EntropyChunking(32, 0.0, 10, 100);

            List<Chunk> chunks = strategy.chunk(repeating("varied content abcdef", 95));

            for (int i = 0; i < chunks.size() - 1; i++) {
                assertThat(chunks.get(i).size()).as("chunk %d", i).isEqualTo(10);
            }
            // The trailing remainder is allowed to be short — it is what is left over, not a cut.
            assertThat(chunks.get(chunks.size() - 1).size()).isEqualTo(5);
        }

        @Test
        @DisplayName("the maximum wins over the minimum when they would conflict")
        void maxTakesPrecedence() {
            // With min == max every chunk must be exactly that size.
            List<Chunk> chunks = new EntropyChunking(32, 8.0, 25, 25)
                    .chunk(repeating("identical", 100));

            assertThat(chunks).hasSize(4);
            assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.size()).isEqualTo(25));
        }

        @Test
        @DisplayName("an unreachable threshold means only the maximum ever cuts")
        void unreachableThresholdFallsBackToMax() {
            // 8 bits/byte is unattainable for a 32-byte window, so the entropy rule can never
            // fire and the strategy degenerates to fixed-size at the ceiling. Worth knowing:
            // it is the honest behaviour at the limit, not a hang or an error.
            List<Chunk> chunks = new EntropyChunking(32, 8.0, 1, 50)
                    .chunk(repeating("abcdefghijklmnop", 120));

            assertThat(chunks.stream().map(Chunk::size).toList()).containsExactly(50, 50, 20);
        }
    }

    @Nested
    @DisplayName("content sensitivity")
    class ContentSensitivity {

        @Test
        @DisplayName("boundaries respond to content, not just to counts")
        void boundariesDependOnContent() {
            // The claim that distinguishes this strategy from fixed-size: feed the same number
            // of entries with different content and the boundaries must differ.
            EntropyChunking strategy = new EntropyChunking(32, 3.5, 2, 1000);

            List<Integer> lowEntropySizes = strategy.chunk(repeating("aaaaaaaaaaaa", 100))
                    .stream().map(Chunk::size).toList();
            List<Integer> highEntropySizes = strategy.chunk(withMessages(variedMessages(100)))
                    .stream().map(Chunk::size).toList();

            assertThat(highEntropySizes).isNotEqualTo(lowEntropySizes);
        }

        @Test
        @DisplayName("a lower threshold cuts more often than a higher one")
        void lowerThresholdCutsMoreOften() {
            List<LogEntry> entries = withMessages(variedMessages(400));

            int atLowThreshold = new EntropyChunking(32, 2.0, 2, 1000).chunk(entries).size();
            int atHighThreshold = new EntropyChunking(32, 4.9, 2, 1000).chunk(entries).size();

            assertThat(atLowThreshold).isGreaterThan(atHighThreshold);
        }

        @Test
        @DisplayName("window size changes the boundaries — it is a real parameter, not decoration")
        void windowSizeAffectsBoundaries() {
            List<LogEntry> entries = withMessages(variedMessages(300));

            List<Integer> narrow = new EntropyChunking(8, 2.5, 2, 1000)
                    .chunk(entries).stream().map(Chunk::size).toList();
            List<Integer> wide = new EntropyChunking(128, 2.5, 2, 1000)
                    .chunk(entries).stream().map(Chunk::size).toList();

            assertThat(narrow).isNotEqualTo(wide);
        }

        private List<String> variedMessages(int count) {
            List<String> messages = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                messages.add(i % 3 == 0
                        ? "heartbeat"
                        : "err 0x" + Integer.toHexString(i * 7919) + " q=" + (i % 61) + "/" + (i % 37));
            }
            return messages;
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("defaults match the documented project defaults")
        void defaults() {
            EntropyChunking strategy = new EntropyChunking();

            assertThat(strategy.windowBytes()).isEqualTo(32);
            assertThat(strategy.thresholdBits()).isEqualTo(4.5);
            assertThat(strategy.minChunkEntries()).isEqualTo(16);
            assertThat(strategy.maxChunkEntries()).isEqualTo(256);
        }

        @Test
        @DisplayName("all four parameters are reported for display and benchmark labelling")
        void parametersAreReported() {
            assertThat(new EntropyChunking().parameters())
                    .containsKeys("windowBytes", "thresholdBits", "minChunkEntries", "maxChunkEntries");
        }

        @Test
        @DisplayName("invalid configuration is rejected at construction")
        void rejectsInvalidConfiguration() {
            assertThatThrownBy(() -> new EntropyChunking(0, 4.5, 16, 256))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windowBytes");

            // Entropy of a byte cannot exceed 8 bits, so a higher threshold is meaningless.
            assertThatThrownBy(() -> new EntropyChunking(32, 8.5, 16, 256))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thresholdBits");
            assertThatThrownBy(() -> new EntropyChunking(32, -0.1, 16, 256))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new EntropyChunking(32, 4.5, 0, 256))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minChunkEntries");

            assertThatThrownBy(() -> new EntropyChunking(32, 4.5, 100, 50))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxChunkEntries");
        }
    }
}
