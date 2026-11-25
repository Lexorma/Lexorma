package com.lexorma.config;

import com.lexorma.config.ManagedDatabaseCheck.ManagedDatabaseConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 관리 DB 설정 확인 (DB-1 2차, 3차 PG-D40 구성 요소 기반 URL 검사) — DB 없이 도는 단위 테스트. 오류 문구에 URL·비밀번호 값이 들어가지 않는다 */
class ManagedDatabaseCheckTest {

    private static final String SECRET = "Fake-Secret-9";

    @Test
    @DisplayName("URL 이 없거나 비면 멈춘다 (임시 DB 로 대신 뜨지 않는다)")
    void missingUrl() {
        assertThatThrownBy(() -> ManagedDatabaseCheck.check(null, "u")).isInstanceOf(ManagedDatabaseConfigurationException.class).hasMessageContaining("spring.datasource.url");
        assertThatThrownBy(() -> ManagedDatabaseCheck.check("  ", "u")).isInstanceOf(ManagedDatabaseConfigurationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:lexorma", "jdbc:h2:file:/data/lexorma;DB_CLOSE_ON_EXIT=FALSE", "jdbc:mysql://h:3306/x", "jdbc:oracle:thin:@h:1521/x", "postgresql://h/db"})
    @DisplayName("PostgreSQL 이 아닌 관리 DB URL 은 지원하지 않는다 (문구에는 jdbc:종류 만)")
    void unsupported(String url) {
        assertThatThrownBy(() -> ManagedDatabaseCheck.check(url, "u")).isInstanceOf(ManagedDatabaseConfigurationException.class)
                .hasMessageContaining("지원하지 않는 관리 DB").satisfies(e -> assertThat(e.getMessage()).doesNotContain("lexorma", "3306", "1521"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://h:5432/db?password=" + SECRET,
            "jdbc:postgresql://h/db?sslmode=require&password=" + SECRET + "&ApplicationName=a",
            "jdbc:postgresql://h/db?password=",
            "jdbc:postgresql://h/db?password=a&password=" + SECRET,
            "jdbc:postgresql://h/db?pass%77ord=" + SECRET,
            "jdbc:postgresql://h/db?%70assword=" + SECRET,
            "jdbc:postgresql://h/db?PASSWORD=" + SECRET,
            "jdbc:postgresql://h/db?Password=" + SECRET,
            "jdbc:postgresql://h/db?sslpassword=" + SECRET,
            "jdbc:postgresql://h/db?password%3D" + SECRET,
            "jdbc:postgresql://h/db?options=-c%20password%3D" + SECRET,
            "jdbc:postgresql://u:" + SECRET + "@h/db",
            "jdbc:postgresql:db?password=" + SECRET,
            "JDBC:POSTGRESQL://h/db?user=u&password=" + SECRET,
            // 3차 추가 : 중복·빈 sslpassword · 이름만 · 사용자 정보만 · 값/DB 이름 안의 표현 · 다중 호스트 안의 사용자 정보 · 잘못된 % 와 함께
            "jdbc:postgresql://h/db?sslpassword=&sslpassword=" + SECRET,
            "jdbc:postgresql://h/db?password",
            "jdbc:postgresql://u@h/db",
            "jdbc:postgresql://h1:5432,u:" + SECRET + "@h2:5432/db",
            "jdbc:postgresql://h/db?options=-c%20sslpassword:" + SECRET,
            "jdbc:postgresql://h/db?ApplicationName=x%20password%3D" + SECRET,
            "jdbc:postgresql://h/db;password=" + SECRET,
            "jdbc:postgresql://h/db?x=%zz&password=" + SECRET})
    @DisplayName("URL 안의 인증정보는 표기와 관계없이 거절한다 (드라이버가 비밀번호로 읽는 형태 + 로그에 원문이 남는 변형)")
    void credentialInUrl(String url) {
        assertThatThrownBy(() -> ManagedDatabaseCheck.check(url, "u")).isInstanceOf(ManagedDatabaseConfigurationException.class)
                .hasMessageContaining("인증정보").satisfies(e -> assertThat(e.getMessage() + ((ManagedDatabaseConfigurationException) e).getAction()).doesNotContain(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db.internal:5432/lexorma",
            "jdbc:postgresql://h/db?sslmode=verify-full&sslrootcert=/certs/root.crt&connectTimeout=5",
            "jdbc:postgresql://h1:5432,h2:5432/db?targetServerType=primary&ApplicationName=lexorma",
            "jdbc:postgresql://h/db?currentSchema=s1&reWriteBatchedInserts=true",
            "jdbc:postgresql:lexorma",
            // 3차 : 이름·값에 password 라는 글자만 있는 정상 URL (예전 단순 포함 검사의 오탐)
            "jdbc:postgresql://password-management-db:5432/lexorma",
            "jdbc:postgresql://localhost:5432/password_service",
            "jdbc:postgresql://h/db?ApplicationName=password-service",
            "jdbc:postgresql://h/db?options=-c%20search_path%3Dpassword_app",
            "jdbc:postgresql://pg-a.internal:5432,pg-b.internal:5433,[::1]:5434/db?targetServerType=preferSecondary&loadBalanceHosts=true",
            "jdbc:postgresql://[fe80::1%25en0]:5432/db",
            "jdbc:postgresql:///db",
            "jdbc:postgresql://h/db?sslmode=verify-ca&sslcert=/certs/client.crt&sslkey=/certs/client.pk8&sslrootcert=/certs/root.crt&&"})
    @DisplayName("정상 PostgreSQL URL · SSL · 연결 옵션 · 다중 호스트, 호스트·DB 이름·옵션 값에 password 라는 글자만 있는 URL 은 통과한다")
    void valid(String url) {
        assertThatCode(() -> ManagedDatabaseCheck.check(url, "lexorma")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사용자명이 없으면 멈춘다. 비밀번호는 요구하지 않는다 (인증 방식은 서버가 정한다 — 필요하면 첫 연결이 인증 오류로 멈춘다)")
    void username() {
        assertThatThrownBy(() -> ManagedDatabaseCheck.check("jdbc:postgresql://h/db", null)).isInstanceOf(ManagedDatabaseConfigurationException.class).hasMessageContaining("username");
        assertThatThrownBy(() -> ManagedDatabaseCheck.check("jdbc:postgresql://h/db", " ")).isInstanceOf(ManagedDatabaseConfigurationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://h/db?sslmode=require%",
            "jdbc:postgresql://h/db?ssl%mode=require",
            "jdbc:postgresql://h/db?pass%2577ord=x",
            "jdbc:postgresql://h o/db",
            "jdbc:postgresql://h:port/db",
            "jdbc:postgresql://h/db?=x",
            "jdbc:postgresql://h/d%zzb",
            "jdbc:postgresql://h/db?pass%zzword=x"})
    @DisplayName("잘못된 % 표기 · 호스트 목록 · 매개변수 이름(이중 인코딩 포함)은 연결 전에 형식 오류로 거절한다 (문구에 URL 이 없다)")
    void malformed(String url) {
        assertThatThrownBy(() -> ManagedDatabaseCheck.check(url, "u")).isInstanceOf(ManagedDatabaseConfigurationException.class)
                .hasMessageContaining("형식").satisfies(e -> assertThat(e.getMessage() + ((ManagedDatabaseConfigurationException) e).getAction()).doesNotContain("port/db", "ssl%mode", "%2577", "h o"));
    }
}
