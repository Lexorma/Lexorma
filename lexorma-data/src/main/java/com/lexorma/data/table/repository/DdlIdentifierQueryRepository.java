package com.lexorma.data.table.repository;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.physical.domain.entity.QTableConstraint;
import com.lexorma.data.physical.domain.entity.QTableIndex;
import com.lexorma.data.physical.domain.entity.QTableRelation;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.table.ddl.DbIdentifierCheck;
import com.lexorma.data.table.ddl.DbIdentifierRules.Kind;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DBMS 식별자 판정({@link DbIdentifierCheck})에 넘길 물리 모델 이름들 (조회 전용). 프로젝트(또는 스키마 하나) 단위로 SQL 6번 — 테이블·컬럼 수와 무관하다.
 * FK 자동 이름 대상은 REF_COLUMN 관계가 있는 컬럼이다 (참조 동기화가 DDL 의 FK 조건과 같은 기준으로 만든다).
 */
@Repository
@RequiredArgsConstructor
public class DdlIdentifierQueryRepository {

    private static final QSchemaInfo schema = QSchemaInfo.schemaInfo;
    private static final QTableInfo table = QTableInfo.tableInfo;
    private static final QColumnInfo column = QColumnInfo.columnInfo;
    private static final QTableRelation relation = QTableRelation.tableRelation;
    private static final QTableConstraint constraint = QTableConstraint.tableConstraint;
    private static final QTableIndex index = QTableIndex.tableIndex;

    private final JPAQueryFactory query;

    public record ColumnRow(Long id, Long tableId, String name, Boolean pk, Boolean unique) {
    }

    public record NameRow(Long id, Long tableId, String name) {
    }

    public record SchemaRow(Long id, String name, Boolean isDefault) {
    }

    public record ConstraintRow(Long id, Long tableId, String name, TableConstraint.Type type) {
    }

    /** schemaId 가 null 이면 프로젝트 전체 */
    public DbIdentifierCheck.Model model(Long projectId, Long schemaId) {
        BooleanBuilder schemaWhere = new BooleanBuilder(schema.projectId.eq(projectId));
        if (schemaId != null) schemaWhere.and(schema.id.eq(schemaId));
        List<DbIdentifierCheck.Schema> schemas = query.select(Projections.constructor(SchemaRow.class, schema.id, schema.schemaName, schema.isDefault))
                .from(schema).where(schemaWhere).orderBy(schema.schemaName.asc(), schema.id.asc()).fetch().stream()
                .map(s -> new DbIdentifierCheck.Schema(s.id(), s.name(), Boolean.TRUE.equals(s.isDefault()))).toList();

        BooleanBuilder tableWhere = new BooleanBuilder(table.projectId.eq(projectId));
        if (schemaId != null) tableWhere.and(table.schema.id.eq(schemaId));
        List<DbIdentifierCheck.Table> tables = query.select(Projections.constructor(DbIdentifierCheck.Table.class, table.id, table.schema.id, table.tableName))
                .from(table).where(tableWhere).orderBy(table.tableName.asc(), table.id.asc()).fetch();

        BooleanBuilder columnWhere = new BooleanBuilder(column.projectId.eq(projectId));
        if (schemaId != null) columnWhere.and(column.tableInfo.schema.id.eq(schemaId));
        List<ColumnRow> columnRows = query.select(Projections.constructor(ColumnRow.class, column.id, column.tableInfo.id, column.columnName, column.isPk, column.isUnique))
                .from(column).where(columnWhere, column.tableInfo.isNotNull())
                .orderBy(column.tableInfo.id.asc(), column.sortOrder.asc().nullsLast(), column.id.asc()).fetch();

        BooleanBuilder relationWhere = new BooleanBuilder(relation.projectId.eq(projectId));
        if (schemaId != null) relationWhere.and(relation.sourceTable.schema.id.eq(schemaId));
        Set<Long> referenceColumns = new HashSet<>(query.select(relation.derivedColumnId).from(relation)
                .where(relationWhere, relation.relationSource.eq(TableRelation.Source.REF_COLUMN), relation.relationType.eq(TableRelation.Type.FK),
                        relation.derivedColumnId.isNotNull())
                .fetch());
        List<NameRow> userRelations = query.select(Projections.constructor(NameRow.class, relation.id, relation.sourceTable.id, relation.relationName))
                .from(relation)
                .where(relationWhere, relation.relationSource.ne(TableRelation.Source.REF_COLUMN), relation.relationType.eq(TableRelation.Type.FK))
                .orderBy(relation.id.asc()).fetch();

        BooleanBuilder constraintWhere = new BooleanBuilder(constraint.projectId.eq(projectId));
        if (schemaId != null) constraintWhere.and(constraint.tableInfo.schema.id.eq(schemaId));
        List<ConstraintRow> constraints = query.select(Projections.constructor(ConstraintRow.class,
                        constraint.id, constraint.tableInfo.id, constraint.constraintName, constraint.constraintType))
                .from(constraint).where(constraintWhere).orderBy(constraint.id.asc()).fetch();

        BooleanBuilder indexWhere = new BooleanBuilder(index.projectId.eq(projectId));
        if (schemaId != null) indexWhere.and(index.tableInfo.schema.id.eq(schemaId));
        List<NameRow> indexes = query.select(Projections.constructor(NameRow.class, index.id, index.tableInfo.id, index.indexName))
                .from(index).where(indexWhere).orderBy(index.id.asc()).fetch();

        List<DbIdentifierCheck.Column> columns = columnRows.stream()
                .map(c -> new DbIdentifierCheck.Column(c.id(), c.tableId(), c.name(), Boolean.TRUE.equals(c.pk()), Boolean.TRUE.equals(c.unique()),
                        referenceColumns.contains(c.id())))
                .toList();
        List<DbIdentifierCheck.StoredName> stored = new ArrayList<>();
        constraints.forEach(c -> stored.add(new DbIdentifierCheck.StoredName(c.type() == TableConstraint.Type.CHECK ? Kind.CHECK : Kind.UNIQUE, c.id(), c.tableId(), c.name())));
        indexes.forEach(i -> stored.add(new DbIdentifierCheck.StoredName(Kind.INDEX, i.id(), i.tableId(), i.name())));
        userRelations.forEach(r -> stored.add(new DbIdentifierCheck.StoredName(Kind.FOREIGN_KEY, r.id(), r.tableId(), r.name())));
        return new DbIdentifierCheck.Model(schemas, tables, columns, stored);
    }
}
