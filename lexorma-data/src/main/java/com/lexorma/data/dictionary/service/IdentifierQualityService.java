package com.lexorma.data.dictionary.service;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.IdentifierQualityDto.Issue;
import com.lexorma.data.dictionary.domain.dto.IdentifierQualityDto.Search;
import com.lexorma.data.dictionary.domain.dto.IdentifierQualityDto.Summary;
import com.lexorma.data.dictionary.quality.FindingFingerprint;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.dictionary.quality.StandardizationRule.TargetType;
import com.lexorma.data.table.ddl.DbIdentifierCheck;
import com.lexorma.data.table.ddl.DbIdentifierRules;
import com.lexorma.data.table.repository.DdlIdentifierQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DBMS 식별자 진단 (M-7 보강 1차 — M-5 D3, 조회 전용) : 로그인 프로젝트의 스키마·테이블·컬럼 이름과 DDL 이 만드는 제약조건명을 프로젝트 DBMS 기준으로 판정한다
 * ({@link DbIdentifierCheck}). 아무것도 고치지 않는다.
 * 기존 컬럼 진단 요약({@code /summary}, 대시보드)과 모델 진단({@code /model/*})에는 넣지 않는다 — 그 값과 의미가 그대로다 (따로 조회·집계).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IdentifierQualityService {

    private static final Map<String, Comparator<Issue>> SORTS = Map.of(
            "type", Comparator.comparing(Issue::type),
            "severity", Comparator.comparing(Issue::severity),
            "kind", Comparator.comparing(Issue::kind),
            "schemaName", Comparator.comparing(Issue::schemaName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "tableName", Comparator.comparing(Issue::tableName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "name", Comparator.comparing(Issue::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "length", Comparator.comparing(Issue::length, Comparator.nullsLast(Comparator.naturalOrder())));

    private final DdlIdentifierQueryRepository identifierRepository;
    private final ProjectRepository projectRepository;

    private record Diagnosis(DbType db, DbIdentifierCheck.Model model, List<Issue> issues) {
    }

    public Summary selectSummary(AccountDto accountDto) {
        Diagnosis diagnosis = diagnose(accountDto.getProjectId());
        Map<StandardizationRule, Long> rules = new EnumMap<>(StandardizationRule.class);
        for (StandardizationRule rule : StandardizationRule.values()) {
            if (rule.targetType() == TargetType.IDENTIFIER) {
                rules.put(rule, diagnosis.issues().stream().filter(issue -> issue.type() == rule).count());
            }
        }
        DbIdentifierCheck.Model model = diagnosis.model();
        return new Summary(diagnosis.db(), diagnosis.db().getLabel(), DbIdentifierRules.lengthBasis(diagnosis.db()),
                model.schemas().stream().filter(s -> !s.isDefault()).count(), model.tables().size(), model.columns().size(), rules,
                severity(diagnosis.issues(), Severity.ERROR), severity(diagnosis.issues(), Severity.WARNING), severity(diagnosis.issues(), Severity.INFO));
    }

    public Page<Issue> selectIssues(AccountDto accountDto, Search search, Pageable pageable) {
        // 표준화 품질 진단과 같이 프로젝트 전체를 읽어 메모리에서 판정·필터·페이징한다 (StandardizationQualityService.selectIssues 의 shortcut 참고)
        String keyword = search.searchQuery() == null ? "" : search.searchQuery().trim().toLowerCase(Locale.ROOT);
        boolean byTable = "tableName".equals(search.searchColumn());
        boolean byName = "name".equals(search.searchColumn());
        List<Issue> issues = diagnose(accountDto.getProjectId()).issues().stream()
                .filter(issue -> search.type() == null || issue.type() == search.type())
                .filter(issue -> search.severity() == null || issue.severity() == search.severity())
                .filter(issue -> search.kind() == null || issue.kind() == search.kind())
                .filter(issue -> search.schemaId() == null || search.schemaId().equals(issue.schemaId()))
                .filter(issue -> keyword.isEmpty() || !(byTable || byName) || lower(byTable ? issue.tableName() : issue.name()).contains(keyword))
                .sorted(order(pageable.getSort()))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), issues.size());
        return new PageImpl<>(issues.subList(from, Math.min(from + pageable.getPageSize(), issues.size())), pageable, issues.size());
    }

    private Diagnosis diagnose(Long projectId) {
        DbType db = projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
        DbIdentifierCheck.Model model = identifierRepository.model(projectId, null);
        return new Diagnosis(db, model, issues(projectId, db, model, DbIdentifierCheck.check(db, model)));
    }

    /** 판정 결과 → 진단 행 (이름은 model 에서 찾는다) */
    static List<Issue> issues(long projectId, DbType db, DbIdentifierCheck.Model model, List<DbIdentifierCheck.Finding> findings) {
        Map<Long, String> schemaNames = new HashMap<>();
        model.schemas().forEach(s -> schemaNames.put(s.id(), s.name()));
        Map<Long, String> tableNames = new HashMap<>();
        model.tables().forEach(t -> tableNames.put(t.id(), t.name()));
        Map<Long, String> columnNames = new HashMap<>();
        model.columns().forEach(c -> columnNames.put(c.id(), c.name()));

        List<Issue> issues = new ArrayList<>(findings.size());
        for (DbIdentifierCheck.Finding finding : findings) {
            DbIdentifierRules.Issue issue = finding.issue();
            StandardizationRule rule = StandardizationRule.valueOf(issue.code().name());
            Long target = finding.columnId() != null ? finding.columnId() : finding.tableId() != null ? finding.tableId() : finding.schemaId();
            List<String> related = finding.others().stream()
                    .map(other -> other.ddlName() + " (" + DbIdentifierRules.kindLabel(other.kind()) + ", " + tableNames.getOrDefault(other.tableId(), "-") + ")")
                    .toList();
            List<String> keys = new ArrayList<>(List.of(issue.kind().name(), db.name(), upper(issue.name())));
            if (rule == StandardizationRule.CONSTRAINT_NAME_COLLISION) {
                keys.addAll(finding.others().stream().map(o -> o.kind() + ":" + upper(o.original())).sorted().toList());
            }
            issues.add(new Issue(rule + ":" + issue.kind() + ":" + target + ":" + upper(issue.name()), rule, rule.severity(), issue.kind(),
                    finding.schemaId(), schemaNames.get(finding.schemaId()), finding.tableId(), tableNames.get(finding.tableId()),
                    finding.columnId(), columnNames.get(finding.columnId()), issue.name(), issue.ddlName(), issue.length(), issue.maxLength(),
                    DbIdentifierRules.basis(db, issue.code()), DbIdentifierRules.reason(db, issue, related), DbIdentifierRules.guide(issue.code()), related,
                    FindingFingerprint.of(projectId, rule, List.of(target), keys)));
        }
        return issues;
    }

    private static long severity(List<Issue> issues, Severity severity) {
        return issues.stream().filter(issue -> issue.severity() == severity).count();
    }

    private static Comparator<Issue> order(Sort sort) {
        Comparator<Issue> order = (a, b) -> 0;
        for (Sort.Order requested : sort) {
            Comparator<Issue> comparator = SORTS.get(requested.getProperty());
            if (comparator != null) order = order.thenComparing(requested.isAscending() ? comparator : comparator.reversed());
        }
        return order;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
