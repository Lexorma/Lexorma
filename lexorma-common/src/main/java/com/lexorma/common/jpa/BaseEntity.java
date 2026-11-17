package com.lexorma.common.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

/**
 * 감사 컬럼. 접근자는 protected 다 : 엔티티의 공개 API·JSON 프로퍼티를 늘리지 않고, 화면에 보여야 하는 엔티티만
 * {@link Audit} 로 꺼내 DTO 에 옮긴다 (예: TableIndex.audit()).
 */
@MappedSuperclass
@Getter(AccessLevel.PROTECTED)
public class BaseEntity {

    /** 등록·수정 일시와 사용자 (DTO 로 옮길 때만 쓴다) */
    public record Audit(LocalDateTime created, LocalDateTime updated, String createdBy, String updatedBy) {
    }

    protected Audit toAudit() {
        return new Audit(created, updated, createdBy, updatedBy);
    }

    /** 수정 기준값 (DEC-08 낙관적 충돌 검사, {@link ModelBase}) : 마지막 수정 일시. bean getter 가 아니라 JSON 프로퍼티가 되지 않는다 */
    public LocalDateTime modifiedAt() {
        return updated;
    }

    /**
     * 자식 행(구성 : 식별자 속성·인덱스 컬럼 등)만 바뀐 수정도 이 행의 수정 일시를 바꾼다 (DEC-08). 행 자신의 값이 그대로면 Hibernate 가 UPDATE 하지 않아
     * MDFCN_DT 가 남고, 예전 기준값으로 보낸 저장이 충돌 없이 구성을 되돌린다. 값을 바꿔 두면 UPDATE 가 나가고 감사 리스너가 지금 시각으로 다시 채운다
     */
    public void markModified() {
        updated = LocalDateTime.now();
    }

    @CreatedDate
    @Column(name = "REG_DT", updatable = false)
    private LocalDateTime created;

    @LastModifiedDate
    @Column(name = "MDFCN_DT")
    private LocalDateTime updated;

    @CreatedBy
    @Column(updatable = false, name = "RGTR_ID")
    private String createdBy;

    @LastModifiedBy
    @Column(name = "MDFR_ID")
    private String updatedBy;

}
