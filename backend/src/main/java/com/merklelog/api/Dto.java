package com.merklelog.api;

import com.merklelog.chunking.Chunk;
import com.merklelog.core.Hashing;
import com.merklelog.core.LogEntry;
import com.merklelog.core.MerkleProof;
import com.merklelog.core.MerkleVerifier;
import com.merklelog.core.ProofStep;
import com.merklelog.persistence.DatasetEntity;
import com.merklelog.persistence.RootAnchorEntity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Every JSON shape the API sends or receives, in one place.
 *
 * <p>Hashes always travel as lowercase hex. Records serialise field by field, so the JSON keys
 * are exactly the component names below.
 */
public final class Dto {

    private Dto() {
    }

    // ------------------------------------------------------------------ strategies

    /** A chunking strategy with its default parameters. */
    public record StrategyInfo(String name, String description, Map<String, String> defaults) {
    }

    // ------------------------------------------------------------------ datasets

    public record DatasetInfo(Long id, String name, long seed, int size, Instant createdAt) {
        static DatasetInfo of(DatasetEntity d) {
            return new DatasetInfo(d.getId(), d.getName(), d.getSeed(), d.getSize(), d.getCreatedAt());
        }
    }

    public record CreateDatasetRequest(
            @NotBlank String name,
            @Min(1) @Max(100_000) int size,
            Long seed) {
    }

    public record EntryView(int position, long id, Instant timestamp, String level, String source,
                            String message, String leafHash) {
        static EntryView of(int position, LogEntry e) {
            return new EntryView(position, e.id(), e.timestamp(), e.level(), e.source(), e.message(),
                    Hashing.toHex(e.leafHash()));
        }
    }

    public record EntryPage(int offset, int total, List<EntryView> entries) {
    }

    /** Body for overwriting a stored entry, i.e. simulating an attacker with database access. */
    public record OverwriteRequest(@NotBlank String message) {
    }

    // ------------------------------------------------------------------ forest

    /** One chunk: where it starts in the stream, how big it is, and its tree's root. */
    public record ChunkView(int index, int start, int size, int depth, String root,
                            Instant startTime, Instant endTime) {
        static ChunkView of(Chunk chunk, int start, int depth, String root) {
            return new ChunkView(chunk.index(), start, chunk.size(), depth, root, chunk.startTime(), chunk.endTime());
        }
    }

    /** How a strategy partitions a dataset: the input to the chunking strip. */
    public record ForestView(String strategy, Map<String, String> parameters, int entryCount,
                             int chunkCount, String superRoot, int superTreeDepth, List<ChunkView> chunks) {
    }

    /**
     * A tree as its array of levels, bottom-up: {@code levels[0]} are the leaves, the last level
     * holds the root. The same layout {@code MerkleTree} stores, so the frontend draws exactly the
     * structure the proofs run over (sibling of i is i ^ 1, parent is i >> 1).
     */
    public record TreeView(String kind, int chunkIndex, int start, List<List<String>> levels) {
    }

    // ------------------------------------------------------------------ proofs

    public record StepView(String sibling, String side) {
        static StepView of(ProofStep step) {
            return new StepView(step.siblingHashHex(), step.side().name().toLowerCase());
        }
    }

    /** One rung of verification: {@code before ‖ sibling → after} (or sibling first, by side). */
    public record TraceView(String before, String sibling, String side, String after) {
        static TraceView of(MerkleVerifier.TraceStep t) {
            return new TraceView(t.runningBeforeHex(), t.siblingHex(), t.side().name().toLowerCase(), t.runningAfterHex());
        }
    }

    /** One proof stage (entry → chunk root, or chunk root → super-root) with its full trace. */
    public record StageView(int leafIndex, int leafCount, List<StepView> steps, List<TraceView> trace,
                            String computedRoot, String expectedRoot, boolean valid) {
        static StageView of(MerkleProof proof, MerkleVerifier.VerificationTrace trace) {
            return new StageView(proof.leafIndex(), proof.leafCount(),
                    proof.steps().stream().map(StepView::of).toList(),
                    trace.steps().stream().map(TraceView::of).toList(),
                    trace.computedRootHex(), trace.expectedRootHex(), trace.valid());
        }
    }

    public record ProofView(String strategy, EntryView entry, int chunkIndex, int localIndex,
                            StageView entryStage, StageView chunkStage, int totalSteps, int sizeInBytes,
                            String superRoot, boolean valid) {
    }

    // ------------------------------------------------------------------ tamper / insert

    public record TamperRequest(
            @Pattern(regexp = "edit|insert") String operation,
            @Min(0) int position,
            @NotBlank String message,
            Map<String, String> parameters) {
    }

    /**
     * What one change did under one strategy.
     *
     * @param changedChunks  indices (in the changed forest) of chunks whose root is new
     * @param rebuildHashOps SHA-256 operations to restore a valid super-root, re-hashing only what changed
     * @param detected       the trusted (old) super-root no longer matches
     */
    public record TamperOutcome(String subject, int chunkCountBefore, int chunkCountAfter,
                                List<Integer> chunkSizesBefore, List<Integer> chunkSizesAfter,
                                List<Integer> changedChunks, long rebuildHashOps,
                                String superRootBefore, String superRootAfter, boolean detected) {
    }

    public record TamperResult(String operation, int position, int entryCountBefore,
                               List<TamperOutcome> outcomes) {
    }

    // ------------------------------------------------------------------ anchors

    public record AnchorView(Long id, long datasetId, String strategy, String parameters,
                             int entryCount, int chunkCount, String superRoot, Instant anchoredAt) {
        static AnchorView of(RootAnchorEntity a) {
            return new AnchorView(a.getId(), a.getDatasetId(), a.getStrategy(), a.getParameters(),
                    a.getEntryCount(), a.getChunkCount(), a.getSuperRoot(), a.getAnchoredAt());
        }
    }

    /**
     * Result of checking the stored log against its latest anchor.
     *
     * @param matches the recomputed super-root equals the anchored one: the log is intact
     */
    public record AnchorCheck(AnchorView anchor, String recomputedRoot, int entryCount, boolean matches) {
    }
}
