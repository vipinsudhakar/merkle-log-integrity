package com.merklelog.benchmark;

import com.merklelog.chunking.Chunk;
import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.chunking.ResourceAwareChunking;
import com.merklelog.core.ForestProof;
import com.merklelog.core.Hashing;
import com.merklelog.core.LogEntry;
import com.merklelog.core.MerkleForest;
import com.merklelog.core.MerkleProof;
import com.merklelog.core.MerkleVerifier;
import com.merklelog.core.PaperPipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One thing being benchmarked: either a chunking strategy inside our per-chunk forest, or the
 * base paper's global-tree pipeline.
 *
 * <p>Both answer the same questions through this one interface, so every metric is measured by
 * the same code for every subject. Nothing here is specialised to make one subject look better.
 */
interface Subject {

    /** Label used in the results, e.g. {@code caac} or {@code paper-pipeline}. */
    String name();

    /** Builds the structure over the entries. */
    Built build(List<LogEntry> entries);

    /** A built structure, ready to be queried. */
    interface Built {

        /** Sizes of the units an edit is confined to: the chunks, or the one global tree. */
        List<Integer> chunkSizes();

        /** Number of sibling hashes in the proof for entry {@code index}. */
        int proofSteps(int index);

        /** Generates the inclusion proof for entry {@code index} (opaque to the benchmark). */
        Object generateProof(int index);

        /** Verifies {@code entry} with a proof from {@link #generateProof} against the trusted root. */
        boolean verify(LogEntry entry, Object proof);

        /** SHA-256 operations to replace one entry and restore a valid root (counted). */
        long editCost(int index, LogEntry replacement);

        /**
         * How many of this structure's chunk roots the original does not have, i.e. how many
         * chunks changed. {@code -1} where the question does not apply: a single global tree has
         * no chunk roots to keep.
         */
        int chunkRootsChangedSince(Built original);

        /**
         * SHA-256 operations needed to get from {@code original} to this structure when this one is
         * the original plus one inserted entry, re-hashing only what changed.
         *
         * <p>Forest: every chunk whose root is new is rebuilt in full ({@code size} leaves plus
         * {@code size − 1} nodes), then the super-tree ({@code chunks − 1} nodes). Chunks whose root
         * already existed are reused as they are. Global tree: one new leaf, then all {@code n − 1}
         * nodes over the {@code n} leaves (existing leaf hashes are reused, as in the paper).
         *
         * <p>Computed from the structure, not timed, so it is exact and machine-independent.
         */
        long insertionCost(Built original);
    }

    /** A strategy inside {@link MerkleForest}: one tree per chunk under a super-root. */
    static Subject forest(ChunkingStrategy strategy) {
        return new Subject() {
            @Override
            public String name() {
                return strategy.name();
            }

            @Override
            public Built build(List<LogEntry> entries) {
                return new ForestBuilt(MerkleForest.build(entries, strategy));
            }
        };
    }

    /**
     * The base paper's pipeline (Algorithm 1): batches from the paper's own sizing rule, one global
     * tree rebuilt after every batch.
     */
    static Subject paperPipeline() {
        return new Subject() {
            @Override
            public String name() {
                return "paper-pipeline";
            }

            @Override
            public Built build(List<LogEntry> entries) {
                return new PipelineBuilt(PaperPipeline.ingest(entries, new ResourceAwareChunking()));
            }
        };
    }

    /** A built {@link MerkleForest}. */
    final class ForestBuilt implements Built {

        private final MerkleForest forest;
        private final byte[] superRoot;

        ForestBuilt(MerkleForest forest) {
            this.forest = forest;
            this.superRoot = forest.superRoot();
        }

        @Override
        public List<Integer> chunkSizes() {
            return forest.chunks().stream().map(Chunk::size).toList();
        }

        @Override
        public int proofSteps(int index) {
            return forest.generateProof(index).totalSteps();
        }

        @Override
        public Object generateProof(int index) {
            return forest.generateProof(index);
        }

        @Override
        public boolean verify(LogEntry entry, Object proof) {
            return MerkleForest.verify(entry.leafHash(), (ForestProof) proof, superRoot);
        }

        @Override
        public long editCost(int index, LogEntry replacement) {
            return forest.withEntryReplaced(index, replacement).hashOperations();
        }

        @Override
        public int chunkRootsChangedSince(Built original) {
            Set<String> roots = chunkRoots(forest);
            roots.removeAll(chunkRoots(((ForestBuilt) original).forest));
            return roots.size();
        }

        @Override
        public long insertionCost(Built original) {
            Set<String> existing = chunkRoots(((ForestBuilt) original).forest);
            long cost = 0;
            for (int c = 0; c < forest.chunkCount(); c++) {
                if (!existing.contains(Hashing.toHex(forest.chunkRoot(c)))) {
                    int size = forest.chunks().get(c).size();
                    cost += size + (size - 1);
                }
            }
            return cost + (forest.chunkCount() - 1);
        }

        private static Set<String> chunkRoots(MerkleForest forest) {
            Set<String> roots = new HashSet<>();
            for (int c = 0; c < forest.chunkCount(); c++) {
                roots.add(Hashing.toHex(forest.chunkRoot(c)));
            }
            return roots;
        }
    }

    /** A built {@link PaperPipeline}. */
    final class PipelineBuilt implements Built {

        private final PaperPipeline pipeline;
        private final byte[] root;

        PipelineBuilt(PaperPipeline pipeline) {
            this.pipeline = pipeline;
            this.root = pipeline.root();
        }

        @Override
        public List<Integer> chunkSizes() {
            return List.of(pipeline.entryCount());
        }

        @Override
        public int proofSteps(int index) {
            return pipeline.generateProof(index).length();
        }

        @Override
        public Object generateProof(int index) {
            return pipeline.generateProof(index);
        }

        @Override
        public boolean verify(LogEntry entry, Object proof) {
            return MerkleVerifier.verify(entry, (MerkleProof) proof, root);
        }

        @Override
        public long editCost(int index, LogEntry replacement) {
            return pipeline.withEntryReplaced(index, replacement).hashOperations();
        }

        @Override
        public int chunkRootsChangedSince(Built original) {
            return -1;
        }

        /**
         * Depends only on the original's size: after inserting into {@code n} entries the tree has
         * {@code n + 1} leaves, so one new leaf hash plus {@code n} node hashes. That lets the
         * benchmark compute it without building a second 100k-entry pipeline.
         */
        @Override
        public long insertionCost(Built original) {
            return 1 + ((PipelineBuilt) original).pipeline.entryCount();
        }
    }
}
