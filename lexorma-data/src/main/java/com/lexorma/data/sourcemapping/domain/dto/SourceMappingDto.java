package com.lexorma.data.sourcemapping.domain.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 원본 매핑 (M-4c) 의 요청·응답. 상태는 저장하지 않고 조회할 때 계산한다 (저장하는 것은 사용자가 확정한 매핑과 확정 시점의 원본 식별 정보뿐).
 */
public final class SourceMappingDto {

    private SourceMappingDto() {
    }

    /**
     * 매핑 상태 (조회 시 계산). 순서대로 먼저 맞는 것 하나 :
     * MODEL_MISSING 모델 객체가 지워짐 → SOURCE_MISSING 마지막 반영 시점에 원본이 없음(삭제 표시) → RECHECK 같은 이름으로 다시 생긴 원본(생성 차수가 다름) →
     * CONFLICT 스키마 매핑과 맞지 않음·원본이 TABLE 이 아님 → MAPPED. 매핑이 없으면 UNMAPPED.
     */
    /** EXCLUDED : 매핑 예외 (M-4g) — 매핑 누락이 아니다. 이유 REMOVED(원본 없음)·RECREATED(재생성, 예외는 유지)를 함께 표시할 수 있다 */
    public enum Status { UNMAPPED, MAPPED, RECHECK, CONFLICT, SOURCE_MISSING, MODEL_MISSING, EXCLUDED }

    /**
     * 상태의 이유 (화면이 문구로 바꾼다).
     * SCHEMA_UNMAPPED 원본 스키마의 매핑이 없거나 유효하지 않음, SCHEMA_MISMATCH 모델 테이블의 스키마가 스키마 매핑과 다름, NOT_TABLE 원본이 VIEW,
     * RECREATED 원본이 사라졌다가 같은 이름으로 다시 생김, REMOVED 원본에서 사라짐, MODEL_DELETED 모델 객체가 지워짐, NO_COLLECTION 반영된 수집 없음
     */
    public enum Reason { SCHEMA_UNMAPPED, SCHEMA_MISMATCH, NOT_TABLE, RECREATED, REMOVED, MODEL_DELETED, NO_COLLECTION }

    public record Connection(Long id, String name, String dbType, boolean enabled, boolean collectable) {
    }

    /** 기준이 아닌 연결에 남아 있는 매핑 (기준 연결을 바꾸면 지우지 않고 남긴다 : 그 연결을 다시 기준으로 고르면 쓰인다) */
    public record Preserved(Long connectionId, String connectionName, long schemaMappings, long tableMappings) {
    }

    /**
     * @param taken 후보가 이미 다른 원본에 매핑되어 있다 (확정하면 거절된다)
     */
    public record Candidate(Long id, String name, boolean taken) {
    }

    public record ModelSchema(Long id, String schemaName, boolean isDefault, Long mappedSourceSchemaId) {
    }

    /**
     * 원본 스키마 한 줄. 원본에 없어진 매핑(SOURCE_MISSING)은 확정 때의 이름으로 보인다.
     *
     * @param candidate 매핑이 없을 때만 : 같은 이름의 모델 스키마, 기본 스키마(PostgreSQL public → DEFAULT). 제안일 뿐 저장하지 않는다
     */
    public record SchemaRow(Long sourceSchemaId, String sourceSchemaName, int tableCount, int viewCount, Long mappingId, Long schemaId, String schemaName,
                            boolean defaultSchema, Integer confirmedOrder, Status status, Reason reason, Candidate candidate) {
    }

    /**
     * 원본 TABLE 한 줄 (VIEW 는 매핑 대상이 아니다 — 정의 SQL 을 수집하지 않는다, 정책 5.9).
     *
     * @param candidate 매핑이 없고 스키마 매핑이 유효할 때만 : 매핑된 모델 스키마에서 이름이 원본 DBMS 규칙으로 같은 테이블. 제안일 뿐 저장하지 않는다
     */
    /**
     * sourceComment : 원본 TABLE 의 COMMENT (수집값) / tableDescription : 매핑된 모델 TABLE 의 업무 설명 / tableSourceComment : 모델의 원본 COMMENT 칸 (가져온 값).
     * 한쪽 값을 다른 쪽으로 채우지 않는다 (없으면 null). 예외·미매핑 행에는 모델 값이 없다
     */
    public record TableRow(Long sourceTableId, Long sourceSchemaId, String sourceSchemaName, String sourceTableName, String sourceComment, Long mappingId,
                           Long tableId, String tableName, String tableKorName, String tableDescription, String tableSourceComment, Long schemaId, String schemaName,
                           Integer confirmedOrder, Status status, Reason reason, Candidate candidate) {
    }

    /**
     * @param base           기준 연결 (없으면 null)
     * @param reflectedOrder 기준 연결의 마지막 반영 순서 (반영된 수집이 없으면 null : 매핑을 확정할 수 없다)
     */
    public record Overview(Connection base, List<Connection> connections, Integer reflectedOrder, Long executionId, LocalDateTime reflectedAt,
                           Map<Status, Long> schemaCounts, Map<Status, Long> tableCounts, int viewCount, List<Preserved> preserved,
                           List<SchemaRow> schemas, List<ModelSchema> modelSchemas) {
    }

    public record TablePage(List<TableRow> rows, long total, Map<Status, Long> counts) {
    }

    /** 모델 테이블 고르기 (매핑 확정 대화상자) */
    public record ModelTable(Long id, String tableName, String tableKorName, Long schemaId, String schemaName, Long mappedSourceTableId) {
    }

    /**
     * @param connectionId 새 기준 연결 (null 이면 해제)
     * @param acknowledge  지금 기준 연결의 매핑이 있을 때 그대로 남기고 바꾸는 것에 동의했는지 (없으면 SOURCE_MAPPING_CONFLICT 로 영향을 알린다)
     */
    public record BaseRequest(Long connectionId, Boolean acknowledge) {
    }

    public record SchemaRequest(Long sourceSchemaId, Long schemaId) {
    }

    public record TableRequest(Long sourceTableId, Long tableId) {
    }

    /** 매핑 예외 등록 (M-4g) */
    public record ExclusionRequest(Long sourceTableId) {
    }
}
