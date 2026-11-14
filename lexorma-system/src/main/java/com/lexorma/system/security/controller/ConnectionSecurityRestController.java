package com.lexorma.system.security.controller;

import com.lexorma.core.network.domain.dto.ExternalConnectionDto;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 외부 연결 보안 관리 (SEC-1) : 시스템 관리자(ADMIN 역할)만. 허용 규칙(연결 유형·호스트·CIDR·포트)과 정책 변경 이력·연결 시도 감사.
 * 프로젝트 관리자·MANAGER 이하는 바꿀 수 없다 (시스템 보안 정책). 수행자는 로그인 세션에서 정한다 (요청 값의 사용자·관리자 여부를 쓰지 않는다).
 */
@Tag(name = "External Connection Security API", description = "외부 연결 허용 규칙·감사 (SEC-1)")
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ConnectionSecurityRestController {

    private final ExternalConnectionRuleService service;

    @Operation(summary = "허용 규칙 목록", description = "type = JDBC · AI_HTTP (비우면 전체), keyword = 호스트·대역·설명")
    @GetMapping("/connection-policies")
    public ResponseEntity<List<ExternalConnectionDto.Rule>> rules(@RequestParam(value = "type", required = false) String type,
                                                                  @RequestParam(value = "keyword", required = false) String keyword) {
        return ResponseEntity.ok(service.list(type, keyword));
    }

    @Operation(summary = "허용 규칙 등록")
    @PostMapping("/connection-policies")
    public ResponseEntity<ExternalConnectionDto.Rule> create(@RequestBody ExternalConnectionDto.RuleRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @Operation(summary = "허용 규칙 수정·활성화·비활성화")
    @PutMapping("/connection-policies/{id}")
    public ResponseEntity<ExternalConnectionDto.Rule> update(@PathVariable("id") Long id, @RequestBody ExternalConnectionDto.RuleRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @Operation(summary = "허용 규칙 삭제")
    @DeleteMapping("/connection-policies/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    @Operation(summary = "허용 규칙이 없는 등록 연결", description = "등록된 연결의 목적지 중 지금 규칙으로 열리지 않는 것 (이름을 풀어 판정, 기록하지 않음)")
    @GetMapping("/connection-policies/unmatched")
    public ResponseEntity<List<ExternalConnectionDto.Unmatched>> unmatched() {
        return ResponseEntity.ok(service.unmatched());
    }

    @Operation(summary = "절대 차단 대역 (안내용)")
    @GetMapping("/connection-policies/settings")
    public ResponseEntity<ExternalConnectionDto.Settings> settings() {
        return ResponseEntity.ok(service.settings());
    }

    @Operation(summary = "연결 시도 감사", description = "result = ALLOWED(허용된 모든 결과) · DENIED(차단) · 또는 결과 코드")
    @GetMapping("/connection-audits")
    public ResponseEntity<ExternalConnectionDto.Page<ExternalConnectionDto.Attempt>> attempts(
            @RequestParam(value = "type", required = false) String type, @RequestParam(value = "result", required = false) String result,
            @RequestParam(value = "keyword", required = false) String keyword, @RequestParam(value = "user", required = false) String user,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(service.attempts(type, result, keyword, user, from, to, page, size));
    }

    @Operation(summary = "정책 변경 감사")
    @GetMapping("/policy-audits")
    public ResponseEntity<ExternalConnectionDto.Page<ExternalConnectionDto.RuleChange>> changes(@RequestParam(value = "page", defaultValue = "0") int page,
                                                                                                @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(service.changes(page, size));
    }
}
