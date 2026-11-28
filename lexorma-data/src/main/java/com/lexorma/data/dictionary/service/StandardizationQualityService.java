package com.lexorma.data.dictionary.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Attribute;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ColumnRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Issue;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Summary;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.CompletenessRules;
import com.lexorma.data.dictionary.quality.Finding;
import com.lexorma.data.dictionary.quality.FindingFingerprint;
import com.lexorma.data.dictionary.quality.StandardNames;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.TableNameRules;
import com.lexorma.data.dictionary.quality.TableNameRules.TableIssue;
import com.lexorma.data.dictionary.repository.DictionaryUsageRepository;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 표준화 품질 진단 (M-5 2차, 조회 전용) : 로그인 프로젝트의 물리 컬럼을 표준 용어·도메인과 비교한다. 아무것도 고치지 않는다.
 * <ul>
 * <li>표준 용어 미연결 : 컬럼이 표준 용어를 참조하지 않는다 (이름이 같은 용어가 있어도 연결로 보지 않는다)</li>
 * <li>컬럼명 불일치 : 컬럼명 ≠ 연결된 용어의 영문약어 (컬럼 등록·일괄 저장·엑셀 업로드가 쓰는 규칙. 대소문자는 구분하지 않는다)</li>
 * <li>도메인 정의 불일치 : 컬럼과 도메인을 DDL 과 같은 타입 매핑({@link TableDdlGenerator#mapType})으로 프로젝트 DB 타입으로 바꿔 비교한다.
 *     도메인에 정의된 항목(타입, 길이·정밀도, 소수 자릿수)만 본다</li>
 * </ul>
 * 요약과 목록은 같은 계산({@link #diagnose})을 쓴다.
 * <p>모델 품질 진단(M-5 잔여, {@code /model/*}) : 위 컬럼 진단에 테이블명 규칙({@link TableNameRules})을 더해 심각도·대상 유형과 함께 보여 준다.
 * 기존 요약·목록({@link #selectSummary}, {@link #selectIssues})은 값과 의미가 그대로다 — 대시보드가 요약을 쓴다 (D2).
 * 규칙 코드와 심각도는 {@link StandardizationRule}, 판정 결과의 식별자는 {@link FindingFingerprint}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StandardizationQualityService {

    private static final Pattern TYPE_ARGS = Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");
    private static final List<String> NUMERIC_TYPES = List.of("NUMBER", "NUMERIC", "DECIMAL");

    private final DictionaryUsageRepository usageRepository;
    private final ProjectRepository projectRepository;

    public Summary selectSummary(AccountDto accountDto) {
        List<ColumnRow> columns = usageRepository.findQualityColumns(accountDto.getProjectId());
        List<Issue> issues = diagnose(dbType(accountDto.getProjectId()), columns);
        return new Summary(columns.size(),
                issues.stream().map(Issue::columnId).distinct().count(),
                count(issues, IssueType.TERM_NOT_LINKED),
                count(issues, IssueType.COLUMN_NAME_MISMATCH),
                count(issues, IssueType.DOMAIN_DEFINITION_MISMATCH));
    }

    private DbType dbType(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
    }

    // ------------------------------------------------------------------ 모델 품질 진단 (컬럼 + 테이블)

    /** 한 번의 모델 진단 : SQL 4번 (프로젝트, 컬럼, 테이블, 단어 약어) — 테이블·컬럼 수와 무관하다 */
    private record ModelDiagnosis(List<Issue> columnIssues, List<TableIssue> tableIssues) {
    }

    private ModelDiagnosis diagnoseModel(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        DbType db = project == null ? DbType.DEFAULT : DbType.orDefault(project.getDbType());
        String prefix = project == null ? null : TableNameRules.normalizePrefix(project.getTablePrefix());

        List<ColumnRow> columns = usageRepository.findQualityColumns(projectId);
        List<Issue> columnIssues = diagnose(db, columns);
        List<StandardizationQualityDto.TableRow> tables = usageRepository.findQualityTables(projectId);
        List<TableIssue> tableIssues = tables.isEmpty() ? List.of()
                : TableNameRules.diagnose(projectId, prefix, tables, TableNameRules.wordStatuses(usageRepository.findWordAbbreviations(projectId)));
        return new ModelDiagnosis(columnIssues, tableIssues);
    }

    /**
     * 저장 검사(AI-6) : 모델 진단(컬럼 3규칙 + 테이블명 2규칙)의 판정 결과를 그대로 넘긴다 — 화면 진단과 같은 계산({@link #diagnoseModel})이다.
     * 호출한 트랜잭션(검사 스냅숏) 안에서 읽는다.
     */
    public List<InspectionItem> inspect(long projectId) {
        List<InspectionItem> items = new ArrayList<>();
        inspect(projectId, items::add);
        return items;
    }

    /**
     * 같은 판정, 결과를 하나씩 넘긴다 : 저장 검사는 받는 즉시 저장 형태로 바꿔 판정 결과 30만 건을 목록으로 들고 있지 않는다 (AI-6 FINAL 메모리 개선).
     * 넘기는 순서는 목록판과 같다 (테이블 → 컬럼 → 완전성).
     */
    public void inspect(long projectId, java.util.function.Consumer<InspectionItem> items) {
        ModelDiagnosis diagnosis = diagnoseModel(projectId);
        for (TableIssue issue : diagnosis.tableIssues()) {
            StandardizationQualityDto.TableRow table = issue.table();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaName", table.schemaName());
            details.put("tableName", table.tableName());
            if (!issue.tokens().isEmpty()) {
                details.put("tokens", issue.tokens().stream().map(token -> token.token() + ":" + token.status()).toList());
            }
            items.accept(new InspectionItem(issue.finding(), InspectionItem.ObjectType.TABLE, table.tableId(), table.tableId(),
                    qualified(table.schemaName(), table.tableName(), null), details));
        }
        for (Issue issue : diagnosis.columnIssues()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("schemaName", issue.schemaName());
            details.put("tableName", issue.tableName());
            details.put("columnName", issue.columnName());
            items.accept(new InspectionItem(finding(projectId, issue), InspectionItem.ObjectType.COLUMN, issue.columnId(), issue.tableId(),
                    qualified(issue.schemaName(), issue.tableName(), issue.columnName()), details));
        }
        // 완전성 4규칙 (AI-6 3차) : 같은 모델 묶음·같은 스냅숏, 프로젝트 단위 조회 2번 (테이블·컬럼마다 조회하지 않는다)
        diagnosis = null; // 모델 진단 목록은 여기까지 — 완전성 조회 동안 들고 있지 않는다
        CompletenessRules.diagnose(projectId, usageRepository.findCompletenessTables(projectId), usageRepository.findCompletenessColumns(projectId), items);
    }

    /** 표시 이름 : 스키마.테이블(.컬럼) — 빈 부분은 뺀다 */
    public static String qualified(String schema, String table, String column) {
        StringBuilder name = new StringBuilder();
        for (String part : new String[]{schema, table, column}) {
            if (part == null || part.isBlank()) continue;
            if (!name.isEmpty()) name.append('.');
            name.append(part);
        }
        return name.isEmpty() ? "-" : name.toString();
    }

    /**
     * 컬럼 진단 1건의 판정 결과. fingerprint 핵심 값 : 컬럼명 불일치는 기대 컬럼명(대문자), 도메인 불일치는 다른 속성 — 현재 값은 넣지 않는다
     * (컬럼명을 다른 틀린 이름으로 바꿔도 같은 문제다)
     */
    public static Finding finding(long projectId, Issue issue) {
        StandardizationRule rule = issue.type().rule();
        List<String> keys = switch (issue.type()) {
            case TERM_NOT_LINKED -> List.of();
            case COLUMN_NAME_MISMATCH -> List.of(issue.expectedValue() == null ? "" : issue.expectedValue().toUpperCase(Locale.ROOT));
            case DOMAIN_DEFINITION_MISMATCH -> issue.attributes().stream().map(Enum::name).sorted().toList();
        };
        Map<String, String> evidence = new LinkedHashMap<>();
        if (issue.termName() != null) evidence.put("termName", issue.termName());
        if (issue.domainName() != null) evidence.put("domainName", issue.domainName());
        if (!issue.attributes().isEmpty()) evidence.put("attributes", String.join(",", issue.attributes().stream().map(Enum::name).toList()));
        return new Finding(rule, List.of(issue.columnId()), issue.actualValue(), issue.expectedValue(), evidence,
                FindingFingerprint.of(projectId, rule, List.of(issue.columnId()), keys));
    }

    private static long count(List<Issue> issues, IssueType type) {
        return issues.stream().filter(issue -> issue.type() == type).count();
    }

    // ------------------------------------------------------------------ 판정

    /** 컬럼마다 진단 유형별로 최대 1건. rows 순서를 유지한다 */
    static List<Issue> diagnose(DbType db, List<ColumnRow> rows) {
        List<Issue> issues = new ArrayList<>();
        for (ColumnRow row : rows) {
            if (row.termId() == null) {
                issues.add(issue(row, IssueType.TERM_NOT_LINKED, null, null, List.of(), Messages.get("표준 용어가 연결되지 않았습니다.")));
                continue;
            }
            // 영문약어가 없는 용어는 기대 컬럼명을 알 수 없으므로 위반으로 보지 않는다 (컬럼명 규칙 = StandardNames, 물리명 추천·정합성 비교와 같다)
            String expected = StandardNames.expectedColumnName(row.termAbbreviation());
            if (expected != null && !StandardNames.sameColumnName(row.columnName(), expected)) {
                issues.add(issue(row, IssueType.COLUMN_NAME_MISMATCH, row.columnName(), expected, List.of(),
                        Messages.get("컬럼명이 표준 용어의 영문약어와 다릅니다.")));
            }
            if (row.domainId() != null) {
                domainMismatch(db, row, issues);
            }
        }
        return issues;
    }

    /**
     * 컬럼 정의와 도메인 정의의 비교 결과 (프로젝트 DB 타입 문자열). attributes 가 비어 있으면 다른 항목이 없다.
     * 타입이 다르면 actual·expected 는 괄호 앞의 기본 타입이다.
     */
    public record DomainComparison(String actual, String expected, List<Attribute> attributes) {
    }

    private static void domainMismatch(DbType db, ColumnRow row, List<Issue> issues) {
        DomainComparison comparison = compareDomain(db, row.dataType(), row.maxLength(), row.precision(), row.scale(),
                row.domainDataType(), row.domainLength(), row.domainDecimalLength());
        if (!comparison.attributes().isEmpty()) {
            issues.add(issue(row, IssueType.DOMAIN_DEFINITION_MISMATCH, comparison.actual(), comparison.expected(), comparison.attributes(),
                    Messages.get("표준 도메인 정의와 다릅니다 : {0}", String.join(", ", comparison.attributes().stream().map(StandardizationQualityService::label).toList()))));
        }
    }

    /**
     * 컬럼과 도메인을 같은 규칙으로 프로젝트 DB 타입으로 바꿔 비교한다 (예: Oracle 에서 VARCHAR ↔ VARCHAR2, INT ↔ NUMBER(10) 은 같다).
     * 도메인에 없는 항목은 보지 않는다 : 타입이 없으면 길이·소수 자릿수만, 길이가 없으면(0 포함) 타입만.
     * 타입이 다르면 길이는 비교하지 않는다 (서로 다른 타입의 길이는 뜻이 다르다).
     * 논리·물리 정합성 비교(M-7 2차)도 이 규칙을 쓴다.
     */
    public static DomainComparison compareDomain(DbType db, String dataType, Integer maxLength, BigDecimal precision, Integer scale,
                                                 String domainDataType, Integer domainDataLength, Integer domainDecimalLength) {
        boolean domainHasType = domainDataType != null && !domainDataType.isBlank();
        // 길이 0 은 미정의다 (표준 용어 화면도 0 이면 길이를 붙이지 않는다)
        Integer domainLength = domainDataLength == null || domainDataLength == 0 ? null : domainDataLength;
        List<String> ignored = new ArrayList<>(); // 길이 미지정 등 DDL 경고는 여기서 쓰지 않는다

        String actual = TableDdlGenerator.mapType(db, ColumnInfo.builder().dataType(dataType).maxLength(maxLength)
                .precision(precision).scale(scale).build(), ignored);
        String expected = TableDdlGenerator.mapType(db, ColumnInfo.builder().dataType(domainHasType ? domainDataType : dataType)
                .maxLength(domainLength).scale(domainDecimalLength).build(), ignored);
        if (actual.equalsIgnoreCase(expected)) {
            return new DomainComparison(actual, expected, List.of());
        }

        String base = TableDdlGenerator.baseType(actual).toUpperCase(Locale.ROOT);
        List<Attribute> attributes = new ArrayList<>();
        if (!base.equalsIgnoreCase(TableDdlGenerator.baseType(expected))) {
            attributes.add(Attribute.DATA_TYPE);
            actual = base;
            expected = TableDdlGenerator.baseType(expected);
        } else {
            Integer[] actualArgs = typeArgs(actual);
            Integer[] expectedArgs = typeArgs(expected);
            if (domainLength != null && !Objects.equals(actualArgs[0], expectedArgs[0])) {
                attributes.add(NUMERIC_TYPES.contains(base) ? Attribute.PRECISION : Attribute.LENGTH);
            }
            if (domainDecimalLength != null && !Objects.equals(actualArgs[1], expectedArgs[1])) {
                attributes.add(Attribute.SCALE);
            }
        }
        return new DomainComparison(actual, expected, attributes);
    }

    /** "NUMERIC(15,2)" → [15, 2], "VARCHAR(100)" → [100, 0], "DATE" → [null, 0] */
    private static Integer[] typeArgs(String type) {
        Matcher matcher = TYPE_ARGS.matcher(type);
        if (!matcher.find()) {
            return new Integer[]{null, 0};
        }
        return new Integer[]{Integer.valueOf(matcher.group(1)), matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2))};
    }

    private static String label(Attribute attribute) {
        return switch (attribute) {
            case DATA_TYPE -> Messages.get("데이터 타입");
            case LENGTH -> Messages.get("길이");
            case PRECISION -> Messages.get("정밀도");
            case SCALE -> Messages.get("소수 자릿수");
        };
    }

    private static Issue issue(ColumnRow row, IssueType type, String actual, String expected, List<Attribute> attributes, String message) {
        return new Issue(type + ":" + row.columnId(), type, row.schemaId(), row.schemaName(), row.tableId(), row.tableName(), row.tableKorName(),
                row.columnId(), row.columnName(), row.columnDesc(), row.termName(), row.domainName(),
                actual, expected, attributes, message, row.isApproval());
    }
}
