package com.merklelog.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * One immutable log record — the unit of data this system proves the integrity of.
 *
 * <p>Immutability is not incidental. "Tampering" in this project means replacing an entry
 * with a different one and rebuilding the affected tree, never mutating an object in place.
 * A mutable entry would let a caller change a payload behind a tree's back, leaving the
 * cached hashes silently describing data that no longer exists.
 *
 * @param id        stable identifier, unique within a dataset
 * @param timestamp when the event occurred (drives {@code TimeWindowChunking})
 * @param level     severity, e.g. INFO / WARN / ERROR
 * @param source    which device or service emitted it
 * @param message   the free-text payload
 */
public record LogEntry(long id, Instant timestamp, String level, String source, String message) {

    public LogEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
    }

    /**
     * The exact bytes that get hashed into this entry's Merkle leaf.
     *
     * <h2>Why length-prefixed rather than delimited</h2>
     *
     * <p>The obvious encoding — join the fields with a separator — is ambiguous, because a
     * field may itself contain the separator. Two <em>different</em> entries could then
     * serialise to identical bytes and therefore produce the same leaf hash:
     *
     * <pre>
     *   source="a", message="b|c"     ->  "a|b|c"
     *   source="a|b", message="c"     ->  "a|b|c"     // same bytes, different entry
     * </pre>
     *
     * <p>That is a collision an attacker can construct at will, with no need to break
     * SHA-256 — it would let one entry be swapped for another without changing the root.
     * Writing each field as a 4-byte big-endian length followed by its UTF-8 bytes makes the
     * encoding unambiguous: the boundaries are stated, not guessed, so distinct entries
     * always serialise to distinct bytes.
     *
     * <p>The timestamp is encoded as epoch-milliseconds rather than a formatted string so
     * the bytes cannot vary with locale or time zone. Canonicalisation has to be
     * deterministic across machines, or the same data would produce different roots on a
     * teammate's laptop and on Render.
     */
    public byte[] canonicalBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLong(out, id);
        writeLong(out, timestamp.toEpochMilli());
        writeField(out, level);
        writeField(out, source);
        writeField(out, message);
        return out.toByteArray();
    }

    /** This entry's Merkle leaf hash: {@code SHA256(0x00 || canonicalBytes())}. */
    public byte[] leafHash() {
        return Hashing.leafHash(canonicalBytes());
    }

    /** Returns a copy of this entry with a different message — how tampering is simulated. */
    public LogEntry withMessage(String newMessage) {
        return new LogEntry(id, timestamp, level, source, newMessage);
    }

    private static void writeField(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            out.write((value >>> shift) & 0xFF);
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xFF));
        }
    }
}
