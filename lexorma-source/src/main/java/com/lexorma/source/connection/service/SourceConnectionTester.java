package com.lexorma.source.connection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.source.tool.SourceMetadata;
import com.lexorma.source.tool.SourceToolExecutor;
import com.lexorma.source.tool.SourceToolSession;
import com.lexorma.source.tool.SourceTools;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 연결 테스트 (AI-2b) : 원본 DB 에 실제로 접속해 메타데이터를 읽을 수 있는지 본다. 권한·호스트 정책·호출 제한은 수집과 같다 (SourceToolExecutor).
 * 접속·조회 실패는 응답의 오류 코드로 알린다 (드라이버 메시지·JDBC URL 은 넣지 않는다). 권한·검증·없는 연결·호출 제한은 HTTP 오류다.
 */
@Service
@RequiredArgsConstructor
public class SourceConnectionTester {

    /** 응답으로 알리는 접속·조회 실패 */
    private static final Set<ErrorCode> RESULT_CODES = EnumSet.of(ErrorCode.SOURCE_CONNECT_TIMEOUT, ErrorCode.SOURCE_CONNECT_FAILED, ErrorCode.SOURCE_AUTH_FAILED,
            ErrorCode.SOURCE_PERMISSION_DENIED, ErrorCode.SOURCE_TLS_FAILED, ErrorCode.SOURCE_DRIVER_NOT_AVAILABLE, ErrorCode.SOURCE_HOST_NOT_ALLOWED,
            ErrorCode.SOURCE_QUERY_TIMEOUT, ErrorCode.SOURCE_METADATA_FAILED, ErrorCode.SOURCE_TOOL_NOT_AVAILABLE);

    private final SourceToolExecutor tools;
    private final SourceConnectionService connections;
    private final SourceConnectionTestLimiter limiter;

    /** 저장된 연결 */
    public SourceConnectionDto.TestResult test(AccountDto accountDto, Long id) {
        ExecutionContext context = ExecutionContext.user(accountDto);
        return limited(context, work -> tools.session(context, id, work));
    }

    /** 저장하지 않은 설정 (등록·수정 화면). connectionId 는 수정 중인 연결 : 비밀번호를 비워 두면 저장된 것을 쓴다 (접속 대상이 같을 때만) */
    public SourceConnectionDto.TestResult testDraft(AccountDto accountDto, Long connectionId, SourceConnectionDto.Request request) {
        ExecutionContext context = ExecutionContext.user(accountDto);
        return limited(context, work -> {
            SourceConnectionService.Draft draft = connections.draft(accountDto, connectionId, request);
            return tools.session(context, draft.connection(), draft.password(), work);
        });
    }

    private SourceConnectionDto.TestResult limited(ExecutionContext context, Function<Function<SourceToolSession, SourceMetadata.ConnectionInfo>, SourceMetadata.ConnectionInfo> run) {
        AtomicReference<SourceToolSession> used = new AtomicReference<>();
        try (AutoCloseable ignored = limiter.acquire(context.accountId())) {
            SourceMetadata.ConnectionInfo info = run.apply(session -> {
                used.set(session);
                return session.run(SourceTools.CONNECTION_TEST, null);
            });
            return new SourceConnectionDto.TestResult(true, info.metadataReadable(), info.productName(), info.productVersion(), info.tls(), null, null);
        } catch (CustomException e) {
            if (!RESULT_CODES.contains(e.getErrorCode())) throw e;
            boolean connected = used.get() != null && used.get().connected();
            // 사유가 있으면 함께 알린다 (예 : 외부 연결 정책의 차단 사유)
            return new SourceConnectionDto.TestResult(connected, false, null, null, null, e.getErrorCode().name(), e.getCustomMessage());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
