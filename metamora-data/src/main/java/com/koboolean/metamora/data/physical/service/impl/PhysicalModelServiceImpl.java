package com.koboolean.metamora.data.physical.service.impl;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.util.AuthUtil;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.column.repository.ColumnInfoRepository;
import com.koboolean.metamora.data.physical.domain.dto.PhysicalModelDto;
import com.koboolean.metamora.data.physical.domain.entity.*;
import com.koboolean.metamora.data.physical.repository.TableConstraintRepository;
import com.koboolean.metamora.data.physical.repository.TableIndexRepository;
import com.koboolean.metamora.data.physical.repository.TableRelationRepository;
import com.koboolean.metamora.data.physical.service.PhysicalModelService;
import com.koboolean.metamora.data.physical.sync.ReferenceSyncBoundary;
import com.koboolean.metamora.data.table.ddl.TableDdlGenerator;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PhysicalModelServiceImpl implements PhysicalModelService {

    // 관계·인덱스·제약조건 이름 : 따옴표 없는 식별자 (DBMS 별 길이 제한은 DDL 생성 시 자른다)
    private static final Pattern OBJECT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,127}$");

    private final TableInfoRepository tableInfoRepository;
    private final ColumnInfoRepository columnInfoRepository;
    private final TableRelationRepository relationRepository;
    private final TableIndexRepository indexRepository;
    private final TableConstraintRepository constraintRepository;
    private final EntityManager entityManager;

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

    // ------------------------------------------------------------------ 등록 (수정은 삭제 후 다시 등록)

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

        TableRelation relation = TableRelation.builder()
                .projectId(projectId)
                .sourceTable(source)
                .targetTable(target)
                .relationName(objectName(request.relationName(), "FK_" + source.getTableName() + "_" + sourceColumns.get(0).getColumnName()))
                .relationType(parse(TableRelation.Type.class, request.relationType(), TableRelation.Type.FK))
                .relationSource(TableRelation.Source.USER)
                .isApproval(AuthUtil.isApprovalAvailable())
                .build();
        for (int i = 0; i < pairs.size(); i++) {
            relation.getColumns().add(TableRelationColumn.builder().relation(relation)
                    .sourceColumn(sourceColumns.get(i)).targetColumn(targetColumns.get(i)).columnOrder(i + 1).build());
        }
        return PhysicalModelDto.Relation.fromEntity(relationRepository.save(relation));
    }

    @Override
    @Transactional
    public PhysicalModelDto.Index saveIndex(AccountDto accountDto, PhysicalModelDto.Index request) {
        Long projectId = accountDto.getProjectId();
        TableInfo table = requireTable(projectId, request.tableId());
        List<ColumnInfo> columns = tableColumns(projectId, table, refIds(request.columns()));
        boolean unique = Boolean.TRUE.equals(request.isUnique());

        rejectSameAsColumnKeys(table, columns, unique);
        List<TableIndex> existing = indexRepository.findAllByProjectIdAndTableInfo_Id(projectId, table.getId());
        if (existing.stream().anyMatch(i -> ids(i.getColumns().stream().map(TableIndexColumn::getColumn).toList()).equals(ids(columns)))) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "같은 컬럼 구성의 인덱스");
        }

        String name = objectName(request.indexName(), (unique ? "UX_" : "IX_") + table.getTableName() + "_" + joinNames(columns));
        if (existing.stream().anyMatch(i -> i.getIndexName().equalsIgnoreCase(name))) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);

        TableIndex index = TableIndex.builder()
                .projectId(projectId)
                .tableInfo(table)
                .indexName(name)
                .indexType(notBlank(request.indexType()) ? request.indexType().trim().toUpperCase(Locale.ROOT) : null)
                .isUnique(unique)
                .isApproval(AuthUtil.isApprovalAvailable())
                .build();
        for (int i = 0; i < columns.size(); i++) {
            index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column(columns.get(i)).columnOrder(i + 1).build());
        }
        return PhysicalModelDto.Index.fromEntity(indexRepository.save(index));
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
                TableIndex index = indexRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalModelServiceImpl::notFound);
                rejectApproved(index.getIsApproval());
                indexRepository.delete(index);
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
            case INDEX -> indexRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalModelServiceImpl::notFound).setIsApproval(isApproval);
            case CONSTRAINT -> constraintRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalModelServiceImpl::notFound).setIsApproval(isApproval);
        }
    }

    // ------------------------------------------------------------------ 컬럼 변경 연동

    @Override
    @Transactional
    public void releaseColumns(Long projectId, Collection<ColumnInfo> columns) {
        if (columns.isEmpty()) return;
        Set<Long> columnIds = new HashSet<>(ids(columns));

        List<TableRelation> relations = relationRepository.findAllUsingColumns(columnIds);
        relations.stream().filter(r -> r.getRelationSource() != TableRelation.Source.REF_COLUMN).findFirst().ifPresent(r -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "관계 {0} 에서 사용 중인 컬럼입니다.", r.getRelationName());
        });
        indexRepository.findAllUsingColumns(columnIds).stream().findFirst().ifPresent(i -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "인덱스 {0} 에서 사용 중인 컬럼입니다.", i.getIndexName());
        });
        constraintRepository.findAllUsingColumns(columnIds).stream().findFirst().ifPresent(c -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "제약조건 {0} 에서 사용 중인 컬럼입니다.", c.getConstraintName());
        });
        relationRepository.deleteAll(relations); // 남은 것은 모두 REF_COLUMN (삭제 후 동기화가 다시 만든다)
    }

    @Override
    @Transactional
    public void syncReferenceRelations(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return;

        // 아직 반영되지 않은 변경을 먼저 내보내고(아래 조회가 DB 기준), 여기서 맞춘 테이블은 커밋 직전 대상에서 뺀다
        entityManager.flush();
        ReferenceSyncBoundary.synced(projectId, tableIds);

        Set<String> tableNames = tableInfoRepository.findAllById(tableIds).stream()
                .filter(t -> projectId.equals(t.getProjectId()))
                .map(t -> normalize(t.getTableName()))
                .collect(Collectors.toSet());

        // 대상 컬럼 : 이 테이블들의 컬럼(참조를 지웠을 수도 있음) + 이 테이블들을 참조하는 컬럼(대상 PK 가 바뀌었을 수 있음)
        Map<Long, ColumnInfo> candidates = new LinkedHashMap<>();
        columnInfoRepository.findAllByTableInfo_IdIn(tableIds).stream()
                .filter(c -> projectId.equals(c.getProjectId()))
                .forEach(c -> candidates.put(c.getId(), c));
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
                .filter(c -> notBlank(c.getRefTableName()))
                .map(c -> normalize(c.getRefTableName()))
                .collect(Collectors.toSet());
        Map<String, List<TableInfo>> targetsByName = refNames.isEmpty() ? Map.of()
                : tableInfoRepository.findAllByProjectIdAndTableNameIn(projectId, refNames).stream()
                    .collect(Collectors.groupingBy(TableInfo::getTableName));
        Set<Long> targetIds = targetsByName.values().stream().flatMap(List::stream).map(TableInfo::getId).collect(Collectors.toSet());
        Map<Long, List<ColumnInfo>> pkByTable = targetIds.isEmpty() ? Map.of()
                : columnInfoRepository.findAllByTableInfo_IdInAndIsPkTrue(targetIds).stream()
                    .collect(Collectors.groupingBy(c -> c.getTableInfo().getId()));

        for (ColumnInfo column : candidates.values()) {
            ColumnInfo targetPk = referencedPk(column, targetsByName, pkByTable);
            TableRelation relation = existing.get(column.getId());

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
     * 참조 테이블명 → 참조 PK 컬럼. DDL 생성기의 FK 조건과 같다 : 같은 프로젝트에 그 이름의 테이블이 정확히 1개,
     * 그 테이블의 PK 가 정확히 1개, 자기 PK 를 가리키지 않음. 아니면 null (관계를 만들지 않는다)
     */
    private static ColumnInfo referencedPk(ColumnInfo column, Map<String, List<TableInfo>> targetsByName, Map<Long, List<ColumnInfo>> pkByTable) {
        if (column.getTableInfo() == null || !notBlank(column.getRefTableName())) return null;
        List<TableInfo> targets = targetsByName.get(normalize(column.getRefTableName()));
        if (targets == null || targets.size() != 1) return null;
        List<ColumnInfo> pk = pkByTable.getOrDefault(targets.get(0).getId(), List.of());
        if (pk.size() != 1 || pk.get(0).getId().equals(column.getId())) return null;
        return pk.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public TableDdlGenerator.Extras ddlExtras(TableInfo table) {
        Long projectId = table.getProjectId();
        return new TableDdlGenerator.Extras(
                constraintRepository.findAllByProjectIdAndTableInfo_Id(projectId, table.getId()),
                indexRepository.findAllByProjectIdAndTableInfo_Id(projectId, table.getId()),
                relationRepository.findAllByProjectIdAndSourceTable_Id(projectId, table.getId()).stream()
                        .filter(r -> r.getRelationSource() != TableRelation.Source.REF_COLUMN && r.getRelationType() == TableRelation.Type.FK)
                        .toList());
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
        return columns.stream().map(ColumnInfo::getColumnName).collect(Collectors.joining("_"));
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
