package com.lexorma.data.mapping.repository;

import com.lexorma.data.mapping.domain.entity.SubtypeAttributeColumnMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubtypeAttributeColumnMappingRepository extends JpaRepository<SubtypeAttributeColumnMapping, Long> {

    /** 상속 일반 속성 매핑의 부모(엔터티 ↔ 테이블 매핑) ID - 로그인 프로젝트 것만 */
    @Query("select s.mapping.id from SubtypeAttributeColumnMapping s where s.id = :id and s.mapping.projectId = :projectId")
    Optional<Long> findParentId(@Param("id") Long id, @Param("projectId") Long projectId);

    boolean existsByMapping_Id(Long mappingId);

    /** 엔터티 매핑 하나의 상속 일반 속성 매핑과 속성·컬럼 (ID 순) */
    @Query("select s from SubtypeAttributeColumnMapping s join fetch s.attribute a join fetch a.entity join fetch s.column c where s.mapping.id = :mappingId order by s.id")
    List<SubtypeAttributeColumnMapping> findAllByMappingId(@Param("mappingId") Long mappingId);

    /** 여러 엔터티 매핑의 상속 일반 속성 매핑 (목록·생성 검사 — 매핑마다 따로 읽지 않는다) */
    @Query("select s from SubtypeAttributeColumnMapping s join fetch s.column c left join fetch c.tableInfo where s.mapping.id in :mappingIds order by s.id")
    List<SubtypeAttributeColumnMapping> findAllByMappingIds(@Param("mappingIds") Collection<Long> mappingIds);

    /** 논리 모델 하나의 상속 일반 속성 매핑 전체 (정합성 비교 — 모델당 한 번) */
    @Query("select s from SubtypeAttributeColumnMapping s join fetch s.mapping m join fetch s.attribute a join fetch s.column c "
            + "where m.projectId = :projectId and m.entity.model.id = :modelId order by s.id")
    List<SubtypeAttributeColumnMapping> findAllByModelId(@Param("projectId") Long projectId, @Param("modelId") Long modelId);

    Optional<SubtypeAttributeColumnMapping> findByColumn_Id(Long columnId);

    Optional<SubtypeAttributeColumnMapping> findByMapping_IdAndAttribute_Id(Long mappingId, Long attributeId);

    /** 컬럼 삭제 차단 */
    Optional<SubtypeAttributeColumnMapping> findFirstByColumn_IdIn(Collection<Long> columnIds);

    /** 원본 속성 삭제 차단 */
    Optional<SubtypeAttributeColumnMapping> findFirstByAttribute_IdIn(Collection<Long> attributeIds);

    /** 엔터티들의 속성이 쓰인 매핑 수 (상속 변환 기록 삭제 의존성 — D-AC-18) */
    @org.springframework.data.jpa.repository.Query("select count(m) from SubtypeAttributeColumnMapping m where m.attribute.entity.id in :ids")
    long countByAttributeEntityIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
