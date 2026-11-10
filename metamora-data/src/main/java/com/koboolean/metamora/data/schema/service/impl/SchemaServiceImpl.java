package com.koboolean.metamora.data.schema.service.impl;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.util.AuthUtil;
import com.koboolean.metamora.data.schema.domain.dto.SchemaDto;
import com.koboolean.metamora.data.schema.domain.entity.SchemaInfo;
import com.koboolean.metamora.data.schema.repository.SchemaInfoRepository;
import com.koboolean.metamora.data.schema.service.SchemaService;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SchemaServiceImpl implements SchemaService {

    // 따옴표 없는 식별자로 쓸 수 있는 이름 (PostgreSQL 63자 제한). 대소문자 정규화는 하지 않는다 (AI 로드맵 U-24 에서 결정)
    private static final Pattern SCHEMA_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,62}$");

    private final SchemaInfoRepository schemaInfoRepository;
    private final TableInfoRepository tableInfoRepository;
    private final ProjectRepository projectRepository;

    @Override
    @Transactional
    public List<SchemaDto> selectSchemas(AccountDto accountDto) {
        resolveSchema(accountDto.getProjectId(), null); // 새 프로젝트도 기본 스키마가 보이도록
        return schemaInfoRepository.findAllByProjectIdOrderByIsDefaultDescSchemaNameAsc(accountDto.getProjectId()).stream()
                .map(SchemaDto::fromEntity)
                .toList();
    }

    @Override
    @Transactional
    public SchemaDto saveSchema(AccountDto accountDto, SchemaDto schemaDto) {
        checkManageable();
        Long projectId = accountDto.getProjectId();
        String name = schemaDto.schemaName() == null ? "" : schemaDto.schemaName().trim();
        if (!SCHEMA_NAME.matcher(name).matches()) {
            throw new CustomException(ErrorCode.INVALID_SCHEMA_NAME);
        }

        SchemaInfo schema = schemaDto.id() == null
                ? SchemaInfo.builder().projectId(projectId).isDefault(false).build()
                : schemaInfoRepository.findByIdAndProjectId(schemaDto.id(), projectId)
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));

        if (!name.equalsIgnoreCase(schema.getSchemaName()) && schemaInfoRepository.existsByProjectIdAndSchemaNameIgnoreCase(projectId, name)) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        }
        schema.setSchemaName(name);
        schema.setSchemaDescription(schemaDto.schemaDescription());
        return SchemaDto.fromEntity(schemaInfoRepository.save(schema));
    }

    @Override
    @Transactional
    public void deleteSchema(AccountDto accountDto, Long schemaId) {
        checkManageable();
        SchemaInfo schema = schemaInfoRepository.findByIdAndProjectId(schemaId, accountDto.getProjectId())
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        if (Boolean.TRUE.equals(schema.getIsDefault())) {
            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED, "기본 스키마는 삭제할 수 없습니다.");
        }
        if (tableInfoRepository.existsBySchema_Id(schemaId)) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "{0} 에 테이블이 있습니다.", schema.getSchemaName());
        }
        schemaInfoRepository.delete(schema);
    }

    @Override
    @Transactional
    public SchemaInfo resolveSchema(Long projectId, Long schemaId) {
        if (schemaId != null) {
            return schemaInfoRepository.findByIdAndProjectId(schemaId, projectId)
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        }
        return schemaInfoRepository.findFirstByProjectIdAndIsDefaultTrue(projectId)
                .orElseGet(() -> createDefaultSchema(projectId));
    }

    /** 최초 생성만 프로젝트 행 잠금으로 직렬화하고 잠금 후 다시 조회한다 (동시 요청이 UNIQUE 충돌로 실패하지 않도록) */
    private SchemaInfo createDefaultSchema(Long projectId) {
        projectRepository.findByIdForUpdate(projectId);
        return schemaInfoRepository.findFirstByProjectIdAndIsDefaultTrue(projectId)
                .orElseGet(() -> schemaInfoRepository.save(SchemaInfo.builder()
                        .projectId(projectId)
                        .schemaName(SchemaInfo.DEFAULT_NAME)
                        .schemaDescription("기본 스키마")
                        .isDefault(true)
                        .build()));
    }

    /** 스키마 구조 변경은 승인 권한(관리자·매니저·프로젝트 관리자)만 */
    private static void checkManageable() {
        if (!AuthUtil.isApprovalAvailable()) {
            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
        }
    }
}
