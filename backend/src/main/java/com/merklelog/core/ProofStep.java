package com.merklelog.core;

import java.util.Objects;

/**
 * One rung of an inclusion proof: a sibling hash, and which side of the pair it sat on.
 *
 * <h2>Why the side must be recorded</h2>
 *
 * <p>Node hashing is not commutative — {@code SHA256(0x01 || l || r)} differs from
 * {@code SHA256(0x01 || r || l)}. A verifier therefore cannot simply combine the running
 * hash with the sibling; it has to know the order. If the side were dropped and the verifier
 * guessed (say, by sorting the two hashes), a proof with its steps rearranged could still
 * verify, and the proof would no longer pin the leaf to a specific <em>position</em> in the
 * tree — only to membership. Recording the side keeps position part of what is proven.
 *
 * @param siblingHash the 32-byte hash of the sibling node at this level
 * @param side        which side the sibling is on, relative to the running hash
 */
public record ProofStep(byte[] siblingHash, Side side) {

    /** Which side of the pair the sibling occupies. */
    public enum Side {
        /** Sibling is the left child: parent = nodeHash(sibling, running). */
        LEFT,
        /** Sibling is the right child: parent = nodeHash(running, sibling). */
        RIGHT
    }

    public ProofStep {
        Objects.requireNonNull(siblingHash, "siblingHash");
        Objects.requireNonNull(side, "side");
        if (siblingHash.length != Hashing.HASH_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "siblingHash must be " + Hashing.HASH_LENGTH_BYTES + " bytes, got " + siblingHash.length);
        }
        siblingHash = siblingHash.clone(); // records expose their components; copy so we stay immutable
    }

    /** A defensive copy of the sibling hash. */
    @Override
    public byte[] siblingHash() {
        return siblingHash.clone();
    }

    /** The sibling hash as lowercase hex, for display and JSON. */
    public String siblingHashHex() {
        return Hashing.toHex(siblingHash);
    }

    /**
     * Combines the running hash with this step's sibling, in the correct order.
     *
     * <p>This single method is where side information is actually honoured, so both the
     * verifier and the Phase 5 step-through UI go through it rather than re-deriving the
     * ordering rule and risking them drifting apart.
     */
    public byte[] combine(byte[] runningHash) {
        return side == Side.LEFT
                ? Hashing.nodeHash(siblingHash, runningHash)
                : Hashing.nodeHash(runningHash, siblingHash);
    }

    @Override
    public String toString() {
        return side + " " + siblingHashHex().substring(0, 12) + "…";
    }
}
