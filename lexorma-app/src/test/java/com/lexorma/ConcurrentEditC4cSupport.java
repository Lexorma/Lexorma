package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M8-C4c-1 동시 실행 도구 (C1~C4b 와 같은 방식) : A 를 한 트랜잭션에서 실행하고 커밋 전에 B 를 다른 스레드(다른 DB 연결)에서 시작한다.
 * B 가 끝났거나 DB 잠금을 기다리기 시작한 것을 DB 세션 정보로 확인한 뒤 A 를 커밋한다 (sleep 으로 순서를 정하지 않는다). 결과 [A, B] = OK · 오류 코드 · 예외 이름 · SQLSTATE
 */
final class ConcurrentEditC4cSupport {

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ProjectRepository projectRepository;

    ConcurrentEditC4cSupport(TransactionTemplate tx, JdbcTemplate jdbc, DataSource dataSource, ProjectRepository projectRepository) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.projectRepository = projectRepository;
    }

    /** B 는 account 와 같은 계정·프로젝트로 로그인한 스레드에서 (role 그대로) */
    String[] both(AccountDto account, String role, Runnable a, Runnable b) throws Exception {
        return both(account, String.valueOf(account.getId()), role, a, b);
    }

    /** B 를 다른 계정(bAccountId)으로 — 프로젝트 관리자 여부처럼 계정에 달린 권한을 나눌 때 */
    String[] both(AccountDto account, String bAccountId, String role, Runnable a, Runnable b) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<String>> other = new AtomicReference<>();
        try {
            String first = outcome(() -> tx.executeWithoutResult(s -> {
                a.run();
                other.set(pool.submit(() -> {
                    SecurityTestSupport.loginAs(projectRepository, account.getProjectId(), bAccountId, role);
                    try {
                        return outcome(b);
                    } finally {
                        SecurityTestSupport.clear();
                    }
                }));
                awaitDoneOrLockWait(other.get());
            }));
            String second = other.get() == null ? "NOT_STARTED" : other.get().get(30, TimeUnit.SECONDS);
            return new String[]{first, second};
        } finally {
            pool.shutdownNow();
        }
    }

    static String outcome(Runnable call) {
        try {
            call.run();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof CustomException c) return c.getErrorCode().name();
            }
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException s && s.getSQLState() != null) return e.getClass().getSimpleName() + "/SQLSTATE:" + s.getSQLState();
            }
            return e.getClass().getSimpleName();
        }
    }

    static void report(String label, String[] result) {
        System.out.println("[M8-C4c] " + label + " : A=" + result[0] + " B=" + result[1]);
    }

    private void awaitDoneOrLockWait(Future<?> work) {
        String sql = postgres() ? "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select count(*) from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (work.isDone()) return;
            if (jdbc.queryForObject(sql, Integer.class) > 0) return;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("B 가 끝나지도, 잠금을 기다리지도 않았다"
                + ConcurrencyDiagnostics.describe("C4c B 대기", started, jdbc, dataSource, projectRepository));
    }

    boolean postgres() {
        try {
            return jdbc.queryForObject("select version()", String.class).contains("PostgreSQL");
        } catch (Exception e) {
            return false;
        }
    }
}
