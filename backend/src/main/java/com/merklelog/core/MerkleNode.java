package com.merklelog.core;

import java.util.Objects;

/**
 * A node in an explicit, pointer-linked view of a Merkle tree.
 *
 * <h2>Why this exists separately from {@link MerkleTree}</h2>
 *
 * <p>{@code MerkleTree} stores the tree as an array of levels, because that layout is what
 * makes proof generation O(log n): a sibling is found by index arithmetic, never by search.
 * That layout is however awkward to draw.
 *
 * <p>This class is the other view — built on demand by {@link MerkleTree#toNodeTree()} —
 * and exists purely so the Phase 4 frontend can walk parent-to-child links and lay the tree
 * out visually. It is deliberately <em>not</em> used on the proof path; building it is O(n),
 * and doing that inside a proof would silently destroy the complexity guarantee this whole
 * project is about.
 */
public final class MerkleNode {

    private final byte[] hash;
    private final MerkleNode left;
    private final MerkleNode right;
    private final int leafIndex;

    private MerkleNode(byte[] hash, MerkleNode left, MerkleNode right, int leafIndex) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.left = left;
        this.right = right;
        this.leafIndex = leafIndex;
    }

    /** Creates a leaf node carrying the position of its entry in the original list. */
    public static MerkleNode leaf(byte[] hash, int leafIndex) {
        if (leafIndex < 0) {
            throw new IllegalArgumentException("leafIndex must be non-negative, got " + leafIndex);
        }
        return new MerkleNode(hash, null, null, leafIndex);
    }

    /** Creates an internal node from two children, deriving its hash from them. */
    public static MerkleNode internal(MerkleNode left, MerkleNode right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return new MerkleNode(Hashing.nodeHash(left.hash(), right.hash()), left, right, -1);
    }

    /** A defensive copy — callers must not be able to mutate a node's hash in place. */
    public byte[] hash() {
        return hash.clone();
    }

    /** This node's hash as lowercase hex, for display and JSON. */
    public String hashHex() {
        return Hashing.toHex(hash);
    }

    /** Left child, or {@code null} if this is a leaf. */
    public MerkleNode left() {
        return left;
    }

    /** Right child, or {@code null} if this is a leaf. */
    public MerkleNode right() {
        return right;
    }

    public boolean isLeaf() {
        return left == null && right == null;
    }

    /** Index of this leaf in the original entry list, or {@code -1} for an internal node. */
    public int leafIndex() {
        return leafIndex;
    }

    /** Number of edges on the longest downward path from this node. A leaf has height 0. */
    public int height() {
        if (isLeaf()) {
            return 0;
        }
        return 1 + Math.max(left.height(), right.height());
    }

    @Override
    public String toString() {
        String kind = isLeaf() ? "leaf[" + leafIndex + "]" : "node";
        return kind + " " + hashHex().substring(0, 12) + "…";
    }
}
