package com.lexorma.source.collection.service;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이 서버 인스턴스가 맡고 있는 수집 실행 (AI-1c). 실행 행에 서버 아이디를 남겨, 멈춘 실행을 정리할 때 "지금 이 서버에서 돌고 있는 실행" 을 잘못 끊지 않는다.
 * 서버 아이디는 기동할 때마다 새로 만든다 (호스트명 + 임의 값) : 재시작 전의 실행은 다른 서버의 실행으로 보고 살아 있음 확인 시각으로만 판단한다.
 */
@Component
public class SourceCollectionInstance {

    private final String serverId;
    private final Set<Long> active = ConcurrentHashMap.newKeySet();

    public SourceCollectionInstance() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        String suffix = "/" + UUID.randomUUID().toString().substring(0, 8);
        this.serverId = (host.length() > 200 ? host.substring(0, 200) : host) + suffix;
    }

    public String serverId() {
        return serverId;
    }

    void register(Long executionId) {
        active.add(executionId);
    }

    void release(Long executionId) {
        active.remove(executionId);
    }

    boolean isActive(Long executionId) {
        return active.contains(executionId);
    }
}
