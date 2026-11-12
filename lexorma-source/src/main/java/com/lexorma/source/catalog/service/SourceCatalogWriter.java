package com.lexorma.source.catalog.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.entity.SourceColumn;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.catalog.domain.entity.SourceConstraint;
import com.lexorma.source.catalog.domain.entity.SourceConstraintColumn;
import com.lexorma.source.catalog.domain.entity.SourceIndex;
import com.lexorma.source.catalog.domain.entity.SourceIndexColumn;
import com.lexorma.source.catalog.domain.entity.SourceRelation;
import com.lexorma.source.catalog.domain.entity.SourceRelationColumn;
import com.lexorma.source.catalog.domain.entity.SourceSchema;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.catalog.repository.SourceColumnRepository;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.catalog.repository.SourceConstraintRepository;
import com.lexorma.source.catalog.repository.SourceIndexRepository;
import com.lexorma.source.catalog.repository.SourceRelationRepository;
import com.lexorma.source.catalog.repository.SourceSchemaRepository;
import com.lexorma.source.catalog.repository.SourceTableRepository;
import com.lexorma.source.catalog.service.SourceIdentifiers.Kind;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.lexorma.source.catalog.service.SourceCatalogRules.compare;
import static com.lexorma.source.catalog.service.SourceCatalogRules.invalid;
import static com.lexorma.source.catalog.service.SourceCatalogRules.limited;
import static com.lexorma.source.catalog.service.SourceCatalogRules.optional;
import static com.lexorma.source.catalog.service.SourceCatalogRules.required;
import static com.lexorma.source.catalog.service.SourceCatalogRules.rule;

/**
 * 원본 카탈로그에 쓰는 단 하나의 경로 (AI-1b). 사용자가 부르는 API 가 없다 : 원본 카탈로그는 수집한 사실이라 화면에서 고치지 않는다.
 * 수집 결과를 적재하는 쪽(AI-1c)과 테스트가 이것을 쓴다.
 * <ul>
 *   <li>projectId 는 부르는 쪽이 <b>검증된 실행 컨텍스트</b>에서 꺼내 넘긴다 (수집 결과에 든 값을 쓰지 않는다). 모든 대상은 그 프로젝트의 것만 찾는다</li>
 *   <li>같은 객체인지는 비교용 이름({@link SourceIdentifiers})으로 판단한다 : 있으면 고치고 없으면 만든다. 이름은 원본이 준 그대로 저장한다</li>
 *   <li>관계·제약조건·인덱스는 같은 테이블의 컬럼, 같은 연결의 테이블만 가리킨다 (다른 연결·프로젝트의 객체와 이어지지 않는다)</li>
 *   <li>JPA 로 저장한다 : projectId 를 가진 엔티티라 프로젝트 삭제와 겹치면 {@code ProjectWriteGuard} 가 커밋을 막는다.
 *       대량 적재를 JDBC 배치로 바꾸면 {@code ProjectWriteGuard.guard(projectId)} 를 직접 불러야 한다</li>
 *   <li>프로젝트 행(공유) → 연결 행(배타) 순서로 잠그고 쓴다 : 연결 삭제와 겹치지 않고, 프로젝트 삭제(프로젝트 → 연결 순서로 잠근다)와 서로를 기다리지 않는다</li>
 *   <li><b>버전·변경 이력을 남기지 않는다.</b> 운영 코드는 이것을 부르지 않는다 : 수집 결과는 {@code SourceCollectionService}(AI-1c)로 적재한다 —
 *       이것은 AI-1b 의 한 건 단위 경로로, 테스트가 카탈로그를 준비할 때 쓴다 (ModuleBoundaryTest.catalogWritePaths 가 운영 코드의 호출을 막는다).
 *       고친 테이블은 내용 해시를 지워 다음 수집이 현재 행을 다시 비교·복구하게 한다. 원본 변경 판정은 마지막 확정 버전으로 하므로 여기서 고친 값은 버전·이력에 들어가지 않는다</li>
 * </ul>
 * 물리·논리 모델, 표준 용어, 업무 설명은 읽지도 쓰지도 않는다.
 */
@Service
@RequiredArgsConstructor
public class SourceCatalogWriter {

    private final SourceConnectionRepository connectionRepository;
    private final SourceSchemaRepository schemaRepository;
    private final SourceTableRepository tableRepository;
    private final SourceColumnRepository columnRepository;
    private final SourceConstraintRepository constraintRepository;
    private final SourceIndexRepository indexRepository;
    private final SourceRelationRepository relationRepository;
    private final ProjectWriteGuard projectWriteGuard;

    /** 이름 공간(카탈로그·스키마). 둘 중 하나는 있어야 한다 */
    @Transactional
    public SourceSchema saveSchema(Long projectId, Long connectionId, Name catalog, Name schema) {
        SourceConnection connection = lock(projectId, connectionId);
        String catalogName = optional(catalog);
        String schemaName = optional(schema);
        if (catalogName == null && schemaName == null) throw invalid("카탈로그와 스키마 중 하나는 있어야 합니다.");
        String pathKey = SourceIdentifiers.pathKey(compare(connection, Kind.NAMESPACE, catalog), compare(connection, Kind.NAMESPACE, schema));
        SourceSchema found = schemaRepository.findByConnection_IdAndPathKey(connectionId, pathKey).orElse(null);
        if (found == null) {
            return schemaRepository.saveAndFlush(SourceSchema.builder().projectId(projectId).connection(connection).catalogName(catalogName).schemaName(schemaName)
                    .pathKey(pathKey).present(true).build());
        }
        found.setCatalogName(catalogName);
        found.setSchemaName(schemaName);
        found.setPresent(true);
        return found;
    }

    /** 테이블·뷰 */
    @Transactional
    public SourceTable saveTable(Long projectId, Long schemaId, SourceCatalogSpec.Table spec) {
        SourceSchema schema = schemaRepository.findByIdAndProjectId(schemaId, projectId).orElseThrow(SourceCatalogWriter::notFound);
        SourceConnection connection = lock(projectId, schema.getConnection().getId());
        String name = required(spec.name());
        String compareName = compare(connection, Kind.TABLE, spec.name());
        SourceTableType type = spec.type() == null ? SourceTableType.TABLE : spec.type();
        SourceTable found = tableRepository.findBySchema_IdAndCompareName(schemaId, compareName).orElse(null);
        if (found == null) {
            return tableRepository.saveAndFlush(SourceTable.builder().projectId(projectId).connectionId(connection.getId()).schema(schema).name(name).compareName(compareName)
                    .quoted(spec.name().quoted()).type(type).comment(spec.comment()).present(true).build());
        }
        found.setName(name);
        found.setQuoted(spec.name().quoted());
        found.setType(type);
        found.setComment(spec.comment());
        found.setPresent(true);
        found.setContentHash(null);
        return found;
    }

    @Transactional
    public SourceColumn saveColumn(Long projectId, Long tableId, SourceCatalogSpec.Column spec) {
        SourceTable table = table(projectId, tableId);
        SourceConnection connection = lock(projectId, table.getConnectionId());
        String name = required(spec.name());
        if (spec.ordinal() == null || spec.ordinal() < 1) throw invalid("컬럼 순서가 올바르지 않습니다.");
        String compareName = compare(connection, Kind.COLUMN, spec.name());
        SourceColumn column = columnRepository.findBySourceTable_IdAndCompareName(tableId, compareName).orElse(null);
        if (column == null) {
            column = SourceColumn.builder().projectId(projectId).sourceTable(table).compareName(compareName).build();
        }
        column.setName(name);
        column.setQuoted(spec.name().quoted());
        column.setOrdinal(spec.ordinal());
        column.setDataType(spec.dataType());
        column.setMaxLength(spec.maxLength());
        column.setPrecision(spec.precision());
        column.setScale(spec.scale());
        column.setNullable(spec.nullable());
        column.setDefaultValue(spec.defaultValue());
        column.setComment(spec.comment());
        column.setPresent(true);
        column.setContentHash(null);
        table.setContentHash(null);
        return columnRepository.saveAndFlush(column);
    }

    /** 테이블의 PK·UNIQUE·CHECK 를 통째로 바꾼다 (원본의 지금 구성 그대로) */
    @Transactional
    public List<SourceConstraint> replaceConstraints(Long projectId, Long tableId, List<SourceCatalogSpec.Constraint> specs) {
        SourceTable table = table(projectId, tableId);
        SourceConnection connection = lock(projectId, table.getConnectionId());
        table.setContentHash(null);
        constraintRepository.deleteAll(constraintRepository.findAllBySourceTable_IdOrderByIdAsc(tableId));
        constraintRepository.flush();
        List<SourceConstraint> saved = new ArrayList<>();
        for (SourceCatalogSpec.Constraint spec : specs) {
            if (spec.type() == null) throw invalid("제약조건 유형이 없습니다.");
            List<Name> names = spec.columns() == null ? List.of() : spec.columns();
            if (spec.type() != SourceConstraintType.CHECK && names.isEmpty()) throw invalid("구성 컬럼이 없습니다.");
            SourceConstraint constraint = SourceConstraint.builder().projectId(projectId).sourceTable(table).name(limited(spec.name())).type(spec.type())
                    .checkExpression(spec.type() == SourceConstraintType.CHECK ? spec.checkExpression() : null).build();
            for (int i = 0; i < names.size(); i++) {
                constraint.getColumns().add(SourceConstraintColumn.builder().sourceConstraint(constraint).sourceColumn(column(connection, table, names.get(i))).columnOrder(i + 1).build());
            }
            saved.add(constraintRepository.save(constraint));
        }
        constraintRepository.flush();
        return saved;
    }

    @Transactional
    public List<SourceIndex> replaceIndexes(Long projectId, Long tableId, List<SourceCatalogSpec.Index> specs) {
        SourceTable table = table(projectId, tableId);
        SourceConnection connection = lock(projectId, table.getConnectionId());
        table.setContentHash(null);
        indexRepository.deleteAll(indexRepository.findAllBySourceTable_IdOrderByIdAsc(tableId));
        indexRepository.flush();
        List<SourceIndex> saved = new ArrayList<>();
        for (SourceCatalogSpec.Index spec : specs) {
            String name = limited(spec.name());
            if (name == null) throw invalid("이름이 없거나 너무 깁니다.");
            if (spec.columns() == null || spec.columns().isEmpty()) throw invalid("구성 컬럼이 없습니다.");
            SourceIndex index = SourceIndex.builder().projectId(projectId).sourceTable(table).name(name).indexType(spec.indexType()).uniqueIndex(spec.unique()).build();
            for (int i = 0; i < spec.columns().size(); i++) {
                index.getColumns().add(SourceIndexColumn.builder().sourceIndex(index).sourceColumn(column(connection, table, spec.columns().get(i))).columnOrder(i + 1).build());
            }
            saved.add(indexRepository.save(index));
        }
        indexRepository.flush();
        return saved;
    }

    /**
     * 테이블의 FK 를 통째로 바꾼다. 참조되는 테이블은 같은 연결의 카탈로그에서만 찾는다 (다른 스키마 가능).
     * 찾지 못하면(수집 범위 밖) 이름만 남기고 나중에 그 테이블이 적재된 뒤 다시 저장하면 이어진다.
     */
    @Transactional
    public List<SourceRelation> replaceRelations(Long projectId, Long tableId, List<SourceCatalogSpec.Relation> specs) {
        SourceTable table = table(projectId, tableId);
        SourceConnection connection = lock(projectId, table.getConnectionId());
        table.setContentHash(null);
        relationRepository.deleteAll(relationRepository.findAllBySourceTable_IdOrderByIdAsc(tableId));
        relationRepository.flush();
        List<SourceRelation> saved = new ArrayList<>();
        for (SourceCatalogSpec.Relation spec : specs) {
            if (spec.columns() == null || spec.columns().isEmpty()) throw invalid("구성 컬럼이 없습니다.");
            String referencedTableName = required(spec.referencedTable());
            SourceSchema referencedSchema = referencedSchema(connection, table.getSchema(), spec);
            SourceTable referencedTable = referencedSchema == null ? null
                    : tableRepository.findBySchema_IdAndCompareName(referencedSchema.getId(), compare(connection, Kind.TABLE, spec.referencedTable())).orElse(null);
            SourceRelation relation = SourceRelation.builder().projectId(projectId).connectionId(connection.getId()).sourceTable(table).referencedTable(referencedTable)
                    .referencedSchemaName(spec.referencedSchema() == null && spec.referencedCatalog() == null ? table.getSchema().getSchemaName() : optional(spec.referencedSchema()))
                    .referencedCatalogName(spec.referencedCatalog() == null ? table.getSchema().getCatalogName() : optional(spec.referencedCatalog()))
                    .referencedTableName(referencedTableName).name(limited(spec.name())).deleteRule(rule(spec.deleteRule())).updateRule(rule(spec.updateRule())).build();
            for (int i = 0; i < spec.columns().size(); i++) {
                SourceCatalogSpec.ColumnPair pair = spec.columns().get(i);
                String referencedColumnName = required(pair.referencedColumn());
                SourceColumn referencedColumn = referencedTable == null ? null
                        : columnRepository.findBySourceTable_IdAndCompareName(referencedTable.getId(), compare(connection, Kind.COLUMN, pair.referencedColumn())).orElse(null);
                relation.getColumns().add(SourceRelationColumn.builder().relation(relation).sourceColumn(column(connection, table, pair.column()))
                        .referencedColumn(referencedColumn).referencedColumnName(referencedColumnName).columnOrder(i + 1).build());
            }
            saved.add(relationRepository.save(relation));
        }
        relationRepository.flush();
        return saved;
    }

    /** 참조되는 테이블의 이름 공간 : 지정하지 않으면 참조하는 테이블과 같은 스키마, 카탈로그만 생략하면 같은 카탈로그 */
    private SourceSchema referencedSchema(SourceConnection connection, SourceSchema own, SourceCatalogSpec.Relation spec) {
        if (spec.referencedCatalog() == null && spec.referencedSchema() == null) return own;
        String catalog = spec.referencedCatalog() == null ? SourceIdentifiers.catalogOf(own.getPathKey()) : compare(connection, Kind.NAMESPACE, spec.referencedCatalog());
        String pathKey = SourceIdentifiers.pathKey(catalog, compare(connection, Kind.NAMESPACE, spec.referencedSchema()));
        return schemaRepository.findByConnection_IdAndPathKey(connection.getId(), pathKey).orElse(null);
    }

    /** 잠금 순서 : 프로젝트(공유) → 연결(배타). 프로젝트 영구 삭제와 같은 순서다 */
    private SourceConnection lock(Long projectId, Long connectionId) {
        projectWriteGuard.lock(projectId);
        return connectionRepository.findForUpdate(connectionId, projectId).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
    }

    private SourceTable table(Long projectId, Long tableId) {
        return tableRepository.findByIdAndProjectId(tableId, projectId).orElseThrow(SourceCatalogWriter::notFound);
    }

    /** 같은 테이블의 컬럼만 찾는다 */
    private SourceColumn column(SourceConnection connection, SourceTable table, Name name) {
        required(name);
        return columnRepository.findBySourceTable_IdAndCompareName(table.getId(), compare(connection, Kind.COLUMN, name))
                .orElseThrow(() -> invalid("구성 컬럼을 찾을 수 없습니다 : {0}", name.value()));
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.SOURCE_OBJECT_NOT_FOUND);
    }

}
