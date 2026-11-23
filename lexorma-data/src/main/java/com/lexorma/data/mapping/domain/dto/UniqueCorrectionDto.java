package com.lexorma.data.mapping.domain.dto;

import java.util.List;

/**
 * 기존 물리 테이블 누락 UNIQUE 보정 (M7-AK-02, docs/completed/alternate-key-unique-m7.md). 정합성 비교의 보조 식별자 진단이 UNIQUE_MISSING 인 보조 식별자를 골라
 * 미리보기 → planToken → 적용으로 명시적 UNIQUE 제약조건만 새로 만든다 (기존 UNIQUE·고유 인덱스·컬럼 고유값·필수 여부는 바꾸지 않는다 — M7-AK-D07).
 */
public final class UniqueCorrectionDto {

    private UniqueCorrectionDto() {
    }

    /** 한 번에 고를 수 있는 보조 식별자 수 (정합성 목록 한 쪽 최대 크기와 같다 — M7-AK-D14) */
    public static final int MAX_IDENTIFIERS = 100;

    /** 보정할 수 없는 사유 (nullable 구성은 사유가 아니라 경고 NULLABLE_COLUMNS — M7-AK-D03) */
    public enum Code {
        /** 진단이 UNIQUE 누락이 아니다 (일치·순서·구성 불일치·비교 불가·미매핑 — 기존 UNIQUE 는 고치지 않는다) */
        NOT_MISSING,
        /** 상속 A·C형 계층에 영향을 주는 대상 (M-7 상속 A·C, D-AC-13 16.4) : 통합·분산·복제 구조를 이 보정이 다루지 않는다 */
        STRATEGY_EXCLUDED,
        /** 엔터티·구성 속성의 물리 매핑이 없다 */
        MAPPING_MISSING,
        /** 구성 컬럼이 매핑 테이블의 컬럼이 아니거나 같은 컬럼이 두 번 쓰였다 */
        COLUMN_INVALID,
        PRIMARY_KEY_DUPLICATE,
        /** 같은 컬럼 집합(순서 무시)의 UNIQUE 제약조건이 이미 있다 */
        UNIQUE_DUPLICATE,
        /** 같은 컬럼 집합의 고유 인덱스가 이미 있다 (진단은 고유 인덱스를 UNIQUE 로 보지 않지만 같은 유일성을 두 번 만들지 않는다) */
        UNIQUE_INDEX_DUPLICATE,
        /** 단일 구성 컬럼이 이미 고유값(UNQ_YN) 이다 */
        COLUMN_UNIQUE_DUPLICATE,
        /** 같은 엔터티에 같은 컬럼 집합의 보조 식별자가 있고 이 식별자가 대표(식별자 ID 가 가장 작은 것)가 아니다 (M7-AK-D05) */
        DUPLICATE_ALTERNATE_KEY,
        /** 만들 UNIQUE 이름이 같은 DBMS 이름 공간의 다른 제약조건·인덱스와 같다 (같은 요청 안 포함) */
        NAME_CONFLICT,
        APPROVED_TABLE,
        APPROVED_COLUMN
    }

    public record Issue(Code code, String reason) {
    }

    /** UNIQUE 구성 컬럼 1자리 (order 1부터 = 식별자 속성 순서 = 만들 UNIQUE 의 컬럼 순서) */
    public record Column(int order, Long attributeId, String attributeName, Long columnId, String columnName, String dataType, Boolean nullable, Boolean approved) {
    }

    public record Item(Long identifierId, String identifierName, String logicalValue, Long entityId, String entityName, Long tableId, String schemaName, String tableName,
                       String diagnosisCode, String constraintName, boolean approved, List<Column> columns, List<String> warnings, boolean correctable, List<Issue> blockers,
                       Long duplicateOfIdentifierId, String duplicateOfIdentifierName) {
    }

    /** identifierIds : 고른 보조 식별자 (같은 논리 모델). planToken : 적용 때만 */
    public record Request(Long modelId, List<Long> identifierIds, String planToken) {
    }

    public record Plan(Long modelId, List<Item> items, int total, int correctable, int blocked, boolean applicable, String planToken) {
    }

    /** 적용 결과 : 만든 UNIQUE 제약조건과 직전 모델 버전 */
    public record Result(int selected, int created, List<Long> constraintIds, Long versionId, String versionName) {
    }
}
