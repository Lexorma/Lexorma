package com.lexorma.core.crypto;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String K1 = key(1);
    private static final String K2 = key(2);
    private static final String SECRET = "Tiger!Secret9 한글 ✓";
    private static final SecretScope SCOPE = SecretScope.ofProject("source-connection", 7L, 31L, "password");

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (seed * 31 + i);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static SecretCipher cipher(String keys, String active) {
        return new SecretCipher(new SecretKeyring(keys, active));
    }

    private static Consumer<CustomException> code(ErrorCode expected) {
        return e -> assertThat(e.getErrorCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("암호화 → 복호화. 암호문은 v1:<키ID>:<Base64(IV 12 + 암호문 + 태그 16)> 이고 평문이 들어 있지 않다")
    void roundTrip() {
        SecretCipher cipher = cipher("k1:" + K1, "");
        String encrypted = cipher.encrypt(SECRET, SCOPE);

        assertThat(encrypted).startsWith("v1:k1:").doesNotContain("Tiger");
        byte[] payload = Base64.getDecoder().decode(encrypted.split(":")[2]);
        assertThat(payload).hasSize(12 + SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 16);
        assertThat(cipher.decrypt(encrypted, SCOPE)).isEqualTo(SECRET);
        assertThat(cipher.keyIdOf(encrypted)).isEqualTo("k1");
        assertThat(cipher.isAvailable()).isTrue();
        // 빈 값도 값이다
        assertThat(cipher.decrypt(cipher.encrypt("", SCOPE), SCOPE)).isEmpty();
    }

    @Test
    @DisplayName("같은 평문을 여러 번 암호화해도 암호문과 IV 가 매번 다르다")
    void randomIv() {
        SecretCipher cipher = cipher("k1:" + K1, "");
        Set<String> encrypted = new HashSet<>();
        Set<String> ivs = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String value = cipher.encrypt(SECRET, SCOPE);
            encrypted.add(value);
            ivs.add(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(Base64.getDecoder().decode(value.split(":")[2]), 12)));
        }
        assertThat(encrypted).hasSize(200);
        assertThat(ivs).hasSize(200);
    }

    @Test
    @DisplayName("다른 자리(AAD)로는 복호화되지 않는다 : 다른 프로젝트·다른 소유 객체·다른 필드·다른 용도·전역 ↔ 프로젝트")
    void scopeBinding() {
        SecretCipher cipher = cipher("k1:" + K1, "");
        String encrypted = cipher.encrypt(SECRET, SCOPE);

        for (SecretScope other : new SecretScope[]{
                SecretScope.ofProject("source-connection", 8L, 31L, "password"),
                SecretScope.ofProject("source-connection", 7L, 32L, "password"),
                SecretScope.ofProject("source-connection", 7L, 31L, "apiKey"),
                SecretScope.ofProject("mcp-server", 7L, 31L, "password"),
                SecretScope.global("source-connection", "31", "password"),
                // 붙여 쓰면 같아 보이는 자리 : 프로젝트 73 의 객체 1 ↔ 프로젝트 7 의 객체 31
                SecretScope.ofProject("source-connection", 73L, 1L, "password")}) {
            assertThatThrownBy(() -> cipher.decrypt(encrypted, other)).as(other.toString()).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_DECRYPT_FAILED));
        }
        assertThat(cipher.decrypt(encrypted, SecretScope.ofProject("source-connection", 7L, 31L, "password"))).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("소유 객체의 ID 가 정해지기 전에는 암호화할 자리를 만들 수 없다")
    void ownerRequired() {
        assertThatThrownBy(() -> SecretScope.ofProject("source-connection", 7L, null, "password")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretScope.ofProject("source-connection", null, 31L, "password")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretScope.global("ai-endpoint", " ", "apiKey")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretScope.global("ai|endpoint", "1", "apiKey")).isInstanceOf(IllegalArgumentException.class);
        SecretCipher cipher = cipher("k1:" + K1, "");
        assertThatThrownBy(() -> cipher.encrypt(SECRET, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(null, SCOPE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("암호문이 한 글자라도 바뀌면 복호화에 실패한다 (IV·본문·태그·키ID·버전·형식)")
    void tamper() {
        SecretCipher cipher = cipher("k1:" + K1 + ",k2:" + K2, "k1");
        String encrypted = cipher.encrypt(SECRET, SCOPE);
        String[] parts = encrypted.split(":");
        byte[] payload = Base64.getDecoder().decode(parts[2]);

        for (int index : new int[]{0, 11, 12, payload.length / 2, payload.length - 16, payload.length - 1}) {
            byte[] changed = payload.clone();
            changed[index] ^= 0x01;
            String tampered = "v1:k1:" + Base64.getEncoder().encodeToString(changed);
            assertThatThrownBy(() -> cipher.decrypt(tampered, SCOPE)).as("byte " + index).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_DECRYPT_FAILED));
        }
        // 잘라 낸 암호문, 다른 키ID 로 바꿔 단 암호문, 모르는 버전, 형식이 아닌 값, 평문
        for (String broken : new String[]{"v1:k1:" + Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(payload, payload.length - 1)),
                "v1:k2:" + parts[2], "v2:k1:" + parts[2], "v1:k1:###", "v1:k1:", "v1::" + parts[2], "k1:" + parts[2], SECRET, "", null}) {
            assertThatThrownBy(() -> cipher.decrypt(broken, SCOPE)).as(String.valueOf(broken)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_DECRYPT_FAILED));
        }
    }

    @Test
    @DisplayName("키가 없으면 : 만들어지고(기동에 영향 없음) 쓰는 시점에 SECRET_KEY_NOT_CONFIGURED 로 실패한다. 평문을 돌려주지 않는다")
    void noKey() {
        for (String keys : new String[]{"", "  ", null}) {
            SecretCipher cipher = cipher(keys, "");
            assertThat(cipher.isAvailable()).isFalse();
            assertThatThrownBy(() -> cipher.encrypt(SECRET, SCOPE)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_KEY_NOT_CONFIGURED));
            assertThatThrownBy(() -> cipher.decrypt("v1:k1:AAAA", SCOPE)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_KEY_NOT_CONFIGURED));
            assertThatThrownBy(() -> cipher.needsReencryption("v1:k1:AAAA")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_KEY_NOT_CONFIGURED));
        }
    }

    @Test
    @DisplayName("잘못된 키 설정은 기동할 때 드러난다. 오류 메시지에 키 값은 없다")
    void invalidKeys() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        for (String keys : new String[]{"k1:not-base64!!", "k1:" + shortKey, K1, ":" + K1, "bad id:" + K1, "k1:" + K1 + ",k1:" + K2}) {
            assertThatThrownBy(() -> new SecretKeyring(keys, "")).as(keys).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LEXORMA_SECRET_KEYS").satisfies(e -> assertThat(e.getMessage()).doesNotContain(K1, K2, shortKey));
        }
        assertThatThrownBy(() -> new SecretKeyring("k1:" + K1, "k9")).isInstanceOf(IllegalStateException.class).hasMessageContaining("k9");
        assertThatThrownBy(() -> new SecretKeyring("", "k1")).isInstanceOf(IllegalStateException.class);
        assertThat(new SecretKeyring("k1:" + K1 + ", k2:" + K2, "").toString()).contains("k1", "k2").doesNotContain(K1, K2);
    }

    @Test
    @DisplayName("같은 키ID 에 다른 키를 넣으면 옛 암호문은 복호화되지 않는다 (평문도 엉뚱한 값도 나오지 않는다)")
    void wrongKey() {
        String encrypted = cipher("k1:" + K1, "").encrypt(SECRET, SCOPE);
        assertThatThrownBy(() -> cipher("k1:" + K2, "").decrypt(encrypted, SCOPE)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_DECRYPT_FAILED));
    }

    @Test
    @DisplayName("키 교체 : 새 키를 활성으로 두어도 옛 키로 만든 암호문을 읽고, 다시 암호화하면 새 키의 암호문이 된다. 옛 키를 지우면 SECRET_KEY_NOT_FOUND(재등록)")
    void rotation() {
        String old = cipher("k1:" + K1, "").encrypt(SECRET, SCOPE);

        SecretCipher rotated = cipher("k2:" + K2 + ",k1:" + K1, "");
        assertThat(rotated.decrypt(old, SCOPE)).isEqualTo(SECRET);
        assertThat(rotated.needsReencryption(old)).isTrue();
        String renewed = rotated.reencrypt(old, SCOPE);
        assertThat(renewed).startsWith("v1:k2:");
        assertThat(rotated.needsReencryption(renewed)).isFalse();
        assertThat(rotated.decrypt(renewed, SCOPE)).isEqualTo(SECRET);
        // 다시 암호화도 자리를 지킨다
        assertThatThrownBy(() -> rotated.reencrypt(old, SecretScope.ofProject("source-connection", 8L, 31L, "password"))).isInstanceOf(CustomException.class);

        // 활성 키를 명시해도 같다 (목록 순서와 무관)
        assertThat(cipher("k1:" + K1 + ",k2:" + K2, "k2").encrypt(SECRET, SCOPE)).startsWith("v1:k2:");

        // 옛 키를 지운 뒤 : 새 암호문은 읽고, 옛 암호문은 다시 등록해야 한다
        SecretCipher onlyNew = cipher("k2:" + K2, "");
        assertThat(onlyNew.decrypt(renewed, SCOPE)).isEqualTo(SECRET);
        assertThatThrownBy(() -> onlyNew.decrypt(old, SCOPE)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_KEY_NOT_FOUND));
    }

    @Test
    @DisplayName("오류에 비밀값·암호문·키가 들어가지 않는다")
    void errorsDoNotLeak() {
        SecretCipher cipher = cipher("k1:" + K1, "");
        String encrypted = cipher.encrypt(SECRET, SCOPE);
        Throwable wrongScope = org.assertj.core.api.Assertions.catchThrowable(() -> cipher.decrypt(encrypted, SecretScope.ofProject("source-connection", 8L, 31L, "password")));
        Throwable lostKey = org.assertj.core.api.Assertions.catchThrowable(() -> cipher("k2:" + K2, "").decrypt(encrypted, SCOPE));
        for (Throwable error : new Throwable[]{wrongScope, lostKey}) {
            String text = error + " " + ((CustomException) error).getCustomMessage() + " " + error.getCause();
            assertThat(text).doesNotContain("Tiger", encrypted.split(":")[2], K1, K2);
        }
    }
}
