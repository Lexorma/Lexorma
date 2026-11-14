package com.lexorma.data.physical.service.impl;

import com.lexorma.data.mapping.repository.AttributeColumnMappingRepository;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.entity.*;
import com.lexorma.data.physical.repository.IndexChangeRequestRepository;
import com.lexorma.data.physical.repository.TableConstraintRepository;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.physical.sync.ReferenceSyncBoundary;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.dto.TableDdlDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableNameResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class PhysicalModelServiceImpl implements PhysicalModelService {

    // 관계·인덱스·제약조건 이름 : 따옴표 없는 식별자 (DBMS 별 길이 제한은 DDL 생성 시 자른다)
    private static final Pattern OBJECT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,127}$");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 인덱스 목록 검색 대상 (Grid searchColumn) */
    private static final Set<String> INDEX_SEARCH_COLUMNS = Set.of("indexName", "tableName", "columnNames");

    private final TableInfoRepository tableInfoRepository;
    private final ColumnInfoRepository columnInfoRepository;
    private final TableRelationRepository relationRepository;
    private final TableIndexRepository indexRepository;
    private final TableConstraintRepository constraintRepository;
    private final EntityManager entityManager;
    private final AttributeColumnMappingRepository attributeMappingRepository;
    private final ProjectRepository projectRepository;
    private final IndexChangeRequestRepository changeRequestRepository;

    // ------------------------------------------------------------------ 조회

    @Override
    @Transactional(readOnly = true)
    public PhysicalModelDto.TableModel selectTableModel(AccountDto accountDto, Long tableId) {
        Long projectId = accountDto.getProjectId();
        TableInfo table = requireTable(projectId, tableId);

        List<TableRelation> outgoing = relationRepository.findAllByProjectIdAndSourceTable_Id(projectId, tableId);
        Set<Long> derivedColumns = outgoing.stream().map(TableRelation::getDerivedColumnId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<String> unresolved = table.getColumns().stream()
                .filter(c -> notBlank(c.getRefTableName()) && !derivedColumns.contains(c.getId()))
                .map(c -> c.getColumnName() + " → " + normalize(c.getRefTableName()))
                .toList();

        return new PhysicalModelDto.TableModel(table.getId(), table.getTableName(),
                table.getSchema() == null ? null : table.getSchema().getId(),
                table.getSchema() == null ? null : table.getSchema().getSchemaName(),
                outgoing.stream().map(PhysicalModelDto.Relation::fromEntity).toList(),
                relationRepository.findAllByProjectIdAndTargetTable_Id(projectId, tableId).stream()
                        .filter(r -> !r.getSourceTable().getId().equals(tableId)) // 자기 참조는 outgoing 에만
                        .map(PhysicalModelDto.Relation::fromEntity).toList(),
                indexRepository.findAllByProjectIdAndTableInfo_Id(projectId, tableId).stream().map(PhysicalModelDto.Index::fromEntity).toList(),
                constraintRepository.findAllByProjectIdAndTableInfo_Id(projectId, tableId).stream().map(PhysicalModelDto.Constraint::fromEntity).toList(),
                unresolved);
    }

    // ------------------------------------------------------------------ 등록 (관계·제약조건 수정은 삭제 후 다시 등록, 인덱스는 수정 API)

    @Override
    @Transactional
    public PhysicalModelDto.Relation saveRelation(AccountDto accountDto, PhysicalModelDto.Relation request) {
        Long projectId = accountDto.getProjectId();
        TableInfo source = requireTable(projectId, request.sourceTableId());
        TableInfo target = requireTable(projectId, request.targetTableId());

        List<PhysicalModelDto.ColumnPair> pairs = request.columns() == null ? List.of() : request.columns();
        if (pairs.isEmpty()) throw invalid("관계 컬럼 쌍을 1개 이상 지정해주세요.");

        List<ColumnInfo> sourceColumns = tableColumns(projectId, source, pairs.stream().map(PhysicalModelDto.ColumnPair::sourceColumnId).toList());
        List<ColumnInfo> targetColumns = tableColumns(projectId, target, pairs.stream().map(PhysicalModelDto.ColumnPair::targetColumnId).toList());

        // 참조 테이블명(단일 FK 약식 입력)이 있는 컬럼은 그 값으로 관계가 만들어지므로 두 곳에서 정의하지 않는다
        sourceColumns.stream().filter(c -> notBlank(c.getRefTableName())).findFirst().ifPresent(c -> {
            throw invalid("{0} 은(는) 참조 테이블({1})이 지정된 컬럼입니다. 컬럼 관리에서 참조 테이블을 비운 뒤 등록해주세요.", c.getColumnName(), normalize(c.getRefTableName()));
        });

        List<Long> sourceIds = ids(sourceColumns);
        boolean duplicated = relationRepository.findAllByProjectIdAndSourceTable_Id(projectId, source.getId()).stream()
                .anyMatch(r -> r.getTargetTable().getId().equals(target.getId())
                        && r.getColumns().stream().map(c -> c.getSourceColumn().getId()).toList().equals(sourceIds));
        if (duplicated) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, source.getTableName() + " → " + target.getTableName());

        TableRelation.Type type = parse(TableRelation.Type.class, request.relationType(), TableRelation.Type.FK);
        // FK 는 DDL 로 나가므로 실제 DB 에서 만들 수 있는 정의만 받는다. 논리 관계(LOGICAL)는 제약이 아니라서 검사하지 않는다
        if (type == TableRelation.Type.FK) validateForeignKey(projectId, source, target, sourceColumns, targetColumns);

        TableRelation relation = TableRelation.builder()
                .projectId(projectId)
                .sourceTable(source)
                .targetTable(target)
                .relationName(objectName(request.relationName(), "FK_" + source.getTableName() + "_" + sourceColumns.get(0).getColumnName()))
                .relationType(type)
                .relationSource(TableRelation.Source.USER)
                .isApproval(AuthUtil.isApprovalAvailable())
                .deleteRule(type == TableRelation.Type.FK ? rule(request.deleteRule()) : null)
                .updateRule(type == TableRelation.Type.FK ? rule(request.updateRule()) : null)
                .build();
        for (int i = 0; i < pairs.size(); i++) {
            relation.getColumns().add(TableRelationColumn.builder().relation(relation)
                    .sourceColumn(sourceColumns.get(i)).targetColumn(targetColumns.get(i)).columnOrder(i + 1).build());
        }
        return PhysicalModelDto.Relation.fromEntity(relationRepository.save(relation));
    }

    @Override
    @Transactional
    public PhysicalModelDto.Relation updateRelationRules(AccountDto accountDto, Long relationId, PhysicalModelDto.RelationRules rules) {
        Long projectId = accountDto.getProjectId();
        TableRelation relation = relationRepository.findByIdAndProjectId(relationId, projectId).orElseThrow(PhysicalModelServiceImpl::notFound);
        if (relation.getRelationType() != TableRelation.Type.FK) throw invalid("논리 관계에는 삭제·수정 규칙이 없습니다.");
        rejectApproved(relation.getIsApproval());
        relation.setDeleteRule(rule(rules == null ? null : rules.deleteRule()));
        relation.setUpdateRule(rule(rules == null ? null : rules.updateRule()));
        return PhysicalModelDto.Relation.fromEntity(relation);
    }

    /** 규칙 값 : 비었거나 NO_ACTION 이면 null (DDL 기본). 모르는 값은 거절 */
    private static TableRelation.Rule rule(String value) {
        if (value == null || value.isBlank()) return null;
        TableRelation.Rule rule = TableRelation.Rule.parse(value);
        if (rule == null) throw invalid("FK 규칙은 NO_ACTION · RESTRICT · CASCADE · SET_NULL · SET_DEFAULT 중 하나입니다.");
        return rule == TableRelation.Rule.NO_ACTION ? null : rule;
    }

    /**
     * FK 참조 무결성 : ① 대상 컬럼 구성이 대상 테이블의 키(PK 전체 / 고유값 컬럼 / UNIQUE 제약·고유 인덱스 1개의 컬럼 전체)이고
     * ② 컬럼 쌍마다 타입이 프로젝트 대상 DB 에서 FK 로 묶일 수 있어야 한다. 기준은 DDL 생성기와 같다 (승인 여부는 보지 않는다 : DDL 도 보지 않는다).
     */
    private void validateForeignKey(Long projectId, TableInfo source, TableInfo target, List<ColumnInfo> sourceColumns, List<ColumnInfo> targetColumns) {
        if (!TableDdlGenerator.referencesKey(target, targetColumns, uniqueKeys(projectId, target.getId()))) {
            throw invalid("FK 대상 컬럼({1})이 {0} 의 PK 전체 또는 UNIQUE 키 전체와 같지 않습니다. 대상 테이블의 키를 구성하는 컬럼을 모두 지정해주세요.",
                    target.getTableName(), joinNames(targetColumns, ", "));
        }
        DbType dbType = dbType(projectId);
        for (int i = 0; i < sourceColumns.size(); i++) {
            ColumnInfo column = sourceColumns.get(i);
            ColumnInfo referenced = targetColumns.get(i);
            if (!TableDdlGenerator.isReferenceTypeCompatible(dbType, column, referenced)) {
                throw invalid("{0} 과(와) {1} 의 데이터 타입이 {2} 에서 FK 로 연결할 수 있는 조합이 아닙니다. ({3} ↔ {4})",
                        source.getTableName() + "." + column.getColumnName(), target.getTableName() + "." + referenced.getColumnName(), dbType.getLabel(),
                        TableDdlGenerator.mappedType(dbType, column), TableDdlGenerator.mappedType(dbType, referenced));
            }
        }
    }

    /** 테이블의 UNIQUE 제약·고유 인덱스 컬럼명 구성 (PK·고유값 컬럼은 컬럼에서 본다) */
    private List<Set<String>> uniqueKeys(Long projectId, Long tableId) {
        List<Set<String>> keys = new ArrayList<>();
        constraintRepository.findAllByProjectIdAndTableInfo_Id(projectId, tableId).stream()
                .filter(c -> c.getConstraintType() == TableConstraint.Type.UNIQUE)
                .forEach(c -> keys.add(c.getColumns().stream().map(TableConstraintColumn::getColumn).map(ColumnInfo::getColumnName).collect(Collectors.toSet())));
        // 고유 인덱스 중 컬럼 키만이고 조건이 없는 것 (표현식·부분 UNIQUE 는 FK 가 참조할 키가 아니다. INCLUDE 는 키가 아니다)
        indexRepository.findAllByProjectIdAndTableInfo_Id(projectId, tableId).stream()
                .filter(i -> Boolean.TRUE.equals(i.getIsUnique()) && i.getCondition() == null && i.keyMembers().stream().allMatch(c -> c.getColumn() != null))
                .forEach(i -> keys.add(i.keyMembers().stream().map(TableIndexColumn::getColumn).map(ColumnInfo::getColumnName).collect(Collectors.toSet())));
        return keys;
    }

    @Override
    @Transactional
    public PhysicalModelDto.Index saveIndex(AccountDto accountDto, PhysicalModelDto.Index request) {
        Long projectId = accountDto.getProjectId();
        TableInfo table = lockTable(projectId, request.tableId());
        IndexSpec spec = indexSpec(projectId, table, request, null);
        TableIndex index = TableIndex.builder()
                .projectId(projectId)
                .tableInfo(table)
                .isApproval(AuthUtil.isApprovalAvailable())
                .build();
        apply(index, spec);
        TableIndex saved = indexRepository.save(index);
        if (!replacedColumns(saved).isEmpty()) resyncColumnIndexes(projectId, table, replacedColumns(saved)); // 승인 권한자가 등록한 UNIQUE = 승인
        return PhysicalModelDto.Index.fromEntity(saved);
    }

    /**
     * 인덱스 수정 (M-14 D-20). 미승인 인덱스는 바로 고친다. 승인된 인덱스는 변경 요청(변경 전·후 정의)을 남기고,
     * 승인 권한자의 요청은 즉시 승인·반영, 아니면 승인 대기 (승인된 정의는 그대로). 승인 대기 요청이 있으면 새 요청을 받지 않는다.
     * 테이블 행을 잠가 같은 테이블의 인덱스 등록·수정·승인과 직렬화한다.
     */
    @Override
    @Transactional
    public PhysicalModelDto.IndexSaveResult updateIndex(AccountDto accountDto, Long id, PhysicalModelDto.Index request) {
        Long projectId = accountDto.getProjectId();
        TableIndex index = lockIndex(projectId, id);
        if (request.tableId() != null && !request.tableId().equals(index.getTableInfo().getId())) throw invalid("인덱스의 테이블은 바꿀 수 없습니다.");
        rejectColumnIndex(index);
        IndexSpec spec = indexSpec(projectId, index.getTableInfo(), request, index.getId());

        if (!Boolean.TRUE.equals(index.getIsApproval())) {
            apply(index, spec);
            resyncColumnIndexes(projectId, index.getTableInfo(), Set.of()); // 미승인 : 대신하지 않는다
            return new PhysicalModelDto.IndexSaveResult(PhysicalModelDto.Index.fromEntity(index), true, null);
        }
        if (changeRequestRepository.existsByTableIndex_IdAndStatus(id, IndexChangeRequest.Status.REQUESTED)) {
            throw invalid("승인 대기 중인 변경 요청이 있습니다. 먼저 승인하거나 반려해주세요.");
        }
        PhysicalModelDto.IndexDefinition before = PhysicalModelDto.IndexDefinition.of(index);
        PhysicalModelDto.IndexDefinition after = spec.definition();
        if (sameDefinition(before, after)) throw invalid("바뀐 내용이 없습니다.");

        IndexChangeRequest changeRequest = changeRequestRepository.save(IndexChangeRequest.builder()
                .projectId(projectId)
                .tableIndex(index)
                .beforeData(JSON.writeValueAsString(before))
                .afterData(JSON.writeValueAsString(after))
                .status(IndexChangeRequest.Status.REQUESTED)
                .build());
        boolean applied = AuthUtil.isApprovalAvailable(); // 승인 권한자가 등록하면 바로 승인되는 것과 같은 규칙
        if (applied) {
            apply(index, spec);
            resyncColumnIndexes(projectId, index.getTableInfo(), replacedColumns(index));
            changeRequest.setStatus(IndexChangeRequest.Status.APPROVED);
        }
        return new PhysicalModelDto.IndexSaveResult(PhysicalModelDto.Index.fromEntity(index), applied, toDto(changeRequest));
    }

    @Override
    @Transactional
    public PhysicalModelDto.ChangeRequest processIndexChangeRequest(AccountDto accountDto, Long requestId, boolean approve) {
        if (!AuthUtil.isApprovalAvailable()) throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
        Long projectId = accountDto.getProjectId();
        IndexChangeRequest changeRequest = changeRequestRepository.findByIdAndProjectId(requestId, projectId).orElseThrow(PhysicalModelServiceImpl::notFound);
        TableIndex index = lockIndex(projectId, changeRequest.getTableIndex().getId());
        entityManager.refresh(changeRequest); // 잠금 전에 다른 요청이 처리했을 수 있다
        if (changeRequest.getStatus() != IndexChangeRequest.Status.REQUESTED) throw invalid("이미 처리된 변경 요청입니다.");

        if (approve) {
            if (!sameDefinition(PhysicalModelDto.IndexDefinition.of(index), read(changeRequest.getBeforeData()))) {
                throw invalid("요청 뒤에 인덱스가 바뀌었습니다. 요청을 반려하고 다시 요청해주세요.");
            }
            PhysicalModelDto.IndexDefinition after = read(changeRequest.getAfterData());
            // 요청 뒤에 생긴 같은 이름·구성의 인덱스, 바뀐 PK·고유값, 프로젝트 DBMS 변경을 다시 확인한다
            apply(index, indexSpec(projectId, index.getTableInfo(), after.toRequest(), index.getId()));
            resyncColumnIndexes(projectId, index.getTableInfo(), replacedColumns(index));
        }
        changeRequest.setStatus(approve ? IndexChangeRequest.Status.APPROVED : IndexChangeRequest.Status.REJECTED);
        return toDto(changeRequest);
    }

    // ------------------------------------------------------------------ 인덱스 관리 조회 (M-14)

    @Override
    @Transactional(readOnly = true)
    public Page<PhysicalModelDto.IndexRow> selectIndexes(AccountDto accountDto, Pageable pageable, String searchColumn, String searchQuery,
                                                         Long schemaId, Long tableId, String isApproval, String indexSource) {
        Long projectId = accountDto.getProjectId();
        Specification<TableIndex> where = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("projectId"), projectId));
            if (schemaId != null) predicates.add(builder.equal(root.get("tableInfo").get("schema").get("id"), schemaId));
            if (tableId != null) predicates.add(builder.equal(root.get("tableInfo").get("id"), tableId));
            if (notBlank(isApproval)) {
                Predicate approved = builder.isTrue(root.get("isApproval"));
                predicates.add("Y".equalsIgnoreCase(isApproval) ? approved : builder.not(builder.coalesce(root.<Boolean>get("isApproval"), false)));
            }
            if (notBlank(indexSource)) predicates.add(builder.equal(root.get("indexSource"), parse(TableIndex.Source.class, indexSource, null)));
            if (notBlank(searchQuery) && INDEX_SEARCH_COLUMNS.contains(searchColumn)) {
                String like = "%" + searchQuery.trim().toUpperCase(Locale.ROOT) + "%";
                switch (searchColumn) {
                    case "indexName" -> predicates.add(builder.like(builder.upper(root.get("indexName")), like));
                    case "tableName" -> predicates.add(builder.like(builder.upper(root.get("tableInfo").get("tableName")), like));
                    default -> {
                        Subquery<Long> columns = query.subquery(Long.class);
                        var column = columns.from(TableIndexColumn.class);
                        columns.select(column.get("id")).where(builder.equal(column.get("tableIndex"), root),
                                builder.like(builder.upper(column.get("column").get("columnName")), like));
                        predicates.add(builder.exists(columns));
                    }
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        // 화면 컬럼명 → 엔티티 경로 (구성 컬럼·변경 요청은 정렬하지 않는다)
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> !Set.of("columnNames", "pendingRequest").contains(order.getProperty()))
                .map(order -> switch (order.getProperty()) {
                    case "schemaName" -> order.withProperty("tableInfo.schema.schemaName");
                    case "tableName" -> order.withProperty("tableInfo.tableName");
                    default -> order;
                }).toList()).and(Sort.by("id"));
        Page<TableIndex> page = indexRepository.findAll(where, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort));

        // 테이블·스키마·구성 컬럼은 일괄 조회(default_batch_fetch_size), 승인 대기 요청은 페이지 단위 한 번
        Set<Long> pending = page.isEmpty() ? Set.of()
                : changeRequestRepository.findAllByTableIndex_IdInAndStatus(page.map(TableIndex::getId).toList(), IndexChangeRequest.Status.REQUESTED).stream()
                    .map(r -> r.getTableIndex().getId()).collect(Collectors.toSet());
        return page.map(i -> {
            TableInfo table = i.getTableInfo();
            BaseEntity.Audit audit = i.audit();
            return new PhysicalModelDto.IndexRow(i.getId(),
                    table.getSchema() == null ? null : table.getSchema().getId(), table.getSchema() == null ? null : table.getSchema().getSchemaName(),
                    table.getId(), table.getTableName(), i.getIndexName(), i.getIndexType(), yn(i.getIsUnique()),
                    memberText(i),
                    i.getCondition(), i.getIndexSource().name(), yn(i.getIsApproval()), yn(pending.contains(i.getId())),
                    audit.created(), audit.updated(), audit.createdBy(), audit.updatedBy());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public PhysicalModelDto.IndexDetail selectIndex(AccountDto accountDto, Long id) {
        TableIndex index = indexRepository.findByIdAndProjectId(id, accountDto.getProjectId()).orElseThrow(PhysicalModelServiceImpl::notFound);
        TableInfo table = index.getTableInfo();
        BaseEntity.Audit audit = index.audit();
        return new PhysicalModelDto.IndexDetail(PhysicalModelDto.Index.fromEntity(index),
                table.getSchema() == null ? null : table.getSchema().getId(), table.getSchema() == null ? null : table.getSchema().getSchemaName(),
                table.getTableName(), audit.created(), audit.updated(), audit.createdBy(), audit.updatedBy(),
                changeRequestRepository.findAllByTableIndex_IdOrderByIdDesc(id).stream().map(PhysicalModelServiceImpl::toDto).toList(),
                replacedColumnIndexes(index));
    }

    @Override
    @Transactional(readOnly = true)
    public TableDdlDto indexDdl(AccountDto accountDto, Long id, String defaultSchemaName) {
        // 테이블 DDL 보기와 같은 기준 : 기본 스키마 테이블에 붙일 스키마명은 식별자 형식만
        String schemaName = notBlank(defaultSchemaName) ? defaultSchemaName.trim() : null;
        if (schemaName != null && !SchemaInfo.NAME_PATTERN.matcher(schemaName).matches()) throw new CustomException(ErrorCode.INVALID_SCHEMA_NAME);
        TableIndex index = indexRepository.findByIdAndProjectId(id, accountDto.getProjectId()).orElseThrow(PhysicalModelServiceImpl::notFound);
        DbType dbType = dbType(accountDto.getProjectId());
        TableDdlGenerator.Result result = TableDdlGenerator.indexDdl(dbType, index.getTableInfo(), index, schemaName);
        List<String> pending = Boolean.TRUE.equals(index.getIsApproval()) ? List.of() : List.of(Messages.get("인덱스 : {0}", index.getIndexName()));
        return new TableDdlDto(dbType.name(), dbType.getLabel(), index.getTableInfo().getTableName(), result.ddl(), result.warnings(), pending);
    }

    @Override
    @Transactional(readOnly = true)
    public PhysicalModelDto.IndexTypes indexTypes(AccountDto accountDto) {
        DbType dbType = dbType(accountDto.getProjectId());
        return new PhysicalModelDto.IndexTypes(dbType.name(), dbType.getLabel(), TableDdlGenerator.supportedIndexTypes(dbType));
    }

    /** 검색 키 하나 : column 또는 expression, sort 는 저장 표기 (null = ASC·NULLS 기본) */
    private record KeySpec(ColumnInfo column, String expression, String sort) {
        PhysicalModelDto.Member member() {
            boolean desc = sort != null && sort.startsWith("DESC");
            String nulls = "NULLS FIRST".equals(sort) ? "FIRST" : "DESC NULLS LAST".equals(sort) ? "LAST" : null;
            return new PhysicalModelDto.Member(column == null ? null : column.getId(), column == null ? null : column.getColumnName(), expression, desc ? "DESC" : "ASC", nulls);
        }
    }

    /** 검증을 마친 인덱스 정의 (등록·수정·변경 요청 승인이 같은 기준) */
    private record IndexSpec(String name, String type, boolean unique, List<KeySpec> keys, List<ColumnInfo> includes, String condition) {
        PhysicalModelDto.IndexDefinition definition() {
            return new PhysicalModelDto.IndexDefinition(name, type, unique,
                    keys.stream().filter(k -> k.column() != null).map(k -> PhysicalModelDto.ColumnRef.of(k.column())).toList(),
                    keys.stream().map(KeySpec::member).toList(), includes.stream().map(PhysicalModelDto.ColumnRef::of).toList(), condition);
        }

        String signature() {
            return keySignature(keys.stream().map(k -> new String[]{k.column() == null ? null : String.valueOf(k.column().getId()), k.expression(), k.sort()}).toList(),
                    includes.stream().map(ColumnInfo::getId).toList(), condition);
        }
    }

    /** 같은 인덱스인가의 기준 : 키(컬럼 ID 또는 표현식, 정렬) 순서·INCLUDE·조건. 이름·유형·고유 여부는 보지 않는다 (M-14 1차의 "같은 컬럼 구성" 과 같다) */
    private static String keySignature(List<String[]> keys, List<Long> includes, String condition) {
        return keys.stream().map(k -> (k[0] != null ? "C" + k[0] : "E" + k[1]) + "/" + k[2]).collect(Collectors.joining(",")) + "|" + includes + "|" + condition;
    }

    private static String signature(TableIndex index) {
        return keySignature(index.keyMembers().stream().map(c -> new String[]{c.getColumn() == null ? null : String.valueOf(c.getColumn().getId()), c.getExpression(), c.getSort()}).toList(),
                index.includeMembers().stream().map(c -> c.getColumn().getId()).toList(), index.getCondition());
    }

    /**
     * 검색 키 : 컬럼(테이블의 컬럼, 중복 불가) 또는 표현식, 정렬. INCLUDE : 테이블의 컬럼, 중복·키와 겹침 불가. 조건 : WHERE 뒤의 식.
     * 오름차순 컬럼 키만 있고 조건이 없으면 PK·고유값·컬럼 인덱스와 같은 구성 거절. 같은 테이블에 같은 정의(키·INCLUDE·조건)·같은 이름(대소문자 무시) 거절.
     * 유형·고급 속성은 프로젝트 DBMS 에서 만들 수 있어야 한다 (지원하지 않는 조합을 기본값으로 바꾸지 않는다). selfId : 수정하는 인덱스 (비교에서 뺀다)
     */
    private IndexSpec indexSpec(Long projectId, TableInfo table, PhysicalModelDto.Index request, Long selfId) {
        List<PhysicalModelDto.Member> requested = request.keyMembers();
        if (requested.isEmpty()) throw invalid("컬럼을 1개 이상 지정해주세요.");
        List<Long> keyColumnIds = new ArrayList<>();
        Set<String> expressions = new HashSet<>();
        for (PhysicalModelDto.Member member : requested) {
            if (member == null || (member.columnId() != null) == notBlank(member.expression())) throw invalid("검색 키는 컬럼 또는 표현식 중 하나만 지정합니다.");
            if (member.direction() != null && !Set.of("ASC", "DESC").contains(member.direction().toUpperCase(Locale.ROOT))
                    || member.nulls() != null && !Set.of("FIRST", "LAST").contains(member.nulls().toUpperCase(Locale.ROOT))) {
                throw invalid("정렬 값이 올바르지 않습니다. ({0})", member.direction() + " " + member.nulls());
            }
            if (member.columnId() != null) keyColumnIds.add(member.columnId());
            else if (!expressions.add(sqlFragment(member.expression(), false))) throw invalid("같은 표현식이 두 번 들어 있습니다.");
        }
        Map<Long, ColumnInfo> keyColumns = (keyColumnIds.isEmpty() ? List.<ColumnInfo>of() : tableColumns(projectId, table, keyColumnIds)).stream()
                .collect(Collectors.toMap(ColumnInfo::getId, Function.identity()));
        List<KeySpec> keys = requested.stream()
                .map(m -> new KeySpec(m.columnId() == null ? null : keyColumns.get(m.columnId()), m.columnId() == null ? sqlFragment(m.expression(), false) : null, m.sortCode()))
                .toList();
        List<Long> includeIds = refIds(request.includeColumns());
        List<ColumnInfo> includes = includeIds.isEmpty() ? List.of() : tableColumns(projectId, table, includeIds);
        includes.stream().filter(c -> keyColumns.containsKey(c.getId())).findFirst().ifPresent(c -> {
            throw invalid("{0} 은(는) 검색 키와 INCLUDE 에 함께 둘 수 없습니다.", c.getColumnName());
        });
        String condition = notBlank(request.condition()) ? sqlFragment(request.condition(), true) : null;
        boolean unique = Boolean.TRUE.equals(request.isUnique());

        List<ColumnInfo> columnKeys = keys.stream().map(KeySpec::column).filter(Objects::nonNull).toList();
        if (columnKeys.size() == keys.size() && condition == null) rejectSameAsColumnKeys(table, columnKeys, unique);
        IndexSpec draft = new IndexSpec(null, null, unique, keys, includes, condition);
        List<TableIndex> others = indexRepository.findAllByProjectIdAndTableInfo_Id(projectId, table.getId()).stream()
                .filter(i -> !i.getId().equals(selfId)).toList();
        // UNIQUE 우선 (D-19 방안 C) : 컬럼 인덱스(일반)와 같은 구성의 UNIQUE 는 받는다 — 승인되면 그 컬럼 인덱스를 대신한다
        if (others.stream().filter(i -> !(unique && i.getIndexSource() == TableIndex.Source.COLUMN))
                .anyMatch(i -> signature(i).equals(draft.signature()))) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "같은 컬럼 구성의 인덱스");
        }

        String defaultName = (unique ? "UX_" : "IX_") + table.getTableName() + "_" + (columnKeys.isEmpty() ? "EXPR" : joinNames(columnKeys));
        String name = objectName(request.indexName(), defaultName);
        if (others.stream().anyMatch(i -> i.getIndexName().equalsIgnoreCase(name))) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);

        DbType dbType = dbType(projectId);
        String type = TableDdlGenerator.normalizeIndexType(request.indexType());
        String problem = TableDdlGenerator.indexTypeProblem(dbType, type, unique);
        if (problem == null) {
            problem = TableDdlGenerator.advancedProblem(dbType, type, columnKeys.size() < keys.size(), keys.stream().anyMatch(k -> k.sort() != null),
                    !includes.isEmpty(), condition != null);
        }
        if (problem == null) problem = TableDdlGenerator.keyCountProblem(dbType, type, keys.size());
        if (problem != null) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, problem);
        return new IndexSpec(name, type, unique, keys, includes, condition);
    }

    /**
     * 표현식·조건 SQL 조각의 정적 검사 : 1000자 이하, 문장 구분자·주석(; -- /* *\/) 금지, 괄호·작은따옴표 짝.
     * 실행하거나 파싱하지 않으므로 문법·컬럼 이름·연산자 클래스의 유효성은 보장하지 않는다 (DDL 을 실행할 때 DB 가 판단한다).
     */
    private static String sqlFragment(String value, boolean condition) {
        String sql = value.trim();
        if (sql.isEmpty()) throw invalid(condition ? "WHERE 조건을 입력해주세요." : "표현식을 입력해주세요.");
        if (sql.length() > 1000) throw invalid("표현식·조건은 1000자까지 입력할 수 있습니다.");
        if (sql.contains(";") || sql.contains("--") || sql.contains("/*") || sql.contains("*/")) throw invalid("표현식·조건에는 ; -- /* */ 를 쓸 수 없습니다.");
        int depth = 0;
        boolean quoted = false;
        for (char c : sql.toCharArray()) {
            if (c == '\'') quoted = !quoted;
            else if (!quoted && c == '(') depth++;
            else if (!quoted && c == ')' && --depth < 0) break;
        }
        if (quoted || depth != 0) throw invalid("표현식·조건의 괄호나 따옴표 짝이 맞지 않습니다.");
        return sql;
    }

    /** 정의를 인덱스에 쓴다 (키 다음 INCLUDE). 구성 행은 순서 자리를 그대로 두고 내용만 바꾼다 ((인덱스, 순서) 고유 인덱스와 부딪히지 않게) */
    private static void apply(TableIndex index, IndexSpec spec) {
        index.setIndexName(spec.name());
        index.setIndexType(spec.type());
        index.setIsUnique(spec.unique());
        index.setCondition(spec.condition());
        List<KeySpec> members = new ArrayList<>(spec.keys());
        spec.includes().forEach(c -> members.add(new KeySpec(c, null, TableIndexColumn.INCLUDE)));
        List<TableIndexColumn> rows = index.getColumns();
        for (int i = 0; i < members.size(); i++) {
            if (i >= rows.size()) rows.add(TableIndexColumn.builder().tableIndex(index).build());
            TableIndexColumn row = rows.get(i);
            row.setColumn(members.get(i).column());
            row.setExpression(members.get(i).expression());
            row.setSort(members.get(i).sort());
            row.setColumnOrder(i + 1);
        }
        while (rows.size() > members.size()) rows.remove(rows.size() - 1);
    }

    /** 이름(대소문자 무시)·유형·고유 여부·키(컬럼 ID 또는 표현식, 정렬)·INCLUDE·조건이 같은가 (컬럼명 스냅샷은 보지 않는다) */
    private static boolean sameDefinition(PhysicalModelDto.IndexDefinition a, PhysicalModelDto.IndexDefinition b) {
        return a.indexName().equalsIgnoreCase(b.indexName())
                && Objects.equals(a.indexType(), b.indexType())
                && Boolean.TRUE.equals(a.isUnique()) == Boolean.TRUE.equals(b.isUnique())
                && definitionSignature(a).equals(definitionSignature(b));
    }

    private static String definitionSignature(PhysicalModelDto.IndexDefinition d) {
        return keySignature(d.toRequest().keyMembers().stream()
                        .map(m -> new String[]{m.columnId() == null ? null : String.valueOf(m.columnId()), m.expression(), m.sortCode()}).toList(),
                refIds(d.includeColumns()), d.condition());
    }

    /** 인덱스를 찾고 그 테이블 행을 잠근 뒤 최신 상태로 다시 읽는다 (잠그기 전에 다른 트랜잭션이 고쳤거나 지웠을 수 있다) */
    private TableIndex lockIndex(Long projectId, Long id) {
        TableIndex index = indexRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalModelServiceImpl::notFound);
        lockTable(projectId, index.getTableInfo().getId());
        try {
            entityManager.refresh(index);
        } catch (EntityNotFoundException e) {
            throw notFound();
        }
        return index;
    }

    private TableInfo lockTable(Long projectId, Long tableId) {
        if (tableId == null) throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        return tableInfoRepository.findForUpdate(tableId, projectId).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
    }

    private static void rejectColumnIndex(TableIndex index) {
        if (index.getIndexSource() == TableIndex.Source.COLUMN) {
            throw invalid("컬럼의 인덱스 여부로 만든 인덱스입니다. 컬럼 관리에서 인덱스 여부를 바꿔주세요.");
        }
    }

    /** 목록의 구성 표시 : 키(표현식은 괄호, 정렬) + INCLUDE */
    private static String memberText(TableIndex index) {
        String keys = index.keyMembers().stream()
                .map(c -> (c.getColumn() != null ? c.getColumn().getColumnName() : "(" + c.getExpression() + ")") + (c.getSort() == null ? "" : " " + c.getSort()))
                .collect(Collectors.joining(", "));
        List<TableIndexColumn> includes = index.includeMembers();
        return includes.isEmpty() ? keys : keys + " INCLUDE (" + includes.stream().map(c -> c.getColumn().getColumnName()).collect(Collectors.joining(", ")) + ")";
    }

    private static PhysicalModelDto.ChangeRequest toDto(IndexChangeRequest r) {
        boolean processed = r.getStatus() != IndexChangeRequest.Status.REQUESTED;
        BaseEntity.Audit audit = r.audit();
        return new PhysicalModelDto.ChangeRequest(r.getId(), r.getTableIndex().getId(), r.getStatus().name(),
                read(r.getBeforeData()), read(r.getAfterData()), audit.createdBy(), audit.created(),
                processed ? audit.updatedBy() : null, processed ? audit.updated() : null);
    }

    private static PhysicalModelDto.IndexDefinition read(String json) {
        return JSON.readValue(json, PhysicalModelDto.IndexDefinition.class);
    }

    private DbType dbType(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
    }

    private static String yn(Boolean value) {
        return Boolean.TRUE.equals(value) ? "Y" : "N";
    }

    @Override
    @Transactional
    public PhysicalModelDto.Constraint saveConstraint(AccountDto accountDto, PhysicalModelDto.Constraint request) {
        Long projectId = accountDto.getProjectId();
        TableInfo table = requireTable(projectId, request.tableId());
        TableConstraint.Type type = parse(TableConstraint.Type.class, request.constraintType(), null);
        if (type == null) throw invalid("제약조건 유형은 UNIQUE 또는 CHECK 입니다. (PK 는 컬럼의 PK 여부, FK 는 관계로 정의)");

        List<TableConstraint> existing = constraintRepository.findAllByProjectIdAndTableInfo_Id(projectId, table.getId());
        List<ColumnInfo> columns = List.of();
        String checkExpression = null;
        String defaultName;

        if (type == TableConstraint.Type.UNIQUE) {
            columns = tableColumns(projectId, table, refIds(request.columns()));
            rejectSameAsColumnKeys(table, columns, true);
            Set<Long> columnSet = new HashSet<>(ids(columns));
            boolean duplicated = existing.stream().anyMatch(c -> c.getConstraintType() == TableConstraint.Type.UNIQUE
                    && new HashSet<>(ids(c.getColumns().stream().map(TableConstraintColumn::getColumn).toList())).equals(columnSet))
                    || indexRepository.findAllByProjectIdAndTableInfo_Id(projectId, table.getId()).stream()
                    .anyMatch(i -> Boolean.TRUE.equals(i.getIsUnique())
                            && new HashSet<>(ids(i.getColumns().stream().map(TableIndexColumn::getColumn).toList())).equals(columnSet));
            if (duplicated) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "같은 컬럼 구성의 UNIQUE");
            defaultName = "UK_" + table.getTableName() + "_" + joinNames(columns);
        } else {
            checkExpression = request.checkExpression() == null ? "" : request.checkExpression().trim();
            if (checkExpression.isEmpty()) throw invalid("CHECK 조건식을 입력해주세요.");
            // DDL 은 출력만 하지만 문장이 나뉘지 않게 한다
            if (checkExpression.contains(";") || checkExpression.contains("--")) throw invalid("CHECK 조건식에는 ; 와 -- 를 쓸 수 없습니다.");
            defaultName = "CK_" + table.getTableName() + "_" + (existing.stream().filter(c -> c.getConstraintType() == TableConstraint.Type.CHECK).count() + 1);
        }

        String name = objectName(request.constraintName(), defaultName);
        if (existing.stream().anyMatch(c -> c.getConstraintName().equalsIgnoreCase(name))) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);

        TableConstraint constraint = TableConstraint.builder()
                .projectId(projectId)
                .tableInfo(table)
                .constraintName(name)
                .constraintType(type)
                .checkExpression(checkExpression)
                .isApproval(AuthUtil.isApprovalAvailable())
                .build();
        for (int i = 0; i < columns.size(); i++) {
            constraint.getColumns().add(TableConstraintColumn.builder().tableConstraint(constraint).column(columns.get(i)).columnOrder(i + 1).build());
        }
        return PhysicalModelDto.Constraint.fromEntity(constraintRepository.save(constraint));
    }

    // ------------------------------------------------------------------ 삭제 / 승인

    @Override
    @Transactional
    public void delete(AccountDto accountDto, Kind kind, Long id) {
        Long projectId = accountDto.getProjectId();
        switch (kind) {
            case RELATION -> {
                TableRelation relation = relationRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalModelServiceImpl::notFound);
                if (relation.getRelationSource() == TableRelation.Source.REF_COLUMN) {
                    throw invalid("참조 테이블명으로 만든 관계입니다. 컬럼 관리에서 참조 테이블을 비워주세요.");
                }
                rejectApproved(relation.getIsApproval());
                relationRepository.delete(relation);
            }
            case INDEX -> {
                TableIndex index = lockIndex(projectId, id);
                rejectColumnIndex(index);
                rejectApproved(index.getIsApproval());
                indexRepository.delete(index); // 변경 요청 이력은 FK CASCADE 로 함께 지워진다
                resyncColumnIndexes(projectId, index.getTableInfo(), Set.of());
            }
            case CONSTRAINT -> {
                TableConstraint constraint = constraintRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalModelServiceImpl::notFound);
                rejectApproved(constraint.getIsApproval());
                constraintRepository.delete(constraint);
            }
        }
    }

    @Override
    @Transactional
    public void updateApproval(AccountDto accountDto, Kind kind, Long id, boolean isApproval) {
        if (!AuthUtil.isApprovalAvailable()) throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
        Long projectId = accountDto.getProjectId();
        switch (kind) {
            case RELATION -> relationRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalModelServiceImpl::notFound).setIsApproval(isApproval);
            case INDEX -> {
                TableIndex index = lockIndex(projectId, id);
                rejectColumnIndex(index);
                // 승인 대기 요청은 승인된 정의를 기준으로 만든 것이라, 승인을 취소하면 요청 없이 직접 고칠 수 있게 되어 요청과 어긋난다
                if (!isApproval && changeRequestRepository.existsByTableIndex_IdAndStatus(id, IndexChangeRequest.Status.REQUESTED)) {
                    throw invalid("승인 대기 중인 변경 요청이 있습니다. 먼저 승인하거나 반려해주세요.");
                }
                index.setIsApproval(isApproval);
                // 승인 : 이 UNIQUE 가 대신하는 컬럼 인덱스를 지운다 / 승인 취소 : 대신하던 컬럼 인덱스를 되살린다
                resyncColumnIndexes(projectId, index.getTableInfo(), replacedColumns(index));
            }
            case CONSTRAINT -> constraintRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalModelServiceImpl::notFound).setIsApproval(isApproval);
        }
    }

    // ------------------------------------------------------------------ 컬럼 변경 연동

    @Override
    @Transactional
    public void releaseColumns(Long projectId, Collection<ColumnInfo> columns) {
        if (columns.isEmpty()) return;
        Set<Long> columnIds = new HashSet<>(ids(columns));

        // 논리 속성에 매핑된 컬럼은 삭제할 수 없다. 테이블 행을 잠가 매핑 등록과 엇갈리지 않게 한다 (ID 순 : 교착 방지)
        columns.stream().map(ColumnInfo::getTableInfo).filter(Objects::nonNull).map(TableInfo::getId).distinct().sorted()
                .forEach(tableId -> tableInfoRepository.findForUpdate(tableId, projectId));
        attributeMappingRepository.findFirstByColumn_IdIn(columnIds).ifPresent(m -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "속성 {0} 에 매핑된 컬럼입니다. 매핑을 먼저 해제해주세요.", m.getAttribute().getAttributeName());
        });

        List<TableRelation> relations = relationRepository.findAllUsingColumns(columnIds);
        relations.stream().filter(r -> r.getRelationSource() != TableRelation.Source.REF_COLUMN).findFirst().ifPresent(r -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "관계 {0} 에서 사용 중인 컬럼입니다.", r.getRelationName());
        });
        List<TableIndex> indexes = indexRepository.findAllUsingColumns(columnIds);
        indexes.stream().filter(i -> i.getIndexSource() != TableIndex.Source.COLUMN).findFirst().ifPresent(i -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "인덱스 {0} 에서 사용 중인 컬럼입니다.", i.getIndexName());
        });
        constraintRepository.findAllUsingColumns(columnIds).stream().findFirst().ifPresent(c -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "제약조건 {0} 에서 사용 중인 컬럼입니다.", c.getConstraintName());
        });
        relationRepository.deleteAll(relations); // 남은 것은 모두 REF_COLUMN (삭제 후 동기화가 다시 만든다)
        indexRepository.deleteAll(indexes); // 남은 것은 모두 그 컬럼의 인덱스 여부에서 만든 COLUMN 인덱스
    }

    @Override
    @Transactional
    public void syncReferenceRelations(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return;

        // 아직 반영되지 않은 변경을 먼저 내보내고(아래 조회가 DB 기준), 여기서 맞춘 테이블은 커밋 직전 대상에서 뺀다
        entityManager.flush();
        ReferenceSyncBoundary.synced(projectId, tableIds);

        // 이 테이블들을 가리킬 수 있는 이름 : 테이블명, 스키마명.테이블명 (TableNameResolver)
        Set<String> tableNames = tableInfoRepository.findAllById(tableIds).stream()
                .filter(t -> projectId.equals(t.getProjectId()))
                .flatMap(t -> TableNameResolver.namesOf(t).stream())
                .collect(Collectors.toSet());

        List<ColumnInfo> ownColumns = columnInfoRepository.findAllByTableInfo_IdIn(tableIds).stream()
                .filter(c -> projectId.equals(c.getProjectId()))
                .toList();
        syncColumnIndexes(projectId, tableIds, ownColumns, Set.of());

        // 대상 컬럼 : 이 테이블들의 컬럼(참조를 지웠을 수도 있음) + 이 테이블들을 참조하는 컬럼(대상 PK 가 바뀌었을 수 있음)
        Map<Long, ColumnInfo> candidates = new LinkedHashMap<>();
        ownColumns.forEach(c -> candidates.put(c.getId(), c));
        if (!tableNames.isEmpty()) {
            columnInfoRepository.findAllReferencing(projectId, tableNames).forEach(c -> candidates.putIfAbsent(c.getId(), c));
        }
        // + 이 테이블들을 대상으로 만들어져 있는 관계의 컬럼 (테이블명이 바뀌어 더 이상 그 이름을 참조하지 않을 수 있다)
        Set<Long> targetingColumnIds = relationRepository.findAllByProjectIdAndRelationSourceAndTargetTable_IdIn(projectId, TableRelation.Source.REF_COLUMN, tableIds).stream()
                .map(TableRelation::getDerivedColumnId)
                .filter(id -> id != null && !candidates.containsKey(id))
                .collect(Collectors.toSet());
        if (!targetingColumnIds.isEmpty()) {
            columnInfoRepository.findAllById(targetingColumnIds).forEach(c -> candidates.putIfAbsent(c.getId(), c));
        }
        if (candidates.isEmpty()) return;

        Map<Long, TableRelation> existing = relationRepository.findAllByDerivedColumnIdIn(candidates.keySet()).stream()
                .collect(Collectors.toMap(TableRelation::getDerivedColumnId, Function.identity()));

        Set<String> refNames = candidates.values().stream()
                .map(c -> TableNameResolver.tableNameOf(c.getRefTableName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<TableInfo> namedTables = refNames.isEmpty() ? List.of() : tableInfoRepository.findAllByProjectIdAndTableNameIn(projectId, refNames);
        Set<Long> targetIds = namedTables.stream().map(TableInfo::getId).collect(Collectors.toSet());
        Map<Long, List<ColumnInfo>> pkByTable = targetIds.isEmpty() ? Map.of()
                : columnInfoRepository.findAllByTableInfo_IdInAndIsPkTrue(targetIds).stream()
                    .collect(Collectors.groupingBy(c -> c.getTableInfo().getId()));

        for (ColumnInfo column : candidates.values()) {
            TableRelation relation = existing.get(column.getId());
            ColumnInfo targetPk = referencedPk(column, namedTables, pkByTable, relation == null ? null : relation.getTargetTable().getId());

            if (targetPk == null) {
                if (relation != null) relationRepository.delete(relation);
                continue;
            }
            String relationName = "FK_" + column.getTableInfo().getTableName() + "_" + column.getColumnName();
            if (relation == null) {
                relation = TableRelation.builder()
                        .projectId(projectId)
                        .sourceTable(column.getTableInfo())
                        .relationType(TableRelation.Type.FK)
                        .relationSource(TableRelation.Source.REF_COLUMN)
                        .derivedColumnId(column.getId())
                        .isApproval(true)
                        .build();
                relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(column).columnOrder(1).build());
            } else if (relation.getTargetTable().getId().equals(targetPk.getTableInfo().getId())
                    && relation.getColumns().get(0).getTargetColumn().getId().equals(targetPk.getId())
                    && relationName.equals(relation.getRelationName())) {
                continue; // 그대로
            }
            relation.setTargetTable(targetPk.getTableInfo());
            relation.setRelationName(relationName); // 원본 테이블명·컬럼명이 바뀌면 이름도 따라간다 (DDL 의 FK 이름과 같은 규칙)
            relation.getColumns().get(0).setTargetColumn(targetPk);
            relationRepository.save(relation);
        }
    }

    /**
     * 컬럼의 인덱스 여부 → 출처 COLUMN 단일 컬럼 인덱스 (M-14 D-19). 기존 DDL 규칙과 같다 : 인덱스 여부이고 PK·고유값이 아닐 때,
     * 이름 IX_테이블명_컬럼명, 승인 상태는 컬럼을 따른다. 같은 컬럼 하나의 일반 인덱스를 사용자가 이미 정의했으면 만들지 않는다 (DDL 중복 방지).
     * 조건이 풀리면(인덱스 여부 해제, PK·고유값이 됨, 컬럼 삭제) 지운다. V3 마이그레이션이 기존 데이터를 같은 규칙으로 옮겼다.
     */
    /**
     * 사용자 인덱스를 지우거나 고치면 "같은 컬럼 하나의 사용자 일반 인덱스" 조건이 바뀔 수 있다 (예: 그 인덱스를 지우면 컬럼 인덱스가 다시 필요).
     * 인덱스 변경은 테이블·컬럼 변경 표시(ReferenceSyncListener)를 남기지 않으므로 그 테이블만 직접 다시 맞춘다.
     * replaceColumns : 방금 승인된 사용자 UNIQUE 가 대신하는 컬럼 — 그 컬럼의 기존 컬럼 인덱스는 이 승인 처리(같은 트랜잭션·같은 변경 그룹)에서 지운다.
     */
    private void resyncColumnIndexes(Long projectId, TableInfo table, Set<Long> replaceColumns) {
        entityManager.flush();
        syncColumnIndexes(projectId, List.of(table.getId()), columnInfoRepository.findAllByTableInfo_IdIn(List.of(table.getId())).stream()
                .filter(c -> projectId.equals(c.getProjectId())).toList(), replaceColumns);
    }

    private void syncColumnIndexes(Long projectId, Collection<Long> tableIds, List<ColumnInfo> columns, Set<Long> replaceColumns) {
        Map<Long, TableIndex> derived = new HashMap<>();
        Set<Long> coveredByIndex = new HashSet<>();  // 같은 컬럼 하나의 사용자 일반 인덱스 (승인 여부 무관)
        Set<Long> coveredByUnique = new HashSet<>(); // 같은 컬럼 하나의 승인된 사용자 UNIQUE 인덱스 (방안 C : UNIQUE 우선)
        for (TableIndex index : indexRepository.findAllByProjectIdAndTableInfo_IdIn(projectId, tableIds)) {
            Long column = singleColumn(index);
            if (index.getIndexSource() == TableIndex.Source.COLUMN) {
                derived.put(index.getDerivedColumnId(), index);
            } else if (column != null && !Boolean.TRUE.equals(index.getIsUnique())) {
                coveredByIndex.add(column);
            } else if (column != null && Boolean.TRUE.equals(index.getIsApproval())) {
                coveredByUnique.add(column);
            }
        }
        for (ColumnInfo column : columns) {
            TableIndex index = derived.remove(column.getId());
            boolean wanted = column.getTableInfo() != null && Boolean.TRUE.equals(column.getIsIndex())
                    && !Boolean.TRUE.equals(column.getIsPk()) && !Boolean.TRUE.equals(column.getIsUnique()) && !coveredByIndex.contains(column.getId());
            if (!wanted) {
                if (index != null) indexRepository.delete(index);
                continue;
            }
            if (coveredByUnique.contains(column.getId())) {
                // 승인된 UNIQUE 가 대신한다 : 새로 만들지 않는다. 이미 있는 컬럼 인덱스는 그 UNIQUE 의 승인 처리에서만 지운다
                // (동기화만으로는 지우지 않는다 : V3 이전부터 공존하던 승인 데이터·DDL 보존)
                if (index == null) continue;
                if (replaceColumns.contains(column.getId())) {
                    indexRepository.delete(index);
                    continue;
                }
            }
            String name = "IX_" + column.getTableInfo().getTableName() + "_" + column.getColumnName();
            boolean approved = Boolean.TRUE.equals(column.getIsApproval());
            if (index == null) {
                index = TableIndex.builder()
                        .projectId(projectId)
                        .tableInfo(column.getTableInfo())
                        .indexSource(TableIndex.Source.COLUMN)
                        .derivedColumnId(column.getId())
                        .isUnique(false)
                        .build();
                index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column(column).columnOrder(1).build());
            } else if (name.equals(index.getIndexName()) && Boolean.valueOf(approved).equals(index.getIsApproval())) {
                continue; // 그대로
            }
            index.setIndexName(name); // 테이블명·컬럼명이 바뀌면 이름도 따라간다
            index.setIsApproval(approved);
            indexRepository.save(index);
        }
        indexRepository.deleteAll(derived.values()); // 컬럼이 없어진 인덱스 (보통은 컬럼 삭제 전에 releaseColumns 가 지운다)
    }

    /** 컬럼 하나만으로 된 인덱스면 그 컬럼 ID (컬럼 인덱스 여부를 대신할 수 있는 인덱스) */
    private static Long singleColumn(TableIndex index) {
        // 키가 그 컬럼 하나 (정렬·INCLUDE 는 상관없다). 표현식·부분 인덱스는 컬럼 전체를 대신하지 않는다
        List<TableIndexColumn> keys = index.keyMembers();
        return keys.size() == 1 && keys.get(0).getColumn() != null && index.getCondition() == null ? keys.get(0).getColumn().getId() : null;
    }

    private List<String> replacedColumnIndexes(TableIndex index) {
        Long column = singleColumn(index);
        if (index.getIndexSource() != TableIndex.Source.USER || !Boolean.TRUE.equals(index.getIsUnique()) || column == null) return List.of();
        return indexRepository.findAllByProjectIdAndTableInfo_Id(index.getProjectId(), index.getTableInfo().getId()).stream()
                .filter(i -> i.getIndexSource() == TableIndex.Source.COLUMN && column.equals(i.getDerivedColumnId()))
                .map(TableIndex::getIndexName).toList();
    }

    /** 승인된 사용자 UNIQUE 단일 컬럼 인덱스 → 이 승인 처리로 대신할(지울) 컬럼 인덱스의 컬럼 */
    private static Set<Long> replacedColumns(TableIndex index) {
        Long column = singleColumn(index);
        return index.getIndexSource() == TableIndex.Source.USER && Boolean.TRUE.equals(index.getIsUnique())
                && Boolean.TRUE.equals(index.getIsApproval()) && column != null ? Set.of(column) : Set.of();
    }

    /**
     * 참조 테이블명 → 참조 PK 컬럼. DDL 생성기의 FK 조건과 같다 : 이름이 테이블 하나로 정해지고({@link TableNameResolver}, 이미 있는 관계의 대상은 모호해도 유지),
     * 그 테이블의 PK 가 정확히 1개, 자기 PK 를 가리키지 않음. 아니면 null (관계를 만들지 않는다)
     */
    private static ColumnInfo referencedPk(ColumnInfo column, List<TableInfo> namedTables, Map<Long, List<ColumnInfo>> pkByTable, Long currentTargetId) {
        if (column.getTableInfo() == null || !notBlank(column.getRefTableName())) return null;
        TableNameResolver.Result target = TableNameResolver.resolve(column.getRefTableName(), column.getTableInfo().getSchema(), namedTables, currentTargetId);
        if (target.status() != TableNameResolver.Status.FOUND) return null;
        List<ColumnInfo> pk = pkByTable.getOrDefault(target.table().getId(), List.of());
        if (pk.size() != 1 || pk.get(0).getId().equals(column.getId())) return null;
        return pk.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public TableDdlGenerator.Extras ddlExtras(TableInfo table) {
        Long projectId = table.getProjectId();
        List<TableRelation> relations = relationRepository.findAllByProjectIdAndSourceTable_Id(projectId, table.getId()).stream()
                .filter(r -> r.getRelationSource() != TableRelation.Source.REF_COLUMN && r.getRelationType() == TableRelation.Type.FK)
                .toList();
        Map<Long, List<Set<String>>> targetUniqueKeys = new HashMap<>();
        relations.forEach(r -> targetUniqueKeys.computeIfAbsent(r.getTargetTable().getId(), id -> uniqueKeys(projectId, id)));
        return new TableDdlGenerator.Extras(
                constraintRepository.findAllByProjectIdAndTableInfo_Id(projectId, table.getId()),
                // 컬럼 인덱스(예전 컬럼 속성 인덱스)를 컬럼 순서대로 먼저, 다음에 등록 순서 : M-14 이전 DDL 과 같은 순서
                indexRepository.findAllByProjectIdAndTableInfo_Id(projectId, table.getId()).stream()
                        .sorted(Comparator.comparing((TableIndex i) -> i.getIndexSource() != TableIndex.Source.COLUMN)
                                .thenComparing(i -> i.getIndexSource() == TableIndex.Source.COLUMN ? i.getColumns().get(0).getColumn().getSortOrder() : null,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(TableIndex::getId))
                        .toList(),
                relations, targetUniqueKeys)
                // 참조 컬럼(REF_COLUMN) 관계 : 컬럼의 참조 테이블로 만드는 FK 에 이 관계의 삭제·수정 규칙을 쓴다
                .withReferenceRelations(relationRepository.findAllByProjectIdAndSourceTable_Id(projectId, table.getId()).stream()
                        .filter(r -> r.getRelationSource() == TableRelation.Source.REF_COLUMN && r.getDerivedColumnId() != null)
                        .collect(Collectors.toMap(TableRelation::getDerivedColumnId, r -> r, (a, b) -> a)));
    }

    // ------------------------------------------------------------------ 보조

    private TableInfo requireTable(Long projectId, Long tableId) {
        TableInfo table = tableId == null ? null : tableInfoRepository.findByIdAndProjectId(tableId, projectId);
        if (table == null) throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        return table;
    }

    /** 요청 순서대로 컬럼을 찾는다. 이 테이블(같은 프로젝트)의 컬럼이 아니거나 중복이면 오류 */
    private List<ColumnInfo> tableColumns(Long projectId, TableInfo table, List<Long> columnIds) {
        if (columnIds.isEmpty()) throw invalid("컬럼을 1개 이상 지정해주세요.");
        if (columnIds.contains(null) || new HashSet<>(columnIds).size() != columnIds.size()) throw invalid("컬럼이 비었거나 중복되어 있습니다.");
        Map<Long, ColumnInfo> found = columnInfoRepository.findAllById(columnIds).stream()
                .filter(c -> projectId.equals(c.getProjectId()) && c.getTableInfo() != null && table.getId().equals(c.getTableInfo().getId()))
                .collect(Collectors.toMap(ColumnInfo::getId, Function.identity()));
        if (found.size() != columnIds.size()) throw invalid("{0} 의 컬럼이 아닌 컬럼이 있습니다.", table.getTableName());
        return columnIds.stream().map(found::get).toList();
    }

    /** 컬럼의 PK / 고유값 / 인덱스 설정과 같은 구성이면 중복 (DDL 에 같은 인덱스·제약이 두 번 생기지 않게) */
    private static void rejectSameAsColumnKeys(TableInfo table, List<ColumnInfo> columns, boolean unique) {
        Set<Long> pk = table.getColumns().stream().filter(c -> Boolean.TRUE.equals(c.getIsPk())).map(ColumnInfo::getId).collect(Collectors.toSet());
        if (unique && !pk.isEmpty() && pk.equals(new HashSet<>(ids(columns)))) throw invalid("PK 와 같은 컬럼 구성입니다.");
        if (columns.size() == 1) {
            ColumnInfo c = columns.get(0);
            boolean covered = Boolean.TRUE.equals(c.getIsPk()) || Boolean.TRUE.equals(c.getIsUnique()) || (!unique && Boolean.TRUE.equals(c.getIsIndex()));
            if (covered) throw invalid("{0} 은(는) 컬럼의 PK·고유값·인덱스 설정으로 이미 정의되어 있습니다.", c.getColumnName());
        }
    }

    private static String objectName(String requested, String defaultName) {
        String name = notBlank(requested) ? requested.trim() : defaultName;
        if (!OBJECT_NAME.matcher(name).matches()) throw invalid("이름은 영문 또는 _ 로 시작하고 영문/숫자/_/$ 만 쓸 수 있습니다. ({0})", name);
        return name;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E defaultValue) {
        if (!notBlank(value)) return defaultValue;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("알 수 없는 유형입니다. ({0})", value);
        }
    }

    private static void rejectApproved(Boolean isApproval) {
        if (Boolean.TRUE.equals(isApproval)) throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
    }

    private static List<Long> refIds(List<PhysicalModelDto.ColumnRef> refs) {
        return refs == null ? List.of() : refs.stream().map(PhysicalModelDto.ColumnRef::columnId).toList();
    }

    private static List<Long> ids(Collection<ColumnInfo> columns) {
        return columns.stream().map(ColumnInfo::getId).toList();
    }

    private static String joinNames(List<ColumnInfo> columns) {
        return joinNames(columns, "_");
    }

    private static String joinNames(List<ColumnInfo> columns, String delimiter) {
        return columns.stream().map(ColumnInfo::getColumnName).collect(Collectors.joining(delimiter));
    }

    private static String normalize(String tableName) {
        return tableName.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, message, args);
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "대상을 찾을 수 없습니다.");
    }
}
