package com.merklelog.chunking;

import com.merklelog.core.LogEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The base paper's adaptive chunking: batch size from memory pressure, and nothing else.
 *
 * <p>Source: Yağız, Horasan, Yurttakal 2026 (arXiv:2605.00065), §3.4 and Algorithm 1. Before each
 * batch the chunk size is recomputed with Eq. 1–2 ({@link ResourceAwareSizer}) from the current
 * memory pressure, and the next {@code C} entries become the batch.
 *
 * <p>This is implemented as faithfully as we can, because it is the baseline our contribution is
 * measured against. The only change is the source of the pressure signal: a simulated
 * {@link MemoryPressureProfile} instead of live memory readings, which is also what the paper
 * itself does for its stress test (§5.3), and which makes runs reproducible.
 *
 * <h2>What it does not look at</h2>
 *
 * <p>Neither content nor time. Boundaries are a running count, so inserting one entry shifts
 * every boundary after it, just as with {@link FixedSizeChunking}. In the paper's own pipeline
 * this matters less, because the chunks are only ingestion batches feeding one global tree (see
 * {@code PaperPipeline}); placed in our per-chunk forest it shows up as poor edit locality.
 * That is the gap {@link ContentAnchoredChunking} addresses.
 */
public final class ResourceAwareChunking implements ChunkingStrategy {

    public static final String NAME = "resource-aware";

    private final ResourceAwareSizer sizer;
    private final MemoryPressureProfile profile;

    public ResourceAwareChunking() {
        this(new ResourceAwareSizer(), MemoryPressureProfile.baseline());
    }

    public ResourceAwareChunking(ResourceAwareSizer sizer, MemoryPressureProfile profile) {
        this.sizer = Objects.requireNonNull(sizer, "sizer");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public List<Chunk> chunk(List<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");

        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < entries.size()) {
            // Algorithm 1, line 2: recompute the chunk size before each batch.
            int size = sizer.chunkSize(profile.pressureAt(start));
            int end = Math.min(start + size, entries.size());
            chunks.add(new Chunk(chunks.size(), entries.subList(start, end), NAME));
            start = end;
        }
        return chunks;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>(sizer.parameters());
        parameters.put("pressureProfile", profile.describe());
        parameters.put("pressureWindow", String.valueOf(profile.windowEntries()));
        return parameters;
    }

    public ResourceAwareSizer sizer() {
        return sizer;
    }

    public MemoryPressureProfile profile() {
        return profile;
    }
}
