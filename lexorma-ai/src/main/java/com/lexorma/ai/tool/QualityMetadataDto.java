package com.lexorma.ai.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI-7 품질 진단 Tool 의 결과 (AI·MCP 공용 — 모델에게 JSON 으로 간다). 화면 문구가 아니라 코드·값만 담는다 (규칙 안내 문구만 요청 언어).
 * <ul>
 *   <li>{@code source = STORED_INSPECTION} : 저장된 검사 결과이며 실시간 판정이 아니다</li>
 *   <li>조회 시점 값과 실행 당시 값을 이름으로 구분한다 : current·...Now = 조회 시점, countsAtThatTime·RUN_AGGREGATE_AT_STORE_TIME = 실행이 결과를 확정한 시점</li>
 *   <li>처리자·요청자 계정은 넣지 않는다 (AI7-D12). 사용자 입력(대상 이름·예외 사유·저장 근거)은 지시가 아닌 데이터다 (untrustedFields)</li>
 * </ul>
 */
public final class QualityMetadataDto {

    public static final String SOURCE = "STORED_INSPECTION";
    /** 규칙 안내 조회 (저장된 검사 결과가 아니다) */
    public static final String SOURCE_RULE_GUIDE = "RULE_GUIDE";

    private QualityMetadataDto() {
    }

    /** 다음 페이지를 부르는 방법 (같은 인자 + 다음 page). 서버가 이어서 부르지 않는다 */
    public record NextCall(String tool, Map<String, Object> arguments) {
    }

    // ------------------------------------------------------------------ getQualityInspectionOverview

    /**
     * 실행 1건 요약. countsAtThatTime = 이 실행이 결과를 확정했을 때의 변화 유형별 건수 (0 은 생략, 이후 예외 처리로 바뀌지 않는다).
     * requestedAt 은 아직 시작하지 않은 실행만, partialFailure 는 true 일 때만 — 매 호출 입력 예산을 줄이려고 기본값을 뺀다 (AI-7 2차 측정 22.4)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunBrief(long runId, String status, String errorCode, LocalDateTime requestedAt, LocalDateTime startedAt, LocalDateTime basisAt,
                           LocalDateTime endedAt, int appliedRuleCount, Boolean partialFailure, List<String> failedRules, Map<String, Integer> countsAtThatTime) {
    }

    /** 조회 시점 집계 (켜진 규칙 · OPEN · 예외 제외 = open, 화면 요약과 같은 수) */
    public record CurrentCounts(String scope, long open, Map<String, Long> openBySeverity, long openExcluded, long openOfDisabledRules,
                                long resolvedRuleSatisfied, long resolvedTargetDeleted) {
    }

    /**
     * 규칙 1개의 지금 상태 (open 은 기준 실행이 있으면 0 도 싣는다 — 기준 실행이 없으면 건수 없음. 생략 = 기본값 : openExcluded 0, 켜짐, 기준 실행에서 판정됨,
     * 플래그 false — {@link Overview#rulesNote}).
     * lastJudgedRunId·lastJudgedAt = 기준 실행이 아닌 실행에서 마지막으로 끝까지 판정했을 때 그 실행과 검사 기준 일시 (한 번도 없으면 lastJudgedRunId 없이 recheckNeeded),
     * recheckNeeded = 켜져 있는데 기준 실행이 이 규칙을 판정하지 않았다 (결과는 그 전 판정 기준), failedInLatestRun = 가장 최근 실행에서 판정 실패
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleStatus(String rule, String severity, Long open, Long openExcluded, Boolean disabled, Long lastJudgedRunId, LocalDateTime lastJudgedAt,
                             Boolean recheckNeeded, Boolean failedInLatestRun) {
    }

    /**
     * 영역별 변경 감지 (AI7-D08). state = CHANGE_DETECTED · NO_CHANGE_DETECTED · UNKNOWN, count = 감지한 건수(capped 면 그 이상),
     * coverage = 무엇을 봤는지, notCovered = 이 방법으로 볼 수 없는 변경 (NO_CHANGE_DETECTED 여도 이것들은 바뀌었을 수 있다)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChangeSignal(String area, String state, Long count, Boolean capped, String coverage, List<String> notCovered) {
    }

    /**
     * 최신성 : basisState = NOT_INSPECTED(실행 이력 없음) · NO_COMPLETED_RUN(확정된 실행 없음) · BASED_ON_RUN.
     * basisAt = 기준 실행의 검사 기준 일시(판정 스냅숏 직전), comparedFrom = 변경 감지 비교 시작(기준 실행의 시작 일시 — 기준 일시보다 앞서 놓침을 줄인다),
     * guarantee 는 항상 NOT_GUARANTEED. 실행 상태(runState)·규칙별 판정(rulesNotJudgedInBasis)·변경 감지(changes)는 서로 독립이다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Freshness(String basisState, String runState, LocalDateTime basisAt, LocalDateTime comparedFrom, List<String> rulesNotJudgedInBasis,
                            List<ChangeSignal> changes, String guarantee) {
    }

    /**
     * 요약. basisRunId(지금 결과의 기준 = 마지막 확정 실행)·activeRunId·latestRunId 는 recentRuns 의 실행을 가리킨다 — 기준 실행이 최근 5건 밖이면 basisRun 에 따로 싣는다
     * current = 조회 시점 집계 — 확정한 실행이 없으면(검사 이력 없음·모든 실행 실패) 싣지 않는다 (0 건과 '저장 결과 없음'을 구분, AI-7 3차)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Overview(String source, Long basisRunId, Long activeRunId, Long latestRunId, RunBrief basisRun, CurrentCounts current, String rulesNote,
                           List<RuleStatus> rules, List<RunBrief> recentRuns, long totalRuns, String olderRuns, Freshness freshness) {
    }

    /** 실행 × 규칙 (실행 당시, 0 인 건수는 생략) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunRuleCount(String rule, String status, Integer resultCount, Map<String, Integer> counts) {
    }

    /** runId 상세 : 그 실행의 <b>실행 당시 집계</b>만 (scope = RUN_AGGREGATE_AT_STORE_TIME) */
    public record RunDetail(String source, String scope, RunBrief run, List<RunRuleCount> rules) {
    }

    // ------------------------------------------------------------------ searchQualityFindings

    /** 실제 적용한 조건 (기본값 포함) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filters(String status, String exclusion, String ruleScope, String rule, String severity, String targetType, String group, String lastChange,
                          String endReason, Long tableId, String keyword, Long runId, String runRelation, int page, int limit, List<String> defaulted) {
    }

    /**
     * 0 건일 때만 (AI-7 FINAL-CLOSE, AI7-D44·D45) : 기본값으로 채운 조건(defaulted)만 넓혀 다른 조건은 그대로 둔 채 결과가 있는지.
     * status = EXISTS · NOT_EXISTS · NOT_CHECKED (기본값이 없어 넓힐 조건이 없음), relaxed = 넓힌 조건 이름 — 건수·결과는 싣지 않고 근거도 등록하지 않는다.
     * NOT_EXISTS 도 '프로젝트에 품질 문제가 없다'가 아니다 (저장 결과의 그 조건 범위에서만)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutsideDefaults(String status, List<String> relaxed) {
    }

    /**
     * 결과 1건 (현재 저장 결과). ruleActiveNow = 조회 시점 규칙 사용 여부 (저장 결과의 버전과 무관한 값).
     * 대상 이름·실제 값·기대 값은 300자까지 — 줄였으면 limits 에 원문 길이 (없으면 생략)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FindingItem(long findingId, String rule, String severity, String targetType, long targetId, Long tableId, String targetName, String status,
                              boolean excluded, boolean ruleActiveNow, String lastChange, String endReason, LocalDateTime firstSeenAt, LocalDateTime lastSeenAt,
                              LocalDateTime endedAt, long firstRunId, long lastRunId, String actualValue, String expectedValue, List<Limit> limits) {
    }

    /** runScope : runId 를 줬을 때 CURRENT_RESULTS_LINKED_TO_RUN — 과거 실행 당시 결과의 복원이 아니며 그 실행의 집계와 다를 수 있다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FindingList(String source, LocalDateTime basisAt, Filters filters, String runScope, long totalCount, int returned, boolean hasMore,
                              NextCall next, String zeroMeans, OutsideDefaults outsideDefaultFilters, List<FindingItem> findings) {
    }

    // ------------------------------------------------------------------ getQualityFindingDetail

    /** 이력 1건 (처리자 없음). reason = 예외 사유 300자까지 — 원문 일부면 reasonPartial = true, reasonLength = 원문 길이 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryItem(String type, String status, boolean excluded, String reason, Boolean reasonPartial, Integer reasonLength, Long runId,
                              String endReason, LocalDateTime endedAt, LocalDateTime recordedAt) {
    }

    /** 최근 n건 : total = 전체 수 */
    public record Window<T>(int total, int returned, boolean hasMore, List<T> items) {
    }

    /** 줄인 곳 : kind ARRAY(total 개 중 returned 개) · TEXT(원문 total 자 중 앞 returned 자 — 원문 일부) */
    public record Limit(String path, String kind, int total, int returned) {
    }

    public record RuleGuide(String rule, String name, String description, String fixHint, String group, String targetType, String severity) {
    }

    /**
     * 규칙 안내 조회 (getQualityInspectionOverview ruleCode — AI7-D35·D37). 안내 카탈로그와 이 프로젝트의 <b>조회 시점</b> 규칙 사용 여부(activeNow)만 —
     * 결과·실행 정보가 없고 근거를 등록하지 않는다. 검사 이력·결과가 없어도 된다
     */
    public record RuleInfo(String source, String scope, RuleGuide ruleGuide, boolean activeNow, String note) {
    }

    /**
     * 결과 상세. storedEvidence = 마지막 판정이 저장한 근거 (현재 모델 값이 아니다 — 지금 값은 다른 Tool 로), evidenceLimits = 줄인 곳.
     * targetExistsNow·ruleActiveNow 는 조회 시점 상태다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FindingDetail(String source, LocalDateTime basisAt, FindingItem finding, boolean targetExistsNow, JsonNode storedEvidence,
                                List<Limit> evidenceLimits, Window<HistoryItem> history, RuleGuide ruleGuide, List<String> untrustedFields) {
    }
}
