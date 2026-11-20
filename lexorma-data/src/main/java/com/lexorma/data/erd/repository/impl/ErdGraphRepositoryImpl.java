package com.lexorma.data.erd.repository.impl;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.entity.QStandardDomain;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.repository.ErdGraphRepository;
import com.lexorma.data.erd.service.PhysicalCardinality;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.entity.QTableConstraint;
import com.lexorma.data.physical.domain.entity.QTableConstraintColumn;
import com.lexorma.data.physical.domain.entity.QTableIndex;
import com.lexorma.data.physical.domain.entity.QTableIndexColumn;
import com.lexorma.data.physical.domain.entity.QTableRelation;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.QTableRelationColumn;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@RequiredArgsConstructor
public class ErdGraphRepositoryImpl implements ErdGraphRepository {

    private static final QTableInfo table = QTableInfo.tableInfo;

    private final JPAQueryFactory query;

    @Override
    public long countTables(Long projectId, ErdDto.Search search) {
        Long count = query.select(table.count()).from(table).where(searchCondition(projectId, search)).fetchOne();
        return count == null ? 0 : count;
    }

    @Override
    public List<ErdDto.Table> findTables(Long projectId, ErdDto.Search search, long offset, int limit) {
        return query.select(tableProjection())
                .from(table)
                .where(searchCondition(projectId, search))
                .orderBy(table.schema.isDefault.desc(), table.schema.schemaName.asc(), table.tableName.asc(), table.id.asc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    @Override
    public List<ErdDto.Table> findTablesByIds(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        return query.select(tableProjection())
                .from(table)
                .where(table.projectId.eq(projectId), table.id.in(tableIds))
                .orderBy(table.tableName.asc(), table.id.asc())
                .fetch();
    }

    @Override
    public List<ErdDto.Column> findColumns(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        QColumnInfo column = QColumnInfo.columnInfo;
        QStandardTerm term = QStandardTerm.standardTerm;
        QStandardDomain domain = QStandardDomain.standardDomain;
        return query.select(Projections.constructor(ErdDto.Column.class,
                        column.id, column.tableInfo.id, column.columnName, term.commonStandardTermName, column.columnDesc,
                        column.dataType, column.maxLength, column.precision, column.scale, column.isNullable, column.isPk,
                        column.isApproval, domain.commonStandardDomainName, column.isUnique, column.isRequired))
                .from(column)
                .leftJoin(column.standardTerms, term)
                .leftJoin(term.standardDomain, domain)
                .where(column.projectId.eq(projectId), column.tableInfo.id.in(tableIds))
                .orderBy(column.tableInfo.id.asc(), column.sortOrder.asc().nullsLast(), column.id.asc())
                .fetch();
    }

    @Override
    public List<PhysicalModelDto.Relation> findRelations(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        QTableRelation relation = QTableRelation.tableRelation;
        return relations(projectId, relation.sourceTable.id.in(tableIds).or(relation.targetTable.id.in(tableIds)));
    }

    @Override
    public List<PhysicalModelDto.Relation> findInnerRelations(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        // 1) 범위 테이블이 원본인 관계의 ID·대상만 (원본 인덱스) → 대상도 범위 안인 것만 남긴다 2) 그 관계만 컬럼 쌍까지.
        // 두 IN 을 한 조건에 함께 쓰면 H2 가 인덱스 대신 행마다 목록 전체를 비교했다 (범위 300 에서 약 180ms, M-2 개선 2차 측정)
        QTableRelation relation = QTableRelation.tableRelation;
        Set<Long> scope = new HashSet<>(tableIds);
        List<Long> inner = query.select(relation.id, relation.targetTable.id).from(relation)
                .where(relation.projectId.eq(projectId), relation.sourceTable.id.in(tableIds))
                .fetch().stream()
                .filter(row -> scope.contains(row.get(relation.targetTable.id)))
                .map(row -> row.get(relation.id))
                .toList();
        return inner.isEmpty() ? List.of() : relations(projectId, relation.id.in(inner));
    }

    @Override
    public Map<Long, Long> countRelationEnds(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return Map.of();
        }
        // 관계 행 단위로 센다 (TB_TBL_REL — 컬럼 쌍 테이블과 조인하지 않으므로 복합 FK 도 1). 원본 쪽·대상 쪽 각각 인덱스로 찾는다
        QTableRelation relation = QTableRelation.tableRelation;
        Map<Long, Long> counts = new HashMap<>();
        query.select(relation.sourceTable.id, relation.count())
                .from(relation)
                .where(relation.projectId.eq(projectId), relation.sourceTable.id.in(tableIds))
                .groupBy(relation.sourceTable.id)
                .fetch()
                .forEach(row -> counts.merge(row.get(0, Long.class), row.get(1, Long.class), Long::sum));
        query.select(relation.targetTable.id, relation.count())
                .from(relation)
                .where(relation.projectId.eq(projectId), relation.targetTable.id.in(tableIds))
                .groupBy(relation.targetTable.id)
                .fetch()
                .forEach(row -> counts.merge(row.get(0, Long.class), row.get(1, Long.class), Long::sum));
        return counts;
    }

    @Override
    public List<Long> findFkColumnIds(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        QTableRelation relation = QTableRelation.tableRelation;
        QTableRelationColumn pair = QTableRelationColumn.tableRelationColumn;
        return query.select(pair.sourceColumn.id).distinct()
                .from(pair)
                .join(pair.relation, relation)
                .where(relation.projectId.eq(projectId), relation.sourceTable.id.in(tableIds), relation.relationType.eq(TableRelation.Type.FK))
                .orderBy(pair.sourceColumn.id.asc())
                .fetch();
    }

    @Override
    public long countTableRelations(Long projectId, Long tableId) {
        QTableRelation relation = QTableRelation.tableRelation;
        Long count = query.select(relation.count()).from(relation)
                .where(relation.projectId.eq(projectId), relation.sourceTable.id.eq(tableId).or(relation.targetTable.id.eq(tableId)))
                .fetchOne();
        return count == null ? 0 : count;
    }

    @Override
    public List<PhysicalModelDto.Relation> findTableRelations(Long projectId, Long tableId, long offset, int limit) {
        QTableRelation relation = QTableRelation.tableRelation;
        // 관계 ID 한 페이지를 먼저 정하고 그 관계의 컬럼 쌍을 모두 가져온다 (쌍 행으로 페이지를 자르면 복합 FK 가 잘린다)
        List<Long> ids = query.select(relation.id).from(relation)
                .where(relation.projectId.eq(projectId), relation.sourceTable.id.eq(tableId).or(relation.targetTable.id.eq(tableId)))
                .orderBy(relation.id.asc())
                .offset(offset)
                .limit(limit)
                .fetch();
        return ids.isEmpty() ? List.of() : relations(projectId, relation.id.in(ids));
    }

    @Override
    public List<ErdDto.TableHit> findTableHits(Long projectId, ErdDto.Search search, int limit) {
        return query.select(Projections.constructor(ErdDto.TableHit.class, table.id, table.schema.id, table.schema.schemaName, table.tableName, table.tableKorName))
                .from(table)
                .where(searchCondition(projectId, search))
                .orderBy(table.schema.isDefault.desc(), table.schema.schemaName.asc(), table.tableName.asc(), table.id.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public List<PhysicalCardinality.ColumnFlags> findColumnFlags(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        QColumnInfo column = QColumnInfo.columnInfo;
        return query.select(Projections.constructor(PhysicalCardinality.ColumnFlags.class,
                        column.id, column.tableInfo.id, column.isNullable, column.isPk, column.isUnique, column.isRequired))
                .from(column)
                .where(column.projectId.eq(projectId), column.tableInfo.id.in(tableIds))
                .fetch();
    }

    @Override
    public Map<Long, List<Set<Long>>> findUniqueKeys(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Set<Long>>> keys = new HashMap<>();
        QTableConstraint constraint = QTableConstraint.tableConstraint;
        QTableConstraintColumn member = QTableConstraintColumn.tableConstraintColumn;
        Map<Long, Set<Long>> constraints = new LinkedHashMap<>();
        Map<Long, Long> constraintTable = new HashMap<>();
        query.select(constraint.id, constraint.tableInfo.id, member.column.id)
                .from(member)
                .join(member.tableConstraint, constraint)
                .where(constraint.projectId.eq(projectId), constraint.tableInfo.id.in(tableIds), constraint.constraintType.eq(TableConstraint.Type.UNIQUE))
                .orderBy(constraint.id.asc())
                .fetch()
                .forEach(row -> {
                    constraints.computeIfAbsent(row.get(constraint.id), k -> new HashSet<>()).add(row.get(member.column.id));
                    constraintTable.put(row.get(constraint.id), row.get(constraint.tableInfo.id));
                });
        constraints.forEach((id, columns) -> keys.computeIfAbsent(constraintTable.get(id), k -> new ArrayList<>()).add(columns));

        // 고유 인덱스 : 조건(부분 인덱스) 없음 + 키가 모두 컬럼 (표현식이 하나라도 있으면 컬럼 값의 유일성을 보장하지 않는다). INCLUDE 는 키가 아니다
        QTableIndex index = QTableIndex.tableIndex;
        QTableIndexColumn indexMember = QTableIndexColumn.tableIndexColumn;
        Map<Long, Set<Long>> indexes = new LinkedHashMap<>();
        Map<Long, Long> indexTable = new HashMap<>();
        Set<Long> expression = new HashSet<>();
        query.select(index.id, index.tableInfo.id, indexMember.column.id, indexMember.sort)
                .from(indexMember)
                .join(indexMember.tableIndex, index)
                .where(index.projectId.eq(projectId), index.tableInfo.id.in(tableIds), index.isUnique.isTrue(), index.condition.isNull())
                .orderBy(index.id.asc())
                .fetch()
                .forEach(row -> {
                    Long id = row.get(index.id);
                    indexTable.put(id, row.get(index.tableInfo.id));
                    if (TableIndexColumn.INCLUDE.equals(row.get(indexMember.sort))) return;
                    Long columnId = row.get(indexMember.column.id);
                    if (columnId == null) expression.add(id);
                    else indexes.computeIfAbsent(id, k -> new HashSet<>()).add(columnId);
                });
        indexes.forEach((id, columns) -> {
            if (!expression.contains(id)) keys.computeIfAbsent(indexTable.get(id), k -> new ArrayList<>()).add(columns);
        });
        return keys;
    }

    /** 관계 + 컬럼 쌍 (행 = 컬럼 쌍 → 관계 ID 별로 묶음, 관계 ID → 컬럼 순서) */
    private List<PhysicalModelDto.Relation> relations(Long projectId, com.querydsl.core.types.Predicate condition) {
        QTableRelation relation = QTableRelation.tableRelation;
        QTableRelationColumn pair = QTableRelationColumn.tableRelationColumn;
        QTableInfo source = new QTableInfo("sourceTable");
        QTableInfo target = new QTableInfo("targetTable");
        QColumnInfo sourceColumn = new QColumnInfo("sourceColumn");
        QColumnInfo targetColumn = new QColumnInfo("targetColumn");

        List<Tuple> rows = query.select(relation.id, relation.relationName, relation.relationType, relation.relationSource,
                        source.id, source.tableName, target.id, target.tableName, relation.isApproval, relation.deleteRule, relation.updateRule, relation.updated,
                        sourceColumn.id, sourceColumn.columnName, targetColumn.id, targetColumn.columnName)
                .from(pair)
                .join(pair.relation, relation)
                .join(relation.sourceTable, source)
                .join(relation.targetTable, target)
                .join(pair.sourceColumn, sourceColumn)
                .join(pair.targetColumn, targetColumn)
                .where(relation.projectId.eq(projectId), condition)
                .orderBy(relation.id.asc(), pair.columnOrder.asc())
                .fetch();

        // 관계 ID 별로 컬럼 쌍을 모은다 (행 순서 = 관계 ID → 컬럼 순서)
        Map<Long, Tuple> heads = new LinkedHashMap<>();
        Map<Long, List<PhysicalModelDto.ColumnPair>> pairs = new HashMap<>();
        for (Tuple row : rows) {
            Long id = row.get(relation.id);
            heads.putIfAbsent(id, row);
            pairs.computeIfAbsent(id, k -> new ArrayList<>()).add(new PhysicalModelDto.ColumnPair(
                    row.get(sourceColumn.id), row.get(sourceColumn.columnName), row.get(targetColumn.id), row.get(targetColumn.columnName)));
        }
        return heads.entrySet().stream().map(e -> {
            Tuple row = e.getValue();
            return new PhysicalModelDto.Relation(e.getKey(), row.get(relation.relationName), row.get(relation.relationType).name(),
                    row.get(relation.relationSource).name(), row.get(source.id), row.get(source.tableName),
                    row.get(target.id), row.get(target.tableName), pairs.get(e.getKey()), row.get(relation.isApproval),
                    PhysicalModelDto.Relation.ruleName(row.get(relation.deleteRule)), PhysicalModelDto.Relation.ruleName(row.get(relation.updateRule)), row.get(relation.updated));
        }).toList();
    }

    @Override
    public Set<Long> findLinkedTableIds(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return Set.of();
        }
        QTableRelation relation = QTableRelation.tableRelation;
        Set<Long> linked = new HashSet<>();
        query.select(relation.sourceTable.id, relation.targetTable.id)
                .from(relation)
                .where(relation.projectId.eq(projectId), relation.sourceTable.id.in(tableIds).or(relation.targetTable.id.in(tableIds)))
                .fetch()
                .forEach(row -> {
                    linked.add(row.get(relation.sourceTable.id));
                    linked.add(row.get(relation.targetTable.id));
                });
        return linked;
    }

    @Override
    public Set<Long> findExistingTableIds(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(query.select(table.id).from(table)
                .where(table.projectId.eq(projectId), table.id.in(tableIds))
                .fetch());
    }

    private static ConstructorExpression<ErdDto.Table> tableProjection() {
        return Projections.constructor(ErdDto.Table.class, table.id, table.schema.id, table.tableName, table.tableKorName,
                table.tableDescription, table.isApproval, table.isMasterTable);
    }

    /** 키워드 : 테이블 물리명·논리명(한글명)·스키마명 부분 일치 (대소문자 무시) */
    private static BooleanBuilder searchCondition(Long projectId, ErdDto.Search search) {
        BooleanBuilder where = new BooleanBuilder(table.projectId.eq(projectId));
        if (search.schemaId() != null) {
            where.and(table.schema.id.eq(search.schemaId()));
        }
        if (search.keyword() != null && !search.keyword().isBlank()) {
            String keyword = search.keyword().trim();
            where.and(table.tableName.containsIgnoreCase(keyword)
                    .or(table.tableKorName.containsIgnoreCase(keyword))
                    .or(table.schema.schemaName.containsIgnoreCase(keyword)));
        }
        if (search.isApproval() != null) {
            where.and(search.isApproval() ? table.isApproval.isTrue() : table.isApproval.isNull().or(table.isApproval.isFalse()));
        }
        if (search.isMasterTable() != null) {
            where.and(search.isMasterTable() ? table.isMasterTable.isTrue() : table.isMasterTable.isNull().or(table.isMasterTable.isFalse()));
        }
        return where;
    }
}
