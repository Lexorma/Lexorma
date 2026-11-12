package com.lexorma.source.tool;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.crypto.SecretCipher;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.ExecutionType;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.jdbc.SourceHostPolicy;
import com.lexorma.source.connection.jdbc.SourceJdbcSettings;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.connection.service.SourceConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

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
 * LLM 이 필요 없다 : 사용자의 최신화 요청, 비동기 작업, 정기 배치가 이 실행기를 직접 부른다.
 * AI 의 Tool Calling 이 이 실행기를 부르게 하지 않는다 — 원본 DB 에 접속하는 도구는 AI 에 공개하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SourceToolExecutor {

    private final SourceToolRegistry registry;
    private final ExecutionContextVerifier verifier;
    private final SourceConnectionRepository connectionRepository;
    private final SecretCipher secretCipher;
    private final SourceHostPolicy hostPolicy;

    /**
     * @param context      서버가 만든 실행 컨텍스트 (사용자 요청은 로그인 세션에서, 비동기·배치는 작업에 저장한 값에서)
     * @param connectionId 접속할 연결. 컨텍스트의 프로젝트에 속해야 한다
     */
    public <I, O> O execute(String toolName, ExecutionContext context, Long connectionId, I input) {
        VerifiedExecution execution = context.type() == ExecutionType.SCHEDULED ? verifier.verify(context) : verifier.verifyProjectManager(context);
        SourceConnection connection = (connectionId == null ? java.util.Optional.<SourceConnection>empty()
                : connectionRepository.findByIdAndProjectId(connectionId, execution.projectId())).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        if (!Boolean.TRUE.equals(connection.getEnabled())) throw unavailable("사용 중지된 연결입니다.");
        if (!hostPolicy.isAllowed(connection.getHost())) throw unavailable("이 호스트로는 접속할 수 없습니다. 운영자가 허용한 호스트만 사용할 수 있습니다.");

        SourceMetadataTool<I, O> tool = tool(toolName, connection, input);
        Map<String, String> options = SourceConnectionService.options(connection);
        String encrypted = connection.getEncryptedPassword();
        SourceToolContext toolContext = new SourceToolContext(execution, connection.getId(), connection.getDbType(), connection.getIdentifierComparison(),
                SourceJdbcSettings.driverClassName(connection.getDbType()),
                SourceJdbcSettings.jdbcUrl(connection.getDbType(), connection.getHost(), connection.getPort(), connection.getDatabaseName(), connection.getDatabaseNameType(), options),
                connection.getUsername(), options,
                () -> encrypted == null ? null : secretCipher.decrypt(encrypted, SourceConnectionService.scope(connection)));
        return tool.execute(toolContext, input);
    }

    @SuppressWarnings("unchecked")
    private <I, O> SourceMetadataTool<I, O> tool(String toolName, SourceConnection connection, I input) {
        SourceMetadataTool<?, ?> found = registry.find(toolName, connection.getDbType())
                .orElseThrow(() -> unavailable("실행할 수 없는 도구입니다 : {0}", toolName + " (" + connection.getDbType() + ")"));
        // 이름으로 찾은 도구가 받는 입력인지 확인한 뒤에만 넘긴다
        if (input != null && !found.inputType().isInstance(input)) throw unavailable("도구의 입력이 올바르지 않습니다 : {0}", toolName);
        return (SourceMetadataTool<I, O>) found;
    }

    private static CustomException unavailable(String message, Object... args) {
        return new CustomException(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE, message, args);
    }
}
