package com.lexorma.source.connection.domain.dto;

import java.util.Map;

/** 외부 DB 연결 요청·응답. 비밀번호는 요청으로만 받고 응답에는 설정 여부만 준다. JDBC URL 은 받지 않는다 */
public final class SourceConnectionDto {

    private SourceConnectionDto() {
    }

    /**
     * @param port                 없으면 DBMS 의 기본 포트
     * @param databaseName         데이터베이스명 (Oracle 은 서비스명 또는 SID)
     * @param databaseNameType     DATABASE · SERVICE_NAME · SID. 없으면 DBMS 의 기본 (Oracle 은 SERVICE_NAME)
     * @param password             수정할 때 비우면(null·빈 값) 저장된 것을 그대로 둔다
     * @param options              허용 목록에 있는 연결 옵션 (예: PostgreSQL 의 sslmode)
     * @param identifierComparison DBMS_DEFAULT(기본) · CASE_SENSITIVE · CASE_INSENSITIVE (MySQL·MariaDB 만 고를 수 있다)
     */
    public record Request(String name, String dbType, String host, Integer port, String databaseName, String databaseNameType, String username, String password,
                          Map<String, String> options, String identifierComparison, String description, Boolean enabled) {

        /** 비밀번호가 로그에 찍히지 않게 한다 */
        @Override
        public String toString() {
            return "SourceConnectionDto.Request[name=" + name + ", dbType=" + dbType + ", host=" + host + ", port=" + port + ", databaseName=" + databaseName
                    + ", username=" + username + ", password=" + (password == null || password.isEmpty() ? "(없음)" : "****") + ", enabled=" + enabled + "]";
        }
    }

    /** 저장된 비밀번호의 상태 */
    public enum PasswordStatus {
        /** 저장된 비밀번호가 없다 */
        NONE,
        /** 저장되어 있고 읽을 수 있다 */
        CONFIGURED,
        /** 저장된 것을 읽을 수 없다 (키 분실·교체, 변조) : 다시 등록해야 한다 */
        REREGISTER_REQUIRED,
        /** 암호화 키가 설정되지 않아 확인할 수 없다 */
        KEY_NOT_CONFIGURED
    }

    /**
     * @param passwordConfigured 비밀번호가 저장되어 있는지 (값은 주지 않는다)
     * @param driverAvailable    이 DBMS 의 JDBC 드라이버가 이 배포본에 들어 있는지
     * @param schemaCount        이 연결의 원본 카탈로그에 있는 스키마 수
     */
    public record Response(Long id, String name, String dbType, String host, Integer port, String databaseName, String databaseNameType, String username,
                           boolean passwordConfigured, PasswordStatus passwordStatus, Map<String, String> options, String identifierComparison,
                           String description, boolean enabled, boolean driverAvailable, long schemaCount) {
    }
}
