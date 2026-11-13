package com.lexorma.source.connection.jdbc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 원본 DB 로 접속할 수 있는 <b>주소</b>(IP)의 범위 (운영자 정책, AI-2b). {@link SourceHostPolicy} 가 호스트 문자열을 보는 것과 달리 이름이 풀린 실제 주소를 본다.
 * <pre>
 * LEXORMA_SOURCE_ALLOWED_NETWORKS = 10.20.0.0/16,192.168.1.10,fd12:3456::/48   (비우면 사설·루프백 주소로는 접속하지 않는다)
 * </pre>
 * <ul>
 *   <li><b>항상 차단</b> : 미지정(0.0.0.0, ::, 0.0.0.0/8), 링크 로컬(169.254.0.0/16 — 클라우드 메타데이터 169.254.169.254 포함, fe80::/10), 멀티캐스트, 브로드캐스트,
 *       그 밖의 클라우드 메타데이터 주소(fd00:ec2::254, 100.100.100.200). 허용 목록에 넣어도 열리지 않는다</li>
 *   <li><b>허용 목록에 있을 때만</b> : 루프백(127.0.0.0/8, ::1), 사설(10/8, 172.16/12, 192.168/16), 공유(100.64.0.0/10), ULA(fc00::/7), 사이트 로컬(fec0::/10)</li>
 *   <li><b>공인 주소</b> : 허용 (호스트 범위는 {@link SourceHostPolicy} 로 좁힌다)</li>
 * </ul>
 * 이 판단은 실제로 접속하는 소켓({@link SourceGuardedSocketFactory})이 접속 직전에 다시 한다 : 미리 확인한 뒤 DNS 가 다른 주소를 돌려주어도(DNS rebinding) 그 주소로는 접속하지 않는다.
 */
@Component
public class SourceAddressPolicy {

    private static final List<Network> PRIVATE = Network.parseList("127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,fc00::/7,fec0::/10");
    private static final List<Network> BLOCKED = Network.parseList("0.0.0.0/8,169.254.0.0/16,255.255.255.255/32,fe80::/10,fd00:ec2::254/128,100.100.100.200/32");

    private final String allowedNetworks;
    private final List<Network> allowed;

    public SourceAddressPolicy(@Value("${lexorma.source.allowed-networks:}") String allowedNetworks) {
        this.allowed = Network.parseList(allowedNetworks);
        this.allowedNetworks = String.join(",", allowed.stream().map(Network::toString).toList());
    }

    /** 허용한 사설·루프백 대역 (소켓 팩토리에 넘기는 표기) */
    public String allowedNetworks() {
        return allowedNetworks;
    }

    /** 이름이 풀리는 주소가 모두 허용되는지 (접속 전 확인 : 실제 접속 주소는 소켓이 다시 본다) */
    public boolean isAllowed(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addresses.length > 0 && Arrays.stream(addresses).allMatch(address -> isAllowed(address, allowed));
        } catch (UnknownHostException | SecurityException e) {
            // 풀리지 않는 이름은 여기서 막지 않는다 : 접속 실패(SOURCE_CONNECT_FAILED)로 알린다
            return true;
        }
    }

    public static boolean isAllowed(InetAddress address, List<Network> allowed) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress() || BLOCKED.stream().anyMatch(network -> network.contains(address))) {
            return false;
        }
        boolean restricted = address.isLoopbackAddress() || address.isSiteLocalAddress() || PRIVATE.stream().anyMatch(network -> network.contains(address));
        return !restricted || allowed.stream().anyMatch(network -> network.contains(address));
    }

    /** 주소 대역 (CIDR). 접두 길이가 없으면 주소 하나 */
    public record Network(byte[] base, int prefix) {

        public static List<Network> parseList(String value) {
            List<Network> networks = new ArrayList<>();
            if (value == null) return networks;
            for (String entry : value.split(",")) {
                if (!entry.isBlank()) networks.add(parse(entry.trim()));
            }
            return List.copyOf(networks);
        }

        public static Network parse(String value) {
            int slash = value.indexOf('/');
            String address = slash < 0 ? value : value.substring(0, slash);
            // 이름을 풀지 않는다 : 숫자 주소만 받는다
            if (!address.matches("[0-9A-Fa-f:.]+")) throw new IllegalStateException("Invalid network in lexorma.source.allowed-networks: " + value);
            try {
                byte[] bytes = InetAddress.getByName(address).getAddress();
                int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(value.substring(slash + 1));
                if (prefix < 0 || prefix > bytes.length * 8) throw new IllegalStateException("Invalid network in lexorma.source.allowed-networks: " + value);
                return new Network(bytes, prefix);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalStateException("Invalid network in lexorma.source.allowed-networks: " + value);
            }
        }

        public boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != base.length) return false;
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (bytes[i] != base[i]) return false;
            }
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (bytes[full] & mask) == (base[full] & mask);
        }

        @Override
        public String toString() {
            try {
                InetAddress address = InetAddress.getByAddress(base);
                String text = address instanceof Inet6Address ? address.getHostAddress() : ((Inet4Address) address).getHostAddress();
                return text + "/" + prefix;
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Network network && prefix == network.prefix && Arrays.equals(base, network.base);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(base) + prefix;
        }
    }
}
