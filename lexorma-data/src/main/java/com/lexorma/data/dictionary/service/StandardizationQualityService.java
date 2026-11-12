package com.lexorma.data.dictionary.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Attribute;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ColumnRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Issue;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Search;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Summary;
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
            // 영문약어가 없는 용어는 기대 컬럼명을 알 수 없으므로 위반으로 보지 않는다
            String abbreviation = row.termAbbreviation() == null ? "" : row.termAbbreviation().trim();
            if (!abbreviation.isEmpty() && !abbreviation.equalsIgnoreCase(row.columnName() == null ? "" : row.columnName().trim())) {
                issues.add(issue(row, IssueType.COLUMN_NAME_MISMATCH, row.columnName(), abbreviation, List.of(),
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
