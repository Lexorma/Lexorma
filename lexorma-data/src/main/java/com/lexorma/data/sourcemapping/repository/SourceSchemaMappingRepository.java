package com.lexorma.data.sourcemapping.repository;

import com.lexorma.data.sourcemapping.domain.entity.SourceSchemaMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SourceSchemaMappingRepository extends JpaRepository<SourceSchemaMapping, Long> {

    List<SourceSchemaMapping> findAllByProjectIdAndConnectionId(Long projectId, Long connectionId);

    Optional<SourceSchemaMapping> findByProjectIdAndConnectionIdAndSourceSchemaId(Long projectId, Long connectionId, Long sourceSchemaId);

    Optional<SourceSchemaMapping> findByProjectIdAndConnectionIdAndSchemaId(Long projectId, Long connectionId, Long schemaId);

    /** 기준이 아닌 연결에 남아 있는 매핑 수 (연결 → 수) */
    @Query("select m.connectionId, count(m) from SourceSchemaMapping m where m.projectId = :projectId group by m.connectionId")
    List<Object[]> countByConnection(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from SourceSchemaMapping m where m.projectId = :projectId and m.connectionId = :connectionId")
    int deleteByConnection(@Param("projectId") Long projectId, @Param("connectionId") Long connectionId);
}
