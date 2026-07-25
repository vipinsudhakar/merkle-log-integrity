package com.merklelog.chunking;

import com.merklelog.core.LogEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A contiguous run of log entries that will become one independent Merkle tree.
 *
 * <p>This is the pivot of the whole project. Because a chunk becomes its own tree rather
 * than a slice of one global tree, the chunking strategy directly controls:
 *
 * <ul>
 *   <li><b>proof size</b> — a proof only has to climb {@code log2(chunkSize)} levels, not
 *       {@code log2(totalEntries)};</li>
 *   <li><b>rebuild cost</b> — tampering with one entry invalidates only its own chunk's
 *       tree, so re-hashing is proportional to the chunk, not the dataset;</li>
 *   <li><b>root count</b> — more chunks means more roots to seal under the super-root.</li>
 * </ul>
 *
 * <p>Those three quantities are exactly what Phase 7 benchmarks across the three strategies.
 *
 * @param index          position of this chunk in the sequence, starting at 0
 * @param entries        the entries it covers, in their original order
 * @param strategyName   which strategy produced it, carried through for display and benchmarks
 */
public record Chunk(int index, List<LogEntry> entries, String strategyName) {

    public Chunk {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(strategyName, "strategyName");
        if (index < 0) {
            throw new IllegalArgumentException("Chunk index must be non-negative, got " + index);
        }
        if (entries.isEmpty()) {
            // An empty chunk would produce an empty tree whose root is the empty-tree hash,
            // adding a meaningless node to the forest and skewing every average the benchmark
            // reports. No strategy is allowed to emit one.
            throw new IllegalArgumentException("A chunk must contain at least one entry");
        }
        entries = List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    /** Timestamp of the first entry. */
    public Instant startTime() {
        return entries.get(0).timestamp();
    }

    /** Timestamp of the last entry. */
    public Instant endTime() {
        return entries.get(entries.size() - 1).timestamp();
    }

    /** Wall-clock span this chunk covers — the headline metric for time-window chunking. */
    public Duration span() {
        return Duration.between(startTime(), endTime());
    }

    @Override
    public String toString() {
        return "Chunk#" + index + "{" + strategyName + ", entries=" + size() + "}";
    }
}
