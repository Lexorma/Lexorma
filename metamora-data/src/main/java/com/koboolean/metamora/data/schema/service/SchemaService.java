package com.koboolean.metamora.data.schema.service;

import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.data.schema.domain.dto.SchemaDto;
import com.koboolean.metamora.data.schema.domain.entity.SchemaInfo;

import java.util.List;

public interface SchemaService {

    List<SchemaDto> selectSchemas(AccountDto accountDto);

    SchemaDto saveSchema(AccountDto accountDto, SchemaDto schemaDto);

    void deleteSchema(AccountDto accountDto, Long schemaId);

    /**
     * 테이블이 속할 스키마. schemaId 가 없으면 프로젝트 기본 스키마(없으면 만든다), 다른 프로젝트의 스키마면 오류.
     */
    SchemaInfo resolveSchema(Long projectId, Long schemaId);
}
