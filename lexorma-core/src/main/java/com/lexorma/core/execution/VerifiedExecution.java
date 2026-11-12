package com.lexorma.core.execution;

import java.util.Set;

/**
 * 실행 직전에 DB 로 확인한 실행 컨텍스트 ({@link ExecutionContextVerifier}). 확인한 그 시점의 사실이므로 오래 들고 있지 않는다 :
 * 긴 작업은 단계마다 다시 확인한다.
 *
 * @param username    요청자의 로그인 아이디. SCHEDULED 는 null
 * @param auditor     등록자·수정자로 남길 이름 (세션이 있는 요청과 같은 값 = 사용자 이름). SCHEDULED 는 {@link #SCHEDULER}
 * @param authorities 요청자가 지금 가진 권한 (역할 계층 적용, ROLE_ 접두사 포함). SCHEDULED 는 비어 있다
 * @param manager     요청자가 이 프로젝트의 관리자인지
 */
public record VerifiedExecution(ExecutionContext context, String username, String auditor, Set<String> authorities, boolean manager) {

    public static final String SCHEDULER = "SCHEDULER";

    public Long projectId() {
        return context.projectId();
    }

    public boolean hasRole(String role) {
        return authorities.contains("ROLE_" + role);
    }
}
