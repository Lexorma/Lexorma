package com.lexorma.data.logical.controller;

import com.lexorma.common.jpa.ModelBase;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalSubtypeService;
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
 * 논리 모델 슈퍼타입·서브타입 API (M-6 후속 5차, docs/completed/logical-subtype.md). 권한은 논리 모델과 같다 : 조회는 VIEWER, 그룹 관리는 USER (메뉴 logical-model / logical-model-edit).
 * 저장(POST)은 id 가 없으면 등록, 있으면 수정이다 (구성원 전체 교체, 한 트랜잭션). 다른 프로젝트·모델의 ID 는 논리 모델과 같은 '찾을 수 없음'이다.
 */
@RestController
@RequestMapping("/api/logical")
@RequiredArgsConstructor
@Tag(name = "Logical Subtype API", description = "논리 모델 슈퍼타입·서브타입 그룹 관리와 엔터티의 상속 정보(유효 속성·식별자·상속 관계)")
public class LogicalSubtypeRestController {

    private static final Map<String, Object> DONE = Map.of("result", true);

    private final LogicalSubtypeService subtypeService;

    @Operation(summary = "서브타입 그룹 목록 (서버 페이징)", description = "searchQuery : 그룹명·슈퍼타입명 (엔터티 검색과 같은 규칙). sort : groupName·supertypeEntityName·modifiedAt + ,asc|desc "
            + "(기본 슈퍼타입명 → 그룹 순서, 같은 값은 ID 순), size 1~100. 행마다 전체 구성원 수")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/subtypeGroup/data")
    public ResponseEntity<Map<String, Object>> selectGroupRows(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                               @RequestParam(value = "page", defaultValue = "0") int page,
                                                               @RequestParam(value = "size", defaultValue = "10") int size,
                                                               @RequestParam(value = "sort", required = false) String sort,
                                                               @RequestParam(value = "searchQuery", required = false) String searchQuery) {
        return com.lexorma.common.utils.PageableUtil.getGridPageableMap(subtypeService.selectGroupRows(accountDto, modelId, searchQuery, page, size, sort));
    }

    @Operation(summary = "서브타입 그룹 상세", description = "슈퍼타입, 배타성(overlapping)·완전성(total), 분류 속성과 그 정의 엔터티, 구성원(순서·분류 값)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/subtypeGroup/{groupId}")
    public ResponseEntity<LogicalSubtypeDto.Group> selectGroup(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "groupId") Long groupId) {
        return ResponseEntity.ok(subtypeService.selectGroup(accountDto, groupId));
    }

    @Operation(summary = "서브타입 그룹 등록·수정", description = "overlapping·total 필수(기본값 없음), 서브타입 2개 이상(단일 상속 — 다른 그룹의 서브타입·자기 자신·순환·4단계 이상 거절), "
            + "분류 속성은 슈퍼타입의 유효 속성. 관련 엔터티 중 승인된 것이 있으면 거절. 수정은 modifiedAt 필수 — 다르면 409")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/subtypeGroup")
    public ResponseEntity<LogicalSubtypeDto.Group> saveGroup(@AuthenticationPrincipal AccountDto accountDto, @RequestBody LogicalSubtypeDto.Save request) {
        ModelBase.require(request != null && request.id() != null, request == null ? null : request.modifiedAt()); // DEC-08
        return ResponseEntity.ok(subtypeService.saveGroup(accountDto, request));
    }

    @Operation(summary = "서브타입 그룹 삭제", description = "구성원 엔터티는 지우지 않는다 (상속만 해제). 상속 주 식별자를 쓰는 속성 짝·분류 속성 참조가 깨지거나 관련 엔터티가 승인되어 있으면 거절. "
            + "modifiedAt 필수 — 다르면 409")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/subtypeGroup/{groupId}")
    public ResponseEntity<Map<String, Object>> deleteGroup(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "groupId") Long groupId,
                                                           @RequestParam(value = "modifiedAt", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime modifiedAt) {
        ModelBase.require(true, modifiedAt);
        subtypeService.deleteGroup(accountDto, groupId, modifiedAt);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "엔터티의 상속 정보", description = "단계(루트 = 1)·루트·조상·직접 슈퍼타입 그룹, 슈퍼타입으로서의 그룹, 유효 속성(직접·상속, 원본 속성 ID·정의 엔터티·깊이), "
            + "유효 식별자(상속 주 식별자·상속 보조 식별자·직접 보조 식별자), 조상에 정의된 일반 관계(복제하지 않음)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{entityId}/inheritance")
    public ResponseEntity<LogicalSubtypeDto.Inheritance> selectInheritance(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "entityId") Long entityId) {
        return ResponseEntity.ok(subtypeService.selectInheritance(accountDto, entityId));
    }

    @Operation(summary = "엔터티의 유효 속성", description = "루트 → 자기 자신 순서의 직접 정의·상속 속성 (원본 속성 ID, 정의 엔터티, 상속 깊이, 편집 가능 여부, 유효 주 식별자 순서). "
            + "직접 정의 속성만 보려면 /entity/{entityId}/attribute")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{entityId}/effectiveAttributes")
    public ResponseEntity<LogicalSubtypeDto.EffectiveAttributes> selectEffectiveAttributes(@AuthenticationPrincipal AccountDto accountDto,
                                                                                         @PathVariable(name = "entityId") Long entityId) {
        return ResponseEntity.ok(subtypeService.selectEffectiveAttributes(accountDto, entityId));
    }
}
