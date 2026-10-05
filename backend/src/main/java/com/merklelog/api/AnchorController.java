package com.merklelog.api;

import com.merklelog.chunking.ChunkingStrategy;
import com.merklelog.core.MerkleForest;
import com.merklelog.persistence.DatasetService;
import com.merklelog.persistence.RootAnchorEntity;
import com.merklelog.persistence.RootAnchorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The trusted root anchor (the base paper's §3.1): publish a super-root, and later check the
 * stored log against it.
 *
 * <p>Demo flow: anchor → overwrite an entry with {@code PUT /api/datasets/{id}/entries/{pos}}
 * (the attacker) → verify, which now fails because the recomputed super-root no longer matches.
 */
@RestController
@RequestMapping("/api/datasets/{id}/anchors")
public class AnchorController {

    private final DatasetService datasets;
    private final ForestService forests;
    private final RootAnchorRepository anchors;

    public AnchorController(DatasetService datasets, ForestService forests, RootAnchorRepository anchors) {
        this.datasets = datasets;
        this.forests = forests;
        this.anchors = anchors;
    }

    @GetMapping
    public List<Dto.AnchorView> history(@PathVariable long id) {
        datasets.get(id);
        return anchors.findByDatasetIdOrderByIdDesc(id).stream().map(Dto.AnchorView::of).toList();
    }

    /** Computes the current super-root under the given strategy and records it as trusted. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dto.AnchorView anchor(@PathVariable long id, @RequestParam Map<String, String> query) {
        ChunkingStrategy strategy = forests.strategy(query);
        MerkleForest forest = forests.forest(datasets.stream(id), strategy);
        return Dto.AnchorView.of(anchors.save(new RootAnchorEntity(id, strategy.name(), strategy.describe(),
                forest.entryCount(), forest.chunkCount(), forest.superRootHex())));
    }

    /**
     * Recomputes the super-root from the stored entries, with the same strategy and parameters as
     * the latest anchor for that strategy, and compares the two.
     */
    @GetMapping("/verify")
    public Dto.AnchorCheck verify(@PathVariable long id, @RequestParam Map<String, String> query) {
        ChunkingStrategy strategy = forests.strategy(query);
        RootAnchorEntity anchor = anchors.findFirstByDatasetIdAndStrategyOrderByIdDesc(id, strategy.name())
                .orElseThrow(() -> new NoSuchElementException(
                        "No anchor for dataset " + id + " under strategy " + strategy.name()));
        if (!anchor.getParameters().equals(strategy.describe())) {
            throw new IllegalArgumentException("The latest anchor was made with " + anchor.getParameters()
                    + "; verify with the same parameters (got " + strategy.describe() + ")");
        }
        MerkleForest forest = forests.forest(datasets.stream(id), strategy);
        return new Dto.AnchorCheck(Dto.AnchorView.of(anchor), forest.superRootHex(), forest.entryCount(),
                forest.superRootHex().equals(anchor.getSuperRoot()));
    }
}
