package com.lexorma.data.physical.diagnostic;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 물리 모델 구조 진단 (DEC-09·DEC-12). DDL 생성({@link TableDdlGenerator})이 FK 를 만들지 않는 조건을 그대로 진단으로 보인다.
 * <ul>
 *   <li>무효 FK (오류) : 참조 테이블명(REF_COLUMN)은 DDL 과 같은 해석({@link TableDesignService#referencedKeys}), 사용자 정의 관계는
 *       {@link TableDdlGenerator#referencesKey}. 사후에 무효가 된 FK 도 모델 수정을 막지 않는다 — 진단·DDL 경고로만 알린다. 자기 자신 PK 참조는 DDL 이 건너뛰지만 오류는 아니다</li>
 *   <li>중복 인덱스 (경고) : 같은 정의(키·정렬·INCLUDE·조건), PK 와 같은 순서의 일반 인덱스(DEC-12 허용), 다른 인덱스·PK 의 앞부분과 같은 일반 인덱스.
 *       조건·INCLUDE·표현식이 있는 인덱스는 앞부분 비교에서 뺀다 (용도가 다를 수 있다)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ModelStructureDiagnosticService {

    private final TableInfoRepository tables;
    private final TableIndexRepository indexes;
    private final TableDesignService tableDesignService;
    private final PhysicalModelService physicalModelService;

    @Transactional(readOnly = true)
    public ModelStructureDiagnosticDto.Result diagnose(AccountDto account, Long schemaId) {
        Long projectId = account.getProjectId();
        List<TableInfo> all = tables.findAllByProjectId(projectId, Pageable.unpaged()).getContent().stream()
                .filter(t -> schemaId == null || t.getSchema().getId().equals(schemaId))
                .sorted(Comparator.comparing((TableInfo t) -> t.getSchema().getSchemaName()).thenComparing(TableInfo::getTableName))
                .toList();
        Map<Long, List<TableIndex>> indexesByTable = all.isEmpty() ? Map.of()
                : indexes.findAllByProjectIdAndTableInfo_IdIn(projectId, all.stream().map(TableInfo::getId).toList()).stream()
                .collect(Collectors.groupingBy(i -> i.getTableInfo().getId()));
        List<ModelStructureDiagnosticDto.Issue> issues = new ArrayList<>();
        for (TableInfo table : all) {
            foreignKeys(projectId, table, issues);
            indexes(table, indexesByTable.getOrDefault(table.getId(), List.of()), issues);
        }
        long errors = issues.stream().filter(i -> i.severity() == ModelStructureDiagnosticDto.Severity.ERROR).count();
        return new ModelStructureDiagnosticDto.Result(new ModelStructureDiagnosticDto.Summary(all.size(), errors, issues.size() - errors), issues);
    }

    private void foreignKeys(Long projectId, TableInfo table, List<ModelStructureDiagnosticDto.Issue> issues) {
        TableDesignService.ReferencedKeys keys = tableDesignService.referencedKeys(projectId, table);
        for (ColumnInfo column : table.getColumns()) {
            if (column.getRefTableName() == null || column.getRefTableName().isBlank()) continue;
            String ref = column.getRefTableName().trim().toUpperCase(Locale.ROOT);
            List<ColumnInfo> pk = keys.pkColumns().get(ref);
            ModelStructureDiagnosticDto.IssueType type;
            if (pk == null) type = keys.ambiguous().contains(ref) ? ModelStructureDiagnosticDto.IssueType.FK_TARGET_AMBIGUOUS : ModelStructureDiagnosticDto.IssueType.FK_TARGET_NOT_FOUND;
            else if (pk.isEmpty()) type = ModelStructureDiagnosticDto.IssueType.FK_TARGET_NO_PK;
            else if (pk.size() > 1) type = ModelStructureDiagnosticDto.IssueType.FK_TARGET_COMPOSITE_PK;
            else continue;
            issues.add(issue(type, ModelStructureDiagnosticDto.Severity.ERROR, table, "C" + column.getId(), column.getColumnName(), column.getRefTableName().trim(), column.getIsApproval()));
        }
        TableDdlGenerator.Extras extras = physicalModelService.ddlExtras(table);
        for (TableRelation relation : extras.relations()) {
            TableInfo target = relation.getTargetTable();
            List<ColumnInfo> targets = relation.getColumns().stream().map(c -> c.getTargetColumn()).toList();
            if (TableDdlGenerator.referencesKey(target, targets, extras.targetUniqueKeys().getOrDefault(target.getId(), List.of()))) continue;
            issues.add(issue(ModelStructureDiagnosticDto.IssueType.FK_RELATION_NOT_KEY, ModelStructureDiagnosticDto.Severity.ERROR, table, "R" + relation.getId(),
                    relation.getRelationName(), target.getTableName() + " (" + targets.stream().map(ColumnInfo::getColumnName).collect(Collectors.joining(", ")) + ")", null));
        }
    }

    private void indexes(TableInfo table, List<TableIndex> list, List<ModelStructureDiagnosticDto.Issue> issues) {
        List<TableIndex> sorted = list.stream().sorted(Comparator.comparing(TableIndex::getId)).toList();
        List<Long> pk = table.getColumns().stream().filter(c -> Boolean.TRUE.equals(c.getIsPk()))
                .sorted(Comparator.comparing(ColumnInfo::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))).map(ColumnInfo::getId).toList();
        Set<Long> reported = new java.util.HashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            TableIndex index = sorted.get(i);
            // 같은 정의 : 뒤에 등록된 쪽에 알린다
            for (int j = 0; j < i; j++) {
                if (signature(sorted.get(j)).equals(signature(index)) && reported.add(index.getId())) {
                    issues.add(indexIssue(ModelStructureDiagnosticDto.IssueType.INDEX_SAME_DEFINITION, table, index, sorted.get(j).getIndexName()));
                }
            }
            if (reported.contains(index.getId()) || Boolean.TRUE.equals(index.getIsUnique()) || plainKeys(index) == null) continue;
            List<Long> keys = plainKeys(index);
            if (!pk.isEmpty() && keys.equals(pk)) {
                issues.add(indexIssue(ModelStructureDiagnosticDto.IssueType.INDEX_SAME_AS_PK, table, index, "PK"));
                continue;
            }
            String longer = isPrefix(keys, pk) ? "PK" : sorted.stream().filter(o -> o != index).filter(o -> isPrefix(keys, plainKeys(o))).map(TableIndex::getIndexName).findFirst().orElse(null);
            if (longer != null) issues.add(indexIssue(ModelStructureDiagnosticDto.IssueType.INDEX_PREFIX_OF_OTHER, table, index, longer));
        }
    }

    /** 앞부분 비교에 쓰는 키 (컬럼 ID 순서) : 오름차순 컬럼 키만이고 조건·INCLUDE 가 없을 때만, 아니면 null */
    private static List<Long> plainKeys(TableIndex index) {
        if (index.getCondition() != null || !index.includeMembers().isEmpty()) return null;
        List<TableIndexColumn> keys = index.keyMembers();
        if (keys.stream().anyMatch(c -> c.getColumn() == null || (c.getSort() != null && !c.getSort().equalsIgnoreCase("ASC")))) return null;
        return keys.stream().map(c -> c.getColumn().getId()).toList();
    }

    private static boolean isPrefix(List<Long> shorter, List<Long> longer) {
        return longer != null && shorter.size() < longer.size() && longer.subList(0, shorter.size()).equals(shorter);
    }

    /** 등록 검사({@code PhysicalModelServiceImpl.signature})와 같은 기준 : 키(컬럼 ID 또는 표현식, 정렬) 순서·INCLUDE·조건 */
    private static String signature(TableIndex index) {
        return index.keyMembers().stream().map(c -> (c.getColumn() != null ? "C" + c.getColumn().getId() : "E" + c.getExpression()) + "/" + c.getSort())
                .collect(Collectors.joining(",")) + "|" + index.includeMembers().stream().map(c -> c.getColumn().getId()).toList() + "|" + index.getCondition();
    }

    private static ModelStructureDiagnosticDto.Issue indexIssue(ModelStructureDiagnosticDto.IssueType type, TableInfo table, TableIndex index, String related) {
        return issue(type, ModelStructureDiagnosticDto.Severity.WARNING, table, "I" + index.getId(), index.getIndexName(), related, index.getIsApproval());
    }

    private static ModelStructureDiagnosticDto.Issue issue(ModelStructureDiagnosticDto.IssueType type, ModelStructureDiagnosticDto.Severity severity, TableInfo table,
                                                           String key, String objectName, String related, Boolean isApproval) {
        return new ModelStructureDiagnosticDto.Issue(type + ":" + key, type, severity, table.getSchema().getId(), table.getSchema().getSchemaName(),
                table.getId(), table.getTableName(), objectName, related, isApproval);
    }
}
