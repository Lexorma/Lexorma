package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.crypto.SecretCipher;
import com.lexorma.core.crypto.SecretKeyring;
import com.lexorma.core.crypto.SecretScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 비밀값 암호화 키 설정 (선택) : 실제 Spring 설정 로딩(명령행·프로필 설정 파일·configtree)으로 키링만 띄워 본다.
 * <ul>
 *   <li>키가 없어도 뜬다 (암호화를 쓰는 순간 SECRET_KEY_NOT_CONFIGURED). 키를 줬는데 틀리면 기동하지 않고, 오류에 키 값이 없다</li>
 *   <li>local · h2-local 프로필에만 개발 전용 고정 키가 있다 (프로필을 주지 않으면 기본 local). 다른 프로필(docker · h2-docker · dev)에는 없다</li>
 *   <li>선택 Docker Secret 처럼 {@code spring.config.import=configtree:<폴더>/} 의 {@code lexorma.secret.keys} 파일에서 키를 읽는다.
 *       재시작·키 교체 뒤에도 옛 암호문을 복호화한다</li>
 *   <li>우선순위 : configtree·프로필 설정 파일은 config data 라 명령행 인자·시스템 속성·OS 환경변수가 같은 속성을 주면 그쪽이 이긴다</li>
 * </ul>
 */
class SecretKeyConfigurationTest {

    private static final String K1 = key(1);
    private static final String K2 = key(2);
    private static final SecretScope SCOPE = SecretScope.ofProject("source-connection", 7L, 31L, "password");

    @Configuration(proxyBeanMethods = false)
    @Import({SecretKeyring.class, SecretCipher.class})
    static class Keys {
    }

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (seed * 17 + i);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 프로필은 실제 실행과 같이 --spring.profiles.active 로 준다 (Docker 이미지 CMD). null 이면 application.yml 의 기본 프로필 */
    private static ConfigurableApplicationContext start(String profile, String... args) {
        String[] all = args;
        if (profile != null) {
            all = java.util.Arrays.copyOf(args, args.length + 1);
            all[args.length] = "--spring.profiles.active=" + profile;
        }
        return new SpringApplicationBuilder(Keys.class).web(WebApplicationType.NONE).logStartupInfo(false).run(all);
    }

    @Test
    @DisplayName("키 없이 뜬다 (암호화를 쓸 때만 SECRET_KEY_NOT_CONFIGURED). 준 키가 틀리면 기동하지 않는다 (오류에 키 값 없음). local·h2-local 에만 개발 키가 있다")
    void optionalKeyAndProfiles() {
        for (String profile : new String[]{"docker", "h2-docker", "dev"}) {
            try (ConfigurableApplicationContext context = start(profile)) {
                assertThat(context.getBean(SecretKeyring.class).isConfigured()).as(String.valueOf(profile)).isFalse();
                assertThatThrownBy(() -> context.getBean(SecretCipher.class).encrypt("pw", SCOPE)).isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SECRET_KEY_NOT_CONFIGURED));
            }
        }
        // 프로필을 주지 않으면 기본 프로필 local (application.yml spring.profiles.active)
        for (String profile : new String[]{"local", "h2-local", null}) {
            try (ConfigurableApplicationContext context = start(profile)) {
                SecretCipher cipher = context.getBean(SecretCipher.class);
                assertThat(context.getBean(SecretKeyring.class).activeKeyId()).as(String.valueOf(profile)).isEqualTo("local");
                assertThat(cipher.decrypt(cipher.encrypt("pw", SCOPE), SCOPE)).isEqualTo("pw");
            }
            // 자기 키(환경변수·명령행 등)가 개발 키보다 우선하고, 활성 키ID 를 주지 않으면 그 목록의 맨 앞 키다
            try (ConfigurableApplicationContext context = start(profile, "--lexorma.secret.keys=k1:" + K1)) {
                assertThat(context.getBean(SecretKeyring.class).keyIds()).containsExactly("k1");
                assertThat(context.getBean(SecretKeyring.class).activeKeyId()).isEqualTo("k1");
            }
        }
        for (String[] args : new String[][]{
                {"--lexorma.secret.keys=k1:not-base64!!"},
                {"--lexorma.secret.keys=k1:" + Base64.getEncoder().encodeToString(new byte[16])},
                {"--lexorma.secret.keys=k1:" + K1 + ",k1:" + K2},
                {"--lexorma.secret.keys=k1:" + K1, "--lexorma.secret.active-key-id=k2"},
                {"--lexorma.secret.active-key-id=k1"}}) {
            assertThatThrownBy(() -> start("docker", args)).as(String.join(" ", args))
                    .rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid secret key configuration")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(K1, K2));
        }
        try (ConfigurableApplicationContext context = start("docker", "--lexorma.secret.keys=k2:" + K2 + ",k1:" + K1, "--lexorma.secret.active-key-id=k2")) {
            assertThat(context.getBean(SecretKeyring.class).keyIds()).containsExactlyInAnyOrder("k1", "k2");
        }
    }

    @Test
    @DisplayName("configtree (선택 Docker Secret 마운트와 같은 구조) : lexorma.secret.keys 파일을 읽고, 재시작·키 교체 뒤에도 옛 암호문을 복호화한다. 명령행 값이 configtree 보다 우선한다")
    void configTree(@TempDir Path secrets) throws Exception {
        Files.writeString(secrets.resolve("lexorma.secret.keys"), "k1:" + K1 + "\n");
        String importTree = "--spring.config.import=configtree:" + secrets.toAbsolutePath() + "/";
        String encrypted;
        try (ConfigurableApplicationContext context = start("docker", importTree, "--lexorma.secret.active-key-id=k1")) {
            encrypted = context.getBean(SecretCipher.class).encrypt("pw-1", SCOPE);
        }
        try (ConfigurableApplicationContext context = start("docker", importTree, "--lexorma.secret.active-key-id=k1")) {
            assertThat(context.getBean(SecretCipher.class).decrypt(encrypted, SCOPE)).isEqualTo("pw-1");
        }
        Files.writeString(secrets.resolve("lexorma.secret.keys"), "k2:" + K2 + ",k1:" + K1 + "\n");
        try (ConfigurableApplicationContext context = start("docker", importTree, "--lexorma.secret.active-key-id=k2")) {
            SecretCipher cipher = context.getBean(SecretCipher.class);
            assertThat(cipher.decrypt(encrypted, SCOPE)).isEqualTo("pw-1");
            assertThat(cipher.encrypt("pw-2", SCOPE)).startsWith("v1:k2:");
        }
        assertThatThrownBy(() -> start("docker", importTree, "--lexorma.secret.keys=k2:not-base64!!", "--lexorma.secret.active-key-id=k2"))
                .rootCause().hasMessageContaining("not valid Base64");
    }
}
