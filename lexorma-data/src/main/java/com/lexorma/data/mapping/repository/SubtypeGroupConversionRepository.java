package com.lexorma.data.mapping.repository;

import com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubtypeGroupConversionRepository extends JpaRepository<SubtypeGroupConversion, Long> {

    /** 논리 모델 하나의 변환 기록 전체 (그룹·통합 테이블·분류 컬럼 ID — 모델당 한 번) */
    @Query("select c from SubtypeGroupConversion c join fetch c.group g left join fetch c.table left join fetch c.classificationColumn "
            + "where c.projectId = :projectId and g.model.id = :modelId order by c.id")
    List<SubtypeGroupConversion> findAllByModelId(@Param("projectId") Long projectId, @Param("modelId") Long modelId);

    /** 여러 논리 모델의 변환 기록 (물리 생성 — 요청의 모델들) */
    @Query("select c from SubtypeGroupConversion c join fetch c.group g left join fetch c.table left join fetch c.classificationColumn "
            + "where c.projectId = :projectId and g.model.id in :modelIds order by c.id")
    List<SubtypeGroupConversion> findAllByModelIds(@Param("projectId") Long projectId, @Param("modelIds") Collection<Long> modelIds);

    Optional<SubtypeGroupConversion> findByIdAndProjectId(Long id, Long projectId);

    boolean existsByGroup_Id(Long groupId);

    /** 테이블·컬럼 삭제 차단 (A형 통합 테이블·분류 컬럼) */
    Optional<SubtypeGroupConversion> findFirstByTable_IdIn(Collection<Long> tableIds);

    Optional<SubtypeGroupConversion> findFirstByClassificationColumn_IdIn(Collection<Long> columnIds);

    Optional<SubtypeGroupConversion> findFirstByGroup_IdIn(Collection<Long> groupIds);
}
