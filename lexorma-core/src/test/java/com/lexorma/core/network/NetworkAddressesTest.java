package com.lexorma.core.network;

import com.lexorma.core.network.NetworkAddresses.Cidr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkAddressesTest {

    @Test
    @DisplayName("절대 차단 (SEC-10~13) : 메타데이터·링크 로컬·멀티캐스트·미지정·예약, IPv6 링크 로컬·Teredo·IPv4 호환, IPv4 를 품은 IPv6(mapped·NAT64·6to4)는 품은 IPv4 로 본다")
    void blocked() throws Exception {
        for (String address : List.of("169.254.169.254", "169.254.1.1", "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.250", "255.255.255.255", "240.0.0.1",
                "100.100.100.200", "168.63.129.16", "::", "fe80::1", "fd00:ec2::254", "ff02::1", "2001:0:4136:e378::1", "::a9fe:a9fe",
                "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1")) {
            assertThat(NetworkAddresses.isBlocked(InetAddress.getByName(address))).as(address).isTrue();
        }
        for (String address : List.of("127.0.0.1", "::1", "10.1.2.3", "192.168.10.5", "8.8.8.8", "2001:4860:4860::8888", "64:ff9b::808:808", "2002:808:808::1")) {
            assertThat(NetworkAddresses.isBlocked(InetAddress.getByName(address))).as(address).isFalse();
        }
        assertThat(NetworkAddresses.isBlockedHostName("Metadata.Google.Internal.")).isTrue();
        assertThat(NetworkAddresses.isPrivateOrLoopback(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(NetworkAddresses.isPrivateOrLoopback(InetAddress.getByName("fd12::1"))).isTrue();
        assertThat(NetworkAddresses.isPrivateOrLoopback(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    @DisplayName("주소 표기 : 표준 숫자 주소·호스트명만 받는다 (127.1·2130706433·0x7f.1·08.1.1.1·와일드카드·다중 호스트·포트 섞기 거부). 숫자 주소는 이름을 풀지 않는다")
    void notation() {
        for (String host : List.of("db.internal.local", "localhost", "a-b.example.com", "127.0.0.1", "10.0.0.5", "::1", "[fd12::5]", "DB.Example.COM.")) {
            assertThat(NetworkAddresses.isValidHost(host)).as(host).isTrue();
        }
        for (String host : List.of("127.1", "2130706433", "0x7f.0.0.1", "08.1.1.1", "*.example.com", "db1,db2", "db:5432", "db/x", "-db.example.com", "fe80::1%eth0", "")) {
            assertThat(NetworkAddresses.isValidHost(host)).as(host).isFalse();
        }
        assertThat(NetworkAddresses.literal("db.example.com")).isNull();
        assertThat(NetworkAddresses.literal("127.1")).isNull();
        assertThat(NetworkAddresses.literal("[::1]")).isNotNull();
    }

    @Test
    @DisplayName("IPv6 숫자 호스트 정규화 : RFC 5952 표기로 맞춘다 (대괄호·대소문자·확장 표기 무관). 이름·IPv4·IPv4-mapped·zone 표기는 바꾸지 않는다")
    void ipv6HostNormalization() {
        for (String host : List.of("::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]", "0000:0000:0000:0000:0000:0000:0000:0001", " [::1] ")) {
            assertThat(NetworkAddresses.normalizeHost(host)).as(host).isEqualTo("::1");
        }
        assertThat(NetworkAddresses.normalizeHost("FD12:0:0:0:0:0:0:A")).isEqualTo("fd12::a");
        assertThat(NetworkAddresses.normalizeHost("2001:DB8:0:0:1:0:0:1")).isEqualTo("2001:db8::1:0:0:1");
        assertThat(NetworkAddresses.normalizeHost("fd12::b")).isNotEqualTo(NetworkAddresses.normalizeHost("fd12::a"));
        assertThat(NetworkAddresses.normalizeHost("DB.Example.COM.")).isEqualTo("db.example.com");
        assertThat(NetworkAddresses.normalizeHost("127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(NetworkAddresses.normalizeHost("::FFFF:169.254.169.254")).as("IPv4-mapped 은 표기를 바꾸지 않고, 품은 IPv4 로 판정한다").isEqualTo("::ffff:169.254.169.254");
        assertThat(NetworkAddresses.isBlocked(NetworkAddresses.literal("::FFFF:A9FE:A9FE"))).isTrue();
        assertThat(NetworkAddresses.normalizeHost("fe80::1%eth0")).isEqualTo("fe80::1%eth0");
        assertThat(NetworkAddresses.isValidHost("fe80::1%eth0")).isFalse();
        assertThat(NetworkAddresses.isBlocked(NetworkAddresses.literal(NetworkAddresses.normalizeHost("FE80:0:0:0:0:0:0:1")))).isTrue();
    }

    @Test
    @DisplayName("CIDR : 숫자만, 호스트 부분은 0 으로 맞춘다. 포함·안에 있음")
    void cidr() throws Exception {
        assertThat(Cidr.parse("10.10.1.5/16")).hasToString("10.10.0.0/16");
        assertThat(Cidr.parse("127.0.0.1")).hasToString("127.0.0.1/32");
        assertThat(Cidr.parse("FE80::1/10")).hasToString("fe80::/10");
        assertThat(Cidr.parse("fd00:ec2::254")).hasToString("fd00:ec2::254/128");
        assertThat(Cidr.parse("2001:db8:0:0:1:0:0:1/128")).as("가장 긴 0 묶음만 줄인다").hasToString("2001:db8::1:0:0:1/128");
        assertThat(Cidr.parse("::/0")).hasToString("::/0");
        assertThat(Cidr.parse("::1")).hasToString("::1/128");
        assertThat(Cidr.parse("10.10.0.0/16").contains(InetAddress.getByName("10.10.255.1"))).isTrue();
        assertThat(Cidr.parse("10.10.0.0/16").contains(InetAddress.getByName("10.11.0.1"))).isFalse();
        assertThat(Cidr.parse("10.10.0.0/16").contains(InetAddress.getByName("::1"))).isFalse();
        assertThat(Cidr.parse("169.254.169.254/32").within(Cidr.parse("169.254.0.0/16"))).isTrue();
        assertThat(Cidr.parse("169.0.0.0/8").within(Cidr.parse("169.254.0.0/16"))).isFalse();
        for (String bad : List.of("db.internal/24", "10.0.0.0/33", "10.0.0.0/x", "127.1/8", "")) {
            assertThatThrownBy(() -> Cidr.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("고정 소켓 : 판정한 주소·포트·대역만, 풀리지 않은 이름은 다시 풀지 않고 판정한 주소로, 시스템 프록시 설정을 거치지 않는다 (SEC-18)")
    void pinnedSocket() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            ConnectionPin pin = new ConnectionPin("api.local.test", port, List.of(loopback), Cidr.parse("127.0.0.1/32"), List.of());
            String previous = System.getProperty("socksProxyHost");
            System.setProperty("socksProxyHost", "203.0.113.1"); // 닿지 않는 프록시 : 프록시를 쓰면 실패한다
            try (Socket socket = new PinnedSocket(pin)) {
                socket.connect(InetSocketAddress.createUnresolved("api.local.test", port), 2000);
                assertThat(socket.getInetAddress()).isEqualTo(loopback);
            } finally {
                if (previous == null) System.clearProperty("socksProxyHost");
                else System.setProperty("socksProxyHost", previous);
            }
            assertThatThrownBy(() -> new PinnedSocket(pin).connect(InetSocketAddress.createUnresolved("other.local.test", port), 1000))
                    .isInstanceOf(PinnedSocket.BlockedConnectionException.class);
            assertThatThrownBy(() -> new PinnedSocket(null).connect(new InetSocketAddress(loopback, port), 1000)).isInstanceOf(PinnedSocket.BlockedConnectionException.class);
            // 속성으로 주고받아도 같다 (JDBC 소켓 팩토리)
            Properties properties = new Properties();
            pin.writeTo(properties);
            assertThat(ConnectionPin.readFrom(properties)).isEqualTo(pin);
            properties.setProperty(ConnectionPin.ADDRESSES, "db.example.com");
            assertThat(ConnectionPin.readFrom(properties)).isNull();
        }
        // 숫자 IPv6 호스트는 표기가 달라도 같은 호스트 (드라이버가 어느 표기로 넘겨도)
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("::1"))) {
            int port = server.getLocalPort();
            ConnectionPin pin = new ConnectionPin(NetworkAddresses.normalizeHost("0:0:0:0:0:0:0:1"), port, List.of(InetAddress.getByName("::1")), Cidr.parse("::1/128"), List.of());
            for (String host : List.of("::1", "[::1]", "0:0:0:0:0:0:0:1")) {
                try (Socket socket = new PinnedSocket(pin)) {
                    socket.connect(InetSocketAddress.createUnresolved(host, port), 2000);
                    assertThat(socket.isConnected()).as(host).isTrue();
                }
            }
            assertThatThrownBy(() -> new PinnedSocket(pin).connect(InetSocketAddress.createUnresolved("::2", port), 1000))
                    .isInstanceOf(PinnedSocket.BlockedConnectionException.class);
        }
    }
}
