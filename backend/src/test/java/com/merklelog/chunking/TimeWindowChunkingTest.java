package com.merklelog.chunking;

import com.merklelog.core.LogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TimeWindowChunking")
class TimeWindowChunkingTest {

    private static final Instant BASE = Instant.parse("2026-07-25T10:00:00Z");

    private static LogEntry at(long secondsFromBase) {
        return new LogEntry(secondsFromBase, BASE.plusSeconds(secondsFromBase),
                "INFO", "device-1", "reading at +" + secondsFromBase + "s");
    }

    private static List<LogEntry> at(long... secondsFromBase) {
        List<LogEntry> entries = new ArrayList<>(secondsFromBase.length);
        for (long s : secondsFromBase) {
            entries.add(at(s));
        }
        return entries;
    }

    @Test
    @DisplayName("entries inside one window form a single chunk")
    void entriesWithinWindowStayTogether() {
        List<Chunk> chunks = new TimeWindowChunking(Duration.ofSeconds(60))
                .chunk(at(0, 10, 20, 30, 59));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).size()).isEqualTo(5);
    }

    @Test
    @DisplayName("the window is half-open — an entry exactly one window later starts a new chunk")
    void windowIsHalfOpen() {
        // [start, start + window). The boundary case is worth pinning down explicitly,
        // because an off-by-one here silently changes every chunk count in the benchmark.
        List<Chunk> chunks = new TimeWindowChunking(Duration.ofSeconds(60)).chunk(at(0, 59, 60, 61));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).size()).isEqualTo(2); // 0s, 59s
        assertThat(chunks.get(1).size()).isEqualTo(2); // 60s, 61s
    }

    @Test
    @DisplayName("each new window is measured from the entry that opened it, not from a fixed grid")
    void windowRestartsFromChunkOpener() {
        // Entries at 0, 100, 150. The second window opens at 100, so 150 is only 50s in and
        // belongs with it. A fixed 60s grid would instead have put 150 in its own bucket.
        List<Chunk> chunks = new TimeWindowChunking(Duration.ofSeconds(60)).chunk(at(0, 100, 150));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).size()).isEqualTo(1);
        assertThat(chunks.get(1).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a long gap produces single-entry chunks, not empty ones")
    void longGapsProduceSingletonChunks() {
        List<Chunk> chunks = new TimeWindowChunking(Duration.ofSeconds(60))
                .chunk(at(0, 3600, 7200));

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.size()).isEqualTo(1));
    }

    @Test
    @DisplayName("chunk sizes track traffic — bursts give large chunks, quiet periods small ones")
    void chunkSizeFollowsTraffic() {
        // The defining behaviour of this strategy, and the trade-off against fixed-size:
        // meaningful boundaries, but unpredictable proof lengths.
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            entries.add(at(i)); // burst: 40 entries in 40 seconds
        }
        entries.add(at(500));   // then a lone entry much later

        List<Chunk> chunks = new TimeWindowChunking(Duration.ofSeconds(60)).chunk(entries);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).size()).isEqualTo(40);
        assertThat(chunks.get(1).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("no chunk spans longer than its window")
    void noChunkExceedsItsWindow() {
        Duration window = Duration.ofSeconds(60);
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            entries.add(at(i * 3L)); // one entry every 3 seconds
        }

        for (Chunk chunk : new TimeWindowChunking(window).chunk(entries)) {
            assertThat(chunk.span()).as("chunk %d", chunk.index()).isLessThan(window);
        }
    }

    @Test
    @DisplayName("an out-of-order timestamp does not open a spurious chunk")
    void toleratesClockSkew() {
        // IoT devices disagree about the time. An entry stamped slightly before its chunk's
        // start has negative elapsed time, which must not be read as "past the window".
        List<LogEntry> entries = at(0, 10, 5, 20);

        List<Chunk> chunks = new TimeWindowChunking(Duration.ofSeconds(60)).chunk(entries);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).size()).isEqualTo(4);
    }

    @Test
    @DisplayName("the default window is the documented project default")
    void defaultWindow() {
        assertThat(new TimeWindowChunking().window()).isEqualTo(Duration.ofSeconds(60));
        assertThat(new TimeWindowChunking().parameters()).containsEntry("windowSeconds", "60");
    }

    @Test
    @DisplayName("a zero or negative window is rejected")
    void rejectsInvalidWindow() {
        assertThatThrownBy(() -> new TimeWindowChunking(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new TimeWindowChunking(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
