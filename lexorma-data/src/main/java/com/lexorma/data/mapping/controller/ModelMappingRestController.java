package com.lexorma.data.mapping.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.ModelMappingService;
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
 * 논리·물리 모델 매핑 API (M-7). 조회는 VIEWER, 변경은 USER (논리·물리 모델 API 와 같다). 매핑에는 승인 절차가 없다.
 * 저장(POST)은 id 가 없으면 등록, 있으면 변경이다. 정합성 비교(/quality)와 물리명 추천 목록(/recommendation/column·table 의 data, summary)은 조회 전용이다. 물리명 추천 일괄 적용(/recommendation/rename/**)은 미리보기 → planToken → 적용이다.
 */
@RestController
@RequestMapping("/api/modelMapping")
@RequiredArgsConstructor
@Tag(name = "Model Mapping API", description = "논리 엔터티 ↔ 물리 테이블, 논리 속성 ↔ 물리 컬럼 매핑 관리와 현황 조회")
public class ModelMappingRestController {

    private static final Map<String, Object> DONE = Map.of("result", true);

    private final ModelMappingService modelMappingService;
    private final ModelMappingQualityService modelMappingQualityService;
    private final com.lexorma.data.mapping.service.MappingSuggestionService suggestionService;
    private final PhysicalNameRecommendationService recommendationService;
    private final com.lexorma.data.mapping.service.PhysicalGenerationService generationService;
    private final com.lexorma.data.mapping.service.PhysicalNameRenameService renameService;
    private final com.lexorma.data.mapping.service.ForeignKeyCorrectionService foreignKeyCorrectionService;
    private final com.lexorma.data.mapping.service.UniqueCorrectionService uniqueCorrectionService;

    @Operation(summary = "논리 → 물리 생성 미리보기 (DEC-10)", description = "고른 엔터티로 만들 테이블·컬럼·PK 계획과 막는 문제·경고, 논리 관계별 FK 미생성 사유(relations), DBMS 식별자 진단(identifiers), planToken. 모델 객체를 저장하지 않는다 (기본 스키마가 없으면 다른 화면처럼 만든다)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/generate/preview")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Plan> generatePreview(@RequestBody com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Request request,
                                                                                                         @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(generationService.preview(accountDto, request));
    }

    @Operation(summary = "논리 → 물리 생성 반영 (DEC-10)", description = "미리보기의 planToken 과 같은 계획일 때만 미승인 테이블·컬럼과 매핑(서브타입은 상속 PK 매핑·상속 FK 포함, M-7 3차)을 만든다. 그 사이 바뀌었거나 참조할 기존 테이블이 바뀌는 중이면 409, 하나라도 실패하면 전체 롤백")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/generate")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Result> generate(@RequestBody com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Request request,
                                                                                                    @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(generationService.apply(accountDto, request));
    }

    @Operation(summary = "엔터티 기준 매핑 목록", description = "프로젝트의 엔터티와 매핑된 테이블. 조건 : modelId, schemaId, mapped(true 매핑됨 / false 매핑 후보 엔터티), entityId(그 엔터티 1건), 검색 : entityName / tableName")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/data")
    public ResponseEntity<Map<String, Object>> selectEntityRows(@AuthenticationPrincipal AccountDto accountDto,
                                                                @RequestParam(value = "page") int page,
                                                                @RequestParam(value = "size") int size,
                                                                @RequestParam(value = "sort", required = false) String sort,
                                                                @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                @RequestParam(value = "modelId", required = false) Long modelId,
                                                                @RequestParam(value = "schemaId", required = false) Long schemaId,
                                                                @RequestParam(value = "mapped", required = false) Boolean mapped,
                                                                @RequestParam(value = "entityId", required = false) Long entityId) {
        return PageableUtil.getGridPageableMap(modelMappingService.selectEntityRows(accountDto,
                new ModelMappingDto.EntitySearch(modelId, schemaId, mapped, searchColumn, searchQuery, entityId),
                PageableUtil.getGridPageable(page, size, sort, com.lexorma.data.mapping.repository.ModelMappingQueryRepository.ENTITY_SORT_FIELDS)));
    }

    @Operation(summary = "엔터티 또는 테이블의 매핑 1건", description = "entityId 와 tableId 중 하나만 준다. 엔터티는 매핑이 없어도 1행(mappingId null), 테이블은 매핑된 경우만. 없으면 본문 없음")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/lookup")
    public ResponseEntity<ModelMappingDto.EntityRow> selectEntityRow(@AuthenticationPrincipal AccountDto accountDto,
                                                                     @RequestParam(value = "entityId", required = false) Long entityId,
                                                                     @RequestParam(value = "tableId", required = false) Long tableId) {
        return ResponseEntity.ok(modelMappingService.selectEntityRow(accountDto, entityId, tableId));
    }

    @Operation(summary = "매핑 상세", description = "엔터티 ↔ 테이블 매핑 1건, 엔터티의 속성 전체(매핑된 컬럼·양쪽 표준 용어), 아직 연결하지 않은 테이블 컬럼(매핑 후보)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{mappingId}")
    public ResponseEntity<ModelMappingDto.Detail> selectEntityMapping(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "mappingId") Long mappingId) {
        return ResponseEntity.ok(modelMappingService.selectEntityMapping(accountDto, mappingId));
    }

    @Operation(summary = "엔터티 ↔ 테이블 매핑 등록·변경", description = "엔터티 1 : 테이블 1. 변경은 테이블만 바꾸며, 속성 매핑이 있으면 거절한다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/entity")
    public ResponseEntity<ModelMappingDto.EntityMapping> saveEntityMapping(@AuthenticationPrincipal AccountDto accountDto, @RequestBody ModelMappingDto.EntityMapping mapping) {
        return ResponseEntity.ok(modelMappingService.saveEntityMapping(accountDto, mapping));
    }

    @Operation(summary = "엔터티 ↔ 테이블 매핑 삭제", description = "속성 매핑이 있으면 거절한다. 엔터티·테이블은 지우지 않는다")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/entity/{mappingId}")
    public ResponseEntity<Map<String, Object>> deleteEntityMapping(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "mappingId") Long mappingId) {
        modelMappingService.deleteEntityMapping(accountDto, mappingId);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "속성 ↔ 컬럼 매핑 등록·변경", description = "부모 매핑(mappingId)의 엔터티에 속한 속성과 테이블에 속한 컬럼만 연결한다. 변경은 컬럼만 바꾼다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/attribute")
    public ResponseEntity<ModelMappingDto.AttributeMapping> saveAttributeMapping(@AuthenticationPrincipal AccountDto accountDto, @RequestBody ModelMappingDto.AttributeMapping mapping) {
        return ResponseEntity.ok(modelMappingService.saveAttributeMapping(accountDto, mapping));
    }

    @Operation(summary = "속성 ↔ 컬럼 매핑 삭제", description = "속성·컬럼은 지우지 않는다")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/attribute/{attributeMappingId}")
    public ResponseEntity<Map<String, Object>> deleteAttributeMapping(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "attributeMappingId") Long attributeMappingId) {
        modelMappingService.deleteAttributeMapping(accountDto, attributeMappingId);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "상속 PK 매핑 조회 (M-7 3차)", description = "서브타입 엔터티 매핑의 상속 PK 매핑 : 지금 루트 주 식별자 속성마다 1행(없으면 컬럼 null), 지금 주 식별자에 없는 기존 매핑(current false), "
            + "지금 메타데이터로 계산한 정합성(status — 저장하지 않는다). 서브타입이 아니게 된 엔터티의 기존 매핑도 보인다 (status NOT_SUBTYPE)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{mappingId}/subtypeKeys")
    public ResponseEntity<ModelMappingDto.SubtypeKeyDetail> selectSubtypeKeys(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "mappingId") Long mappingId) {
        return ResponseEntity.ok(modelMappingService.selectSubtypeKeys(accountDto, mappingId));
    }

    @Operation(summary = "상속 PK 매핑 후보 컬럼 (M-7 3차)", description = "매핑된 테이블의 컬럼 중 속성 매핑·다른 상속 PK 매핑에 쓰이지 않은 것. currentId(변경할 상속 PK 매핑)의 컬럼은 후보로 둔다. 검색 : 컬럼명·컬럼 설명")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{mappingId}/subtypeKeys/candidates")
    public ResponseEntity<Map<String, Object>> selectSubtypeKeyCandidates(@AuthenticationPrincipal AccountDto accountDto,
                                                                          @PathVariable(name = "mappingId") Long mappingId,
                                                                          @RequestParam(value = "page") int page,
                                                                          @RequestParam(value = "size") int size,
                                                                          @RequestParam(value = "sort", required = false) String sort,
                                                                          @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                          @RequestParam(value = "currentId", required = false) Long currentId) {
        return PageableUtil.getGridPageableMap(modelMappingService.selectSubtypeKeyCandidates(accountDto, mappingId, currentId, searchQuery,
                PageableUtil.getGridPageable(page, size, sort, com.lexorma.data.mapping.repository.ModelMappingQueryRepository.KEY_COLUMN_SORT_FIELDS)));
    }

    @Operation(summary = "상속 PK 매핑 등록·변경 (M-7 3차)", description = "mappingId(서브타입 엔터티 매핑)의 루트 주 식별자 속성(attributeId)과 그 테이블의 컬럼을 연결한다. 변경(id 있음)은 컬럼만 바꾼다. "
            + "속성 매핑·다른 상속 PK 매핑에 쓰인 컬럼은 거절한다. 물리 PK·FK·컬럼은 바꾸지 않는다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/subtypeKey")
    public ResponseEntity<ModelMappingDto.SubtypeKeyMapping> saveSubtypeKeyMapping(@AuthenticationPrincipal AccountDto accountDto, @RequestBody ModelMappingDto.SubtypeKeyMapping mapping) {
        return ResponseEntity.ok(modelMappingService.saveSubtypeKeyMapping(accountDto, mapping));
    }

    @Operation(summary = "상속 PK 매핑 해제 (M-7 3차)", description = "매핑만 지운다. 물리 PK·FK·컬럼과 논리 식별자는 그대로")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/subtypeKey/{id}")
    public ResponseEntity<Map<String, Object>> deleteSubtypeKeyMapping(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        modelMappingService.deleteSubtypeKeyMapping(accountDto, id);
        return ResponseEntity.ok(DONE);
    }

    @Operation(summary = "매핑 후보 테이블", description = "아직 엔터티에 매핑하지 않은 프로젝트의 테이블. 조건 : schemaId, 검색 : 테이블명·한글명")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/table/data")
    public ResponseEntity<Map<String, Object>> selectUnmappedTables(@AuthenticationPrincipal AccountDto accountDto,
                                                                    @RequestParam(value = "page") int page,
                                                                    @RequestParam(value = "size") int size,
                                                                    @RequestParam(value = "sort", required = false) String sort,
                                                                    @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                    @RequestParam(value = "schemaId", required = false) Long schemaId) {
        return PageableUtil.getGridPageableMap(modelMappingService.selectUnmappedTables(accountDto, schemaId, searchQuery,
                PageableUtil.getGridPageable(page, size, sort, com.lexorma.data.mapping.repository.ModelMappingQueryRepository.TABLE_SORT_FIELDS)));
    }

    @Operation(summary = "매핑 현황", description = "논리 모델별 엔터티·속성의 전체·매핑·미매핑 수. modelId 가 없으면 프로젝트의 모든 논리 모델")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/status")
    public ResponseEntity<List<ModelMappingDto.Status>> selectStatus(@AuthenticationPrincipal AccountDto accountDto, @RequestParam(value = "modelId", required = false) Long modelId) {
        return ResponseEntity.ok(modelMappingService.selectStatus(accountDto, modelId));
    }

    @Operation(summary = "정합성 요약", description = "논리 모델 1개의 엔터티·속성 매핑 수(매핑 현황과 같은 값)와 진단 결과 건수 : 일치·불일치·비교 불가·미매핑")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/quality/summary")
    public ResponseEntity<ModelMappingQualityDto.Summary> selectQualitySummary(@AuthenticationPrincipal AccountDto accountDto, @RequestParam(value = "modelId") Long modelId) {
        return ResponseEntity.ok(modelMappingQualityService.selectSummary(accountDto, modelId));
    }

    @Operation(summary = "정합성 진단 목록", description = "매핑된 속성 ↔ 컬럼의 진단 유형별 결과와 매핑하지 않은 엔터티·속성. 조건 : modelId(필수), entityId, type, status, group(진단 범위 — ATTRIBUTE·INHERITANCE·RELATIONSHIP·ALTERNATE_KEY·PHYSICAL_ONLY·ALL, 없으면 예전 목록), 검색 : entityName / attributeName / columnName. 구조 진단 행(관계·보조 식별자·물리 전용)은 structure 에 순서대로의 컬럼 구성이 있다")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/quality/data")
    public ResponseEntity<Map<String, Object>> selectQualityRows(@AuthenticationPrincipal AccountDto accountDto,
                                                                 @RequestParam(value = "page") int page,
                                                                 @RequestParam(value = "size") int size,
                                                                 @RequestParam(value = "sort", required = false) String sort,
                                                                 @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                 @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                 @RequestParam(value = "modelId") Long modelId,
                                                                 @RequestParam(value = "entityId", required = false) Long entityId,
                                                                 @RequestParam(value = "type", required = false) ModelMappingQualityDto.CheckType type,
                                                                 @RequestParam(value = "status", required = false) ModelMappingQualityDto.CheckStatus status,
                                                                 @RequestParam(value = "group", required = false) ModelMappingQualityDto.CheckGroup group) {
        return PageableUtil.getGridPageableMap(modelMappingQualityService.selectRows(accountDto,
                new ModelMappingQualityDto.Search(modelId, entityId, type, status, searchColumn, searchQuery, group),
                PageableUtil.getGridPageable(page, size, sort, ModelMappingQualityService.SORT_FIELDS)));
    }

    @Operation(summary = "테이블 매핑 후보 (M-7 자동 매핑 후보)", description = "매핑하지 않은 엔터티 1개의 테이블 후보와 근거 : KEY_TERMS(주 식별자 표준 용어 = PK 컬럼 용어, STANDARD) · "
            + "TABLE_NAME(표준 단어 테이블명과 같음) · TABLE_COMMENT(테이블 코멘트 = 엔터티명). 매핑하지 않은 테이블만, 최대 20개, schemaId 로 스키마 범위. 조회 전용 — 저장은 POST /api/modelMapping/entity")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/suggestions")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.TableSuggestions> selectTableSuggestions(@AuthenticationPrincipal AccountDto accountDto,
                                                                                                                         @RequestParam(value = "entityId") Long entityId,
                                                                                                                         @RequestParam(value = "schemaId", required = false) Long schemaId) {
        return ResponseEntity.ok(suggestionService.tableSuggestions(accountDto, entityId, schemaId));
    }

    @Operation(summary = "컬럼 매핑 후보 (M-7 자동 매핑 후보)", description = "엔터티 매핑 1건의 속성마다 아직 매핑하지 않은 컬럼 후보와 근거 : STANDARD_TERM(승인된 같은 표준 용어, STANDARD) · "
            + "STANDARD_TERM_UNAPPROVED · COLUMN_NAME(컬럼명 = 용어 영문약어) · LOGICAL_NAME(속성명 = 컬럼 논리명), 충돌(conflicts), 매핑했을 때 정합성 비교 불일치 항목. 조회 전용 — 저장은 POST /api/modelMapping/attribute")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/entity/{mappingId}/suggestions")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.AttributeSuggestions> selectAttributeSuggestions(@AuthenticationPrincipal AccountDto accountDto,
                                                                                                                                 @PathVariable(name = "mappingId") Long mappingId) {
        return ResponseEntity.ok(suggestionService.attributeSuggestions(accountDto, mappingId));
    }

    @Operation(summary = "논리 ERD 매핑 표시 (M-7 ERD 연계)", description = "논리 모델의 엔터티(entityIds, 최대 300개 — ERD 에 보이는 것)마다 매핑된 테이블, 속성 ↔ 컬럼, 상속 PK 매핑과 "
            + "정합성 비교 상태(MAPPED · UNMAPPED · MISMATCH · NOT_COMPARABLE). 다른 모델·프로젝트의 ID 는 결과에서 빠진다")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/erd/entities")
    public ResponseEntity<List<com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.EntityOverlay>> selectEntityOverlays(@AuthenticationPrincipal AccountDto accountDto,
                                                                                                                          @RequestParam(value = "modelId") Long modelId,
                                                                                                                          @RequestParam(value = "entityIds", required = false) List<Long> entityIds) {
        return ResponseEntity.ok(suggestionService.entityOverlays(accountDto, modelId, entityIds));
    }

    @Operation(summary = "물리 ERD 매핑 표시 (M-7 ERD 연계)", description = "테이블(tableIds, 최대 300개 — ERD 에 보이는 것)마다 매핑된 논리 엔터티, 컬럼 ↔ 속성(직접·상속 PK)과 정합성 비교 상태. "
            + "다른 프로젝트의 ID 는 결과에서 빠진다")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/erd/tables")
    public ResponseEntity<List<com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.TableOverlay>> selectTableOverlays(@AuthenticationPrincipal AccountDto accountDto,
                                                                                                                        @RequestParam(value = "tableIds", required = false) List<Long> tableIds) {
        return ResponseEntity.ok(suggestionService.tableOverlays(accountDto, tableIds));
    }

    @Operation(summary = "컬럼명 추천 요약", description = "논리 모델 1개의 속성 수와 추천 상태별 건수 : 추천 가능·일치·추천 불가·충돌")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/recommendation/column/summary")
    public ResponseEntity<PhysicalNameRecommendationDto.ColumnSummary> selectColumnRecommendationSummary(@AuthenticationPrincipal AccountDto accountDto, @RequestParam(value = "modelId") Long modelId) {
        return ResponseEntity.ok(recommendationService.selectColumnSummary(accountDto, modelId));
    }

    @Operation(summary = "컬럼명 추천 목록", description = "논리 속성의 표준 용어 영문약어 = 추천 컬럼명. 조회 전용이며 컬럼명을 바꾸지 않는다. 조건 : modelId(attributeId·columnId 가 없으면 필수), entityId, attributeId, columnId, status, 검색 : entityName / logicalName / currentPhysicalName / recommendedPhysicalName")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/recommendation/column/data")
    public ResponseEntity<Map<String, Object>> selectColumnRecommendations(@AuthenticationPrincipal AccountDto accountDto,
                                                                           @RequestParam(value = "page") int page,
                                                                           @RequestParam(value = "size") int size,
                                                                           @RequestParam(value = "sort", required = false) String sort,
                                                                           @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                           @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                           @RequestParam(value = "modelId", required = false) Long modelId,
                                                                           @RequestParam(value = "entityId", required = false) Long entityId,
                                                                           @RequestParam(value = "attributeId", required = false) Long attributeId,
                                                                           @RequestParam(value = "columnId", required = false) Long columnId,
                                                                           @RequestParam(value = "status", required = false) PhysicalNameRecommendationDto.Status status) {
        return PageableUtil.getGridPageableMap(recommendationService.selectColumnRows(accountDto,
                new PhysicalNameRecommendationDto.ColumnSearch(modelId, entityId, attributeId, columnId, status, searchColumn, searchQuery),
                PageableUtil.getGridPageable(page, size, sort, com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository.COLUMN_SORT_FIELDS)));
    }

    @Operation(summary = "테이블명 추천 목록", description = "엔터티명을 표준 단어로 나눠 테이블명 생성 규칙(접두어 + 승인된 단어의 영문약어)으로 만든 추천 테이블명. 조회 전용. 조건 : modelId(entityId 가 없으면 필수), entityId, 검색 : entityName / tableName")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/recommendation/table/data")
    public ResponseEntity<Map<String, Object>> selectTableRecommendations(@AuthenticationPrincipal AccountDto accountDto,
                                                                          @RequestParam(value = "page") int page,
                                                                          @RequestParam(value = "size") int size,
                                                                          @RequestParam(value = "sort", required = false) String sort,
                                                                          @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                          @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                          @RequestParam(value = "modelId", required = false) Long modelId,
                                                                          @RequestParam(value = "entityId", required = false) Long entityId) {
        return PageableUtil.getGridPageableMap(recommendationService.selectTableRows(accountDto,
                new PhysicalNameRecommendationDto.TableSearch(modelId, entityId, searchColumn, searchQuery),
                PageableUtil.getGridPageable(page, size, sort, PhysicalNameRecommendationService.TABLE_SORT_FIELDS)));
    }

    @Operation(summary = "물리명 추천 일괄 적용 미리보기 (M-7)", description = "고른 물리 테이블·컬럼(ID, 합쳐 500개까지)의 지금 이름·서버가 다시 계산한 추천명·적용 가능 여부·차단 사유·경고(원본 매핑)와 planToken. "
            + "추천끼리 충돌은 이 선택 집합 기준. 아무것도 바꾸지 않는다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/recommendation/rename/preview")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Plan> renamePreview(@RequestBody com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Request request,
                                                                                                        @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(renameService.preview(accountDto, request));
    }

    @Operation(summary = "물리명 추천 일괄 적용 (M-7)", description = "미리보기의 planToken 과 대상별 기준값(baseModifiedAt = 미리보기의 modifiedAt)이 지금과 같고 모든 대상을 적용할 수 있을 때만, "
            + "직전 모델 버전(RENAME-요청 ID)을 남긴 뒤 물리 테이블명·컬럼명만 바꾼다. 그 사이 바뀌었거나 다른 사용자가 바꾸는 중이면 409, 막힌 대상이 있으면 400, 하나라도 실패하면 전체 롤백")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/recommendation/rename")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Result> rename(@RequestBody com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Request request,
                                                                                                  @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(renameService.apply(accountDto, request));
    }
    @Operation(summary = "누락 FK 보정 가능 여부 (M7-FK-01)", description = "정합성 비교의 논리 관계(relationshipIds, 같은 모델, 최대 100개 — 목록 한 쪽)마다 FK 를 만들 수 있는지와 막는 사유. 조회 전용 (계획 토큰 없음)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/quality/foreignKey/status")
    public ResponseEntity<List<com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Item>> foreignKeyStatus(@AuthenticationPrincipal AccountDto accountDto,
                                                                                                                  @RequestParam(value = "modelId") Long modelId,
                                                                                                                  @RequestParam(value = "relationshipIds") List<Long> relationshipIds) {
        return ResponseEntity.ok(foreignKeyCorrectionService.status(accountDto, modelId, relationshipIds));
    }

    @Operation(summary = "누락 FK 보정 미리보기 (M7-FK-01)", description = "고른 논리 관계(FK 누락)의 만들 FK 구성(부모·자식 테이블, 컬럼 쌍·순서, 표현, 이름, 승인 상태)과 보정 가능 여부·사유, planToken. 아무것도 바꾸지 않는다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/quality/foreignKey/preview")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Plan> foreignKeyPreview(@RequestBody com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Request request,
                                                                                                             @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(foreignKeyCorrectionService.preview(accountDto, request));
    }

    @Operation(summary = "누락 FK 보정 적용 (M7-FK-01)", description = "미리보기의 planToken 과 같은 계획이고 고른 관계를 모두 보정할 수 있을 때만, 직전 모델 버전(FKFIX-요청 ID)을 남긴 뒤 미승인 FK 를 만든다. "
            + "기존 FK·컬럼·PK 는 바꾸지 않는다. 그 사이 바뀌었거나 다른 사용자가 바꾸는 중이면 409, 막힌 관계가 있으면 400, 하나라도 실패하면 전체 롤백")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/quality/foreignKey/apply")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Result> foreignKeyApply(@RequestBody com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Request request,
                                                                                                             @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(foreignKeyCorrectionService.apply(accountDto, request));
    }

    @Operation(summary = "누락 UNIQUE 보정 가능 여부 (M7-AK-02)", description = "정합성 비교의 보조 식별자(identifierIds, 같은 모델, 최대 100개 — 목록 한 쪽)마다 UNIQUE 를 만들 수 있는지와 막는 사유·경고. 조회 전용 (계획 토큰 없음)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/quality/unique/status")
    public ResponseEntity<List<com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Item>> uniqueStatus(@AuthenticationPrincipal AccountDto accountDto,
                                                                                                          @RequestParam(value = "modelId") Long modelId,
                                                                                                          @RequestParam(value = "identifierIds") List<Long> identifierIds) {
        return ResponseEntity.ok(uniqueCorrectionService.status(accountDto, modelId, identifierIds));
    }

    @Operation(summary = "누락 UNIQUE 보정 미리보기 (M7-AK-02)", description = "고른 보조 식별자(UNIQUE 누락)의 만들 UNIQUE 구성(테이블, 컬럼·순서, 이름, 미승인)과 보정 가능 여부·사유·NULL 경고, planToken. 아무것도 바꾸지 않는다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/quality/unique/preview")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Plan> uniquePreview(@RequestBody com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Request request,
                                                                                                     @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(uniqueCorrectionService.preview(accountDto, request));
    }

    @Operation(summary = "누락 UNIQUE 보정 적용 (M7-AK-02)", description = "미리보기의 planToken 과 같은 계획이고 고른 보조 식별자를 모두 보정할 수 있을 때만, 직전 모델 버전(UKFIX-요청 ID)을 남긴 뒤 미승인 UNIQUE 제약조건을 만든다. "
            + "기존 UNIQUE·고유 인덱스·컬럼은 바꾸지 않고 원본 DB 도 바꾸지 않는다. 그 사이 바뀌었거나 다른 사용자가 바꾸는 중이면 409, 막힌 식별자가 있으면 400, 하나라도 실패하면 전체 롤백")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/quality/unique/apply")
    public ResponseEntity<com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Result> uniqueApply(@RequestBody com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Request request,
                                                                                                     @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(uniqueCorrectionService.apply(accountDto, request));
    }
}
