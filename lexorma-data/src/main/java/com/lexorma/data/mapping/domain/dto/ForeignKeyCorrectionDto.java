package com.lexorma.data.mapping.domain.dto;

import java.util.List;

/**
 * 기존 물리 테이블 누락 FK 보정 (M7-FK-01, docs/completed/physical-fk-correction-m7.md). 정합성 비교의 관계 진단이 FK_MISSING 인 논리 관계를 골라
 * 미리보기 → planToken → 적용으로 그 FK 만 새로 만든다 (기존 FK·컬럼·PK·UNIQUE 는 바꾸지 않는다 — M7-FK-D01·D03).
 */
public final class ForeignKeyCorrectionDto {

    private ForeignKeyCorrectionDto() {
    }

    /** 한 번에 고를 수 있는 논리 관계 수 (정합성 목록 한 쪽 최대 크기와 같다) */
    public static final int MAX_RELATIONSHIPS = 100;

    /** 만들 관계의 표현 (기존 P2 규칙) : 복합·자기 참조 = 미승인 사용자 관계, 자기 참조가 아닌 단일 = 자식 컬럼의 참조 테이블명(REF_COLUMN) */
    public enum Representation { USER_RELATION, REF_COLUMN }

    /** 보정할 수 없는 사유 */
    public enum Code {
        /** 진단이 FK 누락이 아니다 (일치·불일치·비교 불가·미매핑 — 기존 FK 는 고치지 않는다) */
        NOT_MISSING,
        /** 서브타입 → 직계 슈퍼타입 관계 : 상속 FK 는 상속 구조 진단이 다룬다 (이번 범위 밖) */
        INHERITANCE_LINK,
        /** 자기 참조가 아닌 단일 FK : 참조 테이블명 동기화(REF_COLUMN)가 관계를 항상 승인 상태로 만들어 승인 절차를 우회한다 — 정책 제한으로 보류 (M7-FK-D08) */
        SINGLE_FK_HELD,
        CHILD_TABLE_APPROVED,
        CHILD_COLUMN_APPROVED,
        /** 자식 컬럼에 참조 테이블명이 있다 (그 값으로 관계가 만들어지는 컬럼 — 두 곳에서 정의하지 않는다) */
        REF_COLUMN_CONFLICT,
        /** 같은 자식 컬럼 구성·같은 부모의 관계(논리 관계 포함)가 이미 있다 */
        RELATION_EXISTS,
        PARENT_KEY_INVALID,
        TYPE_INCOMPATIBLE,
        DUPLICATE_COLUMN,
        /** 같은 구성의 FK 를 기대하는 다른 논리 관계가 있다 — 만들면 공유 FK(FK_SHARED)가 된다 */
        SHARED_STRUCTURE,
        NAME_CONFLICT
    }

    public record Issue(Code code, String reason) {
    }

    /** FK 컬럼 1자리 (order 1부터 = 관계 속성 짝 순서 = 만들 FK 의 컬럼 순서) */
    public record Pair(int order, Long childColumnId, String childColumnName, String childDataType, Long parentColumnId, String parentColumnName, String parentDataType) {
    }

    public record Item(Long relationshipId, String relationshipName, String logicalValue, Long parentEntityId, String parentEntityName, Long childEntityId, String childEntityName,
                       Long parentTableId, String parentSchemaName, String parentTableName, Long childTableId, String childSchemaName, String childTableName,
                       String diagnosisCode, Representation representation, boolean selfReference, String relationName, boolean approved,
                       List<Pair> pairs, boolean correctable, List<Issue> blockers) {
    }

    /** relationshipIds : 고른 논리 관계 (같은 논리 모델). planToken : 적용 때만 */
    public record Request(Long modelId, List<Long> relationshipIds, String planToken) {
    }

    public record Plan(Long modelId, List<Item> items, int total, int correctable, int blocked, boolean applicable, String planToken) {
    }

    /** 적용 결과 : 만든 FK(관계 ID)와 직전 모델 버전 */
    public record Result(int selected, int created, List<Long> relationIds, Long versionId, String versionName) {
    }
}
