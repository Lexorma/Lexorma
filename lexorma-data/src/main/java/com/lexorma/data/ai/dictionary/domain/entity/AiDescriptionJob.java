package com.lexorma.data.ai.dictionary.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 설명 생성 실행 (AI-5, V10 TB_AI_EXPLN_CRT_EXCN) : 여러 대상의 제안을 서버 안의 제한된 실행기로 차례로 만든다. 별도 작업 큐 없음.
 * 대상 하나가 실패해도 이미 만든 제안은 남는다. 서버가 재시작되면 끝나지 않은 실행은 INTERRUPTED 로 닫는다 (자동 재개 없음).
 */
@Entity
@Table(name = "TB_AI_EXPLN_CRT_EXCN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiDescriptionJob extends BaseEntity {

    public enum Status {
        PENDING, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED, INTERRUPTED;

        public boolean active() {
            return this == PENDING || this == RUNNING;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_AI_EXPLN_CRT_EXCN")
    @SequenceGenerator(name = "SQ_AI_EXPLN_CRT_EXCN", sequenceName = "SQ_AI_EXPLN_CRT_EXCN", allocationSize = 50)
    @Column(name = "AI_EXPLN_CRT_EXCN_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** 요청자 (비동기 실행 컨텍스트 : 대상마다 이 계정의 권한을 다시 확인한다) */
    @Column(name = "ACNT_SN", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "EXCN_STTS_CD", length = 20, nullable = false)
    private Status status;

    /** 대상 목록 JSON [{targetType, tableId, columnId}] (요청 시점에 서버가 정한 것) */
    @Column(name = "TRGT_CN", columnDefinition = "TEXT", nullable = false)
    private String targets;

    @Column(name = "TRGT_CNT", nullable = false)
    private Integer targetCount;

    @Column(name = "CMPTN_CNT", nullable = false)
    private Integer completedCount;

    @Column(name = "FAIL_CNT", nullable = false)
    private Integer failedCount;

    @Column(name = "SKIP_CNT", nullable = false)
    private Integer skippedCount;

    @Column(name = "BGNG_DT")
    private LocalDateTime startedAt;

    @Column(name = "END_DT")
    private LocalDateTime endedAt;

    /** 오류 요약 JSON [{target, code}] (최대 50건, 원문 없이 오류 코드만) */
    @Column(name = "ERR_CN", columnDefinition = "TEXT")
    private String errors;

    public Audit audit() {
        return toAudit();
    }
}
