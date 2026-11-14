package com.lexorma.data.sourcemapping.service;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** 확정의 NOWAIT 잠금 실패만 409 로 바꾼다 (예외 모양은 PostgreSQL 18·H2 에서 실제로 받은 것과 같다) */
class SourceImportLockErrorTest {

    @Test
    @DisplayName("잠금 실패 판정 : PostgreSQL 55P03·H2 50200 만, 교착(PostgreSQL 40P01·H2 40001)과 다른 오류는 아니다")
    void lockNotAvailable() {
        assertThat(SourceImportService.lockNotAvailable(new PessimisticLockException(new org.hibernate.exception.LockTimeoutException("x", new SQLException("could not obtain lock", "55P03"))))).isTrue();
        assertThat(SourceImportService.lockNotAvailable(new LockTimeoutException("x", new RuntimeException(new SQLException("Timeout trying to lock table", "HYT00", 50200))))).isTrue();
        assertThat(SourceImportService.lockNotAvailable(new PessimisticLockException(new SQLException("deadlock detected", "40P01")))).isFalse();
        assertThat(SourceImportService.lockNotAvailable(new PessimisticLockException(new SQLException("Deadlock detected", "40001", 40001)))).isFalse();
        assertThat(SourceImportService.lockNotAvailable(new PessimisticLockException(new SQLException("syntax", "42601")))).isFalse();
        assertThat(SourceImportService.lockNotAvailable(new PessimisticLockException("원인 없음"))).isFalse();
    }
}
