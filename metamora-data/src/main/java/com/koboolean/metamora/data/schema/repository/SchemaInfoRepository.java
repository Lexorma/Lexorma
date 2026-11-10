package com.koboolean.metamora.data.schema.repository;

import com.koboolean.metamora.data.schema.domain.entity.SchemaInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchemaInfoRepository extends JpaRepository<SchemaInfo, Long> {

    List<SchemaInfo> findAllByProjectIdOrderByIsDefaultDescSchemaNameAsc(Long projectId);

    Optional<SchemaInfo> findByIdAndProjectId(Long id, Long projectId);

    Optional<SchemaInfo> findFirstByProjectIdAndIsDefaultTrue(Long projectId);

    boolean existsByProjectIdAndSchemaNameIgnoreCase(Long projectId, String schemaName);
}
