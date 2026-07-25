package com.merklelog.chunking;

import com.merklelog.core.LogEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cuts a new chunk every {@code chunkSize} entries.
 *
 * <p>The simplest of the three, and the baseline the other two are measured against.
 *
 * <h2>What it is good at</h2>
 *
 * <p>Every chunk except possibly the last has identical size, so every tree has identical
 * depth and every proof is the same length. That predictability is genuinely valuable: proof
 * size becomes a constant the system can budget for, and rebuild cost after tampering is
 * bounded and uniform. It is also trivially parallelisable — boundaries are known in advance
 * without reading any content.
 *
 * <h2>What it ignores</h2>
 *
 * <p>It is blind to the data. A burst of a thousand entries in one second and a quiet hour
 * with three entries are chopped by the same rule, so a chunk can span an arbitrary amount
 * of wall-clock time. That is precisely the gap {@link TimeWindowChunking} and
 * {@link EntropyChunking} try to close, and why comparing the three is worth doing.
 */
public final class FixedSizeChunking implements ChunkingStrategy {

    /** Project default. See instructions.md — chosen for demo readability, swept in Phase 7. */
    public static final int DEFAULT_CHUNK_SIZE = 64;

    public static final String NAME = "fixed-size";

    private final int chunkSize;

    public FixedSizeChunking() {
        this(DEFAULT_CHUNK_SIZE);
    }

    /**
     * @param chunkSize entries per chunk; must be at least 1
     */
    public FixedSizeChunking(int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunkSize must be at least 1, got " + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    @Override
    public List<Chunk> chunk(List<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");

        List<Chunk> chunks = new ArrayList<>((entries.size() + chunkSize - 1) / chunkSize);
        for (int start = 0; start < entries.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, entries.size());
            // The final chunk is short whenever the total is not a multiple of chunkSize.
            // That is expected, not an error — it is simply the remainder.
            chunks.add(new Chunk(chunks.size(), entries.subList(start, end), NAME));
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
        parameters.put("chunkSize", String.valueOf(chunkSize));
        return parameters;
    }

    public int chunkSize() {
        return chunkSize;
    }
}
