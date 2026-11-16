package com.lexorma.home.repository.impl;

import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.entity.QStandardDomain;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.dictionary.domain.entity.QStandardWord;
import com.lexorma.data.physical.domain.entity.QTableConstraint;
import com.lexorma.data.physical.domain.entity.QTableIndex;
import com.lexorma.data.physical.domain.entity.QTableRelation;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.lexorma.home.domain.dto.DashboardStatsDto;
import com.lexorma.home.repository.DashboardRepositoryCustom;
import com.lexorma.operation.board.domain.dto.BoardViewDto;
import com.lexorma.operation.board.domain.entity.QBoard;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.BooleanPath;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.EntityPathBase;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 대시보드 조회. 통계는 모두 DB 의 count·group by 로 센다 (엔티티를 읽어 메모리에서 세지 않는다).
 */
@Repository
@RequiredArgsConstructor
public class DashboardRepositoryImpl implements DashboardRepositoryCustom {

    private final JPAQueryFactory query;

    @Override
    public List<BoardViewDto> findNotice(Long projectId, int limit, Account account) {
        QBoard board = QBoard.board;

        return query
                .select(Projections.constructor(BoardViewDto.class,
                        board.id,
                        board.projectId,
                        board.createdBy.as("createdBy"),
                        board.created.as("created"),
                        board.title,
                                new CaseBuilder()
                                        .when(board.accounts.contains(account)).then("확인")
                                        .otherwise("미확인").as("isHit")
                ))
                .from(board)
                .where(board.projectId.eq(projectId))
                .where(board.deleteYn.eq('N'))
                .orderBy(board.created.desc())
                .limit(limit)
                .fetch();
    }

    @Override
    public DashboardStatsDto.Counts countAll(Long projectId) {
        QStandardWord word = QStandardWord.standardWord;
        QStandardDomain domain = QStandardDomain.standardDomain;
        QStandardTerm term = QStandardTerm.standardTerm;
        QSchemaInfo schema = QSchemaInfo.schemaInfo;
        QTableInfo table = QTableInfo.tableInfo;
        QColumnInfo column = QColumnInfo.columnInfo;
        QTableRelation relation = QTableRelation.tableRelation;
        QTableIndex index = QTableIndex.tableIndex;
        QTableConstraint constraint = QTableConstraint.tableConstraint;

        long fk = 0, logical = 0;
        for (Tuple t : query.select(relation.relationType, relation.count()).from(relation).where(relation.projectId.eq(projectId)).groupBy(relation.relationType).fetch()) {
            if (t.get(relation.relationType) == TableRelation.Type.FK) fk = t.get(relation.count());
            else logical += t.get(relation.count());
        }
        long unique = 0, check = 0;
        for (Tuple t : query.select(constraint.constraintType, constraint.count()).from(constraint).where(constraint.projectId.eq(projectId)).groupBy(constraint.constraintType).fetch()) {
            if (t.get(constraint.constraintType) == TableConstraint.Type.UNIQUE) unique = t.get(constraint.count());
            else check += t.get(constraint.count());
        }
        return new DashboardStatsDto.Counts(
                count(word, word.projectId, projectId), count(domain, domain.projectId, projectId), count(term, term.projectId, projectId),
                count(schema, schema.projectId, projectId), count(table, table.projectId, projectId), count(column, column.projectId, projectId),
                fk + logical, fk, logical, count(index, index.projectId, projectId), unique + check, unique, check);
    }

    @Override
    public List<DashboardStatsDto.Approval> countApprovals(Long projectId) {
        QStandardWord word = QStandardWord.standardWord;
        QStandardDomain domain = QStandardDomain.standardDomain;
        QStandardTerm term = QStandardTerm.standardTerm;
        QTableInfo table = QTableInfo.tableInfo;
        QColumnInfo column = QColumnInfo.columnInfo;
        return List.of(
                approval("WORD", word, word.projectId, word.isApproval, projectId),
                approval("DOMAIN", domain, domain.projectId, domain.isApproval, projectId),
                approval("TERM", term, term.projectId, term.isApproval, projectId),
                approval("TABLE", table, table.projectId, table.isApproval, projectId),
                approval("COLUMN", column, column.projectId, column.isApproval, projectId));
    }

    @Override
    public long[] countLinkedColumns(Long projectId) {
        QColumnInfo column = QColumnInfo.columnInfo;
        QStandardTerm term = QStandardTerm.standardTerm;
        Long linked = query.select(column.count()).from(column).where(column.projectId.eq(projectId), column.standardTerms.isNotNull()).fetchOne();
        Long approved = query.select(column.count()).from(column).join(column.standardTerms, term)
                .where(column.projectId.eq(projectId), term.isApproval.isTrue()).fetchOne();
        return new long[]{nz(linked), nz(approved)};
    }

    @Override
    public long[] countMissingDescriptions(Long projectId) {
        QTableInfo table = QTableInfo.tableInfo;
        QColumnInfo column = QColumnInfo.columnInfo;
        return new long[]{
                nz(query.select(table.count()).from(table).where(table.projectId.eq(projectId), blank(table.tableKorName)).fetchOne()),
                nz(query.select(column.count()).from(column).where(column.projectId.eq(projectId), blank(column.columnDesc)).fetchOne()),
                nz(query.select(table.count()).from(table).where(table.projectId.eq(projectId), blank(table.bizDescription)).fetchOne()),
                nz(query.select(column.count()).from(column).where(column.projectId.eq(projectId), blank(column.bizDescription)).fetchOne())};
    }

    @Override
    public List<DashboardStatsDto.SchemaTables> countTablesBySchema(Long projectId) {
        QSchemaInfo schema = QSchemaInfo.schemaInfo;
        QTableInfo table = QTableInfo.tableInfo;
        return query.select(Projections.constructor(DashboardStatsDto.SchemaTables.class, schema.id, schema.schemaName, table.id.count()))
                .from(schema)
                .leftJoin(table).on(table.schema.id.eq(schema.id), table.projectId.eq(projectId))
                .where(schema.projectId.eq(projectId))
                .groupBy(schema.id, schema.schemaName)
                .orderBy(schema.schemaName.asc(), schema.id.asc())
                .fetch();
    }

    @Override
    public List<Long> columnCountsPerTable(Long projectId) {
        QColumnInfo column = QColumnInfo.columnInfo;
        return query.select(column.count()).from(column).where(column.projectId.eq(projectId)).groupBy(column.tableInfo.id).fetch();
    }

    @Override
    public long countTablesWithoutRelation(Long projectId) {
        QTableInfo table = QTableInfo.tableInfo;
        QTableRelation relation = QTableRelation.tableRelation;
        // 프로젝트 단위 비상관 IN 두 개 (테이블마다 다시 훑지 않게)
        return nz(query.select(table.count()).from(table)
                .where(table.projectId.eq(projectId),
                        table.id.notIn(JPAExpressions.select(relation.sourceTable.id).from(relation).where(relation.projectId.eq(projectId))),
                        table.id.notIn(JPAExpressions.select(relation.targetTable.id).from(relation).where(relation.projectId.eq(projectId))))
                .fetchOne());
    }

    private long count(EntityPathBase<?> entity, NumberPath<Long> projectPath, Long projectId) {
        return nz(query.select(projectPath.count()).from(entity).where(projectPath.eq(projectId)).fetchOne());
    }

    /** 승인 = true, 승인 대기 = 그 밖 (false·null) */
    private DashboardStatsDto.Approval approval(String type, EntityPathBase<?> entity, NumberPath<Long> projectPath, BooleanPath approvalPath, Long projectId) {
        long approved = 0, pending = 0;
        for (Tuple t : query.select(approvalPath, projectPath.count()).from(entity).where(projectPath.eq(projectId)).groupBy(approvalPath).fetch()) {
            if (Boolean.TRUE.equals(t.get(approvalPath))) approved += t.get(projectPath.count());
            else pending += t.get(projectPath.count());
        }
        return new DashboardStatsDto.Approval(type, approved, pending);
    }

    private static BooleanExpression blank(StringPath path) {
        return path.isNull().or(path.trim().isEmpty());
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
