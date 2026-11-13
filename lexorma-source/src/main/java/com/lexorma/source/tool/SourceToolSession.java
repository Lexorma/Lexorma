package com.lexorma.source.tool;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.connection.jdbc.SourceJdbcErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 원본 DB 세션 하나 (AI-2b) : 실행기가 확인한 연결 하나에 대해 여러 도구를 차례로 실행한다. {@link SourceToolExecutor#session} 만 만든다.
 * <ul>
 *   <li><b>JDBC 연결은 하나</b>, 처음 조회할 때 연다 (DB 를 읽지 않는 도구는 접속하지 않는다). 도구마다 새로 열지 않는다</li>
 *   <li>읽기 전용 트랜잭션(REPEATABLE READ) 하나에서 모든 조회가 돈다 : 같은 스냅숏을 본다. 끝나면 되돌리고(rollback) 닫는다</li>
 *   <li>조회마다 제한 시간(Statement query timeout)이 있다. 전체 제한 시간·사용자 취소는 다른 스레드가 {@link #abort} 로 알린다 :
 *       실행 중인 조회를 취소하고 연결을 끊는다 (스레드 interrupt 로는 JDBC 조회가 멈추지 않는다)</li>
 *   <li>이 세션 밖으로 연결을 내주지 않는다. 도구는 {@link SourceToolContext#query} 로만 조회한다</li>
 * </ul>
 */
public final class SourceToolSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SourceToolSession.class);
    private static final int FETCH_SIZE = 2000;

    /** 접속 : 실행기가 접속 정책을 확인한 뒤 연다 */
    interface Opener {
        Connection open() throws SQLException;
    }

    /** 조회 값 넣기 */
    @FunctionalInterface
    public interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private final SourceToolRegistry registry;
    private final DbType dbType;
    private final Opener opener;
    private final int queryTimeoutSeconds;
    private final Object openLock = new Object();
    private final AtomicReference<ErrorCode> abortReason = new AtomicReference<>();
    private SourceToolContext context;
    private volatile Connection jdbc;
    private volatile PreparedStatement current;
    private volatile boolean closed;
    private int statementCount;

    SourceToolSession(SourceToolRegistry registry, DbType dbType, Opener opener, int queryTimeoutSeconds) {
        this.registry = registry;
        this.dbType = dbType;
        this.opener = opener;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    void bind(SourceToolContext context) {
        this.context = context;
    }

    /**
     * 도구 하나를 실행한다. 이름과 이 연결의 DBMS 로 등록된 도구만, 그 도구가 받는 입력으로만 실행한다.
     *
     * @throws CustomException SOURCE_TOOL_NOT_AVAILABLE : 없는 도구·미지원 DBMS·맞지 않는 입력. 취소·시간 초과 뒤에는 그 사유
     */
    @SuppressWarnings("unchecked")
    public <I, O> O run(String toolName, I input) {
        checkActive();
        SourceMetadataTool<?, ?> found = registry.find(toolName, dbType)
                .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE, "실행할 수 없는 도구입니다 : {0}", toolName + " (" + dbType + ")"));
        boolean noInput = found.inputType() == Void.class;
        if (noInput ? input != null : input == null || !found.inputType().isInstance(input)) {
            throw new CustomException(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE, "도구의 입력이 올바르지 않습니다 : {0}", toolName);
        }
        return ((SourceMetadataTool<I, O>) found).execute(context, input);
    }

    /**
     * 진행 중인 세션을 멈춘다 (다른 스레드에서 부른다 : 전체 제한 시간, 사용자 취소, 서버 종료). 실행 중인 조회를 취소하고 연결을 끊는다.
     * 그 뒤의 조회·도구 실행은 이 사유로 실패한다. 처음 알린 사유만 남는다.
     */
    public void abort(ErrorCode reason) {
        abortReason.compareAndSet(null, reason);
        PreparedStatement statement = current;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException | RuntimeException e) {
                log.debug("원본 DB 조회를 취소하지 못했습니다 : {}", SourceJdbcErrors.describe(e));
            }
        }
        Connection connection = jdbc;
        if (connection != null) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException e) {
                log.debug("원본 DB 연결을 끊지 못했습니다 : {}", SourceJdbcErrors.describe(e));
            }
        }
    }

    /** 멈춘 사유 (없으면 null) */
    public ErrorCode abortReason() {
        return abortReason.get();
    }

    /** 이 세션이 원본 DB 에 보낸 조회 수 */
    public int statementCount() {
        return statementCount;
    }

    /** 멈췄으면 그 사유로 실패한다 */
    public void checkActive() {
        ErrorCode reason = abortReason.get();
        if (reason != null) throw new CustomException(reason);
    }

    /** 원본 DB 제품 정보 (드라이버가 접속할 때 받은 값 : 조회를 보내지 않는다) */
    String[] product() {
        try {
            var meta = connection().getMetaData();
            return new String[]{meta.getDatabaseProductName(), meta.getDatabaseProductVersion()};
        } catch (SQLException e) {
            throw failure(e, true);
        }
    }

    /** 정해진 조회 하나 : 제한 시간을 두고 실행하고, 결과를 모두 읽어 돌려준다 */
    <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        Connection connection = connection();
        List<T> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            current = statement;
            checkActive();
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setFetchSize(FETCH_SIZE);
            if (binder != null) binder.bind(statement);
            statementCount++;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapper.map(result));
                    if (rows.size() % FETCH_SIZE == 0) checkActive();
                }
            }
        } catch (SQLException e) {
            throw failure(e, true);
        } finally {
            current = null;
        }
        checkActive();
        return rows;
    }

    private Connection connection() {
        checkActive();
        Connection connection = jdbc;
        if (connection != null) return connection;
        synchronized (openLock) {
            if (jdbc == null) {
                if (closed) throw new CustomException(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE, "끝난 세션입니다.");
                try {
                    jdbc = opener.open();
                } catch (SQLException e) {
                    throw failure(e, false);
                }
                // 접속하는 동안 멈추라는 알림이 왔으면 곧바로 끊는다
                if (abortReason.get() != null) abort(abortReason.get());
            }
            checkActive();
            return jdbc;
        }
    }

    /** 원본 DB 의 오류 → 오류 코드 (멈춘 세션이면 그 사유가 먼저다). 메시지는 넘기지 않는다 */
    private CustomException failure(SQLException e, boolean connected) {
        ErrorCode reason = abortReason.get();
        ErrorCode code = reason != null ? reason : SourceJdbcErrors.classify(e, connected);
        log.warn("원본 DB 조회 실패 : code={}, cause={}", code, SourceJdbcErrors.describe(e));
        return new CustomException(code);
    }

    /** 읽기만 했으므로 되돌리고 닫는다. 이미 끊긴 연결이면 조용히 끝낸다 */
    @Override
    public void close() {
        synchronized (openLock) {
            closed = true;
            Connection connection = jdbc;
            if (connection == null) return;
            try {
                if (!connection.isClosed()) connection.rollback();
            } catch (SQLException e) {
                log.debug("원본 DB 트랜잭션을 되돌리지 못했습니다 : {}", SourceJdbcErrors.describe(e));
            }
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("원본 DB 연결을 닫지 못했습니다 : {}", SourceJdbcErrors.describe(e));
            }
        }
    }

    /** 원본 DB 에 접속한 적이 있는지 (접속 실패와 조회 실패를 나눈다) */
    public boolean connected() {
        return jdbc != null;
    }

    /** 세션이 끝났는지 (테스트·진단용 : 연결을 닫았는지) */
    public boolean isClosed() {
        try {
            Connection connection = jdbc;
            return closed && (connection == null || connection.isClosed());
        } catch (SQLException e) {
            return closed;
        }
    }
}
