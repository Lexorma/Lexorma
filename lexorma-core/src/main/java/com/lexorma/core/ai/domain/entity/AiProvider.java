package com.lexorma.core.ai.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.ai.AiProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AI Provider (AI-3, V8) : Lexorma 가 호출할 AI 서버. 시스템 관리자(ADMIN)만 관리하고 프로젝트에 속하지 않는다 (시스템 전역).
 * 인증 정보는 두지 않는다 — Ollama 는 필요 없고, 인증이 필요한 Provider(11단계)를 더할 때 SecretCipher 로 암호화한 칸을 새 마이그레이션으로 더한다.
 * 이 행으로 만든 Spring AI 모델 객체는 요청마다 새로 만든다 (캐시 없음) : 수정·비활성화·삭제가 다음 요청부터 바로 적용된다.
 */
@Entity
@Table(name = "TB_AI_PRVDR")
@Getter
@Setter
@NoArgsConstructor
public class AiProvider extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_AI_PRVDR")
    @SequenceGenerator(name = "SQ_AI_PRVDR", sequenceName = "SQ_AI_PRVDR", allocationSize = 50)
    @Column(name = "AI_PRVDR_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRVDR_NM", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "PRVDR_TYPE_CD", nullable = false, length = 20)
    private AiProviderType type;

    /** AI 서버 주소 (http·https, 끝의 / 없음, 계정 정보·쿼리 없음). 연결은 SEC-1 허용 규칙(AI_HTTP)이 열어야 한다 */
    @Column(name = "BSC_URL_ADDR", nullable = false)
    private String baseUrl;

    /** 기본 모델 ID (비면 호출할 때 모델을 정해야 한다) */
    @Column(name = "BSC_MDL_NM")
    private String defaultModel;

    @Column(name = "ACTVTN_YN", nullable = false)
    private Boolean enabled;

    @Column(name = "PRVDR_EXPLN", length = 1000)
    private String description;

    public Audit audit() {
        return toAudit();
    }
}
