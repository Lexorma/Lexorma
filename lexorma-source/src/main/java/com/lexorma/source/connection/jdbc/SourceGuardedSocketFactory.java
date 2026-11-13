package com.lexorma.source.connection.jdbc;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Properties;

/**
 * 원본 DB 접속에 쓰는 소켓 (AI-2b). JDBC 드라이버가 접속할 때 <b>실제로 연결하려는 주소</b>를 {@link SourceAddressPolicy} 로 본다.
 * 미리 확인한 뒤 이름이 다른 주소로 풀려도(DNS rebinding) 그 주소로는 연결하지 않는다. 확인한 주소에 그대로 연결하므로 확인과 접속 사이에 다시 풀지 않는다.
 * TLS 는 이 소켓 위에서 드라이버가 호스트명으로 검증한다 (sslmode=verify-full 의 호스트명 검증은 그대로다).
 * <p>
 * PostgreSQL 드라이버는 {@code socketFactory} 속성의 클래스를 (Properties) 생성자로 만든다. 허용 대역은 같은 속성 묶음의 {@link #ALLOWED_NETWORKS} 로 받는다
 * (서버가 넣는 값이다. 사용자가 고르는 연결 옵션에는 없다).
 */
public final class SourceGuardedSocketFactory extends SocketFactory {

    public static final String ALLOWED_NETWORKS = "lexormaAllowedNetworks";

    private final List<SourceAddressPolicy.Network> allowed;

    public SourceGuardedSocketFactory(Properties info) {
        this.allowed = SourceAddressPolicy.Network.parseList(info == null ? null : info.getProperty(ALLOWED_NETWORKS));
    }

    /** 접속 정책에 맞지 않는 주소 : 드라이버의 접속 오류 안에 원인으로 남는다 */
    public static final class BlockedAddressException extends SocketException {
        BlockedAddressException() {
            super("Source address is not allowed");
        }
    }

    @Override
    public Socket createSocket() {
        return new GuardedSocket(allowed);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connected(new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return connected(new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connected(new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return connected(new InetSocketAddress(address, port));
    }

    private Socket connected(InetSocketAddress address) throws IOException {
        Socket socket = createSocket();
        socket.connect(address);
        return socket;
    }

    private static final class GuardedSocket extends Socket {

        private final List<SourceAddressPolicy.Network> allowed;

        GuardedSocket(List<SourceAddressPolicy.Network> allowed) {
            this.allowed = allowed;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress target)) throw new BlockedAddressException();
            // 드라이버가 이미 푼 주소면 그것을, 풀지 않은 주소면 여기서 한 번 풀어 그 주소로만 연결한다
            InetAddress address = target.isUnresolved() ? InetAddress.getByName(target.getHostString()) : target.getAddress();
            if (!SourceAddressPolicy.isAllowed(address, allowed)) {
                close();
                throw new BlockedAddressException();
            }
            super.connect(new InetSocketAddress(address, target.getPort()), timeout);
        }
    }
}
