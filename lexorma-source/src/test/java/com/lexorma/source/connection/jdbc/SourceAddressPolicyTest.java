package com.lexorma.source.connection.jdbc;

import com.lexorma.common.exception.domain.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceAddressPolicyTest {

    @Test
    @DisplayName("주소 정책 : 링크 로컬·메타데이터·멀티캐스트·미지정은 허용 목록에 있어도 막고, 루프백·사설은 허용 목록에 있을 때만, 공인 주소는 연다")
    void addressPolicy() throws Exception {
        List<SourceAddressPolicy.Network> none = List.of();
        List<SourceAddressPolicy.Network> allowed = SourceAddressPolicy.Network.parseList("10.20.0.0/16, 127.0.0.1, 169.254.0.0/16, fd12:3456::/32");
        for (String blocked : List.of("169.254.169.254", "169.254.1.1", "0.0.0.0", "0.1.2.3", "224.0.0.1", "255.255.255.255", "fe80::1", "fd00:ec2::254", "100.100.100.200", "::")) {
            assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName(blocked), allowed)).as(blocked).isFalse();
        }
        for (String restricted : List.of("127.0.0.1", "10.20.1.1", "192.168.0.10", "172.16.5.5", "100.64.0.1", "::1", "fd12:3456::1", "::ffff:127.0.0.1")) {
            assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName(restricted), none)).as(restricted).isFalse();
        }
        assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName("127.0.0.1"), allowed)).isTrue();
        assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName("::ffff:127.0.0.1"), allowed)).as("IPv4 매핑 주소는 IPv4 로 본다").isTrue();
        assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName("127.0.0.2"), allowed)).isFalse();
        assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName("10.20.255.1"), allowed)).isTrue();
        assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName("10.21.0.1"), allowed)).isFalse();
        assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName("fd12:3456:1::1"), allowed)).isTrue();
        assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName("8.8.8.8"), none)).isTrue();
        assertThat(SourceAddressPolicy.isAllowed(InetAddress.getByName("2001:4860:4860::8888"), none)).isTrue();
        // 허용 목록은 숫자 주소만 받는다 (이름을 풀지 않는다)
        assertThatThrownBy(() -> SourceAddressPolicy.Network.parse("db.internal")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SourceAddressPolicy.Network.parse("10.0.0.0/33")).isInstanceOf(IllegalStateException.class);
        assertThat(new SourceAddressPolicy("10.0.0.0/8,::1").allowedNetworks()).isEqualTo("10.0.0.0/8,0:0:0:0:0:0:0:1/128");
        assertThat(new SourceAddressPolicy("").isAllowed("127.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("소켓 : 실제로 연결하는 주소를 연결 직전에 본다 (미리 확인한 이름이 다른 주소로 풀려도 막힌다). 허용된 주소는 그대로 연결한다")
    void guardedSocket() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            SourceGuardedSocketFactory blocked = new SourceGuardedSocketFactory(new Properties());
            // 드라이버가 이미 푼 주소, 풀지 않은 이름 모두 연결하는 주소로 판단한다
            assertThatThrownBy(() -> blocked.createSocket().connect(new InetSocketAddress("127.0.0.1", port), 1000))
                    .isInstanceOf(SourceGuardedSocketFactory.BlockedAddressException.class);
            assertThatThrownBy(() -> blocked.createSocket().connect(InetSocketAddress.createUnresolved("localhost", port), 1000))
                    .isInstanceOf(SourceGuardedSocketFactory.BlockedAddressException.class);
            assertThatThrownBy(() -> blocked.createSocket("127.0.0.1", port)).isInstanceOf(SourceGuardedSocketFactory.BlockedAddressException.class);

            Properties properties = new Properties();
            properties.setProperty(SourceGuardedSocketFactory.ALLOWED_NETWORKS, "127.0.0.0/8");
            try (Socket socket = new SourceGuardedSocketFactory(properties).createSocket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                assertThat(socket.isConnected()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("JDBC 오류 분류 : SQLSTATE·원인으로 코드만 고른다 (메시지는 쓰지 않는다)")
    void errorClassification() {
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "28P01"), false)).isEqualTo(ErrorCode.SOURCE_AUTH_FAILED);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "42501"), true)).isEqualTo(ErrorCode.SOURCE_PERMISSION_DENIED);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "57014"), true)).isEqualTo(ErrorCode.SOURCE_QUERY_TIMEOUT);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "57P01"), true)).isEqualTo(ErrorCode.SOURCE_CONNECT_FAILED);
        assertThat(SourceJdbcErrors.classify(new SQLException("cache lookup failed", "XX000"), true)).isEqualTo(ErrorCode.SOURCE_CHANGED_DURING_COLLECTION);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "08001", new SocketTimeoutException()), false)).isEqualTo(ErrorCode.SOURCE_CONNECT_TIMEOUT);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "08006", new SocketTimeoutException()), true)).isEqualTo(ErrorCode.SOURCE_QUERY_TIMEOUT);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "08001", new ConnectException()), false)).isEqualTo(ErrorCode.SOURCE_CONNECT_FAILED);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "08001", new SSLHandshakeException("bad cert")), false)).isEqualTo(ErrorCode.SOURCE_TLS_FAILED);
        assertThat(SourceJdbcErrors.classify(new SQLException("The server does not support SSL.", "08004"), false)).isEqualTo(ErrorCode.SOURCE_TLS_FAILED);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "08001", new SourceGuardedSocketFactory.BlockedAddressException()), false))
                .isEqualTo(ErrorCode.SOURCE_HOST_NOT_ALLOWED);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "22P02"), true)).isEqualTo(ErrorCode.SOURCE_METADATA_FAILED);
        assertThat(SourceJdbcErrors.describe(new SQLException("password=secret", "28P01"))).isEqualTo("SQLException[28P01]").doesNotContain("secret");
    }
}
