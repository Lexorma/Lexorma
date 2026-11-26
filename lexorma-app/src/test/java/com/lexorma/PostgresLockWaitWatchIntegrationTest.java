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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-8 동시성 테스트의 잠금 대기 감시 결함 결정적 재현 (AI 공통 실행 안정성 최종 보완 AES-D17 — modeling-concurrency-m8.md 23장, DB-1 3차 s4TwoRenames 감시 — 24장).
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

    @Test
    @DisplayName("재현 (s4TwoRenames 감시) : 예전 감시는 DB 의 다른 세션 대기와 '직전 문장 + 지금 대기' 행을 B 의 대기로 읽는다. 고친 감시는 B 의 PID·A 의 차단·같은 문장 두 번으로 B 의 실제 대기 문장만 본다")
    void staleStatementAndForeignWait() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        long advisory = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection a = open(hikari); Connection b = open(hikari); Connection d = open(hikari); Connection c = open(hikari); Connection w = open(hikari)) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            w.setAutoCommit(false);
            int aPid = ConcurrencyDiagnostics.backendPid(a), bPid = ConcurrencyDiagnostics.backendPid(b), cPid = ConcurrencyDiagnostics.backendPid(c);
            query(a, LOCK_ROW);                                                                              // A : 프로젝트 행을 쥔다
            query(b, "select count(*) from TB_TBL where PRJ_SN = 0");                                        // B : 첫 문장 (잠금 없음) 뒤 idle in transaction
            // 관련 없는 세션 C 가 D 의 권고 잠금을 기다린다 (같은 DB 의 다른 테스트·세션 대기에 해당)
            query(d, "select pg_advisory_lock(" + advisory + ")");
            Future<?> foreign = pool.submit(() -> query(c, "select pg_advisory_lock(" + advisory + ")"));
            awaitBlocked(cPid);
            List<String> old = oldWatch();
            Map<Integer, String> fixedBefore = ConcurrencyDiagnostics.confirmedLockWaits(jdbc, Set.of(bPid), Set.of(aPid));
            // 감시 W 는 트랜잭션 안에서 한 번 읽어 상태 복사본을 고정한다 (자동 커밋 한 문장 안의 '복사 → 실시간 읽기' 창을 결정적으로 넓힌 것)
            query(w, "select count(*) from pg_stat_activity");
            Future<?> waiting = pool.submit(() -> {                                                          // B : 다음 문장으로 프로젝트 행을 기다린다
                query(b, "select PRJ_SN from TB_PRJ where PRJ_SN = 0 for no key update");
                b.commit();
                return null;
            });
            awaitBlocked(bPid);
            String[] stale = staleRow(w, bPid);
            Map<Integer, String> fixed = ConcurrencyDiagnostics.confirmedLockWaits(jdbc, Set.of(bPid), Set.of(aPid));
            Map<Integer, String> otherBlocker = ConcurrencyDiagnostics.confirmedLockWaits(jdbc, Set.of(bPid), Set.of(cPid));
            Map<Integer, String> anySession = ConcurrencyDiagnostics.confirmedLockWaits(jdbc, Set.of(), Set.of());
            a.commit();                                                                                      // A 가 끝나면 B 는 진행한다
            waiting.get(30, TimeUnit.SECONDS);
            Map<Integer, String> fixedAfter = ConcurrencyDiagnostics.confirmedLockWaits(jdbc, Set.of(bPid), Set.of(aPid));
            query(d, "select pg_advisory_unlock(" + advisory + ")");
            foreign.get(30, TimeUnit.SECONDS);
            query(c, "select pg_advisory_unlock(" + advisory + ")");
            w.rollback();
            System.out.println("[DB-1 3차 감시 재현] 예전 감시(B 대기 전)=" + old.size() + "건 · 고친 감시(B 대기 전)=" + fixedBefore.size()
                    + " · 고정 스냅숏의 B 행 state=" + stale[0] + " wait=" + stale[1] + " · 고친 감시(B 대기)=" + fixed.size() + " · 다른 차단자 기준=" + otherBlocker.size()
                    + " · PID 없이(모든 세션)=" + anySession.size() + " · A 종료 뒤=" + fixedAfter.size());
            assertThat(old).as("예전 감시 : B 가 아직 기다리지 않는데 다른 세션의 대기를 대기로 본다 (결함)").anySatisfy(q -> assertThat(q).containsIgnoringCase("pg_advisory_lock"));
            assertThat(fixedBefore).as("고친 감시 : B 의 PID·A 의 차단만 — 다른 세션 대기는 B 의 대기가 아니다").isEmpty();
            assertThat(stale[0]).as("상태 복사본 : B 는 '직전 문장' 상태로 보인다").isEqualTo("idle in transaction");
            assertThat(stale[1]).as("대기 정보는 실시간 : 같은 행이 지금의 잠금 대기를 보여 준다").isEqualTo("Lock");
            assertThat(stale[2]).as("예전 감시가 B 의 대기 문장으로 기록하던 것 = B 의 직전 문장 (s4 실패 증상)").containsIgnoringCase("tb_tbl").doesNotContainIgnoringCase("tb_prj");
            assertThat(fixed).as("고친 감시 : B 가 A 에 막혀 기다리는 실제 문장").containsOnlyKeys(bPid);
            assertThat(fixed.get(bPid)).containsIgnoringCase("tb_prj").containsIgnoringCase("for no key update");
            assertThat(otherBlocker).as("A 가 아닌 세션을 차단자로 주면 B 의 대기로 인정하지 않는다").isEmpty();
            assertThat(anySession).as("PID 없이 보면 B 와 C 둘 다 대기 (차단 관계로 거르는 이유)").containsKeys(bPid, cPid);
            assertThat(fixedAfter).as("A 가 끝난 뒤 — 잘못 감지하지 않는다").isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    /** 예전 RelationSync 감시 문장 그대로 (PID·state·차단 관계를 보지 않는다) */
    private List<String> oldWatch() {
        return jdbc.queryForList("select left(query, 600) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()", String.class);
    }

    /** 고정된 스냅숏(W 의 트랜잭션)으로 본 B 의 행 : [state, wait_event_type, query] */
    private static String[] staleRow(Connection w, int pid) throws java.sql.SQLException {
        try (Statement statement = w.createStatement();
             ResultSet rows = statement.executeQuery("select state, wait_event_type, left(query, 600) from pg_stat_activity where pid = " + pid)) {
            rows.next();
            return new String[]{rows.getString(1), rows.getString(2), rows.getString(3)};
        }
    }

    /** pid 가 실제로 잠금을 기다릴 때까지 (다른 연결, 스냅숏 초기화 뒤 — 순서를 sleep 이 아니라 관찰로) */
    private void awaitBlocked(int pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer n = jdbc.queryForObject("select count(*) from pg_stat_activity where pid = ? and cardinality(pg_blocking_pids(pid)) > 0", Integer.class, pid);
            if (n != null && n > 0) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("세션이 잠금을 기다리지 않았다");
    }

    private static Object query(Connection connection, String sql) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getObject(1) : null;
        }
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
                seen[0] = ConcurrencyDiagnostics.lockWaitCount(jdbc, WAITING);                        // 고친 감시 (대기 전)
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
                seen[4] = ConcurrencyDiagnostics.lockWaitCount(jdbc, WAITING);                        // 고친 감시
                seen[5] = jdbc.queryForObject("select count(*) from pg_stat_activity a where ? = any(pg_blocking_pids(a.pid))", Integer.class, aPid);
                return b;
            });                                                                                            // A 커밋 → B 진행
            seen[6] = waiter.get(30, TimeUnit.SECONDS);
            seen[7] = ConcurrencyDiagnostics.lockWaitCount(jdbc, WAITING);
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

}
