package com.lexorma.ai.dictionary.controller;

import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.ai.dictionary.service.AiDescriptionJobService;
import com.lexorma.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.ai.dictionary.service.AiDictionaryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 데이터 사전 초안 (AI-5). 로그인 프로젝트만. 조회 VIEWER · 생성·재확인 USER · 검토(승인·반려·보류) USER + 승인 권한(서비스가 DB 에서 다시 확인).
 * /api/ai/** 라 요청·응답 본문은 요청 로그에 남지 않는다.
 */
@RestController
@RequestMapping("/api/ai/dictionary")
@RequiredArgsConstructor
@Tag(name = "AI Dictionary Draft API", description = "AI 데이터 사전 초안 생성·검토 (제안은 승인 전까지 업무 설명을 바꾸지 않는다)")
public class AiDictionaryRestController {

    private final AiDescriptionDraftService drafts;
    private final AiDescriptionReviewService reviews;
    private final AiDescriptionJobService jobs;
    private final AiDictionaryQueryService queries;

    @Operation(summary = "제안 목록", description = "status : PROPOSED·REVIEWED·APPROVED·REJECTED·ON_HOLD·REVIEW_REQUIRED·SUPERSEDED·OPEN(검토 대기 전체)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/proposals")
    public ResponseEntity<Page<AiDictionaryDto.ProposalSummary>> list(@AuthenticationPrincipal AccountDto account, @RequestParam(value = "status", required = false) String status,
                                                                       @RequestParam(value = "targetType", required = false) String targetType, @RequestParam(value = "tableId", required = false) Long tableId,
                                                                       @RequestParam(value = "keyword", required = false) String keyword, @RequestParam(value = "recheck", required = false) Boolean recheck,
                                                                       @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(queries.list(ExecutionContext.user(account), status, targetType, tableId, keyword, recheck, page, size));
    }

    @Operation(summary = "제안 상세", description = "원본 COMMENT·현재 업무 설명·제안·근거·불확실성·확인 질문·생성 뒤 변경(미리 보기)·이력")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/proposals/{id}")
    public ResponseEntity<AiDictionaryDto.ProposalDetail> detail(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id) {
        return ResponseEntity.ok(queries.detail(ExecutionContext.user(account), id));
    }

    @Operation(summary = "생성 대상 테이블 목록 (쪽 단위)", description = "page 0 부터, size 기본 30·최대 100, 테이블 이름 → ID 순. keyword 는 이름·테이블 코멘트 부분 일치, onlyMissing 이면 업무 설명이 없거나 업무 설명 없는 컬럼이 있는 테이블")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/targets/tables")
    public ResponseEntity<Page<AiDictionaryDto.TargetRow>> targetTables(@AuthenticationPrincipal AccountDto account,
                                                                        @RequestParam(value = "onlyMissing", defaultValue = "false") boolean onlyMissing,
                                                                        @RequestParam(value = "keyword", required = false) String keyword,
                                                                        @RequestParam(value = "page", defaultValue = "0") int page,
                                                                        @RequestParam(value = "size", defaultValue = "30") int size) {
        return ResponseEntity.ok(queries.targetTables(ExecutionContext.user(account), onlyMissing, keyword, page, size));
    }

    @Operation(summary = "생성 대상 후보", description = "tableId 가 없으면 테이블(앞에서 최대 500 — 대상 선택 화면은 /targets/tables), 있으면 그 테이블과 컬럼. onlyMissing 이면 업무 설명 없는 것만")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/targets")
    public ResponseEntity<List<AiDictionaryDto.TargetRow>> targets(@AuthenticationPrincipal AccountDto account, @RequestParam(value = "tableId", required = false) Long tableId,
                                                                  @RequestParam(value = "onlyMissing", defaultValue = "false") boolean onlyMissing, @RequestParam(value = "keyword", required = false) String keyword) {
        return ResponseEntity.ok(queries.targets(ExecutionContext.user(account), tableId, onlyMissing, keyword));
    }

    @Operation(summary = "테이블·컬럼의 업무 설명과 제안 상태", description = "테이블·컬럼 관리 화면과 ERD 상세 패널용")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/objects/{tableId}")
    public ResponseEntity<List<AiDictionaryDto.ObjectStatus>> objects(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "tableId") Long tableId) {
        return ResponseEntity.ok(queries.objectStatus(ExecutionContext.user(account), tableId));
    }

    @Operation(summary = "품질 지표", description = "모델·프롬프트 버전별 승인율·수정 비율·근거 오류 비율 등")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/metrics")
    public ResponseEntity<AiDictionaryDto.Metrics> metrics(@AuthenticationPrincipal AccountDto account) {
        return ResponseEntity.ok(queries.metrics(ExecutionContext.user(account)));
    }

    @Operation(summary = "제안 생성 (단건)", description = "시스템 기본 AI Provider 로 테이블 또는 컬럼 하나의 업무 설명 초안을 만든다. 업무 설명은 바꾸지 않는다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/generate")
    public ResponseEntity<AiDictionaryDto.GenerateResult> generate(@AuthenticationPrincipal AccountDto account, @RequestBody AiDictionaryDto.GenerateRequest request) {
        AiDescriptionDraftService.Result result = drafts.generate(ExecutionContext.user(account), request.tableId(), request.columnId(), Boolean.TRUE.equals(request.regenerate()), null);
        return ResponseEntity.ok(queries.generated(result));
    }

    @Operation(summary = "생성 대상 미리 보기", description = "실행과 같은 판정 (SELECTED : tables·columns·onlyMissing). 대상마다 CREATE · OPEN_PROPOSAL(건너뜀) · DESCRIBED(뺌). 아무것도 만들지 않는다")
    @PreAuthorize("hasRole('USER')") // 생성 화면용 (생성 권한과 같다). POST 라 USER 이상
    @PostMapping("/jobs/preview")
    public ResponseEntity<AiDictionaryDto.JobPreview> previewJob(@AuthenticationPrincipal AccountDto account, @RequestBody AiDictionaryDto.JobRequest request) {
        return ResponseEntity.ok(jobs.preview(ExecutionContext.user(account), request));
    }

    @Operation(summary = "대량 생성 시작", description = "MISSING_TABLES · TABLE_COLUMNS · SELECTED(tables : 고른 테이블 자신, columns : 그 테이블의 컬럼도, onlyMissing : 업무 설명 있는 것은 뺀다). 비동기로 차례로 만든다 (프로젝트당 하나)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/jobs")
    public ResponseEntity<AiDictionaryDto.JobSummary> startJob(@AuthenticationPrincipal AccountDto account, @RequestBody AiDictionaryDto.JobRequest request) {
        return ResponseEntity.ok(jobs.start(ExecutionContext.user(account), request));
    }

    @Operation(summary = "최근 실행")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/jobs")
    public ResponseEntity<List<AiDictionaryDto.JobSummary>> recentJobs(@AuthenticationPrincipal AccountDto account) {
        return ResponseEntity.ok(jobs.recent(ExecutionContext.user(account)));
    }

    @Operation(summary = "실행 진행 상태")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/jobs/{id}")
    public ResponseEntity<AiDictionaryDto.JobSummary> job(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id) {
        return ResponseEntity.ok(jobs.get(ExecutionContext.user(account), id));
    }

    @Operation(summary = "승인 (수정 후 승인)", description = "근거가 바뀌었으면 반영하지 않고 REVIEW_REQUIRED. 다른 사용자가 먼저 처리했으면 409")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/{id}/approve")
    public ResponseEntity<AiDictionaryDto.ReviewResult> approve(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id,
                                                                @RequestBody AiDictionaryDto.ReviewRequest request) {
        return ResponseEntity.ok(reviews.approve(ExecutionContext.user(account), id, request));
    }

    @Operation(summary = "반려 (사유 필수)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<AiDictionaryDto.ReviewResult> reject(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id,
                                                               @RequestBody AiDictionaryDto.ReviewRequest request) {
        return ResponseEntity.ok(reviews.reject(ExecutionContext.user(account), id, request));
    }

    @Operation(summary = "종결 (적용하지 않음, 사유 필수)", description = "검토 대기·검토 중·보류·재검토 필요 → CLOSED. 업무 설명은 바꾸지 않는다. 반려와 달리 품질 지표에 넣지 않는다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/{id}/close")
    public ResponseEntity<AiDictionaryDto.ReviewResult> close(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id,
                                                              @RequestBody AiDictionaryDto.ReviewRequest request) {
        return ResponseEntity.ok(reviews.close(ExecutionContext.user(account), id, request));
    }

    @Operation(summary = "근거 재검증 (재검토 필요)", description = "생성 때 기준과 지금을 다시 비교. 남은 변경이 없으면 검토 중으로, 내용 변경뿐이면 acknowledge + 사유로 기준을 옮겨 검토 중으로. 승인은 따로")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/{id}/revalidate")
    public ResponseEntity<AiDictionaryDto.RevalidateResult> revalidate(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id,
                                                                       @RequestBody AiDictionaryDto.RevalidateRequest request) {
        return ResponseEntity.ok(reviews.revalidate(ExecutionContext.user(account), id, request));
    }

    @Operation(summary = "보류")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/{id}/hold")
    public ResponseEntity<AiDictionaryDto.ReviewResult> hold(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id,
                                                             @RequestBody AiDictionaryDto.ReviewRequest request) {
        return ResponseEntity.ok(reviews.hold(ExecutionContext.user(account), id, request));
    }

    @Operation(summary = "검토함 표시 (보류 해제 포함)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/{id}/review")
    public ResponseEntity<AiDictionaryDto.ReviewResult> review(@AuthenticationPrincipal AccountDto account, @PathVariable(value = "id") Long id,
                                                               @RequestBody AiDictionaryDto.ReviewRequest request) {
        return ResponseEntity.ok(reviews.markReviewed(ExecutionContext.user(account), id, request));
    }

    @Operation(summary = "일괄 검토", description = "APPROVE · REJECT · HOLD. 항목마다 따로 처리하고 결과를 돌려준다")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/bulk")
    public ResponseEntity<List<AiDictionaryDto.BulkReviewItem>> bulk(@AuthenticationPrincipal AccountDto account, @RequestBody AiDictionaryDto.BulkReviewRequest request) {
        return ResponseEntity.ok(reviews.bulk(ExecutionContext.user(account), request));
    }

    @Operation(summary = "근거 변경 재확인", description = "근거가 바뀐 검토 대기 제안은 REVIEW_REQUIRED, 승인된 제안은 재검토 필요 표시 (업무 설명은 그대로)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/proposals/recheck")
    public ResponseEntity<AiDictionaryDto.RecheckResult> recheck(@AuthenticationPrincipal AccountDto account) {
        return ResponseEntity.ok(reviews.recheck(ExecutionContext.user(account)));
    }
}
