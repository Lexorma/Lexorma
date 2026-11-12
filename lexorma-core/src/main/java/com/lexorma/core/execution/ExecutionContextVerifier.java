package com.lexorma.core.execution;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.service.ProjectAuthority;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 실행 컨텍스트를 실행 직전에 DB 로 다시 확인한다 (세션이 필요 없다 : 비동기·정기 배치에서도 같다).
 * <p>
 * HTTP 요청은 {@code ProjectAccessInterceptor} 가 요청마다 프로젝트 접근을 다시 확인하지만 세션 값(역할)은 로그인 때의 것이고,
 * 비동기·배치에는 인터셉터가 없다. 그래서 AI Tool 호출·수집·배치는 실행 방식과 상관없이 여기를 지난다 :
 * <ul>
 *   <li>프로젝트가 지워졌다 → PROJECT_NOT_FOUND / 비활성화됐다 → PROJECT_ACCESS_DENIED</li>
 *   <li>USER·ASYNC : 요청자 계정이 없거나 비활성, 멤버에서 빠졌거나 승인이 취소됐다 → PROJECT_ACCESS_DENIED</li>
 *   <li>필요한 역할이 지금 없다(역할 변경), 프로젝트 관리자·ADMIN 이 아니다 → EXECUTION_PERMISSION_DENIED</li>
 *   <li>SCHEDULED : 프로젝트가 있고 활성인지만 본다. 요청자가 없으므로 사람의 권한이 필요한 일(승인, 설정 변경)은 할 수 없다</li>
 * </ul>
 * 긴 작업은 시작할 때 한 번이 아니라 단계마다(Tool 호출마다, 적재 청크마다) 다시 부른다 : 도는 중에 프로젝트가 지워지거나 권한이 회수될 수 있다.
 */
@Service
@RequiredArgsConstructor
public class ExecutionContextVerifier {

    private final ProjectAuthority projectAuthority;

    /** 프로젝트에서 지금 실행할 수 있는지 (프로젝트 상태 + 요청자의 접근 권한) */
    @Transactional(readOnly = true)
    public VerifiedExecution verify(ExecutionContext context) {
        if (context == null) throw new IllegalArgumentException("실행 컨텍스트가 없습니다.");
        ProjectAuthority.Access access = projectAuthority.access(context.projectId(), context.accountId());
        if (!access.projectExists()) throw new CustomException(ErrorCode.PROJECT_NOT_FOUND);
        if (!access.projectActive()) throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
        if (context.type() == ExecutionType.SCHEDULED) {
            return new VerifiedExecution(context, null, VerifiedExecution.SCHEDULER, Set.of(), false);
        }
        if (!access.accessible()) throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
        return new VerifiedExecution(context, access.username(), access.name(), access.authorities(), access.manager());
    }

    /**
     * + 요청자가 지금 그 역할을 가졌는지 (역할 계층 적용).
     *
     * @param role ROLE_ 접두사를 뺀 역할명 (예: USER). 컨트롤러의 {@code @PreAuthorize("hasRole('USER')")} 와 같은 기준이다
     */
    @Transactional(readOnly = true)
    public VerifiedExecution verify(ExecutionContext context, String role) {
        VerifiedExecution execution = verify(context);
        // 정기 배치는 요청자가 없다 : 역할이 필요한 일을 배치로 돌릴지는 배치를 켤 때(사람의 요청) 확인한다
        if (context.type() != ExecutionType.SCHEDULED && !execution.hasRole(role)) throw new CustomException(ErrorCode.EXECUTION_PERMISSION_DENIED);
        return execution;
    }

    /** + 요청자가 프로젝트 관리자이거나 ADMIN 인지 (프로젝트 AI 설정, 외부 DB 연결 관리). 정기 배치로는 할 수 없다 */
    @Transactional(readOnly = true)
    public VerifiedExecution verifyProjectManager(ExecutionContext context) {
        VerifiedExecution execution = verify(context);
        if (context.type() == ExecutionType.SCHEDULED || !(execution.manager() || execution.hasRole("ADMIN"))) {
            throw new CustomException(ErrorCode.EXECUTION_PERMISSION_DENIED);
        }
        return execution;
    }
}
