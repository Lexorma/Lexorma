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
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.StructureColumn;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Code;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Column;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Issue;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Item;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Plan;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Request;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Result;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.physical.sync.ModelRowLocks;
import com.lexorma.data.table.ddl.DbIdentifierCheck;
import com.lexorma.data.table.ddl.DbIdentifierRules;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.DdlIdentifierQueryRepository;
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

import static com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.MAX_IDENTIFIERS;

/**
 * 기존 물리 테이블 누락 UNIQUE 보정 (M7-AK-02 — 확정 정책 M7-AK-D01·D02·D03·D04·D05·D07·D08·D13·D14·D15, docs/completed/alternate-key-unique-m7.md).
 * 누락 FK 보정({@link ForeignKeyCorrectionService})과 같은 계약이다 :
 * <ul>
 *   <li>대상 : 정합성 비교의 보조 식별자 진단({@link ModelMappingQualityService#structureRows})이 <b>UNIQUE_MISSING</b> 인 보조 식별자 중 사용자가 고른 것.
 *       진단을 다시 구현하지 않는다 — 구성 컬럼(식별자 순서)을 진단 결과에서 그대로 쓴다. UNIQUE_MISSING 이 곧 보정 가능은 아니다 : 아래 검사를 따로 한다.</li>
 *   <li>검사 : 매핑·구성 컬럼(같은 테이블, 중복 없음), 같은 컬럼 <b>집합</b>(순서 무시 — D04)의 PK·UNIQUE·고유 인덱스·단일 고유값 컬럼, 같은 엔터티의 같은 집합 보조 식별자 중
 *       대표(식별자 ID 가 가장 작은 것 — 고른 순서·화면 순서와 무관, D05)만, 승인된 테이블·구성 컬럼(관리자도 — D08), 만들 이름이 같은 DBMS 이름 공간의 다른 이름과 같음
 *       ({@link DbIdentifierCheck} 의 이름 공간 규칙, 같은 요청 안 포함). nullable 구성은 경고만 (D03 — NOT NULL 로 바꾸지 않는다).</li>
 *   <li>만들기 : 명시적 UNIQUE 제약조건, 단일·복합 모두, 식별자 순서, 이름 = UNIQUE 등록의 기본 이름, <b>미승인</b> ({@link PhysicalModelService#createGeneratedUnique}).</li>
 *   <li>적용 : 프로젝트 행 배타(맨 먼저) → 논리 모델 행 → 테이블 행(ID 순) → 구성 컬럼 행(ID 순), 모두 NOWAIT ({@link ModelRowLocks}, 바쁘면 409) → 다시 진단·계획해
 *       planToken 비교(다르면 409) → 막는 사유가 있으면 400 → 직전 모델 버전 {@code UKFIX-<요청 ID>} → UNIQUE 생성 → flush → 다시 진단해 고른 보조 식별자가 모두 MATCH 인지
 *       확인 (아니면 전체 롤백). 일반 UNIQUE 등록·제약조건 삭제·고유 인덱스 등록·컬럼 저장은 같은 테이블 행을 잡으므로 커밋 전에 같은 구성을 넣을 수 없다 (D15).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UniqueCorrectionService {

    static final String VERSION_PREFIX = "UKFIX-";
    /** 계획 해시 형식 (판정 규칙·정규화 형식이 바뀌면 올린다) */
    static final String TOKEN_VERSION = "uk-correction/1";

    private final ModelMappingQualityService qualityService;
    private final PhysicalModelService physicalModelService;
    private final PhysicalNameRecommendationService recommendation;
    private final DdlIdentifierQueryRepository identifierRepository;
    private final ProjectRepository projectRepository;
    private final ProjectWriteGuard projectWriteGuard;
    private final ModelRowLocks rowLocks;
    private final ModelVersionService versionService;
    private final ModelChangeRecorder recorder;
    @PersistenceContext
    private EntityManager entityManager;

    /** 목록 표시용 판정 (조회 권한) : 계획 토큰 없이 보조 식별자별 보정 가능 여부만 */
    @Transactional(readOnly = true)
    public List<Item> status(AccountDto account, Long modelId, List<Long> identifierIds) {
        return compute(account.getProjectId(), modelId, normalize(identifierIds)).plan().items();
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
            if (item.tableId() != null) tables.add(item.tableId());
            item.columns().forEach(c -> { if (c.columnId() != null) columns.add(c.columnId()); });
        }
        ModelRowLocks.noWait(() -> entityManager.find(LogicalModel.class, request.modelId(), LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0)));
        rowLocks.tablesNoWait(tables);
        rowLocks.columnsNoWait(columns);

        Computation computed = compute(projectId, request.modelId(), ids);
        if (!computed.plan().planToken().equals(request.planToken())) {
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("UNIQUE 보정 계획"));
        }
        Item blocked = computed.plan().items().stream().filter(i -> !i.correctable()).findFirst().orElse(null);
        if (blocked != null) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "보정할 수 없는 보조 식별자가 있습니다. 미리보기의 사유를 확인하고 그 식별자를 뺀 뒤 다시 미리보기해주세요. ({0} : {1})",
                    blocked.logicalValue(), blocked.blockers().get(0).reason());
        }

        String requestId = UUID.randomUUID().toString();
        String versionName = VERSION_PREFIX + requestId;
        ModelVersionDto.Summary version = versionService.createBeforeModelChange(projectId, versionName,
                Messages.get("누락 UNIQUE 보정 직전 상태 (보조 식별자 {0}개, 요청 {1})", ids.size(), requestId), account.getUsername());
        recorder.markChangeGroup(versionName);

        List<Long> created = new ArrayList<>();
        for (Item item : computed.plan().items()) {
            List<ColumnInfo> members = item.columns().stream().map(c -> computed.columns().get(c.columnId())).toList();
            TableInfo table = members.get(0).getTableInfo();
            created.add(physicalModelService.createGeneratedUnique(projectId, table, members, item.constraintName()).getId());
        }
        entityManager.flush();
        // 최종 확인 : 같은 진단으로 고른 보조 식별자가 모두 일치(MATCH)여야 한다 — 아니면 전체 롤백 (버전·이력도 남지 않는다)
        Map<Long, Row> after = rowsOf(qualityService.structureRows(projectId, request.modelId(), computed.modelName()));
        for (Long id : ids) {
            Row row = after.get(id);
            if (row == null || row.status() != CheckStatus.MATCH) {
                throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "보정한 UNIQUE 가 정합성 비교에서 일치하지 않습니다. ({0} : {1})",
                        row == null ? String.valueOf(id) : row.logicalValue(), row == null ? "-" : row.structure().code());
            }
        }
        return new Result(ids.size(), created.size(), created, version.id(), version.versionName());
    }

    // ------------------------------------------------------------------ 계획

    private record Computation(Plan plan, Map<Long, ColumnInfo> columns, String modelName) {
    }

    private static Map<Long, Row> rowsOf(List<Row> structure) {
        Map<Long, Row> rows = new LinkedHashMap<>();
        structure.stream().filter(r -> r.type() == CheckType.ALTERNATE_KEY && r.structure() != null).forEach(r -> rows.put(r.structure().logicalId(), r));
        return rows;
    }

    private Computation compute(Long projectId, Long modelId, List<Long> ids) {
        String modelName = entityManager.createQuery("select m.modelName from LogicalModel m where m.id = :id and m.projectId = :projectId", String.class)
                .setParameter("id", modelId).setParameter("projectId", projectId).getResultStream().findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("논리 모델")));
        Map<Long, Row> rows = rowsOf(qualityService.structureRows(projectId, modelId, modelName));
        InheritanceConversions conversions = qualityService.conversions(projectId, modelId);
        for (Long id : ids) {
            if (!rows.containsKey(id)) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "이 논리 모델의 보조 식별자가 아닙니다. (식별자 ID {0})", id);
        }
        DbType db = recommendation.dbType(projectId);

        // 같은 엔터티의 같은 속성 집합 보조 식별자 : 대표 = 식별자 ID 가 가장 작은 것 (모델 전체의 보조 식별자로 본다 — 고르지 않은 것 포함, D05)
        Map<String, Long> representative = new HashMap<>();
        Map<Long, String> identifierName = new HashMap<>();
        rows.values().stream().sorted(Comparator.comparing(r -> r.structure().logicalId())).forEach(r -> {
            identifierName.put(r.structure().logicalId(), r.structure().logicalName());
            representative.putIfAbsent(r.entityId() + ":" + attributeSet(r), r.structure().logicalId());
        });

        // 고른 보조 식별자의 컬럼·테이블 (한 번에)
        Set<Long> columnIds = new HashSet<>();
        Set<Long> tableIds = new HashSet<>();
        for (Long id : ids) {
            rows.get(id).structure().columns().forEach(c -> { if (c.expectedColumnId() != null) columnIds.add(c.expectedColumnId()); });
            if (rows.get(id).tableId() != null) tableIds.add(rows.get(id).tableId());
        }
        Map<Long, ColumnInfo> columns = new HashMap<>();
        if (!columnIds.isEmpty()) {
            entityManager.createQuery("select c from ColumnInfo c join fetch c.tableInfo t join fetch t.schema where c.id in :ids and c.projectId = :projectId", ColumnInfo.class)
                    .setParameter("ids", columnIds).setParameter("projectId", projectId).getResultList().forEach(c -> columns.put(c.getId(), c));
        }
        // 대상 테이블의 PK·UNIQUE 제약조건·고유 인덱스 (같은 집합 중복 — D04)
        Map<Long, Set<Long>> primaryKeys = new HashMap<>();
        Map<Long, List<Set<Long>>> uniqueConstraints = new HashMap<>();
        Map<Long, List<Set<Long>>> uniqueIndexes = new HashMap<>();
        if (!tableIds.isEmpty()) {
            entityManager.createQuery("select c.tableInfo.id, c.id from ColumnInfo c where c.tableInfo.id in :ids and c.isPk = true", Object[].class)
                    .setParameter("ids", tableIds).getResultList().forEach(o -> primaryKeys.computeIfAbsent((Long) o[0], k -> new HashSet<>()).add((Long) o[1]));
            entityManager.createQuery("select distinct c from TableConstraint c left join fetch c.columns where c.projectId = :projectId and c.tableInfo.id in :ids and c.constraintType = :type",
                            TableConstraint.class).setParameter("projectId", projectId).setParameter("ids", tableIds).setParameter("type", TableConstraint.Type.UNIQUE).getResultList()
                    .forEach(c -> uniqueConstraints.computeIfAbsent(c.getTableInfo().getId(), k -> new ArrayList<>())
                            .add(c.getColumns().stream().map(TableConstraintColumn::getColumn).map(ColumnInfo::getId).collect(Collectors.toSet())));
            entityManager.createQuery("select distinct i from TableIndex i left join fetch i.columns where i.projectId = :projectId and i.tableInfo.id in :ids and i.isUnique = true",
                            TableIndex.class).setParameter("projectId", projectId).setParameter("ids", tableIds).getResultList()
                    .forEach(i -> uniqueIndexes.computeIfAbsent(i.getTableInfo().getId(), k -> new ArrayList<>())
                            .add(i.getColumns().stream().filter(c -> c.getColumn() != null).map(c -> c.getColumn().getId()).collect(Collectors.toSet())));
        }
        // 이름 공간 (DDL 이 쓰는 PK·UNIQUE·FK·CHECK·인덱스 이름 — 스키마마다 한 번)
        Map<Long, List<DbIdentifierCheck.DdlName>> namesBySchema = new HashMap<>();
        columns.values().stream().map(c -> c.getTableInfo().getSchema().getId()).distinct()
                .forEach(schemaId -> namesBySchema.put(schemaId, DbIdentifierCheck.ddlNames(db, identifierRepository.model(projectId, schemaId))));

        List<Item> items = new ArrayList<>();
        for (Long id : ids) {
            Row row = rows.get(id);
            Item made = item(row, columns, primaryKeys, uniqueConstraints, uniqueIndexes, namesBySchema, representative, identifierName, db);
            // 상속 A·C형 (D-AC-13 16.4) : 통합 테이블·말단 복제 UNIQUE 는 이 보정으로 만들지 않는다 — 상태·미리보기·적용 모두 같은 판정
            if (conversions.alternative(row.entityId())) {
                List<Issue> blockers = new ArrayList<>(made.blockers());
                blockers.add(0, new Issue(Code.STRATEGY_EXCLUDED, Messages.get("상속 A·C형 계층(통합 테이블·말단 테이블)의 보조 식별자는 이 보정으로 UNIQUE 를 만들지 않습니다. 필요하면 물리 모델에서 직접 등록하세요.")));
                made = withBlockers(made, blockers);
            }
            items.add(made);
        }
        // 같은 요청 안에서 만들 UNIQUE 이름이 같은 이름 공간에서 겹친다 (다른 사유로 막혀 만들지 않을 항목은 세지 않는다 — 같은 구성의 두 번째 보조 식별자 등)
        List<Item> named = items.stream().filter(i -> i.correctable() && i.constraintName() != null && i.tableId() != null && !i.columns().isEmpty()).toList();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (!item.correctable() || item.constraintName() == null || item.tableId() == null || item.columns().isEmpty()) continue;
            DbIdentifierCheck.DdlName mine = ddlName(db, columns, item);
            boolean clash = named.stream().filter(o -> o != item).map(o -> ddlName(db, columns, o))
                    .anyMatch(o -> o.ddlName().equalsIgnoreCase(mine.ddlName()) && DbIdentifierCheck.sameNamespace(db, mine, o));
            if (clash && item.blockers().stream().noneMatch(b -> b.code() == Code.NAME_CONFLICT)) {
                List<Issue> blockers = new ArrayList<>(item.blockers());
                blockers.add(new Issue(Code.NAME_CONFLICT, Messages.get("함께 고른 다른 보조 식별자도 같은 이름의 UNIQUE {0} 을(를) 만듭니다. 하나만 골라주세요.", item.constraintName())));
                items.set(i, withBlockers(item, blockers));
            }
        }
        int correctable = (int) items.stream().filter(Item::correctable).count();
        String token = token(projectId, modelId, db, items);
        return new Computation(new Plan(modelId, items, items.size(), correctable, items.size() - correctable, correctable == items.size() && !items.isEmpty(), token),
                columns, modelName);
    }

    private static String attributeSet(Row row) {
        return row.structure().columns().stream().map(StructureColumn::logicalAttributeId).filter(Objects::nonNull).sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static DbIdentifierCheck.DdlName ddlName(DbType db, Map<Long, ColumnInfo> columns, Item item) {
        Long schemaId = columns.get(item.columns().get(0).columnId()).getTableInfo().getSchema().getId();
        return new DbIdentifierCheck.DdlName(DbIdentifierRules.Kind.UNIQUE, schemaId, item.tableId(), null, null, item.constraintName(),
                DbIdentifierRules.ddlName(db, item.constraintName()), false);
    }

    private Item item(Row row, Map<Long, ColumnInfo> columns, Map<Long, Set<Long>> primaryKeys, Map<Long, List<Set<Long>>> uniqueConstraints,
                      Map<Long, List<Set<Long>>> uniqueIndexes, Map<Long, List<DbIdentifierCheck.DdlName>> namesBySchema, Map<String, Long> representative,
                      Map<Long, String> identifierName, DbType db) {
        var s = row.structure();
        Long id = s.logicalId();
        List<Issue> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String code = s.code();
        Long rep = representative.get(row.entityId() + ":" + attributeSet(row));
        Long duplicateOf = rep != null && !rep.equals(id) ? rep : null;
        if (!"UNIQUE_MISSING".equals(code)) {
            blockers.add(new Issue(Code.NOT_MISSING, Messages.get("진단 결과가 UNIQUE 누락이 아닙니다 ({0}). 기존 UNIQUE 는 고치거나 지우지 않습니다.", code)));
        }
        if (duplicateOf != null) {
            blockers.add(new Issue(Code.DUPLICATE_ALTERNATE_KEY, Messages.get("같은 구성의 보조 식별자 {0} 이(가) 있습니다. 같은 컬럼 집합에는 UNIQUE 하나만 만들며 {0} 을(를) 기준으로 합니다.",
                    identifierName.getOrDefault(duplicateOf, String.valueOf(duplicateOf)))));
        }
        List<ColumnInfo> members = new ArrayList<>();
        List<Column> out = new ArrayList<>();
        for (StructureColumn c : s.columns()) {
            if (c.logicalAttributeId() == null) continue;
            ColumnInfo column = c.expectedColumnId() == null ? null : columns.get(c.expectedColumnId());
            members.add(column);
            out.add(new Column(c.order(), c.logicalAttributeId(), c.logicalAttributeName(), c.expectedColumnId(), column == null ? c.expectedColumnName() : column.getColumnName(),
                    column == null ? null : TableDdlGenerator.mappedType(db, column), column == null ? null : column.getIsNullable(), column == null ? null : column.getIsApproval()));
        }
        boolean loaded = !members.isEmpty() && members.stream().allMatch(Objects::nonNull);
        TableInfo table = loaded ? members.get(0).getTableInfo() : null;
        String name = null;
        if (!loaded) {
            if (blockers.stream().noneMatch(b -> b.code() == Code.NOT_MISSING)) {
                blockers.add(new Issue(Code.MAPPING_MISSING, Messages.get("보조 식별자의 엔터티·구성 속성이 물리 테이블·컬럼에 매핑되지 않았습니다.")));
            }
        } else {
            Set<Long> set = members.stream().map(ColumnInfo::getId).collect(Collectors.toCollection(LinkedHashSet::new));
            if (set.size() != members.size() || members.stream().anyMatch(m -> !Objects.equals(m.getTableInfo().getId(), row.tableId()))) {
                blockers.add(new Issue(Code.COLUMN_INVALID, Messages.get("구성 컬럼이 매핑 테이블 {0} 의 서로 다른 컬럼이 아닙니다.", Objects.requireNonNullElse(row.tableName(), "-"))));
            }
            if (Boolean.TRUE.equals(table.getIsApproval())) {
                blockers.add(new Issue(Code.APPROVED_TABLE, Messages.get("테이블 {0} 이(가) 승인되어 있어 UNIQUE 를 추가할 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.", table.getTableName())));
            }
            List<String> approved = members.stream().filter(m -> Boolean.TRUE.equals(m.getIsApproval())).map(ColumnInfo::getColumnName).toList();
            if (!approved.isEmpty()) {
                blockers.add(new Issue(Code.APPROVED_COLUMN, Messages.get("구성 컬럼 {0} 이(가) 승인되어 있어 UNIQUE 를 추가할 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.", String.join(", ", approved))));
            }
            Set<Long> pk = primaryKeys.getOrDefault(table.getId(), Set.of());
            if (!pk.isEmpty() && pk.equals(set)) blockers.add(new Issue(Code.PRIMARY_KEY_DUPLICATE, Messages.get("구성 컬럼이 테이블 PK 와 같아 UNIQUE 를 따로 만들지 않습니다.")));
            if (members.size() == 1 && Boolean.TRUE.equals(members.get(0).getIsUnique()) && !Boolean.TRUE.equals(members.get(0).getIsPk())) {
                blockers.add(new Issue(Code.COLUMN_UNIQUE_DUPLICATE, Messages.get("컬럼 {0} 이(가) 이미 고유값(UNIQUE)입니다.", members.get(0).getColumnName())));
            }
            if (uniqueConstraints.getOrDefault(table.getId(), List.of()).stream().anyMatch(set::equals)) {
                blockers.add(new Issue(Code.UNIQUE_DUPLICATE, Messages.get("같은 컬럼 구성(순서 무관)의 UNIQUE 제약조건이 이미 있습니다. 기존 UNIQUE 는 고치지 않습니다.")));
            }
            if (uniqueIndexes.getOrDefault(table.getId(), List.of()).stream().anyMatch(set::equals)) {
                blockers.add(new Issue(Code.UNIQUE_INDEX_DUPLICATE, Messages.get("같은 컬럼 구성의 고유(UNIQUE) 인덱스가 이미 있습니다. 같은 유일성을 UNIQUE 제약조건으로 다시 만들지 않습니다.")));
            }
            name = DbIdentifierRules.uniqueName(table.getTableName(), members.stream().map(ColumnInfo::getColumnName).toList());
            String finalName = DbIdentifierRules.ddlName(db, name);
            Long schemaId = table.getSchema().getId();
            DbIdentifierCheck.DdlName mine = new DbIdentifierCheck.DdlName(DbIdentifierRules.Kind.UNIQUE, schemaId, table.getId(), null, null, name, finalName, false);
            String finalNameUpper = finalName.toUpperCase(Locale.ROOT);
            if (namesBySchema.getOrDefault(schemaId, List.of()).stream().anyMatch(o -> o.ddlName().toUpperCase(Locale.ROOT).equals(finalNameUpper) && DbIdentifierCheck.sameNamespace(db, mine, o))) {
                blockers.add(new Issue(Code.NAME_CONFLICT, Messages.get("만들 UNIQUE 이름 {0} 이(가) 같은 이름 공간의 다른 제약조건·인덱스와 같습니다. 기존 이름은 바꾸지 않습니다.", name)));
            }
            if (members.stream().anyMatch(m -> Boolean.TRUE.equals(m.getIsNullable()))) warnings.add("NULLABLE_COLUMNS");
        }
        return new Item(id, s.logicalName(), row.logicalValue(), row.entityId(), row.entityName(), row.tableId(), row.schemaName(), row.tableName(), code, name, false,
                out, warnings, blockers.isEmpty(), blockers, duplicateOf, duplicateOf == null ? null : identifierName.get(duplicateOf));
    }

    private static Item withBlockers(Item i, List<Issue> blockers) {
        return new Item(i.identifierId(), i.identifierName(), i.logicalValue(), i.entityId(), i.entityName(), i.tableId(), i.schemaName(), i.tableName(), i.diagnosisCode(),
                i.constraintName(), i.approved(), i.columns(), i.warnings(), blockers.isEmpty(), blockers, i.duplicateOfIdentifierId(), i.duplicateOfIdentifierName());
    }

    /** 계획 해시 : 결과에 영향을 주는 값만 (식별자·진단·테이블·구성 컬럼 이름·타입·NULL 여부·이름·대표·사유 코드 — 승인 상태와 중복은 사유에 들어 있다). 식별자 ID 순 */
    private static String token(Long projectId, Long modelId, DbType db, List<Item> items) {
        StringBuilder b = new StringBuilder(TOKEN_VERSION).append('|').append(projectId).append('|').append(modelId).append('|').append(db);
        items.stream().sorted(Comparator.comparing(Item::identifierId)).forEach(i -> {
            b.append("\n").append(i.identifierId()).append('|').append(i.diagnosisCode()).append('|').append(i.tableId()).append('|').append(i.tableName())
                    .append('|').append(i.constraintName()).append('|').append(i.duplicateOfIdentifierId());
            i.columns().forEach(c -> b.append('|').append(c.attributeId()).append('>').append(c.columnId()).append(':').append(c.columnName()).append(':').append(c.dataType())
                    .append(':').append(c.nullable()));
            i.blockers().forEach(x -> b.append("|!").append(x.code()));
            i.warnings().forEach(w -> b.append("|?").append(w));
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
        List<Long> ids = normalize(request.identifierIds());
        if (apply && (request.planToken() == null || request.planToken().isBlank())) throw invalid("미리보기를 먼저 확인해주세요.");
        return ids;
    }

    /** 같은 계획이면 요청 순서와 무관하게 같은 토큰 (식별자 ID 순으로 정규화) */
    private static List<Long> normalize(List<Long> identifierIds) {
        if (identifierIds == null || identifierIds.isEmpty()) throw invalid("보정할 보조 식별자를 고르세요.");
        if (identifierIds.size() > MAX_IDENTIFIERS) throw invalid("한 번에 {0}개까지 보정할 수 있습니다. (요청 {1}개)", MAX_IDENTIFIERS, identifierIds.size());
        Set<Long> seen = new TreeSet<>();
        for (Long id : identifierIds) {
            if (id == null) throw invalid("보조 식별자 ID 가 비어 있습니다.");
            if (!seen.add(id)) throw invalid("같은 보조 식별자가 두 번 들어 있습니다. (식별자 ID {0})", id);
        }
        return new ArrayList<>(seen);
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, message, args);
    }
}
