package com.lexorma.data.physical.repository;

import com.lexorma.data.physical.domain.entity.TableRelation;
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

    /** 여러 테이블에서 나가는 관계와 컬럼 쌍 (상속 PK 의 부모 FK 확인 — 테이블마다 따로 읽지 않는다, M-7 3차) */
    @Query("select distinct r from TableRelation r left join fetch r.columns c where r.sourceTable.id in :ids")
    List<TableRelation> findAllBySourceTableIds(@Param("ids") Collection<Long> tableIds);

    /** 컬럼 삭제 전 확인 : 원본 또는 대상 컬럼으로 쓰는 관계 */
    @Query("select distinct r from TableRelation r join r.columns c where c.sourceColumn.id in :ids or c.targetColumn.id in :ids")
    List<TableRelation> findAllUsingColumns(@Param("ids") Collection<Long> columnIds);
}
