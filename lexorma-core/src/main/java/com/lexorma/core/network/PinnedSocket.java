package com.lexorma.core.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * 외부 연결의 실제 소켓 (SEC-1). 연결하려는 주소·포트를 {@link ConnectionPin} 으로 연결 직전에 다시 본다.
 * <ul>
 *   <li>프록시를 쓰지 않는다 ({@link Proxy#NO_PROXY}) : JVM 의 socksProxyHost 등으로 다른 곳을 거쳐 나가지 않는다</li>
 *   <li>풀리지 않은 이름으로 연결하려 하면 다시 풀지 않고, 검증한 이름일 때만 검증한 주소로 연결한다</li>
 *   <li>TLS 는 이 소켓 위에서 원래 호스트명으로 검증한다 (SNI·인증서 검증은 호출한 쪽 그대로)</li>
 * </ul>
 */
public final class PinnedSocket extends Socket {

    /** 판정과 다른 목적지 : 연결하지 않았다 (호출한 쪽의 오류 안에 원인으로 남는다) */
    public static final class BlockedConnectionException extends SocketException {
        public BlockedConnectionException() {
            super("Connection target is not allowed");
        }
    }

    private final ConnectionPin pin;

    public PinnedSocket(ConnectionPin pin) {
        super(Proxy.NO_PROXY);
        this.pin = pin;
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (pin == null || !(endpoint instanceof InetSocketAddress target)) throw blocked();
        InetAddress address;
        if (target.isUnresolved()) {
            if (pin.addresses().isEmpty() || !pin.host().equals(NetworkAddresses.normalizeHost(target.getHostString()))) throw blocked();
            address = pin.addresses().getFirst();
        } else {
            address = target.getAddress();
        }
        if (!pin.permits(address, target.getPort())) throw blocked();
        super.connect(new InetSocketAddress(address, target.getPort()), timeout);
    }

    private BlockedConnectionException blocked() throws IOException {
        close();
        return new BlockedConnectionException();
    }

    /** 오류의 원인 사슬에 연결 시점 차단이 있는지 */
    public static boolean isBlocked(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof BlockedConnectionException) return true;
        }
        return false;
    }
}
