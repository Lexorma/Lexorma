package com.lexorma.home.repository;

import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.home.domain.dto.DashboardStatsDto;
import com.lexorma.operation.board.domain.dto.BoardViewDto;

import java.util.List;

public interface DashboardRepositoryCustom {

    List<BoardViewDto> findNotice(Long projectId, int limit, Account account);

    /** 등록 수 (DB 집계) */
    DashboardStatsDto.Counts countAll(Long projectId);

    /** 유형별 승인·승인 대기 수 (WORD · DOMAIN · TERM · TABLE · COLUMN) */
    List<DashboardStatsDto.Approval> countApprovals(Long projectId);

    /** [표준 용어가 연결된 컬럼, 승인된 용어가 연결된 컬럼] */
    long[] countLinkedColumns(Long projectId);

    /** [코멘트 없는 테이블, 코멘트 없는 컬럼, 업무 설명 없는 테이블, 업무 설명 없는 컬럼] — null·공백만이면 없음 */
    long[] countMissingDescriptions(Long projectId);

    /** 스키마별 테이블 수 (테이블이 없는 스키마 포함, 스키마 이름 순) */
    List<DashboardStatsDto.SchemaTables> countTablesBySchema(Long projectId);

    /** 컬럼이 있는 테이블마다 컬럼 수 (테이블 ID 는 싣지 않는다) */
    List<Long> columnCountsPerTable(Long projectId);

    /** 원본·대상 어느 쪽으로도 등록된 관계(FK·LOGICAL)가 없는 테이블 수 */
    long countTablesWithoutRelation(Long projectId);
}
