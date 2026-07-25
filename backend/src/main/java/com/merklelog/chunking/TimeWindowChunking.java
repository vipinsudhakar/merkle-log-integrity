package com.merklelog.chunking;

import com.merklelog.core.LogEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Starts a new chunk once an entry falls outside the current chunk's time window.
 *
 * <p>Chunks align to wall-clock periods rather than to counts, which is how log data is
 * usually reasoned about in practice: "prove nothing in the 09:00–09:01 window was altered"
 * is a question an auditor actually asks, and here it maps to one chunk and one root.
 *
 * <h2>The trade-off this strategy makes</h2>
 *
 * <p>Chunk sizes become as uneven as the traffic. A quiet minute produces a one-entry chunk
 * whose tree has depth 0 and whose proof is empty; a burst produces a large chunk with
 * correspondingly longer proofs. So this strategy buys semantically meaningful boundaries at
 * the cost of the predictable proof size that {@link FixedSizeChunking} gives. Quantifying
 * that trade-off is one of the things Phase 6 and Phase 7 exist to show.
 *
 * <h2>Out-of-order timestamps</h2>
 *
 * <p>Each entry is compared against the timestamp of the entry that <em>opened</em> the
 * current chunk, not against the previous entry. A chunk therefore covers
 * {@code [start, start + window)} regardless of jitter within it. An entry that arrives with
 * a timestamp earlier than its chunk's start (clock skew across IoT devices is real) still
 * lands in the current chunk rather than opening a spurious new one — the comparison is on
 * elapsed time, and a negative elapsed time never exceeds the window.
 */
public final class TimeWindowChunking implements ChunkingStrategy {

    /** Project default. See instructions.md — chosen for demo readability, swept in Phase 7. */
    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);

    public static final String NAME = "time-window";

    private final Duration window;

    public TimeWindowChunking() {
        this(DEFAULT_WINDOW);
    }

    /**
     * @param window the wall-clock span a single chunk may cover; must be positive
     */
    public TimeWindowChunking(Duration window) {
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            // A zero window would put every entry in its own chunk only by accident of
            // timestamp equality, and a negative one is meaningless. Reject both loudly.
            throw new IllegalArgumentException("window must be positive, got " + window);
        }
        this.window = window;
    }

    @Override
    public List<Chunk> chunk(List<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        List<LogEntry> current = new ArrayList<>();
        Instant windowStart = entries.get(0).timestamp();

        for (LogEntry entry : entries) {
            Duration elapsed = Duration.between(windowStart, entry.timestamp());

            // Close the chunk once this entry sits at or beyond the window's end. The window
            // is half-open — [start, start + window) — so an entry exactly one window later
            // opens the next chunk rather than ending the current one.
            if (!current.isEmpty() && elapsed.compareTo(window) >= 0) {
                chunks.add(new Chunk(chunks.size(), current, NAME));
                current = new ArrayList<>();
                windowStart = entry.timestamp();
            }
            current.add(entry);
        }

        // Whatever is still open at the end is a legitimate final chunk.
        if (!current.isEmpty()) {
            chunks.add(new Chunk(chunks.size(), current, NAME));
        }
        return chunks;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("windowSeconds", String.valueOf(window.toSeconds()));
        return parameters;
    }

    public Duration window() {
        return window;
    }
}
