package com.lexorma.source.catalog.repository;

import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SourceTableRepository extends JpaRepository<SourceTable, Long> {

    Optional<SourceTable> findByIdAndProjectId(Long id, Long projectId);

    Optional<SourceTable> findBySchema_IdAndCompareName(Long schemaId, String compareName);

    // 스키마의 테이블·뷰 : 유형·이름 조건은 선택이라 조합마다 따로 둔다 (null 매개변수를 쿼리에 넣지 않는다)
    Page<SourceTable> findAllByProjectIdAndSchema_Id(Long projectId, Long schemaId, Pageable pageable);

    Page<SourceTable> findAllByProjectIdAndSchema_IdAndType(Long projectId, Long schemaId, SourceTableType type, Pageable pageable);

    Page<SourceTable> findAllByProjectIdAndSchema_IdAndNameContainingIgnoreCase(Long projectId, Long schemaId, String keyword, Pageable pageable);

    Page<SourceTable> findAllByProjectIdAndSchema_IdAndTypeAndNameContainingIgnoreCase(Long projectId, Long schemaId, SourceTableType type, String keyword, Pageable pageable);

    long countBySchema_Id(Long schemaId);
}
