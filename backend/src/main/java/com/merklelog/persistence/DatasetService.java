package com.merklelog.persistence;

import com.merklelog.core.LogEntry;
import com.merklelog.demo.SyntheticLogGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores and loads log streams.
 *
 * <p>The database is disposable (Render's free Postgres expires every 30 days), so everything
 * here can be rebuilt from nothing: datasets are generated from a fixed seed by
 * {@link SyntheticLogGenerator}, and {@link #seedDemoData()} recreates them on an empty database.
 *
 * <p>Loaded streams are cached in memory, because every API call that builds a forest needs the
 * whole stream, and the stored rows only change through {@link #overwriteMessage} or a reseed,
 * which both clear the cache.
 */
@Service
public class DatasetService {

    /** Demo datasets created by the seed: small enough to draw, large enough to compare. */
    public static final List<Map.Entry<String, Integer>> DEMO_DATASETS = List.of(
            Map.entry("demo-512", 512),
            Map.entry("demo-2k", 2_000),
            Map.entry("demo-10k", 10_000));

    /** Largest dataset the API will generate; the benchmark's largest size. */
    public static final int MAX_SIZE = 100_000;

    private final DatasetRepository datasets;
    private final LogEntryRepository entries;
    private final RootAnchorRepository anchors;
    private final JdbcTemplate jdbc;
    private final Map<Long, List<LogEntry>> cache = new ConcurrentHashMap<>();

    public DatasetService(DatasetRepository datasets, LogEntryRepository entries,
                          RootAnchorRepository anchors, JdbcTemplate jdbc) {
        this.datasets = datasets;
        this.entries = entries;
        this.anchors = anchors;
        this.jdbc = jdbc;
    }

    public List<DatasetEntity> list() {
        return datasets.findAllByOrderByIdAsc();
    }

    public DatasetEntity get(long id) {
        return datasets.findById(id).orElseThrow(() -> new NoSuchElementException("No dataset with id " + id));
    }

    /** The stream in order, from the cache or the database. */
    public List<LogEntry> stream(long id) {
        get(id); // 404 for an unknown id, even if a stale cache entry existed
        return cache.computeIfAbsent(id, key ->
                entries.findStream(key).stream().map(LogEntryEntity::toLogEntry).toList());
    }

    /** Generates a stream with {@link SyntheticLogGenerator} and stores it. */
    @Transactional
    public DatasetEntity create(String name, int size, long seed) {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE + ", got " + size);
        }
        DatasetEntity dataset = datasets.save(new DatasetEntity(name, seed, size));
        List<LogEntry> stream = SyntheticLogGenerator.generate(size, seed);

        // Batched JDBC insert: tens of thousands of rows in one round trip per batch, instead of
        // one JPA save (and one existence check) per row.
        jdbc.batchUpdate(
                "INSERT INTO log_entries (dataset_id, position, entry_id, logged_at, level, source, message) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                stream, 1_000, (ps, entry) -> {
                    ps.setLong(1, dataset.getId());
                    ps.setInt(2, (int) entry.id() - 1);   // generator ids run 1..n
                    ps.setLong(3, entry.id());
                    ps.setTimestamp(4, Timestamp.from(entry.timestamp()));
                    ps.setString(5, entry.level());
                    ps.setString(6, entry.source());
                    ps.setString(7, entry.message());
                });
        cache.put(dataset.getId(), stream);
        return dataset;
    }

    /**
     * Rewrites one stored entry's message directly in the database: what an attacker with write
     * access to the log store would do. The anchored super-root is untouched, so verifying against
     * it afterwards must fail.
     */
    @Transactional
    public LogEntry overwriteMessage(long id, int position, String message) {
        List<LogEntry> stream = stream(id);
        if (position < 0 || position >= stream.size()) {
            throw new IndexOutOfBoundsException(
                    "Position " + position + " out of range [0, " + stream.size() + ")");
        }
        int updated = jdbc.update("UPDATE log_entries SET message = ? WHERE dataset_id = ? AND position = ?",
                message, id, position);
        if (updated != 1) {
            throw new IllegalStateException("Expected to update one row, updated " + updated);
        }
        cache.remove(id);
        return stream(id).get(position);
    }

    /** Deletes everything and recreates the demo datasets: the from-empty seed path. */
    @Transactional
    public List<DatasetEntity> seedDemoData() {
        anchors.deleteAllInBatch();
        entries.deleteAllInBatch();
        datasets.deleteAllInBatch();
        cache.clear();
        return DEMO_DATASETS.stream()
                .map(demo -> create(demo.getKey(), demo.getValue(), SyntheticLogGenerator.DEFAULT_SEED))
                .toList();
    }

    public boolean isEmpty() {
        return datasets.count() == 0;
    }
}
