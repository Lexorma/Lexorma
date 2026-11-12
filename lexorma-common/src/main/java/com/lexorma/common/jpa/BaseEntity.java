package com.lexorma.common.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

@MappedSuperclass
public class BaseEntity {

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
