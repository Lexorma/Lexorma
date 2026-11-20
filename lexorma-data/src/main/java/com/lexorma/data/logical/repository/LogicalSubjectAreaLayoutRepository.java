package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalSubjectAreaLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LogicalSubjectAreaLayoutRepository extends JpaRepository<LogicalSubjectAreaLayout, Long> {

    Optional<LogicalSubjectAreaLayout> findBySubjectAreaIdAndAccountId(Long subjectAreaId, Long accountId);

    /** 영역을 지울 때 모든 사용자의 그 영역 배치를 함께 지운다 (모델 전체 배치·다른 영역 배치는 그대로) */
    void deleteBySubjectAreaId(Long subjectAreaId);

    /** 모델을 지울 때 */
    void deleteByModelId(Long modelId);
}
