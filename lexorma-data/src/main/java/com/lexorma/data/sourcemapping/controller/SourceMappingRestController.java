package com.lexorma.data.sourcemapping.controller;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 원본 DB 스키마·TABLE 매핑 (M-4c). 조회는 VIEWER 이상, 변경은 USER 이상 + 그 프로젝트의 관리자 또는 ADMIN (서비스가 지금의 DB 로 확인한다 — 원본 연결·수집과 같은 기준).
 * 매핑만 저장하고 원본 DB·물리 모델은 바꾸지 않는다. 원본 카탈로그 자체의 조회는 /api/source/** 를 쓴다 (복제하지 않는다).
 */
@RestController
@RequestMapping("/api/sourceMapping")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Source Mapping API", description = "원본 DB 스키마·TABLE 과 물리 모델의 매핑 (가져오기 기준 연결, 후보 제안, 사용자 확정)")
public class SourceMappingRestController {

    private final SourceMappingService service;

    @Operation(summary = "매핑 현황", description = "기준 연결, 마지막 반영 시점, 스키마 매핑 목록(후보 포함), 상태별 건수, 다른 연결에 남은 매핑.")
    @GetMapping
    public ResponseEntity<SourceMappingDto.Overview> overview(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(service.overview(accountDto));
    }

    @Operation(summary = "TABLE 매핑 목록", description = "원본 TABLE (VIEW 제외) 과 매핑·상태·후보. sourceSchemaId·status·keyword 로 거르고 쪽 단위 (size 최대 500).")
    @GetMapping("/tables")
    public ResponseEntity<SourceMappingDto.TablePage> tables(@AuthenticationPrincipal AccountDto accountDto,
                                                             @RequestParam(value = "sourceSchemaId", required = false) Long sourceSchemaId,
                                                             @RequestParam(value = "status", required = false) String status,
                                                             @RequestParam(value = "keyword", required = false) String keyword,
                                                             @RequestParam(value = "page", defaultValue = "0") int page,
                                                             @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(service.tables(accountDto, sourceSchemaId, status, keyword, page, size));
    }

    @Operation(summary = "매핑할 모델 테이블 고르기", description = "그 모델 스키마의 테이블 (200개까지, 이미 매핑된 것은 표시).")
    @GetMapping("/modelTables")
    public ResponseEntity<List<SourceMappingDto.ModelTable>> modelTables(@AuthenticationPrincipal AccountDto accountDto, @RequestParam("schemaId") Long schemaId,
                                                                         @RequestParam(value = "keyword", required = false) String keyword) {
        return ResponseEntity.ok(service.modelTables(accountDto, schemaId, keyword));
    }

    @Operation(summary = "가져오기 기준 연결 설정·해제", description = "connectionId 가 없으면 해제. 지금 기준 연결에 매핑이 있으면 acknowledge=true 가 있어야 한다 (매핑은 지우지 않고 그 연결의 매핑으로 남는다).")
    @PutMapping("/base")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceMappingDto.Overview> setBase(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceMappingDto.BaseRequest request) {
        return ResponseEntity.ok(service.setBase(accountDto, request));
    }

    @Operation(summary = "스키마 매핑 확정·변경", description = "마지막 반영 시점의 원본 스키마 → 같은 프로젝트의 모델 스키마 (기준 연결 안에서 1:1).")
    @PutMapping("/schemas")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceMappingDto.SchemaRow> mapSchema(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceMappingDto.SchemaRequest request) {
        return ResponseEntity.ok(service.mapSchema(accountDto, request));
    }

    @Operation(summary = "스키마 매핑 해제", description = "그 원본 스키마의 TABLE 매핑도 함께 해제한다.")
    @DeleteMapping("/schemas/{sourceSchemaId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> unmapSchema(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("sourceSchemaId") Long sourceSchemaId) {
        return ResponseEntity.ok(service.unmapSchema(accountDto, sourceSchemaId));
    }

    @Operation(summary = "TABLE 매핑 확정·변경", description = "원본 TABLE → 매핑된 모델 스키마의 모델 TABLE (기준 연결 안에서 1:1).")
    @PutMapping("/tables")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceMappingDto.TableRow> mapTable(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceMappingDto.TableRequest request) {
        return ResponseEntity.ok(service.mapTable(accountDto, request));
    }

    @Operation(summary = "TABLE 매핑 해제")
    @DeleteMapping("/tables/{mappingId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> unmapTable(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("mappingId") Long mappingId) {
        return ResponseEntity.ok(service.unmapTable(accountDto, mappingId));
    }
}
