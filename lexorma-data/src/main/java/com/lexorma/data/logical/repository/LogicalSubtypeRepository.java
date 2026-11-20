package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalSubtype;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LogicalSubtypeRepository extends JpaRepository<LogicalSubtype, Long> {

    /** 모델의 서브타입 전체 (그룹 → 순서). 그룹·엔터티는 FK 값만 쓴다 */
    @Query("select m from LogicalSubtype m where m.modelId = :modelId order by m.group.id, m.sortOrder, m.id")
    List<LogicalSubtype> findAllByModelId(@Param("modelId") Long modelId);

    /** 모델의 계층 연결만 [그룹 ID, 서브타입 엔터티 ID] (그룹 → 순서) */
    @Query("select m.group.id, m.subtypeEntity.id from LogicalSubtype m where m.modelId = :modelId order by m.group.id, m.sortOrder, m.id")
    List<Object[]> findLinksByModelId(@Param("modelId") Long modelId);

    @Query("select m.group.id, m.subtypeEntity.id from LogicalSubtype m where m.modelId in :modelIds order by m.group.id, m.sortOrder, m.id")
    List<Object[]> findLinksByModelIds(@Param("modelIds") Collection<Long> modelIds);


    /** 그룹들의 구성원 (서브타입 엔터티와 함께, 그룹 → 순서) */
    @Query("select m from LogicalSubtype m join fetch m.subtypeEntity where m.group.id in :groupIds order by m.group.id, m.sortOrder, m.id")
    List<LogicalSubtype> findWithEntities(@Param("groupIds") Collection<Long> groupIds);

    /** 그룹들의 구성원 중 고른 엔터티의 [서브타입 엔터티 ID, 분류 값] (논리 ERD 범위 안만 — 구성원이 많은 그룹도 범위 밖 구성원·엔터티 행을 읽지 않는다, D-51) */
    @Query("select m.subtypeEntity.id, m.classificationValue from LogicalSubtype m where m.group.id in :groupIds and m.subtypeEntity.id in :entityIds")
    List<Object[]> findValues(@Param("groupIds") Collection<Long> groupIds, @Param("entityIds") Collection<Long> entityIds);

    /** 그룹별 구성원 수 (목록 페이지의 그룹만) */
    @Query("select m.group.id, count(m) from LogicalSubtype m where m.group.id in :groupIds group by m.group.id")
    List<Object[]> countByGroups(@Param("groupIds") Collection<Long> groupIds);
}
