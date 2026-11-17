package com.lexorma.data.ai.dictionary.controller;

import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.ai.dictionary.domain.dto.AiEvaluationDto;
import com.lexorma.data.ai.dictionary.service.AiDescriptionEvaluationService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 설명 품질 평가 (AI-5 후속). 기준 데이터(평가 대상·실제 의미)와 판정은 사람이 적는다 — AI 가 판정하지 않는다.
 * 조회 VIEWER · 작성 USER (로그인 실행만, 서비스가 다시 확인). /api/ai/** 라 요청·응답 본문은 요청 로그에 남지 않는다.
 */
@RestController
@RequestMapping("/api/ai/dictionary/evaluation")
@RequiredArgsConstructor
@Tag(name = "AI Dictionary Evaluation API", description = "AI 설명 품질 평가 : 사람이 확인한 기준 데이터와 판정, 모델별 지표")
public class AiDictionaryEvaluationRestController {

    private final AiDescriptionEvaluationService evaluations;

    @Operation(summary = "평가 대상 후보", description = "tableId 가 없으면 테이블, 있으면 그 테이블과 컬럼. 범주는 규칙으로 고른 제안값")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/candidates")
    public ResponseEntity<List<AiEvaluationDto.Candidate>> candidates(@AuthenticationPrincipal AccountDto account, @RequestParam(value = "tableId", required = false) Long tableId) {
        return ResponseEntity.ok(evaluations.candidates(ExecutionContext.user(account), tableId));
    }

    @Operation(summary = "평가 대상 목록")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/targets")
    public ResponseEntity<List<AiEvaluationDto.TargetRow>> targets(@AuthenticationPrincipal AccountDto account) {
        return ResponseEntity.ok(evaluations.list(ExecutionContext.user(account)));
    }

    @Operation(summary = "평가 대상 등록", description = "최대 100개, 이미 등록된 대상은 건너뛴다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/targets")
    public ResponseEntity<AiEvaluationDto.RegisterResult> register(@AuthenticationPrincipal AccountDto account, @RequestBody AiEvaluationDto.RegisterRequest request) {
        return ResponseEntity.ok(evaluations.register(ExecutionContext.user(account), request));
    }

    @Operation(summary = "평가 대상 상세", description = "실제 의미·제안(모델별)·판정")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/targets/{id}")
    public ResponseEntity<AiEvaluationDto.TargetDetail> detail(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id) {
        return ResponseEntity.ok(evaluations.detail(ExecutionContext.user(account), id));
    }

    @Operation(summary = "실제 의미·확인 상태·범주 수정", description = "status : PENDING · CONFIRMED(실제 의미 필수) · UNCONFIRMABLE")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/targets/{id}")
    public ResponseEntity<AiEvaluationDto.TargetDetail> update(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id,
                                                               @RequestBody AiEvaluationDto.TargetUpdate request) {
        return ResponseEntity.ok(evaluations.updateTarget(ExecutionContext.user(account), id, request));
    }

    @Operation(summary = "평가 대상 삭제", description = "판정이 없는 대상만")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/targets/{id}")
    public ResponseEntity<Map<String, Boolean>> delete(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id) {
        evaluations.deleteTarget(ExecutionContext.user(account), id);
        return ResponseEntity.ok(Map.of("result", true));
    }

    @Operation(summary = "판정 저장", description = "검토자당 제안 하나에 하나 (있으면 version 으로 고친다)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/judgments")
    public ResponseEntity<AiEvaluationDto.JudgmentRow> judge(@AuthenticationPrincipal AccountDto account, @RequestBody AiEvaluationDto.JudgmentRequest request) {
        return ResponseEntity.ok(evaluations.judge(ExecutionContext.user(account), request));
    }

    @Operation(summary = "판정 변경 이력 (DEC-27)", description = "판정 하나의 저장·수정 이력 (판정자·변경 전후·사유·시각, 추가만). 지표는 최종 판정만 센다")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/judgments/{id}/history")
    public ResponseEntity<List<AiEvaluationDto.JudgmentHistoryRow>> judgmentHistory(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id) {
        return ResponseEntity.ok(evaluations.judgmentHistory(ExecutionContext.user(account), id));
    }

    @Operation(summary = "품질 지표", description = "기준 데이터 현황과 모델·프롬프트 버전별 지표 (분자·분모 포함)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/metrics")
    public ResponseEntity<AiEvaluationDto.Metrics> metrics(@AuthenticationPrincipal AccountDto account) {
        return ResponseEntity.ok(evaluations.metrics(ExecutionContext.user(account)));
    }
}
