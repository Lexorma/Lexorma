package com.lexorma.source.catalog.repository;

import com.lexorma.source.catalog.domain.entity.SourceIndex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceIndexRepository extends JpaRepository<SourceIndex, Long> {

    List<SourceIndex> findAllBySourceTable_IdOrderByIdAsc(Long tableId);
}
