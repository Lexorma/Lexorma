package com.lexorma.data.schema.repository;

import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SchemaInfoRepository extends JpaRepository<SchemaInfo, Long> {

    List<SchemaInfo> findAllByProjectIdOrderByIsDefaultDescSchemaNameAsc(Long projectId);

    /** 스키마 관리 목록 : 소속 테이블 수와 등록·수정 정보 포함 (기본 스키마 먼저) */
    @Query("select new com.lexorma.data.schema.domain.dto.SchemaDto(s.id, s.schemaName, s.schemaDescription, s.isDefault, " +
           "count(t.id), s.created, s.updated, s.createdBy, s.updatedBy) " +
           "from SchemaInfo s left join TableInfo t on t.schema = s " +
           "where s.projectId = :projectId " +
           "group by s.id, s.schemaName, s.schemaDescription, s.isDefault, s.created, s.updated, s.createdBy, s.updatedBy " +
           "order by s.isDefault desc, s.schemaName asc")
    List<SchemaDto> findSchemaList(@Param("projectId") Long projectId);

    Optional<SchemaInfo> findByIdAndProjectId(Long id, Long projectId);

    Optional<SchemaInfo> findFirstByProjectIdAndIsDefaultTrue(Long projectId);

    boolean existsByProjectIdAndSchemaNameIgnoreCase(Long projectId, String schemaName);
}
