package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalSubjectArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LogicalSubjectAreaRepository extends JpaRepository<LogicalSubjectArea, Long> {

    /** 모델의 영역 전체 (계층은 메모리에서 만든다 — 최대 3단계라 재귀 쿼리를 쓰지 않는다). 상위 영역은 FK 값만 쓴다 (프록시를 초기화하지 않는다) */
    @Query("select a from LogicalSubjectArea a where a.model.id = :modelId order by a.sortOrder, a.areaName, a.id")
    List<LogicalSubjectArea> findAllByModelId(@Param("modelId") Long modelId);

    Optional<LogicalSubjectArea> findByIdAndProjectId(Long id, Long projectId);

    @Query("select a.model.id from LogicalSubjectArea a where a.id = :id and a.projectId = :projectId")
    Optional<Long> findModelId(@Param("id") Long id, @Param("projectId") Long projectId);
}
