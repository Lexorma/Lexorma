package com.lexorma.data.dictionary.repository;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto;
import com.lexorma.data.dictionary.domain.dto.DictionaryUsageDto;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto;
import com.lexorma.data.dictionary.domain.entity.QStandardDomain;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.dictionary.domain.entity.QStandardTermWordMapping;
import com.lexorma.data.dictionary.domain.entity.QStandardWord;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 표준 항목 사용 현황 조회. 실제 참조(FK)만 따라간다 — 이름이 같다는 이유로 사용 중으로 보지 않는다.
 * <pre>
 * 표준 단어 ─(TB_STD_TRM_WORD)─ 표준 용어 ─(TB_COL.STD_TRM_SN)─ 컬럼
 *                               표준 용어 ─(TB_STD_TRM.STD_DMN_SN)─ 표준 도메인
 * </pre>
 */
@Repository
@RequiredArgsConstructor
public class DictionaryUsageRepository {

    private static final QColumnInfo column = QColumnInfo.columnInfo;
    private static final QTableInfo table = QTableInfo.tableInfo;
    private static final QSchemaInfo schema = QSchemaInfo.schemaInfo;
    private static final QStandardTerm term = QStandardTerm.standardTerm;
    private static final QStandardDomain domain = QStandardDomain.standardDomain;
    private static final QStandardTermWordMapping mapping = QStandardTermWordMapping.standardTermWordMapping;
    private static final QStandardWord word = QStandardWord.standardWord;

    /** 그리드 정렬 필드 → 조회 컬럼 (목록에 없는 필드는 무시한다) */
    private static final Map<String, ComparableExpressionBase<?>> COLUMN_SORTS = Map.of(
            "schemaName", schema.schemaName,
            "tableName", table.tableName,
            "tableKorName", table.tableKorName,
            "columnName", column.columnName,
            "dataType", column.dataType,
            "termName", term.commonStandardTermName,
            "domainName", domain.commonStandardDomainName,
            "isApproval", column.isApproval,
            "id", column.id);

    /** 정렬할 수 있는 화면 필드 (PageableUtil 허용 목록 — 이 맵의 키, 공통 페이징 안정성 개선) */
    public static final java.util.Set<String> COLUMN_SORT_FIELDS = COLUMN_SORTS.keySet();

    private static final Map<String, ComparableExpressionBase<?>> TERM_SORTS = Map.of(
            "termName", term.commonStandardTermName,
            "termAbbreviation", term.commonStandardTermAbbreviation,
            "domainName", domain.commonStandardDomainName,
            "isApproval", term.isApproval,
            "id", term.id);

    /** 정렬할 수 있는 화면 필드 (PageableUtil 허용 목록 — 이 맵의 키, 공통 페이징 안정성 개선) */
    public static final java.util.Set<String> TERM_SORT_FIELDS = TERM_SORTS.keySet();

    private final JPAQueryFactory query;

    /** 표준 용어를 쓰는 컬럼 */
    public Page<DictionaryUsageDto.ColumnUsage> findColumnsByTerm(Long projectId, Long termId, DictionaryUsageDto.ColumnSearch search, Pageable pageable) {
        return findColumns(columnCondition(projectId, search).and(term.id.eq(termId)), pageable);
    }

    /** 표준 도메인을 쓰는 컬럼 (그 도메인에 속한 표준 용어를 쓰는 컬럼) */
    public Page<DictionaryUsageDto.ColumnUsage> findColumnsByDomain(Long projectId, Long domainId, DictionaryUsageDto.ColumnSearch search, Pageable pageable) {
        return findColumns(columnCondition(projectId, search).and(domain.id.eq(domainId)), pageable);
    }

    private Page<DictionaryUsageDto.ColumnUsage> findColumns(BooleanBuilder where, Pageable pageable) {
        // 컬럼 1행 = 결과 1행 (용어·도메인·테이블·스키마는 모두 N:1 이라 조인으로 행이 늘지 않는다)
        List<DictionaryUsageDto.ColumnUsage> rows = columnQuery(query.select(Projections.constructor(DictionaryUsageDto.ColumnUsage.class,
                        column.id, table.id, schema.id, schema.schemaName, table.tableName, table.tableKorName,
                        column.columnName, column.columnDesc, column.dataType, column.maxLength,
                        term.id, term.commonStandardTermName, domain.commonStandardDomainName, column.isApproval)))
                .where(where)
                .orderBy(orders(pageable.getSort(), COLUMN_SORTS, table.tableName.asc(), column.sortOrder.asc().nullsLast(), column.id.asc()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = columnQuery(query.select(column.count())).where(where).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static <T> JPAQuery<T> columnQuery(JPAQuery<T> select) {
        return select.from(column)
                .join(column.tableInfo, table)
                .join(table.schema, schema)
                .join(column.standardTerms, term)
                .leftJoin(term.standardDomain, domain);
    }

    private static BooleanBuilder columnCondition(Long projectId, DictionaryUsageDto.ColumnSearch search) {
        BooleanBuilder where = new BooleanBuilder(column.projectId.eq(projectId)).and(table.projectId.eq(projectId));
        if (search.schemaId() != null) {
            where.and(schema.id.eq(search.schemaId()));
        }
        if (search.isApproval() != null) {
            where.and(search.isApproval() ? column.isApproval.isTrue() : column.isApproval.isNull().or(column.isApproval.isFalse()));
        }
        if (search.searchQuery() != null && !search.searchQuery().isBlank()) {
            String keyword = search.searchQuery().trim();
            if ("columnName".equals(search.searchColumn())) {
                where.and(column.columnName.containsIgnoreCase(keyword));
            } else if ("tableName".equals(search.searchColumn())) {
                where.and(table.tableName.containsIgnoreCase(keyword));
            }
        }
        return where;
    }

    /** 표준 단어를 쓰는 표준 용어. 한 용어에 같은 단어가 두 번 들어가도(예: 코드_코드) 용어는 한 번만 센다 */
    public Page<DictionaryUsageDto.TermUsage> findTermsByWord(Long projectId, Long wordId, String searchColumn, String searchQuery, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder(term.projectId.eq(projectId))
                .and(term.id.in(JPAExpressions.select(mapping.standardTerm.id).from(mapping).where(mapping.standardWord.id.eq(wordId))));
        if (searchQuery != null && !searchQuery.isBlank()) {
            String keyword = searchQuery.trim();
            if ("termAbbreviation".equals(searchColumn)) {
                where.and(term.commonStandardTermAbbreviation.containsIgnoreCase(keyword));
            } else if ("termName".equals(searchColumn)) {
                where.and(term.commonStandardTermName.containsIgnoreCase(keyword));
            }
        }
        QColumnInfo usedColumn = new QColumnInfo("usedColumn");
        Expression<Long> columnCount = JPAExpressions.select(usedColumn.count()).from(usedColumn)
                .where(usedColumn.standardTerms.id.eq(term.id), usedColumn.projectId.eq(projectId));

        List<DictionaryUsageDto.TermUsage> rows = query.select(Projections.constructor(DictionaryUsageDto.TermUsage.class,
                        term.id, term.commonStandardTermName, term.commonStandardTermAbbreviation, domain.commonStandardDomainName,
                        term.isApproval, columnCount))
                .from(term)
                .leftJoin(term.standardDomain, domain)
                .where(where)
                .orderBy(orders(pageable.getSort(), TERM_SORTS, term.commonStandardTermName.asc(), term.id.asc()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = query.select(term.count()).from(term).where(where).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 표준화 품질 진단용 : 프로젝트의 모든 컬럼과 연결된 표준 용어·도메인 (용어 없는 컬럼 포함, 조회 1번·엔티티를 읽지 않는다) */
    public List<StandardizationQualityDto.ColumnRow> findQualityColumns(Long projectId) {
        return qualityColumns(projectId)
                .orderBy(table.tableName.asc(), column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    /** 컬럼 1개 (개선 후보). 다른 프로젝트의 컬럼이면 null */
    public StandardizationQualityDto.ColumnRow findQualityColumn(Long projectId, Long columnId) {
        return qualityColumns(projectId).where(column.id.eq(columnId)).fetchFirst();
    }

    private JPAQuery<StandardizationQualityDto.ColumnRow> qualityColumns(Long projectId) {
        return query.select(Projections.constructor(StandardizationQualityDto.ColumnRow.class,
                        column.id, table.id, schema.id, schema.schemaName, table.tableName, table.tableKorName,
                        column.columnName, column.columnDesc, column.dataType, column.maxLength, column.precision, column.scale,
                        column.sortOrder, column.isApproval,
                        term.id, term.commonStandardTermName, term.commonStandardTermAbbreviation,
                        domain.id, domain.commonStandardDomainName, domain.dataType, domain.dataLength, domain.dataDecimalLength))
                .from(column)
                .join(column.tableInfo, table)
                .join(table.schema, schema)
                .leftJoin(column.standardTerms, term)
                .leftJoin(term.standardDomain, domain)
                .where(column.projectId.eq(projectId), table.projectId.eq(projectId));
    }

    // ------------------------------------------------------------------ 컬럼명 개선 후보 (컬럼 1개 단위)

    /** 후보 용어 : 프로젝트의 용어 전체와 도메인 정의 */
    public List<ColumnSuggestionDto.TermCandidateRow> findSuggestionTerms(Long projectId) {
        return query.select(Projections.constructor(ColumnSuggestionDto.TermCandidateRow.class,
                        term.id, term.commonStandardTermName, term.commonStandardTermAbbreviation, term.isApproval,
                        domain.id, domain.commonStandardDomainName, domain.dataType, domain.dataLength, domain.dataDecimalLength))
                .from(term)
                .leftJoin(term.standardDomain, domain)
                .where(term.projectId.eq(projectId))
                .orderBy(term.id.asc())
                .fetch();
    }

    /**
     * 논리명이 같은 다른 컬럼이 쓰는 용어 → 그 컬럼 수. 논리명은 소문자로 바꾸고 공백·'_' 를 뺀 값으로 비교한다
     * (normalizedDesc 도 같은 규칙으로 만든 값이어야 한다 — 탭·줄바꿈은 SQL 쪽에서 빼지 않는다)
     */
    public Map<Long, Long> countSameDescColumnsByTerm(Long projectId, String normalizedDesc, Long excludeColumnId) {
        return counts(query.select(column.standardTerms.id, column.count())
                .from(column)
                .where(column.projectId.eq(projectId), column.id.ne(excludeColumnId), column.standardTerms.isNotNull(),
                        Expressions.stringTemplate("replace(replace(lower({0}), ' ', ''), '_', '')", column.columnDesc).eq(normalizedDesc))
                .groupBy(column.standardTerms.id)
                .fetch().stream().map(row -> new Object[]{row.get(0, Long.class), row.get(1, Long.class)}).toList());
    }

    /** 같은 테이블의 컬럼 이름과 연결된 용어의 약어 (이름 충돌) */
    public List<ColumnSuggestionDto.TableColumnRow> findTableColumns(Long projectId, Long tableId) {
        return query.select(Projections.constructor(ColumnSuggestionDto.TableColumnRow.class,
                        column.id, column.columnName, term.id, term.commonStandardTermAbbreviation))
                .from(column)
                .leftJoin(column.standardTerms, term)
                .where(column.projectId.eq(projectId), column.tableInfo.id.eq(tableId))
                .orderBy(column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    /** 테이블명 진단 : 프로젝트의 테이블 전체 (VIEW 는 TB_VIEW 라 들어오지 않는다), 테이블명 → ID 순 */
    public List<StandardizationQualityDto.TableRow> findQualityTables(Long projectId) {
        return query.select(Projections.constructor(StandardizationQualityDto.TableRow.class,
                        table.id, schema.id, schema.schemaName, table.tableName, table.tableKorName, table.isApproval))
                .from(table)
                .join(table.schema, schema)
                .where(table.projectId.eq(projectId))
                .orderBy(table.tableName.asc(), table.id.asc())
                .fetch();
    }

    /** 완전성 판정 (AI-6 3차) : 프로젝트 테이블 전체 — 모델 진단과 같은 범위(프로젝트의 물리 테이블, VIEW 제외, 승인 상태 무관). 한 번의 조회 */
    public List<StandardizationQualityDto.CompletenessTable> findCompletenessTables(Long projectId) {
        return query.select(Projections.constructor(StandardizationQualityDto.CompletenessTable.class,
                        table.id, schema.schemaName, table.tableName, table.tableKorName, table.bizDescription))
                .from(table)
                .join(table.schema, schema)
                .where(table.projectId.eq(projectId))
                .orderBy(table.tableName.asc(), table.id.asc())
                .fetch();
    }

    /** 완전성 판정 (AI-6 3차) : 프로젝트 컬럼 전체 — 모델 진단의 컬럼 진단과 같은 범위. 한 번의 조회 (컬럼마다 조회하지 않는다) */
    public List<StandardizationQualityDto.CompletenessColumn> findCompletenessColumns(Long projectId) {
        return query.select(Projections.constructor(StandardizationQualityDto.CompletenessColumn.class,
                        column.id, table.id, schema.schemaName, table.tableName, column.columnName, column.columnDesc, column.bizDescription))
                .from(column)
                .join(column.tableInfo, table)
                .join(table.schema, schema)
                .where(column.projectId.eq(projectId), table.projectId.eq(projectId))
                .orderBy(table.tableName.asc(), column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    /** 테이블명 진단 : 프로젝트의 표준 단어 약어·승인 여부 전체 (테이블마다 조회하지 않는다) */
    public List<StandardizationQualityDto.WordAbbreviation> findWordAbbreviations(Long projectId) {
        return query.select(Projections.constructor(StandardizationQualityDto.WordAbbreviation.class,
                        word.commonStandardWordAbbreviation, word.isApproval))
                .from(word)
                .where(word.projectId.eq(projectId), word.commonStandardWordAbbreviation.isNotNull())
                .fetch();
    }

    // ------------------------------------------------------------------ 표준 용어 중복·유사어 진단 (프로젝트 단위 6번, 용어 수와 무관)

    public List<TermQualityDto.TermRow> findQualityTerms(Long projectId) {
        return query.select(Projections.constructor(TermQualityDto.TermRow.class,
                        term.id, term.commonStandardTermName, term.commonStandardTermAbbreviation, domain.id, domain.commonStandardDomainName, term.isApproval))
                .from(term)
                .leftJoin(term.standardDomain, domain)
                .where(term.projectId.eq(projectId))
                .orderBy(term.id.asc())
                .fetch();
    }

    public List<TermQualityDto.TermWordRow> findQualityTermWords(Long projectId) {
        return query.select(Projections.constructor(TermQualityDto.TermWordRow.class, mapping.standardTerm.id, mapping.standardWord.id, mapping.orderIndex))
                .from(mapping)
                .where(mapping.standardTerm.projectId.eq(projectId))
                .fetch();
    }

    public List<TermQualityDto.WordRow> findQualityWords(Long projectId) {
        return query.select(Projections.constructor(TermQualityDto.WordRow.class,
                        word.id, word.commonStandardWordName, word.commonStandardWordAbbreviation, word.isApproval))
                .from(word)
                .where(word.projectId.eq(projectId))
                .fetch();
    }

    /** TB_STD_TRM_SYNM */
    public List<TermQualityDto.NameRow> findTermSynonyms(Long projectId) {
        StringPath synonym = Expressions.stringPath("termSynonym");
        return query.select(Projections.constructor(TermQualityDto.NameRow.class, term.id, synonym))
                .from(term)
                .join(term.synonymList, synonym)
                .where(term.projectId.eq(projectId))
                .fetch();
    }

    /** TB_STD_WORD_SYNM */
    public List<TermQualityDto.NameRow> findWordSynonyms(Long projectId) {
        StringPath synonym = Expressions.stringPath("wordSynonym");
        return query.select(Projections.constructor(TermQualityDto.NameRow.class, word.id, synonym))
                .from(word)
                .join(word.synonymList, synonym)
                .where(word.projectId.eq(projectId))
                .fetch();
    }

    /** TB_STD_WORD_PRHBT */
    public List<TermQualityDto.NameRow> findRestrictedWords(Long projectId) {
        StringPath restricted = Expressions.stringPath("restrictedWord");
        return query.select(Projections.constructor(TermQualityDto.NameRow.class, word.id, restricted))
                .from(word)
                .join(word.restrictedWords, restricted)
                .where(word.projectId.eq(projectId))
                .fetch();
    }

    // ------------------------------------------------------------------ 사용 건수 (목록의 한 페이지 분량을 한 번에)

    /** 표준 용어 ID → 그 용어를 쓰는 컬럼 수 */
    public Map<Long, Long> countColumnsByTerm(Long projectId, Collection<Long> termIds) {
        return counts(termIds.isEmpty() ? List.of() : query.select(column.standardTerms.id, column.count())
                .from(column)
                .where(column.projectId.eq(projectId), column.standardTerms.id.in(termIds))
                .groupBy(column.standardTerms.id)
                .fetch().stream().map(row -> new Object[]{row.get(0, Long.class), row.get(1, Long.class)}).toList());
    }

    /** 표준 도메인 ID → 그 도메인의 용어를 쓰는 컬럼 수 */
    public Map<Long, Long> countColumnsByDomain(Long projectId, Collection<Long> domainIds) {
        return counts(domainIds.isEmpty() ? List.of() : query.select(term.standardDomain.id, column.count())
                .from(column)
                .join(column.standardTerms, term)
                .where(column.projectId.eq(projectId), term.standardDomain.id.in(domainIds))
                .groupBy(term.standardDomain.id)
                .fetch().stream().map(row -> new Object[]{row.get(0, Long.class), row.get(1, Long.class)}).toList());
    }

    /** 표준 단어 ID → 그 단어를 쓰는 표준 용어 수 (같은 용어에 두 번 쓰여도 1) */
    public Map<Long, Long> countTermsByWord(Long projectId, Collection<Long> wordIds) {
        return counts(wordIds.isEmpty() ? List.of() : query.select(mapping.standardWord.id, mapping.standardTerm.id.countDistinct())
                .from(mapping)
                .where(mapping.standardWord.id.in(wordIds), mapping.standardTerm.projectId.eq(projectId))
                .groupBy(mapping.standardWord.id)
                .fetch().stream().map(row -> new Object[]{row.get(0, Long.class), row.get(1, Long.class)}).toList());
    }

    private static Map<Long, Long> counts(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        rows.forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    /** 요청한 정렬(허용 필드만) 뒤에 기본 정렬을 붙여 페이지 순서를 고정한다 */
    private static OrderSpecifier<?>[] orders(Sort sort, Map<String, ComparableExpressionBase<?>> allowed, OrderSpecifier<?>... defaults) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            ComparableExpressionBase<?> path = allowed.get(order.getProperty());
            if (path != null) {
                orders.add(order.isAscending() ? path.asc() : path.desc());
            }
        }
        orders.addAll(List.of(defaults));
        return orders.toArray(OrderSpecifier[]::new);
    }
}
