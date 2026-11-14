package com.lexorma.core.source;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 읽기 전용 원본 카탈로그 계약 (M-4c, docs/m4-foundation.md 5.1). lexorma-source 가 구현하고 lexorma-data(원본 매핑)가 쓴다 —
 * 두 모듈이 서로를 직접 참조하지 않도록 값(record)만 오간다. 원본 DB 에 접속하지 않고 Lexorma 에 저장된 카탈로그만 읽는다.
 * <p>
 * 프로젝트 범위는 부르는 쪽이 로그인 정보(검증된 실행 컨텍스트)에서 꺼내 넘긴다. 다른 프로젝트의 연결은 없는 것과 같다.
 * 시점은 <b>마지막으로 반영된(COMPLETED) 수집</b>이다 : 객체마다 그 반영 순서 이하의 마지막 버전(삭제 표시 제외)이다 —
 * PARTIAL 수집의 범위 밖 객체는 앞 버전이 그대로 유효하므로 "이번 수집에 없다" 를 삭제로 보지 않는다 (원본 카탈로그 버전 규칙).
 */
public interface SourceCatalogReader {

    /** 프로젝트의 원본 연결 (없거나 다른 프로젝트면 비어 있다) */
    Optional<Connection> connection(Long projectId, Long connectionId);

    /**
     * 연결 행을 공유 잠금으로 읽는다 (쓰기 트랜잭션 안에서만). 연결에 기대는 데이터(원본 매핑·기준 연결)를 저장하는 쪽이 저장 전에 부른다 :
     * 연결 삭제는 연결 행을 배타 잠금으로 잡은 뒤 그 데이터를 정리하므로, 삭제가 진행 중이면 끝날 때까지 기다렸다가 연결이 없어졌으면 false 이고,
     * 먼저 잠그면 삭제가 이 트랜잭션의 커밋을 기다렸다가 방금 저장한 것까지 지운다. 잠금 순서 : 프로젝트(ProjectWriteGuard.lock) → 연결 → 그 밖의 행.
     *
     * @return 그 프로젝트의 연결이 있으면 true (없거나 다른 프로젝트면 false)
     */
    boolean lockConnection(Long projectId, Long connectionId);

    /** 프로젝트의 연결 목록 (기준 연결을 고르는 화면용) */
    List<Connection> connections(Long projectId);

    /**
     * 마지막 반영 시점의 스키마·테이블·VIEW (컬럼 등 하위 객체는 넣지 않는다). 연결이 없거나 다른 프로젝트면 비어 있고,
     * 반영된 수집이 없으면 reflectedOrder 가 null 이고 목록이 비어 있다.
     */
    Optional<Snapshot> latest(Long projectId, Long connectionId);

    /**
     * 반영 순서 R 시점의 테이블 상세 : 컬럼·제약조건(PK·UNIQUE·CHECK)·인덱스·FK (원본 비교, M-4d). 값은 원본이 준 표현 그대로다 (타입·DEFAULT·식을 다시 쓰지 않는다).
     * R 은 부르는 쪽이 {@link #latest} 에서 받은 반영 순서로 고정해 넘긴다 : 그 사이 새 수집이 반영되어도 같은 시점을 읽고 다른 시점의 객체를 섞지 않는다.
     * 그 시점에 없는(삭제 표시) 테이블, 다른 연결·다른 프로젝트의 ID 는 결과에 없다. 읽기만 하고 잠그지 않는다.
     *
     * @return 원본 테이블 ID → 상세
     */
    Map<Long, TableDetail> tableDetails(Long projectId, Long connectionId, int reflectedOrder, Collection<Long> tableIds);

    /**
     * 따옴표 없이 쓴 이름(모델의 DDL 은 식별자를 따옴표 없이 쓴다)을 이 연결의 DBMS 비교 규칙으로 바꾼다 : 원본 객체의 비교 이름과 바로 비교할 수 있다.
     * 규칙(PostgreSQL 소문자, Oracle 대문자, MySQL·MariaDB 는 연결 설정)은 원본 모듈 한 곳(SourceIdentifiers)에만 있다.
     */
    String compareName(Connection connection, NameKind kind, String unquotedName);

    /** COLUMN : 컬럼과 제약조건·인덱스 이름 (MySQL·MariaDB 는 테이블 이름과 접는 규칙이 다르다) */
    enum NameKind { SCHEMA, TABLE, COLUMN }

    /**
     * @param dbType                    POSTGRESQL · ORACLE · MYSQL · MARIADB
     * @param identifierComparison      DBMS_DEFAULT · CASE_SENSITIVE · CASE_INSENSITIVE
     * @param collectable               이 DBMS 의 수집 도구가 있는지
     * @param defaultSchemaCompareName  이 DBMS 의 기본 스키마 비교 이름 (PostgreSQL public). 모델의 기본 스키마(DEFAULT) 후보를 고를 때 쓴다. 정할 수 없으면 null
     */
    record Connection(Long id, String name, String dbType, String identifierComparison, boolean enabled, boolean collectable, String defaultSchemaCompareName) {
    }

    record Snapshot(Connection connection, Integer reflectedOrder, Long executionId, LocalDateTime reflectedAt, List<Schema> schemas, List<Table> tables) {
    }

    /**
     * @param id            원본 스키마의 안정 식별자 (TB_SRC_SCHM 일련번호 : 수집이 바뀌어도 같은 이름이면 같은 행이다. 버전 행 ID 가 아니다)
     * @param pathKey       연결 안의 비교 경로 (카탈로그·스키마의 비교 이름)
     * @param compareName   스키마의 비교 이름 (경로의 스키마 부분)
     * @param creationCycle 생성 차수 : 사라졌다가 같은 이름으로 다시 생기면 1 씩 는다 (다른 객체로 본다)
     */
    record Schema(Long id, String pathKey, String catalogName, String schemaName, String compareName, int creationCycle) {
    }

    /**
     * @param id          원본 테이블의 안정 식별자 (TB_SRC_TBL 일련번호)
     * @param compareName 원본 DBMS 규칙으로 정규화한 비교 이름
     * @param type        TABLE · VIEW
     */
    record Table(Long id, Long schemaId, String pathKey, String name, String compareName, String type, String comment, int creationCycle) {
    }

    /** 원본 테이블의 하위 객체 (그 시점의 버전 내용) */
    record TableDetail(Long tableId, List<Column> columns, List<Constraint> constraints, List<Index> indexes, List<ForeignKey> foreignKeys) {
    }

    /**
     * @param compareName  원본 DBMS 규칙으로 정규화한 비교 이름
     * @param ordinal      원본의 컬럼 위치 (삭제된 컬럼 때문에 비어 있는 번호가 있을 수 있다)
     * @param dataType     원본 타입 표현 (PostgreSQL format_type)
     * @param precision    숫자 정밀도 (뒤쪽 0 을 지운 문자열)
     * @param defaultValue 기본값 식. IDENTITY·생성 컬럼은 그 절(GENERATED …)이 들어 있다
     */
    record Column(String name, String compareName, Integer ordinal, String dataType, Integer maxLength, String precision, Integer scale, Boolean nullable,
                  String defaultValue, String comment) {
    }

    /**
     * @param type    PRIMARY_KEY · UNIQUE · CHECK
     * @param columns 구성 컬럼의 비교 이름 (순서대로)
     */
    record Constraint(String name, String type, String checkExpression, List<String> columns) {
    }

    /**
     * @param columns     구성 요소마다 컬럼의 비교 이름 (표현식 요소는 null)
     * @param expressions 구성 요소마다 표현식 (컬럼 요소는 null). 없으면 null
     * @param sorts       구성 요소마다 정렬 (기본이면 null, 포함 컬럼은 INCLUDE). 없으면 null
     */
    record Index(String name, String indexType, boolean unique, List<String> columns, List<String> expressions, List<String> sorts, String predicate) {
    }

    /**
     * 원본에 실제로 있는 FK (추정 관계가 아니다). 참조 대상은 원본이 준 이름 그대로다.
     *
     * @param columns           참조하는 컬럼의 비교 이름 (순서대로)
     * @param referencedColumns 참조되는 컬럼 (원본이 준 이름, 같은 순서)
     */
    record ForeignKey(String name, String referencedCatalog, String referencedSchema, String referencedTable, String deleteRule, String updateRule,
                      List<String> columns, List<String> referencedColumns) {
    }
}
