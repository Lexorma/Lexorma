package com.lexorma.source.connection.domain.enums;

/** 접속할 데이터베이스 이름의 종류. DBMS 마다 가리키는 것이 다르다 */
public enum DatabaseNameType {
    /** 데이터베이스명 (PostgreSQL · MySQL · MariaDB) */
    DATABASE,
    /** Oracle 서비스명 */
    SERVICE_NAME,
    /** Oracle SID */
    SID
}
