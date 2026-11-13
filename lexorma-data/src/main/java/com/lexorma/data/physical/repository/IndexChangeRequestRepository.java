package com.lexorma.data.physical.repository;

import com.lexorma.data.physical.domain.entity.IndexChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IndexChangeRequestRepository extends JpaRepository<IndexChangeRequest, Long> {

    Optional<IndexChangeRequest> findByIdAndProjectId(Long id, Long projectId);

    List<IndexChangeRequest> findAllByTableIndex_IdOrderByIdDesc(Long indexId);

    boolean existsByTableIndex_IdAndStatus(Long indexId, IndexChangeRequest.Status status);

    /** 목록의 "변경 요청 대기" 표시 (페이지의 인덱스만 한 번에) */
    List<IndexChangeRequest> findAllByTableIndex_IdInAndStatus(Collection<Long> indexIds, IndexChangeRequest.Status status);
}
