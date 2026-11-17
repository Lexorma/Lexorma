package com.lexorma.core.logs.service;

import com.lexorma.core.logs.repository.LogsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 요청 로그(TB_LOG) 보관 기간 (DEC-14). 기본은 끔(0) — 지금까지처럼 지우지 않는다. {@code lexorma.retention.request-log-days} 를 1 이상으로 두면
 * 매일({@code lexorma.retention.request-log-cron}, 기본 03:30) 그 일수보다 오래된 요청 로그를 {@value #BATCH} 건씩 나눠 지운다.
 * <ul>
 *   <li>요청 로그는 다른 테이블이 참조하지 않는다 (FK 없음) — 지워도 참조 무결성이 깨지지 않는 운영 데이터라 이것만 정리 대상으로 둔다</li>
 *   <li>모델 버전·모델 변경 이력·원본 수집 이력·AI 평가 기준 데이터·외부 연결 결정 이력(감사)은 보존한다 (정리 대상이 아니다)</li>
 * </ul>
 */
@Slf4j
@Component
public class RequestLogRetention {

    static final int BATCH = 5000;

    private final LogsRepository logs;
    private final TransactionTemplate tx;
    private final int days;

    public RequestLogRetention(LogsRepository logs, TransactionTemplate tx, @Value("${lexorma.retention.request-log-days:0}") int days) {
        this.logs = logs;
        this.tx = tx;
        this.days = Math.max(days, 0);
        if (this.days > 0) log.info("요청 로그 보관 기간 : {}일 (오래된 요청 로그를 매일 정리합니다)", this.days);
    }

    @Scheduled(cron = "${lexorma.retention.request-log-cron:0 30 3 * * *}")
    public void scheduled() {
        if (days > 0) purge(days);
    }

    /** 기준 시각보다 오래된 요청 로그를 지운다. 지운 건수. 한 묶음마다 따로 커밋해 긴 잠금을 만들지 않는다 */
    public long purge(int retentionDays) {
        if (retentionDays <= 0) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long total = 0;
        while (true) {
            Integer deleted = tx.execute(s -> {
                List<Long> ids = logs.findIdsOlderThan(cutoff, org.springframework.data.domain.PageRequest.of(0, BATCH));
                if (ids.isEmpty()) return 0;
                logs.deleteAllByIdInBatch(ids);
                return ids.size();
            });
            if (deleted == null || deleted == 0) break;
            total += deleted;
            if (deleted < BATCH) break;
        }
        if (total > 0) log.info("오래된 요청 로그를 정리했습니다 : {}건 ({}일 이전)", total, retentionDays);
        return total;
    }
}
