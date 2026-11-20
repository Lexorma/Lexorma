package com.lexorma.data.logical.controller;

import com.lexorma.common.jpa.ModelBase;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 논리 모델 주제 영역 API (M-6 후속 4차). 권한은 논리 모델과 같다 : 조회·내 배치 저장은 VIEWER, 영역 관리·엔터티 분류는 USER (메뉴 logical-model / logical-model-edit).
 * 저장(POST)은 id 가 없으면 등록, 있으면 수정(이동 포함)이다. 다른 프로젝트·모델의 ID 는 논리 모델과 같은 '찾을 수 없음'이다.
 */
@RestController
@RequestMapping("/api/logical")
@RequiredArgsConstructor
@Tag(name = "Logical Subject Area API", description = "논리 모델 주제 영역 관리·엔터티 분류·주제 영역별 ERD 배치")
public class LogicalSubjectAreaRestController {

    private static final Map<String, Object> DONE = Map.of("result", true);

    private final LogicalSubjectAreaService subjectAreaService;

    @Operation(summary = "주제 영역 목록", description = "계층 순서(상위 → 하위)의 영역과 깊이·경로·직접 소속 수·하위 포함 수, 모델 전체 엔터티 수·미분류 수")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/subjectArea")
    public ResponseEntity<LogicalSubjectAreaDto.Tree> selectTree(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId) {
        return ResponseEntity.ok(subjectAreaService.selectTree(accountDto, modelId));
    }

    @Operation(summary = "주제 영역 등록·수정·이동", description = "parentId 가 null 이면 최상위. 최대 3단계 (옮길 때는 하위 영역까지), 같은 상위 아래 이름 중복 불가(대소문자 무시). 수정은 modifiedAt 필수 — 다르면 409")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/subjectArea")
    public ResponseEntity<LogicalSubjectAreaDto.Area> save(@AuthenticationPrincipal AccountDto accountDto, @RequestBody LogicalSubjectAreaDto.Save request) {
        ModelBase.require(request != null && request.id() != null, request == null ? null : request.modifiedAt()); // DEC-08
        return ResponseEntity.ok(subjectAreaService.save(accountDto, request));
    }

    @Operation(summary = "주제 영역 삭제", description = "직접 소속된 엔터티·하위 영역이 있으면 거절한다. modifiedAt(화면이 읽은 수정 일시) 필수 — 다르면 409. 그 영역의 배치만 함께 지운다")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/subjectArea/{subjectAreaId}")
    public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "subjectAreaId") Long subjectAreaId,
                                                      @RequestParam(value = "modifiedAt", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime modifiedAt) {
        ModelBase.require(true, modifiedAt);
        subjectAreaService.delete(accountDto, subjectAreaId, modifiedAt);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "엔터티 일괄 분류", description = "subjectAreaId(옮길 영역, null = 미분류)와 엔터티마다 화면이 본 지금 소속 expectedSubjectAreaId(null = 미분류)가 모두 있어야 한다. "
            + "하나라도 지금 소속과 다르면 409, 하나라도 실패하면 아무것도 바꾸지 않는다. 승인된 엔터티도 분류할 수 있다 (엔터티 자체는 바뀌지 않는다). 한 번에 최대 500개")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/model/{modelId}/subjectArea/entities")
    public ResponseEntity<LogicalSubjectAreaDto.AssignmentResult> assign(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                                         @RequestBody LogicalSubjectAreaDto.Assignment request) {
        return ResponseEntity.ok(subjectAreaService.assign(accountDto, modelId, request));
    }

    @Operation(summary = "주제 영역별 내 논리 ERD 배치 조회", description = "저장한 적 없으면 모델 전체 배치의 좌표를 참고로 준다 (source MODEL, version·viewport null — 아무것도 저장하지 않는다)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/subjectArea/{subjectAreaId}/layout")
    public ResponseEntity<LogicalSubjectAreaDto.Layout> selectLayout(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                                     @PathVariable(name = "subjectAreaId") Long subjectAreaId) {
        return ResponseEntity.ok(subjectAreaService.selectLayout(accountDto, modelId, subjectAreaId));
    }

    @Operation(summary = "주제 영역별 내 논리 ERD 배치 저장", description = "이 영역의 내 배치에만 저장한다 (모델 전체 배치·다른 영역·다른 사용자는 그대로). 개인 화면 설정이라 VIEWER 도 저장한다. "
            + "nodes 는 기존 좌표에 합친다. 조회한 version 이 아니면 409 ERD_LAYOUT_CONFLICT")
    @PreAuthorize("hasRole('VIEWER')")
    @PutMapping("/model/{modelId}/subjectArea/{subjectAreaId}/layout")
    public ResponseEntity<LogicalSubjectAreaDto.Layout> saveLayout(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                                   @PathVariable(name = "subjectAreaId") Long subjectAreaId, @RequestBody LogicalModelDto.Layout layout) {
        return ResponseEntity.ok(subjectAreaService.saveLayout(accountDto, modelId, subjectAreaId, layout));
    }
}
