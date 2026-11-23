package com.lexorma.data.mapping.repository;

import com.lexorma.data.mapping.domain.entity.AttributeColumnMapping;
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

    /** 여러 속성의 직접 매핑과 그 컬럼·테이블 (물리 생성의 상속 조상 검증 — 속성마다 따로 읽지 않는다, M-7 2차) */
    @Query("select a from AttributeColumnMapping a join fetch a.column c left join fetch c.tableInfo where a.attribute.id in :attributeIds")
    java.util.List<AttributeColumnMapping> findAllByAttributeIds(@Param("attributeIds") Collection<Long> attributeIds);

    Optional<AttributeColumnMapping> findByColumn_Id(Long columnId);

    /** 컬럼 삭제 차단 */
    Optional<AttributeColumnMapping> findFirstByColumn_IdIn(Collection<Long> columnIds);

    /** 엔터티들의 속성이 쓰인 매핑 수 (상속 변환 기록 삭제 의존성 — D-AC-18) */
    @org.springframework.data.jpa.repository.Query("select count(m) from AttributeColumnMapping m where m.attribute.entity.id in :ids")
    long countByAttributeEntityIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
