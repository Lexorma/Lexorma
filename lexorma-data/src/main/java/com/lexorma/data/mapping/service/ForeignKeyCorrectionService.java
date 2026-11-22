package com.lexorma.data.mapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.logical.domain.entity.LogicalModel;
import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Code;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Issue;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Item;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Pair;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Plan;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Representation;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Request;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Result;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.StructureColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.physical.sync.ModelRowLocks;
import com.lexorma.data.table.ddl.DbIdentifierRules;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.MAX_RELATIONSHIPS;

/**
 * 기존 물리 테이블 누락 FK 보정 (M7-FK-01 — 확정 정책 M7-FK-D01~D08, docs/completed/physical-fk-correction-m7.md).
 * <ul>
 *   <li>대상 : 정합성 비교의 관계 진단({@link ModelStructureQualityJudge}, {@link ModelMappingQualityService#structureRows})이 <b>FK_MISSING</b> 인 논리 관계 중 사용자가 고른 것.
 *       진단을 다시 구현하지 않는다 — 기대 자식·부모 컬럼 쌍(짝 순서)을 진단 결과에서 그대로 쓴다. FK_MISSING 이 곧 보정 가능은 아니다 : 아래 검사를 따로 한다.</li>
 *   <li>검사 : 상속 FK(서브타입 → 직계 슈퍼타입)는 제외, 자기 참조가 아닌 단일 FK 는 보류(참조 테이블명 동기화가 관계를 항상 승인 상태로 만든다 — D08),
 *       승인된 자식 테이블·자식 컬럼은 막음(부모는 참조만 하므로 승인돼 있어도 된다), 참조 테이블명이 있는 자식 컬럼·같은 구성의 기존 관계·부모 PK·타입 호환·중복 컬럼
 *       (관계 저장 {@code buildRelation} 과 같은 기준), 같은 구성의 FK 를 기대하는 다른 논리 관계(만들면 FK_SHARED), FK 이름 충돌(같은 스키마의 FK·같은 요청 안).</li>
 *   <li>만들기 : 복합·자기 참조 = 미승인 사용자 관계({@link PhysicalModelService#createGeneratedForeignKey} — 논리 → 물리 생성과 같은 경로·검사, P2 규칙). 이름 = DDL FK 이름 규칙.</li>
 *   <li>적용 : 프로젝트 행 배타(맨 먼저) → 논리 모델 행 → 자식·부모 테이블 행(ID 순) → 자식·부모 컬럼 행(ID 순), 모두 NOWAIT ({@link ModelRowLocks}, 바쁘면 409) →
 *       다시 진단·계획해 planToken 비교(다르면 409) → 막는 사유가 있으면 400 → 직전 모델 버전 {@code FKFIX-<요청 ID>} → 관계 생성 → flush → 다시 진단해 고른 관계가 모두
 *       MATCH 인지 확인 (아니면 전체 롤백). 기존 행의 키는 바꾸지 않으므로 FOR UPDATE 강도 잠금은 쓰지 않는다 (관계 INSERT 의 KEY SHARE 는 스스로 잠근 행에만 걸린다).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ForeignKeyCorrectionService {

    static final String VERSION_PREFIX = "FKFIX-";
    /** 계획 해시 형식 (판정 규칙·정규화 형식이 바뀌면 올린다) */
    /** 2 : 보조 식별자 참조(참조 키 유형·부모 UNIQUE 판정)가 계획에 들어간다 (M7-AK-03 — 1 의 토큰은 새 계획과 맞지 않는다) */
    static final String TOKEN_VERSION = "fk-correction/2";

    private final ModelMappingQualityService qualityService;
    private final LogicalSubtypeService subtypeService;
    private final PhysicalModelService physicalModelService;
    private final PhysicalNameRecommendationService recommendation;
    private final ProjectRepository projectRepository;
    private final ProjectWriteGuard projectWriteGuard;
    private final ModelRowLocks rowLocks;
    private final ModelVersionService versionService;
    private final ModelChangeRecorder recorder;
    @PersistenceContext
    private EntityManager entityManager;

    /** 목록 표시용 판정 (조회 권한) : 계획 토큰 없이 관계별 보정 가능 여부만 */
    @Transactional(readOnly = true)
    public List<Item> status(AccountDto account, Long modelId, List<Long> relationshipIds) {
        return compute(account.getProjectId(), modelId, normalize(relationshipIds)).plan().items();
    }

    /** 미리보기 : 아무것도 바꾸지 않는다 (모델 버전도 만들지 않는다) */
    @Transactional(readOnly = true)
    public Plan preview(AccountDto account, Request request) {
        List<Long> ids = validate(request, false);
        return compute(account.getProjectId(), request.modelId(), ids).plan();
    }

    @Transactional
    public Result apply(AccountDto account, Request request) {
        List<Long> ids = validate(request, true);
        Long projectId = account.getProjectId();
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        projectWriteGuard.lock(projectId);
        // 잠글 행 : 프로젝트를 배타로 잡았으므로 그 사이 커밋되는 모델 변경은 없다 — 지금 계획으로 정한다 (커밋 안 된 변경은 아래 NOWAIT 가 잡는다)
        Computation before = compute(projectId, request.modelId(), ids);
        TreeSet<Long> tables = new TreeSet<>();
        TreeSet<Long> columns = new TreeSet<>();
        for (Item item : before.plan().items()) {
            if (item.childTableId() != null) tables.add(item.childTableId());
            if (item.parentTableId() != null) tables.add(item.parentTableId());
            item.pairs().forEach(p -> {
                if (p.childColumnId() != null) columns.add(p.childColumnId());
                if (p.parentColumnId() != null) columns.add(p.parentColumnId());
            });
        }
        ModelRowLocks.noWait(() -> entityManager.find(LogicalModel.class, request.modelId(), LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0)));
        rowLocks.tablesNoWait(tables);
        rowLocks.columnsNoWait(columns);

        Computation computed = compute(projectId, request.modelId(), ids);
        if (!computed.plan().planToken().equals(request.planToken())) {
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("FK 보정 계획"));
        }
        Item blocked = computed.plan().items().stream().filter(i -> !i.correctable()).findFirst().orElse(null);
        if (blocked != null) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "보정할 수 없는 관계가 있습니다. 미리보기의 사유를 확인하고 그 관계를 뺀 뒤 다시 미리보기해주세요. ({0} : {1})",
                    blocked.logicalValue(), blocked.blockers().get(0).reason());
        }

        String requestId = UUID.randomUUID().toString();
        String versionName = VERSION_PREFIX + requestId;
        ModelVersionDto.Summary version = versionService.createBeforeModelChange(projectId, versionName,
                Messages.get("누락 FK 보정 직전 상태 (논리 관계 {0}개, 요청 {1})", ids.size(), requestId), account.getUsername());
        recorder.markChangeGroup(versionName);

        List<Long> created = new ArrayList<>();
        for (Item item : computed.plan().items()) {
            List<ColumnInfo> childColumns = new ArrayList<>();
            List<ColumnInfo> parentColumns = new ArrayList<>();
            for (Pair p : item.pairs()) {
                childColumns.add(computed.columns().get(p.childColumnId()));
                parentColumns.add(computed.columns().get(p.parentColumnId()));
            }
            TableInfo child = childColumns.get(0).getTableInfo();
            TableInfo parent = parentColumns.get(0).getTableInfo();
            created.add(physicalModelService.createGeneratedForeignKey(projectId, child, parent, childColumns, parentColumns, item.relationName()).getId());
        }
        entityManager.flush();
        // 최종 확인 : 같은 진단으로 고른 관계가 모두 일치(MATCH)여야 한다 — 아니면 전체 롤백 (버전·이력도 남지 않는다)
        Map<Long, Row> after = relationshipRows(projectId, request.modelId(), computed.modelName());
        for (Long id : ids) {
            Row row = after.get(id);
            if (row == null || row.status() != CheckStatus.MATCH) {
                throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "보정한 FK 가 정합성 비교에서 일치하지 않습니다. ({0} : {1})",
                        row == null ? String.valueOf(id) : row.logicalValue(), row == null ? "-" : row.structure().code());
            }
        }
        return new Result(ids.size(), created.size(), created, version.id(), version.versionName());
    }

    // ------------------------------------------------------------------ 계획

    private record Computation(Plan plan, Map<Long, ColumnInfo> columns, String modelName) {
    }

    private Map<Long, Row> relationshipRows(Long projectId, Long modelId, String modelName) {
        return rowsOf(qualityService.structureRows(projectId, modelId, modelName), CheckType.RELATIONSHIP);
    }

    private static Map<Long, Row> rowsOf(List<Row> structure, CheckType type) {
        Map<Long, Row> rows = new LinkedHashMap<>();
        structure.stream().filter(r -> r.type() == type && r.structure() != null).forEach(r -> rows.put(r.structure().logicalId(), r));
        return rows;
    }

    private Computation compute(Long projectId, Long modelId, List<Long> ids) {
        String modelName = entityManager.createQuery("select m.modelName from LogicalModel m where m.id = :id and m.projectId = :projectId", String.class)
                .setParameter("id", modelId).setParameter("projectId", projectId).getResultStream().findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("논리 모델")));
        List<Row> structure = qualityService.structureRows(projectId, modelId, modelName);
        Map<Long, Row> rows = rowsOf(structure, CheckType.RELATIONSHIP);
        Map<Long, Row> alternateKeys = rowsOf(structure, CheckType.ALTERNATE_KEY); // 보조 식별자 ID → 그 UNIQUE 진단 (M7-AK-D10)
        for (Long id : ids) {
            if (!rows.containsKey(id)) {
                throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "이 논리 모델의 관계가 아닙니다. (관계 ID {0})", id);
            }
        }
        LogicalHierarchy hierarchy = subtypeService.hierarchy(modelId);
        DbType db = recommendation.dbType(projectId);

        // 기대 구성 (FK_MISSING 이고 짝 컬럼이 모두 정해진 관계) : 모델 전체 — 다른 관계와 같은 구성인지(공유)도 본다
        Map<Long, List<Long[]>> expected = new HashMap<>();
        Map<String, List<Long>> byStructure = new HashMap<>();
        for (Row row : rows.values()) {
            if (!"FK_MISSING".equals(row.structure().code())) continue;
            List<Long[]> pairs = new ArrayList<>();
            for (StructureColumn c : row.structure().columns()) pairs.add(new Long[]{c.expectedColumnId(), c.expectedParentColumnId()});
            if (pairs.isEmpty() || pairs.stream().anyMatch(p -> p[0] == null || p[1] == null)) continue;
            expected.put(row.structure().logicalId(), pairs);
            byStructure.computeIfAbsent(structureKey(row, pairs), k -> new ArrayList<>()).add(row.structure().logicalId());
        }
        // 고른 관계의 컬럼·테이블 (한 번에)
        Set<Long> columnIds = new HashSet<>();
        Set<Long> childTableIds = new HashSet<>();
        for (Long id : ids) {
            List<Long[]> pairs = expected.get(id);
            if (pairs == null) continue;
            pairs.forEach(p -> { columnIds.add(p[0]); columnIds.add(p[1]); });
            childTableIds.add(rows.get(id).tableId());
        }
        Map<Long, ColumnInfo> columns = new HashMap<>();
        if (!columnIds.isEmpty()) {
            entityManager.createQuery("select c from ColumnInfo c join fetch c.tableInfo t join fetch t.schema where c.id in :ids and c.projectId = :projectId", ColumnInfo.class)
                    .setParameter("ids", columnIds).setParameter("projectId", projectId).getResultList().forEach(c -> columns.put(c.getId(), c));
        }
        // 자식 테이블의 기존 관계 (같은 구성 중복 — 논리 관계 포함) · 자식 스키마의 FK 이름 (DDL 제약조건 이름 충돌)
        Map<Long, List<TableRelation>> relationsByChild = new HashMap<>();
        Set<String> usedNames = new HashSet<>();
        if (!childTableIds.isEmpty()) {
            entityManager.createQuery("select distinct r from TableRelation r left join fetch r.columns where r.projectId = :projectId and r.sourceTable.id in :ids", TableRelation.class)
                    .setParameter("projectId", projectId).setParameter("ids", childTableIds).getResultList()
                    .forEach(r -> relationsByChild.computeIfAbsent(r.getSourceTable().getId(), k -> new ArrayList<>()).add(r));
            Set<Long> schemas = columns.values().stream().map(c -> c.getTableInfo().getSchema().getId()).collect(Collectors.toSet());
            entityManager.createQuery("select r.sourceTable.schema.id, upper(r.relationName) from TableRelation r where r.projectId = :projectId and r.relationType = :fk"
                            + " and r.sourceTable.schema.id in :schemas and r.relationName is not null", Object[].class)
                    .setParameter("projectId", projectId).setParameter("fk", TableRelation.Type.FK).setParameter("schemas", schemas).getResultList()
                    .forEach(o -> usedNames.add(o[0] + ":" + o[1]));
        }

        List<Item> items = new ArrayList<>();
        Map<String, List<Long>> plannedNames = new HashMap<>();
        for (Long id : ids) {
            Row row = rows.get(id);
            Item item = item(row, expected.get(id), columns, relationsByChild, usedNames, byStructure, hierarchy, db, rows, alternateKeys);
            items.add(item);
            if (item.relationName() != null && item.childTableId() != null) {
                String schema = columns.get(item.pairs().get(0).childColumnId()).getTableInfo().getSchema().getId().toString();
                plannedNames.computeIfAbsent(schema + ":" + item.relationName().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(id);
            }
        }
        // 같은 요청 안에서 만들 FK 이름이 겹친다 (같은 스키마)
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.relationName() == null || item.childTableId() == null || item.pairs().isEmpty()) continue;
            String schema = columns.get(item.pairs().get(0).childColumnId()).getTableInfo().getSchema().getId().toString();
            List<Long> same = plannedNames.get(schema + ":" + item.relationName().toUpperCase(Locale.ROOT));
            if (same != null && same.size() > 1 && item.blockers().stream().noneMatch(b -> b.code() == Code.NAME_CONFLICT)) {
                List<Issue> blockers = new ArrayList<>(item.blockers());
                blockers.add(new Issue(Code.NAME_CONFLICT, Messages.get("함께 고른 다른 관계도 같은 이름의 FK {0} 을(를) 만듭니다. 하나만 골라주세요.", item.relationName())));
                items.set(i, withBlockers(item, blockers));
            }
        }
        int correctable = (int) items.stream().filter(Item::correctable).count();
        String token = token(projectId, modelId, db, items);
        return new Computation(new Plan(modelId, items, items.size(), correctable, items.size() - correctable, correctable == items.size() && !items.isEmpty(), token),
                columns, modelName);
    }

    private Item item(Row row, List<Long[]> pairs, Map<Long, ColumnInfo> columns, Map<Long, List<TableRelation>> relationsByChild, Set<String> usedNames,
                      Map<String, List<Long>> byStructure, LogicalHierarchy hierarchy, DbType db, Map<Long, Row> rows, Map<Long, Row> alternateKeys) {
        var s = row.structure();
        boolean alternate = "ALTERNATE".equals(s.referenceKey());
        Long id = s.logicalId();
        List<Issue> blockers = new ArrayList<>();
        String code = s.code();
        boolean self = Objects.equals(row.tableId(), s.parentTableId()) && row.tableId() != null;
        if (!"FK_MISSING".equals(code)) {
            blockers.add(new Issue(Code.NOT_MISSING, Messages.get("진단 결과가 FK 누락이 아닙니다 ({0}). 기존 FK 는 고치거나 지우지 않습니다.", code)));
            return new Item(id, s.logicalName(), row.logicalValue(), s.parentEntityId(), s.parentEntityName(), row.entityId(), row.entityName(),
                    s.parentTableId(), null, s.parentTableName(), row.tableId(), row.schemaName(), row.tableName(), code, null, self, null, false, List.of(), false, blockers,
                    s.referenceKey(), s.referenceKeyName());
        }
        List<ColumnInfo> childColumns = new ArrayList<>();
        List<ColumnInfo> parentColumns = new ArrayList<>();
        List<Pair> out = new ArrayList<>();
        if (pairs == null) {
            blockers.add(new Issue(Code.PARENT_KEY_INVALID, Messages.get("관계 속성 짝의 컬럼을 정할 수 없습니다.")));
        } else {
            for (int i = 0; i < pairs.size(); i++) {
                ColumnInfo child = columns.get(pairs.get(i)[0]);
                ColumnInfo parent = columns.get(pairs.get(i)[1]);
                childColumns.add(child);
                parentColumns.add(parent);
                out.add(new Pair(i + 1, pairs.get(i)[0], child == null ? null : child.getColumnName(), child == null ? null : TableDdlGenerator.mappedType(db, child),
                        pairs.get(i)[1], parent == null ? null : parent.getColumnName(), parent == null ? null : TableDdlGenerator.mappedType(db, parent)));
            }
        }
        boolean loaded = pairs != null && childColumns.stream().allMatch(Objects::nonNull) && parentColumns.stream().allMatch(Objects::nonNull);
        TableInfo childTable = loaded ? childColumns.get(0).getTableInfo() : null;
        TableInfo parentTable = loaded ? parentColumns.get(0).getTableInfo() : null;
        boolean single = out.size() == 1;
        // 보조 식별자 참조는 단일도 사용자 관계 (M7-AK-D11) — 일반 단일 PK 참조만 참조 테이블명(보류 대상)
        Representation representation = single && !self && !alternate ? Representation.REF_COLUMN : Representation.USER_RELATION;
        String name = loaded ? DbIdentifierRules.foreignKeyName(childTable.getTableName(), childColumns.get(0).getColumnName()) : null;

        // 상속 FK : 서브타입 → 직계 슈퍼타입 (상속 구조 진단이 본다)
        if (Objects.equals(hierarchy.parent(row.entityId()), s.parentEntityId())) {
            blockers.add(new Issue(Code.INHERITANCE_LINK, Messages.get("서브타입과 직계 슈퍼타입 사이의 관계입니다. 상속 FK 는 상속 구조 진단에서 다루며 이 기능으로 만들지 않습니다.")));
        }
        if (representation == Representation.REF_COLUMN) {
            blockers.add(new Issue(Code.SINGLE_FK_HELD, Messages.get("자기 참조가 아닌 단일 FK 는 기존 규칙상 자식 컬럼의 참조 테이블명으로 만들며, 그 관계는 동기화에서 항상 승인 상태로 만들어져 승인 절차를 거치지 않습니다. 승인 정책과 충돌하여 이 유형의 보정은 보류합니다 (컬럼 관리에서 참조 테이블을 직접 지정할 수 있습니다).")));
        }
        if (!loaded) {
            if (pairs != null) blockers.add(new Issue(Code.PARENT_KEY_INVALID, Messages.get("관계 속성 짝의 컬럼을 찾을 수 없습니다.")));
        } else {
            if (Boolean.TRUE.equals(childTable.getIsApproval())) {
                blockers.add(new Issue(Code.CHILD_TABLE_APPROVED, Messages.get("자식 테이블 {0} 이(가) 승인되어 있어 FK 를 추가할 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.", childTable.getTableName())));
            }
            List<String> approvedColumns = childColumns.stream().filter(c -> Boolean.TRUE.equals(c.getIsApproval())).map(ColumnInfo::getColumnName).toList();
            if (!approvedColumns.isEmpty()) {
                blockers.add(new Issue(Code.CHILD_COLUMN_APPROVED, Messages.get("자식 컬럼 {0} 이(가) 승인되어 있어 FK 에 쓸 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.", String.join(", ", approvedColumns))));
            }
            childColumns.stream().filter(c -> c.getRefTableName() != null && !c.getRefTableName().isBlank()).findFirst().ifPresent(c -> blockers.add(new Issue(Code.REF_COLUMN_CONFLICT,
                    Messages.get("{0} 은(는) 참조 테이블({1})이 지정된 컬럼입니다. 그 값으로 관계가 만들어지므로 FK 를 따로 만들지 않습니다.", c.getColumnName(), c.getRefTableName().trim()))));
            Set<Long> childIds = new LinkedHashSet<>(childColumns.stream().map(ColumnInfo::getId).toList());
            Set<Long> parentIds = new LinkedHashSet<>(parentColumns.stream().map(ColumnInfo::getId).toList());
            if (childIds.size() != childColumns.size() || parentIds.size() != parentColumns.size()) {
                blockers.add(new Issue(Code.DUPLICATE_COLUMN, Messages.get("관계 속성 짝에 같은 컬럼이 두 번 쓰였습니다.")));
            }
            if (alternate) {
                // 부모 UNIQUE (M7-AK-D10) : 참조하는 보조 식별자의 UNIQUE 진단이 일치(구성·순서)이고, 그 순서 = 만들 FK 컬럼 순서. 부모 UNIQUE 는 이 요청에서 만들지 않는다 (먼저 UNIQUE 보정)
                Row key = s.referenceKeyId() == null ? null : alternateKeys.get(s.referenceKeyId());
                List<Long> uniqueOrder = key == null ? List.of() : key.structure().columns().stream().map(StructureColumn::expectedColumnId).toList();
                if (key == null || key.status() != CheckStatus.MATCH) {
                    blockers.add(new Issue(Code.PARENT_UNIQUE_MISSING, Messages.get("부모 보조 식별자 {0} 에 대응하는 UNIQUE 가 부모 테이블 {1} 에 없거나 일치하지 않습니다. 정합성 비교 [보조 식별자·UNIQUE]에서 UNIQUE 를 먼저 보정해주세요.",
                            Objects.requireNonNullElse(s.referenceKeyName(), "-"), parentTable.getTableName())));
                } else if (!uniqueOrder.equals(parentColumns.stream().map(ColumnInfo::getId).toList())) {
                    blockers.add(new Issue(Code.PARENT_UNIQUE_ORDER, Messages.get("부모 UNIQUE 의 컬럼 순서가 관계 속성 짝 순서와 달라 FK 를 만들지 않습니다 (FK 컬럼 순서 = UNIQUE 순서). 관계 속성 짝의 순서를 보조 식별자 순서로 맞춰주세요.")));
                }
            } else if (parentColumns.stream().anyMatch(c -> !Boolean.TRUE.equals(c.getIsPk()))) {
                blockers.add(new Issue(Code.PARENT_KEY_INVALID, Messages.get("부모 컬럼이 부모 테이블 {0} 의 PK 가 아닙니다.", parentTable.getTableName())));
            }
            for (int i = 0; i < childColumns.size(); i++) {
                ColumnInfo c = childColumns.get(i);
                ColumnInfo p = parentColumns.get(i);
                if (!TableDdlGenerator.isReferenceTypeCompatible(db, c, p)) {
                    blockers.add(new Issue(Code.TYPE_INCOMPATIBLE, Messages.get("{0} 과(와) {1} 의 데이터 타입이 {2} 에서 FK 로 연결할 수 있는 조합이 아닙니다. ({3} ↔ {4})",
                            childTable.getTableName() + "." + c.getColumnName(), parentTable.getTableName() + "." + p.getColumnName(), db.getLabel(),
                            TableDdlGenerator.mappedType(db, c), TableDdlGenerator.mappedType(db, p))));
                }
            }
            List<Long> ordered = childColumns.stream().map(ColumnInfo::getId).toList();
            relationsByChild.getOrDefault(childTable.getId(), List.of()).stream()
                    .filter(r -> r.getTargetTable().getId().equals(parentTable.getId())
                            && r.getColumns().stream().sorted(java.util.Comparator.comparing(TableRelationColumn::getColumnOrder)).map(c -> c.getSourceColumn().getId()).toList().equals(ordered))
                    .findFirst().ifPresent(r -> blockers.add(new Issue(Code.RELATION_EXISTS,
                            Messages.get("같은 컬럼 구성으로 {0} 을(를) 가리키는 관계 {1} 이(가) 이미 있습니다.", parentTable.getTableName(), Objects.requireNonNullElse(r.getRelationName(), "-")))));
            List<Long> same = byStructure.getOrDefault(structureKey(row, pairs), List.of()).stream().filter(other -> !other.equals(id)).toList();
            if (!same.isEmpty()) {
                blockers.add(new Issue(Code.SHARED_STRUCTURE, Messages.get("같은 FK 구성을 기대하는 다른 논리 관계가 있습니다 ({0}). 만들면 하나의 FK 를 여러 관계가 함께 쓰게 되어 자동으로 고르지 않습니다.",
                        same.stream().map(o -> rows.get(o).logicalValue()).collect(Collectors.joining(", ")))));
            }
            if (name != null && usedNames.contains(childTable.getSchema().getId() + ":" + name.toUpperCase(Locale.ROOT))) {
                blockers.add(new Issue(Code.NAME_CONFLICT, Messages.get("같은 스키마에 이름이 {0} 인 FK 가 이미 있습니다.", name)));
            }
        }
        return new Item(id, s.logicalName(), row.logicalValue(), s.parentEntityId(), s.parentEntityName(), row.entityId(), row.entityName(),
                s.parentTableId(), parentTable == null ? null : parentTable.getSchema().getSchemaName(), s.parentTableName(),
                row.tableId(), row.schemaName(), row.tableName(), code, representation, self, name, false, out, blockers.isEmpty(), blockers,
                s.referenceKey(), s.referenceKeyName());
    }

    private static Item withBlockers(Item i, List<Issue> blockers) {
        return new Item(i.relationshipId(), i.relationshipName(), i.logicalValue(), i.parentEntityId(), i.parentEntityName(), i.childEntityId(), i.childEntityName(),
                i.parentTableId(), i.parentSchemaName(), i.parentTableName(), i.childTableId(), i.childSchemaName(), i.childTableName(), i.diagnosisCode(), i.representation(),
                i.selfReference(), i.relationName(), i.approved(), i.pairs(), blockers.isEmpty(), blockers, i.referenceKey(), i.referenceKeyName());
    }

    private static String structureKey(Row row, List<Long[]> pairs) {
        return row.tableId() + ">" + row.structure().parentTableId() + ":" + pairs.stream().map(p -> p[0] + "-" + p[1]).collect(Collectors.joining(","));
    }

    /** 계획 해시 : 결과에 영향을 주는 값만 (관계·구성·컬럼 이름과 타입·표현·이름·사유 코드·승인 상태는 사유에 들어 있다) */
    private static String token(Long projectId, Long modelId, DbType db, List<Item> items) {
        StringBuilder b = new StringBuilder(TOKEN_VERSION).append('|').append(projectId).append('|').append(modelId).append('|').append(db);
        items.stream().sorted(java.util.Comparator.comparing(Item::relationshipId)).forEach(i -> {
            b.append("\n").append(i.relationshipId()).append('|').append(i.diagnosisCode()).append('|').append(i.childTableId()).append('|').append(i.childTableName())
                    .append('|').append(i.parentTableId()).append('|').append(i.parentTableName()).append('|').append(i.representation()).append('|').append(i.relationName())
                    .append('|').append(i.referenceKey()).append('|').append(i.referenceKeyName());
            i.pairs().forEach(p -> b.append('|').append(p.childColumnId()).append(':').append(p.childColumnName()).append(':').append(p.childDataType())
                    .append('>').append(p.parentColumnId()).append(':').append(p.parentColumnName()).append(':').append(p.parentDataType()));
            i.blockers().forEach(x -> b.append("|!").append(x.code()));
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 요청 검사

    private static List<Long> validate(Request request, boolean apply) {
        if (request == null || request.modelId() == null) throw invalid("논리 모델을 고르세요.");
        List<Long> ids = normalize(request.relationshipIds());
        if (apply && (request.planToken() == null || request.planToken().isBlank())) throw invalid("미리보기를 먼저 확인해주세요.");
        return ids;
    }

    private static List<Long> normalize(List<Long> relationshipIds) {
        if (relationshipIds == null || relationshipIds.isEmpty()) throw invalid("보정할 논리 관계를 고르세요.");
        if (relationshipIds.size() > MAX_RELATIONSHIPS) throw invalid("한 번에 {0}개까지 보정할 수 있습니다. (요청 {1}개)", MAX_RELATIONSHIPS, relationshipIds.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : relationshipIds) {
            if (id == null) throw invalid("논리 관계 ID 가 비어 있습니다.");
            if (!seen.add(id)) throw invalid("같은 논리 관계가 두 번 들어 있습니다. (관계 ID {0})", id);
        }
        return new ArrayList<>(seen);
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, message, args);
    }
}
