package com.merklelog.core;

import com.merklelog.chunking.Chunk;
import com.merklelog.chunking.ChunkingStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A forest of per-chunk Merkle trees, sealed under a single super-root.
 *
 * <pre>
 *                        super-root
 *                    /       |       \
 *              root(C0)   root(C1)   root(C2)      <- one tree per chunk
 *               /   \       /   \       /   \
 *             ...   ...   ...   ...   ...   ...    <- entries
 * </pre>
 *
 * <h2>Why a forest rather than one global tree</h2>
 *
 * <p>This is the structural decision the whole comparison rests on. If every entry were a
 * leaf of one tree, the chunking strategy would affect almost nothing measurable: proof
 * length would be {@code log2(totalEntries)} regardless, and tampering would force a rebuild
 * proportional to the whole dataset.
 *
 * <p>Giving each chunk its own tree changes both:
 *
 * <ul>
 *   <li><b>Proof size</b> becomes {@code log2(chunkSize) + log2(chunkCount)}, so the strategy
 *       that chooses chunk sizes directly sets it.</li>
 *   <li><b>Rebuild cost</b> after tampering is proportional to <em>one chunk</em> plus the
 *       small super-tree, not to the dataset. Re-hashing 64 entries instead of a million is
 *       the practical payoff, and it is what makes this viable on an edge device — the
 *       setting the base paper targets.</li>
 * </ul>
 *
 * <h2>Degenerate cases, defined deliberately</h2>
 *
 * <ul>
 *   <li><b>No entries</b> — no chunks, and the super-root is {@link Hashing#emptyTreeHash()}.
 *       Never null. Requesting a proof throws {@link EmptyForestException}.</li>
 *   <li><b>One chunk</b> — the super-root <em>is</em> that chunk's root. This falls out of the
 *       existing rules rather than being special-cased: a single-leaf tree's root is its leaf
 *       hash, and the chunk root is that leaf.</li>
 *   <li><b>One entry in a chunk</b> — the chunk tree's root is the entry's leaf hash and its
 *       entry proof is empty. An empty proof still has to be checked, not waved through; see
 *       {@link MerkleVerifier#computeRoot}.</li>
 * </ul>
 *
 * <p>Instances are immutable. {@link #withEntryReplaced} returns a new forest rather than
 * mutating this one, so an "original" and a "tampered" forest can be compared side by side —
 * which is exactly what the Phase 5 UI does.
 */
public final class MerkleForest {

    private final List<Chunk> chunks;
    private final List<MerkleTree> chunkTrees;
    private final MerkleTree superTree;
    private final String strategyName;

    /**
     * Global index at which each chunk starts, used to locate an entry's chunk by binary
     * search in O(log chunkCount) rather than by walking the chunk list.
     */
    private final int[] chunkStartOffsets;

    private final int entryCount;

    private MerkleForest(List<Chunk> chunks, List<MerkleTree> chunkTrees, MerkleTree superTree,
                         String strategyName, int[] chunkStartOffsets, int entryCount) {
        this.chunks = chunks;
        this.chunkTrees = chunkTrees;
        this.superTree = superTree;
        this.strategyName = strategyName;
        this.chunkStartOffsets = chunkStartOffsets;
        this.entryCount = entryCount;
    }

    /** Chunks the entries with the given strategy and builds a tree over each. */
    public static MerkleForest build(List<LogEntry> entries, ChunkingStrategy strategy) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(strategy, "strategy");
        return fromChunks(strategy.chunk(entries), strategy.name());
    }

    /** Builds a forest over chunks that have already been produced. */
    public static MerkleForest fromChunks(List<Chunk> chunks, String strategyName) {
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(strategyName, "strategyName");

        List<MerkleTree> trees = new ArrayList<>(chunks.size());
        List<byte[]> chunkRoots = new ArrayList<>(chunks.size());
        int[] offsets = new int[chunks.size()];
        int runningOffset = 0;

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            MerkleTree tree = MerkleTree.fromEntries(chunk.entries());
            trees.add(tree);
            chunkRoots.add(tree.root());
            offsets[i] = runningOffset;
            runningOffset += chunk.size();
        }

        // The super-tree's leaves are chunk roots, fed in as pre-computed hashes. They are
        // already commitments, so they are not re-tagged with the 0x00 leaf prefix — doing so
        // would add a hash per chunk for no gain. With one chunk this collapses to a
        // single-leaf tree whose root is that chunk's root, which is the documented behaviour.
        MerkleTree superTree = MerkleTree.fromLeafHashes(chunkRoots);

        return new MerkleForest(List.copyOf(chunks), Collections.unmodifiableList(trees),
                superTree, strategyName, offsets, runningOffset);
    }

    /**
     * The single hash committing to every entry in every chunk. This is what gets published,
     * stored as a trusted anchor, and compared against later.
     *
     * <p>For an empty forest this is the RFC 6962 empty-tree hash, never null.
     */
    public byte[] superRoot() {
        return superTree.root();
    }

    public String superRootHex() {
        return superTree.rootHex();
    }

    public boolean isEmpty() {
        return entryCount == 0;
    }

    public int entryCount() {
        return entryCount;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public String strategyName() {
        return strategyName;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    /** The Merkle tree built over a single chunk. */
    public MerkleTree chunkTree(int chunkIndex) {
        requireChunkIndex(chunkIndex);
        return chunkTrees.get(chunkIndex);
    }

    /** Root of a single chunk's tree — one leaf of the super-tree. */
    public byte[] chunkRoot(int chunkIndex) {
        return chunkTree(chunkIndex).root();
    }

    /** The tree whose leaves are the chunk roots. */
    public MerkleTree superTree() {
        return superTree;
    }

    /**
     * Finds which chunk holds a given global entry index, by binary search over the chunk
     * start offsets — O(log chunkCount), not a walk over the chunks.
     *
     * @return the chunk index
     */
    public int chunkIndexOf(int globalIndex) {
        requireEntryIndex(globalIndex);

        int found = Arrays.binarySearch(chunkStartOffsets, globalIndex);
        // An exact hit means the index is a chunk's first entry. Otherwise binarySearch
        // returns -(insertionPoint) - 1, and the chunk we want is the one before that point.
        return found >= 0 ? found : (-found - 2);
    }

    /**
     * Generates a two-stage inclusion proof for one entry.
     *
     * <p>Cost is {@code O(log chunkSize + log chunkCount)}: a binary search to locate the
     * chunk, then one logarithmic proof in each of the two trees. Nothing here scans the
     * entry list.
     *
     * @throws EmptyForestException      if the forest holds no entries
     * @throws IndexOutOfBoundsException if the index is outside the dataset
     */
    public ForestProof generateProof(int globalIndex) {
        if (isEmpty()) {
            throw new EmptyForestException(
                    "Cannot prove inclusion of entry " + globalIndex + ": the forest is empty");
        }
        requireEntryIndex(globalIndex);

        int chunkIndex = chunkIndexOf(globalIndex);
        int localIndex = globalIndex - chunkStartOffsets[chunkIndex];

        return new ForestProof(globalIndex, chunkIndex, localIndex,
                chunkTrees.get(chunkIndex).generateProof(localIndex),
                superTree.generateProof(chunkIndex));
    }

    /** Verifies a proof against this forest's current super-root. */
    public boolean verify(LogEntry entry, ForestProof proof) {
        return verify(entry.leafHash(), proof, superRoot());
    }

    /**
     * Verifies a two-stage proof against a trusted super-root, with no access to the forest.
     *
     * <p>Stage one folds the entry proof to recover a chunk root; stage two folds the chunk
     * proof, starting from that recovered root, to recover the super-root. If either stage is
     * wrong the final comparison fails — there is no partial credit.
     */
    public static boolean verify(byte[] leafHash, ForestProof proof, byte[] trustedSuperRoot) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(trustedSuperRoot, "trustedSuperRoot");

        byte[] recoveredChunkRoot = MerkleVerifier.computeRoot(leafHash, proof.entryProof());
        byte[] recoveredSuperRoot = MerkleVerifier.computeRoot(recoveredChunkRoot, proof.chunkProof());

        return Hashing.equal(recoveredSuperRoot, trustedSuperRoot);
    }

    /**
     * Returns a new forest with one entry replaced, rebuilding only what actually changed.
     *
     * <p>This is the localised-rebuild claim made concrete. Only the affected chunk's tree is
     * rebuilt, plus the super-tree over the chunk roots. Chunk boundaries are kept as they
     * were, deliberately: re-running the chunking strategy could move every boundary and turn
     * a one-entry edit into a full rebuild, which would defeat the point of measuring rebuild
     * cost per strategy.
     *
     * @return the new forest and a record of how much work the rebuild cost
     */
    public RebuildResult withEntryReplaced(int globalIndex, LogEntry replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (isEmpty()) {
            throw new EmptyForestException("Cannot replace entry " + globalIndex + ": the forest is empty");
        }
        requireEntryIndex(globalIndex);

        int chunkIndex = chunkIndexOf(globalIndex);
        int localIndex = globalIndex - chunkStartOffsets[chunkIndex];

        List<LogEntry> revisedEntries = new ArrayList<>(chunks.get(chunkIndex).entries());
        revisedEntries.set(localIndex, replacement);

        List<Chunk> revisedChunks = new ArrayList<>(chunks);
        revisedChunks.set(chunkIndex, new Chunk(chunkIndex, revisedEntries, chunks.get(chunkIndex).strategyName()));

        MerkleForest rebuilt = fromChunks(revisedChunks, strategyName);

        // Entries re-hashed = the affected chunk only. Compare with entryCount(), which is
        // what a single global tree would have cost.
        return new RebuildResult(rebuilt, chunkIndex, revisedEntries.size(), entryCount);
    }

    /**
     * What a localised rebuild actually cost.
     *
     * @param forest             the rebuilt forest
     * @param rebuiltChunkIndex  which chunk had to be rebuilt
     * @param entriesRehashed    entries re-hashed — the size of that one chunk
     * @param entriesInDataset   total entries, i.e. what a single global tree would have cost
     */
    public record RebuildResult(MerkleForest forest, int rebuiltChunkIndex,
                                int entriesRehashed, int entriesInDataset) {

        /** How many times cheaper the localised rebuild was than re-hashing everything. */
        public double savingFactor() {
            return entriesRehashed == 0 ? 1.0 : (double) entriesInDataset / entriesRehashed;
        }
    }

    private void requireChunkIndex(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) {
            throw new IndexOutOfBoundsException(
                    "Chunk index " + chunkIndex + " out of range [0, " + chunks.size() + ")");
        }
    }

    private void requireEntryIndex(int globalIndex) {
        if (globalIndex < 0 || globalIndex >= entryCount) {
            throw new IndexOutOfBoundsException(
                    "Entry index " + globalIndex + " out of range [0, " + entryCount + ")");
        }
    }

    @Override
    public String toString() {
        return "MerkleForest{strategy=" + strategyName + ", entries=" + entryCount
                + ", chunks=" + chunks.size()
                + ", superRoot=" + (isEmpty() ? "<empty>" : superRootHex().substring(0, 12) + "…") + "}";
    }
}
