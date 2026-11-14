package com.lexorma.source.connection.jdbc;

import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.network.ConnectionPin;
import com.lexorma.core.network.NetworkAddresses.Cidr;
import com.lexorma.core.network.PinnedSocket;
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
    @DisplayName("운영자 상한 (SEC-1) : 설정했을 때만 사설·루프백에 더하는 상한이다 (비면 상한 없음). 숫자 대역만 받고 잘못된 값이면 시작하지 않는다")
    void operatorCeiling() {
        assertThat(new SourceAddressPolicy("").ceiling()).isEmpty();
        assertThat(new SourceAddressPolicy("10.0.0.0/8, ::1").ceiling()).extracting(Cidr::toString).containsExactly("10.0.0.0/8", "::1/128");
        assertThatThrownBy(() -> new SourceAddressPolicy("db.internal")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SourceAddressPolicy("10.0.0.0/33")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("소켓 : 판정 결과(pin)가 없으면 어디에도 연결하지 않고, 판정한 주소·규칙 대역·포트·운영자 상한에 맞을 때만 연결한다 (DNS 가 다른 주소를 줘도 막힌다)")
    void guardedSocket() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            SourceGuardedSocketFactory none = new SourceGuardedSocketFactory(new Properties());
            assertThatThrownBy(() -> none.createSocket().connect(new InetSocketAddress(loopback, port), 1000)).isInstanceOf(PinnedSocket.BlockedConnectionException.class);
            assertThatThrownBy(() -> none.createSocket("127.0.0.1", port)).isInstanceOf(PinnedSocket.BlockedConnectionException.class);

            Properties allowed = properties(new ConnectionPin("localhost", port, List.of(loopback), Cidr.parse("127.0.0.0/8"), List.of()));
            try (Socket socket = new SourceGuardedSocketFactory(allowed).createSocket()) {
                socket.connect(new InetSocketAddress(loopback, port), 1000);
                assertThat(socket.isConnected()).isTrue();
            }
            // 풀리지 않은 이름은 다시 풀지 않고 판정한 주소로
            try (Socket socket = new SourceGuardedSocketFactory(allowed).createSocket()) {
                socket.connect(InetSocketAddress.createUnresolved("localhost", port), 1000);
                assertThat(socket.getInetAddress()).isEqualTo(loopback);
            }
            // 판정 뒤 이름이 다른 주소로 풀렸다 (재바인딩) : 판정한 주소가 아니면 연결하지 않는다
            Properties rebound = properties(new ConnectionPin("localhost", port, List.of(InetAddress.getByName("127.0.0.2")), Cidr.parse("127.0.0.0/8"), List.of()));
            assertThatThrownBy(() -> new SourceGuardedSocketFactory(rebound).createSocket().connect(new InetSocketAddress(loopback, port), 1000))
                    .isInstanceOf(PinnedSocket.BlockedConnectionException.class);
            // 포트가 다르다 · 운영자 상한 밖의 루프백
            Properties otherPort = properties(new ConnectionPin("localhost", port + 1, List.of(loopback), Cidr.parse("127.0.0.0/8"), List.of()));
            assertThatThrownBy(() -> new SourceGuardedSocketFactory(otherPort).createSocket().connect(new InetSocketAddress(loopback, port), 1000))
                    .isInstanceOf(PinnedSocket.BlockedConnectionException.class);
            Properties ceiling = properties(new ConnectionPin("localhost", port, List.of(loopback), Cidr.parse("127.0.0.0/8"), List.of(Cidr.parse("10.0.0.0/8"))));
            assertThatThrownBy(() -> new SourceGuardedSocketFactory(ceiling).createSocket().connect(new InetSocketAddress(loopback, port), 1000))
                    .isInstanceOf(PinnedSocket.BlockedConnectionException.class);
        }
    }

    private static Properties properties(ConnectionPin pin) {
        Properties properties = new Properties();
        pin.writeTo(properties);
        return properties;
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
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "08001", new PinnedSocket.BlockedConnectionException()), false))
                .isEqualTo(ErrorCode.SOURCE_HOST_NOT_ALLOWED);
        assertThat(SourceJdbcErrors.classify(new SQLException("x", "22P02"), true)).isEqualTo(ErrorCode.SOURCE_METADATA_FAILED);
        assertThat(SourceJdbcErrors.describe(new SQLException("password=secret", "28P01"))).isEqualTo("SQLException[28P01]").doesNotContain("secret");
    }
}
