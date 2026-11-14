package com.lexorma.data.sourcemapping.domain.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 원본 ↔ 모델 비교 (M-4d, docs/source-compare-m4d.md). <b>읽기 전용 분석 결과</b>다 : 실행 명령이 아니고 저장하지 않는다 (요청마다 계산한다).
 * M-4c 의 매핑 상태(SourceMappingDto.Status)와 비교 상태({@link CompareStatus})는 다른 개념이다 : 매핑 상태는 "무엇과 무엇을 잇는가", 비교 상태는 "이은 둘이 같은가".
 */
public final class SourceCompareDto {

    private SourceCompareDto() {
    }

    /**
     * 비교 상태.
     * SAME : 비교한 속성이 모두 같다 (비교하지 않은 속성은 notCompared 로 따로 보인다). UNKNOWN 은 SAME 이 아니고 삭제 대상도 아니다.
     */
    public enum CompareStatus { SAME, ADDED_IN_SOURCE, ADDED_IN_MODEL, MODIFIED, RENAME_CANDIDATE, CONFLICT, UNKNOWN }

    /** 속성 하나의 결과. NOT_COMPARED : 모델에 그 속성이 없거나(원본 COMMENT, FK 규칙의 일부) 모델이 이름을 만들어 쓰는 경우 — 차이 없음이 아니다 */
    public enum AttributeResult { SAME, DIFFERENT, UNKNOWN, NOT_COMPARED }

    /** TABLE : TABLE 자체의 속성 (원본 COMMENT) */
    public enum ObjectKind { TABLE, COLUMN, PRIMARY_KEY, UNIQUE, CHECK, FOREIGN_KEY, INDEX }

    /**
     * TABLE 비교 상태의 이유.
     * <ul>
     *   <li>COMPARED : 매핑이 유효해 하위 객체까지 비교했다</li>
     *   <li>UNMAPPED : 원본 TABLE 이 매핑되지 않았고 같은 이름의 모델 TABLE 도 없다 (원본에만 있다)</li>
     *   <li>MAPPING_CANDIDATE : 매핑되지 않았지만 같은 이름의 모델 TABLE 후보가 있다 (확정 전이라 비교하지 않는다)</li>
     *   <li>SCHEMA_UNMAPPED : 원본 스키마 매핑이 없거나 유효하지 않아 모델 쪽을 알 수 없다</li>
     *   <li>MODEL_NAME_TAKEN : 같은 이름의 모델 TABLE 이 이미 다른 원본에 매핑되어 있다 / VIEW_NAME_TAKEN : 같은 이름의 모델 VIEW 가 있다 (가져오면 이름이 부딪힌다)</li>
     *   <li>RECHECK · MAPPING_CONFLICT · SOURCE_REMOVED · MODEL_DELETED : M-4c 매핑 상태 (재확인 필요, 충돌, 원본 없음, 모델 없음)</li>
     *   <li>MODEL_ONLY : 매핑된 모델 스키마에 있지만 어떤 원본 TABLE 에도 매핑되지 않은 모델 TABLE</li>
     *   <li>UNSUPPORTED_DBMS : 이 DBMS 의 비교 규칙이 아직 없다</li>
     * </ul>
     */
    public enum TableReason {
        COMPARED, UNMAPPED, MAPPING_CANDIDATE, SCHEMA_UNMAPPED, MODEL_NAME_TAKEN, VIEW_NAME_TAKEN, RECHECK, MAPPING_CONFLICT, SOURCE_REMOVED, MODEL_DELETED, MODEL_ONLY,
        UNSUPPORTED_DBMS
    }

    /**
     * 속성 설명 코드 (화면이 문구로 바꾼다).
     * <ul>
     *   <li>MODEL_HAS_NO_FIELD : 모델에 이 속성 칸이 없다 / SOURCE_COMMENT : 원본 COMMENT 는 모델의 원본 COMMENT 칸(V3)과 비교하고 업무 설명과는 비교하지 않는다</li>
     *   <li>GENERATED_NAME : 모델은 이 이름을 저장하지 않고 DDL 을 만들 때 정한다</li>
     *   <li>EXPRESSION : 식이 달라 의미가 같은지 판단하지 않았다 / UNSUPPORTED_TYPE : 별칭 규칙을 모르는 타입이라 원문만 비교했다</li>
     *   <li>DEFAULT_RULE : 모델에 FK 규칙 칸이 없고 DDL 은 기본(NO ACTION)이다 / SERIAL_DEFAULT : SERIAL 이 시퀀스 기본값을 만든다</li>
     *   <li>UNRESOLVED_TARGET : 참조 대상 원본 TABLE 이 매핑되지 않아 모델 TABLE 과 견줄 수 없다 / RELATIVE_ORDER : 양쪽 모두에 있는 컬럼 사이의 순서</li>
     *   <li>NULLABLE_UNKNOWN : 원본이 NULL 허용 여부를 주지 않았다</li>
     * </ul>
     */
    public enum Note {
        MODEL_HAS_NO_FIELD, SOURCE_COMMENT, GENERATED_NAME, EXPRESSION, UNSUPPORTED_TYPE, DEFAULT_RULE, SERIAL_DEFAULT, UNRESOLVED_TARGET, RELATIVE_ORDER,
        NULLABLE_UNKNOWN
    }

    /**
     * 경고 코드 (값은 args).
     * MODEL_TYPE_CONVERTED : 모델 타입 args[1] 은 PostgreSQL DDL 에서 args[2] 로 만들어진다 (컬럼 args[0]) /
     * MODEL_TYPE_MISSING : 모델 컬럼 args[0] 에 타입이 없어 VARCHAR 로 비교했다 /
     * CONSTRAINT_INDEX : 원본 인덱스 args[0] 은 PK·UNIQUE 제약조건이 만든 것이라 인덱스 비교에서 뺐다 /
     * DUPLICATE_MODEL_NAME : 모델 TABLE 에 비교 이름이 같은 args[0] 이 여럿이다 / UNSUPPORTED_DBMS : args[0] 원본의 비교 규칙이 없다
     */
    public enum WarningCode { MODEL_TYPE_CONVERTED, MODEL_TYPE_MISSING, CONSTRAINT_INDEX, DUPLICATE_MODEL_NAME, UNSUPPORTED_DBMS }

    public record Warning(WarningCode code, List<String> args) {
    }

    /**
     * 비교 요약. 비교 기준 = 기준 연결 + 마지막 반영 수집의 반영 순서 : 목록·상세 요청에 connectionId·reflectedOrder 를 함께 보내면
     * 그 사이 기준 연결이 바뀌었거나 새 수집이 반영된 경우 409(SOURCE_COMPARE_STALE)로 알린다 (다른 시점의 결과를 섞지 않는다).
     *
     * @param comparable 비교할 수 있는지 (기준 연결·완료된 수집·지원 DBMS)
     * @param tables     TABLE 비교 상태별 건수 (원본 TABLE + 모델에만 있는 TABLE)
     */
    public record Summary(SourceMappingDto.Connection base, Integer reflectedOrder, Long executionId, LocalDateTime reflectedAt, boolean comparable,
                          String notComparable, int sourceTables, int viewCount, int mappedTables, int comparedTables, int sameTables, int differentTables,
                          int unknownTables, int unmappedTables, int modelOnlyTables, int conflictTables, int recheckTables, Map<CompareStatus, Long> tables,
                          Map<TableReason, Long> reasons) {
    }

    /**
     * TABLE 비교 행. key = "S" + 원본 TABLE ID (원본이 있는 행) 또는 "M" + 모델 TABLE ID (모델에만 있는 행)
     *
     * @param differences 차이가 있는 하위 객체 수 (UNKNOWN 은 unknowns 로 따로)
     */
    public record TableRow(String key, Long sourceTableId, Long sourceSchemaId, String sourceSchemaName, String sourceTableName, String sourceComment, Long mappingId,
                           SourceMappingDto.Status mappingStatus, SourceMappingDto.Reason mappingReason, Long tableId, String schemaName, String tableName,
                           String tableKorName, CompareStatus status, TableReason reason, int differences, int unknowns, Map<ObjectKind, Integer> differencesByKind) {
    }

    public record TablePage(Long connectionId, Integer reflectedOrder, List<TableRow> rows, long total, Map<CompareStatus, Long> counts) {
    }

    /** 속성 하나 : 원본 값과 모델 값을 나란히 (값은 화면 표시용 문자열) */
    public record AttributeDiff(String attribute, String source, String model, AttributeResult result, Note note) {
    }

    /**
     * 하위 객체 하나 (컬럼·PK·UNIQUE·CHECK·FK·인덱스).
     *
     * @param candidates RENAME_CANDIDATE 일 때 맞은편 후보 이름들 / ambiguous : 후보가 여럿이거나 맞은편도 다른 후보가 있다
     */
    public record ObjectDiff(ObjectKind kind, String sourceName, String modelName, CompareStatus status, List<AttributeDiff> attributes, List<String> candidates,
                             boolean ambiguous) {
    }

    /**
     * 이름 변경 후보 : 이름은 다르지만 구조가 같은 원본에만·모델에만 있는 객체의 짝. <b>제안일 뿐이다</b> (저장하지 않고, 비교 결과를 SAME 으로 바꾸지 않는다).
     *
     * @param basis     판단 근거 : 같다고 본 속성 (dataType·length·nullable / columns …)
     * @param signature 그 속성의 원본 값 요약 (예 : VARCHAR(100) NULL)
     */
    public record RenameCandidate(String tableKey, Long sourceTableId, String sourceSchemaName, String sourceTableName, Long tableId, String tableName, ObjectKind kind,
                                  String sourceName, String modelName, List<String> basis, String signature, boolean ambiguous) {
    }

    public record RenamePage(Long connectionId, Integer reflectedOrder, List<RenameCandidate> rows, long total) {
    }

    /** TABLE 상세 비교 */
    public record TableDetail(Long connectionId, Integer reflectedOrder, TableRow table, List<ObjectDiff> objects, List<RenameCandidate> renameCandidates,
                              List<Warning> warnings) {
    }
}
