package com.lexorma;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 동시성 테스트(A 의 트랜잭션 안에서 B 를 다른 스레드·연결로 실행)가 "B 가 끝나지도, DB 잠금을 기다리지도 않음" 으로 시간 초과할 때 남기는 진단 (M8-C4a).
 * 테스트 실패 메시지에만 쓰고 운영 코드에는 넣지 않는다. 연결 문자열·계정·비밀번호는 출력하지 않는다.
 * <ul>
 *   <li>정적 필드로 저장소를 쥔 유틸(AuthUtil)이 이 테스트 컨텍스트의 빈을 가리키는지 — 테스트 JVM 에는 설정이 다른 컨텍스트가 여럿 있어 마지막에 만든 컨텍스트가 덮어쓴다</li>
 *   <li>이 컨텍스트 Hikari 풀 active·idle·pending(연결을 기다리는 스레드)·total</li>
 *   <li>DB 세션 상태별 수와 목록 (PostgreSQL pg_stat_activity / H2 SESSIONS)</li>
 *   <li>모든 스레드 이름·상태·스택 (JVM 쪽에서 어디서 멈췄는지)</li>
 * </ul>
 */
final class ConcurrencyDiagnostics {

    private ConcurrencyDiagnostics() {
    }

    static String describe(String phase, long startedNanos, JdbcTemplate jdbc, DataSource dataSource, Object contextProjectRepository) {
        StringBuilder out = new StringBuilder();
        out.append("\n=== 동시성 진단 : ").append(phase).append(" · 경과 ").append(Duration.ofNanos(System.nanoTime() - startedNanos).toMillis()).append("ms ===");
        out.append("\n[AuthUtil.projectRepository 가 이 컨텍스트의 빈인가] ").append(authUtilSameContext(contextProjectRepository));
        out.append("\n[Hikari] ").append(hikari(dataSource));
        out.append("\n[DB 세션] ").append(sessions(jdbc));
        out.append("\n[스레드]").append(threads());
        return out.toString();
    }

    /** AuthUtil 의 정적 저장소 = 이 컨텍스트의 ProjectRepository 빈인지 (같은 프록시 객체인지) */
    static String authUtilSameContext(Object contextProjectRepository) {
        try {
            var field = com.lexorma.core.util.AuthUtil.class.getDeclaredField("projectRepository");
            field.setAccessible(true);
            Object current = field.get(null);
            return (current == contextProjectRepository) + " (AuthUtil=" + identity(current) + ", 컨텍스트=" + identity(contextProjectRepository) + ")";
        } catch (ReflectiveOperationException e) {
            return "확인 불가 : " + e.getClass().getSimpleName();
        }
    }

    private static String identity(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    private static String hikari(DataSource dataSource) {
        try {
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool == null) return "풀 없음";
            return "pool=" + hikari.getPoolName() + " active=" + pool.getActiveConnections() + " idle=" + pool.getIdleConnections()
                    + " pending=" + pool.getThreadsAwaitingConnection() + " total=" + pool.getTotalConnections() + " max=" + hikari.getMaximumPoolSize()
                    + " closed=" + hikari.isClosed();
        } catch (Exception e) {
            return "확인 불가 : " + e.getClass().getSimpleName();
        }
    }

    private static String sessions(JdbcTemplate jdbc) {
        try {
            boolean postgres = jdbc.queryForObject("select version()", String.class).contains("PostgreSQL");
            if (postgres) {
                List<Map<String, Object>> byState = jdbc.queryForList("select state, wait_event_type, count(*) as n from pg_stat_activity where backend_type = 'client backend' group by state, wait_event_type order by n desc");
                Integer max = jdbc.queryForObject("select setting::int from pg_settings where name = 'max_connections'", Integer.class);
                List<Map<String, Object>> rows = jdbc.queryForList("select pid, datname, state, wait_event_type, wait_event, now() - xact_start as xact_age, now() - state_change as state_age, left(query, 100) as query "
                        + "from pg_stat_activity where backend_type = 'client backend' order by state, pid");
                return "max_connections=" + max + " 전체=" + rows.size() + " 상태별=" + byState + "\n  " + String.join("\n  ", rows.stream().map(String::valueOf).toList());
            }
            return String.valueOf(jdbc.queryForList("select SESSION_ID, BLOCKER_ID, ISOLATION_LEVEL, left(EXECUTING_STATEMENT, 100) as STMT from INFORMATION_SCHEMA.SESSIONS"));
        } catch (Exception e) {
            return "확인 불가 : " + e.getClass().getSimpleName();
        }
    }

    private static String threads() {
        StringBuilder out = new StringBuilder();
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            String name = info.getThreadName();
            // JVM·Gradle 내부 스레드는 빼고 테스트·풀·Hikari·Spring 스레드만
            if (name.startsWith("Reference Handler") || name.startsWith("Finalizer") || name.startsWith("Signal Dispatcher") || name.startsWith("Common-Cleaner")
                    || name.startsWith("Notification Thread") || name.startsWith("Attach Listener") || name.startsWith("process reaper")) continue;
            out.append("\n[").append(name).append(' ').append(info.getThreadState());
            if (info.getLockName() != null) out.append(" lock=").append(info.getLockName());
            if (info.getLockOwnerName() != null) out.append(" owner=").append(info.getLockOwnerName());
            out.append(']');
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 30); i++) out.append("\n  at ").append(stack[i]);
        }
        return out.toString();
    }
}
