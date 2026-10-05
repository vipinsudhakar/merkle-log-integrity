package com.merklelog.api;

import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.chunking.ChunkingStrategyFactory;

import com.merklelog.core.LogEntry;
import com.merklelog.core.MerkleForest;
import com.merklelog.core.MerkleTree;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The API's only door into the engine.
 *
 * <p>Controllers never build trees themselves; they ask this service, which calls {@code core} and
 * {@code chunking} as plain Java. Whatever the visualiser shows is therefore exactly what the
 * tested engine computes; there is no second implementation to drift.
 */
@Service
public class ForestService {

    /** Label for the base paper's global-tree pipeline in tamper comparisons. */
    public static final String PAPER_PIPELINE = "paper-pipeline";

    /** Query parameters that select things rather than configure a strategy. */
    private static final List<String> NON_STRATEGY_PARAMETERS = List.of("strategy");

    /**
     * Builds the strategy named in {@code query.get("strategy")}, configured by the other query
     * parameters (unknown keys are ignored by the factory, malformed values are rejected).
     */
    public ChunkingStrategy strategy(Map<String, String> query) {
        String name = query.getOrDefault("strategy", "caac");
        Map<String, String> parameters = new HashMap<>(query);
        NON_STRATEGY_PARAMETERS.forEach(parameters::remove);
        return ChunkingStrategyFactory.create(name, parameters);
    }

    public MerkleForest forest(List<LogEntry> stream, ChunkingStrategy strategy) {
        return MerkleForest.build(stream, strategy);
    }

    /**
     * What one change does to every strategy, side by side: which chunks change, what rebuilding
     * costs (counted hash operations), and whether the trusted super-root catches it.
     *
     * @param operation  {@code edit} (replace the entry at {@code position}) or {@code insert}
     *                   (insert a new entry before {@code position})
     * @param parameters applied to every strategy; each takes the keys it understands
     */
    public List<Dto.TamperOutcome> compare(List<LogEntry> stream, String operation, int position,
                                           String message, Map<String, String> parameters) {
        List<LogEntry> changed = applyChange(stream, operation, position, message);
        boolean insert = operation.equals("insert");
        List<Dto.TamperOutcome> outcomes = new ArrayList<>();

        for (String name : ChunkingStrategyFactory.availableStrategies()) {
            ChunkingStrategy strategy = ChunkingStrategyFactory.create(name, parameters);
            MerkleForest before = MerkleForest.build(stream, strategy);
            MerkleForest after;
            long cost;
            if (insert) {
                after = MerkleForest.build(changed, strategy);
                cost = after.rebuildCostSince(before);
            } else {
                MerkleForest.RebuildResult rebuilt = before.withEntryReplaced(position, changed.get(position));
                after = rebuilt.forest();
                cost = rebuilt.hashOperations();
            }
            outcomes.add(new Dto.TamperOutcome(
                    name, before.chunkCount(), after.chunkCount(),
                    sizes(before), sizes(after), after.chunksChangedSince(before),
                    cost, before.superRootHex(), after.superRootHex(),
                    !before.superRootHex().equals(after.superRootHex())));
        }

        // The base paper's pipeline: one global tree, so any change rebuilds all of it. By the
        // paper's Lemma 4 its root equals a single MerkleTree over the stream whatever the batching
        // (pinned by PaperPipelineTest), so the roots are computed directly in O(n) rather than by
        // replaying ingestion, which rebuilds after every batch and takes seconds at 100k entries.
        // The costs are the ones PaperPipelineTest verifies: an edit re-hashes 1 leaf + n − 1 nodes,
        // an insertion 1 leaf + n nodes over the n + 1 leaves.
        String paperRootBefore = MerkleTree.fromEntries(stream).rootHex();
        String paperRootAfter = MerkleTree.fromEntries(changed).rootHex();
        long paperCost = insert ? 1L + stream.size() : (long) stream.size();
        outcomes.add(new Dto.TamperOutcome(
                PAPER_PIPELINE, 1, 1, List.of(stream.size()), List.of(changed.size()), List.of(0),
                paperCost, paperRootBefore, paperRootAfter, !paperRootBefore.equals(paperRootAfter)));
        return outcomes;
    }

    /** The stream after an edit or an insertion; the original list is not modified. */
    static List<LogEntry> applyChange(List<LogEntry> stream, String operation, int position, String message) {
        List<LogEntry> copy = new ArrayList<>(stream);
        switch (operation) {
            case "edit" -> {
                requireIndex(position, stream.size());
                copy.set(position, stream.get(position).withMessage(message));
            }
            case "insert" -> {
                if (position < 1 || position > stream.size()) {
                    throw new IllegalArgumentException(
                            "Insert position must be in [1, " + stream.size() + "], got " + position);
                }
                LogEntry previous = stream.get(position - 1);
                copy.add(position, new LogEntry(9_000_000L + position, previous.timestamp().plusMillis(1),
                        "WARN", "injected", message));
            }
            default -> throw new IllegalArgumentException(
                    "operation must be 'edit' or 'insert', got '" + operation + "'");
        }
        return copy;
    }

    static void requireIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of range [0, " + size + ")");
        }
    }

    private static List<Integer> sizes(MerkleForest forest) {
        return forest.chunks().stream().map(c -> c.size()).toList();
    }
}
