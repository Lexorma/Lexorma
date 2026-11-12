package com.lexorma.core.execution;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.security.domain.dto.AccountDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionContextTest {

    @Test
    @DisplayName("사용자 요청 : 로그인 세션의 사용자·프로젝트로 만든다. 프로젝트 없는 세션(복구 로그인)은 만들 수 없다")
    void user() {
        ExecutionContext context = ExecutionContext.user(AccountDto.builder().id("12").username("u").projectId(7L).build());
        assertThat(context).isEqualTo(new ExecutionContext(ExecutionType.USER, 7L, 12L, null));

        assertThatThrownBy(() -> ExecutionContext.user(AccountDto.builder().id("12").username("u").build())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> ExecutionContext.user(null)).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("비동기·정기 배치 : 값만 담는다. 비동기는 요청자와 작업 ID 가 있어야 하고, 정기 배치는 요청자가 없다")
    void asyncAndScheduled() {
        assertThat(ExecutionContext.async(7L, 12L, "job-1")).extracting(ExecutionContext::type, ExecutionContext::projectId, ExecutionContext::accountId, ExecutionContext::jobId)
                .containsExactly(ExecutionType.ASYNC, 7L, 12L, "job-1");
        assertThat(ExecutionContext.scheduled(7L, "nightly-7").accountId()).isNull();

        assertThatThrownBy(() -> ExecutionContext.async(7L, null, "job-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionContext.async(7L, 12L, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionContext.async(null, 12L, "job-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionContext.scheduled(null, "nightly")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionContext(ExecutionType.SCHEDULED, 7L, 12L, "nightly")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionContext(null, 7L, 12L, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("실행 컨텍스트 보관 : 닫으면 이전 값으로 돌아가 스레드에 남지 않는다")
    void holder() {
        VerifiedExecution outer = new VerifiedExecution(ExecutionContext.scheduled(7L, "a"), null, VerifiedExecution.SCHEDULER, java.util.Set.of(), false);
        VerifiedExecution inner = new VerifiedExecution(ExecutionContext.async(8L, 1L, "b"), "u", "사용자", java.util.Set.of("ROLE_USER"), false);

        assertThat(ExecutionContextHolder.current()).isEmpty();
        try (ExecutionContextHolder.Scope first = ExecutionContextHolder.open(outer)) {
            assertThat(ExecutionContextHolder.current()).contains(outer);
            try (ExecutionContextHolder.Scope second = ExecutionContextHolder.open(inner)) {
                assertThat(ExecutionContextHolder.current()).contains(inner);
            }
            assertThat(ExecutionContextHolder.current()).contains(outer);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
        assertThat(inner.hasRole("USER")).isTrue();
        assertThat(inner.hasRole("ADMIN")).isFalse();
    }
}
