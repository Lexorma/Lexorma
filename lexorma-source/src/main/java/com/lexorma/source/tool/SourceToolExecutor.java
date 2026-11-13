package com.lexorma.source.tool;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.crypto.SecretCipher;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.ExecutionType;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.jdbc.SourceAddressPolicy;
import com.lexorma.source.connection.jdbc.SourceHostPolicy;
import com.lexorma.source.connection.jdbc.SourceJdbcSettings;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.connection.service.SourceConnectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 내부 도구를 실행하는 단 하나의 길. 도구를 부르기 전에 항상 같은 확인을 한다 :
 * <ol>
 *   <li><b>실행 컨텍스트 재검증</b> (DB) — 프로젝트가 있고 활성인지, 요청자가 지금도 그 프로젝트의 관리자 또는 ADMIN 인지.
 *       세션 없이 도는 비동기 작업도 같다. 정기 배치(요청자 없음)는 프로젝트 상태만 본다</li>
 *   <li><b>연결</b> — 컨텍스트의 프로젝트에 속한 연결만 찾는다 (다른 프로젝트의 연결은 ID 를 알아도 없다). 사용 중지된 연결로는 실행하지 않는다</li>
 *   <li><b>접속 범위</b> — 운영자가 정한 호스트 범위를 실행 시점에 다시 본다 (등록한 뒤 정책이 바뀌었을 수 있다)</li>
 *   <li><b>도구</b> — 이름과 DBMS 로 등록된 도구를 찾는다. 없으면(미지원 DBMS 포함) 실행하지 않는다</li>
 * </ol>
 * 그 뒤에 접속 정보를 담은 {@link SourceToolContext} 를 만들어 도구에 넘긴다. JDBC URL 은 저장된 항목으로 여기서 만든다.
 * <p>
 * <b>원본 DB 접속 (AI-2b)</b> : {@link #session} 하나가 JDBC 연결 하나다 (Lexorma 의 DataSource·JPA·트랜잭션과 무관, 풀 없음). 도구가 처음 조회할 때
 * 드라이버(DBMS 마다 정해진 것)로 직접 연다 — 접속 직전에 이름이 풀리는 주소를 {@link SourceAddressPolicy} 로 보고, 실제 소켓도 연결하는 주소를 다시 본다.
 * 연결은 읽기 전용·REPEATABLE READ 이고 세션이 끝나면 되돌리고 닫는다.
 * <p>
 * LLM 이 필요 없다 : 사용자의 최신화 요청, 비동기 작업, 정기 배치가 이 실행기를 직접 부른다.
 * AI 의 Tool Calling 이 이 실행기를 부르게 하지 않는다 — 원본 DB 에 접속하는 도구는 AI 에 공개하지 않는다.
 */
@Service
public class SourceToolExecutor {

    private final SourceToolRegistry registry;
    private final ExecutionContextVerifier verifier;
    private final SourceConnectionRepository connectionRepository;
    private final SecretCipher secretCipher;
    private final SourceHostPolicy hostPolicy;
    private final SourceAddressPolicy addressPolicy;
    private final int connectTimeoutSeconds;
    private final int queryTimeoutSeconds;

    public SourceToolExecutor(SourceToolRegistry registry, ExecutionContextVerifier verifier, SourceConnectionRepository connectionRepository, SecretCipher secretCipher,
                              SourceHostPolicy hostPolicy, SourceAddressPolicy addressPolicy,
                              @Value("${lexorma.source.jdbc.connect-timeout-seconds:" + SourceToolContext.CONNECT_TIMEOUT_SECONDS + "}") int connectTimeoutSeconds,
                              @Value("${lexorma.source.jdbc.query-timeout-seconds:" + SourceToolContext.QUERY_TIMEOUT_SECONDS + "}") int queryTimeoutSeconds) {
        this.registry = registry;
        this.verifier = verifier;
        this.connectionRepository = connectionRepository;
        this.secretCipher = secretCipher;
        this.hostPolicy = hostPolicy;
        this.addressPolicy = addressPolicy;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * 도구 하나를 실행한다 (세션 하나에 도구 하나).
     *
     * @param context      서버가 만든 실행 컨텍스트 (사용자 요청은 로그인 세션에서, 비동기·배치는 작업에 저장한 값에서)
     * @param connectionId 접속할 연결. 컨텍스트의 프로젝트에 속해야 한다
     */
    public <I, O> O execute(String toolName, ExecutionContext context, Long connectionId, I input) {
        return session(context, connectionId, session -> session.run(toolName, input));
    }

    /**
     * 저장된 연결 하나로 세션을 열어 work 를 실행한다. work 안에서 도구를 여러 번 실행해도 JDBC 연결은 하나다. work 가 끝나면(예외 포함) 연결을 닫는다.
     */
    public <T> T session(ExecutionContext context, Long connectionId, Function<SourceToolSession, T> work) {
        VerifiedExecution execution = verify(context);
        SourceConnection connection = (connectionId == null ? java.util.Optional.<SourceConnection>empty()
                : connectionRepository.findByIdAndProjectId(connectionId, execution.projectId())).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        if (!Boolean.TRUE.equals(connection.getEnabled())) throw new CustomException(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE, "사용 중지된 연결입니다.");
        String encrypted = connection.getEncryptedPassword();
        return open(execution, connection, () -> encrypted == null ? null : secretCipher.decrypt(encrypted, SourceConnectionService.scope(connection)), work);
    }

    /**
     * 저장하지 않은 연결 설정으로 세션을 연다 (등록·수정 화면의 연결 테스트). 요청자는 지금 그 프로젝트의 관리자이거나 ADMIN 이어야 한다.
     *
     * @param draft    검증을 거친 설정 (저장하지 않은 엔티티). 컨텍스트의 프로젝트 것이어야 한다
     * @param password 접속에 쓸 비밀번호 (부를 때 꺼낸다)
     */
    public <T> T session(ExecutionContext context, SourceConnection draft, Supplier<String> password, Function<SourceToolSession, T> work) {
        VerifiedExecution execution = verifier.verifyProjectManager(context);
        if (draft == null || !Objects.equals(draft.getProjectId(), execution.projectId())) throw new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        return open(execution, draft, password, work);
    }

    private VerifiedExecution verify(ExecutionContext context) {
        return context.type() == ExecutionType.SCHEDULED ? verifier.verify(context) : verifier.verifyProjectManager(context);
    }

    private <T> T open(VerifiedExecution execution, SourceConnection connection, Supplier<String> password, Function<SourceToolSession, T> work) {
        if (!hostPolicy.isAllowed(connection.getHost())) throw new CustomException(ErrorCode.SOURCE_HOST_NOT_ALLOWED);
        DbType dbType = connection.getDbType();
        Map<String, String> options = SourceConnectionService.options(connection);
        String url = SourceJdbcSettings.jdbcUrl(dbType, connection.getHost(), connection.getPort(), connection.getDatabaseName(), connection.getDatabaseNameType(), options);
        SourceToolSession session = new SourceToolSession(registry, dbType, () -> connect(connection, url, password), queryTimeoutSeconds);
        session.bind(new SourceToolContext(execution, connection.getId(), dbType, connection.getIdentifierComparison(), SourceJdbcSettings.driverClassName(dbType), url,
                connection.getUsername(), options, password, session));
        try (session) {
            return work.apply(session);
        }
    }

    /** 실제 접속 : 드라이버·주소 정책을 확인하고 연결을 읽기 전용·REPEATABLE READ 로 둔다 */
    private Connection connect(SourceConnection connection, String url, Supplier<String> password) throws SQLException {
        DbType dbType = connection.getDbType();
        if (dbType != DbType.POSTGRESQL || !SourceJdbcSettings.isDriverAvailable(dbType)) throw new CustomException(ErrorCode.SOURCE_DRIVER_NOT_AVAILABLE);
        if (!addressPolicy.isAllowed(connection.getHost())) throw new CustomException(ErrorCode.SOURCE_HOST_NOT_ALLOWED);
        Driver driver;
        try {
            driver = (Driver) Class.forName(SourceJdbcSettings.driverClassName(dbType)).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new CustomException(ErrorCode.SOURCE_DRIVER_NOT_AVAILABLE);
        }
        Connection jdbc = driver.connect(url, SourceJdbcSettings.connectionProperties(dbType, connection.getUsername(), password.get(), connectTimeoutSeconds,
                queryTimeoutSeconds, addressPolicy.allowedNetworks()));
        if (jdbc == null) throw new CustomException(ErrorCode.SOURCE_DRIVER_NOT_AVAILABLE);
        try {
            jdbc.setAutoCommit(false);
            jdbc.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            jdbc.setReadOnly(true);
            return jdbc;
        } catch (SQLException | RuntimeException e) {
            jdbc.close();
            throw e;
        }
    }
}
