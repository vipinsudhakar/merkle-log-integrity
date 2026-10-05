package com.merklelog.core;

import com.merklelog.chunking.Chunk;
import com.merklelog.chunking.ChunkingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The base paper's ingestion pipeline, reproduced as the baseline for rebuild cost.
 *
 * <p>Source: Yağız, Horasan, Yurttakal 2026 (arXiv:2605.00065), Algorithm 1 and §3.5 (the
 * corrected "rebuild-after-batch" design). For each batch produced by the chunker:
 *
 * <ol>
 *   <li>hash each new entry once and append its leaf hash to the leaf array (lines 4–7);</li>
 *   <li>rebuild the <b>one global tree</b> over all leaves so far, once per batch (line 8);</li>
 *   <li>anchor the new root (line 9).</li>
 * </ol>
 *
 * <p>We keep the paper's own optimisation: leaf hashes are computed once and reused, so a
 * rebuild costs only the internal nodes, {@code n − 1} hashes for {@code n} leaves. We do not
 * weaken it. The O(n) per batch is the paper's design, which it names as limitation L4.
 *
 * <h2>Why the chunks do not shape the tree here</h2>
 *
 * <p>The paper's Lemma 4 states it directly: chunking only sets how many leaves are appended
 * before each rebuild, so the final tree and root are the same whatever the chunking. That is the
 * structural difference from {@link MerkleForest}, where each chunk is its own tree and an edit
 * rebuilds one chunk plus the super-tree.
 *
 * <p>Immutable: {@link #withEntryReplaced} returns a new pipeline, like {@link MerkleForest}.
 */
public final class PaperPipeline {

    private final List<LogEntry> entries;
    private final List<byte[]> leafHashes;
    private final MerkleTree tree;
    private final int batchCount;
    private final long ingestHashOperations;

    private PaperPipeline(List<LogEntry> entries, List<byte[]> leafHashes, MerkleTree tree,
                          int batchCount, long ingestHashOperations) {
        this.entries = entries;
        this.leafHashes = leafHashes;
        this.tree = tree;
        this.batchCount = batchCount;
        this.ingestHashOperations = ingestHashOperations;
    }

    /**
     * Ingests the entries batch by batch, exactly as Algorithm 1 does.
     *
     * @param entries  the log stream
     * @param batching how batches are cut; the paper uses {@code ResourceAwareChunking}
     */
    public static PaperPipeline ingest(List<LogEntry> entries, ChunkingStrategy batching) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(batching, "batching");

        // Batch boundaries are decided before the counter starts, so ingest cost covers tree work
        // only, the same as MerkleForest.build would report for building its trees.
        List<Chunk> batches = batching.chunk(entries);
        long hashesBefore = Hashing.operationCount();

        List<byte[]> leaves = new ArrayList<>(entries.size());
        MerkleTree tree = MerkleTree.fromLeafHashes(List.of());
        for (Chunk batch : batches) {
            for (LogEntry entry : batch.entries()) {
                leaves.add(entry.leafHash());              // lines 4–7: hash once, append
            }
            tree = MerkleTree.fromLeafHashes(leaves);      // line 8: rebuild the whole tree once
        }

        long cost = Hashing.operationCount() - hashesBefore;
        return new PaperPipeline(List.copyOf(entries), List.copyOf(leaves), tree, batches.size(), cost);
    }

    /**
     * Replaces one entry. In the paper's design the global tree has to be rebuilt in full:
     * one new leaf hash plus all {@code n − 1} internal nodes.
     */
    public Result withEntryReplaced(int index, LogEntry replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (entries.isEmpty()) {
            throw new EmptyForestException("Cannot replace entry " + index + ": the pipeline is empty");
        }
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException(
                    "Entry index " + index + " out of range [0, " + entries.size() + ")");
        }
        long hashesBefore = Hashing.operationCount();

        List<LogEntry> revisedEntries = new ArrayList<>(entries);
        revisedEntries.set(index, replacement);
        List<byte[]> revisedLeaves = new ArrayList<>(leafHashes);
        revisedLeaves.set(index, replacement.leafHash());
        MerkleTree rebuilt = MerkleTree.fromLeafHashes(revisedLeaves);

        long cost = Hashing.operationCount() - hashesBefore;
        PaperPipeline next = new PaperPipeline(List.copyOf(revisedEntries), List.copyOf(revisedLeaves),
                rebuilt, batchCount, ingestHashOperations);
        return new Result(next, cost);
    }

    /** The anchored root of the global tree. */
    public byte[] root() {
        return tree.root();
    }

    public String rootHex() {
        return tree.rootHex();
    }

    /** O(log n) inclusion proof against the global tree. */
    public MerkleProof generateProof(int index) {
        return tree.generateProof(index);
    }

    public MerkleTree tree() {
        return tree;
    }

    public int entryCount() {
        return entries.size();
    }

    /** How many batches ingestion used, i.e. how many times the tree was rebuilt. */
    public int batchCount() {
        return batchCount;
    }

    /** Hash operations spent on ingestion: n leaves plus one full node rebuild per batch. */
    public long ingestHashOperations() {
        return ingestHashOperations;
    }

    /**
     * @param pipeline       the pipeline after the edit
     * @param hashOperations SHA-256 operations the rebuild actually performed (counted)
     */
    public record Result(PaperPipeline pipeline, long hashOperations) {
    }

    @Override
    public String toString() {
        return "PaperPipeline{entries=" + entries.size() + ", batches=" + batchCount
                + ", root=" + (tree.isEmpty() ? "<empty>" : rootHex().substring(0, 12) + "…") + "}";
    }
}
