package com.merklelog.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * The single entry point for every hash this project computes.
 *
 * <p>All hashing is real SHA-256 from {@link MessageDigest}. There is deliberately no
 * "fast mode", no mock, and no pluggable digest: a tamper-evidence system whose hash can
 * be swapped for a cheap stand-in proves nothing, and the whole point of the project is
 * that the integrity guarantee is genuine.
 *
 * <h2>Domain separation (RFC 6962)</h2>
 *
 * <p>Leaves and internal nodes are hashed with different one-byte prefixes:
 *
 * <pre>
 *   leafHash(d)   = SHA256(0x00 || d)
 *   nodeHash(l,r) = SHA256(0x01 || l || r)
 * </pre>
 *
 * <p>Without these prefixes an attacker could take an internal node's two child hashes,
 * present their 64-byte concatenation as if it were a <em>leaf's</em> data, and obtain a
 * value that hashes identically. The tree could then be reinterpreted with a different
 * shape but the same root — a second-preimage attack. Tagging the two cases makes the
 * inputs to the two hash calls disjoint, so a leaf hash can never collide with a node
 * hash by construction. This is the scheme RFC 6962 (Certificate Transparency) specifies.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link MessageDigest} instances are stateful and <em>not</em> thread-safe, so a fresh
 * instance is obtained per call rather than cached in a field. This costs a little
 * allocation but keeps every method here safe to call concurrently, which matters once
 * the benchmark harness in Phase 7 runs parallel workloads.
 */
public final class Hashing {

    /** Prefix byte marking a leaf hash input. See the class-level note on domain separation. */
    public static final byte LEAF_PREFIX = 0x00;

    /** Prefix byte marking an internal node hash input. See the class-level note on domain separation. */
    public static final byte NODE_PREFIX = 0x01;

    /** Length of a SHA-256 digest in bytes. Every hash this class returns is exactly this long. */
    public static final int HASH_LENGTH_BYTES = 32;

    private static final String ALGORITHM = "SHA-256";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private Hashing() {
        // Static utility class; never instantiated.
    }

    /**
     * Raw SHA-256 of the given bytes, with no prefix applied.
     *
     * <p>Prefer {@link #leafHash(byte[])} and {@link #nodeHash(byte[], byte[])} for anything
     * that goes into a Merkle tree — this method is for the few cases that are genuinely
     * outside the tree structure, such as {@link #emptyTreeHash()}.
     */
    public static byte[] sha256(byte[] data) {
        Objects.requireNonNull(data, "data");
        return newDigest().digest(data);
    }

    /**
     * Hash of a leaf carrying {@code data}: {@code SHA256(0x00 || data)}.
     *
     * @param data the serialised log entry bytes
     * @return a 32-byte digest
     */
    public static byte[] leafHash(byte[] data) {
        Objects.requireNonNull(data, "data");
        MessageDigest digest = newDigest();
        digest.update(LEAF_PREFIX);
        digest.update(data);
        return digest.digest();
    }

    /**
     * Hash of an internal node with the given children: {@code SHA256(0x01 || left || right)}.
     *
     * <p>Order matters. {@code nodeHash(a, b)} and {@code nodeHash(b, a)} are different values,
     * which is exactly why an inclusion proof has to record whether each sibling sat on the
     * left or the right — see {@code ProofStep}. A verifier that ignored side information
     * could be fooled by a reordered proof.
     *
     * @throws IllegalArgumentException if either child is not a 32-byte digest
     */
    public static byte[] nodeHash(byte[] left, byte[] right) {
        requireHash(left, "left");
        requireHash(right, "right");
        MessageDigest digest = newDigest();
        digest.update(NODE_PREFIX);
        digest.update(left);
        digest.update(right);
        return digest.digest();
    }

    /**
     * The root hash of a tree with no leaves at all: {@code SHA256("")}.
     *
     * <p>An empty tree needs <em>some</em> defined root. Returning {@code null} would push a
     * null check into every caller, and throwing would make "no logs yet" an error state
     * rather than an ordinary one. RFC 6962 defines the empty Merkle tree hash as the hash
     * of the empty string, so we follow it rather than inventing a sentinel.
     */
    public static byte[] emptyTreeHash() {
        return sha256(new byte[0]);
    }

    /**
     * Compares two hashes for equality in constant time.
     *
     * <p>Delegates to {@link MessageDigest#isEqual}, which does not short-circuit on the first
     * differing byte. An ordinary {@code Arrays.equals} returns as soon as it finds a
     * mismatch, so how long a comparison takes leaks how many leading bytes were correct —
     * enough, in principle, for an attacker to hunt for a matching root one byte at a time.
     * Verification is the one place in this codebase where that matters.
     */
    public static boolean equal(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    /** Encodes a digest as lowercase hex. Used at the API boundary and in test assertions. */
    public static String toHex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;          // promote to unsigned before shifting
            out[i * 2] = HEX_DIGITS[v >>> 4];
            out[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * Decodes a lowercase or uppercase hex string back into bytes.
     *
     * @throws IllegalArgumentException if the string has odd length or contains a non-hex character
     */
    public static byte[] fromHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length, got " + hex.length());
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = hexValue(hex.charAt(i * 2));
            int lo = hexValue(hex.charAt(i * 2 + 1));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Convenience for tests and seed data: UTF-8 bytes of a string. */
    public static byte[] utf8(String value) {
        Objects.requireNonNull(value, "value");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("Not a hex character: '" + c + "'");
    }

    private static void requireHash(byte[] hash, String name) {
        Objects.requireNonNull(hash, name);
        if (hash.length != HASH_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    name + " must be a " + HASH_LENGTH_BYTES + "-byte SHA-256 digest, got " + hash.length);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform specification, so this cannot happen
            // on a valid JRE. Wrapping it keeps the checked exception out of every signature.
            throw new IllegalStateException(ALGORITHM + " is required but unavailable in this JVM", e);
        }
    }
}
