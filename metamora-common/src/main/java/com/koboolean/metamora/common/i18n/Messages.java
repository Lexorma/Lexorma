package com.koboolean.metamora.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/**
 * 사용자에게 보여 주는 메시지의 국제화 (한국어 / 영어)
 * <p>
 * 코드에는 한국어 원문을 그대로 쓰고, 영어는 {@link EnglishMessages} 에서 원문을 키로 찾는다.
 * 언어는 요청의 Accept-Language 를 따른다. 화면(React)은 브라우저 언어에 맞춰 ko / en 을 보낸다.
 * 요청이 없거나(배치·테스트) Accept-Language 가 없으면 한국어다.
 */
public final class Messages {

    private Messages() {
    }

    /**
     * @param message 한국어 원문. 값이 들어갈 자리는 {0}, {1} 로 쓴다.
     * @param args    {0}, {1} 에 순서대로 들어갈 값
     */
    public static String get(String message, Object... args) {
        if (message == null) return null;
        String text = isEnglish() ? EnglishMessages.MESSAGES.getOrDefault(message, message) : message;
        return format(text, args);
    }

    /** 영어 문구가 등록되어 있는지 (누락 검사용) */
    public static boolean hasEnglish(String message) {
        return EnglishMessages.MESSAGES.containsKey(message);
    }

    private static boolean isEnglish() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return false;
        HttpServletRequest request = attributes.getRequest();
        String header = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        if (header == null || header.isBlank()) return false;
        return !Locale.KOREAN.getLanguage().equals(request.getLocale().getLanguage());
    }

    private static String format(String text, Object[] args) {
        if (args == null || args.length == 0) return text;
        String result = text;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }
}
