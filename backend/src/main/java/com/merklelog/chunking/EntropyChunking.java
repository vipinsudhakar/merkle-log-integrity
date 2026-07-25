package com.merklelog.chunking;

import com.merklelog.core.LogEntry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Content-defined chunking: cuts where the rolling Shannon entropy of recent payload bytes
 * crosses a threshold.
 *
 * <h2>The idea</h2>
 *
 * <p>Neither of the other two strategies looks at what the log actually says. This one does.
 * It maintains a sliding window over the most recent {@code windowBytes} of message content
 * and measures its Shannon entropy — the average number of bits needed per byte, from 0 (the
 * window is all one byte value) to 8 (every byte value equally likely).
 *
 * <p>Repetitive, templated logging ("heartbeat ok", "heartbeat ok", …) has low entropy.
 * Varied content — mixed identifiers, hex payloads, error text, base64 — has high entropy. A
 * rise past the threshold marks a change in the character of the data, and that is treated as
 * a natural boundary. Chunks therefore align to shifts in content rather than to counts or to
 * the clock.
 *
 * <h2>Known limitation — entropy over short payloads</h2>
 *
 * <p><b>This is a real weakness and is stated rather than hidden.</b> Shannon entropy
 * estimated from a small sample is biased <em>low</em> and is noisy. A 32-byte window can
 * contain at most 32 distinct byte values out of 256, so the measured entropy is capped near
 * {@code log2(windowBytes)} — a 32-byte window can never report more than 5 bits/byte even
 * for perfectly random data. For short log messages the signal therefore degrades toward
 * arbitrary, and boundaries start to look more like noise than like structure.
 *
 * <p>Two guards contain the damage rather than pretending it away:
 *
 * <ul>
 *   <li>{@code minChunkEntries} stops entropy noise from shattering the stream into tiny
 *       chunks;</li>
 *   <li>{@code maxChunkEntries} stops a long low-entropy run — a device emitting the same
 *       heartbeat for an hour — from collapsing into one enormous chunk, which would produce
 *       a deep tree, long proofs, and an expensive rebuild, distorting every comparison
 *       Phase 6 and Phase 7 report.</li>
 * </ul>
 *
 * <p>Choosing a larger {@code windowBytes} reduces the bias at the cost of responsiveness.
 * That trade-off is itself a parameter worth sweeping in the benchmark, and worth being
 * honest about in the write-up.
 *
 * <h2>Cost</h2>
 *
 * <p>O(1) amortised per payload byte to maintain the window's byte-frequency counts, plus a
 * fixed 256-slot pass to compute entropy once per entry. Chunking the whole stream is
 * therefore linear in total payload size — which is fine, since chunking happens once at
 * ingest, not on the proof path.
 */
public final class EntropyChunking implements ChunkingStrategy {

    /** Project defaults. See instructions.md — chosen for demo readability, swept in Phase 7. */
    public static final int DEFAULT_WINDOW_BYTES = 32;
    public static final double DEFAULT_THRESHOLD_BITS = 4.5;
    public static final int DEFAULT_MIN_CHUNK_ENTRIES = 16;
    public static final int DEFAULT_MAX_CHUNK_ENTRIES = 256;

    public static final String NAME = "entropy";

    private static final int BYTE_VALUES = 256;

    private final int windowBytes;
    private final double thresholdBits;
    private final int minChunkEntries;
    private final int maxChunkEntries;

    public EntropyChunking() {
        this(DEFAULT_WINDOW_BYTES, DEFAULT_THRESHOLD_BITS,
                DEFAULT_MIN_CHUNK_ENTRIES, DEFAULT_MAX_CHUNK_ENTRIES);
    }

    /**
     * @param windowBytes     how many recent payload bytes the entropy is measured over
     * @param thresholdBits   entropy in bits/byte at or above which a boundary is cut, 0..8
     * @param minChunkEntries a chunk may not be cut below this many entries
     * @param maxChunkEntries a chunk is force-cut at this many entries regardless of entropy
     */
    public EntropyChunking(int windowBytes, double thresholdBits, int minChunkEntries, int maxChunkEntries) {
        if (windowBytes < 1) {
            throw new IllegalArgumentException("windowBytes must be at least 1, got " + windowBytes);
        }
        if (thresholdBits < 0 || thresholdBits > 8) {
            throw new IllegalArgumentException(
                    "thresholdBits must be in [0, 8] — Shannon entropy of a byte cannot exceed 8 — got "
                            + thresholdBits);
        }
        if (minChunkEntries < 1) {
            throw new IllegalArgumentException("minChunkEntries must be at least 1, got " + minChunkEntries);
        }
        if (maxChunkEntries < minChunkEntries) {
            throw new IllegalArgumentException(
                    "maxChunkEntries (" + maxChunkEntries + ") must be at least minChunkEntries ("
                            + minChunkEntries + ")");
        }
        this.windowBytes = windowBytes;
        this.thresholdBits = thresholdBits;
        this.minChunkEntries = minChunkEntries;
        this.maxChunkEntries = maxChunkEntries;
    }

    @Override
    public List<Chunk> chunk(List<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        List<LogEntry> current = new ArrayList<>();
        RollingEntropyWindow window = new RollingEntropyWindow(windowBytes);

        for (LogEntry entry : entries) {
            current.add(entry);
            window.accept(entry.message().getBytes(StandardCharsets.UTF_8));

            if (shouldCut(current.size(), window)) {
                chunks.add(new Chunk(chunks.size(), current, NAME));
                current = new ArrayList<>();
                // The window is reset at a boundary so the next chunk's decision is made on
                // its own content, not on bytes belonging to the chunk we just closed.
                window.reset();
            }
        }

        if (!current.isEmpty()) {
            chunks.add(new Chunk(chunks.size(), current, NAME));
        }
        return chunks;
    }

    /**
     * The boundary decision, isolated so it can be read and reasoned about on its own.
     *
     * <p>Order matters here: the maximum is checked first so it always wins. Without that, a
     * long low-entropy run would never cut.
     */
    private boolean shouldCut(int entriesInChunk, RollingEntropyWindow window) {
        if (entriesInChunk >= maxChunkEntries) {
            return true; // hard ceiling — see the limitation note in the class javadoc
        }
        if (entriesInChunk < minChunkEntries) {
            return false; // too small to cut; suppresses entropy noise
        }
        return window.entropyBits() >= thresholdBits;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("windowBytes", String.valueOf(windowBytes));
        parameters.put("thresholdBits", String.valueOf(thresholdBits));
        parameters.put("minChunkEntries", String.valueOf(minChunkEntries));
        parameters.put("maxChunkEntries", String.valueOf(maxChunkEntries));
        return parameters;
    }

    public int windowBytes() {
        return windowBytes;
    }

    public double thresholdBits() {
        return thresholdBits;
    }

    public int minChunkEntries() {
        return minChunkEntries;
    }

    public int maxChunkEntries() {
        return maxChunkEntries;
    }

    /**
     * A fixed-size sliding window over a byte stream that can report its Shannon entropy.
     *
     * <p>Implemented as a circular buffer plus a 256-slot frequency table. Adding a byte
     * evicts the oldest one and adjusts two counts, so maintaining the window is O(1) per
     * byte — no rescanning of the window contents.
     *
     * <p>Package-private rather than private so it can be unit-tested directly against
     * hand-computed entropy values; the formula deserves its own tests rather than being
     * verified only through chunk boundaries.
     */
    static final class RollingEntropyWindow {

        private final byte[] buffer;
        private final int[] frequencies = new int[BYTE_VALUES];
        private int position;
        private int filled;

        RollingEntropyWindow(int capacity) {
            this.buffer = new byte[capacity];
        }

        /** Feeds bytes in, evicting the oldest once the window is full. */
        void accept(byte[] bytes) {
            for (byte b : bytes) {
                accept(b);
            }
        }

        void accept(byte b) {
            if (filled == buffer.length) {
                frequencies[buffer[position] & 0xFF]--; // evict the byte this slot held
            } else {
                filled++;
            }
            buffer[position] = b;
            frequencies[b & 0xFF]++;
            position = (position + 1) % buffer.length;
        }

        /**
         * Shannon entropy of the window in bits per byte: {@code H = -sum(p * log2(p))}.
         *
         * <p>Returns 0 for an empty window — with no data there is no uncertainty to measure,
         * and 0 keeps the "below threshold, do not cut" behaviour that a caller expects
         * before any content has been seen.
         */
        double entropyBits() {
            if (filled == 0) {
                return 0.0;
            }
            double entropy = 0.0;
            for (int frequency : frequencies) {
                if (frequency == 0) {
                    continue; // p log p tends to 0 as p tends to 0; skip rather than compute log(0)
                }
                double p = (double) frequency / filled;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
            return entropy;
        }

        void reset() {
            java.util.Arrays.fill(frequencies, 0);
            position = 0;
            filled = 0;
        }

        int size() {
            return filled;
        }
    }
}
