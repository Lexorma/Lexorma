package com.lexorma.data.schema.controller;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.service.SchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Schema API", description = "프로젝트 스키마 관리 API")
public class SchemaRestController {

    private final SchemaService schemaService;

    @Operation(summary = "스키마 목록", description = "로그인 프로젝트의 스키마 목록 (기본 스키마 먼저)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/schema")
    public ResponseEntity<List<SchemaDto>> selectSchemas(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(schemaService.selectSchemas(accountDto));
    }

    @Operation(summary = "스키마 등록·수정", description = "id 가 없으면 등록. 승인 권한(관리자·매니저·프로젝트 관리자)만 가능")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/schema")
    public ResponseEntity<SchemaDto> saveSchema(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SchemaDto schemaDto) {
        return ResponseEntity.ok(schemaService.saveSchema(accountDto, schemaDto));
    }

    @Operation(summary = "스키마 삭제", description = "테이블이 없는 기본 스키마 외 스키마만 삭제. 승인 권한만 가능")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/schema/{schemaId}")
    public ResponseEntity<Map<String, Object>> deleteSchema(@AuthenticationPrincipal AccountDto accountDto,
                                                            @PathVariable(name = "schemaId") Long schemaId) {
        schemaService.deleteSchema(accountDto, schemaId);
        return ResponseEntity.ok(Map.of("result", true));
    }
}
