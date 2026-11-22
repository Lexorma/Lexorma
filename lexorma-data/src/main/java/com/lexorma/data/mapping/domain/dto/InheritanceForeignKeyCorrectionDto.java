package com.lexorma.data.mapping.domain.dto;

import java.util.List;

/**
 * 기존 서브타입 누락 상속 FK 보정 (M7-INH-03, docs/completed/inheritance-reference-key-m7.md 5장 — 확정 D-INH-07~10).
 * 정합성 비교 [상속 구조]의 매핑된 서브타입 엔터티를 골라 미리보기 → planToken → 적용으로 직계 슈퍼타입 테이블을 상속 PK 그대로 참조하는 FK 만 새로 만든다
 * (기존 테이블·컬럼·PK·FK·UNIQUE·매핑은 바꾸지 않는다). 대상 ID 공간은 서브타입 엔터티 ID 다 — 일반 FK 보정(논리 관계 ID)과 섞지 않는다.
 */
public final class InheritanceForeignKeyCorrectionDto {

    private InheritanceForeignKeyCorrectionDto() {
    }

    /** 한 번에 고를 수 있는 서브타입 수 (정합성 목록 한 쪽 최대 크기와 같다) */
    public static final int MAX_ENTITIES = 100;

    /** 만들 관계의 표현 : 단일·복합 모두 미승인 사용자 관계 (D-INH-07 — 참조 테이블명으로 만들지 않는다) */
    public static final String REPRESENTATION = "USER_RELATION";

    /** 보정할 수 없는 사유 (서버 판정 — 화면은 문구를 해석하지 않는다) */
    public enum Code {
        /** 엔터티가 서브타입이 아니다 (슈퍼타입만이거나 상속 관계가 지워졌다) */
        NOT_SUBTYPE,
        /** 서브타입이 물리 테이블에 매핑되지 않았다 */
        NOT_MAPPED,
        /** 루트에 주 식별자가 없다 */
        ROOT_KEY_MISSING,
        /** 상속 PK 매핑이 루트 주 식별자 전체를 잇지 않는다 (자동으로 추론·등록하지 않는다 — D-INH-10) */
        INHERITED_KEY_MAPPING_MISSING,
        /** 상속 PK 매핑에 다른 테이블의 컬럼이 있다 */
        INHERITED_KEY_COLUMN_INVALID,
        /** 자식 테이블 PK 구성이 상속 PK 매핑 컬럼과 다르다 */
        CHILD_KEY_MISMATCH,
        /** 상속 PK 컬럼이 NULL 을 허용한다 */
        CHILD_KEY_NULLABLE,
        /** 직계 슈퍼타입이 물리 테이블에 매핑되지 않았다 */
        PARENT_UNMAPPED,
        /** 직계 슈퍼타입 쪽 키를 확정할 수 없다 (그 PK·매핑 불일치 등 — 상위 경로의 기타 불일치) */
        ANCESTOR_INVALID,
        /** 상위 경로의 상속 FK 가 아직 없다 : 그 상위 엔터티를 먼저 보정해야 한다 (D-INH-09 — 한 요청에서 연쇄 보정하지 않는다) */
        ANCESTOR_FK_PENDING,
        /** 상속 PK 컬럼 타입이 부모 키와 FK 로 묶일 수 없다 */
        TYPE_INCOMPATIBLE,
        /** 정상 상속 FK 가 이미 있다 (진단 일치) */
        NOT_MISSING,
        /** 상속 PK 컬럼 집합 FK 가 직계 부모를 다른 부모 컬럼으로 참조한다 (기존 FK 수정 필요 — 자동으로 고치지 않는다) */
        FK_COLUMNS,
        /** 상속 PK 컬럼 집합 FK 가 같은 쌍이지만 순서가 다르다 */
        FK_ORDER,
        /** 상속 PK 컬럼 집합 FK 가 직계 부모가 아닌 테이블을 참조한다 */
        FK_TARGET,
        /** 같은 자식 컬럼 구성으로 부모 테이블을 가리키는 관계(논리 관계 포함)가 이미 있다 */
        RELATION_EXISTS,
        /** 상속 PK 컬럼에 참조 테이블명이 있다 (그 값으로 관계가 만들어지는 컬럼 — 두 곳에서 정의하지 않는다) */
        REF_COLUMN_CONFLICT,
        CHILD_TABLE_APPROVED,
        CHILD_COLUMN_APPROVED,
        NAME_CONFLICT,
        /** 그 밖의 상속 구조 판정 문제 (상세 사유 참고) */
        STRUCTURE_INVALID
    }

    public record Issue(Code code, String reason) {
    }

    /** FK 컬럼 1자리 (order 1부터 = 루트 주 식별자 순서 = 만들 FK 의 컬럼 순서) */
    public record Pair(int order, Long childColumnId, String childColumnName, String childDataType, Long parentColumnId, String parentColumnName, String parentDataType) {
    }

    /**
     * 서브타입 1개의 보정 판정. diagnosis : 상속 구조 진단 결과(MATCH·MISMATCH·UNMAPPED·NOT_COMPARABLE), parentFkDetail : 부모 FK 상세 코드(INHERITANCE_FK_*, 판정할 수 없으면 null).
     * pendingAncestor* : 먼저 보정해야 하는 상위 서브타입 (ANCESTOR_FK_PENDING 일 때). existingForeignKeys : 자식 테이블 → 부모 테이블의 기존 FK (표시용)
     */
    public record Item(Long subtypeEntityId, String subtypeEntityName, Long supertypeEntityId, String supertypeEntityName, Long groupId, String groupName,
                       Long childTableId, String childSchemaName, String childTableName, Long parentTableId, String parentSchemaName, String parentTableName,
                       String diagnosis, String parentFkDetail, String representation, String relationName, List<Pair> pairs, boolean correctable, List<Issue> blockers,
                       Long pendingAncestorEntityId, String pendingAncestorEntityName, List<String> existingForeignKeys) {
    }

    /** entityIds : 고른 서브타입 엔터티 (같은 논리 모델). planToken : 적용 때만 */
    public record Request(Long modelId, List<Long> entityIds, String planToken) {
    }

    public record Plan(Long modelId, List<Item> items, int total, int correctable, int blocked, boolean applicable, String planToken) {
    }

    /** 적용 결과 : 만든 FK(관계 ID)와 직전 모델 버전 */
    public record Result(int selected, int created, List<Long> relationIds, Long versionId, String versionName) {
    }
}
