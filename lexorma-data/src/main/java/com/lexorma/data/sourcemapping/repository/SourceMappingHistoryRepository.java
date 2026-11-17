package com.lexorma.data.sourcemapping.repository;

import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceMappingHistoryRepository extends JpaRepository<SourceMappingHistory, Long> {

    Page<SourceMappingHistory> findAllByProjectIdOrderByIdDesc(Long projectId, Pageable pageable);

    Page<SourceMappingHistory> findAllByProjectIdAndTableMappingIdOrderByIdDesc(Long projectId, Long tableMappingId, Pageable pageable);

    /** 재가져오기 기준값 (DEC-06 보완) : TABLE 매핑들의 이름 변경 이력 */
    java.util.List<SourceMappingHistory> findAllByProjectIdAndTableMappingIdInAndTargetTypeAndChangeType(Long projectId, java.util.Collection<Long> tableMappingIds,
            SourceMappingHistory.TargetType targetType, SourceMappingHistory.ChangeType changeType);
}
