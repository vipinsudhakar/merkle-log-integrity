package com.merklelog.api;

import com.merklelog.chunking.Chunk;
import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.core.ForestProof;
import com.merklelog.core.Hashing;
import com.merklelog.core.LogEntry;
import com.merklelog.core.MerkleForest;
import com.merklelog.core.MerkleTree;
import com.merklelog.core.MerkleVerifier;
import com.merklelog.persistence.DatasetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How a strategy chunks a dataset, the trees it builds, proofs, and the tamper comparison.
 *
 * <p>Every endpoint takes the strategy as {@code ?strategy=caac} plus optional parameters, e.g.
 * {@code ?strategy=fixed-size&chunkSize=32} or {@code ?strategy=caac&pressureProfile=0.25,0.85}.
 */
@RestController
@RequestMapping("/api/datasets/{id}")
public class ForestController {

    /** Above this many leaves a tree is too large to draw usefully, so it is refused. */
    static final int MAX_DRAWABLE_LEAVES = 4_096;

    private final DatasetService datasets;
    private final ForestService forests;

    public ForestController(DatasetService datasets, ForestService forests) {
        this.datasets = datasets;
        this.forests = forests;
    }

    /** Boundaries, sizes and roots of every chunk: what the chunking strip draws. */
    @GetMapping("/chunks")
    public Dto.ForestView chunks(@PathVariable long id, @RequestParam Map<String, String> query) {
        ChunkingStrategy strategy = forests.strategy(query);
        MerkleForest forest = forests.forest(datasets.stream(id), strategy);

        List<Dto.ChunkView> chunks = new ArrayList<>(forest.chunkCount());
        int start = 0;
        for (Chunk chunk : forest.chunks()) {
            chunks.add(Dto.ChunkView.of(chunk, start, forest.chunkTree(chunk.index()).depth(),
                    Hashing.toHex(forest.chunkRoot(chunk.index()))));
            start += chunk.size();
        }
        return new Dto.ForestView(strategy.name(), strategy.parameters(), forest.entryCount(),
                forest.chunkCount(), forest.superRootHex(), forest.superTree().depth(), chunks);
    }

    /** One chunk's Merkle tree, as levels of hashes. */
    @GetMapping("/chunks/{chunk}/tree")
    public Dto.TreeView chunkTree(@PathVariable long id, @PathVariable int chunk,
                                  @RequestParam Map<String, String> query) {
        MerkleForest forest = forests.forest(datasets.stream(id), forests.strategy(query));
        MerkleTree tree = forest.chunkTree(chunk);
        int start = 0;
        for (int c = 0; c < chunk; c++) {
            start += forest.chunks().get(c).size();
        }
        return new Dto.TreeView("chunk", chunk, start, levels(tree));
    }

    /** The super-tree over the chunk roots, as levels of hashes. */
    @GetMapping("/supertree")
    public Dto.TreeView superTree(@PathVariable long id, @RequestParam Map<String, String> query) {
        MerkleForest forest = forests.forest(datasets.stream(id), forests.strategy(query));
        return new Dto.TreeView("super", -1, 0, levels(forest.superTree()));
    }

    /**
     * The two-stage inclusion proof for one entry, with every intermediate hash: entry → chunk
     * root, then chunk root → super-root. Produced by {@link MerkleVerifier#verifyWithTrace}, so
     * the step-through view shows exactly what the verifier computed.
     */
    @GetMapping("/proof/{index}")
    public Dto.ProofView proof(@PathVariable long id, @PathVariable int index,
                               @RequestParam Map<String, String> query) {
        ChunkingStrategy strategy = forests.strategy(query);
        List<LogEntry> stream = datasets.stream(id);
        ForestService.requireIndex(index, stream.size());
        MerkleForest forest = forests.forest(stream, strategy);

        ForestProof proof = forest.generateProof(index);
        LogEntry entry = stream.get(index);
        byte[] chunkRoot = forest.chunkRoot(proof.chunkIndex());

        MerkleVerifier.VerificationTrace entryTrace =
                MerkleVerifier.verifyWithTrace(entry.leafHash(), proof.entryProof(), chunkRoot);
        MerkleVerifier.VerificationTrace chunkTrace =
                MerkleVerifier.verifyWithTrace(entryTrace.computedRoot(), proof.chunkProof(), forest.superRoot());

        return new Dto.ProofView(strategy.name(), Dto.EntryView.of(index, entry),
                proof.chunkIndex(), proof.localIndex(),
                Dto.StageView.of(proof.entryProof(), entryTrace),
                Dto.StageView.of(proof.chunkProof(), chunkTrace),
                proof.totalSteps(), proof.sizeInBytes(), forest.superRootHex(),
                entryTrace.valid() && chunkTrace.valid());
    }

    /**
     * Applies one edit or insertion in memory (nothing is stored) and reports, for every strategy
     * and for the base paper's pipeline, which chunks change and what the rebuild costs.
     */
    @PostMapping("/tamper")
    public Dto.TamperResult tamper(@PathVariable long id, @Valid @RequestBody Dto.TamperRequest request) {
        List<LogEntry> stream = datasets.stream(id);
        Map<String, String> parameters = request.parameters() == null ? Map.of() : request.parameters();
        return new Dto.TamperResult(request.operation(), request.position(), stream.size(),
                forests.compare(stream, request.operation(), request.position(), request.message(), parameters));
    }

    private static List<List<String>> levels(MerkleTree tree) {
        if (tree.leafCount() > MAX_DRAWABLE_LEAVES) {
            throw new IllegalArgumentException("Tree has " + tree.leafCount()
                    + " leaves; drawing is limited to " + MAX_DRAWABLE_LEAVES);
        }
        return tree.levels().stream()
                .map(level -> level.stream().map(Hashing::toHex).toList())
                .toList();
    }
}
