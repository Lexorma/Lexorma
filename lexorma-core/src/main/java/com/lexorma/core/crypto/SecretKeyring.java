package com.lexorma.core.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 비밀값 암호화 키 (환경변수에서 읽는다. 소스·DB·로그에 두지 않는다).
 * <pre>
 * LEXORMA_SECRET_KEYS          = k2:&lt;Base64 32바이트&gt;,k1:&lt;Base64 32바이트&gt;   (키ID:키, 쉼표로 여러 개)
 * LEXORMA_SECRET_ACTIVE_KEY_ID = k2                                            (새로 암호화할 때 쓰는 키. 없으면 맨 앞의 키)
 * </pre>
 * <ul>
 *   <li>키가 없으면 : 애플리케이션은 그대로 뜬다. 암호화·복호화를 쓰는 기능만 그 시점에 실패한다 ({@link SecretCipher})</li>
 *   <li>키가 잘못됐으면(Base64 아님, 32바이트 아님, 키ID 중복, 활성 키ID 없음) : 기동을 멈춘다 — 운영자가 넣은 값이 틀린 것이고,
 *       틀린 채로 뜨면 비밀값을 저장하는 순간에야 드러난다. 오류 메시지에는 키ID 만 적는다</li>
 *   <li>키 교체 : 새 키를 추가하고 활성 키ID 를 바꾼다. 옛 키는 옛 암호문을 읽기 위해 다시 암호화가 끝날 때까지 남겨 둔다</li>
 * </ul>
 */
@Component
public class SecretKeyring {

    static final int KEY_BYTES = 32;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    private final Map<String, SecretKey> keys = new LinkedHashMap<>();
    private final String activeKeyId;

    public SecretKeyring(@Value("${lexorma.secret.keys:}") String keys, @Value("${lexorma.secret.active-key-id:}") String activeKeyId) {
        if (keys != null && !keys.isBlank()) {
            for (String entry : keys.split(",")) {
                String item = entry.trim();
                if (item.isEmpty()) continue;
                int index = item.indexOf(':');
                String id = index < 0 ? "" : item.substring(0, index).trim();
                if (!KEY_ID.matcher(id).matches()) throw misconfigured("each key must be '<keyId>:<Base64 key>' and the key id may use letters, digits, '_' and '-' (up to 32 characters)");
                if (this.keys.containsKey(id)) throw misconfigured("duplicate key id: " + id);
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(item.substring(index + 1).trim());
                } catch (IllegalArgumentException e) {
                    throw misconfigured("the key is not valid Base64: " + id);
                }
                if (bytes.length != KEY_BYTES) throw misconfigured("the key must be 32 bytes (AES-256): " + id);
                this.keys.put(id, new SecretKeySpec(bytes, "AES"));
            }
        }
        String active = activeKeyId == null ? "" : activeKeyId.trim();
        if (this.keys.isEmpty()) {
            if (!active.isEmpty()) throw misconfigured("an active key id is set but no keys are configured");
            this.activeKeyId = null;
        } else if (active.isEmpty()) {
            this.activeKeyId = this.keys.keySet().iterator().next();
        } else {
            if (!this.keys.containsKey(active)) throw misconfigured("no key matches the active key id: " + active);
            this.activeKeyId = active;
        }
    }

    /** 키가 하나라도 설정되어 있는지 (화면에서 "비밀값 저장을 쓸 수 없음" 을 미리 안내할 때) */
    public boolean isConfigured() {
        return activeKeyId != null;
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public Set<String> keyIds() {
        return Set.copyOf(keys.keySet());
    }

    SecretKey key(String keyId) {
        return keys.get(keyId);
    }

    /** 기동 오류 (운영자가 보는 로그) : 키 값은 넣지 않고 키ID 만 적는다 */
    private static IllegalStateException misconfigured(String reason) {
        return new IllegalStateException("Invalid secret key configuration (LEXORMA_SECRET_KEYS / LEXORMA_SECRET_ACTIVE_KEY_ID): " + reason);
    }

    /** 키가 로그에 찍히지 않게 키ID 만 보여 준다 */
    @Override
    public String toString() {
        return "SecretKeyring{keyIds=" + keys.keySet() + ", active=" + activeKeyId + "}";
    }
}
