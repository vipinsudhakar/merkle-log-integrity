package com.merklelog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A published super-root: the base paper's "trusted anchor" (Yağız et al. 2026, §3.1) made concrete
 * (table {@code root_anchors}, Flyway V1).
 *
 * <p>Verifying the log later means recomputing the super-root and comparing it with the anchored
 * one. In a real deployment the anchor lives somewhere the log's owner cannot rewrite (an HSM, a
 * TPM, a remote append-only store); here it is a separate table, enough to demonstrate the check.
 */
@Entity
@Table(name = "root_anchors")
public class RootAnchorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private long datasetId;

    @Column(nullable = false)
    private String strategy;

    /** The strategy's parameters, as {@code ChunkingStrategy.describe()} prints them. */
    @Column(nullable = false)
    private String parameters;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    // CHAR(64) in the schema: always exactly 64 hex characters.
    @Column(name = "super_root", nullable = false, length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String superRoot;

    @Column(name = "anchored_at", nullable = false, updatable = false)
    private Instant anchoredAt = Instant.now();

    protected RootAnchorEntity() {
        // for JPA
    }

    public RootAnchorEntity(long datasetId, String strategy, String parameters,
                            int entryCount, int chunkCount, String superRoot) {
        this.datasetId = datasetId;
        this.strategy = strategy;
        this.parameters = parameters;
        this.entryCount = entryCount;
        this.chunkCount = chunkCount;
        this.superRoot = superRoot;
    }

    public Long getId() {
        return id;
    }

    public long getDatasetId() {
        return datasetId;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getParameters() {
        return parameters;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public String getSuperRoot() {
        return superRoot;
    }

    public Instant getAnchoredAt() {
        return anchoredAt;
    }
}
