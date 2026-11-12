package com.lexorma.operation.notice.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.lexorma.operation.board.domain.entity.Board;

import java.util.Optional;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {

    Page<Board> findAllByProjectId(Long projectId, Pageable pageable);

    Page<Board> findAllByProjectIdAndTitleLike(Long projectId, String searchQuery, Pageable pageable);

    Optional<Board> findByIdAndProjectId(Long id, Long projectId);

    Page<Board> findAllByProjectIdAndDeleteYn(Long selectedId, char deleteYn, Pageable pageable);

    /** 다른 프로젝트의 공지 (프로젝트 삭제 때 대상 목록 정리) */
    java.util.List<Board> findAllByProjectIdNot(Long projectId);
}
