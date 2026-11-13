package com.lexorma.source.collection.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 원본 DB ERD (M-4b) 의 응답 : 반영 순서 R 에 Lexorma 가 확인해 둔 연결 전체의 테이블·VIEW·컬럼·FK 를 한 번에 준다 (테이블마다 상세를 다시 부르지 않는다).
 * 모두 그 시점의 테이블 버전(TB_SRC_TBL_VER)에서 만든다 : 최신 행을 섞지 않는다. 원본 DB 에 접속하지 않고, 물리 모델과도 무관하다.
 * 이름은 원본이 준 그대로(name), 비교·연결에는 DBMS 규칙으로 정규화한 비교 이름(compareName)을 쓴다.
 */
public final class SourceErdDto {

    private SourceErdDto() {
    }

    /**
     * @param reflectedOrder 보여 주는 시점 (반영된 수집이 없으면 null 이고 나머지는 비어 있다)
     * @param dbType         대소문자 비교 규칙을 화면 검색에도 쓰도록 연결의 DBMS·비교 규칙을 함께 준다
     */
    public record Graph(Long connectionId, String dbType, String identifierComparison, Integer reflectedOrder, Long executionId, LocalDateTime reflectedAt,
                        List<Schema> schemas, List<Table> tables, List<Relation> relations) {
    }

    public record Schema(Long schemaId, String catalogName, String schemaName, int tableCount, int viewCount) {
    }

    /** @param type TABLE · VIEW */
    public record Table(Long tableId, Long schemaId, String name, String compareName, String type, String comment, List<Column> columns) {
    }

    /** @param pk PK 구성 컬럼, fk 이 테이블의 FK 구성 컬럼 */
    public record Column(String name, String compareName, String dataType, Boolean nullable, boolean pk, boolean fk) {
    }

    /**
     * 원본에 정의된 FK 하나 (추정한 관계는 없다). source = 참조하는 테이블, target = 참조되는 테이블.
     *
     * @param id            연결 안에서 하나인 값 (참조하는 테이블 ID + 관계의 비교 키)
     * @param targetTableId 그 시점의 카탈로그에 참조 대상 테이블이 없으면(수집 범위 밖) null — 이름은 남는다
     * @param columns       구성 컬럼 쌍 (복합 FK 의 순서 그대로)
     */
    public record Relation(String id, String name, Long sourceTableId, Long targetTableId, String targetCatalogName, String targetSchemaName, String targetTableName,
                           String deleteRule, String updateRule, List<ColumnPair> columns) {
    }

    /** column·referencedColumn 은 표시 이름, …Key 는 노드의 컬럼과 맞출 비교 이름 */
    public record ColumnPair(String column, String columnKey, String referencedColumn, String referencedColumnKey) {
    }
}
