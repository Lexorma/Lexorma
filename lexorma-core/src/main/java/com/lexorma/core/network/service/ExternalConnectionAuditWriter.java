package com.lexorma.core.network.service;

import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.ConnectionDecision;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Result;
import com.lexorma.core.network.domain.entity.ExternalConnectionAttempt;
import com.lexorma.core.network.repository.ExternalConnectionAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결 시도 기록 (SEC-1) : 부른 쪽 트랜잭션과 따로 커밋한다 (부른 쪽이 롤백해도 시도는 남는다). 기록이 실패하면 예외 — 부른 쪽은 연결하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ExternalConnectionAuditWriter {

    private final ExternalConnectionAttemptRepository attempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long begin(ConnectionDecision decision, ExecutionContext context) {
        return attempts.saveAndFlush(ExternalConnectionAttempt.builder().connectionType(decision.connectionType()).host(decision.host()).port(decision.port())
                .addresses(decision.addresses().isEmpty() ? null : truncate(decision.addressText())).ruleId(decision.ruleId())
                .result((decision.allowed() ? Result.ALLOWED : Result.DENIED).name()).reason(decision.reason() == null ? null : decision.reason().name())
                .executionType(context == null ? null : context.type().name()).requestId(decision.requestId()).build()).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long attemptId, Result result, Reason reason) {
        ExternalConnectionAttempt attempt = attempts.findById(attemptId).orElseThrow(() -> new IllegalStateException("connection attempt not found"));
        attempt.setResult(result.name());
        attempt.setReason(reason == null ? null : reason.name());
        attempts.saveAndFlush(attempt);
    }

    private static String truncate(String value) {
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
