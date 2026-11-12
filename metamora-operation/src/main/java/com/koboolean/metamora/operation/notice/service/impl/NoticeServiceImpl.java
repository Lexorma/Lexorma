package com.koboolean.metamora.operation.notice.service.impl;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.AccountRepository;
import com.koboolean.metamora.core.project.domain.dto.ProjectDto;
import com.koboolean.metamora.core.project.repository.ProjectMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.koboolean.metamora.operation.board.domain.dto.BoardDto;
import com.koboolean.metamora.operation.board.domain.entity.Board;
import com.koboolean.metamora.operation.board.domain.entity.BoardCategory;
import com.koboolean.metamora.operation.notice.repository.BoardCategoryRepository;
import com.koboolean.metamora.operation.notice.repository.BoardRepository;
import com.koboolean.metamora.operation.notice.service.NoticeService;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NoticeServiceImpl implements NoticeService {
	
	Logger logger = LoggerFactory.getLogger(NoticeServiceImpl.class);

	private final BoardRepository boardRepository;
	private final BoardCategoryRepository boardCategoryRepository;
	private final AccountRepository accountRepository;

	/**
	 * [공지사항 리스트 조회] 공지사항 그리드에 표시할 데이터를 조회한다.
	 *
	 * @param pageable
	 * @param selectedId
	 * @param accountId
	 * @param searchQuery
	 * @param searchColumn
	 */
	@Override
	public Page<BoardDto> getNoticeList(Pageable pageable, Long selectedId, String accountId, String searchQuery, String searchColumn) {

		if (searchQuery == null || searchQuery.trim().isEmpty()) {
			// 검색어가 없을 경우 전체 조회
			return boardRepository.findAllByProjectIdAndDeleteYn(selectedId, 'N', pageable).map(board -> BoardDto.fromEntity(board, accountId));
		}

		if(!searchQuery.isEmpty()) {
			searchQuery = "%" + searchQuery + "%";
		}

		Page<Board> allByProjectId = boardRepository.findAllByProjectIdAndTitleLike(selectedId, searchQuery, pageable);

		return allByProjectId.map(board -> BoardDto.fromEntity(board, accountId));
	}
	
	/**
	 * [공지사항 등록] 공지사항을 등록한다.
	 * 
	 * @param accountDto
	 * @param boardDto
	 */
    @Override
    @Transactional
    public void insertNotice(AccountDto accountDto, BoardDto boardDto) {

        boardDto.setProjectId(accountDto.getProjectId());
        
        BoardCategory boardCategory = boardCategoryRepository.findById("NOTICE").orElse(null);

        /* DB 데이터가 없는 경우 객체가 비어서 Error 발생하기 때문에 */
        if(boardCategory == null) {

        	// 예외 처리
        	throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
        }

		Account account = accountRepository.findById(Long.valueOf(accountDto.getId())).orElse(null);

		boardDto.getProjectIds().forEach(projectId -> {
			// for문을 돌며 체크박스에 체크된 프로젝트 전부 공지사항을 등록한다.
			Board board = Board.builder()
					.projectId    (projectId)
					.boardCategory(boardCategory           )	// RDB에서 join의 개념으로 본다.
					.title        (boardDto.getTitle()     )
					.content      (boardDto.getContent()   )
					.username     (accountDto.getUsername())
					.deleteYn     ('N'                     )
					.updatedTime  (LocalDateTime.now()     )
					.accounts(new HashSet<>(List.of(account)))
					.projectIds(boardDto.getProjectIds())
					.build();

			boardRepository.save(board);
		});

    }
    
	/**
	 * [공지사항 수정] 공지사항을 수정한다.
	 * 
	 * @param accountDto
	 * @param boardDto
	 */
    @Override
    @Transactional
    public void updateNotice(AccountDto accountDto, BoardDto boardDto) {

        boardDto.setProjectId(accountDto.getProjectId());
        
        BoardCategory boardCategory = boardCategoryRepository.findById("NOTICE").orElse(null);
        
        /* DB 데이터가 없는 경우 객체가 비어서 Error 발생하기 때문에 */
        if(boardCategory == null) {

        	// 예외 처리
        	throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
        }
        
        /* update의 경우 save로 저장하지 않고 객체에 값을 바꾼다.
         * 객체에 값을 바꾸면 JPA 정책 상 데이터를 덮어써서 트랜잭션이 마무리되는 시점에 flush 한다.
         */
        // 로그인한 프로젝트의 공지만 (ID 를 알아도 다른 프로젝트의 공지는 바꿀 수 없다)
        Board board = boardRepository.findByIdAndProjectId(boardDto.getId(), accountDto.getProjectId())
                .orElseThrow(() -> new AccessDeniedException("다른 프로젝트의 공지사항입니다."));

        // 시스템 공지를 수정하면 영어판과의 연결이 끊어진다. (삭제는 가능)
        if(board.isSystemNotice()) {
        	throw new CustomException(ErrorCode.SYSTEM_NOTICE_CANNOT_BE_UPDATE);
        }

        board.setTitle      (boardDto.getTitle()      );
        board.setContent    (boardDto.getContent()    );
        board.setUpdatedTime(LocalDateTime.now()	  );
		board.setProjectIds (boardDto.getProjectIds());

    }

	@Override
	@Transactional
	public BoardDto noticePopupMain(Long id, AccountDto accountDto) {

		// 로그인한 프로젝트의 공지만 (ID 를 알아도 다른 프로젝트의 공지는 읽을 수 없다). 없는 공지는 예전에는 NullPointerException(500)이었다
		Board board = boardRepository.findByIdAndProjectId(id, accountDto.getProjectId())
				.orElseThrow(() -> new AccessDeniedException("다른 프로젝트의 공지사항입니다."));

		Account account = accountRepository.findById(Long.valueOf(accountDto.getId())).orElse(null);

		if(account != null && !board.getAccounts().contains(account)){
			board.getAccounts().add(account);
		}

		return BoardDto.fromEntity(board);
	}

	@Override
	@Transactional
	public void deleteNotice(AccountDto accountDto, List<BoardDto> boardDtos) {
		boardDtos.forEach(boardDto -> {
			// 연결되어있던 모든 공지사항이 함께 삭제된다.
			Board board = boardRepository.findByIdAndProjectId(boardDto.getId(), accountDto.getProjectId()).orElse(null);

			if(board != null){
				board.setDeleteYn('Y');
				board.setUpdatedTime(LocalDateTime.now());
				board.getProjectIds().remove(accountDto.getProjectId());
			}
		});
	}

	@Override
	public List<ProjectDto> selectAllProjectsByUsernameProjectManagerChecked(Long boardId, Long accountId, List<ProjectDto> collect) {

		Board board = boardRepository.findById(boardId).orElse(null);

		if(board != null) {
			BoardDto boardDto = BoardDto.fromEntity(board);

			collect.forEach(projectDto -> {
				boardDto.getProjectIds().forEach(projectId -> {
					Boolean isSelected = projectDto.getIsSelected();
					if(!isSelected){
						projectDto.setIsSelected(projectDto.getId().equals(projectId));
					}
				});
			});
		}

		return collect;
	}
}
