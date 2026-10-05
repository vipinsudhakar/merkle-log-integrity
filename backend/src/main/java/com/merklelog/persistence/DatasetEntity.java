package com.merklelog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A stored log stream (table {@code datasets}, Flyway V1). */
@Entity
@Table(name = "datasets")
public class DatasetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** The {@code SyntheticLogGenerator} seed, so the stream can be regenerated exactly. */
    @Column(nullable = false)
    private long seed;

    @Column(nullable = false)
    private int size;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DatasetEntity() {
        // for JPA
    }

    public DatasetEntity(String name, long seed, int size) {
        this.name = name;
        this.seed = seed;
        this.size = size;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getSeed() {
        return seed;
    }

    public int getSize() {
        return size;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
