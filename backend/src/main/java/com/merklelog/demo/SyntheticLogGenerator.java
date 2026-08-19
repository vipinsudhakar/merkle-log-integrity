package com.merklelog.demo;

import com.merklelog.core.LogEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Produces a synthetic IoT-edge log stream for the console demo and, later, for seeding.
 *
 * <h2>Why the RNG seed is fixed</h2>
 *
 * <p>Every number this project reports — proof size, chunk count, rebuild cost — is a
 * function of the data it ran on. A stream that changed between runs would make two
 * benchmark results incomparable and would make a demo impossible to rehearse: the root hash
 * printed on screen would differ from the one in the report. A fixed seed makes the whole
 * dataset reproducible from nothing but its length, which is also what the disposable-database
 * seeding path in Phase 3 needs.
 *
 * <h2>Why the stream is deliberately uneven</h2>
 *
 * <p>The three chunking strategies key off different signals, so a uniform stream would make
 * them look identical and the comparison would say nothing:
 *
 * <ul>
 *   <li><b>Fixed-size</b> ignores content entirely — it cuts every N entries whatever happens.</li>
 *   <li><b>Time-window</b> needs uneven inter-arrival gaps. Real devices are bursty: quiet
 *       periods then a flurry, so the generator alternates short gaps with occasional long
 *       idle jumps.</li>
 *   <li><b>Entropy</b> needs the payload's byte distribution to actually vary. Routine
 *       heartbeats are near-identical text (low entropy); error payloads carrying encoded
 *       identifiers or stack fragments look closer to random (high entropy).</li>
 * </ul>
 *
 * <p>This class lives in {@code demo} rather than {@code core} on purpose: it is scaffolding
 * for showing the system off, not part of the integrity logic. It has no Spring imports, so
 * the Phase 3 seeder can reuse it as-is.
 */
public final class SyntheticLogGenerator {

    /** Fixed so that every run of the demo prints byte-identical hashes. */
    public static final long DEFAULT_SEED = 20260819L;

    /** The stream starts here rather than at "now", so timestamps do not drift between runs. */
    public static final Instant EPOCH_START = Instant.parse("2026-01-15T09:00:00Z");

    private static final String[] LEVELS = {"INFO", "INFO", "INFO", "DEBUG", "WARN", "ERROR"};

    private static final String[] SOURCES = {
            "edge-gateway-01", "edge-gateway-02", "sensor-hub-a", "sensor-hub-b", "relay-node-07"
    };

    /** Near-identical routine traffic: low Shannon entropy over a short byte window. */
    private static final String[] ROUTINE_MESSAGES = {
            "heartbeat ok",
            "heartbeat ok, queue empty",
            "sensor read ok",
            "sensor read ok, value nominal",
            "link up, no retransmit",
            "battery level nominal"
    };

    /** Payloads carrying encoded identifiers: a much flatter byte distribution. */
    private static final String[] ANOMALY_TEMPLATES = {
            "checksum mismatch on frame %s",
            "tls handshake failed, session %s",
            "unexpected opcode, trace %s",
            "flash write error at block %s"
    };

    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private SyntheticLogGenerator() {
        // Static factory; never instantiated.
    }

    /** Generates {@code count} entries with the default seed. */
    public static List<LogEntry> generate(int count) {
        return generate(count, DEFAULT_SEED);
    }

    /**
     * Generates {@code count} entries, ids running 1..count and timestamps strictly increasing.
     *
     * <p>Timestamps must be non-decreasing because {@code TimeWindowChunking} assumes entries
     * arrive in time order — an out-of-order stream would produce windows that overlap, and
     * chunks are meant to be a clean partition.
     *
     * @param count how many entries; must not be negative
     * @param seed  RNG seed; the same seed always yields the same stream
     */
    public static List<LogEntry> generate(int count, long seed) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0, was " + count);
        }

        Random random = new Random(seed);
        List<LogEntry> entries = new ArrayList<>(count);
        Instant clock = EPOCH_START;

        for (int i = 0; i < count; i++) {
            // Bursty arrivals: mostly sub-second, occasionally a long idle gap that pushes the
            // stream into a new time window.
            clock = clock.plus(random.nextInt(100) < 12
                    ? Duration.ofSeconds(20 + random.nextInt(70))
                    : Duration.ofMillis(50 + random.nextInt(900)));

            String level = LEVELS[random.nextInt(LEVELS.length)];
            String source = SOURCES[random.nextInt(SOURCES.length)];
            boolean anomaly = "ERROR".equals(level) || "WARN".equals(level);

            String message = anomaly
                    ? ANOMALY_TEMPLATES[random.nextInt(ANOMALY_TEMPLATES.length)]
                            .formatted(randomToken(random, 24))
                    : ROUTINE_MESSAGES[random.nextInt(ROUTINE_MESSAGES.length)];

            entries.add(new LogEntry(i + 1L, clock, level, source, message));
        }
        return List.copyOf(entries);
    }

    /** A high-entropy identifier — the thing that makes an anomaly payload look random. */
    private static String randomToken(Random random, int length) {
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return token.toString();
    }
}
