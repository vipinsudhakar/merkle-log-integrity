package com.merklelog.api;

import com.merklelog.core.LogEntry;
import com.merklelog.demo.SyntheticLogGenerator;
import com.merklelog.persistence.DatasetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** Datasets (stored log streams), their entries, and the seed endpoint. */
@RestController
@RequestMapping("/api")
public class DatasetController {

    private static final int MAX_PAGE = 500;

    private final DatasetService datasets;

    public DatasetController(DatasetService datasets) {
        this.datasets = datasets;
    }

    @GetMapping("/datasets")
    public List<Dto.DatasetInfo> list() {
        return datasets.list().stream().map(Dto.DatasetInfo::of).toList();
    }

    @GetMapping("/datasets/{id}")
    public Dto.DatasetInfo get(@PathVariable long id) {
        return Dto.DatasetInfo.of(datasets.get(id));
    }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    public Dto.DatasetInfo create(@Valid @RequestBody Dto.CreateDatasetRequest request) {
        long seed = request.seed() == null ? SyntheticLogGenerator.DEFAULT_SEED : request.seed();
        return Dto.DatasetInfo.of(datasets.create(request.name(), request.size(), seed));
    }

    /** A page of entries with their leaf hashes, for the entry table and the proof view. */
    @GetMapping("/datasets/{id}/entries")
    public Dto.EntryPage entries(@PathVariable long id,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "100") int limit) {
        List<LogEntry> stream = datasets.stream(id);
        if (offset < 0 || limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("offset must be >= 0 and limit in [1, " + MAX_PAGE + "]");
        }
        int end = Math.min(stream.size(), offset + limit);
        List<Dto.EntryView> page = new ArrayList<>();
        for (int i = offset; i < end; i++) {
            page.add(Dto.EntryView.of(i, stream.get(i)));
        }
        return new Dto.EntryPage(offset, stream.size(), page);
    }

    /**
     * Overwrites a stored entry's message in the database, as an attacker with write access
     * would. Nothing else changes, in particular not the anchored super-roots, so
     * {@code GET /api/datasets/{id}/anchors/verify} detects it.
     */
    @PutMapping("/datasets/{id}/entries/{position}")
    public Dto.EntryView overwrite(@PathVariable long id, @PathVariable int position,
                                   @Valid @RequestBody Dto.OverwriteRequest request) {
        return Dto.EntryView.of(position, datasets.overwriteMessage(id, position, request.message()));
    }

    /**
     * Restores one stored entry to its original content, regenerated from the dataset's seed:
     * the "undo" of {@link #overwrite}, correct even if the entry was tampered with more than once.
     */
    @PostMapping("/datasets/{id}/entries/{position}/restore")
    public Dto.EntryView restore(@PathVariable long id, @PathVariable int position) {
        return Dto.EntryView.of(position, datasets.restoreEntry(id, position));
    }

    /** Deletes everything and recreates the demo datasets from their fixed seed. */
    @PostMapping("/admin/seed")
    public List<Dto.DatasetInfo> seed() {
        return datasets.seedDemoData().stream().map(Dto.DatasetInfo::of).toList();
    }
}
