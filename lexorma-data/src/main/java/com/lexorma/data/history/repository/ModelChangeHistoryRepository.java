package com.lexorma.data.history.repository;

import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ModelChangeHistoryRepository extends JpaRepository<ModelChangeHistory, Long>, JpaSpecificationExecutor<ModelChangeHistory> {

    Optional<ModelChangeHistory> findByIdAndProjectId(Long id, Long projectId);

    /** 한 작업의 이력 : 기록된 순서대로 */
    List<ModelChangeHistory> findAllByProjectIdAndChangeGroupIdOrderByIdAsc(Long projectId, String changeGroupId);

    /** 여러 작업의 이력 중 대상 종류가 맞는 것 : 재가져오기 기준값(M-4f) — 가져오기가 모델에 쓴 값(변경 후 상태) */
    @Query("select h from ModelChangeHistory h where h.projectId = :projectId and h.changeGroupId in :groups and h.targetType in :types")
    List<ModelChangeHistory> findAllByGroupsAndTypes(@Param("projectId") Long projectId,
                                                     @Param("groups") Collection<String> groups,
                                                     @Param("types") Collection<ModelChangeHistory.TargetType> types);
}
