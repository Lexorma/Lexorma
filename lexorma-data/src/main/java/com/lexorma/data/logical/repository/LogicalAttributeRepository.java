package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LogicalAttributeRepository extends JpaRepository<LogicalAttribute, Long> {

    boolean existsByStandardTerm_Id(Long standardTermId);

    @Query("select a.entity.id from LogicalAttribute a where a.id = :id and a.entity.projectId = :projectId")
    Optional<Long> findEntityId(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 여러 엔터티의 속성 수 (매핑률 — C형 분산 엔터티의 속성을 분모에서 뺀다) */
    @org.springframework.data.jpa.repository.Query("select count(a) from LogicalAttribute a where a.entity.id in :ids")
    long countByEntityIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
