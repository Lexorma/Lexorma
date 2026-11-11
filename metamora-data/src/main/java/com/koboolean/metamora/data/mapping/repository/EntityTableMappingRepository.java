package com.koboolean.metamora.data.mapping.repository;

import com.koboolean.metamora.data.mapping.domain.entity.EntityTableMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EntityTableMappingRepository extends JpaRepository<EntityTableMapping, Long> {

    Optional<EntityTableMapping> findByIdAndProjectId(Long id, Long projectId);

    /** 잠글 논리 모델을 찾기 위한 엔터티 ID (매핑을 영속성 컨텍스트에 올리지 않는다) */
    @Query("select m.entity.id from EntityTableMapping m where m.id = :id and m.projectId = :projectId")
    Optional<Long> findEntityId(@Param("id") Long id, @Param("projectId") Long projectId);

    Optional<EntityTableMapping> findByEntity_Id(Long entityId);

    Optional<EntityTableMapping> findByTable_Id(Long tableId);
}
