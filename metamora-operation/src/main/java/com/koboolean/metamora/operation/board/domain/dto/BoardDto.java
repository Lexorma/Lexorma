package com.koboolean.metamora.operation.board.domain.dto;

import com.koboolean.metamora.common.i18n.Messages;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.koboolean.metamora.operation.board.domain.entity.Board;

import lombok.experimental.Accessors;

@Data
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BoardDto {

    /* 게시글 번호 */
    private Long id;

    /* 프로젝트 아이디 */
    private Long projectId;

    /* 회원 아이디 */
    private String username;

    /* 게시판 카테고리 아이디 */
    private String categoryId;
    
    /* 게시판 카테고리 명 */
    private String categoryName;

    /* 게시글 제목 */
    private String title;

    /* 게시글 내용 */
    private String content;

    /* 삭제 여부 */
    private char deleteYn;
    
    private LocalDateTime updatedTime;
    
    private BoardCategoryDto boardCategoryDto;

    private Set<AccountDto> accounts = new HashSet<>();

    private String isHit;

    private List<Long> projectIds;

    /* 시스템이 등록한 공지 여부 (수정 불가) */
    private boolean systemNotice;
    
    /* 빌더 호출 */
    public static BoardDto fromEntity(Board entity) {
    	
    	BoardCategoryDto boardCategoryDto = BoardCategoryDto.fromEntity(entity.getBoardCategory());

        Set<AccountDto> collect = entity.getAccounts().stream().map(AccountDto::fromEntity).collect(Collectors.toSet());

        boolean systemNotice = entity.isSystemNotice();

        return BoardDto.builder()
                       .id          (entity.getId()       )
                       .projectId   (entity.getProjectId())
                       .username    (entity.getUsername() )
                       // 시스템 공지는 요청 언어로 보여 준다. (영어판은 EnglishMessages)
                       .title       (systemNotice ? Messages.get(entity.getTitle()) : entity.getTitle())
                       .content     (systemNotice ? Messages.get(entity.getContent()) : entity.getContent())
                       .deleteYn    (entity.getDeleteYn() )
                       .updatedTime (entity.getUpdatedTime())
                       .categoryName(boardCategoryDto.getCategoryName())
                       .categoryId  (boardCategoryDto.getCategoryId())
                       .accounts    (collect)
                       .projectIds  (entity.getProjectIds())
                       .systemNotice(systemNotice)
                       .isHit("")
                       .build()
        ;
    }

    /* 빌더 호출 */
    public static BoardDto fromEntity(Board entity, String accountId) {

        BoardDto boardDto = fromEntity(entity);

        boolean isHits = boardDto.getAccounts().stream().anyMatch(account -> account.getId().equals(accountId));
        boardDto.setIsHit(isHits ? "확인" : "미확인");

        return boardDto;
    }
}
