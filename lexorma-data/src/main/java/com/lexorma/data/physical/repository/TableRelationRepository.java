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

    /**
     * 여러 테이블에서 나가는 FK 의 컬럼 쌍 (투영 — 엔티티를 읽지 않는다, M-7 4차 상속 구조 정합성) : 관계 ID·이름·출처·원본 테이블·대상 테이블 ID·이름·
     * 원본 컬럼 ID·이름·대상 컬럼 ID·이름·관계 컬럼 ID. 관계 ID·컬럼 순서 정렬, 컬럼 쌍이 없는 관계는 관계 컬럼 ID 가 null 인 1행
     */
    @Query("select r.id, r.relationName, r.relationSource, r.sourceTable.id, t.id, t.tableName, sc.id, sc.columnName, tc.id, tc.columnName, x.id "
            + "from TableRelation r left join r.targetTable t left join r.columns x left join x.sourceColumn sc left join x.targetColumn tc "
            + "where r.sourceTable.id in :ids and r.relationType = com.lexorma.data.physical.domain.entity.TableRelation.Type.FK order by r.id, x.columnOrder")
    List<Object[]> findForeignKeyPairs(@Param("ids") Collection<Long> tableIds);

    /** {@link #findForeignKeyPairs} 의 모델 단위 : 그 논리 모델의 엔터티에 매핑된 테이블에서 나가는 FK (정합성 비교 — 긴 IN 목록 대신) */
    @Query("select r.id, r.relationName, r.relationSource, r.sourceTable.id, t.id, t.tableName, sc.id, sc.columnName, tc.id, tc.columnName, x.id "
            + "from TableRelation r left join r.targetTable t left join r.columns x left join x.sourceColumn sc left join x.targetColumn tc "
            + "where r.relationType = com.lexorma.data.physical.domain.entity.TableRelation.Type.FK and r.sourceTable.id in "
            + "(select m.table.id from com.lexorma.data.mapping.domain.entity.EntityTableMapping m where m.projectId = :projectId and m.entity.model.id = :modelId) "
            + "order by r.id, x.columnOrder")
    List<Object[]> findForeignKeyPairsByModel(@Param("projectId") Long projectId, @Param("modelId") Long modelId);

    /** 컬럼 삭제 전 확인 : 원본 또는 대상 컬럼으로 쓰는 관계 */
    @Query("select distinct r from TableRelation r join r.columns c where c.sourceColumn.id in :ids or c.targetColumn.id in :ids")
    List<TableRelation> findAllUsingColumns(@Param("ids") Collection<Long> columnIds);
}
