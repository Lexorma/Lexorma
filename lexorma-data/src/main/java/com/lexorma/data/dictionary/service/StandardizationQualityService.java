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
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ModelIssue;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ModelSearch;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ModelSummary;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.NameToken;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Search;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Summary;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.TokenStatus;
import com.lexorma.data.dictionary.quality.Finding;
import com.lexorma.data.dictionary.quality.FindingFingerprint;
import com.lexorma.data.dictionary.quality.StandardNames;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.dictionary.quality.StandardizationRule.TargetType;
import com.lexorma.data.dictionary.quality.TableNameRules;
import com.lexorma.data.dictionary.quality.TableNameRules.TableIssue;
import com.lexorma.data.dictionary.repository.DictionaryUsageRepository;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** 그리드 정렬 필드 (목록에 없는 필드는 무시한다) */
    private static final Map<String, Comparator<Issue>> SORTS = Map.of(
            "type", Comparator.comparing(Issue::type),
            "schemaName", Comparator.comparing(Issue::schemaName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "tableName", Comparator.comparing(Issue::tableName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "columnName", Comparator.comparing(Issue::columnName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "columnDesc", Comparator.comparing(Issue::columnDesc, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "isApproval", Comparator.comparing((Issue issue) -> Boolean.TRUE.equals(issue.isApproval())));

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

    public Page<Issue> selectIssues(AccountDto accountDto, Search search, Pageable pageable) {
        // shortcut: 프로젝트의 컬럼 전체를 한 번에 읽어 메모리에서 진단·필터·페이징한다 (타입 매핑이 자바 코드라 SQL 로 걸러낼 수 없다),
        // 프로젝트 컬럼이 수십만 건이 되면 진단 결과 저장(AI-6) 또는 SQL 로 걸러낼 수 있는 유형부터 분리한다
        String keyword = search.searchQuery() == null ? "" : search.searchQuery().trim().toLowerCase(Locale.ROOT);
        List<Issue> issues = diagnose(dbType(accountDto.getProjectId()), usageRepository.findQualityColumns(accountDto.getProjectId())).stream()
                .filter(issue -> search.type() == null || issue.type() == search.type())
                .filter(issue -> search.schemaId() == null || search.schemaId().equals(issue.schemaId()))
                .filter(issue -> search.isApproval() == null || search.isApproval() == Boolean.TRUE.equals(issue.isApproval()))
                .filter(issue -> keyword.isEmpty()
                        || !("tableName".equals(search.searchColumn()) || "columnName".equals(search.searchColumn()))
                        || lower("tableName".equals(search.searchColumn()) ? issue.tableName() : issue.columnName()).contains(keyword))
                .sorted(order(pageable.getSort()))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), issues.size());
        return new PageImpl<>(issues.subList(from, Math.min(from + pageable.getPageSize(), issues.size())), pageable, issues.size());
    }

    private DbType dbType(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
    }

    // ------------------------------------------------------------------ 모델 품질 진단 (컬럼 + 테이블)

    /** 모델 진단 정렬 필드 */
    private static final Map<String, Comparator<ModelIssue>> MODEL_SORTS = Map.of(
            "type", Comparator.comparing(ModelIssue::type),
            "severity", Comparator.comparing(ModelIssue::severity),
            "targetType", Comparator.comparing(ModelIssue::targetType),
            "schemaName", Comparator.comparing(ModelIssue::schemaName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "tableName", Comparator.comparing(ModelIssue::tableName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "columnName", Comparator.comparing(ModelIssue::columnName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "columnDesc", Comparator.comparing(ModelIssue::columnDesc, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "isApproval", Comparator.comparing((ModelIssue issue) -> Boolean.TRUE.equals(issue.isApproval())));

    /** 기본 순서 : 테이블명 → 테이블 진단 → 그 테이블의 컬럼 진단(컬럼 순서 → 유형) */
    private static final Comparator<ModelIssue> MODEL_DEFAULT_ORDER = Comparator
            .comparing(ModelIssue::tableName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(issue -> issue.targetType() == TargetType.TABLE ? 0 : 1);

    public ModelSummary selectModelSummary(AccountDto accountDto) {
        ModelDiagnosis diagnosis = diagnoseModel(accountDto.getProjectId());
        List<Issue> columnIssues = diagnosis.columnIssues();
        Summary columns = new Summary(diagnosis.totalColumns(),
                columnIssues.stream().map(Issue::columnId).distinct().count(),
                count(columnIssues, IssueType.TERM_NOT_LINKED),
                count(columnIssues, IssueType.COLUMN_NAME_MISMATCH),
                count(columnIssues, IssueType.DOMAIN_DEFINITION_MISMATCH));
        List<ModelIssue> issues = diagnosis.issues();
        return new ModelSummary(columns, diagnosis.totalTables(),
                diagnosis.tableIssues().stream().map(issue -> issue.table().tableId()).distinct().count(),
                diagnosis.tableIssues().stream().filter(issue -> issue.finding().rule() == StandardizationRule.TABLE_PREFIX_MISSING).count(),
                diagnosis.tableIssues().stream().filter(issue -> issue.finding().rule() == StandardizationRule.TABLE_WORD_NOT_STANDARD).count(),
                diagnosis.tablePrefix(),
                issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count(),
                issues.stream().filter(issue -> issue.severity() == Severity.WARNING).count(),
                issues.stream().filter(issue -> issue.severity() == Severity.INFO).count());
    }

    public Page<ModelIssue> selectModelIssues(AccountDto accountDto, ModelSearch search, Pageable pageable) {
        // 컬럼 진단과 같이 프로젝트 전체를 읽어 메모리에서 판정·필터·페이징한다 (위 selectIssues 의 shortcut 참고)
        String keyword = search.searchQuery() == null ? "" : search.searchQuery().trim().toLowerCase(Locale.ROOT);
        List<ModelIssue> issues = diagnoseModel(accountDto.getProjectId()).issues().stream()
                .filter(issue -> search.type() == null || issue.type() == search.type())
                .filter(issue -> search.severity() == null || issue.severity() == search.severity())
                .filter(issue -> search.targetType() == null || issue.targetType() == search.targetType())
                .filter(issue -> search.schemaId() == null || search.schemaId().equals(issue.schemaId()))
                .filter(issue -> search.isApproval() == null || search.isApproval() == Boolean.TRUE.equals(issue.isApproval()))
                .filter(issue -> keyword.isEmpty()
                        || !("tableName".equals(search.searchColumn()) || "columnName".equals(search.searchColumn()))
                        || lower("tableName".equals(search.searchColumn()) ? issue.tableName() : issue.columnName()).contains(keyword))
                .sorted(modelOrder(pageable.getSort()))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), issues.size());
        return new PageImpl<>(issues.subList(from, Math.min(from + pageable.getPageSize(), issues.size())), pageable, issues.size());
    }

    /** 한 번의 모델 진단 : SQL 4번 (프로젝트, 컬럼, 테이블, 단어 약어) — 테이블·컬럼 수와 무관하다 */
    private record ModelDiagnosis(int totalColumns, int totalTables, String tablePrefix, List<Issue> columnIssues, List<TableIssue> tableIssues,
                                  List<ModelIssue> issues) {
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

        List<ModelIssue> issues = new ArrayList<>(tableIssues.size() + columnIssues.size());
        tableIssues.forEach(issue -> issues.add(modelIssue(issue)));
        columnIssues.forEach(issue -> issues.add(modelIssue(projectId, issue)));
        issues.sort(MODEL_DEFAULT_ORDER); // 안정 정렬 : 같은 테이블 안에서는 조회 순서(컬럼 순서 → 유형)를 유지한다
        return new ModelDiagnosis(columns.size(), tables.size(), prefix, columnIssues, tableIssues, issues);
    }

    private static Comparator<ModelIssue> modelOrder(Sort sort) {
        Comparator<ModelIssue> order = (a, b) -> 0;
        for (Sort.Order requested : sort) {
            Comparator<ModelIssue> comparator = MODEL_SORTS.get(requested.getProperty());
            if (comparator != null) {
                order = order.thenComparing(requested.isAscending() ? comparator : comparator.reversed());
            }
        }
        return order;
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

    private static ModelIssue modelIssue(long projectId, Issue issue) {
        StandardizationRule rule = issue.type().rule();
        return new ModelIssue(issue.id(), rule, rule.severity(), rule.targetType(), issue.schemaId(), issue.schemaName(), issue.tableId(),
                issue.tableName(), issue.tableKorName(), issue.columnId(), issue.columnName(), issue.columnDesc(), issue.termName(),
                issue.actualValue(), issue.expectedValue(), issue.attributes(), List.of(), issue.message(), issue.isApproval(),
                finding(projectId, issue).fingerprint());
    }

    private static ModelIssue modelIssue(TableIssue issue) {
        Finding finding = issue.finding();
        StandardizationRule rule = finding.rule();
        StandardizationQualityDto.TableRow table = issue.table();
        String message = rule == StandardizationRule.TABLE_PREFIX_MISSING
                ? Messages.get("테이블명이 프로젝트 접두어({0})로 시작하지 않습니다.", finding.expectedValue())
                : tokenMessage(issue.tokens());
        return new ModelIssue(rule + ":" + table.tableId(), rule, rule.severity(), rule.targetType(), table.schemaId(), table.schemaName(),
                table.tableId(), table.tableName(), table.tableKorName(), null, null, null, null,
                finding.actualValue(), finding.expectedValue(), List.of(), issue.tokens(), message, table.isApproval(), finding.fingerprint());
    }

    private static String tokenMessage(List<NameToken> tokens) {
        List<String> parts = new ArrayList<>();
        List<String> notFound = tokens(tokens, TokenStatus.NOT_FOUND);
        List<String> notApproved = tokens(tokens, TokenStatus.NOT_APPROVED);
        if (!notFound.isEmpty()) parts.add(Messages.get("표준 단어로 등록되지 않은 약어 : {0}", String.join(", ", notFound)));
        if (!notApproved.isEmpty()) parts.add(Messages.get("승인되지 않은 표준 단어 약어 : {0}", String.join(", ", notApproved)));
        if (tokens.stream().anyMatch(token -> token.status() == TokenStatus.EMPTY)) parts.add(Messages.get("비어 있는 약어가 있습니다 ('_' 가 연달아 있거나 앞뒤에 있음)."));
        return String.join(" / ", parts);
    }

    private static List<String> tokens(List<NameToken> tokens, TokenStatus status) {
        return tokens.stream().filter(token -> token.status() == status).map(NameToken::token).distinct().toList();
    }

    private static long count(List<Issue> issues, IssueType type) {
        return issues.stream().filter(issue -> issue.type() == type).count();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** 요청한 정렬(허용 필드만). 같은 값은 조회 순서(테이블명 → 컬럼 순서 → 진단 유형)를 유지한다 (안정 정렬) */
    private static Comparator<Issue> order(Sort sort) {
        Comparator<Issue> order = (a, b) -> 0;
        for (Sort.Order requested : sort) {
            Comparator<Issue> comparator = SORTS.get(requested.getProperty());
            if (comparator != null) {
                order = order.thenComparing(requested.isAscending() ? comparator : comparator.reversed());
            }
        }
        return order;
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
