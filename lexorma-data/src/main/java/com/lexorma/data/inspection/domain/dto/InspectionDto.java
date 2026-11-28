package com.lexorma.data.inspection.domain.dto;

import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.dictionary.quality.StandardizationRule.TargetType;
import com.lexorma.data.inspection.domain.InspectionCodes.EndReason;
import com.lexorma.data.inspection.domain.InspectionCodes.FindingStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.HistoryType;
import com.lexorma.data.inspection.domain.InspectionCodes.LastChange;
import com.lexorma.data.inspection.domain.InspectionCodes.RuleRunStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.RunChange;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 저장 품질 검사(AI-6 2차) API 의 요청·응답. 응답은 AI-7 Tool 이 그대로 다시 쓸 수 있게 화면 문구가 아니라 코드·값만 담는다 */
public final class InspectionDto {

    private InspectionDto() {
    }

    // ------------------------------------------------------------------ 규칙 설정

    /**
     * 규칙 1개 : 코드·대상·심각도는 서버 정의(바꿀 수 없다, AI6-D06), active 만 프로젝트 설정 (기본 true, AI6-D05).
     * lastInspectedRunId·lastInspectedAt = 이 규칙을 끝까지 판정한 마지막 확정 실행과 그 검사 기준 일시 (없으면 null),
     * recheckNeeded = 지금 켜져 있는데 마지막 확정 실행이 이 규칙을 판정하지 않았다 (그때 꺼져 있었거나 판정 실패 — 결과가 그 전 검사 기준이다, AI6-3-03·28)
     */
    public record RuleSetting(StandardizationRule rule, TargetType targetType, Severity severity, String group, boolean active, Long lastInspectedRunId,
                              LocalDateTime lastInspectedAt, boolean recheckNeeded) {
    }

    public record RuleSettings(List<RuleSetting> rules, boolean editable) {
    }

    /** 바꿀 값(active)과 화면이 읽은 지금 값(expectedActive, 필수 — 다르면 409 INSPECTION_RULE_SETTING_CONFLICT, DEC-08 과 같은 방식) */
    public record RuleChange(StandardizationRule rule, Boolean active, Boolean expectedActive) {
    }

    public record RuleSettingsUpdate(List<RuleChange> rules) {
    }

    // ------------------------------------------------------------------ 실행

    /** 실행 × 규칙 : 판정 상태, 발견 수(NEW + PERSISTING + REOPENED), 변화 유형별 건수 (없으면 0) */
    public record RunRule(StandardizationRule rule, Severity severity, RuleRunStatus status, Integer resultCount, Map<RunChange, Integer> counts) {
    }

    /**
     * 실행 1회. 상태값은 그대로 두고 결과의 성격을 따로 보인다 (AI6-2-02) :
     * appliedRuleCount = 시작 때 확정한 적용 규칙 수 (0 = 모든 규칙을 끈 빈 검사), partialFailure = COMPLETED 이지만 일부 규칙 묶음을 판정하지 못했다
     * (errorCode RULE_FAILED), failedRules = 판정하지 못한 규칙 (그 규칙의 결과는 이 실행에서 검사되지 않았다 — 부분 실패·실패·중단 실행).
     * totals·rules 의 건수(EXCLUDED 포함)는 이 실행이 결과를 확정한 시점(endedAt)의 값이며 그 뒤의 예외 처리·해제로 바뀌지 않는다 (AI6-2-04) —
     * 지금의 결과 집계는 {@link Summary} 가 조회 시점의 상태로 센다.
     */
    public record Run(long id, RunStatus status, LocalDateTime requestedAt, LocalDateTime startedAt, LocalDateTime basisAt, LocalDateTime endedAt,
                      String errorCode, String requestedBy, int appliedRuleCount, boolean partialFailure, List<StandardizationRule> failedRules,
                      Map<RunChange, Integer> totals, List<RunRule> rules) {
    }

    // ------------------------------------------------------------------ 결과

    /** 목록 조건 : 상태(기본 OPEN), 예외(기본 예외 제외), 규칙 범위(기본 지금 쓰는 규칙만, AI6-D14·D26) */
    public enum StatusFilter { OPEN, RESOLVED, ALL }

    public enum ExclusionFilter { NOT_EXCLUDED, EXCLUDED, ALL }

    public enum RuleScope { ACTIVE, INACTIVE, ALL }

    /**
     * group = 대상 구분(MODEL·IDENTIFIER·TERM, 규칙 묶음), lastChange = 결과의 <b>마지막</b> 변화(NEW·PERSISTING·REOPENED·RESOLVED — 특정 과거 실행의 변화가 아니다).
     * 실행 기준 (AI-7 AI7-D07, 화면은 쓰지 않는다) : firstSeenRunId = 그 실행이 처음 찾은 결과(FRST_INSP_EXCN_SN), resolvedInRunId = 그 실행이 해결 처리했고
     * 지금도 해결 상태인 결과(STTS_CD RESOLVED · LAST_INSP_EXCN_SN). 둘 다 <b>현재 저장 결과</b>를 거르는 것이고 과거 실행 당시 결과를 복원하지 않는다
     */
    public record Search(StandardizationRule rule, Severity severity, String targetType, StatusFilter status, ExclusionFilter exclusion, RuleScope ruleScope,
                         EndReason endReason, Long tableId, String searchQuery, String group, LastChange lastChange, Long firstSeenRunId, Long resolvedInRunId) {

        /** 실행 기준 없이 (화면 목록) */
        public Search(StandardizationRule rule, Severity severity, String targetType, StatusFilter status, ExclusionFilter exclusion, RuleScope ruleScope,
                      EndReason endReason, Long tableId, String searchQuery, String group, LastChange lastChange) {
            this(rule, severity, targetType, status, exclusion, ruleScope, endReason, tableId, searchQuery, group, lastChange, null, null);
        }
    }

    /** actualValue·expectedValue = 근거의 현재·기대 값 (목록 표시용, 없으면 null) */
    public record Finding(long id, StandardizationRule rule, Severity severity, String targetType, long targetId, Long tableId, String targetName,
                          FindingStatus status, boolean excluded, LastChange lastChange, EndReason endReason, LocalDateTime firstSeenAt,
                          LocalDateTime lastSeenAt, LocalDateTime endedAt, long firstRunId, long lastRunId, long version, boolean ruleActive,
                          String actualValue, String expectedValue) {
    }

    public record History(long id, HistoryType type, FindingStatus status, boolean excluded, String reason, Long runId, EndReason endReason,
                          LocalDateTime endedAt, LocalDateTime createdAt, String createdBy) {
    }

    /** 결과 1건 + 근거(JSONB 그대로 : fingerprintVersion, targetIds, actualValue, expectedValue, evidence, details) + 이력 */
    /** targetExists = 대표 대상이 지금도 있는지 (조회 시점, 화면의 이동 버튼 — 저장된 근거·이름은 대상이 지워져도 남는다) */
    public record FindingDetail(Finding finding, String fingerprint, JsonNode evidence, List<History> history, boolean targetExists) {
    }

    /** 예외 처리·해제 : 사유(필수, 1,000자 이내)와 화면이 읽은 버전 번호(다르면 409) */
    public record ExclusionRequest(String reason, Long version) {
    }

    // ------------------------------------------------------------------ 요약

    /**
     * 규칙별 : 지금 쓰는지, 미해결(예외 아님) · 미해결 중 예외 · 해결(실제 개선) · 해결(대상 삭제) 건수.
     */
    public record RuleSummary(StandardizationRule rule, Severity severity, boolean active, long open, long openExcluded, long ruleSatisfied,
                              long targetDeleted) {
    }

    /**
     * 조회 시점의 현재 결과를 센다 (예외 처리·해제가 바로 반영된다 — 과거 실행의 건수({@link Run#totals})와 시점이 다르다).
     * 기본 집계(open·bySeverity)는 지금 쓰는 규칙의 미해결 + 예외 아님만 센다 (AI6-D14·D24·D26). 끈 규칙의 미해결은 inactiveOpen 에 따로,
     * 해결은 실제 개선(ruleSatisfied)과 대상 삭제(targetDeleted)를 나눈다 (AI6-D25). lastRun = 마지막으로 결과를 확정한 실행(기준 일시가 결과의 시점),
     * activeRun = 대기·진행 중 실행, latestRun = 상태와 무관한 최근 실행 (실패·중단이면 결과는 그 전 실행 기준이다).
     */
    public record Summary(Run lastRun, Run activeRun, Run latestRun, long open, Map<Severity, Long> bySeverity, long openExcluded, long inactiveOpen,
                          long ruleSatisfied, long targetDeleted, List<RuleSummary> rules) {
    }
}
