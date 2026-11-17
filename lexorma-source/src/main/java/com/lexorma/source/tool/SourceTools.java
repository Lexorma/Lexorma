package com.lexorma.source.tool;

/**
 * 원본 DB 내부 도구의 이름. 구현은 DBMS 별로 AI-2 에서 만든다 — 지금은 이름과 계약만 있다.
 */
public final class SourceTools {

    /** 원본 DB 에 접속할 수 있는지 확인 */
    public static final String CONNECTION_TEST = "source.connection.test";
    /** 카탈로그·스키마 조회 */
    public static final String SCHEMA_DISCOVERY = "source.schema.discover";
    /** 테이블·뷰 목록 조회 */
    public static final String TABLE_DISCOVERY = "source.table.discover";
    /** 컬럼·타입·COMMENT 조회 */
    public static final String COLUMN_DISCOVERY = "source.column.discover";
    /** PK·UNIQUE·CHECK 조회 */
    public static final String CONSTRAINT_DISCOVERY = "source.constraint.discover";
    /** 인덱스 조회 */
    public static final String INDEX_DISCOVERY = "source.index.discover";
    /** FK·참조 관계 조회 */
    public static final String RELATIONSHIP_DISCOVERY = "source.relationship.discover";
    /** VIEW 정의 조회 (DEC-16) : 실패해도 수집 전체를 멈추지 않고 VIEW 마다 상태로 남긴다. 원문은 기록하지 않는다 */
    public static final String VIEW_DEFINITION_DISCOVERY = "source.view-definition.discover";

    private SourceTools() {
    }
}
