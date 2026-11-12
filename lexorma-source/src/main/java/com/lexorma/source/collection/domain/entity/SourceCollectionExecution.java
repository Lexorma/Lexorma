package com.lexorma.source.collection.domain.entity;

import com.lexorma.core.execution.ExecutionType;
import com.lexorma.source.collection.domain.enums.CollectionStatus;
import com.lexorma.source.collection.domain.enums.CollectionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 원본 카탈로그 수집 한 번 (AI-1c). 등록(RUNNING) → 결과 반영(COMPLETED) 또는 실패·취소. 상태는 연결 행을 잠근 트랜잭션에서만 바꾼다.
 * <ul>
 *   <li>jobId : 서버가 만든(또는 서버가 만든 실행 컨텍스트에 든) 작업 아이디. 같은 작업을 다시 보내면 같은 실행이다 (멱등)</li>
 *   <li>progressConnectionId : 진행 중인 동안만 연결 ID. UNIQUE 라 같은 연결의 수집이 동시에 둘일 수 없다 (인스턴스가 여럿이어도 DB 가 막는다)</li>
 *   <li>reflectedOrder : 반영 순서. 연결 안에서 1, 2, 3 … COMPLETED 에만 붙고, 과거 상태 조회는 이 값을 기준으로 한다</li>
 *   <li>serverId·lastIdentifiedAt : 실행을 맡은 서버와 마지막으로 살아 있음을 확인한 시각 (멈춘 실행 정리)</li>
 * </ul>
 */
@Entity
@Table(name = "TB_SRC_CLCT_EXCN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceCollectionExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_CLCT_EXCN")
    @SequenceGenerator(name = "SQ_SRC_CLCT_EXCN", sequenceName = "SQ_SRC_CLCT_EXCN", allocationSize = 50)
    @Column(name = "SRC_CLCT_EXCN_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "JOB_ID", nullable = false)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "EXCN_TYPE_CD", length = 20, nullable = false)
    private ExecutionType executionType;

    /** 요청자 계정. 정기 배치는 null */
    @Column(name = "ACNT_SN")
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "CLCT_TYPE_CD", length = 20, nullable = false)
    private CollectionType collectionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "EXCN_STTS_CD", length = 20, nullable = false)
    private CollectionStatus status;

    @Column(name = "RFLT_ORD")
    private Integer reflectedOrder;

    @Column(name = "PRGRS_CNTN_SN")
    private Long progressConnectionId;

    @Column(name = "EXCN_SRVR_ID", nullable = false)
    private String serverId;

    @Column(name = "BGNG_DT", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "LAST_IDNTY_DT", nullable = false)
    private LocalDateTime lastIdentifiedAt;

    @Column(name = "END_DT")
    private LocalDateTime endedAt;

    @Column(name = "RFLT_DT")
    private LocalDateTime reflectedAt;

    @Column(name = "TBL_CNT")
    private Integer tableCount;

    @Column(name = "COL_CNT")
    private Integer columnCount;

    @Column(name = "VER_CNT")
    private Integer versionCount;

    @Column(name = "CHG_CNT")
    private Integer changeCount;

    @Column(name = "ERR_CD", length = 100)
    private String errorCode;

    /** 마스킹한 오류 요약 (비밀값·접속 정보가 남지 않게 SensitiveDataMasker 를 거친다) */
    @Column(name = "ERR_CN", columnDefinition = "TEXT")
    private String errorMessage;
}
