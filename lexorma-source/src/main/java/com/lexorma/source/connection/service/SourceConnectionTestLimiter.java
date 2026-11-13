package com.lexorma.source.connection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 연결 테스트 호출 제한 (AI-2b) : 연결 테스트가 서버를 통한 내부망 포트 탐색에 쓰이지 않도록 계정마다 1분에 {@code per-minute} 번,
 * 서버 전체에서 동시에 {@code max-concurrent} 개까지만 받는다. 서버 메모리에만 둔다 (서버마다 따로 센다).
 * shortcut: 인스턴스별 계수다. 여러 인스턴스로 운영하면 한도도 인스턴스 수만큼 늘어난다 — 공용 저장소의 제한이 필요해지면 바꾼다.
 */
@Component
public class SourceConnectionTestLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private final int perMinute;
    private final Semaphore concurrent;
    private final Map<Long, Deque<Long>> recent = new ConcurrentHashMap<>();

    public SourceConnectionTestLimiter(@Value("${lexorma.source.connection-test.per-minute:10}") int perMinute,
                                       @Value("${lexorma.source.connection-test.max-concurrent:4}") int maxConcurrent) {
        this.perMinute = perMinute;
        this.concurrent = new Semaphore(maxConcurrent);
    }

    /** 다 쓰면 close() 로 돌려준다 */
    public AutoCloseable acquire(Long accountId) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recent.computeIfAbsent(accountId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MILLIS) times.pollFirst();
            if (times.size() >= perMinute) throw new CustomException(ErrorCode.SOURCE_TEST_RATE_LIMITED);
            times.addLast(now);
        }
        if (!concurrent.tryAcquire()) throw new CustomException(ErrorCode.SOURCE_TEST_RATE_LIMITED);
        return concurrent::release;
    }
}
