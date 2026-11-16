package com.lexorma.data.ai.dictionary.repository;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiDescriptionEvaluationRepository extends JpaRepository<AiDescriptionEvaluation, Long> {

    List<AiDescriptionEvaluation> findAllByProjectId(Long projectId);

    List<AiDescriptionEvaluation> findAllByProjectIdAndTargetIdOrderByIdAsc(Long projectId, Long targetId);

    Optional<AiDescriptionEvaluation> findByProposalIdAndReviewerId(Long proposalId, String reviewerId);

    boolean existsByProjectIdAndTargetId(Long projectId, Long targetId);

    List<AiDescriptionEvaluation> findAllByProjectIdAndProposalId(Long projectId, Long proposalId);
}
