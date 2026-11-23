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
    public record Request(List<Long> entityIds, Long schemaId, String planToken, List<Long> excludedAttributeIds, List<StrategyChoice> strategies) {
        public Request(List<Long> entityIds, Long schemaId, String planToken) {
            this(entityIds, schemaId, planToken, null, null);
        }

        public Request(List<Long> entityIds, Long schemaId, String planToken, List<Long> excludedAttributeIds) {
            this(entityIds, schemaId, planToken, excludedAttributeIds, null);
        }
    }

    /**
     * 독립 루트 상속 계층 하나의 물리 변환 전략 (M-7 상속 A·C, D-AC-15). rootEntityId : 고른 엔터티 중 상속 계층의 루트, strategy : TABLE_PER_TYPE(B형, 기본) ·
     * SINGLE_TABLE(A형) · TABLE_PER_CONCRETE(C형). 생략한 계층은 B형 (기존 요청 그대로). 일반 엔터티·루트가 아닌 엔터티·같은 루트 두 번은 400
     */
    public record StrategyChoice(Long rootEntityId, String strategy) {
    }

    /**
     * 계층 하나의 전략 요약 (M-7 상속 A·C, 미리보기 — 실제 계획 수와 같다). status : READY(만들 수 있다) · BLOCKED(errors). missingEntities : 계층에 있지만 고르지 않은 엔터티 (D-AC-03).
     * entities : 계층의 논리 엔터티 수, tables·columns·uniques·foreignKeys : 실제로 만들 수 (이 계층 테이블의), excludedForeignKeys : 전략상 만들지 않는 FK 수 (D-AC-09·10),
     * mergedEntities (A) · distributedEntities (C) : 통합·분산 엔터티. warnings : 막지 않는 경고 코드 (메시지는 constraintWarnings)
     */
    public record StrategyPlan(Long rootEntityId, String rootEntityName, String strategy, String status, int entities, int tables, int columns, int uniques,
                               int foreignKeys, int excludedForeignKeys, List<EntityRef> missingEntities, List<EntityRef> mergedEntities, List<EntityRef> distributedEntities,
                               List<String> errors, List<String> warnings) {
    }

    public record EntityRef(Long entityId, String entityName) {
    }

    /**
     * 그룹 하나의 변환 기록 계획 (M-7 상속 A·C, D-AC-02). A형 : tableName·classificationAttribute*·classificationColumnName·values(구성원 분류 값), C형 : 모두 null.
     * 반영 때 {@code TB_SBTYP_GROUP_CNVR} 1행
     */
    public record ConversionPlan(Long groupId, String groupName, Long supertypeEntityId, String supertypeEntityName, Long rootEntityId, String strategy, String tableName,
                                 Long classificationAttributeId, String classificationAttributeName, String classificationColumnName, List<ClassificationValue> values) {
    }

    public record ClassificationValue(Long entityId, String entityName, String value) {
    }

    /**
     * status : CREATE(만든다 — 상속 구조 참여 엔터티는 "계획이 유효" 라는 뜻이고 반영은 {@link Plan#executable} 이 정한다) · SKIP(이미 매핑된 엔터티 — 덮어쓰지 않는다)
     * · MERGED(A형 — 루트 통합 테이블에 들어간다, tableName = 통합 테이블, 자기 테이블 없음) · DISTRIBUTED(C형 루트·중간 — 말단 테이블에 복제된다, 자기 테이블 없음)
     * · ERROR(막는 문제 — 하나라도 있으면 반영하지 않는다) · UNSUPPORTED(M-6 후속 5차의 상속 미지원 — M-7 2차부터 만들지 않는다, 예전 클라이언트 호환용으로 값만 남는다).
     * columns 는 만들 순서 (P3 : 주 식별자 속성을 식별자 순서대로 먼저, 나머지는 속성 순서 — 서브타입은 상속 PK 컬럼을 루트 식별자 순서대로 먼저).
     * attributes : 엔터티의 직접 속성과 제외 여부 (SKIP 이면 빈 목록). inheritance : 슈퍼타입·서브타입 구조에 참여하면 그 계획 (아니면 null, M-7 2차)
     */
    public record TablePlan(Long entityId, String entityName, String status, String tableName, String schemaName, List<ColumnPlan> columns,
                            List<String> errors, List<String> warnings, List<AttributeChoice> attributes, InheritancePlan inheritance) {
        public TablePlan(Long entityId, String entityName, String status, String tableName, String schemaName, List<ColumnPlan> columns,
                         List<String> errors, List<String> warnings, List<AttributeChoice> attributes) {
            this(entityId, entityName, status, tableName, schemaName, columns, errors, warnings, attributes, null);
        }
    }

    /**
     * 상속 구조 참여 엔터티의 물리 변환 계획 (M-7 2차, M7-D01 B형 = 엔터티마다 테이블). strategy : TABLE_PER_TYPE (저장하지 않는 계획 값, M7-D20).
     * role : ROOT(루트 슈퍼타입) · INTERMEDIATE(상위의 서브타입이면서 하위의 슈퍼타입) · SUBTYPE. level : 루트 1.
     * parent* : 직계 슈퍼타입 (상속 FK 대상, M7-D04), root* : 루트 (상속 PK = 루트 주 식별자, M7-D03), group* : 이 엔터티가 구성원인 그룹 (루트는 null).
     * reasons : 상속 계획을 막는 사유 코드 (ROOT_KEY_MISSING · ANCESTOR_MISSING · ROOT_KEY_MAPPING_MISSING · PARENT_KEY_MISMATCH · INHERITED_KEY_MAPPING_MISSING ·
     * INHERITED_KEY_TYPE_MISMATCH · INHERITANCE_FK_MISSING — 기존 조상, 3차 · 상속 FK 를 만들 수 없음 : COLUMN_UNAVAILABLE · TYPE_INCOMPATIBLE, M7-D42) — 메시지는 TablePlan.errors
     */
    public record InheritancePlan(String strategy, String role, int level, Long parentEntityId, String parentEntityName, Long rootEntityId, String rootEntityName,
                                  Long groupId, String groupName, List<String> reasons, String physicalRole, List<EntityRef> tableEntities) {
        public InheritancePlan(String strategy, String role, int level, Long parentEntityId, String parentEntityName, Long rootEntityId, String rootEntityName,
                               Long groupId, String groupName, List<String> reasons) {
            this(strategy, role, level, parentEntityId, parentEntityName, rootEntityId, rootEntityName, groupId, groupName, reasons, "DIRECT", List.of());
        }
    }

    /**
     * 속성 하나의 선택 상태 (P7). primary : 주 식별자 속성, foreignKey : 만들 FK(READY 관계)의 자식 속성 — 둘 다 제외할 수 없다
     * (요청에서 제외했으면 excludeRequested true 로 알리고 만든다). excluded : 최종으로 만들지 않는 속성
     */
    public record AttributeChoice(Long attributeId, String attributeName, boolean primary, boolean excluded, boolean excludeRequested, boolean foreignKey) {
    }

    /**
     * refTableName : 컬럼의 참조 테이블명 (단일 FK — 짝이 완성된 관계(4차) 또는 단일 상속 PK 의 상속 FK(M-7 2차)). termApproved false 면 승인 전 용어.
     * inheritedFromEntityId : 상속 PK 컬럼이면 그 주 식별자를 정의한 루트 엔터티 (attributeId 는 루트의 원본 속성 ID — 논리 속성을 복제하지 않는다, M7-D03·D15), 직접 속성이면 null.
     * mappingKind (M-7 상속 A·C) : DIRECT(이 테이블 엔터티의 직접 속성 — 속성 컬럼 매핑) · MERGED(A형 — 같은 계층 다른 엔터티의 직접 속성, 루트 매핑 아래 속성 컬럼 매핑) ·
     * INHERITED_KEY(상속 PK — 서브타입 식별자 컬럼 매핑) · INHERITED_ATTRIBUTE(C형 — 조상 일반 속성 복제, 서브타입 속성 컬럼 매핑). sourceEntityId·sourceEntityName : 속성을 정의한 엔터티.
     * logicalRequired : 논리 필수 여부 (A형 서브타입 필수 속성은 nullable true 로 만든다 — D-AC-05, 차이는 경고), classification : A형 분류 컬럼
     */
    public record ColumnPlan(Long attributeId, String attributeName, Long termId, String columnName, String dataType, Integer maxLength, Integer precision,
                             Integer scale, boolean pk, boolean nullable, String refTableName, Boolean termApproved, Long inheritedFromEntityId,
                             String mappingKind, Long sourceEntityId, String sourceEntityName, boolean logicalRequired, boolean classification) {
        public ColumnPlan(Long attributeId, String attributeName, Long termId, String columnName, String dataType, Integer maxLength, Integer precision,
                          Integer scale, boolean pk, boolean nullable, String refTableName, Boolean termApproved) {
            this(attributeId, attributeName, termId, columnName, dataType, maxLength, precision, scale, pk, nullable, refTableName, termApproved, null);
        }

        public ColumnPlan(Long attributeId, String attributeName, Long termId, String columnName, String dataType, Integer maxLength, Integer precision,
                          Integer scale, boolean pk, boolean nullable, String refTableName, Boolean termApproved, Long inheritedFromEntityId) {
            this(attributeId, attributeName, termId, columnName, dataType, maxLength, precision, scale, pk, nullable, refTableName, termApproved, inheritedFromEntityId,
                    inheritedFromEntityId == null ? "DIRECT" : "INHERITED_KEY", null, null, !nullable, false);
        }
    }

    /**
     * creatable : ERROR 가 없고 CREATE 가 하나 이상 (식별자 진단은 생성 가능 여부에 들어가지 않는다 — 심각도만 보여 준다) — 계획이 유효하다는 뜻.
     * executable : 지금 반영할 수 있는지 (M-7 2차에 추가 — 2차에는 상속 구조 참여 엔터티를 만드는 계획을 INHERITANCE_GENERATION_NOT_AVAILABLE 로 막았다).
     * 3차부터 상속 생성도 반영하므로 creatable 과 같다 (executionBlockedReason null). 필수 상속 FK 를 만들 수 없는 서브타입은 ERROR 라 creatable 이 아니다 (M7-D42).
     * inheritanceForeignKeys·requiredAncestors·constraintWarnings : 상속 계획 (상속 참여 엔터티가 없으면 빈 목록).
     * dbType : 식별자 검사 기준인 프로젝트 DBMS, identifiers : 만들 테이블·컬럼·제약조건명과 대상 스키마의 식별자 진단, relations : 만들 엔터티(자식)의 논리 관계별 FK 생성 여부
     */
    public record Plan(List<TablePlan> tables, boolean creatable, String planToken, String dbType, List<IdentifierIssue> identifiers, List<RelationPlan> relations,
                       boolean executable, String executionBlockedReason, List<InheritanceForeignKeyPlan> inheritanceForeignKeys,
                       List<RequiredAncestor> requiredAncestors, List<ConstraintWarning> constraintWarnings, List<AncestorUse> ancestors, List<UniquePlan> uniques,
                       List<StrategyPlan> strategies, List<ConversionPlan> conversions) {
        public Plan(List<TablePlan> tables, boolean creatable, String planToken, String dbType, List<IdentifierIssue> identifiers, List<RelationPlan> relations,
                    boolean executable, String executionBlockedReason, List<InheritanceForeignKeyPlan> inheritanceForeignKeys,
                    List<RequiredAncestor> requiredAncestors, List<ConstraintWarning> constraintWarnings, List<AncestorUse> ancestors, List<UniquePlan> uniques) {
            this(tables, creatable, planToken, dbType, identifiers, relations, executable, executionBlockedReason, inheritanceForeignKeys, requiredAncestors, constraintWarnings,
                    ancestors, uniques, List.of(), List.of());
        }
    }

    /**
     * 보조 식별자 → UNIQUE 계획 (M7-AK-01, docs/completed/alternate-key-unique-m7.md) : 새로 만드는 테이블의 보조 식별자마다 1행 (식별자 ID 순).
     * status : CREATE(명시적 UNIQUE 제약조건 — 단일·복합 모두, 식별자 순서, 미승인) · SKIP(만들지 않음 — 사유 PRIMARY_KEY_DUPLICATE · DUPLICATE_ALTERNATE_KEY · COLUMN_UNAVAILABLE).
     * duplicateOfIdentifierId : 같은 컬럼 집합의 대표 식별자 (식별자 ID 가 가장 작은 것). warnings : NULLABLE_COLUMNS (막지 않는다 — M7-AK-D03)
     */
    public record UniquePlan(Long entityId, String entityName, Long identifierId, String identifierName, String tableName, String constraintName, List<String> columns,
                             String status, String reason, Long duplicateOfIdentifierId, String duplicateOfIdentifierName, List<String> warnings, String message) {
    }

    /**
     * 고른 서브타입의 조상 하나가 이 계획에서 어떻게 쓰이는가 (M-7 4차 — 필요한 조상 함께 선택 화면). 조상마다 1건, requiredBy = 그 조상이 필요한 고른 엔터티.
     * state : SELECTED(이번 계획에서 만든다) · REUSE(유효한 기존 매핑 테이블을 재사용) · REVIEW(매핑은 있지만 구조가 맞지 않음 — 함께 선택해도 해결되지 않는다, reason = 판정 사유,
     * mappingId = 검토할 엔터티 매핑) · MISSING(매핑·선택 모두 없음 — requiredAncestors 와 같다). 계획 토큰에는 이미 조상 근거가 들어 있어 따로 넣지 않는다
     */
    public record AncestorUse(Long entityId, String entityName, String state, String reason, Long mappingId, String tableName, List<Long> requiredBy) {
    }

    /**
     * 상속 FK 1건 (M-7 2차) : 서브타입 테이블의 상속 PK → 직계 슈퍼타입 테이블 PK 전체 (M7-D04, 복합이면 FK 하나). 논리 관계에서 온 FK({@link RelationPlan})와 따로 둔다 (M7-D21 : 출처를 저장하지 않는 계획 값).
     * status : READY · BLOCKED (사유는 reason : ANCESTOR_MISSING · ROOT_KEY_MISSING · ROOT_KEY_MAPPING_MISSING · PARENT_KEY_MISMATCH · INHERITED_KEY_MAPPING_MISSING · COLUMN_UNAVAILABLE · TYPE_INCOMPATIBLE).
     * fkType : SINGLE(자식 상속 PK 컬럼의 refTableName) · COMPOSITE(TableRelation). parentTableId : 이미 있는(매핑된) 부모 테이블 (새로 만들면 null). columns : 루트 주 식별자 순서
     */
    public record InheritanceForeignKeyPlan(Long childEntityId, String childEntityName, Long parentEntityId, String parentEntityName, Long groupId, String status,
                                            String reason, String fkType, String parentTableName, Long parentTableId, String childTableName, String constraintName,
                                            String refTableName, List<FkColumn> columns) {
    }

    /** 서브타입을 만들려면 필요한데 이번 계획에도 없고 유효한 물리 매핑도 없는 조상 (M7-D35 — 서버가 자동으로 넣지 않는다). requiredBy : 이 조상이 필요한 고른 엔터티 (ID 순) */
    public record RequiredAncestor(Long entityId, String entityName, String reason, List<Long> requiredBy) {
    }

    /**
     * 논리 상속 제약이 만들 물리 구조(PK·FK)로 보장되지 않음 (M7-D09·D11·D37) — 경고이며 생성을 막지 않는다. 그룹·제약마다 1건 (구성원 수만큼 늘지 않는다).
     * constraint : DISJOINT(배타 — 같은 키가 여러 서브타입 테이블에 들어갈 수 있다) · TOTAL(완전 — 서브타입 행이 없는 슈퍼타입 행이 있을 수 있다), guarantee : NOT_ENFORCED.
     * Overlapping·Partial 은 PK·FK 가 허용하는 참여 방식 그대로라 경고하지 않는다
     */
    public record ConstraintWarning(Long groupId, String groupName, Long supertypeEntityId, String supertypeEntityName, String constraint, String guarantee, String message) {
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
     * constraintName : DDL 의 FK 이름 (SINGLE 은 DDL 이 만드는 FK_자식_컬럼), refTableName : SINGLE 일 때 자식 컬럼에 넣을 값. columns : 짝 순서의 컬럼 쌍. approved false 면 승인 전 관계.
     * keyType : 짝이 가리키는 부모 식별자 PRIMARY · ALTERNATE (M7-AK-D09). ALTERNATE 는 단일이어도 참조 테이블명이 아니라 미승인 사용자 관계(TableRelation)로 만든다 (M7-AK-D11 — 부모 PK 가 아닌
     * UNIQUE 를 참조하므로 REF_COLUMN 으로 표현할 수 없다), 추가 사유 : PARENT_UNIQUE_MISSING(기존 부모에 그 순서의 UNIQUE 없음) · UNIQUE_ORDER_DIFFERS(짝 순서 ≠ UNIQUE 순서)
     */
    public record RelationPlan(Long relationshipId, String relationshipName, Long parentEntityId, String parentEntityName, Long childEntityId, String childEntityName,
                               boolean fkCreated, List<String> reasons, String message, Boolean approved, String pairStatus, List<String> pairWarnings,
                               List<com.lexorma.data.logical.domain.dto.LogicalModelDto.AttributePair> pairs,
                               String fkStatus, String fkType, String parentTableName, Long parentTableId, String childTableName, String constraintName, String refTableName,
                               List<FkColumn> columns, String keyType, String keyIdentifierName) {
    }

    /** FK 컬럼 쌍 1개 (짝 순서) : 부모 컬럼 ← 자식 컬럼 */
    public record FkColumn(int order, Long parentAttributeId, String parentColumnName, Long childAttributeId, String childColumnName) {
    }

    /**
     * versionId·versionName : 반영 직전에 남긴 모델 버전 (P11, 이름 GENERATE-요청 ID). foreignKeys : 만든 논리 관계 FK 수 (단일 FK 는 참조 테이블명으로 — 반영 안에서 관계로 맞춘다).
     * inheritanceForeignKeys : 만든 상속 FK 수, inheritedKeyMappings : 저장한 상속 PK 매핑 수 (M-7 3차)
     */
    public record Result(int tables, int columns, List<Long> tableIds, Long versionId, String versionName, int foreignKeys, int inheritanceForeignKeys, int inheritedKeyMappings,
                         int uniques, int inheritedAttributeMappings, int conversions, List<Long> excludedRelationshipIds) {
        public Result(int tables, int columns, List<Long> tableIds, Long versionId, String versionName, int foreignKeys) {
            this(tables, columns, tableIds, versionId, versionName, foreignKeys, 0, 0, 0, 0, 0, List.of());
        }

        public Result(int tables, int columns, List<Long> tableIds, Long versionId, String versionName, int foreignKeys, int inheritanceForeignKeys, int inheritedKeyMappings,
                      int uniques) {
            this(tables, columns, tableIds, versionId, versionName, foreignKeys, inheritanceForeignKeys, inheritedKeyMappings, uniques, 0, 0, List.of());
        }
    }
}
