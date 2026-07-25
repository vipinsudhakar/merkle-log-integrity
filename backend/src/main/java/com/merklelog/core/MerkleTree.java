package com.merklelog.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable Merkle tree over a list of log entries.
 *
 * <h2>Storage layout — and why it is an array of levels</h2>
 *
 * <p>The tree is held as a list of levels. {@code levels.get(0)} is the leaf hashes in entry
 * order; each subsequent level is built by hashing adjacent pairs of the level below; the
 * final level holds exactly one hash, the root.
 *
 * <pre>
 *   level 2:            H(H(a,b), c)          <- root
 *   level 1:      H(a,b)          c           <- c promoted, not duplicated
 *   level 0:    a       b         c           <- leaf hashes
 * </pre>
 *
 * <p>This layout is chosen specifically so that finding a node's sibling is <em>arithmetic</em>
 * rather than a search: the sibling of index {@code i} is {@code i ^ 1}, and the parent is at
 * {@code i >> 1} on the next level up. A pointer-linked tree would need a search or a
 * parent-pointer walk to do the same job. That distinction is the whole O(log n) claim — if
 * proof generation ever had to scan the leaf list, the system would be no better than the
 * O(n) re-hashing it is meant to replace.
 *
 * <h2>Odd nodes are promoted, never duplicated</h2>
 *
 * <p>When a level has an odd number of nodes, the last one is carried up to the next level
 * unchanged. The better-known alternative — hashing the last node with itself — is what
 * Bitcoin does, and it is the source of CVE-2012-2459: because {@code H(x, x)} is reachable
 * two different ways, distinct leaf lists can produce the same root, so a root no longer
 * uniquely commits to its leaves. Promotion has no such ambiguity.
 *
 * <p>One consequence worth knowing: a promoted node contributes <em>no</em> proof step at
 * that level, so proofs for leaves near the right edge can be shorter than
 * {@code ceil(log2 n)}. The bound is {@code proofLength <= ceil(log2 n)}, not equality —
 * still O(log n), which is what matters.
 */
public final class MerkleTree {

    /**
     * levels.get(0) = leaf hashes, last level = the single root.
     * Empty when the tree has no leaves at all.
     */
    private final List<byte[][]> levels;

    private MerkleTree(List<byte[][]> levels) {
        this.levels = levels;
    }

    /** Builds a tree over the given entries, hashing each into a leaf in list order. */
    public static MerkleTree fromEntries(List<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<byte[]> leafHashes = new ArrayList<>(entries.size());
        for (LogEntry entry : entries) {
            leafHashes.add(entry.leafHash());
        }
        return fromLeafHashes(leafHashes);
    }

    /**
     * Builds a tree over pre-computed leaf hashes.
     *
     * <p>Used by {@code MerkleForest} to build the level above the chunk roots, where the
     * "leaves" are themselves roots rather than entries.
     *
     * @throws IllegalArgumentException if any hash is not a 32-byte digest
     */
    public static MerkleTree fromLeafHashes(List<byte[]> leafHashes) {
        Objects.requireNonNull(leafHashes, "leafHashes");

        if (leafHashes.isEmpty()) {
            // An empty tree is a legitimate state, not an error. root() reports the RFC 6962
            // empty-tree hash; see Hashing.emptyTreeHash().
            return new MerkleTree(List.of());
        }

        byte[][] current = new byte[leafHashes.size()][];
        for (int i = 0; i < leafHashes.size(); i++) {
            byte[] hash = leafHashes.get(i);
            Objects.requireNonNull(hash, "leaf hash at index " + i);
            if (hash.length != Hashing.HASH_LENGTH_BYTES) {
                throw new IllegalArgumentException(
                        "Leaf hash at index " + i + " must be " + Hashing.HASH_LENGTH_BYTES
                                + " bytes, got " + hash.length);
            }
            current[i] = hash.clone(); // defensive: the tree must not alias caller-owned arrays
        }

        List<byte[][]> levels = new ArrayList<>();
        levels.add(current);

        // Build upward one level at a time until a single hash remains.
        // A level of size n produces one of size ceil(n / 2), so this always terminates.
        while (current.length > 1) {
            byte[][] parent = new byte[(current.length + 1) / 2][];
            for (int i = 0; i < current.length; i += 2) {
                parent[i / 2] = (i + 1 < current.length)
                        ? Hashing.nodeHash(current[i], current[i + 1])
                        : current[i]; // odd node out: promote unchanged (see class javadoc)
            }
            levels.add(parent);
            current = parent;
        }

        return new MerkleTree(levels);
    }

    /**
     * The root hash committing to every leaf.
     *
     * <p>For an empty tree this is {@link Hashing#emptyTreeHash()} rather than {@code null},
     * so callers never have to null-check a root.
     */
    public byte[] root() {
        if (isEmpty()) {
            return Hashing.emptyTreeHash();
        }
        return levels.get(levels.size() - 1)[0].clone();
    }

    /** The root as lowercase hex. */
    public String rootHex() {
        return Hashing.toHex(root());
    }

    public boolean isEmpty() {
        return levels.isEmpty();
    }

    /** Number of leaves, i.e. number of entries this tree commits to. */
    public int leafCount() {
        return isEmpty() ? 0 : levels.get(0).length;
    }

    /**
     * Number of levels above the leaves. A single-leaf tree has depth 0; an empty tree, 0.
     *
     * <p>This is an upper bound on any proof length in this tree.
     */
    public int depth() {
        return isEmpty() ? 0 : levels.size() - 1;
    }

    /** The leaf hash at the given position. */
    public byte[] leafHash(int leafIndex) {
        requireLeafIndex(leafIndex);
        return levels.get(0)[leafIndex].clone();
    }

    /**
     * Generates an inclusion proof for the leaf at {@code leafIndex}.
     *
     * <h2>Why this is O(log n)</h2>
     *
     * <p>The loop runs once per level — {@code depth()} iterations, i.e. {@code ceil(log2 n)} —
     * and every operation inside it is constant time:
     *
     * <ul>
     *   <li>the sibling's position is {@code index ^ 1}: flipping the low bit turns an even
     *       index into its right-hand partner and an odd index into its left-hand one;</li>
     *   <li>the parent's position is {@code index >> 1};</li>
     *   <li>reading a hash is an array index, not a search.</li>
     * </ul>
     *
     * <p>There is no scan of the leaf list anywhere. That matters: an implementation that
     * looked up siblings by searching would still return correct proofs while quietly costing
     * O(n) per proof, which is exactly the cost this project exists to avoid.
     *
     * <p>When a level has an odd size, the last node has no sibling — it was promoted — so no
     * step is emitted for that level, and the proof comes out shorter than the depth.
     *
     * @throws IndexOutOfBoundsException if the index is out of range, or the tree is empty
     */
    public MerkleProof generateProof(int leafIndex) {
        requireLeafIndex(leafIndex);

        List<ProofStep> steps = new ArrayList<>(depth());
        int index = leafIndex;

        for (int level = 0; level < levels.size() - 1; level++) {
            int siblingIndex = index ^ 1;

            if (siblingIndex < levelSize(level)) {
                // A sibling exists. It is on the right when our own index is even.
                ProofStep.Side side = (siblingIndex > index) ? ProofStep.Side.RIGHT : ProofStep.Side.LEFT;
                steps.add(new ProofStep(hashAt(level, siblingIndex), side));
            }
            // else: this node was promoted to the level above unchanged, so there is nothing
            // to combine with here and the proof simply has no step for this level.

            index >>= 1; // ascend to the parent
        }

        return new MerkleProof(leafIndex, steps, leafCount());
    }

    /**
     * Read-only access to the level structure, bottom-up, for visualisation and tests.
     * Copies defensively — the internal arrays must stay immutable.
     */
    public List<List<byte[]>> levels() {
        List<List<byte[]>> copy = new ArrayList<>(levels.size());
        for (byte[][] level : levels) {
            List<byte[]> row = new ArrayList<>(level.length);
            for (byte[] hash : level) {
                row.add(hash.clone());
            }
            copy.add(Collections.unmodifiableList(row));
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Builds an explicit pointer-linked view of this tree for the frontend to draw.
     *
     * <p><b>O(n) — never call this from a proof path.</b> It exists only for visualisation.
     *
     * @return the root node, or {@code null} for an empty tree
     */
    public MerkleNode toNodeTree() {
        if (isEmpty()) {
            return null;
        }
        return buildNode(levels.size() - 1, 0);
    }

    /**
     * Recursively materialises the subtree rooted at {@code (level, index)}.
     *
     * <p>A promoted node has only a left child at the level below, and its hash equals that
     * child's hash. Rather than emit a redundant one-child node, we return the child's
     * subtree directly — so the drawn tree shows a promoted leaf reaching up to where it was
     * actually combined, which is what the structure really is.
     */
    private MerkleNode buildNode(int level, int index) {
        if (level == 0) {
            return MerkleNode.leaf(levels.get(0)[index], index);
        }
        byte[][] below = levels.get(level - 1);
        int leftChild = index * 2;
        int rightChild = leftChild + 1;

        if (rightChild >= below.length) {
            return buildNode(level - 1, leftChild); // promoted: collapse the redundant node
        }
        return MerkleNode.internal(buildNode(level - 1, leftChild), buildNode(level - 1, rightChild));
    }

    /** Package-private raw access for {@link MerkleTree}'s proof generation. Never copies. */
    byte[] hashAt(int level, int index) {
        return levels.get(level)[index];
    }

    /** Package-private: number of nodes at a level, used when walking a proof path. */
    int levelSize(int level) {
        return levels.get(level).length;
    }

    /** Package-private: total number of levels, leaves included. */
    int levelCount() {
        return levels.size();
    }

    void requireLeafIndex(int leafIndex) {
        if (isEmpty()) {
            throw new IndexOutOfBoundsException("Tree has no leaves");
        }
        if (leafIndex < 0 || leafIndex >= leafCount()) {
            throw new IndexOutOfBoundsException(
                    "Leaf index " + leafIndex + " out of range [0, " + leafCount() + ")");
        }
    }

    @Override
    public String toString() {
        return "MerkleTree{leaves=" + leafCount() + ", depth=" + depth()
                + ", root=" + (isEmpty() ? "<empty>" : rootHex().substring(0, 12) + "…") + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MerkleTree other)) return false;
        // Two trees are equal exactly when they commit to the same leaves, which the root
        // captures — that is precisely the property a Merkle root is for.
        return Arrays.equals(root(), other.root()) && leafCount() == other.leafCount();
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(root());
    }
}
