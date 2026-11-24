package com.lexorma.data.logical.controller;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 논리 데이터 모델 API (M-6 1차). 조회는 VIEWER, 변경은 USER (물리 모델 API 와 같다), 승인은 승인 권한자.
 * 저장(POST)은 id 가 없으면 등록, 있으면 수정이다.
 */
@RestController
@RequestMapping("/api/logical")
@RequiredArgsConstructor
@Tag(name = "Logical Model API", description = "논리 모델·엔터티·속성·식별자·관계 관리와 논리 ERD 조회")
public class LogicalModelRestController {

    private static final Map<String, Object> DONE = Map.of("result", true);

    private final LogicalModelService logicalModelService;

    // ------------------------------------------------------------------ 논리 모델

    @Operation(summary = "논리 모델 목록")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model")
    public ResponseEntity<List<LogicalModelDto.Model>> selectModels(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(logicalModelService.selectModels(accountDto));
    }

    @Operation(summary = "논리 모델 상세")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}")
    public ResponseEntity<LogicalModelDto.Model> selectModel(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId) {
        return ResponseEntity.ok(logicalModelService.selectModel(accountDto, modelId));
    }

    @Operation(summary = "논리 모델 등록·수정", description = "모델명은 프로젝트 안에서 중복될 수 없다 (대소문자 구분 없음)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/model")
    public ResponseEntity<LogicalModelDto.Model> saveModel(@AuthenticationPrincipal AccountDto accountDto, @RequestBody LogicalModelDto.Model model) {
        com.lexorma.common.jpa.ModelBase.require(model.id() != null, model.modifiedAt()); // DEC-08
        return ResponseEntity.ok(logicalModelService.saveModel(accountDto, model));
    }

    @Operation(summary = "논리 모델 삭제 확인 (M8-C2)", description = "삭제 확인 화면용 : 지금 기준값(modifiedAt), 함께 지워질 주제 영역 수(cascade), 구성 기준값(composition)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/deletion")
    public ResponseEntity<com.lexorma.common.jpa.ModelComposition.Deletion> selectModelDeletion(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId) {
        return ResponseEntity.ok(logicalModelService.selectModelDeletion(accountDto, modelId));
    }

    @Operation(summary = "논리 모델 삭제", description = "엔터티가 있는 모델은 삭제할 수 없다. modifiedAt(화면이 읽은 모델의 수정 일시)·composition(삭제 확인 조회의 구성 기준값) 필수 — "
            + "없으면 400 MODEL_BASE_REQUIRED, 그 사이 모델이나 함께 지워질 주제 영역이 바뀌었으면 409 MODEL_CONFLICT (M8-C2)")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/model/{modelId}")
    public ResponseEntity<Map<String, Object>> deleteModel(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                           @RequestParam(value = "modifiedAt", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime modifiedAt, @RequestParam(value = "composition", required = false) String composition) {
        com.lexorma.common.jpa.ModelBase.require(true, modifiedAt);
        com.lexorma.common.jpa.ModelComposition.require(composition);
        logicalModelService.deleteModel(accountDto, modelId, modifiedAt, composition);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "논리 ERD 조회", description = "모델의 엔터티(속성, 식별자 표시)와 관계")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/erd")
    public ResponseEntity<LogicalModelDto.Graph> selectGraph(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId) {
        return ResponseEntity.ok(logicalModelService.selectGraph(accountDto, modelId));
    }

    @Operation(summary = "논리 ERD 범위 조회", description = "이름·설명 검색어(없으면 모델 전체)에 맞는 엔터티를 이름순 page 번째 size 개(1~300, 기본 100). 양 끝이 모두 범위 안인 관계만, 범위 밖 관계는 엔터티별 수."
            + " 주제 영역(M-6 후속 4차) : subjectAreaId(기본 하위 영역 포함, includeChildren=false 면 직접 소속만) 또는 unclassified=true(미분류). 둘 다 주면 400, 이 모델의 영역이 아니면 찾을 수 없음, 없으면 모델 전체")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/erd/part")
    public ResponseEntity<LogicalModelDto.GraphPart> selectGraphPart(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                                     @RequestParam(value = "keyword", required = false) String keyword,
                                                                     @RequestParam(value = "page", defaultValue = "0") int page,
                                                                     @RequestParam(value = "size", defaultValue = "100") int size,
                                                                     @RequestParam(value = "subjectAreaId", required = false) Long subjectAreaId,
                                                                     @RequestParam(value = "unclassified", required = false) Boolean unclassified,
                                                                     @RequestParam(value = "includeChildren", required = false) Boolean includeChildren) {
        return ResponseEntity.ok(logicalModelService.selectGraphPart(accountDto, modelId, keyword, page, size, subjectAreaId, unclassified, includeChildren));
    }

    @Operation(summary = "논리 ERD 이웃 탐색", description = "엔터티에서 관계(출발·도착 모두)로 1~3단계 이어진 엔터티 (최대 300)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/erd/neighbors/{entityId}")
    public ResponseEntity<LogicalModelDto.GraphPart> selectNeighbors(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                                     @PathVariable(name = "entityId") Long entityId,
                                                                     @RequestParam(value = "depth", defaultValue = "1") int depth) {
        return ResponseEntity.ok(logicalModelService.selectNeighbors(accountDto, modelId, entityId, depth));
    }

    @Operation(summary = "논리 ERD 엔터티 찾기", description = "이름·설명 검색, 이름순 최대 size 개(1~50, 기본 20)와 전체 수. 속성은 주지 않는다."
            + " 주제 영역(M-6 후속 4차) : subjectAreaId(기본 하위 영역 포함, includeChildren=false 면 직접 소속만) 또는 unclassified=true(미분류). 둘 다 주면 400, 이 모델의 영역이 아니면 찾을 수 없음, 없으면 모델 전체")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/erd/entities")
    public ResponseEntity<LogicalModelDto.EntityHits> searchEntities(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                                     @RequestParam(value = "keyword", required = false) String keyword,
                                                                     @RequestParam(value = "size", defaultValue = "20") int size,
                                                                     @RequestParam(value = "subjectAreaId", required = false) Long subjectAreaId,
                                                                     @RequestParam(value = "unclassified", required = false) Boolean unclassified,
                                                                     @RequestParam(value = "includeChildren", required = false) Boolean includeChildren) {
        return ResponseEntity.ok(logicalModelService.searchEntities(accountDto, modelId, keyword, size, subjectAreaId, unclassified, includeChildren));
    }

    @Operation(summary = "내 논리 ERD 레이아웃 조회", description = "모델별·사용자별. 저장한 적 없으면 version·viewport 가 null")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/erd/layout")
    public ResponseEntity<LogicalModelDto.Layout> selectLayout(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId) {
        return ResponseEntity.ok(logicalModelService.selectLayout(accountDto, modelId));
    }

    @Operation(summary = "내 논리 ERD 레이아웃 저장", description = "개인 화면 설정이라 VIEWER 도 저장한다 (논리 모델은 바뀌지 않음). nodes 는 기존 좌표에 합친다. 조회한 version 이 아니면 409 ERD_LAYOUT_CONFLICT")
    @PreAuthorize("hasRole('VIEWER')")
    @PutMapping("/model/{modelId}/erd/layout")
    public ResponseEntity<LogicalModelDto.Layout> saveLayout(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                             @RequestBody LogicalModelDto.Layout layout) {
        return ResponseEntity.ok(logicalModelService.saveLayout(accountDto, modelId, layout));
    }

    // 주제 영역 조건이 없는 예전 Java 호출 (요청 매핑 없음 — HTTP 는 위의 조건 있는 메서드가 받는다. 권한은 같다)

    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<LogicalModelDto.GraphPart> selectGraphPart(AccountDto accountDto, Long modelId, String keyword, int page, int size) {
        return ResponseEntity.ok(logicalModelService.selectGraphPart(accountDto, modelId, keyword, page, size));
    }

    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<LogicalModelDto.EntityHits> searchEntities(AccountDto accountDto, Long modelId, String keyword, int size) {
        return ResponseEntity.ok(logicalModelService.searchEntities(accountDto, modelId, keyword, size));
    }

    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<Map<String, Object>> selectEntityRows(AccountDto accountDto, Long modelId, int page, int size, String sort, String searchQuery) {
        return com.lexorma.common.utils.PageableUtil.getGridPageableMap(logicalModelService.selectEntityRows(accountDto, modelId, searchQuery, page, size, sort));
    }

    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<Map<String, Object>> selectRelationshipRows(AccountDto accountDto, Long modelId, int page, int size, String sort, String searchQuery, Boolean isIdentifying) {
        return com.lexorma.common.utils.PageableUtil.getGridPageableMap(logicalModelService.selectRelationshipRows(accountDto, modelId, searchQuery, isIdentifying, page, size, sort));
    }

    // ------------------------------------------------------------------ 엔터티

    @Operation(summary = "모델의 엔터티 목록")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/entity")
    public ResponseEntity<List<LogicalModelDto.Entity>> selectEntities(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId) {
        return ResponseEntity.ok(logicalModelService.selectEntities(accountDto, modelId));
    }

    @Operation(summary = "논리 모델 요약 (M-6 후속 3차)", description = "모델 전체 엔터티 수·관계 수 (검색과 무관). 논리 모델 관리 화면 상단")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/summary")
    public ResponseEntity<LogicalModelDto.Summary> selectSummary(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId) {
        return ResponseEntity.ok(logicalModelService.selectSummary(accountDto, modelId));
    }

    @Operation(summary = "엔터티 목록 페이지 (M-6 후속 3차)", description = "공용 그리드 형식 { data, recordsTotal }. searchQuery : 이름·설명(대소문자 무시, %·_ 는 글자 그대로), "
            + "sort : entityName·entityDescription·isApproval + ,asc|desc (기본 이름순, 같은 값은 ID 순), size 1~100. 행마다 속성 수·관계 수(자기 참조 1번)·소속 주제 영역(subjectAreaId)."
            + " 주제 영역(M-6 후속 4차) : subjectAreaId(기본 하위 영역 포함, includeChildren=false 면 직접 소속만) 또는 unclassified=true(미분류). 둘 다 주면 400, 이 모델의 영역이 아니면 찾을 수 없음, 없으면 모델 전체")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/entity/data")
    public ResponseEntity<Map<String, Object>> selectEntityRows(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                                @RequestParam(value = "page", defaultValue = "0") int page,
                                                                @RequestParam(value = "size", defaultValue = "10") int size,
                                                                @RequestParam(value = "sort", required = false) String sort,
                                                                @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                @RequestParam(value = "subjectAreaId", required = false) Long subjectAreaId,
                                                                @RequestParam(value = "unclassified", required = false) Boolean unclassified,
                                                                @RequestParam(value = "includeChildren", required = false) Boolean includeChildren) {
        return com.lexorma.common.utils.PageableUtil.getGridPageableMap(logicalModelService.selectEntityRows(accountDto, modelId, searchQuery, page, size, sort,
                subjectAreaId, unclassified, includeChildren));
    }

    @Operation(summary = "엔터티 상세", description = "속성·식별자 포함")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{entityId}")
    public ResponseEntity<LogicalModelDto.Entity> selectEntity(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "entityId") Long entityId) {
        return ResponseEntity.ok(logicalModelService.selectEntity(accountDto, entityId));
    }

    @Operation(summary = "엔터티 등록·수정", description = "엔터티명은 모델 안에서 중복될 수 없다. 승인된 엔터티는 수정할 수 없다. 등록은 subjectAreaId 로 처음 주제 영역을 지정할 수 있다 (같은 트랜잭션, 수정에서는 무시)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/entity")
    public ResponseEntity<LogicalModelDto.Entity> saveEntity(@AuthenticationPrincipal AccountDto accountDto, @RequestBody LogicalModelDto.Entity entity) {
        com.lexorma.common.jpa.ModelBase.require(entity.id() != null, entity.modifiedAt()); // DEC-08
        return ResponseEntity.ok(logicalModelService.saveEntity(accountDto, entity));
    }

    @Operation(summary = "엔터티 삭제 확인 (M8-C2)", description = "삭제 확인 화면용 : 지금 기준값(modifiedAt), 함께 지워질 속성·식별자 수(cascade), 구성 기준값(composition)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{entityId}/deletion")
    public ResponseEntity<com.lexorma.common.jpa.ModelComposition.Deletion> selectEntityDeletion(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "entityId") Long entityId) {
        return ResponseEntity.ok(logicalModelService.selectEntityDeletion(accountDto, entityId));
    }

    @Operation(summary = "엔터티 삭제", description = "관계에 쓰인 엔터티·승인된 엔터티는 삭제할 수 없다. 속성·식별자는 함께 삭제된다. modifiedAt(화면이 읽은 엔터티의 수정 일시)·"
            + "composition(삭제 확인 조회의 구성 기준값) 필수 — 없으면 400, 그 사이 엔터티나 속성·식별자가 바뀌었으면 409 (M8-C2)")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/entity/{entityId}")
    public ResponseEntity<Map<String, Object>> deleteEntity(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "entityId") Long entityId,
                                                            @RequestParam(value = "modifiedAt", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime modifiedAt, @RequestParam(value = "composition", required = false) String composition) {
        com.lexorma.common.jpa.ModelBase.require(true, modifiedAt);
        com.lexorma.common.jpa.ModelComposition.require(composition);
        logicalModelService.deleteEntity(accountDto, entityId, modifiedAt, composition);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "엔터티 승인/승인 취소", description = "승인 권한(관리자·매니저·프로젝트 관리자)만 가능. modifiedAt(화면이 읽은 엔터티의 수정 일시) 필수 — 그 사이 바뀌었으면 409 (M8-C2)")
    @PreAuthorize("hasRole('USER')")
    @PatchMapping("/entity/{entityId}/approval/{isApproval}")
    public ResponseEntity<Map<String, Object>> updateEntityApproval(@AuthenticationPrincipal AccountDto accountDto,
                                                                    @PathVariable(name = "entityId") Long entityId,
                                                                    @PathVariable(name = "isApproval") boolean isApproval,
                                                                    @RequestParam(value = "modifiedAt", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime modifiedAt) {
        // 승인 권한이 없으면 기준값보다 권한 오류(서비스)가 먼저다
        if (com.lexorma.core.util.AuthUtil.isApprovalAvailable()) com.lexorma.common.jpa.ModelBase.require(true, modifiedAt);
        logicalModelService.updateEntityApproval(accountDto, entityId, isApproval, modifiedAt);
        return ResponseEntity.ok(DONE);
    }

    // ------------------------------------------------------------------ 속성

    @Operation(summary = "엔터티의 속성 목록", description = "속성 순서대로")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{entityId}/attribute")
    public ResponseEntity<List<LogicalModelDto.Attribute>> selectAttributes(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "entityId") Long entityId) {
        return ResponseEntity.ok(logicalModelService.selectAttributes(accountDto, entityId));
    }

    @Operation(summary = "속성 등록·수정", description = "termId 로 표준 용어를 연결한다 (도메인은 용어의 도메인). 새 속성은 맨 뒤에 붙는다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/attribute")
    public ResponseEntity<LogicalModelDto.Attribute> saveAttribute(@AuthenticationPrincipal AccountDto accountDto, @RequestBody LogicalModelDto.Attribute attribute) {
        com.lexorma.common.jpa.ModelBase.require(attribute.id() != null, attribute.modifiedAt()); // DEC-08
        return ResponseEntity.ok(logicalModelService.saveAttribute(accountDto, attribute));
    }

    @Operation(summary = "속성 삭제", description = "식별자에 쓰인 속성은 삭제할 수 없다. modifiedAt(화면이 읽은 속성의 수정 일시) 필수 — 그 사이 바뀌었으면 409 (M8-C2)")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/attribute/{attributeId}")
    public ResponseEntity<Map<String, Object>> deleteAttribute(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "attributeId") Long attributeId,
                                                               @RequestParam(value = "modifiedAt", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime modifiedAt) {
        com.lexorma.common.jpa.ModelBase.require(true, modifiedAt);
        logicalModelService.deleteAttribute(accountDto, attributeId, modifiedAt);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "속성 순서 변경", description = "엔터티의 속성 ID 전체를 원하는 순서대로 보낸다")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/entity/{entityId}/attribute/order")
    public ResponseEntity<List<LogicalModelDto.Attribute>> reorderAttributes(@AuthenticationPrincipal AccountDto accountDto,
                                                                             @PathVariable(name = "entityId") Long entityId,
                                                                             @RequestBody List<Long> attributeIds) {
        return ResponseEntity.ok(logicalModelService.reorderAttributes(accountDto, entityId, attributeIds));
    }

    // ------------------------------------------------------------------ 식별자

    @Operation(summary = "엔터티의 식별자 목록")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{entityId}/identifier")
    public ResponseEntity<List<LogicalModelDto.Identifier>> selectIdentifiers(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "entityId") Long entityId) {
        return ResponseEntity.ok(logicalModelService.selectIdentifiers(accountDto, entityId));
    }

    @Operation(summary = "식별자 등록·수정", description = "attributeIds : 같은 엔터티의 속성 (순서대로, 복합 가능). 주 식별자(isPrimary)는 엔터티당 하나")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/identifier")
    public ResponseEntity<LogicalModelDto.Identifier> saveIdentifier(@AuthenticationPrincipal AccountDto accountDto, @RequestBody LogicalModelDto.Identifier identifier) {
        com.lexorma.common.jpa.ModelBase.require(identifier.id() != null, identifier.modifiedAt()); // DEC-08
        return ResponseEntity.ok(logicalModelService.saveIdentifier(accountDto, identifier));
    }

    @Operation(summary = "식별자 삭제", description = "modifiedAt(화면이 읽은 식별자의 수정 일시) 필수 — 그 사이 이름·구성이 바뀌었으면 409 (M8-C2)")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/identifier/{identifierId}")
    public ResponseEntity<Map<String, Object>> deleteIdentifier(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "identifierId") Long identifierId,
                                                                @RequestParam(value = "modifiedAt", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime modifiedAt) {
        com.lexorma.common.jpa.ModelBase.require(true, modifiedAt);
        logicalModelService.deleteIdentifier(accountDto, identifierId, modifiedAt);
        return ResponseEntity.ok(DONE);
    }

    // ------------------------------------------------------------------ 관계

    @Operation(summary = "모델의 관계 목록")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/relationship")
    public ResponseEntity<List<LogicalModelDto.Relationship>> selectRelationships(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId) {
        return ResponseEntity.ok(logicalModelService.selectRelationships(accountDto, modelId));
    }

    @Operation(summary = "관계 목록 페이지 (M-6 후속 3차)", description = "공용 그리드 형식 { data, recordsTotal(검색·필터 조건에 맞는 수) }. size 1~100, 행마다 속성 짝과 판정. "
            + "searchQuery : 관계명·출발·도착 엔터티명(대소문자 무시, %·_ 는 글자 그대로), isIdentifying : true 식별 / false 비식별 / 없으면 전체, "
            + "sort : relationshipName·sourceEntityName·targetEntityName + ,asc|desc (없으면 관계 ID 순, 같은 값은 ID 순)."
            + " 주제 영역(M-6 후속 4차) : subjectAreaId(기본 하위 영역 포함, includeChildren=false 면 직접 소속만) 또는 unclassified=true(미분류). 둘 다 주면 400, 이 모델의 영역이 아니면 찾을 수 없음, 없으면 모델 전체 — 양 끝 엔터티 중 하나라도 범위 안인 관계. 행마다 양 끝의 소속 주제 영역")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/model/{modelId}/relationship/data")
    public ResponseEntity<Map<String, Object>> selectRelationshipRows(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "modelId") Long modelId,
                                                                      @RequestParam(value = "page", defaultValue = "0") int page,
                                                                      @RequestParam(value = "size", defaultValue = "10") int size,
                                                                      @RequestParam(value = "sort", required = false) String sort,
                                                                      @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                      @RequestParam(value = "isIdentifying", required = false) Boolean isIdentifying,
                                                                      @RequestParam(value = "subjectAreaId", required = false) Long subjectAreaId,
                                                                      @RequestParam(value = "unclassified", required = false) Boolean unclassified,
                                                                      @RequestParam(value = "includeChildren", required = false) Boolean includeChildren) {
        return com.lexorma.common.utils.PageableUtil.getGridPageableMap(
                logicalModelService.selectRelationshipRows(accountDto, modelId, searchQuery, isIdentifying, page, size, sort, subjectAreaId, unclassified, includeChildren));
    }

    @Operation(summary = "엔터티의 관계 (M-6 후속 3차)", description = "출발·도착 어느 쪽이든 이 엔터티인 관계와 속성 짝. 엔터티 상세의 관계 탭")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{entityId}/relationship")
    public ResponseEntity<List<LogicalModelDto.Relationship>> selectEntityRelationships(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "entityId") Long entityId) {
        return ResponseEntity.ok(logicalModelService.selectEntityRelationships(accountDto, entityId));
    }

    @Operation(summary = "관계 등록·수정", description = "양쪽 엔터티는 같은 모델에 있어야 한다 (자기 참조 가능). 카디널리티는 양쪽 최소·최대 참여 수 (최대 null = 다)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/relationship")
    public ResponseEntity<LogicalModelDto.Relationship> saveRelationship(@AuthenticationPrincipal AccountDto accountDto, @RequestBody LogicalModelDto.Relationship relationship) {
        com.lexorma.common.jpa.ModelBase.require(relationship.id() != null, relationship.modifiedAt()); // DEC-08
        return ResponseEntity.ok(logicalModelService.saveRelationship(accountDto, relationship));
    }

    @Operation(summary = "관계 속성 짝 조회 (M-7 보강 3차)", description = "부모 주 식별자 속성 ↔ 자식 속성 짝과 판정(status·warnings), 편집 후보(부모 = 주 식별자 속성, 자식 = 자식 엔터티 속성)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/relationship/{relationshipId}/attributes")
    public ResponseEntity<LogicalModelDto.RelationshipPairs> selectRelationshipPairs(@AuthenticationPrincipal AccountDto accountDto,
                                                                                    @PathVariable(name = "relationshipId") Long relationshipId) {
        return ResponseEntity.ok(logicalModelService.selectRelationshipPairs(accountDto, relationshipId));
    }

    @Operation(summary = "관계 속성 짝 저장 (M-7 보강 3차)", description = "관계 단위 일괄 교체 (순서 = 목록 순서, 빈 목록이면 모두 해제). modifiedAt 필수 — 다르면 409. 승인된 관계는 바꿀 수 없다")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/relationship/{relationshipId}/attributes")
    public ResponseEntity<LogicalModelDto.RelationshipPairs> saveRelationshipPairs(@AuthenticationPrincipal AccountDto accountDto,
                                                                                  @PathVariable(name = "relationshipId") Long relationshipId,
                                                                                  @RequestBody LogicalModelDto.PairSave request) {
        com.lexorma.common.jpa.ModelBase.require(true, request == null ? null : request.modifiedAt()); // DEC-08
        return ResponseEntity.ok(logicalModelService.saveRelationshipPairs(accountDto, relationshipId, request));
    }

    @Operation(summary = "관계 삭제", description = "승인된 관계는 삭제할 수 없다. modifiedAt(화면이 읽은 관계의 수정 일시) 필수 — 그 사이 관계나 속성 짝이 바뀌었으면 409 (M8-C2)")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/relationship/{relationshipId}")
    public ResponseEntity<Map<String, Object>> deleteRelationship(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "relationshipId") Long relationshipId,
                                                                  @RequestParam(value = "modifiedAt", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime modifiedAt) {
        com.lexorma.common.jpa.ModelBase.require(true, modifiedAt);
        logicalModelService.deleteRelationship(accountDto, relationshipId, modifiedAt);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "관계 승인/승인 취소", description = "승인 권한(관리자·매니저·프로젝트 관리자)만 가능. modifiedAt(화면이 읽은 관계의 수정 일시) 필수 — 그 사이 관계가 바뀌었으면 409 (M8-C1)")
    @PreAuthorize("hasRole('USER')")
    @PatchMapping("/relationship/{relationshipId}/approval/{isApproval}")
    public ResponseEntity<Map<String, Object>> updateRelationshipApproval(@AuthenticationPrincipal AccountDto accountDto,
                                                                          @PathVariable(name = "relationshipId") Long relationshipId,
                                                                          @PathVariable(name = "isApproval") boolean isApproval,
                                                                          @RequestParam(value = "modifiedAt", required = false)
                                                                          @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
                                                                          java.time.LocalDateTime modifiedAt) {
        // 승인 권한이 없으면 기준값보다 권한 오류(서비스)가 먼저다 (표준 용어 승인과 같은 순서)
        if (com.lexorma.core.util.AuthUtil.isApprovalAvailable()) com.lexorma.common.jpa.ModelBase.require(true, modifiedAt);
        logicalModelService.updateRelationshipApproval(accountDto, relationshipId, isApproval, modifiedAt);
        return ResponseEntity.ok(DONE);
    }
}
