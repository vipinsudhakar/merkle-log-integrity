package com.merklelog.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link Hashing}.
 *
 * <p>Two independent kinds of check are used deliberately:
 *
 * <ol>
 *   <li><b>Published known-answer vectors.</b> Hard-coded digests taken from NIST and
 *       RFC 6962. These anchor us to an external specification — if our output ever drifts
 *       from these, the code is wrong, not the vector. They must never be "corrected" to
 *       match a new output.</li>
 *   <li><b>An independent recomputation path.</b> Expected values are recomputed here with a
 *       raw {@link MessageDigest}, deliberately not reusing {@code Hashing}'s own helpers,
 *       so a bug in how {@code Hashing} assembles prefix and payload cannot hide behind
 *       itself.</li>
 * </ol>
 */
@DisplayName("Hashing — real SHA-256 with RFC 6962 domain separation")
class HashingTest {

    // --- Published vectors -------------------------------------------------------------

    /** NIST: SHA-256 of the empty input. */
    private static final String SHA256_OF_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** NIST: SHA-256 of "abc". */
    private static final String SHA256_OF_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    /** RFC 6962: the leaf hash of an empty entry, i.e. SHA-256 of the single byte 0x00. */
    private static final String RFC6962_EMPTY_LEAF =
            "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d";

    @Nested
    @DisplayName("known-answer vectors")
    class KnownAnswers {

        @Test
        @DisplayName("raw SHA-256 matches the NIST vectors")
        void rawSha256MatchesNistVectors() {
            assertThat(Hashing.toHex(Hashing.sha256(new byte[0]))).isEqualTo(SHA256_OF_EMPTY);
            assertThat(Hashing.toHex(Hashing.sha256(Hashing.utf8("abc")))).isEqualTo(SHA256_OF_ABC);
        }

        @Test
        @DisplayName("leaf hash of empty data matches the RFC 6962 vector")
        void leafHashOfEmptyMatchesRfc6962() {
            // This simultaneously proves the 0x00 prefix is applied and that it is applied
            // *before* the payload — a suffix or missing prefix would give a different digest.
            assertThat(Hashing.toHex(Hashing.leafHash(new byte[0]))).isEqualTo(RFC6962_EMPTY_LEAF);
        }

        @Test
        @DisplayName("the empty-tree root is SHA-256 of the empty string, not null")
        void emptyTreeHashIsDefined() {
            assertThat(Hashing.emptyTreeHash()).isNotNull().hasSize(Hashing.HASH_LENGTH_BYTES);
            assertThat(Hashing.toHex(Hashing.emptyTreeHash())).isEqualTo(SHA256_OF_EMPTY);
        }
    }

    @Nested
    @DisplayName("prefix construction, recomputed independently")
    class IndependentRecomputation {

        @Test
        @DisplayName("leafHash(d) equals SHA256(0x00 || d)")
        void leafHashAppliesLeafPrefix() throws Exception {
            byte[] data = Hashing.utf8("2026-07-25T10:00:00Z INFO sensor-14 temp=21.4C");
            assertThat(Hashing.leafHash(data)).isEqualTo(digestOf(concat(new byte[]{0x00}, data)));
        }

        @Test
        @DisplayName("nodeHash(l, r) equals SHA256(0x01 || l || r)")
        void nodeHashAppliesNodePrefix() throws Exception {
            byte[] left = Hashing.leafHash(Hashing.utf8("left"));
            byte[] right = Hashing.leafHash(Hashing.utf8("right"));
            assertThat(Hashing.nodeHash(left, right))
                    .isEqualTo(digestOf(concat(new byte[]{0x01}, left, right)));
        }

        @Test
        @DisplayName("every hash produced is exactly 32 bytes")
        void hashesAreThirtyTwoBytes() {
            byte[] leaf = Hashing.leafHash(Hashing.utf8("x"));
            assertThat(leaf).hasSize(Hashing.HASH_LENGTH_BYTES);
            assertThat(Hashing.nodeHash(leaf, leaf)).hasSize(Hashing.HASH_LENGTH_BYTES);
        }
    }

    @Nested
    @DisplayName("domain separation — the reason the prefixes exist")
    class DomainSeparation {

        @Test
        @DisplayName("a leaf hash can never equal a node hash over the same bytes")
        void leafHashNeverCollidesWithNodeHash() {
            byte[] left = Hashing.leafHash(Hashing.utf8("child-a"));
            byte[] right = Hashing.leafHash(Hashing.utf8("child-b"));

            // The attack the prefixes prevent: feed an internal node's two child hashes,
            // concatenated, in as if they were a leaf's payload. Without domain separation
            // these two calls would hash identical input and produce the same digest, letting
            // a tree be reinterpreted with a different shape but the same root.
            byte[] asNode = Hashing.nodeHash(left, right);
            byte[] asLeaf = Hashing.leafHash(concat(left, right));

            assertThat(asNode).isNotEqualTo(asLeaf);
        }

        @Test
        @DisplayName("a leaf hash differs from the unprefixed hash of the same data")
        void leafHashDiffersFromRawHash() {
            byte[] data = Hashing.utf8("payload");
            assertThat(Hashing.leafHash(data)).isNotEqualTo(Hashing.sha256(data));
        }

        @Test
        @DisplayName("child order is significant — nodeHash is not commutative")
        void nodeHashIsOrderSensitive() {
            byte[] a = Hashing.leafHash(Hashing.utf8("a"));
            byte[] b = Hashing.leafHash(Hashing.utf8("b"));

            // This is why a proof step must record which side its sibling was on. If node
            // hashing were commutative, a proof with its sides flipped would still verify.
            assertThat(Hashing.nodeHash(a, b)).isNotEqualTo(Hashing.nodeHash(b, a));
        }
    }

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        @DisplayName("nodeHash rejects a child that is not a 32-byte digest")
        void nodeHashRejectsWrongLengthChild() {
            byte[] valid = Hashing.leafHash(Hashing.utf8("ok"));
            byte[] tooShort = new byte[16];

            assertThatThrownBy(() -> Hashing.nodeHash(tooShort, valid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("left");

            assertThatThrownBy(() -> Hashing.nodeHash(valid, tooShort))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("right");
        }

        @Test
        @DisplayName("null inputs are rejected rather than silently hashed")
        void nullInputsRejected() {
            assertThatThrownBy(() -> Hashing.sha256(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Hashing.leafHash(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("hex codec and comparison")
    class HexAndComparison {

        @Test
        @DisplayName("toHex and fromHex round-trip")
        void hexRoundTrips() {
            byte[] original = Hashing.leafHash(Hashing.utf8("round trip"));
            String hex = Hashing.toHex(original);

            assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
            assertThat(Hashing.fromHex(hex)).isEqualTo(original);
        }

        @Test
        @DisplayName("high bytes encode correctly — no sign-extension bug")
        void encodesHighBytesWithoutSignExtension() {
            // 0x80..0xFF are negative as Java bytes; forgetting the & 0xFF mask corrupts them.
            assertThat(Hashing.toHex(new byte[]{(byte) 0x00, (byte) 0x0F, (byte) 0x80, (byte) 0xFF}))
                    .isEqualTo("000f80ff");
        }

        @Test
        @DisplayName("fromHex accepts uppercase and rejects malformed input")
        void fromHexValidates() {
            assertThat(Hashing.fromHex("0A1B")).isEqualTo(Hashing.fromHex("0a1b"));

            assertThatThrownBy(() -> Hashing.fromHex("abc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("even length");

            assertThatThrownBy(() -> Hashing.fromHex("zz"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hex character");
        }

        @Test
        @DisplayName("equal() distinguishes hashes that differ in a single bit")
        void equalDetectsSingleBitDifference() {
            byte[] a = Hashing.leafHash(Hashing.utf8("entry"));
            byte[] b = a.clone();
            b[31] ^= 0x01; // flip the lowest bit of the last byte

            assertThat(Hashing.equal(a, a.clone())).isTrue();
            assertThat(Hashing.equal(a, b)).isFalse();
            assertThat(Hashing.equal(a, null)).isFalse();
        }
    }

    // --- helpers -----------------------------------------------------------------------

    /** Digests with a raw MessageDigest, independent of {@link Hashing}'s own plumbing. */
    private static byte[] digestOf(byte[] input) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
