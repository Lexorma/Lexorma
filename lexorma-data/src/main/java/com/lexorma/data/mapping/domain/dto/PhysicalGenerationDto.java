package com.lexorma.data.mapping.domain.dto;

import java.util.List;

/** 논리 → 물리 선택 생성 (DEC-10) : 고른 엔터티로 미승인 테이블·컬럼을 만들고 매핑을 잇는다. 미리보기 → 확인(planToken) → 반영 */
public final class PhysicalGenerationDto {

    private PhysicalGenerationDto() {
    }

    /**
     * schemaId 비우면 기본 스키마. planToken : 반영 때만 — 미리보기가 준 값.
     * excludedAttributeIds (M-7 보강 2차 P7) : 만들지 않을 속성 — 고른 엔터티의 속성만 (아니면 400, 중복도 400). 비었거나 없으면 모든 속성.
     * 주 식별자 속성은 제외를 요청해도 만든다 (서버가 정규화하고 경고)
     */
    public record Request(List<Long> entityIds, Long schemaId, String planToken, List<Long> excludedAttributeIds) {
        public Request(List<Long> entityIds, Long schemaId, String planToken) {
            this(entityIds, schemaId, planToken, null);
        }
    }

    /**
     * status : CREATE(만든다) · SKIP(이미 매핑된 엔터티 — 덮어쓰지 않는다) · ERROR(막는 문제 — 하나라도 있으면 반영하지 않는다)
     * · UNSUPPORTED(슈퍼타입·서브타입 구조에 참여 — 만들지 않지만 다른 엔터티의 반영은 막지 않는다, M-6 후속 5차).
     * columns 는 만들 순서 (P3 : 주 식별자 속성을 식별자 순서대로 먼저, 나머지는 속성 순서). attributes : 엔터티의 모든 속성과 제외 여부 (SKIP 이면 빈 목록)
     */
    public record TablePlan(Long entityId, String entityName, String status, String tableName, String schemaName, List<ColumnPlan> columns,
                            List<String> errors, List<String> warnings, List<AttributeChoice> attributes) {
    }

    /**
     * 속성 하나의 선택 상태 (P7). primary : 주 식별자 속성, foreignKey : 만들 FK(READY 관계)의 자식 속성 — 둘 다 제외할 수 없다
     * (요청에서 제외했으면 excludeRequested true 로 알리고 만든다). excluded : 최종으로 만들지 않는 속성
     */
    public record AttributeChoice(Long attributeId, String attributeName, boolean primary, boolean excluded, boolean excludeRequested, boolean foreignKey) {
    }

    /**
     * refTableName : 컬럼의 참조 테이블명 (FK). M-7 보강 1차부터 생성은 이 값을 채우지 않는다 (항상 null) — 논리 관계에 명시적 속성 짝이 없으면 FK 를 만들지 않는다 (P1-a, {@link RelationPlan}).
     * termApproved false 면 승인 전 용어
     */
    public record ColumnPlan(Long attributeId, String attributeName, Long termId, String columnName, String dataType, Integer maxLength, Integer precision,
                             Integer scale, boolean pk, boolean nullable, String refTableName, Boolean termApproved) {
    }

    /**
     * creatable : ERROR 가 없고 CREATE 가 하나 이상 (식별자 진단은 생성 가능 여부에 들어가지 않는다 — 심각도만 보여 준다).
     * dbType : 식별자 검사 기준인 프로젝트 DBMS, identifiers : 만들 테이블·컬럼·제약조건명과 대상 스키마의 식별자 진단, relations : 만들 엔터티(자식)의 논리 관계별 FK 생성 여부
     */
    public record Plan(List<TablePlan> tables, boolean creatable, String planToken, String dbType, List<IdentifierIssue> identifiers, List<RelationPlan> relations) {
    }

    /**
     * 식별자 진단 1건 (M-7 보강 1차). entityId·attributeId : 대상 엔터티(테이블·PK 제약조건)·속성(컬럼) — 대상 스키마 이름이면 둘 다 null.
     * code : 규칙 코드 (IDENTIFIER_LENGTH_EXCEEDED · IDENTIFIER_INVALID_CHARACTER · IDENTIFIER_NOT_RECOMMENDED · CONSTRAINT_NAME_TRUNCATED · CONSTRAINT_NAME_COLLISION),
     * severity : ERROR · WARNING, kind : SCHEMA · TABLE · COLUMN · PRIMARY_KEY, ddlName : DDL 에 실제로 나가는 이름 (잘림·충돌), related : 충돌한 다른 이름
     */
    public record IdentifierIssue(Long entityId, Long attributeId, String code, String severity, String kind, String name, String ddlName,
                                  Integer length, Integer maxLength, String basis, String message, String guide, List<String> related) {
    }

    /**
     * 논리 관계 1건의 FK 생성 계획 (원본 = 부모, 대상 = 자식, M-7 보강 4차). fkCreated = (fkStatus == READY) — 반영하면 FK 를 만든다.
     * fkStatus : READY · NO_PAIRS · PARTIAL_PAIRS · IDENTIFIER_MISMATCH · PARENT_TABLE_UNAVAILABLE · COLUMN_UNAVAILABLE · UNSUPPORTED · CONFLICT —
     * READY 가 아닌 관계는 FK 없이 만든다(경고, 생성을 막지 않는다).
     * fkType : SINGLE(일반 단일 — 자식 컬럼의 refTableName) · SELF_SINGLE · COMPOSITE · SELF_COMPOSITE (모두 TableRelation) — 판정할 수 없으면 null.
     * reasons : READY 가 아니면 첫 항목이 그 사유 (ATTRIBUTE_PAIRS_MISSING · ATTRIBUTE_PAIRS_INCOMPLETE · ATTRIBUTE_PAIRS_INVALID · PARENT_TABLE_MISSING · COLUMN_UNAVAILABLE ·
     * PARENT_KEY_NOT_TABLE_KEY · SELF_KEY_ONLY · TYPE_INCOMPATIBLE · CHILD_COLUMN_IN_USE), 그 뒤 참고 : SELF_REFERENCE · MULTIPLE_RELATIONSHIPS · COMPOSITE_PARENT_KEY · PARENT_KEY_MISSING.
     * pairStatus·pairWarnings·pairs : 논리 관계 속성 짝 판정 (P4·P5 경고 포함). parentTableId : 이미 있는(매핑된) 부모 테이블 (새로 만들면 null).
     * constraintName : DDL 의 FK 이름 (SINGLE 은 DDL 이 만드는 FK_자식_컬럼), refTableName : SINGLE 일 때 자식 컬럼에 넣을 값. columns : 짝 순서의 컬럼 쌍. approved false 면 승인 전 관계
     */
    public record RelationPlan(Long relationshipId, String relationshipName, Long parentEntityId, String parentEntityName, Long childEntityId, String childEntityName,
                               boolean fkCreated, List<String> reasons, String message, Boolean approved, String pairStatus, List<String> pairWarnings,
                               List<com.lexorma.data.logical.domain.dto.LogicalModelDto.AttributePair> pairs,
                               String fkStatus, String fkType, String parentTableName, Long parentTableId, String childTableName, String constraintName, String refTableName,
                               List<FkColumn> columns) {
    }

    /** FK 컬럼 쌍 1개 (짝 순서) : 부모 컬럼 ← 자식 컬럼 */
    public record FkColumn(int order, Long parentAttributeId, String parentColumnName, Long childAttributeId, String childColumnName) {
    }

    /** versionId·versionName : 반영 직전에 남긴 모델 버전 (P11, 이름 GENERATE-요청 ID). foreignKeys : 만든 FK 수 (단일 FK 는 참조 테이블명으로, 커밋 때 관계가 된다) */
    public record Result(int tables, int columns, List<Long> tableIds, Long versionId, String versionName, int foreignKeys) {
    }
}
