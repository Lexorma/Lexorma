package com.lexorma.core.security.conf;

import com.lexorma.core.security.details.FormWebAuthenticationDetails;
import com.lexorma.core.security.domain.dto.AccountDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이름을 바꾸기 전(com.koboolean.metamora)에 만든 세션을 지금도 읽는지 확인한다.
 * <p>
 * session/legacy-security-context.b64 는 만들어 낸 값이 아니다 : 이름을 바꾸기 전의 빌드(커밋 7a40013)를 PostgreSQL 에 띄워 admin 으로
 * 로그인한 뒤 SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES (SPRING_SECURITY_CONTEXT) 를 그대로 꺼낸 것이다. (비밀번호는 들어 있지 않다)
 */
class SessionSerializationConfigTest {

    private final ConversionService conversionService = conversionService();

    private static ConversionService conversionService() {
        SessionSerializationConfig config = new SessionSerializationConfig();
        config.setBeanClassLoader(SessionSerializationConfigTest.class.getClassLoader());
        return config.springSessionConversionService();
    }

    private static byte[] legacySession() throws IOException {
        try (var in = SessionSerializationConfigTest.class.getResourceAsStream("/session/legacy-security-context.b64")) {
            return Base64.getMimeDecoder().decode(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("이전 버전의 세션 : 로그인 사용자·권한·프로젝트가 그대로 읽힌다")
    void readsSessionWrittenBeforeRename() throws IOException {
        byte[] legacy = legacySession();
        // 정말 예전 패키지 이름으로 직렬화된 값인지 (새 이름은 들어 있지 않다)
        String raw = new String(legacy, StandardCharsets.ISO_8859_1);
        assertThat(raw).contains("com.koboolean.metamora.core.security.domain.dto.AccountDto").doesNotContain("com.lexorma");

        Object value = conversionService.convert(legacy, Object.class);

        assertThat(value).isInstanceOf(SecurityContext.class);
        Authentication authentication = ((SecurityContext) value).getAuthentication();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("admin");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(authentication.getPrincipal()).isInstanceOf(AccountDto.class);
        AccountDto account = (AccountDto) authentication.getPrincipal();
        assertThat(account.getUsername()).isEqualTo("admin");
        assertThat(account.getProjectId()).isZero();
        assertThat(account.getPassword()).isNull();
        assertThat(authentication.getDetails()).isInstanceOf(FormWebAuthenticationDetails.class);
        assertThat(((FormWebAuthenticationDetails) authentication.getDetails()).getProjectId()).isZero();
    }

    @Test
    @DisplayName("이전 세션을 읽어 다시 저장하면 새 패키지 이름으로 저장된다")
    void rewritesLegacySessionWithCurrentNames() throws IOException {
        Object value = conversionService.convert(legacySession(), Object.class);

        byte[] written = conversionService.convert(value, byte[].class);

        assertThat(new String(written, StandardCharsets.ISO_8859_1)).contains("com.lexorma.core.security.domain.dto.AccountDto")
                .doesNotContain("com.koboolean.metamora.core.security.domain.dto.AccountDto"); // 값으로 들어 있는 문자열(Role@…)은 그대로다
        assertThat(conversionService.convert(written, Object.class)).isInstanceOf(SecurityContext.class);
    }

    @Test
    @DisplayName("읽을 수 없는 세션 값은 예외 없이 없는 값이 된다 (요청이 500 이 되지 않고 로그인이 풀린다)")
    void unreadableValueBecomesNull() throws IOException {
        byte[] legacy = legacySession();
        byte[] truncated = java.util.Arrays.copyOf(legacy, legacy.length / 2);
        byte[] garbage = "not a java serialization stream".getBytes(StandardCharsets.UTF_8);
        // 예전 패키지 아래에 있었지만 지금은 없는 클래스
        byte[] removedClass = new String(legacy, StandardCharsets.ISO_8859_1)
                .replace("security.domain.dto.AccountDto", "security.domain.dto.AccountXxx").getBytes(StandardCharsets.ISO_8859_1);

        assertThat(conversionService.convert(truncated, Object.class)).isNull();
        assertThat(conversionService.convert(garbage, Object.class)).isNull();
        assertThat(conversionService.convert(removedClass, Object.class)).isNull();
    }

    @Test
    @DisplayName("이름 치환은 예전 기본 패키지로 시작하는 클래스에만 적용된다")
    void mapsOnlyTheLegacyBasePackage() throws IOException {
        // 예전 패키지가 이름 중간에 들어간 클래스는 치환하지 않는다 : 없는 클래스이므로 읽지 못한다
        assertThat(conversionService.convert(streamWithClassName("x.com.koboolean.metamora.core.security.domain.dto.AccountDto"), Object.class)).isNull();
        // 다른 패키지의 클래스는 이름 그대로 읽는다
        assertThat(conversionService.convert(conversionService.convert(new Plain("v"), byte[].class), Object.class)).isEqualTo(new Plain("v"));
    }

    /** 주어진 이름의 클래스 인스턴스 하나가 들어 있는 직렬화 스트림 */
    private static byte[] streamWithClassName(String className) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes) {
            @Override
            protected void writeClassDescriptor(ObjectStreamClass descriptor) throws IOException {
                writeUTF(className);
                writeLong(1L);
                writeByte(java.io.ObjectStreamConstants.SC_SERIALIZABLE);
                writeShort(0);
            }
        }) {
            out.writeObject(new Plain("v"));
        }
        return bytes.toByteArray();
    }

    private record Plain(String value) implements Serializable {
    }
}
