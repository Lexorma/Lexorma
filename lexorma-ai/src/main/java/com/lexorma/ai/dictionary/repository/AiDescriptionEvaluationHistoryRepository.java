package com.lexorma.ai.dictionary.repository;

import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 판정 변경 이력 (DEC-27) : 추가·조회만 한다 */
public interface AiDescriptionEvaluationHistoryRepository extends JpaRepository<AiDescriptionEvaluationHistory, Long> {

    List<AiDescriptionEvaluationHistory> findAllByProjectIdAndJudgmentIdOrderByIdAsc(Long projectId, Long judgmentId);
}
