package com.lexorma.config;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

import java.sql.SQLException;

/**
 * 관리 DB 때문에 기동하지 못했을 때 원인을 짧게 알려 준다 (DB-1). 연결은 다시 시도하지 않고 받은 예외만 읽는다.
 * <ul>
 *   <li>{@link ManagedDatabaseCheck.ManagedDatabaseConfigurationException} : 설정 확인 결과 그대로</li>
 *   <li>Hikari 의 URL 형식 거절 (URL 원문을 문구에 넣지 않는다)</li>
 *   <li>첫 연결의 {@link SQLException} : SQLSTATE 로 연결 불가 · 연결 거부(08004) · 인증 실패 · DB 없음 · 연결 수 초과를 나눈다. 드라이버 문구는 비밀번호를 담지 않는다 (사용자명·호스트는 담는다)</li>
 * </ul>
 * 등록 : META-INF/spring.factories
 */
public class ManagedDatabaseFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ManagedDatabaseCheck.ManagedDatabaseConfigurationException config) {
                return new FailureAnalysis(config.getMessage(), config.getAction(), config);
            }
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            // Hikari : 드라이버가 URL 형식을 받지 않았다 (예 : 포트가 숫자가 아님). URL 에는 인증정보가 없다 (ManagedDatabaseCheck 가 먼저 거절)
            if (cause.getMessage() != null && cause.getMessage().startsWith("Driver org.postgresql.Driver claims to not accept jdbcUrl")) {
                return new FailureAnalysis("관리 DB JDBC URL 형식이 잘못됐습니다 (PostgreSQL 드라이버가 해석하지 못함).",
                        "spring.datasource.url 을 jdbc:postgresql://<호스트>:<포트>/<DB>[?옵션] 형식으로 지정하세요.", cause);
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String reason = reason(sql.getSQLState());
                if (reason == null) return null;
                return new FailureAnalysis("관리 DB(PostgreSQL)에 연결하지 못했습니다 : " + reason + " (SQLSTATE " + sql.getSQLState() + ") — " + sql.getMessage(),
                        "spring.datasource.url · username · password 와 PostgreSQL 서버 상태를 확인하세요. 관리 DB 는 미리 만들어 두어야 합니다 (스키마는 Flyway 가 만든다).", sql);
            }
        }
        return null;
    }

    private static String reason(String state) {
        // 08004 : 서버가 연결을 거부했다 — 비밀번호가 필요한데 주지 않았거나 pg_hba.conf 가 막는다 (드라이버 판단)
        if (state.equals("08004")) return "서버가 연결을 거부함 (비밀번호 미지정 · 인증 방식 · pg_hba.conf)";
        if (state.startsWith("08")) return "서버에 연결할 수 없음 (호스트 · 포트 · 방화벽 · SSL)";
        return switch (state) {
            case "28P01", "28000" -> "인증 실패 (사용자명 · 비밀번호 · 인증 방식)";
            case "3D000" -> "데이터베이스가 없음";
            case "53300" -> "서버의 연결 수 초과";
            case "42501" -> "권한 없음";
            default -> null;
        };
    }
}
