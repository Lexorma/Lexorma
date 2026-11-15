package com.lexorma.core.network.http;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.ConnectionDecision;
import com.lexorma.core.network.ConnectionPin;
import com.lexorma.core.network.ConnectionType;
import com.lexorma.core.network.NetworkAddresses;
import com.lexorma.core.network.PinnedSocket;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.service.ExternalConnectionGuard;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * 외부 HTTP 연결 (SEC-1) : AI Endpoint(AI-3) 등 Lexorma 가 HTTP 로 나가는 연결은 모두 여기서 연다. <b>다른 HTTP 클라이언트를 만들지 않는다.</b>
 * AI-3 은 {@link GuardedHttpTarget#requestFactory()} 로 Spring RestClient 를 만든다 ({@code RestClient.builder().requestFactory(...)}).
 * <ul>
 *   <li>정책 : {@link ExternalConnectionGuard} 로 연결 유형별 관리자 규칙을 판정하고 연결 시도를 남긴다. 차단이면 연결하지 않는다</li>
 *   <li>DNS 재바인딩 : 이름 풀기는 판정 때 검증한 주소만 돌려준다 (다시 풀지 않는다). 소켓({@link PinnedSocket})도 연결 직전에 주소·포트·대역을 다시 본다</li>
 *   <li>TLS : 기본 전략(JVM 신뢰 저장소, 호스트명 검증) 그대로. SNI·인증서 검증은 원래 호스트명으로 한다. 검증을 끄지 않는다</li>
 *   <li>리다이렉트는 따라가지 않는다 (3xx 를 그대로 돌려준다). 프록시를 쓰지 않는다 (경로 계획·소켓 모두). 자동 재시도 없음</li>
 *   <li>요청마다 새 연결 (연결을 다시 쓰지 않는다) : 정책을 바꾼 뒤 예전 연결이 남지 않는다. 대상은 요청 한 번(또는 한 작업)마다 연다</li>
 * </ul>
 */
@Service
public class ExternalHttpClients {

    private final ExternalConnectionGuard guard;
    private final int connectTimeoutSeconds;
    private final int responseTimeoutSeconds;

    public ExternalHttpClients(ExternalConnectionGuard guard,
                               @Value("${lexorma.external.http.connect-timeout-seconds:10}") int connectTimeoutSeconds,
                               @Value("${lexorma.external.http.response-timeout-seconds:120}") int responseTimeoutSeconds) {
        this.guard = guard;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.responseTimeoutSeconds = responseTimeoutSeconds;
    }

    /**
     * 판정하고 이 대상에만 연결하는 클라이언트를 연다. 차단이면 예외 (EXTERNAL_CONNECTION_BLOCKED, 시도는 기록된다). 다 쓰면 닫는다.
     *
     * @param uri 대상 (http·https, 계정 정보 없음). 이 대상의 호스트·포트로만 연결된다
     */
    public GuardedHttpTarget open(ConnectionType type, URI uri, ExecutionContext context) {
        return open(type, uri, context, responseTimeoutSeconds);
    }

    /**
     * {@link #open(ConnectionType, URI, ExecutionContext)} 에 응답 제한 시간만 따로 준다 (연결 테스트처럼 짧게 끝내야 하는 요청).
     * 기본 응답 제한 시간보다 길게 늘리지 않는다. 차단이면 {@link ExternalConnectionBlockedException} (사유 코드 포함).
     */
    public GuardedHttpTarget open(ConnectionType type, URI uri, ExecutionContext context, int responseTimeoutSeconds) {
        if (type != ConnectionType.AI_HTTP) throw new IllegalArgumentException("HTTP 연결 유형이 아닙니다.");
        if (responseTimeoutSeconds < 1) throw new IllegalArgumentException("응답 제한 시간이 올바르지 않습니다.");
        String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new ExternalConnectionBlockedException(Reason.INVALID_HOST, Messages.get("http 또는 https 주소만 연결할 수 있습니다."));
        if (uri.getRawUserInfo() != null) throw new ExternalConnectionBlockedException(Reason.INVALID_HOST, Messages.get("주소에 계정 정보를 넣을 수 없습니다."));
        if (uri.getHost() == null) throw new ExternalConnectionBlockedException(Reason.INVALID_HOST, Messages.get("주소에 호스트가 없습니다."));
        int port = uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        ConnectionDecision decision = guard.authorize(type, uri.getHost(), port, List.of(), context);
        if (!decision.allowed()) {
            throw new ExternalConnectionBlockedException(decision.reason(), ExternalConnectionGuard.reasonText(decision));
        }
        int timeout = Math.min(responseTimeoutSeconds, this.responseTimeoutSeconds);
        return new GuardedHttpTarget(decision, client(decision.pin(), connectTimeoutSeconds, timeout), guard);
    }

    static CloseableHttpClient client(ConnectionPin pin, int connectTimeoutSeconds, int responseTimeoutSeconds) {
        DnsResolver dns = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                // 판정 때 검증한 이름만, 검증한 주소로 (다시 풀지 않는다)
                if (!pin.host().equals(NetworkAddresses.normalizeHost(host))) throw new UnknownHostException(host);
                return pin.addresses().toArray(InetAddress[]::new);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var tls = RegistryBuilder.<TlsSocketStrategy>create().register("https", DefaultClientTlsStrategy.createDefault()).build();
        var operator = new DefaultHttpClientConnectionOperator(proxy -> new PinnedSocket(pin), DefaultSchemePortResolver.INSTANCE, dns, tls);
        var manager = new BasicHttpClientConnectionManager(operator, ManagedHttpClientConnectionFactory.INSTANCE);
        manager.setConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(connectTimeoutSeconds))
                .setSocketTimeout(Timeout.ofSeconds(responseTimeoutSeconds)).build());
        return HttpClients.custom()
                .setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .setConnectionReuseStrategy((request, response, context) -> false)
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(responseTimeoutSeconds)).build())
                .build();
    }
}
