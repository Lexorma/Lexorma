package com.lexorma.source.tool;

import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 도구 실행 한 번의 컨텍스트 : 누가 어느 프로젝트에서 실행하는지(확인된 것)와 접속할 연결.
 * <ul>
 *   <li>{@link SourceToolExecutor} 만 만들 수 있다 (생성자가 패키지 안에만 열려 있다). 권한 확인을 거치지 않은 컨텍스트로는 도구를 실행할 수 없다</li>
 *   <li>JDBC URL 은 검증한 항목으로 서버가 만든 것이다. 계정·비밀번호는 URL 에 없다</li>
 *   <li>비밀번호는 {@link #password()} 를 부를 때 복호화한다 (필요한 순간에만 평문이 된다). 읽을 수 없으면 SECRET_* 오류다</li>
 *   <li>실행이 끝나면 버린다. 필드에 보관하거나 다른 스레드로 넘기지 않는다</li>
 *   <li>원본 DB 는 {@link #query} 로만 읽는다 (AI-2b) : 세션의 연결 하나·읽기 전용 트랜잭션·조회 제한 시간·취소가 함께 적용된다. 연결을 직접 내주지 않는다</li>
 * </ul>
 */
public final class SourceToolContext {

    /** 접속을 기다리는 시간 (초) */
    public static final int CONNECT_TIMEOUT_SECONDS = 10;
    /** 조회 하나를 기다리는 시간 (초) */
    public static final int QUERY_TIMEOUT_SECONDS = 30;

    private final VerifiedExecution execution;
    private final Long connectionId;
    private final DbType dbType;
    private final IdentifierComparison identifierComparison;
    private final String driverClassName;
    private final String jdbcUrl;
    private final String username;
    private final Map<String, String> options;
    private final Supplier<String> password;
    private final SourceToolSession session;

    SourceToolContext(VerifiedExecution execution, Long connectionId, DbType dbType, IdentifierComparison identifierComparison, String driverClassName,
                      String jdbcUrl, String username, Map<String, String> options, Supplier<String> password, SourceToolSession session) {
        this.execution = execution;
        this.connectionId = connectionId;
        this.dbType = dbType;
        this.identifierComparison = identifierComparison;
        this.driverClassName = driverClassName;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.options = Map.copyOf(options);
        this.password = password;
        this.session = session;
    }

    /** 실행 직전에 DB 로 확인한 실행 컨텍스트 (프로젝트, 요청자, 권한) */
    public VerifiedExecution execution() {
        return execution;
    }

    public Long projectId() {
        return execution.projectId();
    }

    public Long connectionId() {
        return connectionId;
    }

    public DbType dbType() {
        return dbType;
    }

    public IdentifierComparison identifierComparison() {
        return identifierComparison;
    }

    /** 이 DBMS 에 정해진 드라이버 (입력으로 받은 값이 아니다) */
    public String driverClassName() {
        return driverClassName;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return username;
    }

    /** 허용 목록을 거친 연결 옵션 */
    public Map<String, String> options() {
        return options;
    }

    /** 저장된 비밀번호 (없으면 null). 부를 때 복호화한다 — 로그·예외·결과에 넣지 않는다 */
    public String password() {
        return password.get();
    }

    /**
     * 코드에 정해 둔 조회 하나를 실행해 모든 행을 돌려준다. 값은 binder 로만 넣는다 (SQL 을 이어 붙이지 않는다).
     * 원본 DB 의 오류는 SOURCE_* 오류 코드로 바뀐다 (드라이버 메시지는 넘기지 않는다)
     */
    public <T> List<T> query(String sql, SourceToolSession.Binder binder, SourceToolSession.RowMapper<T> mapper) {
        return session.query(sql, binder, mapper);
    }

    /** 원본 DBMS 의 제품명·버전 (접속할 때 받은 값) */
    public String[] product() {
        return session.product();
    }

    /** 취소·시간 초과로 멈췄으면 그 사유로 실패한다 (긴 처리 사이에 부른다) */
    public void checkActive() {
        session.checkActive();
    }

    /** 비밀번호와 계정이 로그에 찍히지 않게 한다 */
    @Override
    public String toString() {
        return "SourceToolContext[projectId=" + projectId() + ", connectionId=" + connectionId + ", dbType=" + dbType + "]";
    }
}
