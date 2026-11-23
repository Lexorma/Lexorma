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
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Code;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Issue;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Item;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Pair;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Plan;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Request;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Result;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.MAX_ENTITIES;
import static com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.REPRESENTATION;

/**
 * 기존 서브타입 누락 상속 FK 보정 (M7-INH-03 — 확정 D-INH-06~10, docs/completed/inheritance-reference-key-m7.md 5장).
 * <ul>
 *   <li>판정 : 상속 구조 진단과 같은 {@link SubtypePlanContext#inspect} 결과를 그대로 쓴다 (규칙을 다시 구현하지 않는다). 보정할 수 있는 것은 상속 PK 매핑·자식 PK·NOT NULL·
 *       직계 부모 키·상위 경로·타입이 모두 맞고 부모 FK 상세가 {@link SubtypePlanContext#FK_MISSING}(상속 FK 후보가 전혀 없음)인 서브타입뿐이다 — 잘못된 상속 FK(구성·순서·대상)는
 *       고치지 않고 막는다. 상위 경로의 상속 FK 가 없으면 {@link Code#ANCESTOR_FK_PENDING} (위에서부터 한 단계씩 — 한 요청에서 연쇄 보정하지 않는다, D-INH-09)</li>
 *   <li>만들기 : 직계 슈퍼타입 테이블(루트 주 식별자 순서의 그 테이블 키 컬럼)을 자식의 상속 PK 컬럼으로 참조하는 FK — 단일·복합 모두 미승인 USER 관계
 *       ({@link PhysicalModelService#createGeneratedForeignKey}, D-INH-07). 이름 = DDL FK 이름 규칙. 승인된 자식 테이블·상속 PK 컬럼은 막는다 (관리자도 — D-INH-08), 부모 승인은 막지 않는다</li>
 *   <li>적용 : 프로젝트 행 배타(맨 먼저) → 논리 모델 행 → 자식·부모 테이블 행(ID 순) → 자식·부모 키 컬럼 행(ID 순), 모두 NOWAIT ({@link ModelRowLocks}, 바쁘면 409) →
 *       다시 판정해 planToken 비교(다르면 409) → 막는 사유가 있으면 400 → 직전 모델 버전 {@code INHFIX-<요청 ID>} → 관계 생성 → flush → 다시 판정해 고른 서브타입이 모두
 *       MATCH 인지 확인 (아니면 전체 롤백). 기존 행의 키는 바꾸지 않으므로 FOR UPDATE 강도 잠금은 쓰지 않는다 (일반 FK 보정과 같은 잠금 — D-INH-09)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class InheritanceForeignKeyCorrectionService {

    static final String VERSION_PREFIX = "INHFIX-";
    /** 계획 해시 형식 (판정 규칙·정규화 형식이 바뀌면 올린다) */
    static final String TOKEN_VERSION = "inheritance-fk-correction/1";

    private final LogicalSubtypeService subtypeService;
    private final SubtypePlanContexts subtypeContexts;
    private final PhysicalModelService physicalModelService;
    private final PhysicalNameRecommendationService recommendation;
    private final ProjectRepository projectRepository;
    private final ProjectWriteGuard projectWriteGuard;
    private final ModelRowLocks rowLocks;
    private final ModelVersionService versionService;
    private final ModelChangeRecorder recorder;
    private final com.lexorma.data.mapping.repository.SubtypeGroupConversionRepository conversionRepository;
    @PersistenceContext
    private EntityManager entityManager;

    /** 목록 표시용 판정 (조회 권한) : 계획 토큰 없이 서브타입별 보정 가능 여부만 */
    @Transactional(readOnly = true)
    public List<Item> status(AccountDto account, Long modelId, List<Long> entityIds) {
        return compute(account.getProjectId(), modelId, normalize(entityIds)).plan().items();
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
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("상속 FK 보정 계획"));
        }
        Item blocked = computed.plan().items().stream().filter(i -> !i.correctable()).findFirst().orElse(null);
        if (blocked != null) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "보정할 수 없는 서브타입이 있습니다. 미리보기의 사유를 확인하고 그 서브타입을 뺀 뒤 다시 미리보기해주세요. ({0} : {1})",
                    blocked.subtypeEntityName(), blocked.blockers().get(0).reason());
        }

        String requestId = UUID.randomUUID().toString();
        String versionName = VERSION_PREFIX + requestId;
        ModelVersionDto.Summary version = versionService.createBeforeModelChange(projectId, versionName,
                Messages.get("누락 상속 FK 보정 직전 상태 (서브타입 {0}개, 요청 {1})", ids.size(), requestId), account.getUsername());
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
        // 최종 확인 : 같은 판정으로 고른 서브타입이 모두 일치(MATCH)여야 한다 — 아니면 전체 롤백 (버전·이력도 남지 않는다)
        LogicalHierarchy hierarchy = subtypeService.hierarchy(request.modelId());
        SubtypePlanContext after = subtypeContexts.light(projectId, hierarchy, recommendation.dbType(projectId));
        after.prepare(ids);
        for (Long id : ids) {
            SubtypePlanContext.Inspection inspection = after.mapping(id).isPresent() ? after.inspect(id) : null;
            if (inspection == null || !"MATCH".equals(inspection.result())) {
                throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "보정한 상속 FK 가 정합성 비교에서 일치하지 않습니다. ({0} : {1})",
                        after.entity(id).getEntityName(), inspection == null ? "-" : Objects.requireNonNullElse(inspection.code(), inspection.result()));
            }
        }
        return new Result(ids.size(), created.size(), created, version.id(), version.versionName());
    }

    // ------------------------------------------------------------------ 계획

    private record Computation(Plan plan, Map<Long, ColumnInfo> columns) {
    }

    private Computation compute(Long projectId, Long modelId, List<Long> ids) {
        entityManager.createQuery("select m.id from LogicalModel m where m.id = :id and m.projectId = :projectId", Long.class)
                .setParameter("id", modelId).setParameter("projectId", projectId).getResultStream().findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("논리 모델")));
        // 고른 엔터티는 모두 이 프로젝트·이 논리 모델의 것이어야 한다 (다른 모델·프로젝트의 ID 는 있는지도 알려주지 않는다)
        Set<Long> inModel = new HashSet<>(entityManager.createQuery("select e.id from LogicalEntity e where e.id in :ids and e.model.id = :modelId and e.projectId = :projectId", Long.class)
                .setParameter("ids", ids).setParameter("modelId", modelId).setParameter("projectId", projectId).getResultList());
        for (Long id : ids) {
            if (!inModel.contains(id)) throw invalid("이 논리 모델의 엔터티가 아닙니다. (엔터티 ID {0})", id);
        }
        LogicalHierarchy hierarchy = subtypeService.hierarchy(modelId);
        DbType db = recommendation.dbType(projectId);
        SubtypePlanContext context = subtypeContexts.light(projectId, hierarchy, db);
        context.prepare(ids);

        Map<Long, String> groupNames = new HashMap<>();
        Set<Long> groupIds = ids.stream().map(hierarchy::parentGroup).filter(Objects::nonNull).map(LogicalHierarchy.Group::id).collect(Collectors.toSet());
        if (!groupIds.isEmpty()) {
            entityManager.createQuery("select g.id, g.groupName from LogicalSubtypeGroup g where g.id in :ids", Object[].class).setParameter("ids", groupIds).getResultList()
                    .forEach(o -> groupNames.put((Long) o[0], (String) o[1]));
        }

        // 판정 (자식 테이블·컬럼은 매핑·상속 PK 매핑에서 — 이미 읽었다)
        List<Judged> judged = new ArrayList<>();
        Map<Long, ColumnInfo> columns = new HashMap<>();
        Set<Long> childTables = new TreeSet<>();
        InheritanceConversions conversions = InheritanceConversions.ofRows(hierarchy, conversionRepository.findAllByModelId(projectId, modelId));
        for (Long id : ids) {
            Judged j = judge(id, hierarchy, context, db, groupNames);
            // 상속 A·C형 (D-AC-13 16.4) : A·C 에는 상속 FK 가 없다 — 이 보정의 대상이 아니다 (상태·미리보기·적용 모두)
            if (conversions.alternative(id)) {
                j.blockers.add(0, new Issue(Code.STRATEGY_EXCLUDED, Messages.get("엔터티 {0} 의 상속 계층은 A·C형으로 만들어져 상속 FK 가 없습니다. 상속 FK 보정 대상이 아닙니다.", j.name)));
            }
            judged.add(j);
            j.childColumns.forEach(c -> columns.put(c.getId(), c));
            j.parentColumns.forEach(c -> columns.put(c.getId(), c));
            if (j.childTable != null) childTables.add(j.childTable.getId());
        }
        // 자식 테이블의 기존 관계 (같은 구성 — 논리 관계 포함) · 자식 스키마의 FK 이름 (DDL 제약조건 이름 충돌)
        Map<Long, List<TableRelation>> relationsByChild = new HashMap<>();
        Set<String> usedNames = new HashSet<>();
        if (!childTables.isEmpty()) {
            entityManager.createQuery("select distinct r from TableRelation r left join fetch r.columns where r.projectId = :projectId and r.sourceTable.id in :ids", TableRelation.class)
                    .setParameter("projectId", projectId).setParameter("ids", childTables).getResultList()
                    .forEach(r -> relationsByChild.computeIfAbsent(r.getSourceTable().getId(), k -> new ArrayList<>()).add(r));
            Set<Long> schemas = judged.stream().filter(j -> j.childTable != null && j.childTable.getSchema() != null).map(j -> j.childTable.getSchema().getId()).collect(Collectors.toSet());
            if (!schemas.isEmpty()) {
                entityManager.createQuery("select r.sourceTable.schema.id, upper(r.relationName) from TableRelation r where r.projectId = :projectId and r.relationType = :fk"
                                + " and r.sourceTable.schema.id in :schemas and r.relationName is not null", Object[].class)
                        .setParameter("projectId", projectId).setParameter("fk", TableRelation.Type.FK).setParameter("schemas", schemas).getResultList()
                        .forEach(o -> usedNames.add(o[0] + ":" + o[1]));
            }
        }
        Map<String, Integer> plannedNames = new HashMap<>();
        for (Judged j : judged) {
            if (j.correctableSoFar() && j.relationName != null) plannedNames.merge(j.childTable.getSchema().getId() + ":" + j.relationName.toUpperCase(Locale.ROOT), 1, Integer::sum);
        }
        List<Item> items = new ArrayList<>();
        for (Judged j : judged) {
            if (j.correctableSoFar()) physicalChecks(j, relationsByChild, usedNames, plannedNames);
            items.add(j.item());
        }
        int correctable = (int) items.stream().filter(Item::correctable).count();
        String token = token(projectId, modelId, db, items, judged);
        return new Computation(new Plan(modelId, items, items.size(), correctable, items.size() - correctable, correctable == items.size() && !items.isEmpty(), token), columns);
    }

    /** 서브타입 1개의 판정 중간값 */
    private static final class Judged {
        Long id;
        String name;
        Long parentId;
        String parentName;
        Long groupId;
        String groupName;
        TableInfo childTable;
        TableInfo parentTable;
        String diagnosis;
        String detail;
        String inspectionCode;
        String relationName;
        final List<ColumnInfo> childColumns = new ArrayList<>();
        final List<ColumnInfo> parentColumns = new ArrayList<>();
        final List<Pair> pairs = new ArrayList<>();
        final List<Issue> blockers = new ArrayList<>();
        final List<String> existing = new ArrayList<>();
        final List<Long> rootKey = new ArrayList<>();
        Long pendingId;
        String pendingName;

        boolean correctableSoFar() {
            return blockers.isEmpty() && childTable != null && parentTable != null && !childColumns.isEmpty();
        }

        Item item() {
            return new Item(id, name, parentId, parentName, groupId, groupName, childTable == null ? null : childTable.getId(),
                    childTable == null || childTable.getSchema() == null ? null : childTable.getSchema().getSchemaName(), childTable == null ? null : childTable.getTableName(),
                    parentTable == null ? null : parentTable.getId(), parentTable == null || parentTable.getSchema() == null ? null : parentTable.getSchema().getSchemaName(),
                    parentTable == null ? null : parentTable.getTableName(), diagnosis, detail, REPRESENTATION, relationName, List.copyOf(pairs), blockers.isEmpty(),
                    List.copyOf(blockers), pendingId, pendingName, List.copyOf(existing));
        }
    }

    /** 상속 구조 판정 → 보정 가능 여부 (물리 충돌은 {@link #physicalChecks}) */
    private Judged judge(Long id, LogicalHierarchy hierarchy, SubtypePlanContext context, DbType db, Map<Long, String> groupNames) {
        Judged j = new Judged();
        j.id = id;
        j.name = context.entity(id).getEntityName();
        j.parentId = hierarchy.parent(id);
        if (j.parentId == null) {
            j.blockers.add(new Issue(Code.NOT_SUBTYPE, Messages.get("엔터티 {0} 은(는) 서브타입이 아닙니다 (상속 관계가 없거나 지워졌습니다).", j.name)));
            return j;
        }
        j.parentName = context.entity(j.parentId).getEntityName();
        LogicalHierarchy.Group group = hierarchy.parentGroup(id);
        j.groupId = group == null ? null : group.id();
        j.groupName = j.groupId == null ? null : groupNames.get(j.groupId);
        context.rootKey(id).forEach(a -> j.rootKey.add(a.getId()));
        java.util.Optional<EntityTableMapping> mapping = context.mapping(id);
        if (mapping.isEmpty()) {
            j.blockers.add(new Issue(Code.NOT_MAPPED, Messages.get("서브타입 {0} 이(가) 물리 테이블에 매핑되지 않았습니다.", j.name)));
            return j;
        }
        SubtypePlanContext.Inspection inspection = context.inspect(id);
        j.childTable = inspection.table();
        j.parentTable = inspection.parentTable();
        j.diagnosis = inspection.result();
        j.inspectionCode = inspection.code();
        j.detail = inspection.checks().stream().filter(c -> "PARENT_FK".equals(c.code())).map(SubtypePlanContext.Check::detail).findFirst().orElse(null);
        if (j.parentTable != null) {
            for (SubtypePlanContext.Fk fk : context.outgoing(j.childTable.getId())) {
                if (j.parentTable.getId().equals(fk.targetTableId())) {
                    j.existing.add(Objects.requireNonNullElse(fk.name(), "FK") + " (" + String.join(", ", fk.childNames()) + ") → " + fk.targetTableName() + " ("
                            + String.join(", ", fk.parentNames()) + ")");
                }
            }
        }
        for (SubtypePlanContext.Check check : inspection.checks()) {
            if (SubtypePlanContext.OK.equals(check.level()) || SubtypePlanContext.WARNING.equals(check.level())) continue;
            switch (check.code()) {
                case SubtypePlanContext.ROOT_KEY_MISSING -> j.blockers.add(new Issue(Code.ROOT_KEY_MISSING, check.message()));
                case "KEY_COMPLETE" -> j.blockers.add(new Issue(Code.INHERITED_KEY_MAPPING_MISSING, check.message()));
                case "COLUMN_OWNER" -> j.blockers.add(new Issue(Code.INHERITED_KEY_COLUMN_INVALID, check.message()));
                case "PK_SET" -> {
                    if (SubtypePlanContext.ERROR.equals(check.level())) j.blockers.add(new Issue(Code.CHILD_KEY_MISMATCH, check.message()));
                }
                case "NOT_NULL" -> j.blockers.add(new Issue(Code.CHILD_KEY_NULLABLE, check.message()));
                case "PARENT_TABLE" -> j.blockers.add(new Issue(Code.PARENT_UNMAPPED, check.message()));
                case "ANCESTOR_PATH" -> ancestorIssue(j, id, context, check);
                case "KEY_TYPE" -> {
                    if (SubtypePlanContext.ERROR.equals(check.level())) j.blockers.add(new Issue(Code.TYPE_INCOMPATIBLE, check.message()));
                }
                case "PARENT_FK" -> {
                    if (!SubtypePlanContext.FK_MISSING.equals(check.detail())) {
                        Code code = switch (Objects.requireNonNullElse(check.detail(), "")) {
                            case SubtypePlanContext.FK_ORDER -> Code.FK_ORDER;
                            case SubtypePlanContext.FK_TARGET -> Code.FK_TARGET;
                            default -> Code.FK_COLUMNS;
                        };
                        j.blockers.add(new Issue(code, check.message()));
                    }
                }
                default -> j.blockers.add(new Issue(Code.STRUCTURE_INVALID, check.message()));
            }
        }
        // 보정할 FK 가 없는 경우 (이미 정상 상속 FK) — 아래 계획은 만들지 않는다
        if (j.blockers.isEmpty() && (SubtypePlanContext.FK_OK.equals(j.detail) || SubtypePlanContext.FK_DUPLICATE.equals(j.detail))) {
            j.blockers.add(new Issue(Code.NOT_MISSING, Messages.get("직계 상위 테이블 {0} 을(를) 상속 PK 그대로 참조하는 FK 가 이미 있습니다. 기존 FK 는 고치거나 지우지 않습니다.",
                    j.parentTable.getTableName())));
        }
        if (j.blockers.isEmpty() && !SubtypePlanContext.FK_MISSING.equals(j.detail)) {
            j.blockers.add(new Issue(Code.STRUCTURE_INVALID, Messages.get("상속 구조를 판정할 수 없어 보정하지 않습니다.")));
        }
        // 만들 FK 의 컬럼 쌍 (루트 주 식별자 순서) : 상속 PK 매핑 컬럼 → 직계 부모 테이블 키 컬럼
        for (SubtypePlanContext.KeyState key : inspection.keys()) {
            if (key.column() == null || key.parentColumn() == null) continue;
            j.childColumns.add(key.column());
            j.parentColumns.add(key.parentColumn());
            j.pairs.add(new Pair(j.pairs.size() + 1, key.column().getId(), key.column().getColumnName(), TableDdlGenerator.mappedType(db, key.column()),
                    key.parentColumn().getId(), key.parentColumn().getColumnName(), TableDdlGenerator.mappedType(db, key.parentColumn())));
        }
        if (j.blockers.isEmpty() && (j.childColumns.size() != j.rootKey.size() || j.childColumns.isEmpty())) {
            j.blockers.add(new Issue(Code.STRUCTURE_INVALID, Messages.get("상속 PK 컬럼 쌍을 정할 수 없습니다.")));
        }
        if (!j.childColumns.isEmpty() && j.childTable != null) j.relationName = DbIdentifierRules.foreignKeyName(j.childTable.getTableName(), j.childColumns.get(0).getColumnName());
        return j;
    }

    /** 상위 경로 문제 : 상속 FK 만 없는 상위 서브타입(먼저 보정할 대상)이면 ANCESTOR_FK_PENDING, 그 밖의 불일치는 ANCESTOR_INVALID (위 → 아래 순서로 보정 — D-INH-09) */
    private static void ancestorIssue(Judged j, Long id, SubtypePlanContext context, SubtypePlanContext.Check check) {
        List<SubtypePlanContext.AncestorState> problems = context.problems(id, Set.of());
        Long pending = null;
        boolean other = problems.isEmpty();
        for (SubtypePlanContext.AncestorState problem : problems) {
            boolean missingOnly = SubtypePlanContext.INHERITANCE_FK_MISSING.equals(problem.code())
                    && SubtypePlanContext.FK_MISSING.equals(context.foreignKeyDetail(problem.entityId()));
            if (missingOnly) pending = problem.entityId(); // 조상 순서(직계 → 루트)의 마지막 = 가장 위의 것부터 보정
            else other = true;
        }
        if (pending != null && !other) {
            Long pendingId = pending;
            j.pendingId = pendingId;
            j.pendingName = context.entity(pendingId).getEntityName();
            j.blockers.add(new Issue(Code.ANCESTOR_FK_PENDING, Messages.get("상위 서브타입 {0} 의 상속 FK 가 아직 없습니다. {0} 을(를) 먼저 보정한 뒤 다시 미리보기해주세요.", j.pendingName)));
        } else {
            j.blockers.add(new Issue(Code.ANCESTOR_INVALID, check.message()));
        }
    }

    /** 물리 충돌 : 승인된 자식 테이블·상속 PK 컬럼(관리자도 — D-INH-08), 참조 테이블명 컬럼, 같은 구성의 기존 관계, FK 이름 (같은 스키마·같은 요청) */
    private static void physicalChecks(Judged j, Map<Long, List<TableRelation>> relationsByChild, Set<String> usedNames, Map<String, Integer> plannedNames) {
        TableInfo child = j.childTable;
        if (Boolean.TRUE.equals(child.getIsApproval())) {
            j.blockers.add(new Issue(Code.CHILD_TABLE_APPROVED, Messages.get("자식 테이블 {0} 이(가) 승인되어 있어 FK 를 추가할 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.", child.getTableName())));
        }
        List<String> approved = j.childColumns.stream().filter(c -> Boolean.TRUE.equals(c.getIsApproval())).map(ColumnInfo::getColumnName).toList();
        if (!approved.isEmpty()) {
            j.blockers.add(new Issue(Code.CHILD_COLUMN_APPROVED, Messages.get("자식 컬럼 {0} 이(가) 승인되어 있어 FK 에 쓸 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.", String.join(", ", approved))));
        }
        j.childColumns.stream().filter(c -> c.getRefTableName() != null && !c.getRefTableName().isBlank()).findFirst().ifPresent(c -> j.blockers.add(new Issue(Code.REF_COLUMN_CONFLICT,
                Messages.get("{0} 은(는) 참조 테이블({1})이 지정된 컬럼입니다. 그 값으로 관계가 만들어지므로 FK 를 따로 만들지 않습니다.", c.getColumnName(), c.getRefTableName().trim()))));
        List<Long> ordered = j.childColumns.stream().map(ColumnInfo::getId).toList();
        relationsByChild.getOrDefault(child.getId(), List.of()).stream()
                .filter(r -> r.getTargetTable() != null && r.getTargetTable().getId().equals(j.parentTable.getId())
                        && r.getColumns().stream().sorted(Comparator.comparing(TableRelationColumn::getColumnOrder)).map(c -> c.getSourceColumn().getId()).toList().equals(ordered))
                .findFirst().ifPresent(r -> j.blockers.add(new Issue(Code.RELATION_EXISTS,
                        Messages.get("같은 컬럼 구성으로 {0} 을(를) 가리키는 관계 {1} 이(가) 이미 있습니다.", j.parentTable.getTableName(), Objects.requireNonNullElse(r.getRelationName(), "-")))));
        if (j.relationName != null) {
            String key = child.getSchema().getId() + ":" + j.relationName.toUpperCase(Locale.ROOT);
            if (usedNames.contains(key)) {
                j.blockers.add(new Issue(Code.NAME_CONFLICT, Messages.get("같은 스키마에 이름이 {0} 인 FK 가 이미 있습니다.", j.relationName)));
            } else if (plannedNames.getOrDefault(key, 0) > 1) {
                j.blockers.add(new Issue(Code.NAME_CONFLICT, Messages.get("함께 고른 다른 서브타입도 같은 이름의 FK {0} 을(를) 만듭니다. 하나만 골라주세요.", j.relationName)));
            }
        }
    }

    /**
     * 계획 해시 : 결과에 영향을 주는 값만 — 서브타입·직계 부모·그룹, 루트 주 식별자 속성, 자식·부모 테이블(ID·이름)·컬럼 쌍(ID·이름·타입), 진단·부모 FK 상세·판정 코드,
     * 만들 이름, 선행 보정 대상, 막는 사유 코드(승인·이름·관계 충돌 포함), 기존 FK 표시. 설명 같은 무관한 값은 넣지 않는다
     */
    private static String token(Long projectId, Long modelId, DbType db, List<Item> items, List<Judged> judged) {
        StringBuilder b = new StringBuilder(TOKEN_VERSION).append('|').append(projectId).append('|').append(modelId).append('|').append(db);
        Map<Long, Judged> byId = new HashMap<>();
        judged.forEach(j -> byId.put(j.id, j));
        items.stream().sorted(Comparator.comparing(Item::subtypeEntityId)).forEach(i -> {
            Judged j = byId.get(i.subtypeEntityId());
            b.append("\n").append(i.subtypeEntityId()).append('|').append(i.supertypeEntityId()).append('|').append(i.groupId()).append('|').append(j.rootKey)
                    .append('|').append(i.childTableId()).append('|').append(i.childTableName()).append('|').append(i.parentTableId()).append('|').append(i.parentTableName())
                    .append('|').append(i.diagnosis()).append('|').append(i.parentFkDetail()).append('|').append(j.inspectionCode).append('|').append(i.relationName())
                    .append('|').append(i.pendingAncestorEntityId()).append('|').append(i.existingForeignKeys());
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
        List<Long> ids = normalize(request.entityIds());
        if (apply && (request.planToken() == null || request.planToken().isBlank())) throw invalid("미리보기를 먼저 확인해주세요.");
        return ids;
    }

    private static List<Long> normalize(List<Long> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) throw invalid("보정할 서브타입을 고르세요.");
        if (entityIds.size() > MAX_ENTITIES) throw invalid("한 번에 {0}개까지 보정할 수 있습니다. (요청 {1}개)", MAX_ENTITIES, entityIds.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : entityIds) {
            if (id == null) throw invalid("엔터티 ID 가 비어 있습니다.");
            if (!seen.add(id)) throw invalid("같은 서브타입이 두 번 들어 있습니다. (엔터티 ID {0})", id);
        }
        return new ArrayList<>(seen);
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, message, args);
    }
}
