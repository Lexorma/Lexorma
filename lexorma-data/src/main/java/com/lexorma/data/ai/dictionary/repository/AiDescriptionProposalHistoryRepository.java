package com.lexorma.data.ai.dictionary.repository;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposalHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiDescriptionProposalHistoryRepository extends JpaRepository<AiDescriptionProposalHistory, Long> {

    List<AiDescriptionProposalHistory> findAllByProjectIdAndProposalIdOrderByIdAsc(Long projectId, Long proposalId);

    List<AiDescriptionProposalHistory> findAllByProjectId(Long projectId);
}
