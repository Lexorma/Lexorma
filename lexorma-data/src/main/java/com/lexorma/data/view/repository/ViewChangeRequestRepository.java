package com.lexorma.data.view.repository;

import com.lexorma.data.view.domain.entity.ViewChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ViewChangeRequestRepository extends JpaRepository<ViewChangeRequest, Long> {

    Optional<ViewChangeRequest> findByIdAndProjectId(Long id, Long projectId);

    List<ViewChangeRequest> findAllByView_IdOrderByIdDesc(Long viewId);

    boolean existsByView_IdAndStatus(Long viewId, ViewChangeRequest.Status status);

    /** 목록의 "변경 요청 대기" 표시 (페이지의 VIEW 만 한 번에) */
    List<ViewChangeRequest> findAllByView_IdInAndStatus(Collection<Long> viewIds, ViewChangeRequest.Status status);
}
