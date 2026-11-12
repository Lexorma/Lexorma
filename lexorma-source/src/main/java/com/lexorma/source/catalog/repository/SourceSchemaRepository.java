package com.lexorma.source.catalog.repository;

import com.lexorma.source.catalog.domain.entity.SourceSchema;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SourceSchemaRepository extends JpaRepository<SourceSchema, Long> {

    Optional<SourceSchema> findByIdAndProjectId(Long id, Long projectId);

    Optional<SourceSchema> findByConnection_IdAndPathKey(Long connectionId, String pathKey);

    Page<SourceSchema> findAllByProjectIdAndConnection_Id(Long projectId, Long connectionId, Pageable pageable);

    long countByConnection_Id(Long connectionId);
}
