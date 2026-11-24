package com.lexorma.data.schema.service;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.domain.entity.SchemaInfo;

import java.util.List;

public interface SchemaService {

    List<SchemaDto> selectSchemas(AccountDto accountDto);

    SchemaDto saveSchema(AccountDto accountDto, SchemaDto schemaDto);

    /** modifiedAt : 화면이 읽은 수정 일시 (M8-C2). HTTP 요청은 필수 — 권한 확인 뒤 검사한다 (null = 내부 호출) */
    void deleteSchema(AccountDto accountDto, Long schemaId, java.time.LocalDateTime modifiedAt);

    /**
     * 테이블이 속할 스키마. schemaId 가 없으면 프로젝트 기본 스키마(없으면 만든다), 다른 프로젝트의 스키마면 오류.
     */
    SchemaInfo resolveSchema(Long projectId, Long schemaId);
}
