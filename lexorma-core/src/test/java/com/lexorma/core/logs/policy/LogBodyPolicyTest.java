package com.lexorma.core.logs.policy;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

class LogBodyPolicyTest {

    static class PlainController {
        public void plain() {
        }

        @ProtectedLogBody
        public void secret() {
        }
    }

    @ProtectedLogBody
    static class ProtectedController {
        public void any() {
        }
    }

    @Test
    @DisplayName("경로 : 인증·비밀번호·복구·AI·MCP API 는 본문을 남기지 않고, 그 밖의 API 는 남긴다")
    void paths() {
        for (String path : new String[]{"/api/auth/me", "/api/auth/csrf", "/api/auth/signup", "/api/auth/login", "/api/login/projects", "/api/login/password",
                "/api/updatePwd", "/api/saveUser/password", "/api/recovery/projects", "/api/recovery/projects/3/activate",
                "/api/ai/settings", "/api/ai/chat/12/messages", "/api/mcp/servers", "/api/mcp/servers/1/test"}) {
            assertThat(LogBodyPolicy.isProtectedPath(path)).as(path).isTrue();
        }
        for (String path : new String[]{"/api/updateName", "/api/saveTable", "/api/saveUser/update", "/api/modelVersion", "/api/aiReport", "/api/authx", "/api/dashboard"}) {
            assertThat(LogBodyPolicy.isProtectedPath(path)).as(path).isFalse();
        }
        // 경로를 모르면 남기지 않는다
        assertThat(LogBodyPolicy.isProtectedPath(null)).isTrue();
    }

    @Test
    @DisplayName("@ProtectedLogBody : 경로 목록에 없는 API 도 컨트롤러 메서드·클래스에 붙이면 본문을 남기지 않는다")
    void annotation() throws Exception {
        PlainController plain = new PlainController();
        assertThat(LogBodyPolicy.isProtected("/api/sourceConnection", new HandlerMethod(plain, "plain"))).isFalse();
        assertThat(LogBodyPolicy.isProtected("/api/sourceConnection", new HandlerMethod(plain, "secret"))).isTrue();
        assertThat(LogBodyPolicy.isProtected("/api/sourceConnection", new HandlerMethod(new ProtectedController(), "any"))).isTrue();
        // 핸들러가 없어도(정적 리소스 등) 경로 목록은 적용된다
        assertThat(LogBodyPolicy.isProtected("/api/ai/settings", new Object())).isTrue();
    }

    @Test
    @DisplayName("보호 대상의 오류 기록 : 메시지가 아니라 오류 코드(없으면 예외 종류)만")
    void errorSummary() {
        assertThat(LogBodyPolicy.errorSummary(new CustomException(ErrorCode.SECRET_DECRYPT_FAILED))).isEqualTo("SECRET_DECRYPT_FAILED");
        assertThat(LogBodyPolicy.errorSummary(new IllegalStateException("jdbc:postgresql://scott:tiger@db/app"))).isEqualTo("IllegalStateException");
        assertThat(LogBodyPolicy.errorSummary(null)).isNull();
    }
}
