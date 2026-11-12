package com.lexorma.source.collection.repository;

import com.lexorma.source.collection.domain.entity.SourceChangeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceChangeHistoryRepository extends JpaRepository<SourceChangeHistory, Long> {

    Page<SourceChangeHistory> findAllByProjectIdAndExecutionId(Long projectId, Long executionId, Pageable pageable);

    List<SourceChangeHistory> findAllByExecutionIdOrderByIdAsc(Long executionId);

    List<SourceChangeHistory> findAllByTableIdOrderByReflectedOrderAscIdAsc(Long tableId);

    long countByExecutionId(Long executionId);
}
