package com.lexorma.source.catalog.repository;

import com.lexorma.source.catalog.domain.entity.SourceViewDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SourceViewDefinitionRepository extends JpaRepository<SourceViewDefinition, Long> {

    Optional<SourceViewDefinition> findByTableIdAndProjectId(Long tableId, Long projectId);

    /** 원문 없이 상태만 : [VIEW ID, 상태, 해시, 길이, 수집 일시, 변경 반영 순서] */
    @Query("select d.tableId, d.status, d.hash, d.length, d.collectedAt, d.changedOrder from SourceViewDefinition d where d.projectId = :projectId and d.tableId in :tableIds")
    List<Object[]> summaries(@Param("projectId") Long projectId, @Param("tableIds") Collection<Long> tableIds);

    /** 수집 반영용 : [행 ID, VIEW ID, 상태, 해시] (원문은 읽지 않는다) */
    @Query("select d.id, d.tableId, d.status, d.hash from SourceViewDefinition d where d.connectionId = :connectionId")
    List<Object[]> statesOf(@Param("connectionId") Long connectionId);
}
