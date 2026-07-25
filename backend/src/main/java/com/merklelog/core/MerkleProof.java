package com.merklelog.core;

import java.util.List;
import java.util.Objects;

/**
 * A Merkle inclusion proof: the sibling hashes needed to recompute the root from one leaf.
 *
 * <p>This is the object that makes the whole system worthwhile. To check that a single log
 * entry is intact, a verifier needs this proof and the trusted root — not the other n-1
 * entries. That is the difference between O(log n) verification and re-hashing the dataset.
 *
 * <h2>Size</h2>
 *
 * <p>A proof carries at most {@code ceil(log2 n)} sibling hashes of 32 bytes each, so for a
 * 1,000-entry tree it is about 320 bytes, and for a million entries about 640. Doubling the
 * dataset adds one hash. {@link #sizeInBytes()} is what the Phase 7 dashboard plots against
 * the base paper's ~1006-byte figure.
 *
 * <p>Proofs for leaves near the right edge of an unbalanced tree can be <em>shorter</em> than
 * {@code ceil(log2 n)}, because a promoted node contributes no step at its level.
 *
 * @param leafIndex position of the proven leaf in the original entry list
 * @param steps     sibling hashes ordered from the leaf upward to the root
 * @param leafCount number of leaves in the tree this proof came from, for context and display
 */
public record MerkleProof(int leafIndex, List<ProofStep> steps, int leafCount) {

    public MerkleProof {
        Objects.requireNonNull(steps, "steps");
        if (leafIndex < 0) {
            throw new IllegalArgumentException("leafIndex must be non-negative, got " + leafIndex);
        }
        if (leafCount < 0) {
            throw new IllegalArgumentException("leafCount must be non-negative, got " + leafCount);
        }
        steps = List.copyOf(steps); // immutable snapshot — a proof must not change after issue
    }

    /**
     * Number of sibling hashes in this proof, i.e. how many hash operations verification costs.
     *
     * <p>An empty proof (length 0) is the correct, non-degenerate result for a single-leaf
     * tree: the leaf hash <em>is</em> the root, so no siblings are needed.
     */
    public int length() {
        return steps.size();
    }

    /** Wire size of the proof: one 32-byte hash per step. The metric Phase 7 charts. */
    public int sizeInBytes() {
        return steps.size() * Hashing.HASH_LENGTH_BYTES;
    }

    @Override
    public String toString() {
        return "MerkleProof{leaf=" + leafIndex + "/" + leafCount
                + ", steps=" + steps.size() + ", bytes=" + sizeInBytes() + "}";
    }
}
