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

    @Query("select r.model.id from LogicalRelationship r where r.id = :id and r.projectId = :projectId")
    Optional<Long> findModelId(@Param("id") Long id, @Param("projectId") Long projectId);

    Optional<LogicalRelationship> findFirstBySourceEntity_IdOrTargetEntity_Id(Long sourceEntityId, Long targetEntityId);
}
