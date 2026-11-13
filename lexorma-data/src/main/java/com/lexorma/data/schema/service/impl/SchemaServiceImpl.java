package com.lexorma.data.schema.service.impl;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.view.repository.PhysicalViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SchemaServiceImpl implements SchemaService {

    private final SchemaInfoRepository schemaInfoRepository;
    private final TableInfoRepository tableInfoRepository;
    private final PhysicalViewRepository viewRepository;
    private final ProjectRepository projectRepository;
    private final ColumnInfoRepository columnInfoRepository;

    @Override
    @Transactional
    public List<SchemaDto> selectSchemas(AccountDto accountDto) {
        resolveSchema(accountDto.getProjectId(), null); // 새 프로젝트도 기본 스키마가 보이도록
        return schemaInfoRepository.findSchemaList(accountDto.getProjectId());
    }

    @Override
    @Transactional
    public SchemaDto saveSchema(AccountDto accountDto, SchemaDto schemaDto) {
        checkManageable();
        Long projectId = accountDto.getProjectId();
        String name = schemaDto.schemaName() == null ? "" : schemaDto.schemaName().trim();
        if (!SchemaInfo.NAME_PATTERN.matcher(name).matches()) {
            throw new CustomException(ErrorCode.INVALID_SCHEMA_NAME);
        }

        // 기존 스키마는 행을 잠근다 (이 스키마의 TABLE·VIEW 이름 공간 잠금과 같은 행 : 이름을 바꾸는 동안 그 스키마에 등록·이동하지 않는다)
        SchemaInfo schema = schemaDto.id() == null
                ? SchemaInfo.builder().projectId(projectId).isDefault(false).build()
                : schemaInfoRepository.findForUpdate(schemaDto.id(), projectId)
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));

        // 기본 스키마는 이름을 고정한다 (설명만 수정). DDL 은 이름이 아니라 기본 여부로 접두어를 정하지만 M-3 정책으로 변경을 막는다
        if (Boolean.TRUE.equals(schema.getIsDefault()) && !name.equals(schema.getSchemaName())) {
            throw new CustomException(ErrorCode.INVALID_SCHEMA_NAME, "기본 스키마의 이름은 바꿀 수 없습니다.");
        }
        if (!name.equalsIgnoreCase(schema.getSchemaName()) && schemaInfoRepository.existsByProjectIdAndSchemaNameIgnoreCase(projectId, name)) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        }
        if (schema.getId() != null && !name.equals(schema.getSchemaName())) {
            // 참조 테이블명을 "스키마명.테이블명" 으로 적은 컬럼은 새 스키마명으로 고친다 (같은 테이블을 계속 가리키게. 관계는 내부 ID 라 그대로다)
            String oldPrefix = schema.getSchemaName().toUpperCase(Locale.ROOT) + ".";
            String newPrefix = name.toUpperCase(Locale.ROOT) + ".";
            columnInfoRepository.findAllByProjectIdAndRefTableNameStartingWith(projectId, oldPrefix)
                    .forEach(column -> column.setRefTableName(newPrefix + column.getRefTableName().substring(oldPrefix.length())));
        }
        schema.setSchemaName(name);
        schema.setSchemaDescription(schemaDto.schemaDescription());
        return SchemaDto.fromEntity(schemaInfoRepository.save(schema));
    }

    @Override
    @Transactional
    public void deleteSchema(AccountDto accountDto, Long schemaId) {
        checkManageable();
        // 이름 공간 잠금과 같은 행을 먼저 잠가, 이 스키마에 테이블·VIEW 를 등록·이동하는 쓰기와 직렬화한 뒤 비어 있는지 본다
        SchemaInfo schema = schemaInfoRepository.findForUpdate(schemaId, accountDto.getProjectId())
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        if (Boolean.TRUE.equals(schema.getIsDefault())) {
            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED, "기본 스키마는 삭제할 수 없습니다.");
        }
        if (tableInfoRepository.existsBySchema_Id(schemaId)) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "{0} 에 테이블이 있습니다.", schema.getSchemaName());
        }
        if (viewRepository.existsBySchema_Id(schemaId)) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "{0} 에 VIEW 가 있습니다.", schema.getSchemaName());
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
            throw new CustomException(ErrorCode.SCHEMA_PERMISSION_DENIED);
        }
    }
}
