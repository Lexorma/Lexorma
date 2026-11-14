package com.lexorma.system.ai.controller;

import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.service.AiDefaultProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시스템 기본 AI Provider 관리 : 시스템 관리자(ADMIN 역할)만. 조회·지정(변경)·해제. AI 서버에 연결하지 않고 SEC-1 허용 규칙을 바꾸지 않는다.
 * 업무 AI 기능은 이 API 가 아니라 내부 서비스(AiDefaultProviderService.resolve · AiProviderClients.callDefault)를 쓴다.
 */
@Tag(name = "AI Default Provider API", description = "시스템 기본 AI Provider (AI-3 후속)")
@RestController
@RequestMapping("/api/ai/default-provider")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AiDefaultProviderRestController {

    private final AiDefaultProviderService service;

    @Operation(summary = "시스템 기본 Provider 조회", description = "미지정이면 status = NOT_CONFIGURED, provider = null")
    @GetMapping
    public ResponseEntity<AiProviderDto.DefaultProvider> get() {
        return ResponseEntity.ok(service.get());
    }

    @Operation(summary = "시스템 기본 Provider 지정·변경", description = "활성이고 기본 모델이 있는 Provider 만. 같은 Provider 를 다시 지정해도 된다")
    @PutMapping
    public ResponseEntity<AiProviderDto.DefaultProvider> set(@RequestBody AiProviderDto.DefaultProviderRequest request) {
        return ResponseEntity.ok(service.set(request));
    }

    @Operation(summary = "시스템 기본 Provider 지정 해제")
    @DeleteMapping
    public ResponseEntity<AiProviderDto.DefaultProvider> clear() {
        return ResponseEntity.ok(service.clear());
    }
}
