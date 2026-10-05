package com.merklelog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RootAnchorRepository extends JpaRepository<RootAnchorEntity, Long> {

    List<RootAnchorEntity> findByDatasetIdOrderByIdDesc(long datasetId);

    Optional<RootAnchorEntity> findFirstByDatasetIdAndStrategyOrderByIdDesc(long datasetId, String strategy);
}
