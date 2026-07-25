package com.merklelog.chunking;

import com.merklelog.core.LogEntry;

import java.util.List;
import java.util.Map;

/**
 * Decides where one chunk ends and the next begins.
 *
 * <p>The base paper validates a single adaptive-chunking approach. This interface is the
 * seam that lets us implement three and compare them on equal terms — the contribution this
 * project is making. Everything downstream (forest construction, proof generation,
 * benchmarking) is written against this interface, so a strategy can be swapped without any
 * other code knowing.
 *
 * <h2>Contract every implementation must honour</h2>
 *
 * <ol>
 *   <li><b>Partition, not selection.</b> Concatenating the chunks in order must reproduce the
 *       input list exactly — every entry appears once, none is dropped, duplicated or
 *       reordered. A strategy that lost an entry would silently exclude it from the integrity
 *       guarantee, which is worse than useless.</li>
 *   <li><b>No empty chunks.</b> Enforced by {@link Chunk} itself.</li>
 *   <li><b>Deterministic.</b> The same input must always yield the same boundaries, or the
 *       same data would produce different roots on different machines.</li>
 *   <li><b>Empty input yields an empty list</b>, not a list containing an empty chunk.</li>
 * </ol>
 *
 * <p>{@code ChunkingInvariantTest} checks all four against every registered strategy, so a
 * future strategy cannot quietly break them.
 */
public interface ChunkingStrategy {

    /**
     * Splits the entries into chunks, preserving order.
     *
     * @param entries the entries to partition; not modified
     * @return chunks in order, indexed from 0; empty if the input was empty
     */
    List<Chunk> chunk(List<LogEntry> entries);

    /** Stable identifier used in the API, benchmark labels and the comparison UI. */
    String name();

    /**
     * This strategy's tuning parameters, for display and for labelling benchmark runs.
     *
     * <p>Values are strings so that mixed types (counts, durations, thresholds) can be shown
     * in one table without the UI needing to know each strategy's specifics.
     */
    Map<String, String> parameters();

    /** Human-readable one-liner, e.g. {@code fixed-size(chunkSize=64)}. */
    default String describe() {
        StringBuilder sb = new StringBuilder(name()).append('(');
        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters().entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(parameter.getKey()).append('=').append(parameter.getValue());
            first = false;
        }
        return sb.append(')').toString();
    }
}
