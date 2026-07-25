package com.merklelog.chunking;

import com.merklelog.core.LogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract every {@link ChunkingStrategy} must satisfy, checked against all of them.
 *
 * <p>Written as one parameterised suite rather than duplicated per strategy so that a
 * strategy added later cannot quietly skip the rules — adding it to
 * {@link ChunkingStrategyFactory#allWithDefaults()} automatically subjects it to all of this.
 *
 * <p>The first invariant is the one that matters most. If chunking ever dropped an entry,
 * that entry would simply not be covered by any Merkle tree, so it could be altered freely
 * without changing any root. The system would still <em>look</em> like it worked.
 */
@DisplayName("Chunking — invariants every strategy must satisfy")
class ChunkingInvariantTest {

    static Stream<ChunkingStrategy> allStrategies() {
        return ChunkingStrategyFactory.allWithDefaults().stream();
    }

    /** Entries spread over time with varied payload content, to exercise all three rules. */
    static List<LogEntry> mixedEntries(int count) {
        List<LogEntry> entries = new ArrayList<>(count);
        Instant start = Instant.parse("2026-07-25T10:00:00Z");
        for (int i = 0; i < count; i++) {
            String message = (i % 5 == 0)
                    ? "heartbeat ok"                                    // low entropy, repetitive
                    : "sensor reading a3f" + Integer.toHexString(i * 2654435761L != 0 ? i * 31 : i)
                            + " value=" + (i % 97) + "." + (i % 13);    // higher entropy, varied
            entries.add(new LogEntry(
                    i, start.plusSeconds(i * 7L), i % 11 == 0 ? "WARN" : "INFO", "device-" + (i % 6), message));
        }
        return entries;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("chunks concatenate back to exactly the input — nothing lost, added or reordered")
    void chunksPartitionTheInput(ChunkingStrategy strategy) {
        List<LogEntry> input = mixedEntries(500);

        List<LogEntry> rebuilt = new ArrayList<>();
        for (Chunk chunk : strategy.chunk(input)) {
            rebuilt.addAll(chunk.entries());
        }

        // containsExactlyElementsOf checks order as well as membership, which is what makes
        // this a partition check rather than merely a set-equality check.
        assertThat(rebuilt).containsExactlyElementsOf(input);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("no chunk is empty")
    void noEmptyChunks(ChunkingStrategy strategy) {
        for (Chunk chunk : strategy.chunk(mixedEntries(500))) {
            assertThat(chunk.entries()).as("chunk %d", chunk.index()).isNotEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("chunk indices are sequential from zero")
    void chunkIndicesAreSequential(ChunkingStrategy strategy) {
        List<Chunk> chunks = strategy.chunk(mixedEntries(500));

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
            assertThat(chunks.get(i).strategyName()).isEqualTo(strategy.name());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("chunking is deterministic — same input, same boundaries")
    void chunkingIsDeterministic(ChunkingStrategy strategy) {
        // If boundaries varied between runs, the same data would produce different roots on
        // different machines, and the Render deployment would disagree with a laptop.
        List<LogEntry> input = mixedEntries(300);

        List<Integer> firstRun = strategy.chunk(input).stream().map(Chunk::size).toList();
        List<Integer> secondRun = strategy.chunk(input).stream().map(Chunk::size).toList();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("empty input gives an empty chunk list, not a list holding an empty chunk")
    void emptyInputGivesNoChunks(ChunkingStrategy strategy) {
        assertThat(strategy.chunk(List.of())).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("a single entry gives exactly one chunk holding it")
    void singleEntryGivesOneChunk(ChunkingStrategy strategy) {
        List<LogEntry> one = mixedEntries(1);
        List<Chunk> chunks = strategy.chunk(one);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).entries()).containsExactlyElementsOf(one);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("the input list is not modified")
    void inputIsNotModified(ChunkingStrategy strategy) {
        List<LogEntry> input = mixedEntries(200);
        List<LogEntry> snapshot = new ArrayList<>(input);

        strategy.chunk(input);

        assertThat(input).containsExactlyElementsOf(snapshot);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("null input is rejected")
    void nullInputRejected(ChunkingStrategy strategy) {
        assertThatThrownBy(() -> strategy.chunk(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("describe() names the strategy and its parameters")
    void describeIsInformative(ChunkingStrategy strategy) {
        assertThat(strategy.describe()).startsWith(strategy.name()).contains("=");
        assertThat(strategy.parameters()).isNotEmpty();
    }
}
