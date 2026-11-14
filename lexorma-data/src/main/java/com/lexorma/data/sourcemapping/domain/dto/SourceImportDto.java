package com.lexorma.data.sourcemapping.domain.dto;

import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectKind;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 원본 메타데이터 선택 가져오기 (M-4e, docs/source-import-m4e.md). 흐름 : 대상 조회(candidates) → 계획(plan, 읽기 전용) → 확정(apply, 다시 계획해 같을 때만 반영).
 * 항목 ID 는 사용자가 고르는 단위다 :
 * <ul>
 *   <li>{@code TABLE:CREATE} (원본 TABLE 로 새 모델 TABLE + 매핑 확정), {@code TABLE:COMMENT}</li>
 *   <li>{@code COLUMN:<원본 컬럼>:ADD}, {@code COLUMN:<원본 컬럼>:TYPE|NULLABLE|DEFAULT|COMMENT} (TYPE = 타입·길이·정밀도·소수)</li>
 *   <li>{@code PRIMARY_KEY:<이름>}, {@code UNIQUE:<이름>}, {@code CHECK:<이름>}, {@code FOREIGN_KEY:<이름>}, {@code INDEX:<이름>} (추가만)</li>
 * </ul>
 * 고르지 않은 속성은 바꾸지 않는다. 삭제·이름 변경·기존 객체 교체는 없다.
 */
public final class SourceImportDto {

    private SourceImportDto() {
    }

    public enum Action { CREATE, ADD, UPDATE }

    /**
     * 가져올 수 없는 이유 (화면이 문구로 바꾼다).
     * <ul>
     *   <li>매핑 : NOT_MAPPED(유효한 매핑 아님) · MAPPING_CANDIDATE · SCHEMA_UNMAPPED · NAME_TAKEN · VIEW_NAME_TAKEN · RECHECK · MAPPING_CONFLICT · SOURCE_REMOVED · MODEL_DELETED</li>
     *   <li>비교 : SAME(차이 없음) · UNKNOWN(판단 불가) · RENAME_CANDIDATE · CONFLICT · REPLACE_REQUIRED(기존 객체를 지우고 바꿔야 함) · MODEL_ONLY(모델에만 있음 — 지우지 않는다)</li>
     *   <li>모델 : UNSUPPORTED_ATTRIBUTE(모델이 표현하지 못하는 원본 속성이 있어 빼고 만들면 의미가 달라진다 — excluded 에 그 속성) · APPROVED(승인된 객체 — 승인 취소 후) · TYPE_NOT_REPRESENTABLE · DEFAULT_NOT_REPRESENTABLE · INVALID_NAME · QUOTED_NAME · PK_NOT_NULL ·
     *       PK_EXISTS · PK_ORDER · ORDER_NOT_SUPPORTED · NO_FIELD(모델에 칸 없음) · EMPTY_COMMENT(원본 COMMENT 가 없어 지우지 않는다) · UNSUPPORTED_INDEX_TYPE</li>
     *   <li>의존 : MISSING_DEPENDENCY(선행 항목을 함께 고르세요) · UNRESOLVED_TARGET(FK 참조 대상 TABLE 이 매핑·선택되지 않음) · TARGET_NOT_KEY(참조 컬럼이 PK·UNIQUE 가 아님)</li>
     *   <li>요청 : NOT_FOUND(지금 비교에 없는 항목) · UNSUPPORTED_DBMS</li>
     * </ul>
     */
    public enum Reason {
        NOT_MAPPED, MAPPING_CANDIDATE, SCHEMA_UNMAPPED, NAME_TAKEN, VIEW_NAME_TAKEN, RECHECK, MAPPING_CONFLICT, SOURCE_REMOVED, MODEL_DELETED,
        SAME, UNKNOWN, RENAME_CANDIDATE, CONFLICT, REPLACE_REQUIRED, MODEL_ONLY,
        UNSUPPORTED_ATTRIBUTE, APPROVED, TYPE_NOT_REPRESENTABLE, DEFAULT_NOT_REPRESENTABLE, INVALID_NAME, QUOTED_NAME, PK_NOT_NULL, PK_EXISTS, PK_ORDER, ORDER_NOT_SUPPORTED, NO_FIELD,
        EMPTY_COMMENT, UNSUPPORTED_INDEX_TYPE,
        MISSING_DEPENDENCY, UNRESOLVED_TARGET, TARGET_NOT_KEY,
        NOT_FOUND, UNSUPPORTED_DBMS
    }

    /**
     * 가져올 수 있는 항목 하나 (또는 가져올 수 없는 이유).
     *
     * @param before   지금 모델 값 (없으면 null)
     * @param after    적용 후 모델 값
     * @param source   원본 값
     * @param excluded 모델이 표현하지 못하는 원본 속성 (예 : IDENTITY, ON DELETE CASCADE). 있으면 그 객체는 만들지 않는다 (UNSUPPORTED_ATTRIBUTE)
     * @param dependsOn 함께 골라야 하는 항목 (모델에 아직 없는 컬럼·참조 TABLE 등)
     */
    public record Item(String id, ObjectKind kind, String sourceName, String modelName, Action action, String attribute, String source, String before, String after,
                       boolean selectable, Reason reason, List<String> excluded, List<String> dependsOn) {
    }

    /** 원본 TABLE 하나의 가져오기 대상 */
    public record TableCandidates(Long connectionId, Integer reflectedOrder, SourceCompareDto.TableRow table, String modelSchemaName, String modelTableName,
                                  boolean create, Reason tableReason, List<Item> items) {
    }

    /**
     * @param key       비교 목록의 key (S + 원본 TABLE ID)
     * @param tableName 새로 만들 때의 모델 TABLE 이름 (비우면 원본 이름에서 정한다)
     * @param items     고른 항목 ID
     */
    public record TableSelection(String key, String tableName, List<String> items) {
    }

    /**
     * @param connectionId·reflectedOrder 비교 기준 (요약에서 받은 값). 다르면 SOURCE_COMPARE_STALE
     * @param planToken·requestId        확정 때만 : 계획 응답의 값 그대로
     */
    public record Request(Long connectionId, Integer reflectedOrder, List<TableSelection> tables, String planToken, String requestId) {
    }

    /** 계획 항목 = 고른 항목 하나의 적용 내용 (적용할 수 없으면 이유) */
    public record PlanTable(String key, Long sourceTableId, String sourceTableName, Long tableId, String modelSchemaName, String modelTableName, boolean create,
                            List<Item> items) {
    }

    /** 건수 : 만들 TABLE, 바꿀 TABLE(기존 TABLE 중 무엇이든 바뀌는 것), 추가할 컬럼, 바꿀 속성, 추가할 제약조건(PK·UNIQUE·CHECK·FK), 추가할 인덱스, 원본 COMMENT */
    public record Counts(int tablesCreated, int tablesChanged, int columnsAdded, int attributesChanged, int constraintsAdded, int indexesAdded, int commentsChanged) {
    }

    /**
     * 적용 계획 (읽기 전용, 저장하지 않는다). planToken = 기준·항목·변경 전후의 지문 : 확정 때 서버가 다시 계획해 같은지 본다.
     *
     * @param applicable 고른 항목이 모두 적용 가능한지 (하나라도 아니면 확정할 수 없다)
     */
    public record Plan(Long connectionId, Integer reflectedOrder, String planToken, String requestId, boolean applicable, Counts counts, List<PlanTable> tables) {
    }

    /** @param modelVersionId·modelVersionName 가져오기 직전 상태를 남긴 모델 버전 (같은 트랜잭션) */
    public record Result(Long executionId, String changeGroupId, Long connectionId, Integer reflectedOrder, Long modelVersionId, String modelVersionName, Counts counts,
                         List<PlanTable> tables) {
    }

    public record Execution(Long id, Long connectionId, Long executionId, Integer reflectedOrder, String changeGroupId, Map<String, Object> summary, String userId,
                            LocalDateTime appliedAt) {
    }

    public record ExecutionPage(List<Execution> rows, long total) {
    }

    /** 가져오기가 남긴 모델 변경 이력 (출처 IMPORT·AUTO_SYNC) */
    public record Change(String targetType, Long targetId, String targetName, String action, String source, String before, String after) {
    }

    public record ExecutionDetail(Execution execution, List<Change> changes) {
    }
}
