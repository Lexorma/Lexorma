package com.lexorma.data.sourcemapping.repository;

import com.lexorma.data.sourcemapping.domain.entity.SourceImportExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SourceImportExecutionRepository extends JpaRepository<SourceImportExecution, Long> {

    Page<SourceImportExecution> findAllByProjectIdOrderByIdDesc(Long projectId, Pageable pageable);

    Optional<SourceImportExecution> findByIdAndProjectId(Long id, Long projectId);

    boolean existsByChangeGroupId(String changeGroupId);

    /** 한 기준 연결의 가져오기 실행 (최근 것부터) : 재가져오기 기준값(M-4f) */
    List<SourceImportExecution> findAllByProjectIdAndConnectionIdOrderByIdDesc(Long projectId, Long connectionId);
}
