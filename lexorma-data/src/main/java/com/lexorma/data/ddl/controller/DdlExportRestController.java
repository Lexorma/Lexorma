package com.lexorma.data.ddl.controller;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.ddl.domain.dto.DdlExportDto;
import com.lexorma.data.ddl.service.DdlExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 통합 DDL Export (M-11). DDL 은 생성·조회만 하고 실행하지 않는다 */
@RestController
@RequestMapping("/api/physical/ddl")
@RequiredArgsConstructor
@Tag(name = "DDL Export API", description = "프로젝트·스키마 단위 통합 DDL (SCHEMA·TABLE·INDEX·FK·VIEW)")
public class DdlExportRestController {

    private final DdlExportService ddlExportService;

    @Operation(summary = "통합 DDL", description = "프로젝트 전체(schemaId 없음) 또는 스키마 하나의 물리 모델 DDL. 미리보기와 .sql 다운로드가 같은 결과를 쓴다. errors 가 있으면 ddl 은 비어 있다.")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/export")
    public ResponseEntity<DdlExportDto> export(@AuthenticationPrincipal AccountDto accountDto,
                                               @Parameter(description = "스키마 단위 Export (없으면 프로젝트 전체)") @RequestParam(required = false, value = "schemaId") Long schemaId,
                                               @Parameter(description = "대상 DBMS (POSTGRESQL / ORACLE / MYSQL / MARIADB, 없으면 프로젝트 설정)") @RequestParam(required = false, value = "dbType") String dbType,
                                               @Parameter(description = "기본 스키마 객체에 붙일 스키마명", example = "public") @RequestParam(required = false, value = "defaultSchemaName") String defaultSchemaName,
                                               @Parameter(description = "승인 기준 : 승인 대기 항목이 있으면 만들지 않는다") @RequestParam(required = false, value = "approvedOnly", defaultValue = "false") boolean approvedOnly) {
        return ResponseEntity.ok(ddlExportService.export(accountDto, schemaId, dbType, defaultSchemaName, approvedOnly));
    }
}
