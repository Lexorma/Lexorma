package com.lexorma.data.ai.dictionary.repository;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiDescriptionEvaluationTargetRepository extends JpaRepository<AiDescriptionEvaluationTarget, Long> {

    Optional<AiDescriptionEvaluationTarget> findByIdAndProjectId(Long id, Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AiDescriptionEvaluationTarget t where t.id = :id and t.projectId = :projectId")
    Optional<AiDescriptionEvaluationTarget> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    List<AiDescriptionEvaluationTarget> findAllByProjectIdOrderByIdAsc(Long projectId);

    /** 그 테이블들과 컬럼들의 평가 대상 (일반 제안 조회의 블라인드 판단) */
    List<AiDescriptionEvaluationTarget> findAllByProjectIdAndTableIdIn(Long projectId, Collection<Long> tableIds);

    /** 제안의 대상(테이블·컬럼)에 등록된 평가 대상. 등록은 모델 대상 행(테이블·컬럼) 잠금으로 줄 세워 대상당 하나다 */
    @Query("select t from AiDescriptionEvaluationTarget t where t.projectId = :projectId and t.tableId = :tableId and t.columnId is null")
    Optional<AiDescriptionEvaluationTarget> findTableTarget(@Param("projectId") Long projectId, @Param("tableId") Long tableId);

    @Query("select t from AiDescriptionEvaluationTarget t where t.projectId = :projectId and t.tableId = :tableId and t.columnId = :columnId")
    Optional<AiDescriptionEvaluationTarget> findColumnTarget(@Param("projectId") Long projectId, @Param("tableId") Long tableId, @Param("columnId") Long columnId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AiDescriptionEvaluationTarget t where t.projectId = :projectId and t.tableId = :tableId and t.columnId is null")
    Optional<AiDescriptionEvaluationTarget> lockTableTarget(@Param("projectId") Long projectId, @Param("tableId") Long tableId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AiDescriptionEvaluationTarget t where t.projectId = :projectId and t.tableId = :tableId and t.columnId = :columnId")
    Optional<AiDescriptionEvaluationTarget> lockColumnTarget(@Param("projectId") Long projectId, @Param("tableId") Long tableId, @Param("columnId") Long columnId);
}
