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
