package com.lexorma.data.history.repository;

import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ModelChangeHistoryRepository extends JpaRepository<ModelChangeHistory, Long>, JpaSpecificationExecutor<ModelChangeHistory> {

    Optional<ModelChangeHistory> findByIdAndProjectId(Long id, Long projectId);

    /** 한 작업의 이력 : 기록된 순서대로 */
    List<ModelChangeHistory> findAllByProjectIdAndChangeGroupIdOrderByIdAsc(Long projectId, String changeGroupId);
}
