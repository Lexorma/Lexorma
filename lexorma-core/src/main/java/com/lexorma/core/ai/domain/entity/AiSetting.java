package com.lexorma.core.ai.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AI 설정 (V9) : Lexorma 전체에 행 하나 (PK = {@link #SYSTEM}, CK_AI_STNG_01). 시스템 관리자만 바꾼다.
 * 시스템 기본 AI Provider 를 하나만 가리킨다 (비면 미지정) — 행이 하나라 기본 Provider 가 둘이 될 수 없고,
 * FK_AI_STNG_01 (NO ACTION) 이라 기본으로 지정된 Provider 는 DB 에서도 지워지지 않는다. 행은 마이그레이션이 넣는다 (앱은 만들지 않는다).
 */
@Entity
@Table(name = "TB_AI_STNG")
@Getter
@Setter
@NoArgsConstructor
public class AiSetting extends BaseEntity {

    /** 하나뿐인 행의 키 */
    public static final long SYSTEM = 1L;

    @Id
    @Column(name = "AI_STNG_SN", nullable = false)
    private Long id;

    /** 시스템 기본 AI Provider (TB_AI_PRVDR.AI_PRVDR_SN). 비면 미지정 */
    @Column(name = "BSC_AI_PRVDR_SN")
    private Long defaultProviderId;

    public Audit audit() {
        return toAudit();
    }
}
