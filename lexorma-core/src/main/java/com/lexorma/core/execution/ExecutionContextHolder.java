package com.lexorma.core.execution;

import java.util.Optional;

/**
 * 세션 없이 도는 실행(비동기·정기 배치)의 "지금 실행 중인 컨텍스트". 로그인 세션이 없는 스레드에서 등록자·수정자를 남기는 데 쓴다
 * (JpaConfig 의 AuditorAware : 로그인 사용자가 있으면 그 사용자, 없으면 여기).
 * <pre>
 * try (ExecutionContextHolder.Scope scope = ExecutionContextHolder.open(verifier.verify(context))) {
 *     ... 작업 ...
 * }
 * </pre>
 * 권한을 주는 장치가 아니다 : 프로젝트 범위와 권한은 {@link VerifiedExecution} 을 인자로 받아 판단한다.
 */
public final class ExecutionContextHolder {

    private static final ThreadLocal<VerifiedExecution> CURRENT = new ThreadLocal<>();

    private ExecutionContextHolder() {
    }

    /** 이 스레드의 실행 컨텍스트를 정한다. 닫으면 이전 값으로 돌아간다 (스레드 풀에 남지 않게 반드시 닫는다) */
    public static Scope open(VerifiedExecution execution) {
        if (execution == null) throw new IllegalArgumentException("실행 컨텍스트가 없습니다.");
        VerifiedExecution previous = CURRENT.get();
        CURRENT.set(execution);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Optional<VerifiedExecution> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
