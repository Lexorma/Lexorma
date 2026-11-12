package com.lexorma.core.logs.policy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 요청·응답 본문을 시스템 로그(TB_LOG)에 남기지 않는 API (컨트롤러 클래스 또는 메서드에 붙인다).
 * 비밀값을 받거나 돌려주는 API, AI 프롬프트·응답처럼 본문 전체가 민감한 API 에 쓴다 : 호출 시각·요청자·프로젝트·결과 코드는 그대로 남는다.
 * 경로로 정하는 목록은 {@link LogBodyPolicy#PROTECTED_PATHS} 에 있다. 새 API 는 둘 중 하나로 표시한다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ProtectedLogBody {
}
