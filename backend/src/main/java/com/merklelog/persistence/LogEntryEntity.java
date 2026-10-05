package com.merklelog.persistence;

import com.merklelog.core.LogEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One stored log entry (table {@code log_entries}, Flyway V1).
 *
 * <p>Every field of {@link LogEntry} is stored exactly, because every field is part of the bytes
 * that get hashed: if any of them changed on the way through the database, the leaf hash and every
 * root above it would change too.
 */
@Entity
@Table(name = "log_entries")
public class LogEntryEntity {

    @EmbeddedId
    private Key key;

    @Column(name = "entry_id", nullable = false)
    private long entryId;

    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;

    @Column(nullable = false)
    private String level;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String message;

    protected LogEntryEntity() {
        // for JPA
    }

    public LogEntryEntity(long datasetId, int position, LogEntry entry) {
        this.key = new Key(datasetId, position);
        this.entryId = entry.id();
        this.loggedAt = entry.timestamp();
        this.level = entry.level();
        this.source = entry.source();
        this.message = entry.message();
    }

    /** Back to the engine's immutable record. */
    public LogEntry toLogEntry() {
        return new LogEntry(entryId, loggedAt, level, source, message);
    }

    public int getPosition() {
        return key.position;
    }

    /** Composite primary key: {@code (dataset_id, position)}. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "dataset_id", nullable = false)
        private long datasetId;

        @Column(nullable = false)
        private int position;

        protected Key() {
            // for JPA
        }

        public Key(long datasetId, int position) {
            this.datasetId = datasetId;
            this.position = position;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && datasetId == other.datasetId && position == other.position;
        }

        @Override
        public int hashCode() {
            return Objects.hash(datasetId, position);
        }
    }
}
