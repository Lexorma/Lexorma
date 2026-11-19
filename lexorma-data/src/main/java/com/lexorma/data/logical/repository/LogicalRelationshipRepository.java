package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LogicalRelationshipRepository extends JpaRepository<LogicalRelationship, Long> {

    @Query("select r from LogicalRelationship r join fetch r.sourceEntity join fetch r.targetEntity where r.model.id = :modelId order by r.id")
    List<LogicalRelationship> findAllByModelId(@Param("modelId") Long modelId);

    Optional<LogicalRelationship> findByIdAndProjectId(Long id, Long projectId);

    /** 논리 ERD 부분 조회 : 고른 엔터티가 한쪽이라도 걸린 관계의 (관계 ID, 출발 엔터티 ID, 도착 엔터티 ID) — 이웃 탐색·범위 밖 관계 수 */
    @Query("select r.id, r.sourceEntity.id, r.targetEntity.id from LogicalRelationship r where r.model.id = :modelId"
            + " and (r.sourceEntity.id in :ids or r.targetEntity.id in :ids)")
    List<Object[]> findLinks(@Param("modelId") Long modelId, @Param("ids") java.util.Collection<Long> ids);

    /** 논리 ERD 부분 조회 : 관계 ID 로 (양 끝 엔터티와 함께) — 범위 안 관계는 findLinks 결과에서 고른다 */
    @Query("select r from LogicalRelationship r join fetch r.sourceEntity join fetch r.targetEntity where r.id in :ids order by r.id")
    List<LogicalRelationship> findWithEntities(@Param("ids") java.util.Collection<Long> ids);

    @Query("select r.model.id from LogicalRelationship r where r.id = :id and r.projectId = :projectId")
    Optional<Long> findModelId(@Param("id") Long id, @Param("projectId") Long projectId);

    Optional<LogicalRelationship> findFirstBySourceEntity_IdOrTargetEntity_Id(Long sourceEntityId, Long targetEntityId);

    // ---- 논리 모델 관리 (M-6 후속 3차) : 관계 목록 페이지(ID 순, 예전 전체 목록과 같은 순서)·모델 관계 수·엔터티의 관계

    @Query(value = "select r.id from LogicalRelationship r where r.model.id = :modelId order by r.id",
            countQuery = "select count(r) from LogicalRelationship r where r.model.id = :modelId")
    org.springframework.data.domain.Page<Long> findIdPage(@Param("modelId") Long modelId, org.springframework.data.domain.Pageable pageable);

    long countByModel_Id(Long modelId);

    /** 출발·도착 어느 쪽이든 이 엔터티인 관계 (자기 참조는 1번) */
    @Query("select r from LogicalRelationship r join fetch r.sourceEntity join fetch r.targetEntity"
            + " where r.sourceEntity.id = :entityId or r.targetEntity.id = :entityId order by r.id")
    List<LogicalRelationship> findAllByEntityId(@Param("entityId") Long entityId);
}
