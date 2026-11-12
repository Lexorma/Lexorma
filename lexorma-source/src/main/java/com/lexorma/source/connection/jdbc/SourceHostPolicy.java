package com.lexorma.source.connection.jdbc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 원본 DB 로 접속할 수 있는 호스트의 범위 (운영자 정책). Lexorma 서버가 사용자가 적은 주소로 접속하게 되므로
 * 운영자가 범위를 좁힐 수 있게 한다.
 * <pre>
 * LEXORMA_SOURCE_ALLOWED_HOSTS = db1.internal,*.db.internal,10.20.30.40     (비우면 제한 없음)
 * </pre>
 * 이름 그대로 또는 {@code *.} 로 시작하는 접미사를 비교한다 (대소문자 무시). 이 확인은 <b>문자열</b>에 대한 것이다 :
 * 등록·수정할 때와 도구를 실행하기 직전에 본다. 이름이 실제로 어느 주소로 풀리는지(DNS)와 주소 대역 제한은 실제 접속을 만드는 AI-2 에서 더한다.
 */
@Component
public class SourceHostPolicy {

    private final List<String> allowed;

    public SourceHostPolicy(@Value("${lexorma.source.allowed-hosts:}") String allowedHosts) {
        this.allowed = allowedHosts == null ? List.of()
                : Arrays.stream(allowedHosts.split(",")).map(entry -> entry.trim().toLowerCase(Locale.ROOT)).filter(entry -> !entry.isEmpty()).toList();
    }

    /** 제한을 두었는지 */
    public boolean isRestricted() {
        return !allowed.isEmpty();
    }

    public boolean isAllowed(String host) {
        if (allowed.isEmpty()) return true;
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(entry -> entry.startsWith("*.") ? value.endsWith(entry.substring(1)) && value.length() > entry.length() - 1 : value.equals(entry));
    }
}
