package com.lexorma.core.network.http;

import com.lexorma.core.network.ConnectionDecision;
import com.lexorma.core.network.PinnedSocket;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Result;
import com.lexorma.core.network.service.ExternalConnectionGuard;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 판정이 끝난 HTTP 대상 하나 ({@link ExternalHttpClients#open}). 이 대상의 호스트·포트로만 연결된다 (다른 호스트는 이름이 풀리지 않고, 다른 포트는 소켓이 막는다).
 * 첫 응답·첫 실패를 연결 시도의 결과로 남긴다 (연결됨 / 실패 / 연결 시점 차단). 다 쓰면 닫는다.
 */
public final class GuardedHttpTarget implements AutoCloseable {

    private final ConnectionDecision decision;
    private final CloseableHttpClient client;
    private final ExternalConnectionGuard guard;
    private final AtomicBoolean finished = new AtomicBoolean();

    GuardedHttpTarget(ConnectionDecision decision, CloseableHttpClient client, ExternalConnectionGuard guard) {
        this.decision = decision;
        this.client = client;
        this.guard = guard;
    }

    public ConnectionDecision decision() {
        return decision;
    }

    /** Spring RestClient 용 (AI-3) : {@code RestClient.builder().requestFactory(target.requestFactory())} */
    public ClientHttpRequestFactory requestFactory() {
        ClientHttpRequestInterceptor outcome = (request, body, execution) -> {
            try {
                ClientHttpResponse response = execution.execute(request, body);
                if (finished.compareAndSet(false, true)) guard.finish(decision, Result.CONNECTED, null);
                return response;
            } catch (IOException e) {
                if (finished.compareAndSet(false, true)) {
                    boolean blocked = PinnedSocket.isBlocked(e);
                    guard.finish(decision, blocked ? Result.BLOCKED : Result.FAILED, blocked ? Reason.TARGET_CHANGED : Reason.CONNECT_FAILED);
                }
                throw e;
            }
        };
        return new InterceptingClientHttpRequestFactory(new HttpComponentsClientHttpRequestFactory(client), List.of(outcome));
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
