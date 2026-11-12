package com.lexorma.source.connection.domain.enums;

/**
 * 원본 DB 식별자(스키마·테이블명)의 대소문자 비교 규칙. MySQL·MariaDB 는 서버 설정(lower_case_table_names)에 따라 달라 연결마다 정한다.
 * PostgreSQL·Oracle 은 DBMS 의 규칙이 정해져 있어 DBMS_DEFAULT 만 쓴다.
 */
public enum IdentifierComparison {
    /** DBMS 의 기본 규칙. MySQL·MariaDB 에서는 서버 설정을 모르는 상태 = 대소문자를 구분한다 (다른 이름을 임의로 합치지 않는다) */
    DBMS_DEFAULT,
    /** 대소문자를 구분한다 (MySQL·MariaDB : lower_case_table_names = 0) */
    CASE_SENSITIVE,
    /** 대소문자를 구분하지 않는다 (MySQL·MariaDB : lower_case_table_names = 1·2) */
    CASE_INSENSITIVE
}
