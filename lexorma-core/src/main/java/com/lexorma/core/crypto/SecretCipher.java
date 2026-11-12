package com.lexorma.core.crypto;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 비밀값 암호화 (AES-256-GCM, JDK 표준 기능만 쓴다). 외부 DB 연결·AI Endpoint 의 인증 정보를 DB 에 저장할 때 쓴다.
 * <pre>
 * 암호문 = v1:&lt;키ID&gt;:&lt;Base64(IV 12바이트 + 암호문 + 인증 태그 16바이트)&gt;
 * </pre>
 * <ul>
 *   <li>암호화할 때마다 IV 를 새로 뽑는다 : 같은 값을 다시 암호화해도 암호문이 다르다</li>
 *   <li>{@link SecretScope}(용도·프로젝트·소유 객체·필드)를 AAD 로 묶는다 : 다른 자리의 암호문을 복사해 오면 복호화되지 않는다</li>
 *   <li>복호화는 인증 태그를 확인한다 : 암호문이 한 비트라도 바뀌었거나 키·자리가 다르면 실패한다. 실패 이유는 구분해 알려 주지 않는다</li>
 *   <li>실패하면 예외다. 평문을 돌려주거나 평문으로 저장하게 두는 경로가 없다. 예외와 로그에 평문·암호문·키를 넣지 않는다</li>
 * </ul>
 * 키가 없어도 이 빈은 만들어진다 (원본 DB 연결·AI 를 쓰지 않는 Lexorma 는 그대로 뜬다). 쓰는 시점에 {@code SECRET_KEY_NOT_CONFIGURED} 로 실패한다.
 */
@Component
@RequiredArgsConstructor
public class SecretCipher {

    static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeyring keyring;
    private final SecureRandom random = new SecureRandom();

    /** 암호화 키가 설정되어 있는지 */
    public boolean isAvailable() {
        return keyring.isConfigured();
    }

    /** 활성 키로 암호화한다. scope 의 소유 객체 ID 가 정해진 뒤에 부른다 */
    public String encrypt(String plaintext, SecretScope scope) {
        if (plaintext == null) throw new IllegalArgumentException("암호화할 값이 없습니다.");
        if (scope == null) throw new IllegalArgumentException("scope 가 없습니다.");
        if (!keyring.isConfigured()) throw new CustomException(ErrorCode.SECRET_KEY_NOT_CONFIGURED);
        String keyId = keyring.activeKeyId();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyring.key(keyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(scope.aad());
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return VERSION + ":" + keyId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            // 평문으로 넘어가지 않는다 : 저장 자체가 실패해야 한다
            throw new IllegalStateException("비밀값을 암호화하지 못했습니다.", e);
        }
    }

    /** 암호문을 만든 키로 복호화한다 (활성 키가 아니어도 등록되어 있으면 읽는다) */
    public String decrypt(String ciphertext, SecretScope scope) {
        if (scope == null) throw new IllegalArgumentException("scope 가 없습니다.");
        if (!keyring.isConfigured()) throw new CustomException(ErrorCode.SECRET_KEY_NOT_CONFIGURED);
        Parsed parsed = parse(ciphertext);
        SecretKey key = keyring.key(parsed.keyId());
        // 키를 잃어버렸거나 지웠다 : 이 비밀값은 다시 등록해야 한다
        if (key == null) throw new CustomException(ErrorCode.SECRET_KEY_NOT_FOUND);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, parsed.payload(), 0, IV_BYTES));
            cipher.updateAAD(scope.aad());
            return new String(cipher.doFinal(parsed.payload(), IV_BYTES, parsed.payload().length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // 변조, 다른 자리의 암호문, 같은 키ID 의 다른 키 : 어느 것인지 알려 주지 않는다
            throw new CustomException(ErrorCode.SECRET_DECRYPT_FAILED);
        }
    }

    /** 암호문을 만든 키ID (모양이 맞지 않으면 SECRET_DECRYPT_FAILED) */
    public String keyIdOf(String ciphertext) {
        return parse(ciphertext).keyId();
    }

    /** 활성 키가 아닌 키로 만든 암호문인지 (키 교체 뒤 다시 암호화할 대상) */
    public boolean needsReencryption(String ciphertext) {
        if (!keyring.isConfigured()) throw new CustomException(ErrorCode.SECRET_KEY_NOT_CONFIGURED);
        return !parse(ciphertext).keyId().equals(keyring.activeKeyId());
    }

    /** 키 교체 : 옛 키로 읽어 활성 키로 다시 암호화한다 (같은 자리). 옛 키가 아직 등록되어 있어야 한다 */
    public String reencrypt(String ciphertext, SecretScope scope) {
        return encrypt(decrypt(ciphertext, scope), scope);
    }

    private record Parsed(String keyId, byte[] payload) {
    }

    private static Parsed parse(String ciphertext) {
        if (ciphertext == null) throw new CustomException(ErrorCode.SECRET_DECRYPT_FAILED);
        String[] parts = ciphertext.split(":", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0]) || parts[1].isEmpty()) throw new CustomException(ErrorCode.SECRET_DECRYPT_FAILED);
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.SECRET_DECRYPT_FAILED);
        }
        if (payload.length < IV_BYTES + TAG_BITS / 8) throw new CustomException(ErrorCode.SECRET_DECRYPT_FAILED);
        return new Parsed(parts[1], payload);
    }
}
