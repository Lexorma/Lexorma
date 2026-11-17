package com.lexorma.data.physical.diagnostic;

import java.util.List;

/** 물리 모델 구조 진단 (DEC-09 무효 FK · DEC-12 중복 인덱스, 읽기 전용). 조회할 때 계산하고 저장하지 않으며 모델 수정을 막지 않는다 */
public final class ModelStructureDiagnosticDto {

    private ModelStructureDiagnosticDto() {
    }

    public enum Severity { ERROR, WARNING }

    public enum IssueType {
        /** 컬럼의 참조 테이블명을 등록된 테이블로 정하지 못했다 (없음) — DDL 에서 FK 를 만들지 않는다 */
        FK_TARGET_NOT_FOUND,
        /** 참조 테이블명이 여러 스키마의 테이블과 맞는다 — 스키마명.테이블명으로 지정해야 한다 */
        FK_TARGET_AMBIGUOUS,
        /** 참조 테이블에 PK 가 없다 */
        FK_TARGET_NO_PK,
        /** 참조 테이블의 PK 가 복합키라 컬럼 하나로 참조할 수 없다 */
        FK_TARGET_COMPOSITE_PK,
        /** 사용자 정의 관계의 대상 컬럼이 대상 테이블의 PK·고유 키가 아니다 (등록 뒤에 대상 키가 바뀌었다) */
        FK_RELATION_NOT_KEY,
        /** 같은 테이블에 키·INCLUDE·조건이 같은 인덱스가 둘 이상 */
        INDEX_SAME_DEFINITION,
        /** 일반(비고유) 인덱스의 키가 PK 와 같은 순서의 같은 컬럼 — PK 인덱스가 이미 같은 일을 한다 (DEC-12 : 허용하되 경고) */
        INDEX_SAME_AS_PK,
        /** 일반 인덱스의 키가 다른 인덱스(또는 PK)의 앞부분과 같다 — 앞부분 검색은 긴 쪽이 대신한다 */
        INDEX_PREFIX_OF_OTHER
    }

    /** objectName : 컬럼명·관계명·인덱스명. related : 비교 대상(참조 테이블명, 같은 정의·포함하는 인덱스명 또는 PK) */
    public record Issue(String id, IssueType type, Severity severity, Long schemaId, String schemaName, Long tableId, String tableName,
                        String objectName, String related, Boolean isApproval) {
    }

    public record Summary(long tables, long errors, long warnings) {
    }

    public record Result(Summary summary, List<Issue> issues) {
    }
}
