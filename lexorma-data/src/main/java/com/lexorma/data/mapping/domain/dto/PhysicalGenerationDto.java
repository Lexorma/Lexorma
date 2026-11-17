package com.lexorma.data.mapping.domain.dto;

import java.util.List;

/** 논리 → 물리 선택 생성 (DEC-10) : 고른 엔터티로 미승인 테이블·컬럼을 만들고 매핑을 잇는다. 미리보기 → 확인(planToken) → 반영 */
public final class PhysicalGenerationDto {

    private PhysicalGenerationDto() {
    }

    /** schemaId 비우면 기본 스키마. planToken : 반영 때만 — 미리보기가 준 값 */
    public record Request(List<Long> entityIds, Long schemaId, String planToken) {
    }

    /** status : CREATE(만든다) · SKIP(이미 매핑된 엔터티 — 덮어쓰지 않는다) · ERROR(막는 문제 — 하나라도 있으면 반영하지 않는다) */
    public record TablePlan(Long entityId, String entityName, String status, String tableName, String schemaName, List<ColumnPlan> columns,
                            List<String> errors, List<String> warnings) {
    }

    /** refTableName : 논리 관계로 정한 참조 테이블 (FK). termApproved false 면 승인 전 용어 */
    public record ColumnPlan(Long attributeId, String attributeName, Long termId, String columnName, String dataType, Integer maxLength, Integer precision,
                             Integer scale, boolean pk, boolean nullable, String refTableName, Boolean termApproved) {
    }

    /** creatable : ERROR 가 없고 CREATE 가 하나 이상 */
    public record Plan(List<TablePlan> tables, boolean creatable, String planToken) {
    }

    public record Result(int tables, int columns, List<Long> tableIds) {
    }
}
