package com.lexorma.core.source;

import java.time.LocalDateTime;
import java.util.List;
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

    /** 프로젝트의 연결 목록 (기준 연결을 고르는 화면용) */
    List<Connection> connections(Long projectId);

    /**
     * 마지막 반영 시점의 스키마·테이블·VIEW (컬럼 등 하위 객체는 넣지 않는다). 연결이 없거나 다른 프로젝트면 비어 있고,
     * 반영된 수집이 없으면 reflectedOrder 가 null 이고 목록이 비어 있다.
     */
    Optional<Snapshot> latest(Long projectId, Long connectionId);

    /**
     * 따옴표 없이 쓴 이름(모델의 DDL 은 식별자를 따옴표 없이 쓴다)을 이 연결의 DBMS 비교 규칙으로 바꾼다 : 원본 객체의 비교 이름과 바로 비교할 수 있다.
     * 규칙(PostgreSQL 소문자, Oracle 대문자, MySQL·MariaDB 는 연결 설정)은 원본 모듈 한 곳(SourceIdentifiers)에만 있다.
     */
    String compareName(Connection connection, NameKind kind, String unquotedName);

    enum NameKind { SCHEMA, TABLE }

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
}
