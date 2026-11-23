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
    private final com.lexorma.core.project.guard.ProjectWriteGuard projectWriteGuard;
    private final com.lexorma.data.physical.sync.ModelRowLocks rowLocks;
    private final jakarta.persistence.EntityManager entityManager;

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

        // 이름을 바꾸는지 잠그기 전에 본다 (M8-C1 P-02). 이름 변경은 다른 테이블 컬럼의 참조 테이블명("스키마명.테이블명")까지 고치는 구조 변경이라 테이블 이름 변경과
        // 같은 순서로 잠근다 : 프로젝트 행 배타(맨 먼저) → 스키마 행 → 고칠 컬럼 행, 프로젝트를 잡은 뒤에는 기다리지 않는다 (바쁘면 409 — ModelRowLocks).
        // 예전에는 스키마 행만 잠그고 참조 컬럼을 잠그지 않은 채 고쳐, 그 사이 다른 사용자가 바꾼 참조 테이블명을 예전 값 기준으로 덮어썼다. 설명만 고치는 저장은 예전 그대로다
        boolean rename = false;
        if (schemaDto.id() != null) {
            SchemaInfo seen = schemaInfoRepository.findByIdAndProjectId(schemaDto.id(), projectId)
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));
            rename = !name.equals(seen.getSchemaName());
        }
        SchemaInfo schema;
        if (schemaDto.id() == null) {
            schema = SchemaInfo.builder().projectId(projectId).isDefault(false).build();
        } else if (rename) {
            projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
            projectWriteGuard.lock(projectId);
            rowLocks.schemasNoWait(List.of(schemaDto.id()));
            schema = schemaInfoRepository.findByIdAndProjectId(schemaDto.id(), projectId).orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));
            entityManager.refresh(schema);
        } else {
            // 기존 스키마는 행을 잠근다 (이 스키마의 TABLE·VIEW 이름 공간 잠금과 같은 행 : 이름을 바꾸는 동안 그 스키마에 등록·이동하지 않는다)
            schema = schemaInfoRepository.findForUpdate(schemaDto.id(), projectId)
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        }
        // DEC-08 : 잠근 스키마 행과 화면이 읽은 수정 일시(목록의 updated) 비교
        if (schemaDto.id() != null) com.lexorma.common.jpa.ModelBase.verify(schema, schemaDto.updated(), schema.getSchemaName());
        // 잠그기 전에 본 이름과 잠근 뒤의 이름이 다르다 (그 사이 다른 사용자가 바꿨다) : 구조 변경 잠금 없이 이름을 바꾸지 않는다
        if (schema.getId() != null && !rename && !name.equals(schema.getSchemaName())) {
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", schema.getSchemaName());
        }

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
            // 고칠 컬럼을 기다리지 않고 잠근 뒤(ID 순) 다시 읽어, 지금도 이 스키마를 가리키는 컬럼만 고친다 (그 사이 다른 참조로 바뀐 컬럼은 그대로 둔다)
            List<Long> referencing = columnInfoRepository.findAllByProjectIdAndRefTableNameStartingWith(projectId, oldPrefix).stream()
                    .map(com.lexorma.data.column.domain.entity.ColumnInfo::getId).toList();
            for (com.lexorma.data.column.domain.entity.ColumnInfo column : rowLocks.columnsNoWait(referencing)) {
                entityManager.refresh(column);
                String ref = column.getRefTableName();
                if (projectId.equals(column.getProjectId()) && ref != null && ref.startsWith(oldPrefix)) {
                    column.setRefTableName(newPrefix + ref.substring(oldPrefix.length()));
                }
            }
        }
        schema.setSchemaName(name);
        schema.setSchemaDescription(schemaDto.schemaDescription());
        return SchemaDto.fromEntity(schemaInfoRepository.save(schema));
    }

    @Override
    @Transactional
    public void deleteSchema(AccountDto accountDto, Long schemaId, java.time.LocalDateTime modifiedAt) {
        checkManageable();
        // 이름 공간 잠금과 같은 행을 먼저 잠가, 이 스키마에 테이블·VIEW 를 등록·이동하는 쓰기와 직렬화한 뒤 비어 있는지 본다
        SchemaInfo schema = schemaInfoRepository.findForUpdate(schemaId, accountDto.getProjectId())
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        if (Boolean.TRUE.equals(schema.getIsDefault())) {
            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED, "기본 스키마는 삭제할 수 없습니다.");
        }
        // 화면이 본 스키마(목록의 updated)와 같을 때만 지운다 (M8-C2, M8-D01). 잠근 뒤 다시 읽은 값으로 비교한다
        entityManager.refresh(schema);
        com.lexorma.common.jpa.ModelBase.verify(schema, modifiedAt, schema.getSchemaName());
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
