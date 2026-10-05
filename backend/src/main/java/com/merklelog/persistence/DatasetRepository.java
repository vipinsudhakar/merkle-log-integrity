package com.merklelog.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasetRepository extends JpaRepository<DatasetEntity, Long> {

    List<DatasetEntity> findAllByOrderByIdAsc();
}
