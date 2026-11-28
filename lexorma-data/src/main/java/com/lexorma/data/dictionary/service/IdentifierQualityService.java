package com.lexorma.data.dictionary.service;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.data.dictionary.domain.dto.IdentifierQualityDto.Issue;
import com.lexorma.data.dictionary.quality.Finding;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.FindingFingerprint;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.table.ddl.DbIdentifierCheck;
import com.lexorma.data.table.ddl.DbIdentifierRules;
import com.lexorma.data.table.repository.DdlIdentifierQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    private final DdlIdentifierQueryRepository identifierRepository;
    private final ProjectRepository projectRepository;

    private record Diagnosis(DbType db, DbIdentifierCheck.Model model, List<Issue> issues) {
    }

    private Diagnosis diagnose(Long projectId) {
        DbType db = projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
        DbIdentifierCheck.Model model = identifierRepository.model(projectId, null);
        return new Diagnosis(db, model, issues(projectId, db, model, DbIdentifierCheck.check(db, model)));
    }

    /**
     * 저장 검사(AI-6) : 식별자 5규칙의 진단 행을 판정 결과({@link Finding})로 바꾼다 — fingerprint 는 화면 진단과 같다 ({@link #issues}).
     * 대표 대상은 컬럼 → 테이블 → 스키마 순으로 정한 실제 객체다. 호출한 트랜잭션(검사 스냅숏) 안에서 읽는다.
     */
    public List<InspectionItem> inspect(long projectId) {
        Diagnosis diagnosis = diagnose(projectId);
        List<InspectionItem> items = new ArrayList<>(diagnosis.issues().size());
        for (Issue issue : diagnosis.issues()) {
            InspectionItem.ObjectType type = issue.columnId() != null ? InspectionItem.ObjectType.COLUMN
                    : issue.tableId() != null ? InspectionItem.ObjectType.TABLE : InspectionItem.ObjectType.SCHEMA;
            long target = issue.columnId() != null ? issue.columnId() : issue.tableId() != null ? issue.tableId() : issue.schemaId();
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("kind", issue.kind().name());
            evidence.put("dbType", diagnosis.db().name());
            if (issue.ddlName() != null) evidence.put("ddlName", issue.ddlName());
            if (issue.length() != null) evidence.put("length", String.valueOf(issue.length()));
            if (issue.maxLength() != null) evidence.put("maxLength", String.valueOf(issue.maxLength()));
            // 겹치는 이름은 표시 정보(details.related)에 종류 코드로 둔다 — 근거에 판정 때 언어의 문구를 넣지 않는다 (FINAL)
            Finding finding = new Finding(issue.type(), List.of(target), issue.name(), null, evidence, issue.fingerprint());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaName", issue.schemaName());
            details.put("tableName", issue.tableName());
            details.put("columnName", issue.columnName());
            details.put("basis", issue.basis()); // 판정 기준 문구 (서버 기준 문구 그대로 — 화면이 번역 사전으로 표시 언어로 바꾼다)
            if (!issue.relatedObjects().isEmpty()) details.put("related", issue.relatedObjects());
            String owner = StandardizationQualityService.qualified(issue.schemaName(), issue.tableName(), issue.columnName());
            String name = issue.kind() == DbIdentifierRules.Kind.SCHEMA || issue.kind() == DbIdentifierRules.Kind.TABLE || issue.kind() == DbIdentifierRules.Kind.COLUMN
                    ? owner : owner + " · " + issue.name();
            items.add(new InspectionItem(finding, type, target, issue.tableId(), name, details));
        }
        return items;
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
                    FindingFingerprint.of(projectId, rule, List.of(target), keys),
                    // 저장 근거용 : 언어와 무관한 값 (종류 코드) — 화면이 표시 언어로 바꾼다 (FINAL : 판정 때의 한국어 표시 문구를 저장하지 않는다)
                    finding.others().stream().map(other -> {
                        Map<String, String> object = new LinkedHashMap<>();
                        object.put("ddlName", other.ddlName());
                        object.put("kind", other.kind().name());
                        object.put("tableName", tableNames.getOrDefault(other.tableId(), "-"));
                        return object;
                    }).toList()));
        }
        return issues;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
