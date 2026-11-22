package com.lexorma.data.mapping.repository;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.logical.domain.entity.QLogicalAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalEntity;
import com.lexorma.data.logical.domain.entity.QLogicalIdentifier;
import com.lexorma.data.logical.domain.entity.QLogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalRelationship;
import com.lexorma.data.logical.domain.entity.QLogicalRelationshipAttribute;
import com.lexorma.data.mapping.domain.entity.QAttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.QEntityTableMapping;
import com.lexorma.data.mapping.domain.entity.QSubtypeIdentifierColumnMapping;
import com.lexorma.data.physical.domain.entity.QTableConstraint;
import com.lexorma.data.physical.domain.entity.QTableConstraintColumn;
import com.lexorma.data.physical.domain.entity.QTableIndex;
import com.lexorma.data.physical.domain.entity.QTableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.QTableRelation;
import com.lexorma.data.physical.domain.entity.QTableRelationColumn;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 논리·물리 구조 정합성 진단 재료 (M-7 구조 정합성, 읽기 전용 투영 — 엔티티를 적재하지 않는다). 논리 모델 1개 범위를 종류별로 한 번씩 읽는다 (행마다 다시 읽지 않는다).
 * 물리 쪽은 그 모델의 엔터티에 매핑된 테이블만 본다 (하위 질의 — 긴 ID 목록을 보내지 않는다). 모든 질의는 프로젝트 ID 로 거른다.
 */
@Repository
@RequiredArgsConstructor
public class ModelStructureQualityQueryRepository {

    private static final QLogicalEntity entity = QLogicalEntity.logicalEntity;
    private static final QLogicalAttribute attribute = QLogicalAttribute.logicalAttribute;
    private static final QLogicalRelationship relationship = QLogicalRelationship.logicalRelationship;
    private static final QLogicalRelationshipAttribute pair = QLogicalRelationshipAttribute.logicalRelationshipAttribute;
    private static final QLogicalAttribute parentAttribute = new QLogicalAttribute("parentAttribute");
    private static final QLogicalAttribute childAttribute = new QLogicalAttribute("childAttribute");
    private static final QLogicalIdentifier identifier = QLogicalIdentifier.logicalIdentifier;
    private static final QLogicalIdentifierAttribute identifierAttribute = QLogicalIdentifierAttribute.logicalIdentifierAttribute;
    private static final QEntityTableMapping mapping = QEntityTableMapping.entityTableMapping;
    private static final QEntityTableMapping scopeMapping = new QEntityTableMapping("scopeMapping");
    private static final QAttributeColumnMapping attributeMapping = QAttributeColumnMapping.attributeColumnMapping;
    private static final QSubtypeIdentifierColumnMapping inheritedKey = QSubtypeIdentifierColumnMapping.subtypeIdentifierColumnMapping;
    private static final QTableInfo table = QTableInfo.tableInfo;
    private static final QTableInfo parentTable = new QTableInfo("parentTable");
    private static final QSchemaInfo schema = QSchemaInfo.schemaInfo;
    private static final QColumnInfo column = QColumnInfo.columnInfo;
    private static final QColumnInfo parentColumn = new QColumnInfo("parentColumn");
    private static final QTableRelation relation = QTableRelation.tableRelation;
    private static final QTableRelationColumn relationColumn = QTableRelationColumn.tableRelationColumn;
    private static final QTableConstraint constraint = QTableConstraint.tableConstraint;
    private static final QTableConstraintColumn constraintColumn = QTableConstraintColumn.tableConstraintColumn;
    private static final QTableIndex index = QTableIndex.tableIndex;
    private static final QTableIndexColumn indexColumn = QTableIndexColumn.tableIndexColumn;

    private final JPAQueryFactory query;

    /** 모델의 엔터티 (매핑이 없으면 매핑·테이블 값이 null) */
    public record EntityRow(Long entityId, String entityName, Long mappingId, Long tableId, String tableName, Long schemaId, String schemaName) {
    }

    public List<EntityRow> entities(Long projectId, Long modelId) {
        return query.select(Projections.constructor(EntityRow.class, entity.id, entity.entityName, mapping.id, table.id, table.tableName, schema.id, schema.schemaName))
                .from(entity)
                .leftJoin(mapping).on(mapping.entity.eq(entity))
                .leftJoin(mapping.table, table)
                .leftJoin(table.schema, schema)
                .where(entity.projectId.eq(projectId), entity.model.id.eq(modelId))
                .orderBy(entity.entityName.asc(), entity.id.asc())
                .fetch();
    }

    /** 논리 관계 : source = 부모, target = 자식 */
    public record RelationshipRow(Long relationshipId, String relationshipName, Long parentEntityId, Long childEntityId) {
    }

    public List<RelationshipRow> relationships(Long projectId, Long modelId) {
        return query.select(Projections.constructor(RelationshipRow.class, relationship.id, relationship.relationshipName, relationship.sourceEntity.id, relationship.targetEntity.id))
                .from(relationship)
                .where(relationship.projectId.eq(projectId), relationship.model.id.eq(modelId))
                .orderBy(relationship.id.asc())
                .fetch();
    }

    /** 관계 속성 짝 (부모 속성 → 자식 속성, 짝 순서) */
    public record PairRow(Long relationshipId, Integer sortOrder, Long pairId, Long parentAttributeId, String parentAttributeName, Long childAttributeId, String childAttributeName) {
    }

    public List<PairRow> pairs(Long projectId, Long modelId) {
        return query.select(Projections.constructor(PairRow.class, relationship.id, pair.sortOrder, pair.id, parentAttribute.id, parentAttribute.attributeName,
                        childAttribute.id, childAttribute.attributeName))
                .from(pair)
                .join(pair.relationship, relationship)
                .join(pair.parentAttribute, parentAttribute)
                .join(pair.childAttribute, childAttribute)
                .where(relationship.projectId.eq(projectId), relationship.model.id.eq(modelId))
                .orderBy(relationship.id.asc(), pair.sortOrder.asc(), pair.id.asc())
                .fetch();
    }

    /** 식별자 구성 속성 (주·보조 모두, 식별자 순서) */
    public record IdentifierRow(Long identifierId, Long entityId, String identifierName, Boolean primary, Long attributeId, String attributeName, Integer sortOrder, Long rowId) {
    }

    public List<IdentifierRow> identifiers(Long projectId, Long modelId) {
        return query.select(Projections.constructor(IdentifierRow.class, identifier.id, entity.id, identifier.identifierName, identifier.isPrimary, attribute.id,
                        attribute.attributeName, identifierAttribute.sortOrder, identifierAttribute.id))
                .from(identifierAttribute)
                .join(identifierAttribute.identifier, identifier)
                .join(identifier.entity, entity)
                .join(identifierAttribute.attribute, attribute)
                .where(entity.projectId.eq(projectId), entity.model.id.eq(modelId))
                .orderBy(identifier.id.asc(), identifierAttribute.sortOrder.asc().nullsLast(), identifierAttribute.id.asc())
                .fetch();
    }

    /** 속성 ↔ 컬럼 매핑 (모델의 속성) */
    public record AttributeColumnRow(Long attributeId, String attributeName, Long columnId, String columnName, Long tableId) {
    }

    public List<AttributeColumnRow> attributeColumns(Long projectId, Long modelId) {
        return query.select(Projections.constructor(AttributeColumnRow.class, attribute.id, attribute.attributeName, column.id, column.columnName, column.tableInfo.id))
                .from(attributeMapping)
                .join(attributeMapping.attribute, attribute)
                .join(attribute.entity, entity)
                .join(attributeMapping.column, column)
                .where(entity.projectId.eq(projectId), entity.model.id.eq(modelId))
                .fetch();
    }

    /** 상속 PK 매핑 (서브타입 엔터티의 테이블 컬럼 ↔ 루트 주 식별자 속성) */
    public record InheritedKeyRow(Long entityId, Long attributeId, String attributeName, Long columnId, String columnName, Long tableId) {
    }

    public List<InheritedKeyRow> inheritedKeys(Long projectId, Long modelId) {
        return query.select(Projections.constructor(InheritedKeyRow.class, entity.id, inheritedKey.attribute.id, inheritedKey.attribute.attributeName, column.id, column.columnName, column.tableInfo.id))
                .from(inheritedKey)
                .join(inheritedKey.mapping, mapping)
                .join(mapping.entity, entity)
                .join(inheritedKey.column, column)
                .where(entity.projectId.eq(projectId), entity.model.id.eq(modelId))
                .fetch();
    }

    /** 이 모델의 엔터티에 매핑된 테이블 */
    private static BooleanExpression mappedTable(com.querydsl.core.types.dsl.NumberPath<Long> tableId, Long projectId, Long modelId) {
        return tableId.in(JPAExpressions.select(scopeMapping.table.id).from(scopeMapping)
                .where(scopeMapping.entity.model.id.eq(modelId), scopeMapping.entity.projectId.eq(projectId)));
    }

    /** 물리 FK 의 컬럼 쌍 1개 (자식 = 원본 테이블, 부모 = 대상 테이블, 컬럼 순서) — 자식 테이블이 이 모델에 매핑된 FK 만 */
    public record ForeignKeyRow(Long relationId, String relationName, TableRelation.Source relationSource, Long childTableId, String childTableName,
                                Long parentTableId, String parentTableName, Integer columnOrder, Long childColumnId, String childColumnName,
                                Long parentColumnId, String parentColumnName) {
    }

    public List<ForeignKeyRow> foreignKeys(Long projectId, Long modelId) {
        return query.select(Projections.constructor(ForeignKeyRow.class, relation.id, relation.relationName, relation.relationSource, table.id, table.tableName,
                        parentTable.id, parentTable.tableName, relationColumn.columnOrder, column.id, column.columnName, parentColumn.id, parentColumn.columnName))
                .from(relation)
                .join(relation.sourceTable, table)
                .join(relation.targetTable, parentTable)
                .leftJoin(relationColumn).on(relationColumn.relation.eq(relation))
                .leftJoin(relationColumn.sourceColumn, column)
                .leftJoin(relationColumn.targetColumn, parentColumn)
                .where(relation.projectId.eq(projectId), relation.relationType.eq(TableRelation.Type.FK), mappedTable(table.id, projectId, modelId))
                .orderBy(relation.id.asc(), relationColumn.columnOrder.asc())
                .fetch();
    }

    /** UNIQUE 제약조건의 컬럼 1개 (제약조건 순서) */
    public record UniqueRow(Long constraintId, String constraintName, Long tableId, Integer columnOrder, Long columnId, String columnName) {
    }

    public List<UniqueRow> uniqueConstraints(Long projectId, Long modelId) {
        return query.select(Projections.constructor(UniqueRow.class, constraint.id, constraint.constraintName, constraint.tableInfo.id, constraintColumn.columnOrder,
                        column.id, column.columnName))
                .from(constraintColumn)
                .join(constraintColumn.tableConstraint, constraint)
                .join(constraintColumn.column, column)
                .where(constraint.projectId.eq(projectId), constraint.constraintType.eq(TableConstraint.Type.UNIQUE), mappedTable(constraint.tableInfo.id, projectId, modelId))
                .orderBy(constraint.id.asc(), constraintColumn.columnOrder.asc())
                .fetch();
    }

    /** 컬럼 단위 고유값 (PK 가 아닌 UNQ_YN — DDL 이 UK_테이블_컬럼 으로 만든다) · PK 컬럼 */
    public record ColumnFlagRow(Long tableId, String tableName, Long columnId, String columnName) {
    }

    public List<ColumnFlagRow> uniqueColumns(Long projectId, Long modelId) {
        return query.select(Projections.constructor(ColumnFlagRow.class, table.id, table.tableName, column.id, column.columnName))
                .from(column).join(column.tableInfo, table)
                .where(column.projectId.eq(projectId), column.isUnique.isTrue(), column.isPk.isNull().or(column.isPk.isFalse()), mappedTable(table.id, projectId, modelId))
                .orderBy(table.id.asc(), column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    public List<ColumnFlagRow> primaryKeyColumns(Long projectId, Long modelId) {
        return query.select(Projections.constructor(ColumnFlagRow.class, table.id, table.tableName, column.id, column.columnName))
                .from(column).join(column.tableInfo, table)
                .where(column.projectId.eq(projectId), column.isPk.isTrue(), mappedTable(table.id, projectId, modelId))
                .orderBy(table.id.asc(), column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    /**
     * FK 가 참조할 수 있는 고유 인덱스의 검색 키 1개 (M7-AK-D12 — 사후 무효 FK 판정이 DDL {@code TableDdlGenerator.referencesKey} 와 같은 키 범위를 보게 한다).
     * 고유 · 조건 없음 인덱스의 INCLUDE 가 아닌 구성 (표현식 요소는 columnId 가 null — 그런 인덱스는 판정에서 뺀다). 보조 식별자 진단은 고유 인덱스를 UNIQUE 로 보지 않는다
     */
    public record UniqueIndexRow(Long indexId, Long tableId, Integer columnOrder, Long columnId) {
    }

    public List<UniqueIndexRow> uniqueIndexes(Long projectId, Long modelId) {
        return query.select(Projections.constructor(UniqueIndexRow.class, index.id, index.tableInfo.id, indexColumn.columnOrder, column.id))
                .from(indexColumn)
                .join(indexColumn.tableIndex, index)
                .leftJoin(indexColumn.column, column)
                .where(index.projectId.eq(projectId), index.isUnique.isTrue(), index.condition.isNull(),
                        indexColumn.sort.isNull().or(indexColumn.sort.ne(TableIndexColumn.INCLUDE)), mappedTable(index.tableInfo.id, projectId, modelId))
                .orderBy(index.id.asc(), indexColumn.columnOrder.asc())
                .fetch();
    }
}
