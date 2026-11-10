package com.koboolean.metamora.common.i18n;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void request(String acceptLanguage) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (acceptLanguage != null) {
            request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
            request.setPreferredLocales(Locale.LanguageRange.parse(acceptLanguage).stream()
                    .map(range -> Locale.forLanguageTag(range.getRange())).toList());
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("요청이 없거나 Accept-Language 가 없으면 한국어 원문이다")
    void koreanByDefault() {
        assertThat(Messages.get("로그인이 필요합니다.")).isEqualTo("로그인이 필요합니다.");

        request(null);
        assertThat(Messages.get("로그인이 필요합니다.")).isEqualTo("로그인이 필요합니다.");
    }

    @Test
    @DisplayName("Accept-Language 가 한국어면 한국어, 그 밖의 언어면 영어다")
    void followsAcceptLanguage() {
        request("ko-KR,ko;q=0.9,en;q=0.8");
        assertThat(Messages.get("로그인이 필요합니다.")).isEqualTo("로그인이 필요합니다.");

        request("en");
        assertThat(Messages.get("로그인이 필요합니다.")).isEqualTo("Login is required.");

        request("ja,en;q=0.8");
        assertThat(Messages.get("로그인이 필요합니다.")).isEqualTo("Login is required.");
    }

    @Test
    @DisplayName("{0}, {1} 자리에 값을 넣고, 영어 문구가 없으면 한국어 원문을 쓴다")
    void formatsArguments() {
        assertThat(Messages.get("관계 {0} 에서 사용 중인 컬럼입니다.", "FK_USER")).isEqualTo("관계 FK_USER 에서 사용 중인 컬럼입니다.");

        request("en");
        assertThat(Messages.get("관계 {0} 에서 사용 중인 컬럼입니다.", "FK_USER")).isEqualTo("The column is used by relation FK_USER.");
        assertThat(Messages.get("사전에 없는 문구 {0}", 1)).isEqualTo("사전에 없는 문구 1");
    }

    @Test
    @DisplayName("ErrorCode 와 CustomException 의 메시지도 요청 언어를 따른다")
    void errorCodeFollowsLocale() {
        assertThat(ErrorCode.LOGIN_FAILED.getMessage()).isEqualTo("아이디 또는 비밀번호가 잘못되었습니다.");

        request("en");
        assertThat(ErrorCode.LOGIN_FAILED.getMessage()).isEqualTo("The username or password is incorrect.");
        assertThat(new CustomException(ErrorCode.RELATION_EXISTS, "{0} 에 테이블이 있습니다.", "PUBLIC").getCustomMessage())
                .isEqualTo("Data that is in use cannot be deleted. \n[PUBLIC contains tables.]");
    }

    @Test
    @DisplayName("모든 ErrorCode 에 영어 문구가 있다")
    void everyErrorCodeHasEnglish() {
        assertThat(Arrays.stream(ErrorCode.values()).filter(code -> !Messages.hasEnglish(code.getMessage())))
                .isEmpty();
    }
}
