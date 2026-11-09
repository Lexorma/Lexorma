package com.koboolean.metamora.home.repository.impl;

import com.koboolean.metamora.operation.board.domain.dto.BoardDto;
import com.koboolean.metamora.operation.board.domain.dto.BoardViewDto;
import com.koboolean.metamora.operation.board.domain.entity.Board;
import com.koboolean.metamora.operation.board.domain.entity.QBoard;
import com.koboolean.metamora.data.column.domain.entity.QColumnInfo;
import com.koboolean.metamora.data.dictionary.domain.entity.QStandardDomain;
import com.koboolean.metamora.data.dictionary.domain.entity.QStandardTerm;
import com.koboolean.metamora.data.dictionary.domain.entity.QStandardWord;
import com.koboolean.metamora.data.table.domain.entity.QTableInfo;
import com.koboolean.metamora.home.domain.dto.DashboardDto;
import com.koboolean.metamora.home.domain.dto.RecentChangeDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.home.repository.DashboardRepositoryCustom;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Repository
@RequiredArgsConstructor
public class DashboardRepositoryImpl implements DashboardRepositoryCustom {

    private final JPAQueryFactory query;

    public List<RecentChangeDto> findRecentChanges(Long projectId, int limit) {
        QStandardWord word = QStandardWord.standardWord;
        QStandardTerm term = QStandardTerm.standardTerm;
        QStandardDomain domain = QStandardDomain.standardDomain;
        QTableInfo tableInfo = QTableInfo.tableInfo;
        QColumnInfo columnInfo = QColumnInfo.columnInfo;

        List<RecentChangeDto> wordChanges = query
                .select(Projections.constructor(RecentChangeDto.class,
                        Expressions.constant("표준단어"),
                        word.commonStandardWordName,
                        word.updatedBy,
                        word.updated
                ))
                .from(word)
                .where(word.projectId.eq(projectId))
                .orderBy(word.updated.desc())
                .limit(limit)
                .fetch();

        List<RecentChangeDto> termChanges = query
                .select(Projections.constructor(RecentChangeDto.class,
                        Expressions.constant("표준용어"),
                        term.commonStandardTermName,
                        term.updatedBy,
                        term.updated
                ))
                .from(term)
                .where(term.projectId.eq(projectId))
                .orderBy(term.updated.desc())
                .limit(limit)
                .fetch();

        List<RecentChangeDto> domainChanges = query
                .select(Projections.constructor(RecentChangeDto.class,
                        Expressions.constant("표준도메인"),
                        domain.commonStandardDomainName,
                        domain.updatedBy,
                        domain.updated
                ))
                .from(domain)
                .where(domain.projectId.eq(projectId))
                .orderBy(domain.updated.desc())
                .limit(limit)
                .fetch();

        List<RecentChangeDto> tableChanges = query
                .select(Projections.constructor(RecentChangeDto.class,
                        Expressions.constant("테이블관리"),
                        tableInfo.tableName,
                        tableInfo.updatedBy,
                        tableInfo.updated
                ))
                .from(tableInfo)
                .where(tableInfo.projectId.eq(projectId))
                .orderBy(tableInfo.updated.desc())
                .limit(limit)
                .fetch();

        List<RecentChangeDto> columnChanges = query
                .select(Projections.constructor(RecentChangeDto.class,
                        Expressions.constant("컬럼관리"),
                        columnInfo.columnName,
                        columnInfo.updatedBy,
                        columnInfo.updated
                ))
                .from(columnInfo)
                .where(columnInfo.projectId.eq(projectId))
                .orderBy(columnInfo.updated.desc())
                .limit(limit)
                .fetch();

        return Stream.of(wordChanges, termChanges, domainChanges, tableChanges, columnChanges)
                .flatMap(Collection::stream)
                .sorted(Comparator.comparing(RecentChangeDto::modifiedAt).reversed())
                .limit(limit)
                .toList();
    }

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
    public DashboardDto findDataCount(Long projectId) {

        QStandardWord word = QStandardWord.standardWord;
        QStandardTerm term = QStandardTerm.standardTerm;
        QStandardDomain domain = QStandardDomain.standardDomain;

        int wordData = query.selectFrom(word).where(word.projectId.eq(projectId)).where(word.isApproval.eq(false)).fetch().size();
        int termData = query.selectFrom(term).where(term.projectId.eq(projectId)).where(term.isApproval.eq(false)).fetch().size();
        int domainData = query.selectFrom(domain).where(domain.projectId.eq(projectId)).where(domain.isApproval.eq(false)).fetch().size();



        QTableInfo tableInfo = QTableInfo.tableInfo;

        int tableSize = query.selectFrom(tableInfo).where(tableInfo.projectId.eq(projectId)).where(tableInfo.isApproval.eq(false)).fetch().size();

        QColumnInfo columnInfo = QColumnInfo.columnInfo;

        int columnSize = query.selectFrom(columnInfo).where(columnInfo.projectId.eq(projectId)).where(columnInfo.isApproval.eq(false)).fetch().size();

        return DashboardDto.builder()
                .pendingStandardCount(wordData + termData + domainData)
                .pendingTableCount(tableSize)
                .pendingColumnCount(columnSize)
                .build();
    }
}
