package com.lexorma.data.sourcemapping.compare;

import java.math.BigDecimal;
import java.util.List;

/**
 * 비교에 쓰는 물리 모델의 값 (M-4d). 엔티티가 아니라 한 번의 조회(스칼라 JPQL)로 읽은 값이다 : 연관 엔티티를 하나씩 읽지 않는다 (N+1 없음).
 * 원본 기준 속성(정책 5.5 필드 소유권)만 담는다 — 논리명·업무 설명·표준 용어·승인 상태 같은 사용자 관리 속성은 비교하지 않는다.
 */
public final class CompareModel {

    private CompareModel() {
    }

    /**
     * @param sourceComment 원본 COMMENT 칸 (가져온 값, V3 SRC_ANTN_CN — 가져오기 대상) / description : 업무 설명(COL_EXPLN, DDL 의 COMMENT) — 원본 COMMENT 와 비교한다
     * @param approved      승인 여부 (가져오기는 승인된 객체를 바꾸지 않는다)
     */
    public record Column(Long id, Long tableId, String name, String dataType, Integer maxLength, BigDecimal precision, Integer scale, Boolean nullable,
                         Boolean required, Boolean pk, Boolean unique, String defaultValue, Integer sortOrder, String sourceComment, String description, boolean approved,
                         String identity) {
    }

    /** UNIQUE(복합 포함)·CHECK (PK 는 컬럼의 pk, FK 는 Relation) */
    public record Constraint(Long id, Long tableId, String name, String type, String checkExpression, List<Long> columnIds) {
    }

    /** @param sort 저장 표기 (null = 기본, DESC, DESC NULLS LAST, NULLS FIRST, INCLUDE) — 원본 수집기와 같은 표기다 */
    public record IndexMember(Long columnId, String expression, String sort) {
    }

    public record Index(Long id, Long tableId, String name, String indexType, boolean unique, String condition, List<IndexMember> members) {
    }

    /** FK 관계만 (LOGICAL 관계는 DDL 제약이 아니라 비교하지 않는다). source : USER · REF_COLUMN · IMPORTED · AI_APPROVED */
    /** deleteRule · updateRule : SQL 표기 (NO ACTION · RESTRICT · CASCADE · SET NULL · SET DEFAULT) */
    public record Relation(Long id, Long tableId, Long targetTableId, String name, String source, List<Long> columnIds, List<Long> targetColumnIds,
                           String deleteRule, String updateRule) {
    }

    /** @param sourceComment 원본 COMMENT 칸 (SRC_ANTN_CN) / korName : 한글명 (TBL_KORN_NM, DDL 의 TABLE COMMENT) — 원본 TABLE COMMENT 와 비교한다 */
    public record Table(Long id, String name, String sourceComment, String korName, boolean approved, List<Column> columns, List<Constraint> constraints, List<Index> indexes,
                        List<Relation> relations) {

        /** 아직 없는 모델 TABLE (가져오기로 새로 만들 때 원본을 이것과 비교하면 모든 객체가 원본에만 있다) */
        public static Table empty(String name) {
            return new Table(null, name, null, null, false, List.of(), List.of(), List.of(), List.of());
        }
    }
}
