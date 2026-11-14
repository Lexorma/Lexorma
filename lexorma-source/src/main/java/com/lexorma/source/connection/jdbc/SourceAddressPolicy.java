package com.lexorma.source.connection.jdbc;

import com.lexorma.core.network.NetworkAddresses.Cidr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 원본 DB 접속의 <b>운영자 상한</b> (사설·루프백 주소). SEC-1 부터 연결을 여는 것은 시스템 관리자의 허용 규칙(외부 연결 보안 관리, 기본 차단)이고,
 * 이 값은 운영자가 설정했을 때만 그 위에 더하는 상한이다 (AND).
 * <pre>
 * LEXORMA_SOURCE_ALLOWED_NETWORKS = 10.20.0.0/16,192.168.1.10,fd12:3456::/48   (비우면 상한 없음 — 관리자 규칙만 본다)
 * </pre>
 * 설정하면 판정한 주소 중 사설·루프백 주소는 이 대역 안이어야 하고, 실제 소켓({@link SourceGuardedSocketFactory})도 연결 직전에 다시 본다.
 * 절대 차단(링크 로컬·메타데이터 등)과 주소 판정은 {@link com.lexorma.core.network.NetworkAddresses} 가 한다.
 */
@Component
public class SourceAddressPolicy {

    private final List<Cidr> ceiling;

    public SourceAddressPolicy(@Value("${lexorma.source.allowed-networks:}") String allowedNetworks) {
        try {
            this.ceiling = Cidr.parseList(allowedNetworks);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid network in lexorma.source.allowed-networks: " + allowedNetworks);
        }
    }

    /** 사설·루프백 주소의 상한 (비면 상한 없음) */
    public List<Cidr> ceiling() {
        return ceiling;
    }
}
