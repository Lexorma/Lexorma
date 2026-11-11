package com.koboolean.metamora.data.logical.repository;

import com.koboolean.metamora.data.logical.domain.entity.LogicalAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LogicalAttributeRepository extends JpaRepository<LogicalAttribute, Long> {

    boolean existsByStandardTerm_Id(Long standardTermId);

    @Query("select a.entity.id from LogicalAttribute a where a.id = :id and a.entity.projectId = :projectId")
    Optional<Long> findEntityId(@Param("id") Long id, @Param("projectId") Long projectId);
}
