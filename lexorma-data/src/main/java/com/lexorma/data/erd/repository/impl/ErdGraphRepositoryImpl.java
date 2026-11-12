package com.lexorma.data.erd.repository.impl;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.entity.QStandardDomain;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.repository.ErdGraphRepository;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.entity.QTableRelation;
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
                        column.isApproval, domain.commonStandardDomainName))
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
        QTableRelationColumn pair = QTableRelationColumn.tableRelationColumn;
        QTableInfo source = new QTableInfo("sourceTable");
        QTableInfo target = new QTableInfo("targetTable");
        QColumnInfo sourceColumn = new QColumnInfo("sourceColumn");
        QColumnInfo targetColumn = new QColumnInfo("targetColumn");

        List<Tuple> rows = query.select(relation.id, relation.relationName, relation.relationType, relation.relationSource,
                        source.id, source.tableName, target.id, target.tableName, relation.isApproval,
                        sourceColumn.id, sourceColumn.columnName, targetColumn.id, targetColumn.columnName)
                .from(pair)
                .join(pair.relation, relation)
                .join(relation.sourceTable, source)
                .join(relation.targetTable, target)
                .join(pair.sourceColumn, sourceColumn)
                .join(pair.targetColumn, targetColumn)
                .where(relation.projectId.eq(projectId), source.id.in(tableIds).or(target.id.in(tableIds)))
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
                    row.get(target.id), row.get(target.tableName), pairs.get(e.getKey()), row.get(relation.isApproval));
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
