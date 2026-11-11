package com.koboolean.metamora.data.mapping.repository;

import com.koboolean.metamora.data.mapping.domain.entity.AttributeColumnMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface AttributeColumnMappingRepository extends JpaRepository<AttributeColumnMapping, Long> {

    /** 속성 매핑의 부모(엔터티 ↔ 테이블 매핑) ID - 로그인 프로젝트 것만 */
    @Query("select a.mapping.id from AttributeColumnMapping a where a.id = :id and a.mapping.projectId = :projectId")
    Optional<Long> findParentId(@Param("id") Long id, @Param("projectId") Long projectId);

    boolean existsByMapping_Id(Long mappingId);

    Optional<AttributeColumnMapping> findByAttribute_Id(Long attributeId);

    Optional<AttributeColumnMapping> findByColumn_Id(Long columnId);

    /** 컬럼 삭제 차단 */
    Optional<AttributeColumnMapping> findFirstByColumn_IdIn(Collection<Long> columnIds);
}
