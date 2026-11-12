package com.lexorma.data.schema.domain.dto;

import com.lexorma.data.schema.domain.entity.SchemaInfo;

import java.time.LocalDateTime;

/**
 * 스키마 조회·저장 (id 가 없으면 등록).
 * tableCount(소속 테이블 수)와 등록·수정 정보는 목록 조회 전용이다 (저장 요청·응답에서는 null).
 */
public record SchemaDto(Long id, String schemaName, String schemaDescription, Boolean isDefault, Long tableCount,
                        LocalDateTime created, LocalDateTime updated, String createdBy, String updatedBy) {

    public SchemaDto(Long id, String schemaName, String schemaDescription, Boolean isDefault) {
        this(id, schemaName, schemaDescription, isDefault, null, null, null, null, null);
    }

    public static SchemaDto fromEntity(SchemaInfo entity) {
        return new SchemaDto(entity.getId(), entity.getSchemaName(), entity.getSchemaDescription(), entity.getIsDefault());
    }
}
