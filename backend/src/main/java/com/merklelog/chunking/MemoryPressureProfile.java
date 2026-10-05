package com.merklelog.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A simulated memory-pressure signal, keyed by position in the log stream.
 *
 * <h2>Why simulated rather than read from the JVM</h2>
 *
 * <p>The base paper (Yağız et al. 2026) sizes batches from the memory that happens to be free
 * at runtime. That makes boundaries depend on the machine and the moment: the same log can be
 * chunked differently on two runs, which breaks our determinism invariant and makes benchmarks
 * impossible to compare. The paper hits the same problem in its own stress test (§5.3) and
 * solves it the same way we do: it overrides the pressure signal with a fixed profile.
 *
 * <p>Pressure {@code P} is the fraction of memory in use, {@code P = 1 − M_avail / M_total},
 * so it lies in {@code [0, 1]}.
 *
 * <h2>How positions map to pressure</h2>
 *
 * <p>The stream is divided into windows of {@code windowEntries} entries. Window {@code w}
 * has pressure {@code pressures.get(w)}; positions past the last window keep the last value,
 * so a one-element profile is a constant.
 *
 * @param pressures     pressure for each window, each in [0, 1]; at least one value
 * @param windowEntries entries per window; at least 1
 */
public record MemoryPressureProfile(List<Double> pressures, int windowEntries) {

    /** Baseline pressure used in the paper's stress test (§5.3). */
    public static final double PAPER_BASELINE = 0.25;

    /** Stress pressure used in the paper's stress test (§5.3). */
    public static final double PAPER_STRESS = 0.85;

    /** Window size used in the paper's stress test: 2000 entries. */
    public static final int PAPER_WINDOW_ENTRIES = 2000;

    public MemoryPressureProfile {
        Objects.requireNonNull(pressures, "pressures");
        if (pressures.isEmpty()) {
            throw new IllegalArgumentException("A pressure profile needs at least one value");
        }
        for (Double pressure : pressures) {
            if (pressure == null || pressure < 0.0 || pressure > 1.0) {
                throw new IllegalArgumentException("Pressure must be in [0, 1], got " + pressure);
            }
        }
        if (windowEntries < 1) {
            throw new IllegalArgumentException("windowEntries must be at least 1, got " + windowEntries);
        }
        pressures = List.copyOf(pressures);
    }

    /** The same pressure everywhere. */
    public static MemoryPressureProfile constant(double pressure) {
        return new MemoryPressureProfile(List.of(pressure), PAPER_WINDOW_ENTRIES);
    }

    /** The default: steady baseline pressure, as in the paper's normal operation. */
    public static MemoryPressureProfile baseline() {
        return constant(PAPER_BASELINE);
    }

    /**
     * The paper's stress-test profile (§5.3, Fig. 4): one baseline window, three windows under
     * stress, then recovery to baseline.
     */
    public static MemoryPressureProfile paperStressTest() {
        return new MemoryPressureProfile(
                List.of(PAPER_BASELINE, PAPER_STRESS, PAPER_STRESS, PAPER_STRESS, PAPER_BASELINE),
                PAPER_WINDOW_ENTRIES);
    }

    /**
     * Parses a comma-separated list such as {@code "0.25,0.85,0.85,0.25"}, for the API and the
     * benchmark configuration.
     *
     * @throws IllegalArgumentException if a value is not a number or is outside [0, 1]
     */
    public static MemoryPressureProfile parse(String commaSeparated, int windowEntries) {
        Objects.requireNonNull(commaSeparated, "commaSeparated");
        List<Double> values = new ArrayList<>();
        for (String part : commaSeparated.split(",")) {
            try {
                values.add(Double.parseDouble(part.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a pressure value: '" + part.trim() + "'", e);
            }
        }
        return new MemoryPressureProfile(values, windowEntries);
    }

    /** Pressure at a position in the stream (0-based). */
    public double pressureAt(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative, got " + position);
        }
        int window = position / windowEntries;
        return pressures.get(Math.min(window, pressures.size() - 1));
    }

    /** The profile as a comma-separated string, the inverse of {@link #parse}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pressures.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(pressures.get(i));
        }
        return sb.toString();
    }
}
