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
}
