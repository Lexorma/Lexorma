package com.lexorma.data.mapping.repository;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.logical.domain.entity.QLogicalAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalEntity;
import com.lexorma.data.logical.domain.entity.QLogicalIdentifier;
import com.lexorma.data.logical.domain.entity.QLogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalModel;
import com.lexorma.data.mapping.domain.entity.QAttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.QEntityTableMapping;
import com.lexorma.data.mapping.domain.entity.QSubtypeAttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.QSubtypeIdentifierColumnMapping;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매핑 후보 추천 · ERD 매핑 표시 조회 (M-7 자동 매핑 후보·ERD 연계). 읽기 전용 투영 질의만 둔다 — 엔터티를 불러오지 않고, 행마다 다시 조회하지 않는다.
 * 모든 질의는 프로젝트 ID(또는 프로젝트가 확인된 매핑·테이블)로 거른다.
 */
@Repository
@RequiredArgsConstructor
public class MappingSuggestionQueryRepository {

    private static final QLogicalModel model = QLogicalModel.logicalModel;
    private static final QLogicalEntity entity = QLogicalEntity.logicalEntity;
    private static final QLogicalAttribute attribute = QLogicalAttribute.logicalAttribute;
    private static final QLogicalIdentifier identifier = QLogicalIdentifier.logicalIdentifier;
    private static final QLogicalIdentifierAttribute identifierAttribute = QLogicalIdentifierAttribute.logicalIdentifierAttribute;
    private static final QEntityTableMapping mapping = QEntityTableMapping.entityTableMapping;
    private static final QAttributeColumnMapping attributeMapping = QAttributeColumnMapping.attributeColumnMapping;
    private static final QSubtypeIdentifierColumnMapping subtypeKey = QSubtypeIdentifierColumnMapping.subtypeIdentifierColumnMapping;
    private static final QSubtypeAttributeColumnMapping subtypeAttribute = QSubtypeAttributeColumnMapping.subtypeAttributeColumnMapping;
    private static final QTableInfo table = QTableInfo.tableInfo;
    private static final QSchemaInfo schema = QSchemaInfo.schemaInfo;
    private static final QColumnInfo column = QColumnInfo.columnInfo;
    private static final QStandardTerm term = QStandardTerm.standardTerm;

    private final JPAQueryFactory query;

    // ------------------------------------------------------------------ 속성 ↔ 컬럼 후보

    /** 후보 컬럼 : 테이블의 컬럼 중 속성 매핑·상속 PK 매핑에 쓰이지 않은 것 (기존 후보 조회와 같은 조건) + 타입·용어·용어 승인 */
    public record CandidateColumn(Long columnId, String columnName, String columnDesc, String dataType, Integer maxLength, BigDecimal precision, Integer scale,
                                  Boolean isNullable, Boolean isRequired, Boolean isPk, Long termId, String termName, Boolean termApproval) {
    }

    public List<CandidateColumn> candidateColumns(Long tableId) {
        return query.select(Projections.constructor(CandidateColumn.class,
                        column.id, column.columnName, column.columnDesc, column.dataType, column.maxLength, column.precision, column.scale,
                        column.isNullable, column.isRequired, column.isPk, term.id, term.commonStandardTermName, term.isApproval))
                .from(column)
                .leftJoin(column.standardTerms, term)
                .where(column.tableInfo.id.eq(tableId),
                        JPAExpressions.selectOne().from(attributeMapping).where(attributeMapping.column.eq(column)).notExists(),
                        JPAExpressions.selectOne().from(subtypeKey).where(subtypeKey.column.eq(column)).notExists())
                .orderBy(column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    /** 표준 용어 ID → 승인 여부 (프로젝트 범위) */
    public Map<Long, Boolean> termApprovals(Long projectId, Collection<Long> termIds) {
        Map<Long, Boolean> result = new HashMap<>();
        if (termIds.isEmpty()) return result;
        query.select(term.id, term.isApproval).from(term).where(term.projectId.eq(projectId), term.id.in(termIds)).fetch()
                .forEach(row -> result.put(row.get(term.id), Boolean.TRUE.equals(row.get(term.isApproval))));
        return result;
    }

    // ------------------------------------------------------------------ 엔터티 ↔ 테이블 후보

    public record CandidateTable(Long tableId, Long schemaId, String schemaName, String tableName, String tableKorName, Boolean isApproval) {
    }

    /** 엔터티 주 식별자 속성의 표준 용어 ID (용어 없는 속성은 null 로 온다) */
    public List<Long> primaryKeyTermIds(Long projectId, Long entityId) {
        return query.select(attribute.standardTerm.id)
                .from(identifierAttribute)
                .join(identifierAttribute.identifier, identifier)
                .join(identifierAttribute.attribute, attribute)
                .where(identifier.entity.id.eq(entityId), identifier.isPrimary.isTrue(), attribute.entity.projectId.eq(projectId))
                .fetch();
    }

    /** 엔터티 속성의 표준 용어 ID (중복 제거 전, 용어 없는 속성 제외) */
    public List<Long> attributeTermIds(Long projectId, Long entityId) {
        return query.select(attribute.standardTerm.id)
                .from(attribute)
                .where(attribute.entity.id.eq(entityId), attribute.entity.projectId.eq(projectId), attribute.standardTerm.isNotNull())
                .fetch();
    }

    private BooleanBuilder unmappedTables(Long projectId, Long schemaId) {
        BooleanBuilder where = new BooleanBuilder(table.projectId.eq(projectId))
                .and(JPAExpressions.selectOne().from(mapping).where(mapping.table.eq(table)).notExists());
        if (schemaId != null) where.and(table.schema.id.eq(schemaId));
        return where;
    }

    /** 이름(대문자 비교)이 같은 매핑 없는 테이블 */
    public List<Long> unmappedTablesByName(Long projectId, Long schemaId, String upperName, int limit) {
        return query.select(table.id).from(table).where(unmappedTables(projectId, schemaId), table.tableName.upper().eq(upperName)).limit(limit).fetch();
    }

    /** 테이블 코멘트(한글명)에서 공백·'_' 를 빼고 소문자로 바꾼 값이 key 와 같은 매핑 없는 테이블 (StandardNames.termNameKey 와 같은 비교) */
    public List<Long> unmappedTablesByComment(Long projectId, Long schemaId, String key, int limit) {
        BooleanExpression same = Expressions.stringTemplate("replace(replace(lower({0}), ' ', ''), '_', '')", table.tableKorName).eq(key);
        return query.select(table.id).from(table).where(unmappedTables(projectId, schemaId), same).limit(limit).fetch();
    }

    /** PK 컬럼 중 하나라도 termIds 의 용어를 쓰는 매핑 없는 테이블 */
    public List<Long> unmappedTablesWithKeyTerms(Long projectId, Long schemaId, Collection<Long> termIds, int limit) {
        if (termIds.isEmpty()) return List.of();
        return query.select(table.id).from(table)
                .where(unmappedTables(projectId, schemaId),
                        JPAExpressions.selectOne().from(column).where(column.tableInfo.eq(table), column.isPk.isTrue(), column.standardTerms.id.in(termIds)).exists())
                .limit(limit).fetch();
    }

    /** 테이블별 PK 컬럼 표준 용어 ID (용어 없는 PK 컬럼은 null 로 온다) */
    public Map<Long, List<Long>> primaryKeyTerms(Collection<Long> tableIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (tableIds.isEmpty()) return result;
        query.select(column.tableInfo.id, column.standardTerms.id).from(column)
                .where(column.tableInfo.id.in(tableIds), column.isPk.isTrue()).fetch()
                .forEach(row -> result.computeIfAbsent(row.get(column.tableInfo.id), k -> new java.util.ArrayList<>()).add(row.get(column.standardTerms.id)));
        return result;
    }

    /** 테이블별 컬럼 표준 용어 중 termIds 에 있는 것 (중복 제거) */
    public Map<Long, java.util.Set<Long>> columnTerms(Collection<Long> tableIds, Collection<Long> termIds) {
        Map<Long, java.util.Set<Long>> result = new HashMap<>();
        if (tableIds.isEmpty() || termIds.isEmpty()) return result;
        query.select(column.tableInfo.id, column.standardTerms.id).from(column)
                .where(column.tableInfo.id.in(tableIds), column.standardTerms.id.in(termIds)).fetch()
                .forEach(row -> result.computeIfAbsent(row.get(column.tableInfo.id), k -> new java.util.HashSet<>()).add(row.get(column.standardTerms.id)));
        return result;
    }

    public List<CandidateTable> tables(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(CandidateTable.class, table.id, schema.id, schema.schemaName, table.tableName, table.tableKorName, table.isApproval))
                .from(table).join(table.schema, schema)
                .where(table.projectId.eq(projectId), table.id.in(tableIds))
                .fetch();
    }

    // ------------------------------------------------------------------ ERD 매핑 표시 : 논리 쪽

    public record EntityLink(Long entityId, Long mappingId, Long schemaId, String schemaName, Long tableId, String tableName) {
    }

    /** 모델의 엔터티 중 entityIds 와 그 매핑 (모델·프로젝트가 다른 ID 는 빠진다) */
    public List<EntityLink> entityLinks(Long projectId, Long modelId, Collection<Long> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(EntityLink.class, entity.id, mapping.id, schema.id, schema.schemaName, table.id, table.tableName))
                .from(entity)
                .leftJoin(mapping).on(mapping.entity.eq(entity))
                .leftJoin(mapping.table, table)
                .leftJoin(table.schema, schema)
                .where(entity.projectId.eq(projectId), entity.model.id.eq(modelId), entity.id.in(entityIds))
                .fetch();
    }

    /** 속성 1개와 그 직접 매핑 컬럼 (매핑이 없으면 column 값이 null) */
    public record AttributeLinkRow(Long entityId, Long attributeId, String attributeName, Long columnId, String columnName) {
    }

    public List<AttributeLinkRow> attributeLinks(Collection<Long> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(AttributeLinkRow.class, attribute.entity.id, attribute.id, attribute.attributeName, column.id, column.columnName))
                .from(attribute)
                .leftJoin(attributeMapping).on(attributeMapping.attribute.eq(attribute))
                .leftJoin(attributeMapping.column, column)
                .where(attribute.entity.id.in(entityIds))
                .orderBy(attribute.sortOrder.asc().nullsLast(), attribute.id.asc())
                .fetch();
    }

    /** 상속 PK 매핑 : 엔터티(서브타입) ID · 루트 주 식별자 속성 · 서브타입 테이블 컬럼 */
    public List<AttributeLinkRow> inheritedKeyLinksByEntity(Collection<Long> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(AttributeLinkRow.class, mapping.entity.id, attribute.id, attribute.attributeName, column.id, column.columnName))
                .from(subtypeKey)
                .join(subtypeKey.mapping, mapping)
                .join(subtypeKey.attribute, attribute)
                .join(subtypeKey.column, column)
                .where(mapping.entity.id.in(entityIds))
                .orderBy(column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    /** C형 상속 일반 속성 매핑 (말단 엔터티 문맥, M-7 상속 C) */
    public List<AttributeLinkRow> inheritedAttributeLinksByEntity(Collection<Long> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(AttributeLinkRow.class, mapping.entity.id, attribute.id, attribute.attributeName, column.id, column.columnName))
                .from(subtypeAttribute)
                .join(subtypeAttribute.mapping, mapping)
                .join(subtypeAttribute.attribute, attribute)
                .join(subtypeAttribute.column, column)
                .where(mapping.entity.id.in(entityIds))
                .orderBy(column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    // ------------------------------------------------------------------ ERD 매핑 표시 : 물리 쪽

    public record TableLink(Long tableId, Long mappingId, Long modelId, String modelName, Long entityId, String entityName, Long columnCount) {
    }

    /** 테이블과 그 엔터티 매핑 (프로젝트가 다른 ID 는 빠진다), 컬럼 수 */
    public List<TableLink> tableLinks(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(TableLink.class, table.id, mapping.id, model.id, model.modelName, entity.id, entity.entityName,
                        JPAExpressions.select(column.count()).from(column).where(column.tableInfo.eq(table))))
                .from(table)
                .leftJoin(mapping).on(mapping.table.eq(table))
                .leftJoin(mapping.entity, entity)
                .leftJoin(entity.model, model)
                .where(table.projectId.eq(projectId), table.id.in(tableIds))
                .fetch();
    }

    /** 컬럼 매핑 : 테이블 ID · 컬럼 · 속성 (직접 매핑) */
    public record ColumnLinkRow(Long tableId, Long entityId, Long columnId, String columnName, Long attributeId, String attributeName) {
    }

    public List<ColumnLinkRow> columnLinks(Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(ColumnLinkRow.class, column.tableInfo.id, attribute.entity.id, column.id, column.columnName, attribute.id, attribute.attributeName))
                .from(attributeMapping)
                .join(attributeMapping.column, column)
                .join(attributeMapping.attribute, attribute)
                .where(column.tableInfo.id.in(tableIds))
                .fetch();
    }

    public List<ColumnLinkRow> inheritedKeyLinksByTable(Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(ColumnLinkRow.class, column.tableInfo.id, mapping.entity.id, column.id, column.columnName, attribute.id, attribute.attributeName))
                .from(subtypeKey)
                .join(subtypeKey.mapping, mapping)
                .join(subtypeKey.column, column)
                .join(subtypeKey.attribute, attribute)
                .where(column.tableInfo.id.in(tableIds))
                .fetch();
    }

    /** C형 상속 일반 속성 매핑 (말단 테이블 컬럼, M-7 상속 C) */
    public List<ColumnLinkRow> inheritedAttributeLinksByTable(Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(ColumnLinkRow.class, column.tableInfo.id, mapping.entity.id, column.id, column.columnName, attribute.id, attribute.attributeName))
                .from(subtypeAttribute)
                .join(subtypeAttribute.mapping, mapping)
                .join(subtypeAttribute.column, column)
                .join(subtypeAttribute.attribute, attribute)
                .where(column.tableInfo.id.in(tableIds))
                .fetch();
    }
}
