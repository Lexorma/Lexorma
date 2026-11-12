package com.koboolean.metamora.operation.notice.controller;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.koboolean.metamora.operation.board.domain.dto.BoardDto;
import com.koboolean.metamora.operation.notice.service.NoticeService;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.project.domain.dto.ProjectDto;
import com.koboolean.metamora.core.user.service.UserService;
import com.koboolean.metamora.common.utils.PageableUtil;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@Tag(name = "공지사항 API", description = "공지사항 조회 및 관리 API")
@RequiredArgsConstructor
@RequestMapping("/api")
public class NoticeRestController {

    private final NoticeService noticeService;
    private final UserService userService;

    @Operation(summary = "공지사항 관리 권한", description = "현재 프로젝트의 프로젝트 관리자이면 공지사항 등록/삭제 버튼을 보여준다.")
    @PreAuthorize("hasRole('NOT_APPROVE')")
    @GetMapping("/selectNotice/permission")
    public ResponseEntity<Map<String, Boolean>> getNoticePermission(@AuthenticationPrincipal AccountDto accountDto) {
        // 프로젝트 ID가 포함되어있을 경우에만 공지사항 추가, 삭제가 가능하다.
        boolean isProjectManager = userService.selectAllProjectsByUsernameProjectManager(accountDto).stream()
                .anyMatch(project -> project.getId().equals(accountDto.getProjectId()));
        return ResponseEntity.ok(Map.of("isProjectManager", isProjectManager));
    }

    @Operation(summary = "공지사항 상세 보기", description = "공지사항 상세와 조회자 목록을 조회하고, 현재 사용자를 조회자로 기록한다.")
    @PreAuthorize("hasRole('NOT_APPROVE')")
    @GetMapping("/selectNotice/view/{id}")
    public ResponseEntity<BoardDto> getNoticeView(@PathVariable(name = "id") Long id, @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(noticeService.noticePopupMain(id, accountDto));
    }

    @Operation(summary = "공지 대상 프로젝트", description = "관리 중인 프로젝트 목록. 수정(type=modify)이면 해당 공지의 대상 프로젝트에 isSelected=true")
    @PreAuthorize("hasRole('MANAGER')")
    @GetMapping("/selectNotice/projects")
    public ResponseEntity<List<ProjectDto>> getNoticeProjects(@AuthenticationPrincipal AccountDto accountDto,
                                                             @RequestParam(value = "type") String type,
                                                             @RequestParam(value = "id", required = false) Long id) {
        List<ProjectDto> managedProjects = userService.selectAllProjectsByUsernameProjectManager(accountDto);
        if (type.equals("add") || managedProjects.isEmpty()) {
            return ResponseEntity.ok(managedProjects);
        }
        // 관리 중인 프로젝트 중 해당 공지의 대상 프로젝트에 체크 표시
        return ResponseEntity.ok(noticeService.selectAllProjectsByUsernameProjectManagerChecked(id, Long.parseLong(accountDto.getId()), managedProjects));
    }

    /**
     * 공지사항 리스트 데이터 조회
     * @param page
     * @param size
     * @param sort
     * @param searchQuery
     * @param searchColumn
     * @return String
     */
    @Operation(summary = "공지사항 리스트 조회", description = "공지사항 리스트를 조회한다.")
    @PreAuthorize("hasRole('NOT_APPROVE')")
    @GetMapping("/selectNotice/data")
    public ResponseEntity<Map<String,Object>> getNoticeList(@RequestParam(value="page") int page
                                                          , @RequestParam(value="size") int size
                                                          , @RequestParam(value="sort", required = false) String sort
                                                          , @RequestParam(required = false, value = "searchQuery") String searchQuery
                                                          , @RequestParam(required = false, value = "searchColumn") String searchColumn
                                                          , @AuthenticationPrincipal AccountDto accountDto) {

        Pageable pageable = PageableUtil.getGridPageable(page, size, sort);

        String accountId = accountDto.getId();

        Page<BoardDto> selectnoticeListPage = noticeService.getNoticeList(pageable, accountDto.getProjectId(), accountId, searchQuery, searchColumn);

        return PageableUtil.getGridPageableMap(selectnoticeListPage);
    }

    /**
     * 공지사항 게시글 등록
     * @param accountDto
     * @param boardDto
     * @return
     * 
     * Restful API
     * 	url명칭은 동일하나, save의 경우 PostMapping으로 설정.
     */
    @Operation(summary = "공지사항 게시글 등록", description = "공지사항을 등록한다.")
    @PreAuthorize("hasRole('MANAGER')")
    @PostMapping(value = "/saveNotice")
    public ResponseEntity<Map<String, Boolean>> insertNotice(@AuthenticationPrincipal AccountDto accountDto
    		                                               , @RequestBody BoardDto boardDto) {

    	requireProjectManager(accountDto, boardDto.getProjectIds());
    	noticeService.insertNotice(accountDto, boardDto);

        return ResponseEntity.ok(Map.of("result", true));
    }
    
    /**
     * 공지사항 게시글 수정
     * @param accountDto
     * @param boardDto
     * @return
     * 
     * Restful API
     * 	url명칭은 동일하나, update의 경우 PutMapping으로 설정.
     */
    @Operation(summary = "공지사항 게시글 수정", description = "공지사항을 등록한다.")
    @PreAuthorize("hasRole('MANAGER')")
    @PutMapping(value = "/saveNotice")
    public ResponseEntity<Map<String, Boolean>> updateNotice(@AuthenticationPrincipal AccountDto accountDto
    		                                               , @RequestBody BoardDto boardDto) {

    	requireProjectManager(accountDto, boardDto.getProjectIds());
    	noticeService.updateNotice(accountDto, boardDto);

        return ResponseEntity.ok(Map.of("result", true));
    }

    /**
     * 공지사항 상세 조회
     * @return
     */
    @Operation(summary = "공지사항 상세 조회", description = "선택된 공지사항의 상세정보를 조회한다.")
    @PreAuthorize("hasRole('NOT_APPROVE')")
    @GetMapping("/selectNotice/detail/{id}")
    public ResponseEntity<Map<String, Object>> getNoticeDetail(@PathVariable(name = "id") Long id, @AuthenticationPrincipal AccountDto accountDto) {

        BoardDto boardDto = noticeService.noticePopupMain(id, accountDto);

        return ResponseEntity.ok(Map.of("board", boardDto, "result", true));
    }

    /**
     * 공지사항 삭제
     * @return
     */
    @Operation(summary = "공지사항 삭제", description = "선택된 공지사항의 정보를 삭제한다.")
    @PreAuthorize("hasRole('MANAGER')")
    @DeleteMapping("/deleteNotice")
    public ResponseEntity<Map<String, Object>> deleteNotice(@RequestBody List<BoardDto> boardDtos, @AuthenticationPrincipal AccountDto accountDto) {

        requireProjectManager(accountDto, List.of());
        noticeService.deleteNotice(accountDto, boardDtos);

        return ResponseEntity.ok(Map.of("result", true));
    }

    /**
     * 공지 등록·수정·삭제는 현재 프로젝트의 관리자만 (화면의 /selectNotice/permission 과 같은 기준), 대상 프로젝트도 자신이 관리하는 프로젝트만.
     * MANAGER 역할만으로 다른 프로젝트에 공지를 쓰거나 바꾸지 못하게 한다 → 403 ACCESS_DENIED
     */
    private void requireProjectManager(AccountDto accountDto, Collection<Long> targetProjectIds) {
        Set<Long> managed = userService.selectAllProjectsByUsernameProjectManager(accountDto).stream().map(ProjectDto::getId).collect(Collectors.toSet());
        if (targetProjectIds == null || !managed.contains(accountDto.getProjectId()) || !managed.containsAll(targetProjectIds)) {
            throw new AccessDeniedException("프로젝트 관리자만 공지사항을 등록·수정·삭제할 수 있습니다.");
        }
    }
}
