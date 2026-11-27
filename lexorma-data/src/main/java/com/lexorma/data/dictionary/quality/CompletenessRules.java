package com.lexorma.data.dictionary.quality;

import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.CompletenessColumn;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.CompletenessTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 완전성 규칙 4종 (AI-6 3차, AI6-D04 · AI6-3-12·13 — 순수 함수, DB 를 읽지 않는다). 설명의 뜻·길이·문법은 보지 않고 <b>값이 있는지</b>만 본다.
 * <ul>
 * <li>{@link StandardizationRule#TABLE_COMMENT_MISSING} : 테이블 코멘트(TBL_KORN_NM — 화면 이름 '테이블 코멘트', 대시보드 '코멘트 없는 테이블' 과 같은 칸)가 없다.
 *     DDL 생성기는 이 칸이 비면 테이블 설명(TBL_EXPLN)을 COMMENT 로 대신 쓰지만, 이 규칙은 그 대체를 보지 않는다 (화면·대시보드와 같은 칸 하나만 본다)</li>
 * <li>{@link StandardizationRule#COLUMN_COMMENT_MISSING} : 컬럼 설명(COL_EXPLN — DDL 의 컬럼 COMMENT)이 없다</li>
 * <li>{@link StandardizationRule#TABLE_BIZ_DESCRIPTION_MISSING} · {@link StandardizationRule#COLUMN_BIZ_DESCRIPTION_MISSING} : 업무 설명(TASK_EXPLN)이 없다.
 *     코멘트와 업무 설명은 다른 항목이라 따로 판정한다</li>
 * </ul>
 * 누락 = null · 빈 문자열 · ASCII 공백류(U+0020·탭·LF·VT·FF·CR)만 있는 값 — 기존 빈 이름 규칙 {@code ColumnNameKey.isBlankName}(DB {@code CK_COL_01} 과 같다)을
 * 그대로 쓴다. 같은 기준의 SQL 은 {@link CompletenessSql} (대시보드 실시간 수치). NBSP·전각 공백만 있는 값은 내용이 있는 것으로 본다 (기존 규칙 그대로). 대상 범위 = 모델 진단과 같다 (프로젝트의 물리 테이블·컬럼 전체, VIEW 제외, 승인 상태 무관).
 * fingerprint 는 대상 식별 (대상 ID, 핵심 값 없음 — FindingFingerprint.of) — 이름·문구가 바뀌어도 같은 문제, 같은 대상의 코멘트 누락과 업무 설명 누락은 규칙이 달라 다른 문제다.
 * 근거에는 누락 항목만 두고 설명 원문은 넣지 않는다.
 */
public final class CompletenessRules {

    /** 누락된 항목 (근거 evidence.missing) */
    public static final String COMMENT = "COMMENT";
    public static final String BIZ_DESCRIPTION = "BIZ_DESCRIPTION";

    private CompletenessRules() {
    }

    /** 누락 : null · 빈 문자열 · 공백 문자만 */
    public static boolean missing(String value) {
        return com.lexorma.data.column.service.ColumnNameKey.isBlankName(value);
    }

    public static List<InspectionItem> diagnose(long projectId, List<CompletenessTable> tables, List<CompletenessColumn> columns) {
        List<InspectionItem> items = new ArrayList<>();
        diagnose(projectId, tables, columns, items::add);
        return items;
    }

    /** 같은 판정, 결과를 하나씩 넘긴다 (저장 검사가 결과를 모아 두지 않고 곧바로 저장 형태로 바꾼다 — AI-6 FINAL 메모리 개선) */
    public static void diagnose(long projectId, List<CompletenessTable> tables, List<CompletenessColumn> columns, java.util.function.Consumer<InspectionItem> items) {
        for (CompletenessTable table : tables) {
            if (missing(table.tableKorName())) {
                items.accept(item(projectId, StandardizationRule.TABLE_COMMENT_MISSING, COMMENT, InspectionItem.ObjectType.TABLE, table.tableId(), table.tableId(),
                        table.schemaName(), table.tableName(), null));
            }
            if (missing(table.bizDescription())) {
                items.accept(item(projectId, StandardizationRule.TABLE_BIZ_DESCRIPTION_MISSING, BIZ_DESCRIPTION, InspectionItem.ObjectType.TABLE, table.tableId(),
                        table.tableId(), table.schemaName(), table.tableName(), null));
            }
        }
        for (CompletenessColumn column : columns) {
            if (missing(column.columnDesc())) {
                items.accept(item(projectId, StandardizationRule.COLUMN_COMMENT_MISSING, COMMENT, InspectionItem.ObjectType.COLUMN, column.columnId(), column.tableId(),
                        column.schemaName(), column.tableName(), column.columnName()));
            }
            if (missing(column.bizDescription())) {
                items.accept(item(projectId, StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING, BIZ_DESCRIPTION, InspectionItem.ObjectType.COLUMN, column.columnId(),
                        column.tableId(), column.schemaName(), column.tableName(), column.columnName()));
            }
        }
    }

    private static InspectionItem item(long projectId, StandardizationRule rule, String missing, InspectionItem.ObjectType type, long targetId, Long tableId,
                                       String schemaName, String tableName, String columnName) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("missing", missing);
        Finding finding = new Finding(rule, List.of(targetId), null, null, evidence, FindingFingerprint.of(projectId, rule, List.of(targetId), List.of()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schemaName", schemaName);
        details.put("tableName", tableName);
        if (columnName != null) details.put("columnName", columnName);
        String name = columnName == null ? join(schemaName, tableName, null) : join(schemaName, tableName, columnName);
        return new InspectionItem(finding, type, targetId, tableId, name, details);
    }

    private static String join(String schema, String table, String column) {
        StringBuilder name = new StringBuilder();
        for (String part : new String[]{schema, table, column}) {
            if (part == null || part.isBlank()) continue;
            if (!name.isEmpty()) name.append('.');
            name.append(part);
        }
        return name.isEmpty() ? "-" : name.toString();
    }
}
