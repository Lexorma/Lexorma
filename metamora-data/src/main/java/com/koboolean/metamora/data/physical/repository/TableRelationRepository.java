package com.koboolean.metamora.data.physical.repository;

import com.koboolean.metamora.data.physical.domain.entity.TableRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TableRelationRepository extends JpaRepository<TableRelation, Long> {

    Optional<TableRelation> findByIdAndProjectId(Long id, Long projectId);

    List<TableRelation> findAllByProjectIdAndSourceTable_Id(Long projectId, Long tableId);

    List<TableRelation> findAllByProjectIdAndTargetTable_Id(Long projectId, Long tableId);

    List<TableRelation> findAllByDerivedColumnIdIn(Collection<Long> columnIds);

    /** 관계 동기화 : 이 테이블들을 대상으로 하는 특정 출처의 관계 */
    List<TableRelation> findAllByProjectIdAndRelationSourceAndTargetTable_IdIn(Long projectId, TableRelation.Source relationSource, Collection<Long> tableIds);

    /** 컬럼 삭제 전 확인 : 원본 또는 대상 컬럼으로 쓰는 관계 */
    @Query("select distinct r from TableRelation r join r.columns c where c.sourceColumn.id in :ids or c.targetColumn.id in :ids")
    List<TableRelation> findAllUsingColumns(@Param("ids") Collection<Long> columnIds);
}
