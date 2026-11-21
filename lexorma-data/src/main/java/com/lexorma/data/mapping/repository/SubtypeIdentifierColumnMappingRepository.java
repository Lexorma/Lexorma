package com.lexorma.data.mapping.repository;

import com.lexorma.data.mapping.domain.entity.SubtypeIdentifierColumnMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubtypeIdentifierColumnMappingRepository extends JpaRepository<SubtypeIdentifierColumnMapping, Long> {

    /** 상속 PK 매핑의 부모(엔터티 ↔ 테이블 매핑) ID - 로그인 프로젝트 것만 */
    @Query("select s.mapping.id from SubtypeIdentifierColumnMapping s where s.id = :id and s.mapping.projectId = :projectId")
    Optional<Long> findParentId(@Param("id") Long id, @Param("projectId") Long projectId);

    boolean existsByMapping_Id(Long mappingId);

    /** 엔터티 매핑 하나의 상속 PK 매핑과 속성·컬럼 (ID 순) */
    @Query("select s from SubtypeIdentifierColumnMapping s join fetch s.attribute a join fetch a.entity join fetch s.column c where s.mapping.id = :mappingId order by s.id")
    List<SubtypeIdentifierColumnMapping> findAllByMappingId(@Param("mappingId") Long mappingId);

    /** 여러 엔터티 매핑의 상속 PK 매핑과 컬럼·테이블 (물리 생성의 기존 조상 검증 — 매핑마다 따로 읽지 않는다) */
    @Query("select s from SubtypeIdentifierColumnMapping s join fetch s.column c left join fetch c.tableInfo where s.mapping.id in :mappingIds order by s.id")
    List<SubtypeIdentifierColumnMapping> findAllByMappingIds(@Param("mappingIds") Collection<Long> mappingIds);

    /** 논리 모델 하나의 상속 PK 매핑 전체와 속성·컬럼 (정합성 비교 — M-7 4차, 모델당 한 번) */
    @Query("select s from SubtypeIdentifierColumnMapping s join fetch s.mapping m join fetch s.attribute a join fetch s.column c "
            + "where m.projectId = :projectId and m.entity.model.id = :modelId order by s.id")
    List<SubtypeIdentifierColumnMapping> findAllByModelId(@Param("projectId") Long projectId, @Param("modelId") Long modelId);

    Optional<SubtypeIdentifierColumnMapping> findByColumn_Id(Long columnId);

    Optional<SubtypeIdentifierColumnMapping> findByMapping_IdAndAttribute_Id(Long mappingId, Long attributeId);

    /** 컬럼 삭제 차단 */
    Optional<SubtypeIdentifierColumnMapping> findFirstByColumn_IdIn(Collection<Long> columnIds);

    /** 원본 속성 삭제 차단 */
    Optional<SubtypeIdentifierColumnMapping> findFirstByAttribute_IdIn(Collection<Long> attributeIds);
}
