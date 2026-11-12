package com.lexorma.data.mapping.repository;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.logical.domain.entity.QLogicalAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalEntity;
import com.lexorma.data.logical.domain.entity.QLogicalModel;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSource;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.domain.entity.QAttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.QEntityTableMapping;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.StringExpression;
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
import java.util.List;
import java.util.Map;

/**
 * 물리명 추천 조회 (M-7 3차). 컬럼명 추천은 상태 조건까지 DB 에서 걸러 페이징한다 —
 * 추천명이 표준 용어의 영문약어(저장된 값)라서 추천 가능·일치·추천 불가·충돌을 SQL 로 판정할 수 있다.
 * 상태 판정식은 PhysicalNameRecommendationService 의 자바 판정과 같아야 한다 (통합 테스트가 건수를 맞춰 본다).
 */
@Repository
@RequiredArgsConstructor
public class PhysicalNameRecommendationQueryRepository {

    private static final QLogicalModel model = QLogicalModel.logicalModel;
    private static final QLogicalEntity entity = QLogicalEntity.logicalEntity;
    private static final QLogicalAttribute attribute = QLogicalAttribute.logicalAttribute;
    private static final QEntityTableMapping mapping = QEntityTableMapping.entityTableMapping;
    private static final QAttributeColumnMapping attributeMapping = QAttributeColumnMapping.attributeColumnMapping;
    private static final QTableInfo table = QTableInfo.tableInfo;
    private static final QSchemaInfo schema = QSchemaInfo.schemaInfo;
    private static final QColumnInfo column = QColumnInfo.columnInfo;
    private static final QStandardTerm term = QStandardTerm.standardTerm;
    private static final QColumnInfo otherColumn = new QColumnInfo("otherColumn");
    private static final QAttributeColumnMapping otherMapping = new QAttributeColumnMapping("otherMapping");
    private static final QLogicalAttribute otherAttribute = new QLogicalAttribute("otherAttribute");
    private static final QEntityTableMapping tableMapping = new QEntityTableMapping("tableMapping");
    private static final QLogicalEntity mappedEntity = new QLogicalEntity("mappedEntity");

    /** 추천 컬럼명 = 표준 용어의 영문약어 (앞뒤 공백 제거, 대소문자 무시 비교용 대문자) */
    private static final StringExpression RECOMMENDED = term.commonStandardTermAbbreviation.trim().upper();
    private static final StringExpression CURRENT = column.columnName.coalesce("").trim().upper();

    private static final BooleanExpression UNAVAILABLE = term.id.isNull()
            .or(term.commonStandardTermAbbreviation.isNull())
            .or(term.commonStandardTermAbbreviation.trim().isEmpty());
    private static final BooleanExpression MATCHED = column.id.isNotNull().and(CURRENT.eq(RECOMMENDED));
    /** 매핑된 테이블에서 이 속성의 컬럼이 아닌 다른 컬럼이 추천명을 쓰고 있다 */
    private static final BooleanExpression CONFLICT = table.id.isNotNull().and(JPAExpressions.selectOne().from(otherColumn)
            .where(otherColumn.tableInfo.id.eq(table.id),
                    otherColumn.columnName.coalesce("").trim().upper().eq(RECOMMENDED),
                    column.id.isNull().or(otherColumn.id.ne(column.id)))
            .exists());

    /** 그리드 정렬 필드 → 조회 컬럼 (목록에 없는 필드는 무시한다) */
    private static final Map<String, ComparableExpressionBase<?>> COLUMN_SORTS = Map.of(
            "entityName", entity.entityName,
            "logicalName", attribute.attributeName,
            "currentPhysicalName", column.columnName,
            "recommendedPhysicalName", term.commonStandardTermAbbreviation);

    private final JPAQueryFactory query;

    /** 컬럼명 추천 원본 : 속성 1개 = 1행 (기본 정렬 엔터티명 → 속성 순서). 모델·엔터티·속성·컬럼·상태·이름 조건을 DB 에서 거른다 */
    public Page<ColumnSource> findColumnSources(Long projectId, ColumnSearch search, Pageable pageable) {
        BooleanBuilder where = columnWhere(projectId, search);
        List<ColumnSource> rows = columnJoins(query.select(Projections.constructor(ColumnSource.class,
                        model.id, model.modelName, entity.id, entity.entityName, mapping.id,
                        schema.id, schema.schemaName, table.id, table.tableName,
                        attribute.id, attribute.attributeName, term.id, term.commonStandardTermName, term.commonStandardTermAbbreviation,
                        attributeMapping.id, column.id, column.columnName)))
                .where(where)
                .orderBy(orders(pageable.getSort(), entity.entityName.asc(), entity.id.asc(), attribute.sortOrder.asc().nullsLast(), attribute.id.asc()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        return new PageImpl<>(rows, pageable, count(where));
    }

    /** 상태별 건수 (요약). 조건은 목록과 같은 식을 쓴다 */
    public long countColumns(Long projectId, Long modelId, Status status) {
        return count(columnWhere(projectId, new ColumnSearch(modelId, null, null, null, status, null, null)));
    }

    private long count(BooleanBuilder where) {
        Long total = columnJoins(query.select(attribute.count())).where(where).fetchOne();
        return total == null ? 0 : total;
    }

    private static BooleanBuilder columnWhere(Long projectId, ColumnSearch search) {
        BooleanBuilder where = new BooleanBuilder(entity.projectId.eq(projectId));
        if (search.modelId() != null) where.and(model.id.eq(search.modelId()));
        if (search.entityId() != null) where.and(entity.id.eq(search.entityId()));
        if (search.attributeId() != null) where.and(attribute.id.eq(search.attributeId()));
        if (search.columnId() != null) where.and(column.id.eq(search.columnId()));
        if (search.status() != null) {
            where.and(switch (search.status()) {
                case UNAVAILABLE -> UNAVAILABLE;
                case ALREADY_MATCHED -> UNAVAILABLE.not().and(MATCHED);
                case CONFLICT -> UNAVAILABLE.not().and(MATCHED.not()).and(CONFLICT);
                case AVAILABLE -> UNAVAILABLE.not().and(MATCHED.not()).and(CONFLICT.not());
            });
        }
        if (search.searchQuery() != null && !search.searchQuery().isBlank()) {
            String keyword = search.searchQuery().trim();
            where.and(switch (search.searchColumn() == null ? "" : search.searchColumn()) {
                case "logicalName" -> attribute.attributeName.containsIgnoreCase(keyword);
                case "currentPhysicalName" -> column.columnName.containsIgnoreCase(keyword);
                case "recommendedPhysicalName" -> term.commonStandardTermAbbreviation.containsIgnoreCase(keyword);
                default -> entity.entityName.containsIgnoreCase(keyword);
            });
        }
        return where;
    }

    private static <T> JPAQuery<T> columnJoins(JPAQuery<T> select) {
        return select.from(attribute)
                .join(attribute.entity, entity)
                .join(entity.model, model)
                .leftJoin(attribute.standardTerm, term)
                .leftJoin(mapping).on(mapping.entity.eq(entity))
                .leftJoin(mapping.table, table)
                .leftJoin(table.schema, schema)
                .leftJoin(attributeMapping).on(attributeMapping.attribute.eq(attribute))
                .leftJoin(attributeMapping.column, column);
    }

    /** 테이블에서 그 이름을 쓰는 컬럼 (충돌 사유 표시용, 한 쪽 분량을 한 번에) */
    public record NamedColumn(Long tableId, Long columnId, String columnName, String mappedAttributeName) {
    }

    /** upperNames : 대문자로 바꾼 추천 컬럼명 */
    public List<NamedColumn> findColumnsByName(Collection<Long> tableIds, Collection<String> upperNames) {
        if (tableIds.isEmpty() || upperNames.isEmpty()) return List.of();
        return query.select(Projections.constructor(NamedColumn.class, otherColumn.tableInfo.id, otherColumn.id, otherColumn.columnName, otherAttribute.attributeName))
                .from(otherColumn)
                .leftJoin(otherMapping).on(otherMapping.column.eq(otherColumn))
                .leftJoin(otherMapping.attribute, otherAttribute)
                .where(otherColumn.tableInfo.id.in(tableIds), otherColumn.columnName.coalesce("").trim().upper().in(upperNames))
                .fetch();
    }

    /** 프로젝트에서 그 이름을 쓰는 테이블 (테이블명은 프로젝트 안에서 유일해야 한다 — 테이블 등록·수정의 중복 기준) */
    public record NamedTable(Long tableId, String tableName, String mappedEntityName) {
    }

    public List<NamedTable> findTablesByName(Long projectId, Collection<String> upperNames) {
        if (upperNames.isEmpty()) return List.of();
        return query.select(Projections.constructor(NamedTable.class, table.id, table.tableName, mappedEntity.entityName))
                .from(table)
                .leftJoin(tableMapping).on(tableMapping.table.eq(table))
                .leftJoin(tableMapping.entity, mappedEntity)
                .where(table.projectId.eq(projectId), table.tableName.coalesce("").trim().upper().in(upperNames))
                .fetch();
    }

    private static OrderSpecifier<?>[] orders(Sort sort, OrderSpecifier<?>... defaults) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            ComparableExpressionBase<?> path = COLUMN_SORTS.get(order.getProperty());
            if (path != null) {
                orders.add(order.isAscending() ? path.asc() : path.desc());
            }
        }
        orders.addAll(List.of(defaults));
        return orders.toArray(OrderSpecifier[]::new);
    }
}
