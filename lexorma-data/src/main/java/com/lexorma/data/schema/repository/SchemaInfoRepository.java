package com.lexorma.data.schema.repository;

import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** 스키마 행 잠금 : 그 스키마의 TABLE·VIEW 이름 공간 잠금으로 쓴다 ({@code ObjectNamespace}). 기본 스키마 행은 프로젝트의 VIEW 참조 그래프 잠금도 겸한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SchemaInfo s where s.id = :id and s.projectId = :projectId")
    Optional<SchemaInfo> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    boolean existsByProjectIdAndSchemaNameIgnoreCase(Long projectId, String schemaName);

    Optional<SchemaInfo> findFirstByProjectIdAndSchemaNameIgnoreCase(Long projectId, String schemaName);
}
