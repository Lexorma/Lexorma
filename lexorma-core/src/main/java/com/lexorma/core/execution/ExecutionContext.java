package com.lexorma.core.execution;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;

/**
 * 실행 컨텍스트 : AI Tool 호출·수집·배치가 "누구의 요청으로 어느 프로젝트에서" 도는지를 서버가 정해 들고 다니는 값.
 * <ul>
 *   <li>값만 담는다 (실행 방식, 프로젝트 ID, 요청자 계정 ID, 작업 ID). HTTP 세션·SecurityContext·엔티티를 담지 않으므로
 *       다른 스레드·작업 테이블로 넘길 수 있다</li>
 *   <li>바꿀 수 없다 (record). 프로젝트와 요청자는 만들 때 서버가 정하고, AI 모델이 준 값으로 만들거나 바꾸지 않는다 —
 *       Tool 의 인자에는 프로젝트 ID·계정 ID 를 두지 않는다</li>
 *   <li>여기 담긴 것은 "요청한 시점의 주장" 이다. 그 뒤에 프로젝트가 지워지거나 비활성화되고 권한이 회수될 수 있으므로
 *       실제로 실행하기 직전에 {@link ExecutionContextVerifier} 로 DB 에서 다시 확인한다</li>
 * </ul>
 *
 * @param accountId 요청자. SCHEDULED 는 null
 * @param jobId     작업 ID. USER 는 null 일 수 있다
 */
public record ExecutionContext(ExecutionType type, Long projectId, Long accountId, String jobId) {

    public ExecutionContext {
        if (type == null) throw new IllegalArgumentException("실행 방식이 없습니다.");
        if (projectId == null) throw new IllegalArgumentException("프로젝트가 없습니다.");
        if (type == ExecutionType.SCHEDULED) {
            if (accountId != null) throw new IllegalArgumentException("정기 배치에는 요청자가 없습니다.");
        } else if (accountId == null) {
            throw new IllegalArgumentException("요청자가 없습니다.");
        }
        if (type != ExecutionType.USER && (jobId == null || jobId.isBlank())) throw new IllegalArgumentException("작업 ID 가 없습니다.");
    }

    /** 사용자 요청 : 로그인 세션의 사용자와 그가 로그인한 프로젝트. 프로젝트 없이 로그인한 세션(복구 로그인)은 만들 수 없다 */
    public static ExecutionContext user(AccountDto principal) {
        if (principal == null || principal.getProjectId() == null || principal.getId() == null) throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
        return new ExecutionContext(ExecutionType.USER, principal.getProjectId(), Long.valueOf(principal.getId()), null);
    }

    /** 비동기 요청 : 작업에 저장해 둔 요청자·프로젝트 (요청을 받을 때 {@link #user}의 값을 저장한다) */
    public static ExecutionContext async(Long projectId, Long accountId, String jobId) {
        return new ExecutionContext(ExecutionType.ASYNC, projectId, accountId, jobId);
    }

    /** 정기 배치 : 배치를 켠 프로젝트 하나 */
    public static ExecutionContext scheduled(Long projectId, String jobId) {
        return new ExecutionContext(ExecutionType.SCHEDULED, projectId, null, jobId);
    }
}
