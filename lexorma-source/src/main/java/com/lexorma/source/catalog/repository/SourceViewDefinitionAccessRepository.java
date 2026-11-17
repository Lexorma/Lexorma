package com.lexorma.source.catalog.repository;

import com.lexorma.source.catalog.domain.entity.SourceViewDefinitionAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceViewDefinitionAccessRepository extends JpaRepository<SourceViewDefinitionAccess, Long> {

    Page<SourceViewDefinitionAccess> findAllByProjectIdAndTableIdOrderByIdDesc(Long projectId, Long tableId, Pageable pageable);
}
