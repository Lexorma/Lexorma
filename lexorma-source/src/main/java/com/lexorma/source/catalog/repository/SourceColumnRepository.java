package com.lexorma.source.catalog.repository;

import com.lexorma.source.catalog.domain.entity.SourceColumn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceColumnRepository extends JpaRepository<SourceColumn, Long> {

    Optional<SourceColumn> findBySourceTable_IdAndCompareName(Long tableId, String compareName);

    List<SourceColumn> findAllBySourceTable_IdOrderByOrdinalAscIdAsc(Long tableId);
}
