package com.lexorma.data.sourcemapping.controller;

import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceRenameDto;
import com.lexorma.data.sourcemapping.service.SourceRenameService;
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
 * 원본 이름 변경 매핑 (DEC-06). 조회는 VIEWER 이상, 확정·해제는 USER 이상 + 그 프로젝트의 관리자 또는 ADMIN (서비스가 지금의 DB 로 확인한다 — 매핑 변경과 같다).
 * 매핑만 저장하고 원본 DB·물리 모델은 바꾸지 않는다. 요청 로그 본문은 남기지 않는다 (원본 객체 이름·대량 목록).
 */
@RestController
@RequestMapping("/api/sourceMapping/renames")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@ProtectedLogBody
@Tag(name = "Source Rename Mapping API", description = "원본 TABLE·컬럼 이름 변경 후보와 사용자 확정 매핑 (DEC-06)")
public class SourceRenameRestController {

    private final SourceRenameService service;

    @Operation(summary = "이름 변경 후보", description = "원본에서 사라진 TABLE 매핑의 새 원본 TABLE 후보, 매핑된 TABLE 의 컬럼 이름 변경 후보, 후보 없는 원본 없음 매핑. 제안일 뿐이다.")
    @GetMapping
    public ResponseEntity<SourceRenameDto.Candidates> candidates(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(service.candidates(accountDto));
    }

    @Operation(summary = "컬럼 매핑 목록", description = "기준 연결의 확정된 컬럼 매핑과 상태 (tableMappingId 로 거르기).")
    @GetMapping("/columns")
    public ResponseEntity<List<SourceRenameDto.ColumnMapping>> columns(@AuthenticationPrincipal AccountDto accountDto,
                                                                       @RequestParam(value = "tableMappingId", required = false) Long tableMappingId) {
        return ResponseEntity.ok(service.columnMappings(accountDto, tableMappingId));
    }

    @Operation(summary = "매핑 변경 이력", description = "TABLE·컬럼 매핑의 확정·변경·이름 변경·해제 (최근 것부터, size 최대 200).")
    @GetMapping("/history")
    public ResponseEntity<SourceRenameDto.HistoryPage> history(@AuthenticationPrincipal AccountDto accountDto,
                                                               @RequestParam(value = "tableMappingId", required = false) Long tableMappingId,
                                                               @RequestParam(value = "page", defaultValue = "0") int page,
                                                               @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(service.history(accountDto, tableMappingId, page, size));
    }

    @Operation(summary = "TABLE 이름 변경 확정", description = "원본 없음인 TABLE 매핑을 같은 스키마 매핑의 미매핑 원본 TABLE 로 옮긴다 (모델 TABLE·컬럼 매핑 유지).")
    @PutMapping("/table")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceRenameDto.Candidates> confirmTable(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceRenameDto.TableRenameRequest request) {
        return ResponseEntity.ok(service.confirmTable(accountDto, request));
    }

    @Operation(summary = "컬럼 매핑 확정·변경", description = "매핑된 TABLE 안에서 원본에만 있는 원본 컬럼 ↔ 모델에만 있는 모델 컬럼 (TABLE 매핑 안에서 1:1).")
    @PutMapping("/columns")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<SourceRenameDto.ColumnMapping>> confirmColumn(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceRenameDto.ColumnRequest request) {
        return ResponseEntity.ok(service.confirmColumn(accountDto, request));
    }

    @Operation(summary = "컬럼 매핑 해제", description = "매핑 행만 지운다. 모델 컬럼은 그대로이고 다시 이름으로 짝짓는다.")
    @DeleteMapping("/columns/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> releaseColumn(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("id") Long id) {
        return ResponseEntity.ok(service.releaseColumn(accountDto, id));
    }
}
