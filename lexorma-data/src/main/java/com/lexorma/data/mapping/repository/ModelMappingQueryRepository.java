package com.lexorma.data.mapping.repository;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.entity.QStandardDomain;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.logical.domain.entity.QLogicalAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalEntity;
import com.lexorma.data.logical.domain.entity.QLogicalIdentifier;
import com.lexorma.data.logical.domain.entity.QLogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalModel;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto;
import com.lexorma.data.mapping.domain.entity.QAttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.QEntityTableMapping;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 논리·물리 매핑 조회 (M-7). 목록·현황 집계·후보를 DB 에서 걸러 페이징한다.
 * 저장된 매핑(FK)만 따라간다 — 이름이나 표준 용어가 같다는 이유로 매핑된 것으로 보지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class ModelMappingQueryRepository {

    private static final QLogicalModel model = QLogicalModel.logicalModel;
    private static final QLogicalEntity entity = QLogicalEntity.logicalEntity;
    private static final QLogicalAttribute attribute = QLogicalAttribute.logicalAttribute;
    private static final QEntityTableMapping mapping = QEntityTableMapping.entityTableMapping;
    private static final QAttributeColumnMapping attributeMapping = QAttributeColumnMapping.attributeColumnMapping;
    private static final QTableInfo table = QTableInfo.tableInfo;
    private static final QSchemaInfo schema = QSchemaInfo.schemaInfo;
    private static final QColumnInfo column = QColumnInfo.columnInfo;
    private static final QStandardTerm attributeTerm = new QStandardTerm("attributeTerm");
    private static final QStandardTerm columnTerm = new QStandardTerm("columnTerm");
    private static final QStandardDomain attributeDomain = new QStandardDomain("attributeDomain");
    private static final QLogicalIdentifier identifier = QLogicalIdentifier.logicalIdentifier;
    private static final QLogicalIdentifierAttribute identifierAttribute = QLogicalIdentifierAttribute.logicalIdentifierAttribute;

    /** 그리드 정렬 필드 → 조회 컬럼 (목록에 없는 필드는 무시한다. 정렬을 보내지 않으면 이름순) */
    private static final Map<String, ComparableExpressionBase<?>> ENTITY_SORTS = Map.of(
            "modelName", model.modelName,
            "entityName", entity.entityName,
            "schemaName", schema.schemaName,
            "tableName", table.tableName);

    private static final Map<String, ComparableExpressionBase<?>> TABLE_SORTS = Map.of(
            "schemaName", schema.schemaName,
            "tableName", table.tableName,
            "tableKorName", table.tableKorName);

    private final JPAQueryFactory query;

    /** 엔터티 기준 목록 : 엔터티 1행 = 결과 1행 (매핑은 엔터티당 최대 1건이라 조인으로 행이 늘지 않는다) */
    public Page<ModelMappingDto.EntityRow> findEntityRows(Long projectId, ModelMappingDto.EntitySearch search, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder(entity.projectId.eq(projectId));
        if (search.modelId() != null) where.and(model.id.eq(search.modelId()));
        if (search.entityId() != null) where.and(entity.id.eq(search.entityId()));
        if (search.schemaId() != null) where.and(schema.id.eq(search.schemaId()));
        if (search.mapped() != null) where.and(search.mapped() ? mapping.id.isNotNull() : mapping.id.isNull());
        if (search.searchQuery() != null && !search.searchQuery().isBlank()) {
            String keyword = search.searchQuery().trim();
            where.and("tableName".equals(search.searchColumn()) ? table.tableName.containsIgnoreCase(keyword) : entity.entityName.containsIgnoreCase(keyword));
        }
        List<ModelMappingDto.EntityRow> rows = entityRows(where)
                .orderBy(orders(pageable.getSort(), ENTITY_SORTS, model.modelName.asc(), entity.entityName.asc(), entity.id.asc()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = entityJoins(query.select(entity.count())).where(where).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    public ModelMappingDto.EntityRow findEntityRow(Long projectId, Long mappingId) {
        return entityRows(new BooleanBuilder(entity.projectId.eq(projectId)).and(mapping.id.eq(mappingId))).fetchFirst();
    }

    /** 엔터티 또는 테이블로 찾은 1행 (다른 화면의 매핑 표시용). 엔터티는 매핑이 없어도 나오고, 테이블은 매핑된 것만 나온다 */
    public ModelMappingDto.EntityRow findEntityRow(Long projectId, Long entityId, Long tableId) {
        BooleanBuilder where = new BooleanBuilder(entity.projectId.eq(projectId));
        if (entityId != null) where.and(entity.id.eq(entityId));
        if (tableId != null) where.and(table.id.eq(tableId));
        return entityRows(where).fetchFirst();
    }

    private JPAQuery<ModelMappingDto.EntityRow> entityRows(BooleanBuilder where) {
        return entityJoins(query.select(Projections.constructor(ModelMappingDto.EntityRow.class,
                mapping.id, model.id, model.modelName, entity.id, entity.entityName, entity.isApproval,
                schema.id, schema.schemaName, table.id, table.tableName, table.isApproval,
                JPAExpressions.select(attribute.count()).from(attribute).where(attribute.entity.eq(entity)),
                JPAExpressions.select(attributeMapping.count()).from(attributeMapping).where(attributeMapping.mapping.eq(mapping))))).where(where);
    }

    private static <T> JPAQuery<T> entityJoins(JPAQuery<T> select) {
        return select.from(entity)
                .join(entity.model, model)
                .leftJoin(mapping).on(mapping.entity.eq(entity))
                .leftJoin(mapping.table, table)
                .leftJoin(table.schema, schema);
    }

    /** 엔터티의 속성 전체 (속성 순서대로) 와 각 속성에 매핑된 컬럼 */
    public List<ModelMappingDto.AttributeRow> findAttributeRows(Long entityId) {
        return query.select(Projections.constructor(ModelMappingDto.AttributeRow.class,
                        attributeMapping.id, attribute.id, attribute.attributeName, attributeTerm.id, attributeTerm.commonStandardTermName,
                        column.id, column.columnName, columnTerm.id, columnTerm.commonStandardTermName))
                .from(attribute)
                .leftJoin(attribute.standardTerm, attributeTerm)
                .leftJoin(attributeMapping).on(attributeMapping.attribute.eq(attribute))
                .leftJoin(attributeMapping.column, column)
                .leftJoin(column.standardTerms, columnTerm)
                .where(attribute.entity.id.eq(entityId))
                .orderBy(attribute.sortOrder.asc().nullsLast(), attribute.id.asc())
                .fetch();
    }

    /** 매핑 후보 컬럼 : 테이블의 컬럼 중 속성 매핑이 없는 것 */
    public List<ModelMappingDto.ColumnCandidate> findUnmappedColumns(Long tableId) {
        return query.select(Projections.constructor(ModelMappingDto.ColumnCandidate.class,
                        column.id, column.columnName, column.columnDesc, columnTerm.id, columnTerm.commonStandardTermName))
                .from(column)
                .leftJoin(column.standardTerms, columnTerm)
                .where(column.tableInfo.id.eq(tableId),
                        JPAExpressions.selectOne().from(attributeMapping).where(attributeMapping.column.eq(column)).notExists())
                .orderBy(column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    /** 매핑 후보 테이블 : 프로젝트의 테이블 중 엔터티 매핑이 없는 것. 검색어는 테이블명·한글명 */
    public Page<ModelMappingDto.TableCandidate> findUnmappedTables(Long projectId, Long schemaId, String searchQuery, Pageable pageable) {
        BooleanBuilder where = new BooleanBuilder(table.projectId.eq(projectId))
                .and(JPAExpressions.selectOne().from(mapping).where(mapping.table.eq(table)).notExists());
        if (schemaId != null) where.and(schema.id.eq(schemaId));
        if (searchQuery != null && !searchQuery.isBlank()) {
            String keyword = searchQuery.trim();
            where.and(table.tableName.containsIgnoreCase(keyword).or(table.tableKorName.containsIgnoreCase(keyword)));
        }
        List<ModelMappingDto.TableCandidate> rows = query.select(Projections.constructor(ModelMappingDto.TableCandidate.class,
                        table.id, schema.id, schema.schemaName, table.tableName, table.tableKorName, table.isApproval))
                .from(table).join(table.schema, schema)
                .where(where)
                .orderBy(orders(pageable.getSort(), TABLE_SORTS, schema.schemaName.asc(), table.tableName.asc(), table.id.asc()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = query.select(table.count()).from(table).join(table.schema, schema).where(where).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 논리 모델별 현황 : 모델 1행에 엔터티·속성의 전체 수와 매핑된 수 (모델명 순). modelId 가 null 이면 프로젝트의 모든 모델 */
    public List<ModelMappingDto.Status> findStatus(Long projectId, Long modelId) {
        BooleanBuilder where = new BooleanBuilder(model.projectId.eq(projectId));
        if (modelId != null) where.and(model.id.eq(modelId));
        return query.select(Projections.constructor(ModelMappingDto.Status.class, model.id, model.modelName,
                        JPAExpressions.select(entity.count()).from(entity).where(entity.model.eq(model)),
                        JPAExpressions.select(mapping.count()).from(mapping).join(mapping.entity, entity).where(entity.model.eq(model)),
                        JPAExpressions.select(attribute.count()).from(attribute).join(attribute.entity, entity).where(entity.model.eq(model)),
                        JPAExpressions.select(attributeMapping.count()).from(attributeMapping)
                                .join(attributeMapping.attribute, attribute).join(attribute.entity, entity).where(entity.model.eq(model))))
                .from(model)
                .where(where)
                .orderBy(model.modelName.asc(), model.id.asc())
                .fetch();
    }

    // ------------------------------------------------------------------ 정합성 비교 (M-7 2차)

    /**
     * 정합성 진단 원본 : 모델의 엔터티 × 속성 (엔터티명 → 속성 순서). 매핑·컬럼·양쪽 표준 용어·논리 속성의 도메인을 한 번에 읽는다.
     * 모델·엔터티·이름 조건은 DB 에서 거른다 (진단 유형·상태는 타입 매핑이 자바 코드라 서비스에서 거른다).
     */
    public List<ModelMappingQualityDto.Source> findQualitySources(Long projectId, ModelMappingQualityDto.Search search) {
        BooleanBuilder where = new BooleanBuilder(entity.projectId.eq(projectId)).and(model.id.eq(search.modelId()));
        if (search.entityId() != null) where.and(entity.id.eq(search.entityId()));
        if (search.searchQuery() != null && !search.searchQuery().isBlank()) {
            String keyword = search.searchQuery().trim();
            where.and(switch (search.searchColumn() == null ? "" : search.searchColumn()) {
                case "attributeName" -> attribute.attributeName.containsIgnoreCase(keyword);
                case "columnName" -> column.columnName.containsIgnoreCase(keyword);
                default -> entity.entityName.containsIgnoreCase(keyword);
            });
        }
        return query.select(Projections.constructor(ModelMappingQualityDto.Source.class,
                        model.id, model.modelName, entity.id, entity.entityName, mapping.id,
                        schema.id, schema.schemaName, table.id, table.tableName,
                        attribute.id, attribute.attributeName, attribute.isRequired,
                        attributeTerm.id, attributeTerm.commonStandardTermName, attributeTerm.commonStandardTermAbbreviation,
                        attributeDomain.id, attributeDomain.commonStandardDomainName, attributeDomain.dataType, attributeDomain.dataLength, attributeDomain.dataDecimalLength,
                        attributeMapping.id, column.id, column.columnName,
                        column.dataType, column.maxLength, column.precision, column.scale,
                        column.isNullable, column.isRequired, column.isPk,
                        columnTerm.id, columnTerm.commonStandardTermName))
                .from(entity)
                .join(entity.model, model)
                .leftJoin(mapping).on(mapping.entity.eq(entity))
                .leftJoin(mapping.table, table)
                .leftJoin(table.schema, schema)
                .leftJoin(attribute).on(attribute.entity.eq(entity))
                .leftJoin(attribute.standardTerm, attributeTerm)
                .leftJoin(attributeTerm.standardDomain, attributeDomain)
                .leftJoin(attributeMapping).on(attributeMapping.attribute.eq(attribute))
                .leftJoin(attributeMapping.column, column)
                .leftJoin(column.standardTerms, columnTerm)
                .where(where)
                .orderBy(entity.entityName.asc(), entity.id.asc(), attribute.sortOrder.asc().nullsLast(), attribute.id.asc())
                .fetch();
    }

    /** 모델의 주 식별자 : 엔터티 ID → 주 식별자를 이루는 속성 ID. 주 식별자가 없는 엔터티는 키가 없다 */
    public Map<Long, Set<Long>> findPrimaryIdentifierAttributes(Long projectId, Long modelId) {
        Map<Long, Set<Long>> result = new HashMap<>();
        query.select(entity.id, identifierAttribute.attribute.id)
                .from(identifierAttribute)
                .join(identifierAttribute.identifier, identifier)
                .join(identifier.entity, entity)
                .where(entity.projectId.eq(projectId), entity.model.id.eq(modelId), identifier.isPrimary.isTrue())
                .fetch()
                .forEach(row -> result.computeIfAbsent(row.get(entity.id), key -> new HashSet<>()).add(row.get(identifierAttribute.attribute.id)));
        return result;
    }

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
