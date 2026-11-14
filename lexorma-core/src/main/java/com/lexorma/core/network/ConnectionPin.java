package com.lexorma.core.network;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 허용 판정이 끝난 연결 목적지 (SEC-1). 실제 소켓이 연결 직전에 이것으로 다시 본다 ({@link PinnedSocket}) :
 * 판정 때 푼 주소 가운데 하나여야 하고, 절대 차단이 아니며, 적용한 규칙의 대역·포트와 운영자 상한(사설·루프백)에 맞아야 한다.
 * 판정 뒤에 DNS 가 다른 주소를 돌려줘도(재바인딩) 그 주소로는 연결하지 않는다.
 *
 * @param host      요청한 호스트 (비교용 표기)
 * @param addresses 판정 때 풀어 검증한 주소 (연결은 이 주소로만)
 * @param ceiling   운영자 상한 (사설·루프백에만 적용, 비면 상한 없음)
 */
public record ConnectionPin(String host, int port, List<InetAddress> addresses, NetworkAddresses.Cidr cidr, List<NetworkAddresses.Cidr> ceiling) {

    /** JDBC 드라이버는 소켓 팩토리를 Properties 로 만든다 : 서버가 넣는 속성 이름 (사용자가 고르는 연결 옵션에는 없다) */
    public static final String HOST = "lexormaPinHost";
    public static final String PORT = "lexormaPinPort";
    public static final String ADDRESSES = "lexormaPinAddresses";
    public static final String CIDR = "lexormaPinCidr";
    public static final String CEILING = "lexormaPinCeiling";

    public ConnectionPin {
        addresses = List.copyOf(addresses);
        ceiling = ceiling == null ? List.of() : List.copyOf(ceiling);
    }

    public boolean permits(InetAddress address, int port) {
        return address != null && port == this.port && addresses.contains(address) && !NetworkAddresses.isBlocked(address) && cidr.contains(address)
                && (ceiling.isEmpty() || !NetworkAddresses.isPrivateOrLoopback(address) || ceiling.stream().anyMatch(network -> network.contains(address)));
    }

    public void writeTo(Properties properties) {
        properties.setProperty(HOST, host);
        properties.setProperty(PORT, Integer.toString(port));
        properties.setProperty(ADDRESSES, String.join(",", addresses.stream().map(NetworkAddresses::text).toList()));
        properties.setProperty(CIDR, cidr.toString());
        properties.setProperty(CEILING, String.join(",", ceiling.stream().map(NetworkAddresses.Cidr::toString).toList()));
    }

    /** 속성이 없거나 형식이 맞지 않으면 null : 그 소켓은 어디에도 연결하지 않는다 */
    public static ConnectionPin readFrom(Properties properties) {
        try {
            if (properties == null || properties.getProperty(HOST) == null) return null;
            List<InetAddress> addresses = new ArrayList<>();
            for (String value : properties.getProperty(ADDRESSES, "").split(",")) {
                InetAddress address = NetworkAddresses.literal(value);
                if (address == null) return null;
                addresses.add(address);
            }
            return new ConnectionPin(properties.getProperty(HOST), Integer.parseInt(properties.getProperty(PORT)), addresses,
                    NetworkAddresses.Cidr.parse(properties.getProperty(CIDR)), NetworkAddresses.Cidr.parseList(properties.getProperty(CEILING)));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
