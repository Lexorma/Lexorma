package com.lexorma.data.ai.controller;

import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.ai.domain.dto.AiMetadataQueryDto;
import com.lexorma.data.ai.service.AiMetadataQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 메타데이터 질의 (AI-4). 로그인 프로젝트의 메타데이터만 읽는다 — 조회 화면·API 와 같은 VIEWER.
 * /api/ai/** 라 요청·응답 본문(질문·답변)은 요청 로그에 남지 않는다.
 */
@RestController
@RequestMapping("/api/ai/metadata")
@RequiredArgsConstructor
@Tag(name = "AI Metadata Query API", description = "AI 메타데이터 질의 (읽기 전용 Tool Calling)")
public class AiMetadataQueryRestController {

    private final AiMetadataQueryService service;

    @Operation(summary = "질의 가능 여부", description = "이 프로젝트가 쓸 AI Provider 상태 — 프로젝트 지정(DEC-18)이 있으면 그것, 없으면 시스템 기본 (READY · NOT_CONFIGURED · DISABLED · MODEL_MISSING · UNSUPPORTED). AI 서버에 연결하지 않는다")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/status")
    public ResponseEntity<AiMetadataQueryDto.Availability> status(@org.springframework.security.core.annotation.AuthenticationPrincipal com.lexorma.core.security.domain.dto.AccountDto accountDto) {
        return ResponseEntity.ok(service.availability(accountDto == null ? null : accountDto.getProjectId()));
    }

    @Operation(summary = "메타데이터 질의", description = "시스템 기본 AI Provider 로 현재 프로젝트의 메타데이터에 대해 묻는다. AI 는 읽기 전용 조회 Tool 만 쓸 수 있다")
    @PreAuthorize("hasRole('VIEWER')")
    @PostMapping("/query")
    public ResponseEntity<AiMetadataQueryDto.QueryResult> query(@AuthenticationPrincipal AccountDto accountDto,
                                                                @RequestBody AiMetadataQueryDto.QueryRequest request) {
        return ResponseEntity.ok(service.query(ExecutionContext.user(accountDto), request));
    }
}
