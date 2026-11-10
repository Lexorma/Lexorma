package com.koboolean.metamora.core.security.handler;

import com.koboolean.metamora.common.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Security 필터 단계(로그인/로그아웃/인증·권한 오류)의 JSON 응답.
 * React 화면은 리다이렉트 대신 상태코드와 code 값으로 처리한다. (GlobalExceptionHandler 의 ErrorResponse 와 같은 형태)
 * message 는 한국어 원문으로 넘기면 요청 언어에 맞춰 내려간다.
 */
public final class JsonResponses {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private JsonResponses() {
    }

    public static void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        OBJECT_MAPPER.writeValue(response.getWriter(), body);
    }

    public static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("message", Messages.get(message));
        write(response, status, body);
    }
}
