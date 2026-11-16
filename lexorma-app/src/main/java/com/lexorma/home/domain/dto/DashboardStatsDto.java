package com.lexorma.home.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 대시보드 통계 (현재 프로젝트, 조회 시점의 DB 값). 모든 수는 저장된 행을 그대로 센다 — 이 프로젝트는 표준·모델 데이터를 지우면 행을 삭제하므로(논리 삭제 없음) 따로 거를 것이 없다.
 * 승인 상태는 승인 여부(APRV_YN) 하나뿐이다 : 승인(true) / 승인 대기(false·null — 칸이 null 을 허용한다). 반려 상태는 없다.
 */
public record DashboardStatsDto(
        Counts counts,
        List<Approval> approvals,
        boolean canApproveDictionary,
        boolean canApproveModel,
        Standard standard,
        Quality quality,
        Model model,
        LocalDateTime generatedAt
) {

    /** 등록 수 : 표준 단어·도메인·용어, 스키마·테이블·컬럼, 관계(FK·LOGICAL), 인덱스, 제약조건(UNIQUE·CHECK) */
    public record Counts(long words, long domains, long terms, long schemas, long tables, long columns,
                         long relations, long fkRelations, long logicalRelations, long indexes, long constraints, long uniqueConstraints,
                         long checkConstraints) {
    }

    /** 유형별 승인 상태. type : WORD · DOMAIN · TERM · TABLE · COLUMN */
    public record Approval(String type, long approved, long pending) {
    }

    /**
     * 표준 적용 : 연결률 = 표준 용어가 연결된 컬럼 / 전체 컬럼 (용어의 승인 여부와 무관, 승인된 용어 연결 수는 따로).
     * 규칙 준수 = 표준화 품질 진단(M-5)에서 문제가 하나도 없는 컬럼 (용어 연결 + 컬럼명 = 용어 영문약어 + 도메인 정의 일치)
     */
    public record Standard(long totalColumns, long linkedColumns, long linkedApprovedTermColumns, long compliantColumns) {
    }

    /**
     * 품질 : 표준화 품질 진단(M-5) 유형별 건수(issueColumns 는 문제가 하나라도 있는 컬럼 수 — 유형별 합과 다르다),
     * 코멘트(DDL COMMENT : 테이블은 테이블 코멘트, 컬럼은 컬럼 설명)·업무 설명이 빈 테이블·컬럼 수
     */
    public record Quality(long issueColumns, long termNotLinked, long columnNameMismatch, long domainDefinitionMismatch,
                          long tablesWithoutComment, long columnsWithoutComment, long tablesWithoutBizDescription, long columnsWithoutBizDescription) {
    }

    /** 모델 구성 : 스키마별 테이블 수, 컬럼 수 구간별 테이블 수, 등록된 관계가 없는 테이블 수 (오류가 아니라 참고 정보) */
    public record Model(List<SchemaTables> schemas, List<ColumnBucket> columnsPerTable, long tablesWithoutRelation) {
    }

    public record SchemaTables(Long schemaId, String schemaName, long tables) {
    }

    /** label : 0 · 1-5 · 6-10 · 11-20 · 21-50 · 51+ */
    public record ColumnBucket(String label, long tables) {
    }
}
