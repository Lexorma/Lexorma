package com.koboolean.metamora.data.logical.repository;

import com.koboolean.metamora.data.logical.domain.entity.LogicalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LogicalEntityRepository extends JpaRepository<LogicalEntity, Long> {

    List<LogicalEntity> findAllByModel_IdOrderByEntityNameAscIdAsc(Long modelId);

    Optional<LogicalEntity> findByIdAndProjectId(Long id, Long projectId);

    @Query("select e.model.id from LogicalEntity e where e.id = :id and e.projectId = :projectId")
    Optional<Long> findModelId(@Param("id") Long id, @Param("projectId") Long projectId);

    boolean existsByModel_Id(Long modelId);

    @Query("select e.id from LogicalEntity e where e.model.id = :modelId")
    Set<Long> findIdsByModelId(@Param("modelId") Long modelId);

    boolean existsByModel_IdAndEntityNameIgnoreCaseAndIdNot(Long modelId, String entityName, Long id);

    /** 논리 ERD : 모델의 엔터티와 속성·표준 용어·도메인을 한 번에 */
    @Query("select distinct e from LogicalEntity e left join fetch e.attributes a left join fetch a.standardTerm t left join fetch t.standardDomain "
            + "where e.model.id = :modelId order by e.entityName, e.id")
    List<LogicalEntity> findGraph(@Param("modelId") Long modelId);
}
