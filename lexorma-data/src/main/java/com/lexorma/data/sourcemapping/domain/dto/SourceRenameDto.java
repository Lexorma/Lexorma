package com.lexorma.data.sourcemapping.domain.dto;

import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 원본 이름 변경 매핑 (DEC-06, docs/completed/source-rename-mapping-dec06.md). 후보는 제안일 뿐이고 사용자가 확정해야 저장된다.
 * 변경 요청은 비교 화면과 같은 기준(connectionId·reflectedOrder)을 보내야 한다 : 그 사이 기준 연결·반영 수집이 바뀌었으면 409 SOURCE_COMPARE_STALE.
 */
public final class SourceRenameDto {

    private SourceRenameDto() {
    }

    /**
     * 컬럼 매핑 상태 (조회 때 계산). MAPPED : 원본 컬럼·모델 컬럼이 모두 있다 / SOURCE_MISSING : 마지막 반영 수집의 원본 TABLE 에 그 컬럼이 없다 /
     * MODEL_MISSING : 모델 컬럼이 지워졌거나 매핑된 모델 TABLE 의 컬럼이 아니다 / TABLE_INVALID : TABLE 매핑이 매핑됨 상태가 아니다 (원본 없음·재확인·충돌 등)
     */
    public enum ColumnStatus { MAPPED, SOURCE_MISSING, MODEL_MISSING, TABLE_INVALID }

    /**
     * TABLE 이름 변경 후보 : 원본에서 사라진(원본 없음) TABLE 매핑 ↔ 같은 스키마 매핑의 미매핑 원본 TABLE. 근거는 컬럼 비교 이름이 겹치는 정도뿐이다 (유사도만으로 확정하지 않는다).
     *
     * @param score     겹친 컬럼 이름 수 / 컬럼이 더 많은 쪽의 컬럼 수 (0.5 이상만 후보)
     * @param ambiguous 이 매핑의 후보가 여럿이거나, 이 원본 TABLE 이 다른 매핑의 후보이기도 하다
     */
    public record TableCandidate(Long mappingId, Long tableId, String schemaName, String tableName, String tableKorName, String removedSourceTableName,
                                 Long sourceTableId, String sourceSchemaName, String sourceTableName, int matchedColumns, int sourceColumns, int modelColumns,
                                 double score, boolean ambiguous) {
    }

    /** 컬럼 이름 변경 후보 (M-4d 비교의 RENAME_CANDIDATE 중 COLUMN) */
    public record ColumnCandidate(Long mappingId, Long sourceTableId, String sourceTableName, Long tableId, String tableName, String sourceColumnName, Long columnId,
                                  String columnName, boolean approved, List<String> basis, String signature, boolean ambiguous) {
    }

    /** 원본 없음인 TABLE 매핑 중 이름 변경 후보가 없는 것 (삭제 후보와 구분하는 근거) */
    public record RemovedTable(Long mappingId, Long tableId, String tableName, String removedSourceTableName, boolean hasCandidate) {
    }

    public record Candidates(Long connectionId, Integer reflectedOrder, List<TableCandidate> tables, List<ColumnCandidate> columns, List<RemovedTable> removedTables) {
    }

    public record ColumnMapping(Long id, Long tableMappingId, Long sourceTableId, String sourceTableName, String sourceColumnName, String compareName, Long tableId,
                                String tableName, Long columnId, String columnName, Integer reflectedOrder, ColumnStatus status, LocalDateTime confirmedAt,
                                String confirmedBy) {
    }

    /** TABLE 이름 변경 확정 : 원본 없음인 TABLE 매핑(mappingId)을 새 원본 TABLE 로 옮긴다 (모델 TABLE·컬럼 매핑·예외 여부는 그대로) */
    public record TableRenameRequest(Long connectionId, Integer reflectedOrder, Long mappingId, Long sourceTableId) {
    }

    /** 컬럼 매핑 확정·변경 : TABLE 매핑 안의 원본 컬럼(이름) ↔ 모델 컬럼 */
    public record ColumnRequest(Long connectionId, Integer reflectedOrder, Long tableMappingId, String sourceColumnName, Long columnId) {
    }

    public record History(Long id, Long tableMappingId, SourceMappingHistory.TargetType targetType, SourceMappingHistory.ChangeType changeType,
                          Map<String, Object> before, Map<String, Object> after, LocalDateTime changedAt, String changedBy) {
    }

    public record HistoryPage(List<History> rows, long total) {
    }
}
