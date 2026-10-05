package com.merklelog.persistence;

import com.merklelog.core.LogEntry;
import com.merklelog.demo.SyntheticLogGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Overwrite and restore against a real PostgreSQL database.
 *
 * <p>Opt-in, because {@code mvn test} must not need a database: run with
 * {@code MERKLELOG_DB_TESTS=true} and the local database from {@code backend/config/}.
 *
 * <p>Regression: {@code overwriteMessage} used to re-read the stream through JPA inside its own
 * transaction. JPA then returned the entities it had already loaded, still holding the old
 * message, and that stale copy was cached, so later verifications saw the log as unchanged.
 */
@SpringBootTest(properties = "app.seed-on-startup=false")
@EnabledIfEnvironmentVariable(named = "MERKLELOG_DB_TESTS", matches = "true")
@DisplayName("DatasetService — overwrite and restore against PostgreSQL")
class DatasetServiceDatabaseTest {

    @Autowired
    private DatasetService service;
    @Autowired
    private DatasetRepository datasets;
    @Autowired
    private LogEntryRepository entries;
    @Autowired
    private RootAnchorRepository anchors;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(datasets::deleteById); // log entries cascade
    }

    private long newDataset() {
        long id = service.create("db-test", 50, SyntheticLogGenerator.DEFAULT_SEED).getId();
        created.add(id);
        return id;
    }

    @Test
    @DisplayName("an overwrite is visible to the next read, even when the stream was loaded in the same transaction")
    void overwriteIsNeverStale() {
        long id = newDataset();
        // A second service instance starts with an empty cache, so its first read goes through JPA,
        // inside the transaction, exactly as in the bug.
        DatasetService fresh = new DatasetService(datasets, entries, anchors, jdbc);

        LogEntry returned = tx.execute(status -> {
            fresh.stream(id);
            return fresh.overwriteMessage(id, 10, "PAYMENT APPROVED to account 4242");
        });

        assertThat(returned.message()).isEqualTo("PAYMENT APPROVED to account 4242");
        assertThat(fresh.stream(id).get(10).message()).isEqualTo("PAYMENT APPROVED to account 4242");
        // And the database itself holds the new message: a newly started service reads it back.
        assertThat(new DatasetService(datasets, entries, anchors, jdbc).stream(id).get(10).message())
                .isEqualTo("PAYMENT APPROVED to account 4242");
    }

    @Test
    @DisplayName("restore brings back the generator's original, even after repeated tampering")
    void restoreUsesTheSeed() {
        long id = newDataset();
        String original = SyntheticLogGenerator.generate(50).get(10).message();

        service.overwriteMessage(id, 10, "first attack");
        service.overwriteMessage(id, 10, "second attack");
        LogEntry restored = service.restoreEntry(id, 10);

        assertThat(restored.message()).isEqualTo(original);
        assertThat(service.stream(id).get(10).message()).isEqualTo(original);
        assertThat(service.stream(id)).isEqualTo(SyntheticLogGenerator.generate(50));
    }
}
