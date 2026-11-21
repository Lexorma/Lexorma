package com.lexorma.data.mapping.repository;

import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EntityTableMappingRepository extends JpaRepository<EntityTableMapping, Long> {

    Optional<EntityTableMapping> findByIdAndProjectId(Long id, Long projectId);

    /** 잠글 논리 모델을 찾기 위한 엔터티 ID (매핑을 영속성 컨텍스트에 올리지 않는다) */
    @Query("select m.entity.id from EntityTableMapping m where m.id = :id and m.projectId = :projectId")
    Optional<Long> findEntityId(@Param("id") Long id, @Param("projectId") Long projectId);

    Optional<EntityTableMapping> findByEntity_Id(Long entityId);

    Optional<EntityTableMapping> findByTable_Id(Long tableId);

    /** 논리 → 물리 생성 : 여러 엔터티의 매핑과 테이블·스키마를 한 번에 */
    @Query("select m from EntityTableMapping m join fetch m.table t join fetch t.schema where m.entity.id in :entityIds")
    List<EntityTableMapping> findAllByEntityIds(@Param("entityIds") java.util.Collection<Long> entityIds);

    /** 논리 모델 하나의 엔터티 매핑과 테이블·스키마 (정합성 비교 — 모델 전체를 볼 때 긴 IN 목록 대신, M-7 4차) */
    @Query("select m from EntityTableMapping m join fetch m.table t join fetch t.schema where m.projectId = :projectId and m.entity.model.id = :modelId")
    List<EntityTableMapping> findAllByModelId(@Param("projectId") Long projectId, @Param("modelId") Long modelId);
}
