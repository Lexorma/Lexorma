package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalSubtypeGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogicalSubtypeGroupRepository extends JpaRepository<LogicalSubtypeGroup, Long> {

    /** 모델의 그룹 전체 (계층은 메모리에서 만든다 — 최대 3단계). 슈퍼타입·분류 속성은 FK 값만 쓴다 */
    @Query("select g from LogicalSubtypeGroup g where g.model.id = :modelId order by g.sortOrder, g.id")
    List<LogicalSubtypeGroup> findAllByModelId(@Param("modelId") Long modelId);

    /** 모델의 계층 연결만 [그룹 ID, 슈퍼타입 엔터티 ID] (조회 경로의 계층 — 그룹 행을 엔티티로 올리지 않는다) */
    @Query("select g.id, g.supertypeEntity.id from LogicalSubtypeGroup g where g.model.id = :modelId order by g.sortOrder, g.id")
    List<Object[]> findLinksByModelId(@Param("modelId") Long modelId);

    /** 여러 모델의 계층 연결 (물리 생성처럼 모델 여러 개를 한 번에 볼 때) */
    @Query("select g.id, g.supertypeEntity.id from LogicalSubtypeGroup g where g.model.id in :modelIds order by g.sortOrder, g.id")
    List<Object[]> findLinksByModelIds(@Param("modelIds") Collection<Long> modelIds);

    Optional<LogicalSubtypeGroup> findByIdAndProjectId(Long id, Long projectId);

    @Query("select g.model.id from LogicalSubtypeGroup g where g.id = :id and g.projectId = :projectId")
    Optional<Long> findModelId(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 분류 속성으로 쓰는 그룹 (속성 삭제 검사) */
    Optional<LogicalSubtypeGroup> findFirstByClassificationAttribute_Id(Long attributeId);

    /** 고른 그룹의 슈퍼타입·분류 속성(정의 엔터티 포함)을 함께 */
    @Query("select g from LogicalSubtypeGroup g join fetch g.supertypeEntity left join fetch g.classificationAttribute a left join fetch a.entity where g.id in :ids")
    List<LogicalSubtypeGroup> findWithEntities(@Param("ids") Collection<Long> ids);

    /** 모델의 그룹 → 분류 속성 [그룹 ID, 분류 속성 ID] (M-7 상속 A — 분류 컬럼 정합성) */
    @Query("select g.id, a.id from LogicalSubtypeGroup g left join g.classificationAttribute a where g.model.id = :modelId")
    List<Object[]> findClassificationByModelId(@Param("modelId") Long modelId);
}
