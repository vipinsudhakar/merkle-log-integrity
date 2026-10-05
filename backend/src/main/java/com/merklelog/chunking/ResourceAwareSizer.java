package com.merklelog.chunking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The base paper's chunk-sizing rule, Eq. 1 and Eq. 2, implemented exactly as published.
 *
 * <p>Source: Yağız, Horasan, Yurttakal, <i>Lightweight Tamper-Evident Log Integrity
 * Verification for IoT Edge Environments</i>, arXiv:2605.00065, 2026, §3.4.
 *
 * <pre>
 *   Eq. 1   C = clamp( ⌊ M_avail · M_target / K ⌋ , C_min, C_max )
 *   Eq. 2   A = 0.8 if P &gt; 0.8
 *               0.9 if P &gt; 0.6
 *               1.1 if P &lt; 0.3
 *               1.0 otherwise,          where P = 1 − M_avail / M_total
 * </pre>
 *
 * <p>The adjustment factor {@code A} "further modulates" {@code C}; we apply it to the clamped
 * value, floor the result, and clamp again so the final size always respects the bounds.
 *
 * <p>This class is shared by {@link ResourceAwareChunking} (the paper's method) and
 * {@link ContentAnchoredChunking} (ours). Sharing it is deliberate: CAAC keeps the paper's sizing
 * rule unchanged and only adds where to cut, so any difference in the results comes from that
 * addition and not from a different sizing rule.
 *
 * <h2>Units</h2>
 *
 * <p>The paper measures chunks in kilobytes; this project chunks by entries, because a chunk
 * here becomes a Merkle tree and tree size is counted in leaves. {@code K} is the paper's
 * "device-specific scaling constant". The defaults ({@code M_total = 1024}, {@code M_target = 0.5},
 * {@code K = 6}) are chosen so that the paper's baseline pressure of 0.25 gives about 70 entries
 * per chunk, close to the 64-entry fixed-size default, so the strategies are compared at similar
 * chunk sizes.
 */
public final class ResourceAwareSizer {

    public static final double DEFAULT_TOTAL_MEMORY = 1024.0;
    public static final double DEFAULT_TARGET_UTILISATION = 0.5;
    public static final double DEFAULT_SCALING_K = 6.0;
    public static final int DEFAULT_MIN_CHUNK = 8;
    public static final int DEFAULT_MAX_CHUNK = 256;

    private final double totalMemory;
    private final double targetUtilisation;
    private final double scalingK;
    private final int minChunk;
    private final int maxChunk;

    public ResourceAwareSizer() {
        this(DEFAULT_TOTAL_MEMORY, DEFAULT_TARGET_UTILISATION, DEFAULT_SCALING_K,
                DEFAULT_MIN_CHUNK, DEFAULT_MAX_CHUNK);
    }

    /**
     * @param totalMemory       {@code M_total}, simulated total memory; positive
     * @param targetUtilisation {@code M_target}, target utilisation ratio in (0, 1)
     * @param scalingK          {@code K}, the device-specific scaling constant; positive
     * @param minChunk          {@code C_min}; at least 1
     * @param maxChunk          {@code C_max}; at least {@code C_min}
     */
    public ResourceAwareSizer(double totalMemory, double targetUtilisation, double scalingK,
                              int minChunk, int maxChunk) {
        if (!(totalMemory > 0)) {
            throw new IllegalArgumentException("totalMemory must be positive, got " + totalMemory);
        }
        if (!(targetUtilisation > 0 && targetUtilisation < 1)) {
            throw new IllegalArgumentException("targetUtilisation must be in (0, 1), got " + targetUtilisation);
        }
        if (!(scalingK > 0)) {
            throw new IllegalArgumentException("scalingK must be positive, got " + scalingK);
        }
        if (minChunk < 1) {
            throw new IllegalArgumentException("minChunk must be at least 1, got " + minChunk);
        }
        if (maxChunk < minChunk) {
            throw new IllegalArgumentException(
                    "maxChunk (" + maxChunk + ") must be at least minChunk (" + minChunk + ")");
        }
        this.totalMemory = totalMemory;
        this.targetUtilisation = targetUtilisation;
        this.scalingK = scalingK;
        this.minChunk = minChunk;
        this.maxChunk = maxChunk;
    }

    /** Eq. 2: the adjustment factor for a given memory pressure. */
    public static double adjustmentFactor(double pressure) {
        if (pressure > 0.8) return 0.8;
        if (pressure > 0.6) return 0.9;
        if (pressure < 0.3) return 1.1;
        return 1.0;
    }

    /**
     * Eq. 1 then Eq. 2: the chunk size, in entries, for a given memory pressure.
     *
     * @param pressure {@code P} in [0, 1]
     */
    public int chunkSize(double pressure) {
        if (pressure < 0 || pressure > 1) {
            throw new IllegalArgumentException("pressure must be in [0, 1], got " + pressure);
        }
        double availableMemory = totalMemory * (1.0 - pressure);                 // P = 1 − M_avail/M_total
        int base = clamp((int) Math.floor(availableMemory * targetUtilisation / scalingK)); // Eq. 1
        return clamp((int) Math.floor(base * adjustmentFactor(pressure)));       // Eq. 2
    }

    private int clamp(int size) {
        return Math.max(minChunk, Math.min(maxChunk, size));
    }

    /** The sizing parameters, for display and benchmark labels. */
    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("totalMemory", String.valueOf(totalMemory));
        parameters.put("targetUtilisation", String.valueOf(targetUtilisation));
        parameters.put("scalingK", String.valueOf(scalingK));
        parameters.put("minChunk", String.valueOf(minChunk));
        parameters.put("maxChunk", String.valueOf(maxChunk));
        return parameters;
    }

    public int minChunk() {
        return minChunk;
    }

    public int maxChunk() {
        return maxChunk;
    }
}
