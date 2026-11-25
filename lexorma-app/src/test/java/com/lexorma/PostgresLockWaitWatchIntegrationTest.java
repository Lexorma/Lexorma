package com.lexorma;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M-8 동시성 테스트의 잠금 대기 감시 결함 결정적 재현 (AI 공통 실행 안정성 최종 보완 AES-D17 — modeling-concurrency-m8.md 23장). PostgreSQL 전용 (H2 는 건너뛴다).
 * <p>
 * PostgreSQL 은 한 트랜잭션 안에서 {@code pg_stat_activity} 의 <b>백엔드 목록</b>을 처음 읽은 때로 고정한다. 목록에 있던 백엔드의 대기 상태는 읽을 때마다 새로 보이지만,
 * 첫 조회 뒤에 연결된 백엔드는 그 트랜잭션이 끝날 때까지 보이지 않는다. A 의 트랜잭션 안에서 감시하는 M-8 테스트는 B 가 풀에서 <b>새 연결</b>을 열어야 할 때(풀이 다 쓰인 때)
 * 그 대기를 못 보고 20초 뒤 실패한다. 아래는 두 경우를 sleep 없이 관찰로 순서를 정해 재현한다 — B 가 첫 조회 뒤 새 연결(새 백엔드)을 쓰는 경우 / 첫 조회 전부터 있던 연결을 쓰는 경우.
 * 고친 감시({@link ConcurrencyDiagnostics#lockWaitCount} — 같은 연결에서 pg_stat_clear_snapshot() 뒤)는 둘 다 보고, 대기가 없을 때는 0 이다.
 * 운영 코드는 쓰지 않는다 (예제 프로젝트 0 의 프로젝트 행만 잠깐 잠근다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PostgresLockWaitWatchIntegrationTest {

    private static final String WAITING = "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'";
    private static final String LOCK_ROW = "select PRJ_SN from TB_PRJ where PRJ_SN = 0 for update";

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("재현 : B 가 감시 첫 조회 뒤 새 연결(새 백엔드)로 기다리면 예전 감시(A 트랜잭션 연결의 pg_stat_activity)는 0 에 고정, 고친 감시는 1. 대기 전·A 종료 뒤에는 0")
    void newBackendAfterFirstPollIsInvisible() throws Exception {
        assumeTrue(postgres(), "PostgreSQL 전용");
        int[] seen = scenario(true);
        assertThat(seen[0]).as("대기 전 고친 감시 — 잘못 감지하지 않는다").isZero();
        assertThat(seen[1]).as("대기 전 예전 감시").isZero();
        assertThat(seen[2]).as("B 는 실제로 A 의 잠금을 기다렸다 (다른 연결로 확인)").isEqualTo(1);
        assertThat(seen[3]).as("예전 감시 : 첫 조회 뒤 연결된 B 는 A 트랜잭션 안에서 보이지 않는다 (결함)").isZero();
        assertThat(seen[4]).as("고친 감시 : 같은 연결에서 pg_stat_clear_snapshot() 뒤에는 보인다").isEqualTo(1);
        assertThat(seen[5]).as("B 를 막은 것은 A").isEqualTo(1);
        assertThat(seen[6]).as("A 가 끝난 뒤 B 는 행을 얻었다").isEqualTo(1);
        assertThat(seen[7]).as("A 가 끝난 뒤 고친 감시 — 잘못 감지하지 않는다").isZero();
    }

    @Test
    @DisplayName("대조 : B 가 첫 조회 전부터 있던 연결로 기다리면 예전 감시도 1 (M-8 19.11 의 탐침이 가설을 기각한 조건). 고친 감시도 1")
    void existingBackendIsVisible() throws Exception {
        assumeTrue(postgres(), "PostgreSQL 전용");
        int[] seen = scenario(false);
        assertThat(seen[0]).isZero();
        assertThat(seen[1]).isZero();
        assertThat(seen[2]).isEqualTo(1);
        assertThat(seen[3]).as("이미 목록에 있던 백엔드의 대기 상태는 다시 읽힌다").isEqualTo(1);
        assertThat(seen[4]).isEqualTo(1);
        assertThat(seen[5]).isEqualTo(1);
        assertThat(seen[6]).isEqualTo(1);
        assertThat(seen[7]).isZero();
    }

    /**
     * @param newBackend true = B 는 감시 첫 조회 뒤에 새로 연 연결, false = 첫 조회 전에 이미 열어 둔 연결
     * @return [고친 감시(대기 전), 예전 감시(대기 전), 다른 연결(B 대기), 예전 감시(재조회), 고친 감시, A 에 막힌 세션, B 행 획득, 고친 감시(A 종료 뒤)]
     */
    private int[] scenario(boolean newBackend) throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection preopened = newBackend ? null : open(hikari);
        int[] seen = new int[8];
        try {
            Future<Integer> waiter = new TransactionTemplate(transactionManager).execute(status -> {
                Long aPid = jdbc.queryForObject("select pg_backend_pid()", Long.class);
                jdbc.queryForObject(LOCK_ROW, Long.class);                                                  // A 가 행을 잠근다
                seen[0] = ConcurrencyDiagnostics.lockWaitCount(jdbc, WAITING, true);                        // 고친 감시 (대기 전)
                seen[1] = jdbc.queryForObject(WAITING, Integer.class);                                      // 예전 감시 첫 조회 — 이때의 백엔드 목록으로 고정된다
                Future<Integer> b = pool.submit(() -> {                                                     // B : 새 연결(첫 조회 뒤) 또는 미리 연 연결로 같은 행
                    try (Connection connection = newBackend ? open(hikari) : preopened; Statement statement = connection.createStatement()) {
                        connection.setAutoCommit(false);
                        try (ResultSet rows = statement.executeQuery(LOCK_ROW)) {
                            int got = rows.next() ? 1 : 0;
                            connection.commit();
                            return got;
                        }
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline && seen[2] == 0) {                                      // 다른 연결로 B 의 실제 대기 확인 (순서를 sleep 이 아니라 관찰로)
                    try {
                        seen[2] = CompletableFuture.supplyAsync(() -> jdbc.queryForObject(WAITING, Integer.class), pool).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                seen[3] = jdbc.queryForObject(WAITING, Integer.class);                                      // 예전 감시 재조회 (이 사이 A 의 연결에서 스냅숏을 바꾸지 않았다)
                seen[4] = ConcurrencyDiagnostics.lockWaitCount(jdbc, WAITING, true);                        // 고친 감시
                seen[5] = jdbc.queryForObject("select count(*) from pg_stat_activity a where ? = any(pg_blocking_pids(a.pid))", Integer.class, aPid);
                return b;
            });                                                                                            // A 커밋 → B 진행
            seen[6] = waiter.get(30, TimeUnit.SECONDS);
            seen[7] = ConcurrencyDiagnostics.lockWaitCount(jdbc, WAITING, true);
            System.out.println("[AES-D17 재현] B=" + (newBackend ? "첫 조회 뒤 새 연결" : "첫 조회 전부터 있던 연결") + " · 고친 감시(대기 전)=" + seen[0] + " · 예전 감시 첫 조회=" + seen[1]
                    + " · 다른 연결(B 실제 대기)=" + seen[2] + " · 예전 감시 재조회=" + seen[3] + " · 고친 감시=" + seen[4] + " · A 에 막힌 세션=" + seen[5]
                    + " · B 행 획득=" + seen[6] + " · A 끝난 뒤 고친 감시=" + seen[7]);
            return seen;
        } finally {
            pool.shutdownNow();
            if (preopened != null && !preopened.isClosed()) preopened.close();
        }
    }

    /** 풀 밖의 새 연결 (새 백엔드) — 시험용 계정·주소는 이 컨텍스트의 풀 설정 그대로, 출력하지 않는다 */
    private static Connection open(HikariDataSource hikari) throws java.sql.SQLException {
        return DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }

    /** 기존 동시성 테스트와 같은 판별 (H2 에는 version() 이 없어 예외 — PostgreSQL 아님) */
    private boolean postgres() {
        try {
            return jdbc.queryForObject("select version()", String.class).contains("PostgreSQL");
        } catch (Exception e) {
            return false;
        }
    }
}
