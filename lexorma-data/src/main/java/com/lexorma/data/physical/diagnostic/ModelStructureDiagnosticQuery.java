package com.lexorma.data.physical.diagnostic;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.physical.domain.entity.QTableConstraint;
import com.lexorma.data.physical.domain.entity.QTableConstraintColumn;
import com.lexorma.data.physical.domain.entity.QTableIndex;
import com.lexorma.data.physical.domain.entity.QTableIndexColumn;
import com.lexorma.data.physical.domain.entity.QTableRelation;
import com.lexorma.data.physical.domain.entity.QTableRelationColumn;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 물리 모델 구조 진단의 일괄 조회 (M-DIAG-PERF). 프로젝트 단위로 종류마다 쿼리 1번 — 테이블 수와 무관하다 (예전에는 테이블마다 참조 키·관계·제약조건·인덱스를 따로 읽었다).
 * QueryDSL DTO 프로젝션이라 엔티티를 적재하지 않는다. 컬럼 컬렉션을 한 쿼리에 함께 조인하지 않아 행이 곱해지지 않는다.
 * 프로젝트 전체를 읽는 이유 : 참조 대상·관계 대상은 진단 범위(스키마) 밖 테이블일 수 있다 — 범위는 서비스가 메모리에서 거른다 (긴 IN 목록을 만들지 않는다)
 */
@Repository
@RequiredArgsConstructor
public class ModelStructureDiagnosticQuery {

    private final JPAQueryFactory query;

    public record TableRow(Long id, Long schemaId, String schemaName, String tableName) {
    }

    /** PK 컬럼 (참조 대상 PK 판단·PK 순서) */
    public record PkColumnRow(Long id, Long tableId, String columnName, Integer sortOrder) {
    }

    /** 참조 테이블명을 적은 컬럼 */
    public record RefColumnRow(Long id, Long tableId, String columnName, String refTableName, Boolean isApproval) {
    }

    /** 컬럼에서 만든 관계(DRVN_COL_SN)의 확정 대상 : 이름이 모호할 때 지금 대상을 유지하는 힌트 */
    public record DerivedTargetRow(Long relationId, Long derivedColumnId, Long targetTableId) {
    }

    /** 사용자 정의 FK 관계 (REF_COLUMN 이 아닌 FK) 의 대상 컬럼 쌍 한 행. 쌍이 없는 관계는 대상 컬럼 값이 null 인 한 행 */
    public record RelationPairRow(Long relationId, String relationName, Long sourceTableId, Long targetTableId, Integer columnOrder,
                                  String targetColumnName, Boolean targetColumnUnique) {
    }

    public record UniqueMemberRow(Long constraintId, Long tableId, String columnName) {
    }

    public record IndexRow(Long id, Long tableId, String indexName, Boolean isUnique, String condition, Boolean isApproval) {
    }

    /** 인덱스 구성 요소 (columnOrder 순) : 컬럼 또는 표현식, 정렬(INCLUDE 포함) */
    public record IndexMemberRow(Long indexId, Long columnId, String columnName, String expression, String sort) {
    }

    public List<TableRow> tables(Long projectId) {
        QTableInfo table = QTableInfo.tableInfo;
        return query.select(Projections.constructor(TableRow.class, table.id, table.schema.id, table.schema.schemaName, table.tableName))
                .from(table)
                .join(table.schema)
                .where(table.projectId.eq(projectId))
                .orderBy(table.id.asc())
                .fetch();
    }

    public List<PkColumnRow> pkColumns(Long projectId) {
        QColumnInfo column = QColumnInfo.columnInfo;
        return query.select(Projections.constructor(PkColumnRow.class, column.id, column.tableInfo.id, column.columnName, column.sortOrder))
                .from(column)
                .where(column.projectId.eq(projectId), column.isPk.isTrue(), column.tableInfo.isNotNull())
                .orderBy(column.id.asc())
                .fetch();
    }

    public List<RefColumnRow> refColumns(Long projectId) {
        QColumnInfo column = QColumnInfo.columnInfo;
        return query.select(Projections.constructor(RefColumnRow.class, column.id, column.tableInfo.id, column.columnName, column.refTableName, column.isApproval))
                .from(column)
                .where(column.projectId.eq(projectId), column.refTableName.isNotNull(), column.tableInfo.isNotNull())
                .orderBy(column.id.asc())
                .fetch();
    }

    public List<DerivedTargetRow> derivedTargets(Long projectId) {
        QTableRelation relation = QTableRelation.tableRelation;
        return query.select(Projections.constructor(DerivedTargetRow.class, relation.id, relation.derivedColumnId, relation.targetTable.id))
                .from(relation)
                .where(relation.projectId.eq(projectId), relation.derivedColumnId.isNotNull())
                .orderBy(relation.id.asc())
                .fetch();
    }

    public List<RelationPairRow> foreignKeyRelations(Long projectId) {
        QTableRelation relation = QTableRelation.tableRelation;
        QTableRelationColumn pair = QTableRelationColumn.tableRelationColumn;
        QColumnInfo target = new QColumnInfo("targetColumn");
        return query.select(Projections.constructor(RelationPairRow.class, relation.id, relation.relationName, relation.sourceTable.id, relation.targetTable.id,
                        pair.columnOrder, target.columnName, target.isUnique))
                .from(relation)
                .leftJoin(relation.columns, pair)
                .leftJoin(pair.targetColumn, target)
                .where(relation.projectId.eq(projectId), relation.relationType.eq(TableRelation.Type.FK), relation.relationSource.ne(TableRelation.Source.REF_COLUMN))
                .orderBy(relation.id.asc(), pair.columnOrder.asc())
                .fetch();
    }

    public List<UniqueMemberRow> uniqueConstraintMembers(Long projectId) {
        QTableConstraint constraint = QTableConstraint.tableConstraint;
        QTableConstraintColumn member = QTableConstraintColumn.tableConstraintColumn;
        QColumnInfo column = QColumnInfo.columnInfo;
        // 구성 컬럼이 없는 제약도 빈 집합으로 남긴다 (DDL 쪽 판단과 같게) : 제약에서 출발하는 left join
        return query.select(Projections.constructor(UniqueMemberRow.class, constraint.id, constraint.tableInfo.id, column.columnName))
                .from(constraint)
                .leftJoin(constraint.columns, member)
                .leftJoin(member.column, column)
                .where(constraint.projectId.eq(projectId), constraint.constraintType.eq(TableConstraint.Type.UNIQUE))
                .orderBy(constraint.id.asc(), member.columnOrder.asc())
                .fetch();
    }

    public List<IndexRow> indexes(Long projectId) {
        QTableIndex index = QTableIndex.tableIndex;
        return query.select(Projections.constructor(IndexRow.class, index.id, index.tableInfo.id, index.indexName, index.isUnique, index.condition, index.isApproval))
                .from(index)
                .where(index.projectId.eq(projectId))
                .orderBy(index.id.asc())
                .fetch();
    }

    public List<IndexMemberRow> indexMembers(Long projectId) {
        QTableIndex index = QTableIndex.tableIndex;
        QTableIndexColumn member = QTableIndexColumn.tableIndexColumn;
        QColumnInfo column = QColumnInfo.columnInfo;
        return query.select(Projections.constructor(IndexMemberRow.class, index.id, column.id, column.columnName, member.expression, member.sort))
                .from(member)
                .join(member.tableIndex, index)
                .leftJoin(member.column, column)
                .where(index.projectId.eq(projectId))
                .orderBy(index.id.asc(), member.columnOrder.asc())
                .fetch();
    }
}
