package com.lexorma;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 테스트 DB 안전 확인 (DB-1 2차, PG-D19) — DB 없이 도는 단위 테스트 */
class TestDatabaseGuardTest {

    private static final String TEST_URL = "jdbc:postgresql://127.0.0.1:5432/lexorma_test";

    @Test
    @DisplayName("테스트 전용 키가 없으면 멈춘다")
    void missing() {
        assertThatThrownBy(() -> TestDatabaseGuard.check(null, "t", TEST_URL, "t")).hasMessageContaining("LEXORMA_TEST_POSTGRES_URL");
        assertThatThrownBy(() -> TestDatabaseGuard.check(TEST_URL, null, TEST_URL, "t")).hasMessageContaining("LEXORMA_TEST_POSTGRES_USER");
    }

    @Test
    @DisplayName("일반 설정이 테스트 설정을 덮어써 다른 DB · 다른 계정을 가리키면 멈춘다")
    void overridden() {
        assertThatThrownBy(() -> TestDatabaseGuard.check(TEST_URL, "t", "jdbc:postgresql://localhost:15439/lexorma", "t")).hasMessageContaining("다른 DB");
        assertThatThrownBy(() -> TestDatabaseGuard.check(TEST_URL, "t", null, "t")).hasMessageContaining("다른 DB");
        assertThatThrownBy(() -> TestDatabaseGuard.check(TEST_URL, "t", TEST_URL, "lexorma")).hasMessageContaining("username");
    }

    @Test
    @DisplayName("DB 이름에 test 가 없으면 멈춘다 (보조 안전장치), PostgreSQL 이 아니면 멈춘다")
    void name() {
        String dev = "jdbc:postgresql://localhost:15439/lexorma";
        assertThatThrownBy(() -> TestDatabaseGuard.check(dev, "t", dev, "t")).hasMessageContaining("test");
        assertThatThrownBy(() -> TestDatabaseGuard.check("jdbc:h2:mem:test", "t", "jdbc:h2:mem:test", "t")).hasMessageContaining("jdbc:postgresql:");
    }

    @Test
    @DisplayName("같은 테스트 DB 의 별도 스키마(쿼리 매개변수만 다름)는 통과한다")
    void ok() {
        assertThatCode(() -> TestDatabaseGuard.check(TEST_URL, "t", TEST_URL, "t")).doesNotThrowAnyException();
        assertThatCode(() -> TestDatabaseGuard.check(TEST_URL, "t", TEST_URL + "?currentSchema=setup_1", "t")).doesNotThrowAnyException();
    }
}
