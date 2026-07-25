package com.merklelog.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LogEntry}, focused almost entirely on canonical serialisation.
 *
 * <p>This is where a subtle and serious bug could hide: if two different entries can produce
 * the same bytes, they produce the same leaf hash, and one can be substituted for the other
 * without changing the root — a forgery requiring no cryptographic break at all.
 */
@DisplayName("LogEntry — canonical serialisation")
class LogEntryTest {

    private static final Instant T = Instant.parse("2026-07-25T10:00:00Z");

    @Test
    @DisplayName("field boundaries are unambiguous — no separator-shifting collision")
    void fieldBoundariesCannotBeShifted() {
        // The classic delimiter bug: with fields joined by a separator, moving the separator
        // between two adjacent fields yields identical bytes. Length prefixing makes the two
        // encodings differ, because the recorded lengths differ.
        LogEntry a = new LogEntry(1, T, "INFO", "device", "alpha|beta");
        LogEntry b = new LogEntry(1, T, "INFO", "device|alpha", "beta");

        assertThat(a.canonicalBytes()).isNotEqualTo(b.canonicalBytes());
        assertThat(a.leafHash()).isNotEqualTo(b.leafHash());
    }

    @Test
    @DisplayName("an empty field is distinguishable from an absent boundary")
    void emptyFieldsAreDistinct() {
        LogEntry emptySource = new LogEntry(1, T, "INFO", "", "payload");
        LogEntry emptyMessage = new LogEntry(1, T, "INFO", "payload", "");

        assertThat(emptySource.canonicalBytes()).isNotEqualTo(emptyMessage.canonicalBytes());
    }

    @Test
    @DisplayName("every field participates in the hash")
    void everyFieldAffectsTheHash() {
        LogEntry base = new LogEntry(1, T, "INFO", "device", "payload");

        assertThat(new LogEntry(2, T, "INFO", "device", "payload").leafHash())
                .as("id").isNotEqualTo(base.leafHash());
        assertThat(new LogEntry(1, T.plusMillis(1), "INFO", "device", "payload").leafHash())
                .as("timestamp").isNotEqualTo(base.leafHash());
        assertThat(new LogEntry(1, T, "WARN", "device", "payload").leafHash())
                .as("level").isNotEqualTo(base.leafHash());
        assertThat(new LogEntry(1, T, "INFO", "other", "payload").leafHash())
                .as("source").isNotEqualTo(base.leafHash());
        assertThat(new LogEntry(1, T, "INFO", "device", "changed").leafHash())
                .as("message").isNotEqualTo(base.leafHash());
    }

    @Test
    @DisplayName("serialisation does not depend on the JVM's default time zone")
    void serialisationIsTimeZoneIndependent() {
        // The same data must hash identically on a teammate's laptop and on Render. Encoding
        // the timestamp as epoch millis rather than a formatted string is what guarantees it.
        LogEntry entry = new LogEntry(1, T, "INFO", "device", "payload");

        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Kolkata")));
            byte[] inKolkata = entry.canonicalBytes();

            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/Los_Angeles")));
            byte[] inLosAngeles = entry.canonicalBytes();

            assertThat(inKolkata).isEqualTo(inLosAngeles);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("non-ASCII payloads round-trip through UTF-8 without collision")
    void handlesNonAsciiPayloads() {
        LogEntry ascii = new LogEntry(1, T, "INFO", "device", "temperature");
        LogEntry unicode = new LogEntry(1, T, "INFO", "device", "sıcaklık 21.4°C");

        assertThat(unicode.canonicalBytes()).isNotEqualTo(ascii.canonicalBytes());
        // 15 characters, but more than 15 bytes in UTF-8 — the length prefix records bytes.
        assertThat(unicode.canonicalBytes().length).isGreaterThan(ascii.canonicalBytes().length);
    }

    @Test
    @DisplayName("serialisation is deterministic across repeated calls")
    void serialisationIsDeterministic() {
        LogEntry entry = new LogEntry(1, T, "INFO", "device", "payload");

        assertThat(entry.canonicalBytes()).isEqualTo(entry.canonicalBytes());
        assertThat(entry.leafHash()).isEqualTo(entry.leafHash());
    }

    @Test
    @DisplayName("withMessage produces a new entry and leaves the original untouched")
    void withMessageIsNonMutating() {
        LogEntry original = new LogEntry(1, T, "INFO", "device", "original");
        byte[] originalHash = original.leafHash();

        LogEntry tampered = original.withMessage("tampered");

        assertThat(original.message()).isEqualTo("original");
        assertThat(original.leafHash()).isEqualTo(originalHash);
        assertThat(tampered.leafHash()).isNotEqualTo(originalHash);
        assertThat(tampered.id()).isEqualTo(original.id());
        assertThat(tampered.timestamp()).isEqualTo(original.timestamp());
    }

    @Test
    @DisplayName("null fields are rejected at construction")
    void nullFieldsRejected() {
        assertThatThrownBy(() -> new LogEntry(1, null, "INFO", "d", "m"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LogEntry(1, T, null, "d", "m"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LogEntry(1, T, "INFO", "d", null))
                .isInstanceOf(NullPointerException.class);
    }
}
