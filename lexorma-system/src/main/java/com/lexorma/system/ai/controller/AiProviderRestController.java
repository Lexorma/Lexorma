package com.lexorma.system.ai.controller;

import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.service.AiProviderClients;
import com.lexorma.core.ai.service.AiProviderService;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.security.domain.dto.AccountDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.Map;

/**
 * AI Provider 관리 (AI-3) : 시스템 관리자(ADMIN 역할)만. 등록·수정·삭제·활성화와 연결 테스트·모델 목록·테스트 프롬프트.
 * 경로가 /api/ai/** 라 요청 로그에 본문(프롬프트·응답)을 남기지 않는다 (LogBodyPolicy). AI 서버 연결은 SEC-1 허용 규칙(AI_HTTP)이 열어야 한다.
 * 연결 기록의 수행자·프로젝트는 로그인 세션에서 정한다.
 */
@Tag(name = "AI Provider API", description = "AI Provider 설정·연결 테스트 (AI-3)")
@RestController
@RequestMapping("/api/ai/providers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AiProviderRestController {

    private final AiProviderService service;
    private final AiProviderClients clients;

    @Operation(summary = "Provider 목록")
    @GetMapping
    public ResponseEntity<List<AiProviderDto.Provider>> list() {
        return ResponseEntity.ok(service.list());
    }

    @Operation(summary = "지원하는 Provider 유형", description = "유형별 안내용 기본 주소와 모델 목록 지원 여부")
    @GetMapping("/types")
    public ResponseEntity<List<AiProviderDto.TypeInfo>> types() {
        return ResponseEntity.ok(service.types());
    }

    @Operation(summary = "Provider 상세")
    @GetMapping("/{id}")
    public ResponseEntity<AiProviderDto.Provider> get(@PathVariable("id") Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    @Operation(summary = "Provider 등록", description = "저장만 하고 AI 서버에 연결하지 않는다")
    @PostMapping
    public ResponseEntity<AiProviderDto.Provider> create(@RequestBody AiProviderDto.ProviderRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @Operation(summary = "Provider 수정·활성화·비활성화", description = "다음 요청부터 적용된다 (재시작 불필요)")
    @PutMapping("/{id}")
    public ResponseEntity<AiProviderDto.Provider> update(@PathVariable("id") Long id, @RequestBody AiProviderDto.ProviderRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @Operation(summary = "Provider 삭제")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    @Operation(summary = "연결 테스트", description = "실제 AI 서버의 응답(모델 목록)을 받아 확인한다. 실패하면 원인을 나눠 돌려준다")
    @PostMapping("/{id}/test")
    public ResponseEntity<AiProviderDto.ConnectionTest> test(@PathVariable("id") Long id, @AuthenticationPrincipal AccountDto principal) {
        return ResponseEntity.ok(clients.testConnection(id, ExecutionContext.user(principal)));
    }

    @Operation(summary = "모델 목록", description = "요청할 때만 AI 서버에서 조회한다")
    @GetMapping("/{id}/models")
    public ResponseEntity<AiProviderDto.Models> models(@PathVariable("id") Long id, @AuthenticationPrincipal AccountDto principal) {
        return ResponseEntity.ok(clients.models(id, ExecutionContext.user(principal)));
    }

    @Operation(summary = "테스트 프롬프트", description = "활성화된 Provider 의 모델에 프롬프트 하나를 보내 응답을 확인한다 (Spring AI ChatClient). 대화 이력을 남기지 않는다")
    @PostMapping("/{id}/prompt")
    public ResponseEntity<AiProviderDto.PromptResult> prompt(@PathVariable("id") Long id, @RequestBody AiProviderDto.PromptRequest request,
                                                             @AuthenticationPrincipal AccountDto principal) {
        return ResponseEntity.ok(clients.prompt(id, request, ExecutionContext.user(principal)));
    }
}
