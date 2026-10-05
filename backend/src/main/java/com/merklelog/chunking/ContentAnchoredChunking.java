package com.merklelog.chunking;

import com.merklelog.core.LogEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Content-Anchored Adaptive Chunking (CAAC) — this project's contribution.
 *
 * <h2>The idea in one sentence</h2>
 *
 * <p>The base paper (Yağız et al. 2026, Eq. 1–2) decides <em>how big</em> a chunk may be from
 * memory pressure; CAAC keeps that rule and additionally decides <em>exactly where</em> to cut,
 * from the content, so that editing or inserting one entry only moves the boundaries next to it.
 *
 * <h2>Algorithm</h2>
 *
 * <p>At the start of each chunk, with pressure {@code P} at that position:
 *
 * <ol>
 *   <li><b>Size range (the paper's rule, unchanged).</b> {@code T = sizer.chunkSize(P)}, the
 *       paper's Eq. 1–2. The chunk may hold between {@code min = max(1, T/4)} and
 *       {@code max = 2T} entries.</li>
 *   <li><b>Content anchor (ours).</b> Once the chunk holds at least {@code min} entries, it ends
 *       after the first entry whose leaf hash has its low {@code b} bits all zero, with
 *       {@code b = round(log2(T − min))}. An entry is an anchor with probability {@code 2^-b},
 *       so a chunk is expected to be about {@code min + 2^b ≈ T} entries long.</li>
 *   <li><b>Ceiling.</b> If no anchor appears, the chunk is cut at {@code max} entries.</li>
 * </ol>
 *
 * <h2>Why this keeps edits local</h2>
 *
 * <p>Whether an entry is an anchor depends only on that entry's own leaf hash, which in turn
 * depends only on its own fields. So inserting or editing an entry cannot change whether any
 * <em>other</em> entry is an anchor. The chunk containing the change may end at a different
 * place, but as soon as the stream reaches an anchor that both versions cut at, the boundaries
 * after it are identical. With count-based cutting ({@link FixedSizeChunking},
 * {@link ResourceAwareChunking}) one insertion shifts every later boundary instead, so every
 * later chunk root changes.
 *
 * <p>This is the same idea backup tools such as rsync and restic use for files
 * (content-defined chunking); here the units are log entries rather than bytes, and the hash is
 * the SHA-256 leaf hash the Merkle tree needs anyway.
 *
 * <h2>Known limitations</h2>
 *
 * <ul>
 *   <li>Boundaries are content-anchored <em>within</em> a pressure regime. When the simulated
 *       pressure changes, {@code T} changes, and so do the size range and anchor pattern.</li>
 *   <li>A run of {@code max − min} entries with no anchor is force-cut, like fixed-size; locality
 *       is then restored at the next anchor rather than immediately.</li>
 * </ul>
 */
public final class ContentAnchoredChunking implements ChunkingStrategy {

    public static final String NAME = "caac";

    private final ResourceAwareSizer sizer;
    private final MemoryPressureProfile profile;

    public ContentAnchoredChunking() {
        this(new ResourceAwareSizer(), MemoryPressureProfile.baseline());
    }

    public ContentAnchoredChunking(ResourceAwareSizer sizer, MemoryPressureProfile profile) {
        this.sizer = Objects.requireNonNull(sizer, "sizer");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public List<Chunk> chunk(List<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");

        List<Chunk> chunks = new ArrayList<>();
        List<LogEntry> current = new ArrayList<>();
        SizeRange range = null;

        for (int position = 0; position < entries.size(); position++) {
            if (current.isEmpty()) {
                // A new chunk: take the paper's size for the pressure at this point in the stream.
                range = SizeRange.forTarget(sizer.chunkSize(profile.pressureAt(position)));
            }
            LogEntry entry = entries.get(position);
            current.add(entry);

            boolean atCeiling = current.size() >= range.max();
            boolean anchored = current.size() >= range.min() && isAnchor(entry.leafHash(), range.anchorMask());
            if (atCeiling || anchored) {
                chunks.add(new Chunk(chunks.size(), current, NAME));
                current = new ArrayList<>();
            }
        }

        if (!current.isEmpty()) {
            chunks.add(new Chunk(chunks.size(), current, NAME));
        }
        return chunks;
    }

    /**
     * True when the low bits of a leaf hash selected by {@code mask} are all zero.
     *
     * <p>Reads the last four bytes of the hash as an integer. SHA-256 output is uniformly
     * distributed, so each bit is 0 or 1 with equal probability and the test fires with
     * probability {@code 1 / (mask + 1)}.
     */
    static boolean isAnchor(byte[] leafHash, int mask) {
        int n = leafHash.length;
        int lowBits = ((leafHash[n - 4] & 0xFF) << 24)
                | ((leafHash[n - 3] & 0xFF) << 16)
                | ((leafHash[n - 2] & 0xFF) << 8)
                | (leafHash[n - 1] & 0xFF);
        return (lowBits & mask) == 0;
    }

    /**
     * The bounds and anchor pattern for one chunk, derived from the paper's target size.
     *
     * @param target     {@code T}, the paper's Eq. 1–2 chunk size
     * @param min        smallest chunk that may end at an anchor
     * @param max        size at which the chunk is force-cut
     * @param anchorMask {@code 2^b − 1}; an entry is an anchor when its low {@code b} bits are zero
     */
    record SizeRange(int target, int min, int max, int anchorMask) {

        static SizeRange forTarget(int target) {
            int min = Math.max(1, target / 4);
            int max = Math.max(min, 2 * target);
            int spread = target - min;
            int bits = spread < 1 ? 0 : (int) Math.round(Math.log(spread) / Math.log(2));
            return new SizeRange(target, min, max, (1 << bits) - 1);
        }

        /** Expected chunk length: {@code min} entries, then on average {@code 2^b} more. */
        double expectedLength() {
            return min + (anchorMask + 1.0);
        }
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
