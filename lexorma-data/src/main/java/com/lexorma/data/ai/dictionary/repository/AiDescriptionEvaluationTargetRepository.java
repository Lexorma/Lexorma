package com.lexorma.data.ai.dictionary.repository;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiDescriptionEvaluationTargetRepository extends JpaRepository<AiDescriptionEvaluationTarget, Long> {

    Optional<AiDescriptionEvaluationTarget> findByIdAndProjectId(Long id, Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AiDescriptionEvaluationTarget t where t.id = :id and t.projectId = :projectId")
    Optional<AiDescriptionEvaluationTarget> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    List<AiDescriptionEvaluationTarget> findAllByProjectIdOrderByIdAsc(Long projectId);
}
