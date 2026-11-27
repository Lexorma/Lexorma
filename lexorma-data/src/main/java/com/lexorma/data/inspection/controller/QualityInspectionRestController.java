package com.lexorma.data.inspection.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.InspectionCodes;
import com.lexorma.data.inspection.domain.InspectionCodes.EndReason;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.repository.InspectionQueryRepository;
import com.lexorma.data.inspection.service.InspectionExceptionService;
import com.lexorma.data.inspection.service.InspectionQueryService;
import com.lexorma.data.inspection.service.InspectionRuleSettingService;
import com.lexorma.data.inspection.service.InspectionRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import java.util.Set;

/**
 * 저장 품질 검사 (AI-6 2차) : 로그인 프로젝트 전체를 서버 안에서 비동기로 검사하고 결과를 저장한다. 조회는 VIEWER (AI6-D19),
 * 실행 요청·예외 처리·해제는 USER, 규칙 설정 변경은 USER + 프로젝트 관리자·ADMIN (서비스가 DB 로 다시 확인).
 * 3차부터 품질 진단 화면은 이 API 만 쓴다 (조회할 때마다 계산하던 실시간 진단 API 는 지웠다, AI6-3-02·03). 저장된 결과만 읽고 다시 판정하지 않는다 (AI6-D31).
 */
@RestController
@RequestMapping("/api/qualityInspection")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Quality Inspection API", description = "저장 품질 검사 (AI-6) : 실행 요청·상태·이력, 결과 요약·목록·상세, 예외 처리·해제·이력, 규칙 설정")
public class QualityInspectionRestController {

    /** 실행 이력 정렬 : 요청 일시 하나 (= 실행 일련번호 순). 그리드 첫 컬럼 */
    private static final Set<String> RUN_SORT_FIELDS = Set.of("requestedAt");

    private final InspectionRunService runService;
    private final InspectionQueryService queryService;
    private final InspectionExceptionService exceptionService;
    private final InspectionRuleSettingService ruleSettingService;

    @Operation(summary = "검사 요약", description = "지금 쓰는 규칙의 미해결(예외 제외) 건수·심각도별 건수, 예외·끈 규칙·해결(실제 개선/대상 삭제) 건수, 규칙별 건수, 마지막 확정 실행·진행 중 실행")
    @GetMapping("/summary")
    public ResponseEntity<InspectionDto.Summary> summary(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(queryService.summary(accountDto.getProjectId()));
    }

    @Operation(summary = "검사 결과 목록", description = "그리드 규격 (searchColumn 은 무시 — 검색은 대상 이름 하나). 기본 : 미해결 · 예외 제외 · 지금 쓰는 규칙. 조건 : rule, severity, targetType, status(OPEN·RESOLVED·ALL), "
            + "exclusion(NOT_EXCLUDED·EXCLUDED·ALL), ruleScope(ACTIVE·INACTIVE·ALL), endReason, tableId, searchQuery(대상 이름), "
            + "group(MODEL·IDENTIFIER·TERM 대상 구분), lastChange(마지막 변화 NEW·PERSISTING·REOPENED·RESOLVED)")
    @GetMapping("/findings/data")
    public ResponseEntity<Map<String, Object>> findings(@AuthenticationPrincipal AccountDto accountDto,
                                                        @RequestParam(value = "page") int page,
                                                        @RequestParam(value = "size") int size,
                                                        @RequestParam(value = "sort", required = false) String sort,
                                                        @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                        @RequestParam(value = "rule", required = false) StandardizationRule rule,
                                                        @RequestParam(value = "severity", required = false) StandardizationRule.Severity severity,
                                                        @RequestParam(value = "targetType", required = false) String targetType,
                                                        @RequestParam(value = "status", required = false) InspectionDto.StatusFilter status,
                                                        @RequestParam(value = "exclusion", required = false) InspectionDto.ExclusionFilter exclusion,
                                                        @RequestParam(value = "ruleScope", required = false) InspectionDto.RuleScope ruleScope,
                                                        @RequestParam(value = "endReason", required = false) EndReason endReason,
                                                        @RequestParam(value = "tableId", required = false) Long tableId,
                                                        @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                        @RequestParam(value = "group", required = false) String group,
                                                        @RequestParam(value = "lastChange", required = false) InspectionCodes.LastChange lastChange) {
        return PageableUtil.getGridPageableMap(queryService.findings(accountDto.getProjectId(),
                new InspectionDto.Search(rule, severity, targetType, status, exclusion, ruleScope, endReason, tableId, searchQuery, group, lastChange),
                PageableUtil.getGridPageable(page, size, sort, InspectionQueryRepository.SORT_FIELDS)));
    }

    @Operation(summary = "검사 결과 상세", description = "결과 + fingerprint + 근거(JSON) + 이력(예외 처리·해제·재발)")
    @GetMapping("/findings/{findingId}")
    public ResponseEntity<InspectionDto.FindingDetail> finding(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("findingId") long findingId) {
        return ResponseEntity.ok(queryService.finding(accountDto.getProjectId(), findingId));
    }

    @Operation(summary = "검사 결과 이력", description = "예외 처리·해제(사유·사람·시각)와 재발(직전 해결 사유·일시) — 추가만 한다")
    @GetMapping("/findings/{findingId}/history")
    public ResponseEntity<List<InspectionDto.History>> history(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("findingId") long findingId) {
        return ResponseEntity.ok(queryService.history(accountDto.getProjectId(), findingId));
    }

    @Operation(summary = "예외 처리", description = "미해결 결과만. 사유(1~1,000자)와 화면이 읽은 version 필수 — 다르면 409 INSPECTION_RESULT_CONFLICT")
    @PostMapping("/findings/{findingId}/exclude")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<InspectionDto.FindingDetail> exclude(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("findingId") long findingId,
                                                               @RequestBody InspectionDto.ExclusionRequest request) {
        return ResponseEntity.ok(exceptionService.exclude(accountDto, findingId, request));
    }

    @Operation(summary = "예외 해제", description = "사유(1~1,000자)와 화면이 읽은 version 필수 — 다르면 409 INSPECTION_RESULT_CONFLICT")
    @PostMapping("/findings/{findingId}/unexclude")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<InspectionDto.FindingDetail> unexclude(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("findingId") long findingId,
                                                                 @RequestBody InspectionDto.ExclusionRequest request) {
        return ResponseEntity.ok(exceptionService.unexclude(accountDto, findingId, request));
    }

    @Operation(summary = "검사 실행 요청", description = "로그인 프로젝트 전체를 서버에서 비동기로 검사한다 (202). 대기·진행 중 실행이 있으면 409 INSPECTION_ALREADY_RUNNING. 취소는 없다")
    @PostMapping("/runs")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<InspectionDto.Run> request(@AuthenticationPrincipal AccountDto accountDto) {
        long runId = runService.request(accountDto);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(queryService.run(accountDto.getProjectId(), runId));
    }

    @Operation(summary = "검사 실행 이력", description = "그리드 규격. 정렬은 requestedAt(요청 순서) 하나 — 기본 최근부터")
    @GetMapping("/runs/data")
    public ResponseEntity<Map<String, Object>> runs(@AuthenticationPrincipal AccountDto accountDto,
                                                    @RequestParam(value = "page") int page,
                                                    @RequestParam(value = "size") int size,
                                                    @RequestParam(value = "sort", required = false) String sort,
                                                    @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                    @RequestParam(value = "searchQuery", required = false) String searchQuery) {
        return PageableUtil.getGridPageableMap(queryService.runs(accountDto.getProjectId(), PageableUtil.getGridPageable(page, size, sort, RUN_SORT_FIELDS)));
    }

    @Operation(summary = "검사 실행 상태", description = "상태·요청·시작·기준·종료 일시, 실패 사유 코드, 적용 규칙별 판정 상태·발견 수·변화 유형별 건수")
    @GetMapping("/runs/{runId}")
    public ResponseEntity<InspectionDto.Run> run(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("runId") long runId) {
        return ResponseEntity.ok(queryService.run(accountDto.getProjectId(), runId));
    }

    @Operation(summary = "검사 규칙 설정", description = "20규칙의 코드·대상·심각도(서버 정의)와 이 프로젝트의 사용 여부(기본 사용), 규칙별 마지막 확정 판정·재검사 필요, editable = 지금 사용자가 바꿀 수 있는지")
    @GetMapping("/rules")
    public ResponseEntity<InspectionDto.RuleSettings> rules(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(ruleSettingService.settings(accountDto));
    }

    @Operation(summary = "검사 규칙 설정 변경", description = "프로젝트 관리자·ADMIN. 사용 여부만 바꾼다 — 규칙마다 화면이 읽은 값(expectedActive) 필수, 다르면 409 INSPECTION_RULE_SETTING_CONFLICT. 검사를 실행하지 않고, 끈 규칙의 결과는 그대로 둔다")
    @PutMapping("/rules")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<InspectionDto.RuleSettings> updateRules(@AuthenticationPrincipal AccountDto accountDto,
                                                                  @RequestBody InspectionDto.RuleSettingsUpdate request) {
        return ResponseEntity.ok(ruleSettingService.update(accountDto, request));
    }
}
