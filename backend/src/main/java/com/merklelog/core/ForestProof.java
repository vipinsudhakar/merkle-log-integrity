package com.merklelog.core;

import java.util.Objects;

/**
 * A two-stage inclusion proof: from an entry up to its chunk root, then from that chunk root
 * up to the forest's super-root.
 *
 * <p>Both stages are needed because the forest is two levels of Merkle tree. The entry proof
 * shows the entry belongs to its chunk; the chunk proof shows that chunk belongs to the
 * published super-root. Verifying them in sequence links one log line to the single hash the
 * system publishes.
 *
 * <h2>Size</h2>
 *
 * <p>{@code ceil(log2(chunkSize)) + ceil(log2(chunkCount))} hashes. For a fixed dataset this
 * is minimised somewhere in the middle: very small chunks make the entry proof short but the
 * chunk proof long, and very large chunks do the reverse. Showing that trade-off across the
 * three strategies is what the Phase 6 comparison is for.
 *
 * @param globalIndex position of the entry across the whole dataset
 * @param chunkIndex  which chunk holds it
 * @param localIndex  its position within that chunk
 * @param entryProof  proof from the entry up to its chunk root
 * @param chunkProof  proof from the chunk root up to the super-root
 */
public record ForestProof(int globalIndex, int chunkIndex, int localIndex,
                          MerkleProof entryProof, MerkleProof chunkProof) {

    public ForestProof {
        Objects.requireNonNull(entryProof, "entryProof");
        Objects.requireNonNull(chunkProof, "chunkProof");
        if (globalIndex < 0 || chunkIndex < 0 || localIndex < 0) {
            throw new IllegalArgumentException("Indices must be non-negative");
        }
    }

    /** Total hash operations verification will cost — the O(log n) figure end to end. */
    public int totalSteps() {
        return entryProof.length() + chunkProof.length();
    }

    /** Total wire size of both stages. The metric Phase 7 plots against the paper's ~1006 bytes. */
    public int sizeInBytes() {
        return entryProof.sizeInBytes() + chunkProof.sizeInBytes();
    }

    @Override
    public String toString() {
        return "ForestProof{entry=" + globalIndex + " (chunk " + chunkIndex + ", local " + localIndex
                + "), steps=" + entryProof.length() + "+" + chunkProof.length()
                + ", bytes=" + sizeInBytes() + "}";
    }
}
