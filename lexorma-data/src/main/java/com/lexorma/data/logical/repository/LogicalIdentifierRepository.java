package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LogicalIdentifierRepository extends JpaRepository<LogicalIdentifier, Long> {

    @Query("select i.entity.id from LogicalIdentifier i where i.id = :id and i.entity.projectId = :projectId")
    Optional<Long> findEntityId(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 논리 ERD : 모델의 식별자와 구성 속성을 한 번에 */
    @Query("select distinct i from LogicalIdentifier i join fetch i.attributes ia join fetch ia.attribute where i.entity.model.id = :modelId")
    List<LogicalIdentifier> findAllByModelId(@Param("modelId") Long modelId);
}
