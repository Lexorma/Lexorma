package com.lexorma.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 비밀값이 속한 자리 : 용도 + 소유 프로젝트 + 소유 객체 + 필드. 암호화할 때 AAD(추가 인증 데이터)로 묶인다.
 * <p>
 * 복호화는 같은 자리를 주어야만 성공한다 → 암호문을 다른 프로젝트·다른 객체·다른 필드의 칸에 복사해 넣어도 쓸 수 없다.
 * 그래서 <b>소유 객체의 ID 는 암호화하기 전에 정해져 있어야 한다</b> (ID 를 먼저 발급받고 암호화한다. ID 없이는 만들 수 없다).
 *
 * @param purpose   용도 (예: source-connection, ai-endpoint). 코드에 고정한 값을 쓴다
 * @param projectId 소유 프로젝트. 시스템 전역 비밀값이면 null
 * @param ownerId   소유 객체의 ID (연결 ID 등)
 * @param field     필드 (예: password, apiKey)
 */
public record SecretScope(String purpose, Long projectId, String ownerId, String field) {

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    public SecretScope {
        if (purpose == null || !PART.matcher(purpose).matches()) throw new IllegalArgumentException("purpose 가 올바르지 않습니다.");
        if (ownerId == null || !PART.matcher(ownerId).matches()) throw new IllegalArgumentException("ownerId 가 없습니다 : 소유 객체의 ID 를 먼저 정한 뒤 암호화합니다.");
        if (field == null || !PART.matcher(field).matches()) throw new IllegalArgumentException("field 가 올바르지 않습니다.");
    }

    /** 프로젝트가 가진 비밀값 (외부 DB 연결 등) */
    public static SecretScope ofProject(String purpose, Long projectId, Long ownerId, String field) {
        if (projectId == null) throw new IllegalArgumentException("projectId 가 없습니다.");
        return new SecretScope(purpose, projectId, ownerId == null ? null : String.valueOf(ownerId), field);
    }

    /** 시스템 전역 비밀값 (AI Endpoint 등) */
    public static SecretScope global(String purpose, String ownerId, String field) {
        return new SecretScope(purpose, null, ownerId, field);
    }

    /** AAD : 구분자가 값에 들어갈 수 없으므로(PART) 서로 다른 자리가 같은 바이트가 되지 않는다 */
    byte[] aad() {
        return ("lexorma-secret|" + purpose + "|" + (projectId == null ? "global" : "project:" + projectId) + "|" + ownerId + "|" + field).getBytes(StandardCharsets.UTF_8);
    }
}
