package com.lexorma.ai.tool;

import com.lexorma.ai.evidence.EvidenceCatalog;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.ai.tool.QualityMetadataDto.ChangeSignal;
import com.lexorma.ai.tool.QualityMetadataDto.CurrentCounts;
import com.lexorma.ai.tool.QualityMetadataDto.FindingItem;
import com.lexorma.ai.tool.QualityMetadataDto.Freshness;
import com.lexorma.ai.tool.QualityMetadataDto.HistoryItem;
import com.lexorma.ai.tool.QualityMetadataDto.Limit;
import com.lexorma.ai.tool.QualityMetadataDto.RunBrief;
import com.lexorma.ai.tool.QualityMetadataDto.RuleStatus;
import com.lexorma.ai.tool.QualityToolDefinitions.QualityFindingRef;
import com.lexorma.ai.tool.QualityToolDefinitions.QualityFindingSearch;
import com.lexorma.ai.tool.QualityToolDefinitions.QualityOverviewRequest;
import com.lexorma.ai.tool.QualityToolDefinitions.RunRelation;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRuleGuide;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.domain.InspectionCodes;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.repository.InspectionQueryRepository;
import com.lexorma.data.inspection.service.InspectionJudge;
import com.lexorma.data.inspection.service.InspectionQueryService;
import com.lexorma.data.inspection.service.InspectionRuleSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI-7 품질 진단 Tool 3개의 실행 ({@link QualityToolDefinitions.Handlers}). 내부 AI(QUALITY 범위)와 MCP 가 같은 이 코드를 {@link MetadataToolset} 경계 안에서 쓴다 —
 * 실행 컨텍스트 재확인(VIEWER)·읽기 전용 트랜잭션(REPEATABLE READ, 한 Tool 호출 안의 조회가 한 스냅숏)·결과 24,000자·근거 체크포인트는 경계가 한다.
 * <ul>
 *   <li>프로젝트는 {@link MetadataToolSession#scope()} (서버가 정한 실행 컨텍스트)에서만 온다. 결과·실행 ID 도 그 프로젝트 안에서만 찾는다 (없으면 NOT_FOUND)</li>
 *   <li>저장 결과만 읽고 다시 판정하지 않는다. 대상 모델 객체를 근거로 등록하지 않는다 (AI7-D10) — 품질 결과·실행만 QUALITY_FINDING·QUALITY_RUN</li>
 *   <li>반환 제한 (AI7-D15) : 목록 기본 10·최대 20, 최근 실행 5, 이력 10, 근거 배열 20개, 문자열 300자 — 줄인 곳은 전체 수·원문 길이를 함께 준다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class QualityMetadataTools implements QualityToolDefinitions.Handlers {

    static final int RECENT_RUNS = 5;
    static final int HISTORY_MAX = 10;
    static final int ARRAY_MAX = 20;
    static final int TEXT_MAX = MetadataTools.TEXT_MAX;
    /** 변경 감지 건수를 세는 상한 (그 이상은 capped) */
    static final int CHANGE_CAP = 1_000;
    /** 너무 먼 페이지 (오프셋 넘침 방지) */
    static final int MAX_PAGE = 100_000;

    private final InspectionQueryService queries;
    private final InspectionRuleSettingService ruleSettings;
    private final EvidenceCatalog evidenceCatalog;

    // ------------------------------------------------------------------ getQualityInspectionOverview

    @Override
    public Object overview(QualityOverviewRequest input, ToolContext context) {
        MetadataToolSession session = MetadataToolSession.from(context);
        long projectId = session.scope().getProjectId();
        if (input != null && input.ruleCode() != null) {
            // 규칙 안내 (AI7-D35~D37) : 안내 카탈로그 + 이 프로젝트의 지금 규칙 사용 여부만. 결과·실행을 읽지 않으므로 근거를 등록하지 않는다
            if (input.runId() != null) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "runId 와 ruleCode 는 함께 지정할 수 없습니다.");
            if (input.ruleCode().isBlank()) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "ruleCode 가 비어 있습니다.");
            StandardizationRule rule = rule(input.ruleCode());
            boolean active = ruleSettings.rules(projectId).stream().filter(r -> r.rule() == rule).findFirst().map(InspectionDto.RuleSetting::active).orElse(true);
            return new QualityMetadataDto.RuleInfo(QualityMetadataDto.SOURCE_RULE_GUIDE, "RULE_GUIDE_AND_SETTING_AT_QUERY_TIME", guide(rule), active, RULE_INFO_NOTE);
        }
        if (input != null && input.runId() != null) {
            InspectionDto.Run run = queries.run(projectId, input.runId());
            saw(session, EvidenceType.QUALITY_RUN, List.of(run.id()));
            return new QualityMetadataDto.RunDetail(QualityMetadataDto.SOURCE, "RUN_AGGREGATE_AT_STORE_TIME", brief(run),
                    run.rules().stream().map(r -> new QualityMetadataDto.RunRuleCount(r.rule().name(), r.status().name(),
                            r.resultCount() == null || r.resultCount() == 0 ? null : r.resultCount(), names(r.counts()))).toList());
        }
        InspectionDto.Summary summary = queries.summary(projectId);
        List<InspectionDto.RuleSetting> settings = ruleSettings.rules(projectId);
        List<InspectionDto.Run> recent = queries.runs(projectId, PageRequest.of(0, RECENT_RUNS)).getContent();
        long totalRuns = queries.runCount(projectId);
        InspectionDto.Run basis = summary.lastRun();
        InspectionDto.Run latest = summary.latestRun();
        Set<StandardizationRule> failedLatest = latest == null ? Set.of() : Set.copyOf(latest.failedRules());
        Map<StandardizationRule, InspectionDto.RuleSummary> counts = summary.rules().stream()
                .collect(Collectors.toMap(InspectionDto.RuleSummary::rule, Function.identity()));
        List<RuleStatus> rules = new ArrayList<>();
        Long basisId = basis == null ? null : basis.id();
        for (InspectionDto.RuleSetting setting : settings) {
            InspectionDto.RuleSummary c = counts.get(setting.rule());
            boolean judgedInBasis = basisId != null && basisId.equals(setting.lastInspectedRunId());
            // 기준 실행이 있으면 open 은 0 도 싣는다 (AI-7 3차 : 생략한 0 을 실제 모델이 '미확인'·임의 건수로 읽었다). 기준 실행이 없으면 건수 자체가 없다
            Long open = basisId == null ? null : Long.valueOf(c == null ? 0 : c.open());
            Long openExcluded = basisId == null ? null : nonZero(c == null ? 0 : c.openExcluded());
            rules.add(new RuleStatus(setting.rule().name(), setting.severity().name(), open, openExcluded,
                    setting.active() ? null : Boolean.TRUE, judgedInBasis ? null : setting.lastInspectedRunId(), judgedInBasis ? null : setting.lastInspectedAt(),
                    setting.recheckNeeded() ? Boolean.TRUE : null, failedLatest.contains(setting.rule()) ? Boolean.TRUE : null));
        }
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        summary.bySeverity().forEach((severity, n) -> bySeverity.put(severity.name(), n));
        // 확정한 실행이 없으면 저장 결과가 없다 : 0 건이 아니라 '건수 없음' (검사 이력 없음 ↔ 결과 0건을 섞지 않는다)
        CurrentCounts current = basisId == null ? null : new CurrentCounts("CURRENT_AT_QUERY_TIME", summary.open(), bySeverity, summary.openExcluded(),
                summary.inactiveOpen(), summary.ruleSatisfied(), summary.targetDeleted());
        Freshness freshness = freshness(projectId, totalRuns, basis, summary.activeRun(), latest, settings);

        Set<Long> runIds = new LinkedHashSet<>();
        for (InspectionDto.Run run : new InspectionDto.Run[]{basis, summary.activeRun(), latest}) if (run != null) runIds.add(run.id());
        recent.forEach(run -> runIds.add(run.id()));
        saw(session, EvidenceType.QUALITY_RUN, List.copyOf(runIds));
        boolean basisListed = basis == null || recent.stream().anyMatch(run -> run.id() == basis.id());
        return new QualityMetadataDto.Overview(QualityMetadataDto.SOURCE, basisId, summary.activeRun() == null ? null : summary.activeRun().id(),
                latest == null ? null : latest.id(), basisListed ? null : brief(basis), current, basisId == null ? NO_BASIS_NOTE : RULES_NOTE, rules,
                recent.stream().map(QualityMetadataTools::brief).toList(), totalRuns, totalRuns > recent.size() ? "OLDER_RUNS_ONLY_BY_RUN_ID" : null, freshness);
    }

    /** 규칙 안내 (요청 언어) — 결과 상세와 ruleCode 조회가 같은 모양을 쓴다 */
    private static QualityMetadataDto.RuleGuide guide(StandardizationRule rule) {
        StandardizationRuleGuide.Guide guide = StandardizationRuleGuide.guide(rule);
        return new QualityMetadataDto.RuleGuide(rule.name(), guide.name(), guide.description(), guide.fixHint(), InspectionJudge.group(rule).name(),
                rule.targetType().name(), rule.severity().name());
    }

    /** ruleCode 조회 결과가 무엇이 아닌지 (모델이 읽는 설명) */
    static final String RULE_INFO_NOTE = "General guide and this project's current setting, not a judgment: it says nothing about findings or past runs. "
            + "Use searchQualityFindings with rule for stored findings.";

    /** 규칙 행에서 뺀 기본값 (모델이 읽는 설명). open 은 생략하지 않는다 (AI-7 3차) */
    static final String RULES_NOTE = "open is always given. Omitted in rules: openExcluded 0, disabled false, flags false; no lastJudgedRunId means judged in the basis run.";

    /** 확정한 실행이 없을 때 (검사 이력 없음 · 모든 실행 실패·진행 중) : 건수·current 를 싣지 않는다 */
    static final String NO_BASIS_NOTE = "No completed inspection run: nothing is stored, so there are no counts (unknown, not 0) and no current.";

    private static Long nonZero(long value) {
        return value == 0 ? null : value;
    }

    /** 실행 상태 · 규칙별 판정 · 영역별 변경 감지를 따로 판단한다 (서로 섞지 않는다) */
    private Freshness freshness(long projectId, long totalRuns, InspectionDto.Run basis, InspectionDto.Run active, InspectionDto.Run latest,
                                List<InspectionDto.RuleSetting> settings) {
        String runState = runState(totalRuns, active, latest);
        if (totalRuns == 0) return new Freshness("NOT_INSPECTED", runState, null, null, null, null, "NOT_GUARANTEED");
        if (basis == null || basis.basisAt() == null) {
            List<ChangeSignal> unknown = new ArrayList<>();
            for (String area : List.of("MODEL", "DICTIONARY", "PROJECT_SETTINGS", "RULE_SETTINGS", "EXCLUSIONS")) {
                unknown.add(new ChangeSignal(area, "UNKNOWN", null, null, null, null));
            }
            return new Freshness("NO_COMPLETED_RUN", runState, null, null, null, unknown, "NOT_GUARANTEED");
        }
        LocalDateTime comparedFrom = basis.startedAt() != null ? basis.startedAt() : basis.basisAt();
        InspectionQueryRepository.ChangeCounts changes = queries.changeCounts(projectId, comparedFrom, basis.basisAt(), CHANGE_CAP);
        Set<StandardizationRule> appliedInBasis = basis.rules().stream().map(InspectionDto.RunRule::rule).collect(Collectors.toSet());
        Set<StandardizationRule> activeNow = settings.stream().filter(InspectionDto.RuleSetting::active).map(InspectionDto.RuleSetting::rule).collect(Collectors.toSet());
        // 지금 켜진 규칙 ↔ 기준 실행이 적용한 규칙 : 한쪽에만 있는 규칙 수
        Set<StandardizationRule> differ = java.util.EnumSet.noneOf(StandardizationRule.class);
        for (StandardizationRule rule : StandardizationRule.values()) {
            if (appliedInBasis.contains(rule) != activeNow.contains(rule)) differ.add(rule);
        }
        // 감지 방법(coverage)·볼 수 없는 변경(notCovered)은 짧은 코드 — 뜻은 설계 문서 22.6 (모델에게는 notCovered 가 있으면 그 변경은 바뀌었을 수 있다는 뜻)
        List<ChangeSignal> signals = List.of(
                signal("MODEL", changes.model(), "MODEL_HISTORY", List.of("WRITES_WITHOUT_HISTORY", "TX_STARTED_BEFORE_RUN")),
                signal("DICTIONARY", changes.dictionary(), "WORD_TERM_DOMAIN_ROWS", List.of("DELETIONS", "WORD_SYNONYMS", "FORBIDDEN_WORDS", "TERM_WORD_LINKS")),
                signal("PROJECT_SETTINGS", changes.project(), "PROJECT_ROW", List.of("WHICH_FIELD")),
                new ChangeSignal("RULE_SETTINGS", differ.isEmpty() ? "NO_CHANGE_DETECTED" : "CHANGE_DETECTED", differ.isEmpty() ? null : (long) differ.size(), null,
                        "ENABLED_VS_BASIS_RULES", List.of("TOGGLED_BACK")),
                signal("EXCLUSIONS", changes.exclusions(), "EXCLUSION_HISTORY", null));
        List<String> notJudged = settings.stream().filter(InspectionDto.RuleSetting::active).filter(s -> !Long.valueOf(basis.id()).equals(s.lastInspectedRunId()))
                .map(s -> s.rule().name()).toList();
        return new Freshness("BASED_ON_RUN", runState, basis.basisAt(), comparedFrom, notJudged, signals, "NOT_GUARANTEED");
    }

    private static ChangeSignal signal(String area, long count, String coverage, List<String> notCovered) {
        boolean capped = count > CHANGE_CAP;
        return new ChangeSignal(area, count > 0 ? "CHANGE_DETECTED" : "NO_CHANGE_DETECTED", count > 0 ? Math.min(count, CHANGE_CAP) : null, capped ? Boolean.TRUE : null,
                coverage, notCovered);
    }

    private static String runState(long totalRuns, InspectionDto.Run active, InspectionDto.Run latest) {
        if (totalRuns == 0 || latest == null) return "NONE";
        if (active != null) return active.status() == RunStatus.PENDING ? "RUN_PENDING" : "RUN_IN_PROGRESS";
        return switch (latest.status()) {
            case FAILED -> "LATEST_RUN_FAILED";
            case INTERRUPTED -> "LATEST_RUN_INTERRUPTED";
            case COMPLETED -> latest.appliedRuleCount() == 0 ? "LATEST_RUN_NO_RULES" : latest.partialFailure() ? "LATEST_RUN_PARTIAL" : "LATEST_RUN_COMPLETED";
            case PENDING -> "RUN_PENDING";
            case RUNNING -> "RUN_IN_PROGRESS";
        };
    }

    private static RunBrief brief(InspectionDto.Run run) {
        Map<String, Integer> counts = names(run.totals());
        return new RunBrief(run.id(), run.status().name(), run.errorCode(), run.startedAt() == null ? run.requestedAt() : null, run.startedAt(), run.basisAt(),
                run.endedAt(), run.appliedRuleCount(), run.partialFailure() ? Boolean.TRUE : null,
                run.failedRules().isEmpty() ? null : run.failedRules().stream().map(Enum::name).toList(), counts);
    }

    /** 0 이 아닌 건수만 (없으면 null) */
    private static <E extends Enum<E>> Map<String, Integer> names(Map<E, Integer> counts) {
        Map<String, Integer> named = new LinkedHashMap<>();
        counts.forEach((key, value) -> {
            if (value != null && value != 0) named.put(key.name(), value);
        });
        return named.isEmpty() ? null : named;
    }

    // ------------------------------------------------------------------ searchQualityFindings

    @Override
    public Object search(QualityFindingSearch input, ToolContext context) {
        MetadataToolSession session = MetadataToolSession.from(context);
        long projectId = session.scope().getProjectId();
        QualityFindingSearch in = input == null ? new QualityFindingSearch(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null) : input;
        StandardizationRule rule = rule(in.rule());
        RunRelation relation = in.runRelation();
        if ((relation == null) != (in.runId() == null)) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "runId 와 runRelation 은 함께 지정해야 합니다.");
        InspectionDto.StatusFilter status = in.status();
        // 기본값으로 채운 조건 (AI7-D44) : 사용자가 준 조건·RESOLVED_IN/endReason 이 정한 상태는 기본값이 아니다
        boolean statusDefaulted = status == null && relation != RunRelation.RESOLVED_IN && in.endReason() == null
                && in.lastChange() != InspectionCodes.LastChange.RESOLVED;
        if (relation == RunRelation.RESOLVED_IN) {
            if (status != null && status != InspectionDto.StatusFilter.RESOLVED) {
                throw new CustomException(ErrorCode.AI_QUERY_INVALID, "RESOLVED_IN 은 해결된 결과만 대상이라 status 를 생략하거나 RESOLVED 로 지정해야 합니다.");
            }
            status = InspectionDto.StatusFilter.RESOLVED;
        }
        // 해결된 결과에만 있는 조건 (endReason · lastChange RESOLVED) : 기본 status OPEN 과 함께면 언제나 0 건이라 모델이 '해결된 결과 없음'으로 답했다 (AI-7 3차 실제 모델).
        // RESOLVED_IN 과 같은 규칙 — 생략하면 RESOLVED, OPEN 이면 거절 (ALL 은 그대로 둔다 : 해결된 결과만 맞는다)
        if (in.endReason() != null || in.lastChange() == InspectionCodes.LastChange.RESOLVED) {
            if (status == InspectionDto.StatusFilter.OPEN) {
                throw new CustomException(ErrorCode.AI_QUERY_INVALID, "endReason·lastChange RESOLVED 는 해결된 결과에만 있어 status 를 생략하거나 RESOLVED·ALL 로 지정해야 합니다.");
            }
            if (status == null) status = InspectionDto.StatusFilter.RESOLVED;
        }
        // 실행도 이 프로젝트 것이어야 한다 (없으면 NOT_FOUND — 다른 프로젝트 실행이 있는지 알리지 않는다)
        if (in.runId() != null) queries.run(projectId, in.runId());
        int page = in.page() == null ? 0 : in.page();
        if (page < 0 || page > MAX_PAGE) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "page 는 0 이상 {0} 이하여야 합니다.", MAX_PAGE);
        int limit = in.limit() == null ? MetadataTools.DEFAULT_LIMIT : Math.max(1, Math.min(MetadataTools.MAX_LIMIT, in.limit()));
        String keyword = in.keyword() == null ? null : in.keyword().strip();
        if (keyword != null && keyword.length() > MetadataTools.KEYWORD_MAX) {
            throw new CustomException(ErrorCode.AI_QUERY_INVALID, "검색어는 {0}자까지 입력할 수 있습니다.", MetadataTools.KEYWORD_MAX);
        }
        if (keyword != null && keyword.isEmpty()) keyword = null;
        InspectionDto.Search search = new InspectionDto.Search(rule, in.severity(), in.targetType() == null ? null : in.targetType().name(), status, in.exclusion(),
                in.ruleScope(), in.endReason(), in.tableId(), keyword, in.group() == null ? null : in.group().name(), in.lastChange(),
                relation == RunRelation.FIRST_SEEN ? in.runId() : null, relation == RunRelation.RESOLVED_IN ? in.runId() : null);
        List<String> defaulted = new ArrayList<>();
        if (statusDefaulted) defaulted.add("status");
        if (in.exclusion() == null) defaulted.add("exclusion");
        if (in.ruleScope() == null) defaulted.add("ruleScope");
        // 0 건이면 기본값만 넓힌 같은 조건의 존재 여부를 같은 스냅숏에서 한 번 본다 (그 밖의 조건 · 실행 관계 · endReason 은 그대로)
        InspectionDto.Search relaxed = defaulted.isEmpty() ? null : new InspectionDto.Search(search.rule(), search.severity(), search.targetType(),
                statusDefaulted ? InspectionDto.StatusFilter.ALL : search.status(), in.exclusion() == null ? InspectionDto.ExclusionFilter.ALL : search.exclusion(),
                in.ruleScope() == null ? InspectionDto.RuleScope.ALL : search.ruleScope(), search.endReason(), search.tableId(), search.searchQuery(), search.group(),
                search.lastChange(), search.firstSeenRunId(), search.resolvedInRunId());
        InspectionQueryService.FindingsWithOutside result = queries.findings(projectId, search, PageRequest.of(page, limit), relaxed);
        Page<InspectionDto.Finding> found = result.page();
        List<FindingItem> items = found.getContent().stream().map(QualityMetadataTools::item).toList();
        boolean hasMore = (long) page * limit + items.size() < found.getTotalElements();
        QualityMetadataDto.Filters filters = new QualityMetadataDto.Filters(status == null ? "OPEN" : status.name(),
                in.exclusion() == null ? "NOT_EXCLUDED" : in.exclusion().name(), in.ruleScope() == null ? "ACTIVE" : in.ruleScope().name(),
                rule == null ? null : rule.name(), name(in.severity()), name(in.targetType()), name(in.group()), name(in.lastChange()), name(in.endReason()),
                in.tableId(), keyword, in.runId(), name(relation), page, limit, defaulted.isEmpty() ? null : List.copyOf(defaulted));
        boolean zero = found.getTotalElements() == 0;
        QualityMetadataDto.OutsideDefaults outside = !zero ? null : defaulted.isEmpty()
                ? new QualityMetadataDto.OutsideDefaults("NOT_CHECKED", null)
                : new QualityMetadataDto.OutsideDefaults(Boolean.TRUE.equals(result.outsideExists()) ? "EXISTS" : "NOT_EXISTS", List.copyOf(defaulted));
        saw(session, EvidenceType.QUALITY_FINDING, items.stream().map(FindingItem::findingId).toList());
        LocalDateTime basisAt = queries.lastCompletedRun(projectId).map(InspectionDto.Run::basisAt).orElse(null);
        return new QualityMetadataDto.FindingList(QualityMetadataDto.SOURCE, basisAt, filters, in.runId() == null ? null : "CURRENT_RESULTS_LINKED_TO_RUN",
                found.getTotalElements(), items.size(), hasMore, hasMore ? next(filters, page + 1) : null, zero ? ZERO_MEANS : null, outside, items);
    }

    /** 0 건의 뜻 (AI7-D45 — MCP 클라이언트도 읽는다) */
    static final String ZERO_MEANS = "No stored finding matched these filters; not proof that the project has no quality issues.";

    private static StandardizationRule rule(String code) {
        if (code == null || code.isBlank()) return null;
        try {
            return StandardizationRule.valueOf(code.strip());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.AI_QUERY_INVALID, "알 수 없는 규칙 코드입니다 : {0}. getQualityInspectionOverview 의 rules 에 있는 코드를 쓰세요.",
                    code.length() > 60 ? code.substring(0, 60) : code);
        }
    }

    private static QualityMetadataDto.NextCall next(QualityMetadataDto.Filters f, int page) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (f.rule() != null) args.put("rule", f.rule());
        if (f.severity() != null) args.put("severity", f.severity());
        if (f.targetType() != null) args.put("targetType", f.targetType());
        if (f.group() != null) args.put("group", f.group());
        args.put("status", f.status());
        args.put("exclusion", f.exclusion());
        args.put("ruleScope", f.ruleScope());
        if (f.lastChange() != null) args.put("lastChange", f.lastChange());
        if (f.endReason() != null) args.put("endReason", f.endReason());
        if (f.tableId() != null) args.put("tableId", f.tableId());
        if (f.keyword() != null) args.put("keyword", f.keyword());
        if (f.runId() != null) args.put("runId", f.runId());
        if (f.runRelation() != null) args.put("runRelation", f.runRelation());
        args.put("page", page);
        args.put("limit", f.limit());
        return new QualityMetadataDto.NextCall(QualityToolDefinitions.SEARCH, args);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static FindingItem item(InspectionDto.Finding f) {
        List<Limit> limits = new ArrayList<>();
        return new FindingItem(f.id(), f.rule().name(), f.severity().name(), f.targetType(), f.targetId(), f.tableId(), text(f.targetName(), "targetName", limits),
                f.status().name(), f.excluded(), f.ruleActive(), f.lastChange().name(), name(f.endReason()), f.firstSeenAt(), f.lastSeenAt(), f.endedAt(),
                f.firstRunId(), f.lastRunId(), text(f.actualValue(), "actualValue", limits), text(f.expectedValue(), "expectedValue", limits),
                limits.isEmpty() ? null : limits);
    }

    // ------------------------------------------------------------------ getQualityFindingDetail

    @Override
    public Object detail(QualityFindingRef input, ToolContext context) {
        MetadataToolSession session = MetadataToolSession.from(context);
        long projectId = session.scope().getProjectId();
        if (input == null || input.findingId() == null) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "findingId 가 필요합니다.");
        InspectionDto.FindingDetail detail = queries.finding(projectId, input.findingId());
        InspectionDto.Finding f = detail.finding();
        List<Limit> limits = new ArrayList<>();
        JsonNode evidence = limited(detail.evidence(), "", limits);
        List<InspectionDto.History> all = detail.history();
        List<HistoryItem> recent = new ArrayList<>();
        // 최근 것부터 HISTORY_MAX 건 (저장 순서 = 일련번호 오름차순)
        for (int i = all.size() - 1; i >= 0 && recent.size() < HISTORY_MAX; i--) {
            InspectionDto.History h = all.get(i);
            String reason = h.reason();
            int length = reason == null ? 0 : reason.codePointCount(0, reason.length());
            boolean partial = length > TEXT_MAX;
            recent.add(new HistoryItem(h.type().name(), h.status().name(), h.excluded(), partial ? cut(reason) : reason, partial ? Boolean.TRUE : null,
                    partial ? length : null, h.runId(), name(h.endReason()), h.endedAt(), h.createdAt()));
        }
        QualityMetadataDto.RuleGuide ruleGuide = guide(f.rule());
        saw(session, EvidenceType.QUALITY_FINDING, List.of(f.id()));
        LocalDateTime basisAt = queries.lastCompletedRun(projectId).map(InspectionDto.Run::basisAt).orElse(null);
        return new QualityMetadataDto.FindingDetail(QualityMetadataDto.SOURCE, basisAt, item(f), detail.targetExists(), evidence, limits.isEmpty() ? null : limits,
                new QualityMetadataDto.Window<>(all.size(), recent.size(), all.size() > recent.size(), recent), ruleGuide,
                List.of("finding.targetName", "storedEvidence", "history.reason"));
    }

    /**
     * 저장 근거를 반환 제한대로 새로 만든다 (원본을 고치지 않고, 넘는 배열 원소는 복사하지 않는다) : 배열은 앞 20개, 문자열은 300자.
     * 줄인 곳은 limits 에 경로·전체 수와 함께 남긴다 — 결과를 조용히 자르지 않는다. 숫자·불린·null 은 그대로
     */
    static JsonNode limited(JsonNode node, String path, List<Limit> limits) {
        if (node == null || node.isNull()) return JsonNodeFactory.instance.nullNode();
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) copy.set(e.getKey(), limited(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), limits));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            int index = 0;
            for (JsonNode child : node) {
                if (index >= ARRAY_MAX) break;
                copy.add(limited(child, path + "[" + index + "]", limits));
                index++;
            }
            if (node.size() > ARRAY_MAX) limits.add(new Limit(path, "ARRAY", node.size(), ARRAY_MAX));
            return copy;
        }
        if (node.isString()) {
            String value = node.stringValue();
            int length = value.codePointCount(0, value.length());
            if (length <= TEXT_MAX) return node;
            limits.add(new Limit(path, "TEXT", length, TEXT_MAX));
            return JsonNodeFactory.instance.stringNode(cut(value));
        }
        return node;
    }

    /** 문자열 제한 : 원문 그대로이거나 앞 300 코드 포인트 + "…" (서로게이트 쌍을 가르지 않는다), 줄였으면 limits 에 남긴다 */
    private static String text(String value, String path, List<Limit> limits) {
        if (value == null) return null;
        int length = value.codePointCount(0, value.length());
        if (length <= TEXT_MAX) return value;
        limits.add(new Limit(path, "TEXT", length, TEXT_MAX));
        return cut(value);
    }

    private static String cut(String value) {
        return value.substring(0, value.offsetByCodePoints(0, TEXT_MAX)) + "…";
    }

    private void saw(MetadataToolSession session, EvidenceType type, List<Long> ids) {
        session.saw(evidenceCatalog.load(type, ids).values());
    }
}
