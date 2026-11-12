package com.lexorma.source.connection.jdbc;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.connection.domain.enums.DatabaseNameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JDBC 접속 규칙 : 입력으로 URL·드라이버·임의 옵션을 받지 않고 검증한 항목으로 URL 을 만든다 */
class SourceJdbcSettingsTest {

    @Test
    @DisplayName("DBMS 마다 정해진 드라이버와 URL 모양. 계정·비밀번호는 URL 에 없다")
    void urls() {
        assertThat(SourceJdbcSettings.jdbcUrl(DbType.POSTGRESQL, "db.internal", 5432, "app", DatabaseNameType.DATABASE, Map.of())).isEqualTo("jdbc:postgresql://db.internal:5432/app");
        assertThat(SourceJdbcSettings.jdbcUrl(DbType.MYSQL, "10.0.0.5", 3306, "shop", DatabaseNameType.DATABASE, Map.of("sslmode", "required"))).isEqualTo("jdbc:mysql://10.0.0.5:3306/shop?sslMode=REQUIRED");
        assertThat(SourceJdbcSettings.jdbcUrl(DbType.MARIADB, "fd00::5", 3307, "crm", DatabaseNameType.DATABASE, Map.of())).isEqualTo("jdbc:mariadb://[fd00::5]:3307/crm");
        assertThat(SourceJdbcSettings.jdbcUrl(DbType.ORACLE, "ora.internal", 1521, "ORCLPDB1", DatabaseNameType.SERVICE_NAME, Map.of())).isEqualTo("jdbc:oracle:thin:@//ora.internal:1521/ORCLPDB1");
        assertThat(SourceJdbcSettings.jdbcUrl(DbType.ORACLE, "ora.internal", 1521, "ORCL", DatabaseNameType.SID, Map.of())).isEqualTo("jdbc:oracle:thin:@ora.internal:1521:ORCL");
        assertThat(SourceJdbcSettings.driverClassName(DbType.POSTGRESQL)).isEqualTo("org.postgresql.Driver");
        assertThat(SourceJdbcSettings.driverClassName(DbType.ORACLE)).isEqualTo("oracle.jdbc.OracleDriver");
        assertThat(SourceJdbcSettings.defaultPort(DbType.ORACLE)).isEqualTo(1521);
    }

    @Test
    @DisplayName("검증하지 않은 값으로는 URL 을 만들지 않는다 : 호스트·데이터베이스명의 URL 구분 문자, 포트 범위, 이름 종류, 허용하지 않는 옵션")
    void rejectsUnverified() {
        for (String host : new String[]{"db.internal/app", "db.internal:5432", "user@db", "db internal", "-db", "db.", "", "jdbc:postgresql://x", "a".repeat(256)}) {
            assertThat(SourceJdbcSettings.isValidHost(host)).as(host).isFalse();
            assertThatThrownBy(() -> SourceJdbcSettings.jdbcUrl(DbType.POSTGRESQL, host, 5432, "app", DatabaseNameType.DATABASE, Map.of())).as(host).isInstanceOf(IllegalArgumentException.class);
        }
        for (String name : new String[]{"app?user=x", "app;password=x", "app/x", "app&x", "a b", "", "app#x", "a".repeat(256)}) {
            assertThat(SourceJdbcSettings.isValidDatabaseName(name)).as(name).isFalse();
        }
        assertThat(SourceJdbcSettings.isValidDatabaseName("데이터_베이스$1.x-y")).isTrue();
        assertThatThrownBy(() -> SourceJdbcSettings.jdbcUrl(DbType.POSTGRESQL, "db", 0, "app", DatabaseNameType.DATABASE, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceJdbcSettings.jdbcUrl(DbType.POSTGRESQL, "db", 5432, "app", DatabaseNameType.SID, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceJdbcSettings.jdbcUrl(DbType.POSTGRESQL, "db", 5432, "app", DatabaseNameType.DATABASE, Map.of("socketFactory", "x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("옵션은 허용 목록의 이름·값만, 드라이버 표기로 맞춘다. 비밀값·파일·다른 주소를 여는 옵션은 받지 않는다")
    void options() {
        assertThat(SourceJdbcSettings.normalizeOptions(DbType.POSTGRESQL, Map.of("SSLMODE", "Verify-Full"))).isEqualTo(Map.of("sslmode", "verify-full"));
        assertThat(SourceJdbcSettings.normalizeOptions(DbType.MARIADB, Map.of("sslMode", "TRUST"))).isEqualTo(Map.of("sslMode", "trust"));
        assertThat(SourceJdbcSettings.normalizeOptions(DbType.POSTGRESQL, null)).isEmpty();
        for (Map<String, String> bad : java.util.List.of(Map.of("password", "x"), Map.of("user", "x"), Map.of("sslfactory", "x"), Map.of("socketFactory", "x"),
                Map.of("loggerFile", "/tmp/x"), Map.of("sslmode", "off"), Map.of("allowLoadLocalInfile", "true"), Map.of("autoDeserialize", "true"))) {
            assertThat(SourceJdbcSettings.normalizeOptions(DbType.POSTGRESQL, bad)).as(bad.toString()).isNull();
            assertThat(SourceJdbcSettings.normalizeOptions(DbType.MYSQL, bad)).as(bad.toString()).isNull();
        }
        assertThat(SourceJdbcSettings.normalizeOptions(DbType.ORACLE, Map.of("sslmode", "require"))).isNull();
        assertThat(SourceJdbcSettings.allowedOptions(DbType.ORACLE)).isEmpty();
    }

    @Test
    @DisplayName("이름 종류 : Oracle 은 서비스명(기본)·SID, 그 밖에는 데이터베이스명")
    void nameTypes() {
        assertThat(SourceJdbcSettings.defaultNameType(DbType.ORACLE)).isEqualTo(DatabaseNameType.SERVICE_NAME);
        assertThat(SourceJdbcSettings.defaultNameType(DbType.MYSQL)).isEqualTo(DatabaseNameType.DATABASE);
        assertThat(SourceJdbcSettings.supports(DbType.ORACLE, DatabaseNameType.DATABASE)).isFalse();
        assertThat(SourceJdbcSettings.supports(DbType.MARIADB, DatabaseNameType.SID)).isFalse();
    }

    @Test
    @DisplayName("운영자의 호스트 범위 : 비우면 제한 없음, 이름 그대로 또는 *. 접미사 (대소문자 무시)")
    void hostPolicy() {
        assertThat(new SourceHostPolicy("").isAllowed("anything.example")).isTrue();
        SourceHostPolicy policy = new SourceHostPolicy(" db1.internal , *.db.internal,10.20.30.40 ");
        assertThat(policy.isRestricted()).isTrue();
        assertThat(policy.isAllowed("DB1.internal")).isTrue();
        assertThat(policy.isAllowed("pg.db.internal")).isTrue();
        assertThat(policy.isAllowed("10.20.30.40")).isTrue();
        assertThat(policy.isAllowed("db.internal")).isFalse();
        assertThat(policy.isAllowed("evil-db.internal")).isFalse();
        assertThat(policy.isAllowed("db1.internal.evil")).isFalse();
        assertThat(policy.isAllowed("10.20.30.41")).isFalse();
        assertThat(policy.isAllowed(null)).isFalse();
    }
}
