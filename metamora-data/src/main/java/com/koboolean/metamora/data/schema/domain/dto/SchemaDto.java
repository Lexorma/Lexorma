package com.koboolean.metamora.data.schema.domain.dto;

import com.koboolean.metamora.data.schema.domain.entity.SchemaInfo;

/** 스키마 조회·저장 (id 가 없으면 등록) */
public record SchemaDto(Long id, String schemaName, String schemaDescription, Boolean isDefault) {

    public static SchemaDto fromEntity(SchemaInfo entity) {
        return new SchemaDto(entity.getId(), entity.getSchemaName(), entity.getSchemaDescription(), entity.getIsDefault());
    }
}
