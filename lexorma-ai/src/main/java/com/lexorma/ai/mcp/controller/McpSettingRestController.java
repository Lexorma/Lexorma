package com.lexorma.ai.mcp.controller;

import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.ai.mcp.domain.dto.McpDto;
import com.lexorma.ai.mcp.service.McpTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내장 MCP 설정·토큰 (DEC-31). 조회는 VIEWER (토큰 목록·호출 이력은 관리자에게만), 설정·발급·폐기는 USER + 지금의 DB 로 프로젝트 관리자·ADMIN (서비스).
 * 발급 응답에 토큰 원문이 있으므로 요청 로그에 본문을 남기지 않고 캐시하지 않는다.
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@ProtectedLogBody
@PreAuthorize("hasRole('VIEWER')")
public class McpSettingRestController {

    private final McpTokenService service;

    @GetMapping
    public ResponseEntity<McpDto.Settings> settings(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(service.settings(accountDto));
    }

    @PutMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<McpDto.Settings> setEnabled(@AuthenticationPrincipal AccountDto accountDto, @RequestBody McpDto.SettingRequest request) {
        return ResponseEntity.ok(service.setEnabled(accountDto, request));
    }

    @PostMapping("/tokens")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<McpDto.Issued> issue(@AuthenticationPrincipal AccountDto accountDto, @RequestBody McpDto.IssueRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.issue(accountDto, request));
    }

    @DeleteMapping("/tokens/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<McpDto.Token> revoke(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(service.revoke(accountDto, id));
    }
}
