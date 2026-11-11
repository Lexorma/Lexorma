package com.koboolean.metamora.data.mapping.controller;

import com.koboolean.metamora.common.utils.PageableUtil;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto;
import com.koboolean.metamora.data.mapping.service.ModelMappingService;
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
 * 논리·물리 모델 매핑 API (M-7 1차). 조회는 VIEWER, 변경은 USER (논리·물리 모델 API 와 같다). 매핑에는 승인 절차가 없다.
 * 저장(POST)은 id 가 없으면 등록, 있으면 변경이다.
 */
@RestController
@RequestMapping("/api/modelMapping")
@RequiredArgsConstructor
@Tag(name = "Model Mapping API", description = "논리 엔터티 ↔ 물리 테이블, 논리 속성 ↔ 물리 컬럼 매핑 관리와 현황 조회")
public class ModelMappingRestController {

    private static final Map<String, Object> DONE = Map.of("result", true);

    private final ModelMappingService modelMappingService;

    @Operation(summary = "엔터티 기준 매핑 목록", description = "프로젝트의 엔터티와 매핑된 테이블. 조건 : modelId, schemaId, mapped(true 매핑됨 / false 매핑 후보 엔터티), 검색 : entityName / tableName")
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
                                                                @RequestParam(value = "mapped", required = false) Boolean mapped) {
        return PageableUtil.getGridPageableMap(modelMappingService.selectEntityRows(accountDto,
                new ModelMappingDto.EntitySearch(modelId, schemaId, mapped, searchColumn, searchQuery), PageableUtil.getGridPageable(page, size, sort)));
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

    @Operation(summary = "매핑 후보 테이블", description = "아직 엔터티에 매핑하지 않은 프로젝트의 테이블. 조건 : schemaId, 검색 : 테이블명·한글명")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/table/data")
    public ResponseEntity<Map<String, Object>> selectUnmappedTables(@AuthenticationPrincipal AccountDto accountDto,
                                                                    @RequestParam(value = "page") int page,
                                                                    @RequestParam(value = "size") int size,
                                                                    @RequestParam(value = "sort", required = false) String sort,
                                                                    @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                    @RequestParam(value = "schemaId", required = false) Long schemaId) {
        return PageableUtil.getGridPageableMap(modelMappingService.selectUnmappedTables(accountDto, schemaId, searchQuery, PageableUtil.getGridPageable(page, size, sort)));
    }

    @Operation(summary = "매핑 현황", description = "논리 모델별 엔터티·속성의 전체·매핑·미매핑 수. modelId 가 없으면 프로젝트의 모든 논리 모델")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/status")
    public ResponseEntity<List<ModelMappingDto.Status>> selectStatus(@AuthenticationPrincipal AccountDto accountDto, @RequestParam(value = "modelId", required = false) Long modelId) {
        return ResponseEntity.ok(modelMappingService.selectStatus(accountDto, modelId));
    }
}
