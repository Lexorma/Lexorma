package com.lexorma.source.connection.jdbc;

import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.network.PinnedSocket;

import javax.net.ssl.SSLException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 원본 DB 의 JDBC 오류 → 사용자에게 알릴 오류 코드 (AI-2b). 드라이버의 메시지는 접속 정보·SQL 을 담을 수 있어 응답·실행 기록에 넣지 않는다 : 코드만 고른다.
 * SQLSTATE 로 먼저 나누고, 접속 단계의 오류는 원인(TLS·시간 초과·접속 정책)으로 나눈다.
 */
public final class SourceJdbcErrors {

    private SourceJdbcErrors() {
    }

    /**
     * @param connected 접속이 끝난 뒤(조회 중)의 오류인지 : 같은 네트워크 시간 초과라도 접속 중이면 접속 시간 초과, 조회 중이면 조회 시간 초과다
     */
    public static ErrorCode classify(Throwable error, boolean connected) {
        if (PinnedSocket.isBlocked(error)) return ErrorCode.SOURCE_HOST_NOT_ALLOWED;
        String state = error instanceof SQLException sql && sql.getSQLState() != null ? sql.getSQLState() : "";
        switch (state) {
            case "28000", "28P01":
                return ErrorCode.SOURCE_AUTH_FAILED;
            case "42501":
                return ErrorCode.SOURCE_PERMISSION_DENIED;
            case "57014":
                return ErrorCode.SOURCE_QUERY_TIMEOUT;
            // 서버가 연결을 끊었다 (관리자 종료·서버 종료·재시작)
            case "57P01", "57P02", "57P03":
                return ErrorCode.SOURCE_CONNECT_FAILED;
            // 조회 중에 객체가 지워졌다 (카탈로그 함수의 cache lookup failed, 없는 객체), 동시 변경으로 직렬화 실패
            case "XX000", "42P01", "42703", "42704", "40001":
                return connected ? ErrorCode.SOURCE_CHANGED_DURING_COLLECTION : ErrorCode.SOURCE_CONNECT_FAILED;
            default:
                break;
        }
        if (has(error, SSLException.class) || has(error, CertificateException.class)) return ErrorCode.SOURCE_TLS_FAILED;
        if (state.startsWith("08") || !connected) {
            if (has(error, SocketTimeoutException.class)) return connected ? ErrorCode.SOURCE_QUERY_TIMEOUT : ErrorCode.SOURCE_CONNECT_TIMEOUT;
            // 서버가 TLS 를 지원하지 않는데 sslmode=require 이상 : 원인 예외 없이 메시지로만 알려 온다
            if (!connected && error.getMessage() != null && error.getMessage().toUpperCase(Locale.ROOT).contains("SSL")) return ErrorCode.SOURCE_TLS_FAILED;
            return ErrorCode.SOURCE_CONNECT_FAILED;
        }
        if (has(error, SocketTimeoutException.class)) return ErrorCode.SOURCE_QUERY_TIMEOUT;
        return ErrorCode.SOURCE_METADATA_FAILED;
    }

    /** 로그에 남길 수 있는 정도 : 예외 종류와 SQLSTATE (메시지는 남기지 않는다) */
    public static String describe(Throwable error) {
        String state = error instanceof SQLException sql ? sql.getSQLState() : null;
        return error.getClass().getSimpleName() + (state == null ? "" : "[" + state + "]");
    }

    private static boolean has(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }
}
