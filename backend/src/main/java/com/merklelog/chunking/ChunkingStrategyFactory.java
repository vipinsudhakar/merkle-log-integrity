package com.merklelog.chunking;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a {@link ChunkingStrategy} from a name and optional parameters.
 *
 * <p>Exists so the Phase 2 API and the Phase 7 benchmark harness can select and configure a
 * strategy from strings — a query parameter, a config file, a sweep definition — without
 * either of them holding a hard-coded switch over the concrete classes. When a fourth
 * strategy is added, this is the only place that has to know.
 *
 * <p>Deliberately free of Spring: {@code core} and {@code chunking} must stay unit-testable
 * with no application context. Phase 2 can wrap this in a {@code @Bean} if it wants to.
 */
public final class ChunkingStrategyFactory {

    private ChunkingStrategyFactory() {
        // Static factory; never instantiated.
    }

    /** Every strategy name this factory understands, in the order the UI should present them. */
    public static List<String> availableStrategies() {
        return List.of(FixedSizeChunking.NAME, TimeWindowChunking.NAME, EntropyChunking.NAME);
    }

    /** Creates a strategy with the project default parameters. */
    public static ChunkingStrategy create(String name) {
        return create(name, Map.of());
    }

    /**
     * Creates a strategy, overriding any parameters supplied.
     *
     * <p>Unrecognised keys are ignored rather than rejected, so a UI can send one parameter
     * map covering every strategy and let each pick out what it understands. Values that
     * <em>are</em> recognised but malformed still fail loudly — a silently ignored bad
     * threshold would produce benchmark numbers that quietly do not mean what they claim.
     *
     * <table>
     *   <caption>Recognised parameters</caption>
     *   <tr><td>fixed-size</td>  <td>{@code chunkSize}</td></tr>
     *   <tr><td>time-window</td> <td>{@code windowSeconds}</td></tr>
     *   <tr><td>entropy</td>     <td>{@code windowBytes}, {@code thresholdBits},
     *                                {@code minChunkEntries}, {@code maxChunkEntries}</td></tr>
     * </table>
     *
     * @throws IllegalArgumentException if the name is unknown or a recognised value is malformed
     */
    public static ChunkingStrategy create(String name, Map<String, String> parameters) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parameters, "parameters");

        return switch (name) {
            case FixedSizeChunking.NAME -> new FixedSizeChunking(
                    intParam(parameters, "chunkSize", FixedSizeChunking.DEFAULT_CHUNK_SIZE));

            case TimeWindowChunking.NAME -> new TimeWindowChunking(Duration.ofSeconds(
                    longParam(parameters, "windowSeconds", TimeWindowChunking.DEFAULT_WINDOW.toSeconds())));

            case EntropyChunking.NAME -> new EntropyChunking(
                    intParam(parameters, "windowBytes", EntropyChunking.DEFAULT_WINDOW_BYTES),
                    doubleParam(parameters, "thresholdBits", EntropyChunking.DEFAULT_THRESHOLD_BITS),
                    intParam(parameters, "minChunkEntries", EntropyChunking.DEFAULT_MIN_CHUNK_ENTRIES),
                    intParam(parameters, "maxChunkEntries", EntropyChunking.DEFAULT_MAX_CHUNK_ENTRIES));

            default -> throw new IllegalArgumentException(
                    "Unknown chunking strategy '" + name + "'. Available: " + availableStrategies());
        };
    }

    /** All three strategies with default parameters — what the Phase 6 comparison page runs. */
    public static List<ChunkingStrategy> allWithDefaults() {
        return availableStrategies().stream().map(ChunkingStrategyFactory::create).toList();
    }

    private static int intParam(Map<String, String> parameters, String key, int fallback) {
        String value = parameters.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + key + "' must be an integer, got '" + value + "'", e);
        }
    }

    private static long longParam(Map<String, String> parameters, String key, long fallback) {
        String value = parameters.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + key + "' must be a number, got '" + value + "'", e);
        }
    }

    private static double doubleParam(Map<String, String> parameters, String key, double fallback) {
        String value = parameters.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + key + "' must be a number, got '" + value + "'", e);
        }
    }
}
