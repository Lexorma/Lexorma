package com.lexorma.operation.notice.service;

import java.util.List;

import com.lexorma.core.project.domain.dto.ProjectDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.lexorma.operation.board.domain.dto.BoardDto;
import com.lexorma.core.security.domain.dto.AccountDto;

public interface NoticeService {
    /** 정렬할 수 있는 화면 필드 = 화면(columns.ts)에 정렬 가능으로 정의한 열(숨긴 열 포함 — SmokeTest.gridSortAndSearch 계약) + 기본 정렬 열 (PageableUtil 허용 목록 — 공통 페이징 안정성 개선) */
    java.util.Set<String> NOTICE_SORT_FIELDS = java.util.Set.of("id", "title");


    /**
     * 공지사항 리스트 조회
     * @return
     */
    Page<BoardDto> getNoticeList(Pageable pageable, Long selectedId, String accountId, String searchQuery, String searchColumn);

    /**
     * 공지사항 등록
     * @param accountDto
     * @param boardDto
     */
    void insertNotice(AccountDto accountDto, BoardDto boardDto);
    
    /**
     * 공지사항 수정
     * @param accountDto
     * @param boardDto
     */
    void updateNotice(AccountDto accountDto, BoardDto boardDto);

    /**
     * 공지사항 상세조회
     * @param id
     * @return
     */
    BoardDto noticePopupMain(Long id, AccountDto accountDto);

    /**
     * 공지사항 삭제
     * @param accountDto
     * @param boardDtos
     * @return
     */
    void deleteNotice(AccountDto accountDto, List<BoardDto> boardDtos);

    /**
     * 공지사항 프로젝트별 정보를 수정하기위한 프로젝트를 조회한다.
     * @param boardId
     * @param accountId
     * @param collect
     * @return
     */
    List<ProjectDto> selectAllProjectsByUsernameProjectManagerChecked(Long boardId, Long accountId, List<ProjectDto> collect);
}
