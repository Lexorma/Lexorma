package com.lexorma.core.logs.policy;

import com.lexorma.common.exception.CustomException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * 시스템 로그(TB_LOG)의 본문 저장 정책.
 * <p>
 * 인증 정보·원본 DB 연결·AI 를 다루는 API 는 요청·응답 본문을 저장하지 않고 {@link #PROTECTED} 만 남긴다 (마스킹에 기대지 않는다 :
 * 필드 이름을 몰라도, 본문 전체가 민감해도 새지 않는다). 호출 시각·요청자·프로젝트·URL·결과 코드는 그대로 남는다.
 * 오류 메시지도 본문을 되풀이할 수 있어 오류 코드(또는 예외 종류)만 남긴다.
 * <p>
 * 보호 대상은 두 가지로 정한다 :
 * <ul>
 *   <li>{@link #PROTECTED_PATHS} — 경로. 원본 DB 연결(/api/source)과 AI(/api/ai)는 경로 전체가 대상이라 앞으로 만드는 API 도 이 경로 아래에 두면 자동으로 보호된다</li>
 *   <li>{@link ProtectedLogBody} — 그 밖의 경로에 있는 API 는 컨트롤러에 붙인다</li>
 * </ul>
 * 그 밖의 일반 API 는 {@link com.lexorma.core.logs.util.SensitiveDataMasker} 로 비밀 필드와 값 안의 비밀을 가린 본문을 남긴다.
 */
public final class LogBodyPolicy {

    /** 본문 대신 남기는 값 */
    public static final String PROTECTED = "[PROTECTED]";

    /**
     * 본문을 남기지 않는 경로.
     * 로그인 전 API(/api/login/**, 회원가입)와 Security 가 처리하는 /api/auth/login·logout 은 요청 로그 대상이 아니지만
     * (로그인한 사용자의 요청만 남는다) 로그인한 상태로 불러도 본문이 남지 않도록 같이 넣는다.
     */
    public static final List<String> PROTECTED_PATHS = List.of(
            // 인증 : 로그인·회원가입(비밀번호), CSRF 토큰, 로그인 정보
            "/api/auth/**",
            "/api/login/**",
            // 비밀번호 변경 (본인 / 관리자 초기화)
            "/api/updatePwd",
            "/api/saveUser/password",
            // 복구 로그인
            "/api/recovery/**",
            // 원본 DB 연결·원본 카탈로그 : 접속 정보(호스트·계정·비밀번호), 드라이버 오류에 섞여 나올 수 있는 접속 정보
            "/api/source/**",
            // AI : 프롬프트·응답·Endpoint·인증 정보 (AI 기능을 만들 때 API 는 이 경로 아래에 둔다)
            "/api/ai/**");

    private static final List<PathPattern> PATTERNS = PROTECTED_PATHS.stream().map(PathPatternParser.defaultInstance::parse).toList();

    private LogBodyPolicy() {
    }

    /** 이 요청의 본문을 남기지 않는지 : 경로 목록 또는 컨트롤러의 {@link ProtectedLogBody} */
    public static boolean isProtected(String requestUri, Object handler) {
        if (handler instanceof HandlerMethod method
                && (method.hasMethodAnnotation(ProtectedLogBody.class) || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ProtectedLogBody.class))) {
            return true;
        }
        return isProtectedPath(requestUri);
    }

    public static boolean isProtectedPath(String requestUri) {
        if (requestUri == null) return true; // 경로를 모르면 남기지 않는다
        try {
            PathContainer path = PathContainer.parsePath(requestUri);
            return PATTERNS.stream().anyMatch(pattern -> pattern.matches(path));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 보호 대상 API 의 오류 기록 : 메시지 대신 오류 코드 (없으면 예외 종류) */
    public static String errorSummary(Throwable error) {
        if (error == null) return null;
        return error instanceof CustomException custom && custom.getErrorCode() != null ? custom.getErrorCode().getCode() : error.getClass().getSimpleName();
    }
}
