package com.lexorma.source.catalog.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.catalog.domain.dto.SourceCatalogDto;
import com.lexorma.source.catalog.domain.entity.SourceColumn;
import com.lexorma.source.catalog.domain.entity.SourceRelation;
import com.lexorma.source.catalog.domain.entity.SourceSchema;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.catalog.repository.SourceColumnRepository;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.catalog.repository.SourceConstraintRepository;
import com.lexorma.source.catalog.repository.SourceIndexRepository;
import com.lexorma.source.catalog.repository.SourceRelationRepository;
import com.lexorma.source.catalog.repository.SourceSchemaRepository;
import com.lexorma.source.catalog.repository.SourceTableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 원본 카탈로그 조회 (AI-1b). 읽기만 한다. 로그인한 프로젝트의 객체만 보이고, 다른 프로젝트·없는 ID 는 똑같이 "없음" 이다.
 * 목록은 쪽 단위로만 준다 (한 번에 최대 {@value #SIZE_MAX}건) : 스키마에 테이블이 수천 개일 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceCatalogService {

    static final int SIZE_MAX = 200;

    private final SourceConnectionRepository connectionRepository;
    private final SourceSchemaRepository schemaRepository;
    private final SourceTableRepository tableRepository;
    private final SourceColumnRepository columnRepository;
    private final SourceConstraintRepository constraintRepository;
    private final SourceIndexRepository indexRepository;
    private final SourceRelationRepository relationRepository;

    public Page<SourceCatalogDto.Schema> schemas(AccountDto accountDto, Long connectionId, int page, int size) {
        Long projectId = accountDto.getProjectId();
        connectionRepository.findByIdAndProjectId(connectionId, projectId).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        return schemaRepository.findAllByProjectIdAndConnection_Id(projectId, connectionId, pageable(page, size, Sort.by("catalogName", "schemaName", "id"))).map(this::schema);
    }

    /** @param type TABLE · VIEW (없으면 둘 다), keyword : 이름에 들어 있는 말 (대소문자 무시) */
    public Page<SourceCatalogDto.Table> tables(AccountDto accountDto, Long schemaId, String type, String keyword, int page, int size) {
        Long projectId = accountDto.getProjectId();
        schemaRepository.findByIdAndProjectId(schemaId, projectId).orElseThrow(SourceCatalogService::notFound);
        SourceTableType tableType = null;
        if (type != null && !type.isBlank()) {
            try {
                tableType = SourceTableType.valueOf(type.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw notFound();
            }
        }
        String word = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Pageable pageable = pageable(page, size, Sort.by("name", "id"));
        Page<SourceTable> found = tableType == null
                ? (word == null ? tableRepository.findAllByProjectIdAndSchema_Id(projectId, schemaId, pageable)
                        : tableRepository.findAllByProjectIdAndSchema_IdAndNameContainingIgnoreCase(projectId, schemaId, word, pageable))
                : (word == null ? tableRepository.findAllByProjectIdAndSchema_IdAndType(projectId, schemaId, tableType, pageable)
                        : tableRepository.findAllByProjectIdAndSchema_IdAndTypeAndNameContainingIgnoreCase(projectId, schemaId, tableType, word, pageable));
        return found.map(SourceCatalogService::table);
    }

    /** 테이블·뷰 하나 : 컬럼(원본 순서), 제약조건, 인덱스, 나가는 FK, 들어오는 FK */
    public SourceCatalogDto.TableDetail table(AccountDto accountDto, Long tableId) {
        SourceTable table = tableRepository.findByIdAndProjectId(tableId, accountDto.getProjectId()).orElseThrow(SourceCatalogService::notFound);
        return new SourceCatalogDto.TableDetail(table(table), schema(table.getSchema()),
                columnRepository.findAllBySourceTable_IdOrderByOrdinalAscIdAsc(tableId).stream().map(SourceCatalogService::column).toList(),
                constraintRepository.findAllBySourceTable_IdOrderByIdAsc(tableId).stream().map(constraint -> new SourceCatalogDto.Constraint(constraint.getId(), constraint.getName(),
                        constraint.getType().name(), constraint.getCheckExpression(), constraint.getColumns().stream().map(c -> c.getSourceColumn().getName()).toList())).toList(),
                indexRepository.findAllBySourceTable_IdOrderByIdAsc(tableId).stream().map(index -> new SourceCatalogDto.Index(index.getId(), index.getName(), index.getIndexType(),
                        Boolean.TRUE.equals(index.getUniqueIndex()), index.getColumns().stream().map(c -> c.getSourceColumn().getName()).toList())).toList(),
                relationRepository.findAllBySourceTable_IdOrderByIdAsc(tableId).stream().map(SourceCatalogService::relation).toList(),
                relationRepository.findAllByReferencedTable_IdOrderByIdAsc(tableId).stream().map(SourceCatalogService::relation).toList());
    }

    private SourceCatalogDto.Schema schema(SourceSchema schema) {
        return new SourceCatalogDto.Schema(schema.getId(), schema.getConnection().getId(), schema.getCatalogName(), schema.getSchemaName(),
                Boolean.TRUE.equals(schema.getPresent()), tableRepository.countBySchema_Id(schema.getId()));
    }

    private static SourceCatalogDto.Table table(SourceTable table) {
        return new SourceCatalogDto.Table(table.getId(), table.getSchema().getId(), table.getName(), table.getType().name(), table.getQuoted(), table.getComment(),
                Boolean.TRUE.equals(table.getPresent()));
    }

    private static SourceCatalogDto.Column column(SourceColumn column) {
        return new SourceCatalogDto.Column(column.getId(), column.getName(), column.getQuoted(), column.getOrdinal(), column.getDataType(), column.getMaxLength(),
                column.getPrecision(), column.getScale(), column.getNullable(), column.getDefaultValue(), column.getComment(), Boolean.TRUE.equals(column.getPresent()));
    }

    private static SourceCatalogDto.Relation relation(SourceRelation relation) {
        return new SourceCatalogDto.Relation(relation.getId(), relation.getName(), relation.getSourceTable().getId(), relation.getSourceTable().getName(),
                relation.getReferencedTable() == null ? null : relation.getReferencedTable().getId(), relation.getReferencedSchemaName(), relation.getReferencedTableName(),
                relation.getDeleteRule(), relation.getUpdateRule(),
                relation.getColumns().stream().map(pair -> new SourceCatalogDto.ColumnPair(pair.getSourceColumn().getId(), pair.getSourceColumn().getName(),
                        pair.getReferencedColumn() == null ? null : pair.getReferencedColumn().getId(), pair.getReferencedColumnName())).toList());
    }

    private static Pageable pageable(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), SIZE_MAX), sort);
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.SOURCE_OBJECT_NOT_FOUND);
    }
}
