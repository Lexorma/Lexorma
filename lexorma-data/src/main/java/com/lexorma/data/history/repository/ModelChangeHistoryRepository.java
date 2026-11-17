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

    /**
     * 테이블 하나(자신과 그 컬럼 — 컬럼 이력의 대상 이름은 "테이블명.컬럼명")의 변경 이력 중 두 시각 사이의 수 (DEC-29 평가 입력 맥락 : 같은 테이블의 다른 컬럼·업무 설명 변경을 잡는다).
     * 이름이 같은 다른 스키마의 테이블 컬럼도 함께 세므로 바뀌었다고 보는 쪽으로 넓다 (안전한 쪽)
     */
    @Query("select count(h) from ModelChangeHistory h where h.projectId = :projectId and h.changedAt > :from and h.changedAt <= :to"
            + " and ((h.targetType = com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType.PHYSICAL_TABLE and h.targetId = :tableId)"
            + " or (h.targetType = com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType.PHYSICAL_COLUMN and h.targetName like :columnPrefix))")
    long countTableChanges(@Param("projectId") Long projectId, @Param("tableId") Long tableId, @Param("columnPrefix") String columnPrefix,
                           @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    /** 여러 객체(같은 종류)의 변경 이력 중 두 시각 사이의 수 (DEC-29 평가 입력 맥락 : 테이블에 걸린 관계) */
    @Query("select count(h) from ModelChangeHistory h where h.projectId = :projectId and h.targetType = :type and h.targetId in :ids and h.changedAt > :from and h.changedAt <= :to")
    long countChanges(@Param("projectId") Long projectId, @Param("type") ModelChangeHistory.TargetType type, @Param("ids") java.util.Collection<Long> ids,
                      @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    /** 객체 하나의 이력 중 마지막 ID (AI-5 : 제안 생성 시점의 버전으로 남긴다). 이력이 없으면 null */
    @Query("select max(h.id) from ModelChangeHistory h where h.projectId = :projectId and h.targetType = :type and h.targetId = :targetId")
    Long lastId(@Param("projectId") Long projectId, @Param("type") ModelChangeHistory.TargetType type, @Param("targetId") Long targetId);

    /** 객체 하나의 이력 중 그 ID 보다 뒤이거나 그 시각 이후의 것 (AI-5 승인 재검증 : 시퀀스 블록 때문에 ID 만으로 순서를 보지 않는다) */
    @Query("select h from ModelChangeHistory h where h.projectId = :projectId and h.targetType = :type and h.targetId = :targetId"
            + " and (h.id > :afterId or h.changedAt >= :since) order by h.id")
    List<ModelChangeHistory> changesAfter(@Param("projectId") Long projectId, @Param("type") ModelChangeHistory.TargetType type, @Param("targetId") Long targetId,
                                          @Param("afterId") Long afterId, @Param("since") java.time.LocalDateTime since);
}
