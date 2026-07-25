package com.merklelog.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChunkingStrategyFactory")
class ChunkingStrategyFactoryTest {

    @Test
    @DisplayName("creates each strategy by name with default parameters")
    void createsByName() {
        assertThat(ChunkingStrategyFactory.create("fixed-size")).isInstanceOf(FixedSizeChunking.class);
        assertThat(ChunkingStrategyFactory.create("time-window")).isInstanceOf(TimeWindowChunking.class);
        assertThat(ChunkingStrategyFactory.create("entropy")).isInstanceOf(EntropyChunking.class);
    }

    @Test
    @DisplayName("applies supplied parameters")
    void appliesParameters() {
        FixedSizeChunking fixed = (FixedSizeChunking) ChunkingStrategyFactory
                .create("fixed-size", Map.of("chunkSize", "128"));
        assertThat(fixed.chunkSize()).isEqualTo(128);

        TimeWindowChunking timed = (TimeWindowChunking) ChunkingStrategyFactory
                .create("time-window", Map.of("windowSeconds", "300"));
        assertThat(timed.window()).isEqualTo(Duration.ofMinutes(5));

        EntropyChunking entropy = (EntropyChunking) ChunkingStrategyFactory.create("entropy",
                Map.of("windowBytes", "64", "thresholdBits", "3.25",
                        "minChunkEntries", "8", "maxChunkEntries", "512"));
        assertThat(entropy.windowBytes()).isEqualTo(64);
        assertThat(entropy.thresholdBits()).isEqualTo(3.25);
        assertThat(entropy.minChunkEntries()).isEqualTo(8);
        assertThat(entropy.maxChunkEntries()).isEqualTo(512);
    }

    @Test
    @DisplayName("ignores parameters a strategy does not recognise")
    void ignoresIrrelevantParameters() {
        // Lets the UI send one parameter map covering every strategy and let each take what
        // it understands, rather than the caller having to know each strategy's specifics.
        FixedSizeChunking fixed = (FixedSizeChunking) ChunkingStrategyFactory.create("fixed-size",
                Map.of("chunkSize", "32", "windowSeconds", "999", "thresholdBits", "7.5"));

        assertThat(fixed.chunkSize()).isEqualTo(32);
    }

    @Test
    @DisplayName("a recognised parameter with a malformed value fails loudly")
    void rejectsMalformedValues() {
        // The opposite of the rule above, and deliberately so: silently falling back to a
        // default here would produce benchmark numbers that do not mean what they claim.
        assertThatThrownBy(() -> ChunkingStrategyFactory.create("fixed-size", Map.of("chunkSize", "sixty-four")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkSize");

        assertThatThrownBy(() -> ChunkingStrategyFactory.create("entropy", Map.of("thresholdBits", "high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thresholdBits");
    }

    @Test
    @DisplayName("an out-of-range value still fails the strategy's own validation")
    void propagatesStrategyValidation() {
        assertThatThrownBy(() -> ChunkingStrategyFactory.create("entropy", Map.of("thresholdBits", "12")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unknown strategy name is rejected with the available options listed")
    void rejectsUnknownName() {
        assertThatThrownBy(() -> ChunkingStrategyFactory.create("rabin-fingerprint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rabin-fingerprint")
                .hasMessageContaining("fixed-size");
    }

    @Test
    @DisplayName("availableStrategies lists exactly the three implemented strategies")
    void listsAvailableStrategies() {
        assertThat(ChunkingStrategyFactory.availableStrategies())
                .containsExactly("fixed-size", "time-window", "entropy");

        assertThat(ChunkingStrategyFactory.allWithDefaults())
                .hasSize(3)
                .extracting(ChunkingStrategy::name)
                .containsExactlyElementsOf(ChunkingStrategyFactory.availableStrategies());
    }
}
