package com.lexorma.source.catalog.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.catalog.domain.dto.SourceCatalogDto;
import com.lexorma.source.catalog.service.SourceCatalogService;
import com.lexorma.source.catalog.service.SourceViewDefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 원본 메타데이터 카탈로그 조회 (VIEWER 이상, 로그인한 프로젝트의 것만). 읽기만 한다 :
 * 원본 카탈로그는 수집한 사실이라 고치는 API 가 없다. 이 API 는 Lexorma 자체 DB 만 읽고 원본 DB 에 접속하지 않는다.
 */
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Source Catalog API", description = "원본 메타데이터 카탈로그 조회 (Lexorma 에 저장된 것)")
public class SourceCatalogRestController {

    private final SourceCatalogService catalogService;
    private final SourceViewDefinitionService viewDefinitionService;

    @Operation(summary = "원본 카탈로그 : 연결의 스키마 목록", description = "쪽 단위 (size 최대 200).")
    @GetMapping("/connections/{id}/schemas")
    public ResponseEntity<Map<String, Object>> schemas(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                       @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "50") int size) {
        return PageableUtil.getGridPageableMap(catalogService.schemas(accountDto, id, page, size));
    }

    @Operation(summary = "원본 카탈로그 : 스키마의 테이블·뷰 목록", description = "쪽 단위 (size 최대 200). type = TABLE · VIEW, keyword = 이름에 들어 있는 말.")
    @GetMapping("/schemas/{schemaId}/tables")
    public ResponseEntity<Map<String, Object>> tables(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "schemaId") Long schemaId,
                                                      @RequestParam(value = "type", required = false) String type, @RequestParam(value = "keyword", required = false) String keyword,
                                                      @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "50") int size) {
        return PageableUtil.getGridPageableMap(catalogService.tables(accountDto, schemaId, type, keyword, page, size));
    }

    @Operation(summary = "원본 카탈로그 : 테이블·뷰 상세", description = "컬럼(원본 순서), PK·UNIQUE·CHECK, 인덱스, 나가는 FK·들어오는 FK.")
    @GetMapping("/tables/{tableId}")
    public ResponseEntity<SourceCatalogDto.TableDetail> table(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "tableId") Long tableId) {
        return ResponseEntity.ok(catalogService.table(accountDto, tableId));
    }

    /** VIEW 정의 원문 (DEC-16) : 프로젝트 관리자·ADMIN 만 (서비스가 지금의 DB 로 확인), 확인 이력을 남긴다. /api/source/** 라 요청 로그에 본문이 남지 않는다 */
    @GetMapping("/tables/{tableId}/viewDefinition")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceCatalogDto.ViewDefinitionText> viewDefinition(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "tableId") Long tableId) {
        return ResponseEntity.ok(viewDefinitionService.read(accountDto, tableId));
    }

    @GetMapping("/tables/{tableId}/viewDefinition/accesses")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<java.util.List<SourceCatalogDto.ViewDefinitionAccess>> viewDefinitionAccesses(@AuthenticationPrincipal AccountDto accountDto,
                                                                                                    @PathVariable(name = "tableId") Long tableId) {
        return ResponseEntity.ok(viewDefinitionService.accesses(accountDto, tableId, 20));
    }
}
