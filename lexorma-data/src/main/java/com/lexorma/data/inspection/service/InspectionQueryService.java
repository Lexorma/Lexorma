package com.lexorma.data.inspection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.inspection.domain.InspectionCodes;
import com.lexorma.data.inspection.domain.InspectionCodes.EndReason;
import com.lexorma.data.inspection.domain.InspectionCodes.RuleRunStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.FindingStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.RunChange;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.repository.InspectionQueryRepository;
import com.lexorma.data.inspection.repository.InspectionRepository;
import com.lexorma.data.inspection.repository.InspectionRepository.RunCountRow;
import com.lexorma.data.inspection.repository.InspectionRepository.RunRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 저장 품질 검사 결과 조회 (AI-6 2차, 읽기 전용). 화면(3차)과 AI-7 Tool 이 같은 서비스를 쓰도록 프로젝트 ID 만 받는다 —
 * 프로젝트 범위는 호출하는 쪽이 로그인 정보·검증된 실행 컨텍스트에서 가져온다 (모델이 준 값을 쓰지 않는다).
 * 조회는 저장된 결과만 읽고 다시 판정하지 않는다 (AI6-D31).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionQueryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InspectionRepository repository;
    private final InspectionQueryRepository queries;

    public InspectionDto.Summary summary(long projectId) {
        Set<StandardizationRule> disabled = repository.disabledRules(projectId);
        Map<StandardizationRule, long[]> byRule = new EnumMap<>(StandardizationRule.class);
        for (StandardizationRule rule : StandardizationRule.values()) byRule.put(rule, new long[4]);
        for (InspectionQueryRepository.Count count : queries.counts(projectId)) {
            long[] values = byRule.get(count.rule());
            if (count.status() == FindingStatus.OPEN) values[count.excluded() ? 1 : 0] += count.count();
            else if (count.endReason() == EndReason.TARGET_DELETED) values[3] += count.count();
            else values[2] += count.count();
        }
        Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) bySeverity.put(severity, 0L);
        long open = 0;
        long openExcluded = 0;
        long inactiveOpen = 0;
        long ruleSatisfied = 0;
        long targetDeleted = 0;
        List<InspectionDto.RuleSummary> rules = new ArrayList<>();
        for (Map.Entry<StandardizationRule, long[]> entry : byRule.entrySet()) {
            StandardizationRule rule = entry.getKey();
            long[] v = entry.getValue();
            boolean active = !disabled.contains(rule);
            rules.add(new InspectionDto.RuleSummary(rule, rule.severity(), active, v[0], v[1], v[2], v[3]));
            if (active) {
                open += v[0];
                bySeverity.merge(rule.severity(), v[0], Long::sum);
                openExcluded += v[1];
                ruleSatisfied += v[2];
                targetDeleted += v[3];
            } else {
                inactiveOpen += v[0];
            }
        }
        InspectionDto.Run last = repository.lastRun(projectId, EnumSet.of(RunStatus.COMPLETED)).map(this::run).orElse(null);
        InspectionDto.Run active = repository.findActiveRun(projectId).map(this::run).orElse(null);
        InspectionDto.Run latest = repository.lastRun(projectId, EnumSet.allOf(RunStatus.class)).map(this::run).orElse(null);
        return new InspectionDto.Summary(last, active, latest, open, bySeverity, openExcluded, inactiveOpen, ruleSatisfied, targetDeleted, rules);
    }

    public Page<InspectionDto.Finding> findings(long projectId, InspectionDto.Search search, Pageable pageable) {
        Set<StandardizationRule> disabled = repository.disabledRules(projectId);
        long total = queries.count(projectId, search, disabled);
        List<InspectionDto.Finding> content = total == 0 ? List.of()
                : queries.page(projectId, search, disabled, pageable.getSort(), pageable.getOffset(), pageable.getPageSize());
        return new PageImpl<>(content, pageable, total);
    }

    public InspectionDto.FindingDetail finding(long projectId, long findingId) {
        InspectionQueryRepository.FindingRow row = queries.find(projectId, findingId, repository.disabledRules(projectId))
                .orElseThrow(() -> new CustomException(ErrorCode.INSPECTION_RESULT_NOT_FOUND));
        InspectionDto.Finding finding = row.finding();
        boolean exists = repository.existingObjects(projectId, InspectionItem.ObjectType.valueOf(finding.targetType()), List.of(finding.targetId()))
                .contains(finding.targetId());
        return new InspectionDto.FindingDetail(finding, row.fingerprint(), JSON.readTree(row.evidence()), queries.history(projectId, findingId), exists);
    }

    public List<InspectionDto.History> history(long projectId, long findingId) {
        queries.find(projectId, findingId, Set.of()).orElseThrow(() -> new CustomException(ErrorCode.INSPECTION_RESULT_NOT_FOUND));
        return queries.history(projectId, findingId);
    }

    public Page<InspectionDto.Run> runs(long projectId, Pageable pageable) {
        long total = repository.countRuns(projectId);
        // 정렬은 요청 순서(실행 일련번호) 하나뿐이다 — 오름차순을 고른 때만 오래된 것부터, 그 밖에는 최근부터
        boolean ascending = pageable.getSort().stream().anyMatch(Sort.Order::isAscending);
        List<InspectionDto.Run> content = repository.runs(projectId, (int) pageable.getOffset(), pageable.getPageSize(), ascending).stream().map(this::run).toList();
        return new PageImpl<>(content, pageable, total);
    }

    public InspectionDto.Run run(long projectId, long runId) {
        return repository.findRun(projectId, runId).map(this::run).orElseThrow(() -> new CustomException(ErrorCode.INSPECTION_RUN_NOT_FOUND));
    }

    private InspectionDto.Run run(RunRow run) {
        Map<StandardizationRule, Map<RunChange, Integer>> counts = new EnumMap<>(StandardizationRule.class);
        Map<RunChange, Integer> totals = zero();
        for (RunCountRow row : repository.runCounts(run.id())) {
            counts.computeIfAbsent(row.rule(), rule -> zero()).merge(row.change(), row.count(), Integer::sum);
            totals.merge(row.change(), row.count(), Integer::sum);
        }
        List<InspectionDto.RunRule> rules = repository.runRules(run.id()).stream()
                .map(rule -> new InspectionDto.RunRule(rule.rule(), rule.rule().severity(), rule.status(), rule.resultCount(),
                        counts.getOrDefault(rule.rule(), zero())))
                .toList();
        List<StandardizationRule> failedRules = rules.stream().filter(rule -> rule.status() == RuleRunStatus.FAILED).map(InspectionDto.RunRule::rule).toList();
        boolean partialFailure = run.status() == RunStatus.COMPLETED && InspectionCodes.ERR_RULE_FAILED.equals(run.errorCode());
        return new InspectionDto.Run(run.id(), run.status(), run.requestedAt(), run.startedAt(), run.basisAt(), run.endedAt(), run.errorCode(),
                run.requestedBy(), rules.size(), partialFailure, failedRules, totals, rules);
    }

    private static Map<RunChange, Integer> zero() {
        Map<RunChange, Integer> map = new EnumMap<>(RunChange.class);
        for (RunChange change : RunChange.values()) map.put(change, 0);
        return map;
    }
}
