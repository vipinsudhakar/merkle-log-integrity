package com.merklelog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LogEntryRepository extends JpaRepository<LogEntryEntity, LogEntryEntity.Key> {

    /** Entries in stream order. Order matters: the Merkle root commits to it. */
    @Query("select e from LogEntryEntity e where e.key.datasetId = :datasetId order by e.key.position")
    List<LogEntryEntity> findStream(long datasetId);
}
