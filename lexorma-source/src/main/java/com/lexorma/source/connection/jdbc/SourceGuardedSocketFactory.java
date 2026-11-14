package com.lexorma.source.connection.jdbc;

import com.lexorma.core.network.ConnectionPin;
import com.lexorma.core.network.PinnedSocket;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Properties;

/**
 * 원본 DB 접속에 쓰는 소켓 (AI-2b, SEC-1). JDBC 드라이버가 접속할 때 <b>실제로 연결하려는 주소</b>를 외부 연결 판정 결과({@link ConnectionPin})로 본다 :
 * 판정 때 푼 주소 가운데 하나이고, 적용한 규칙의 대역·포트, 절대 차단, 운영자 상한에 맞아야 한다. 판정 뒤에 이름이 다른 주소로 풀려도(DNS rebinding) 그 주소로는 연결하지 않는다.
 * 프록시를 거치지 않는다. TLS 는 이 소켓 위에서 드라이버가 호스트명으로 검증한다 (sslmode=verify-full 의 호스트명 검증은 그대로다).
 * <p>
 * PostgreSQL 드라이버는 {@code socketFactory} 속성의 클래스를 (Properties) 생성자로 만든다. 판정 결과는 같은 속성 묶음으로 받는다
 * (서버가 넣는 값이다. 사용자가 고르는 연결 옵션에는 없다). 판정 결과가 없으면 어디에도 연결하지 않는다.
 */
public final class SourceGuardedSocketFactory extends SocketFactory {

    private final ConnectionPin pin;

    public SourceGuardedSocketFactory(Properties info) {
        this.pin = ConnectionPin.readFrom(info);
    }

    @Override
    public Socket createSocket() {
        return new PinnedSocket(pin);
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
}
