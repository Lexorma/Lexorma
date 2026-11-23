package com.lexorma.data.mapping.service;

import com.lexorma.data.column.repository.ColumnNameDbKeys;
import com.lexorma.data.column.service.ColumnNameKey;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.common.jpa.ModelBase;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.service.ColumnRenameService;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.logical.domain.entity.LogicalModel;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Applicability;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Item;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.PlanItem;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Request;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Result;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Warning;
import com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository;
import com.lexorma.data.mapping.repository.PhysicalNameRenameQueryRepository;
import com.lexorma.data.mapping.repository.PhysicalNameRenameQueryRepository.ColumnTarget;
import com.lexorma.data.mapping.repository.PhysicalNameRenameQueryRepository.TableTarget;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.ddl.DbIdentifierCheck;
import com.lexorma.data.table.ddl.DbIdentifierRules;
import com.lexorma.data.table.domain.dto.TableNameDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.DdlIdentifierQueryRepository;
import com.lexorma.data.table.service.TableDesignService;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.MAX_ITEMS;

/**
 * 물리명 추천 일괄 적용 (M-7 물리명 추천 일괄 적용 2차 — 사용자 확정 P-01~P-15). 미리보기 → planToken → 적용.
 * <ul>
 *   <li>대상 : 물리 테이블(엔터티 매핑 → 테이블명 추천)·물리 컬럼(속성 매핑 → 표준 용어 영문약어) ID. 새 이름은 서버가 지금 추천 규칙으로 다시 계산한다 —
 *       요청의 추천명이 다르면 {@link ApplyCode#RECOMMENDATION_CHANGED}, 요청 값으로 이름을 바꾸지 않는다. 0건·500건 초과(P-07, 테이블 + 컬럼 합)·중복 대상·빈 값은 400,
 *       다른 프로젝트·없는 ID 는 TABLE/COLUMN_IS_NOT_DEFINED (존재 여부를 구분해 알리지 않는다).</li>
 *   <li>판정 (목록 판정과 같은 함수) : 추천 없음·바꿀 것 없음·매핑 없음, 지금 이름 충돌(테이블 = 같은 스키마의 테이블·VIEW, 컬럼 = 같은 테이블 — 이름 맞바꾸기·연쇄 변경도 여기서 막힌다),
 *       <b>선택 집합 안</b>의 추천끼리 충돌(P-12, {@link RenameSafety#duplicates}), DBMS 식별자({@link DbIdentifierRules#check}), 승인(테이블 P-04, 컬럼 = 컬럼 + 소속 테이블 P-10),
 *       용어(없음 P-09·불일치 P-03), CHECK·인덱스 식·VIEW 정의의 이름 참조(P-08, {@link RenameSafety}), 이름을 바꾼 뒤 DDL 자동 제약조건명의 잘림·충돌(P-13, {@link DbIdentifierCheck}),
 *       원본 매핑은 경고(P-15).</li>
 *   <li>적용 : 프로젝트 행 배타(맨 먼저 — 직전 버전이 한 시점의 모델) → 논리 모델 행(ID 순) → 스키마 행(기본 스키마 = VIEW 참조 그래프, 그다음 이름을 바꿀 테이블의 스키마 ID 순)
 *       → 테이블 행(ID 순) → 컬럼 행(ID 순 한 번에). 프로젝트를 잡은 뒤의 행 잠금은 기다리지 않는다 (일반 저장은 행을 잠근 채 커밋 직전에 프로젝트 행을 공유 잠금으로
 *       확인하므로 기다리면 교착 — 논리 → 물리 생성·원본 가져오기와 같다, 바쁘면 409). 잠근 뒤 다시 계획해 planToken 이 다르면 409, 대상 행의 기준값(DEC-08)이 다르면 409,
 *       막는 사유가 하나라도 있으면 400 (부분 적용 없음, 자동 재시도 없음). 그다음 같은 트랜잭션에서 직전 모델 버전 {@code RENAME-<요청 ID>}(스냅샷 형식 그대로, P-06) →
 *       테이블명(기존 이름 변경과 같은 동기화 — P-11) → 컬럼명(전용 경로 {@link ColumnRenameService}, P-14) → 참조 관계 동기화. 이력은 일반 쓰기처럼 남고 변경 그룹 ID 가 버전명이다.
 *       하나라도 실패하면 버전·이력까지 전체 롤백 (P-05).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PhysicalNameRenameService {

    static final String VERSION_PREFIX = "RENAME-";
    /** 계획 해시 형식 (판정 규칙·정규화 형식이 바뀌면 올린다 — 물리 생성 planToken 과 섞지 않는다) */
    static final String TOKEN_VERSION = "physical-rename/1";
    private static final Map<String, Object> NO_WAIT = Map.of("jakarta.persistence.lock.timeout", 0);

    private final PhysicalNameRenameQueryRepository targets;
    private final PhysicalNameRecommendationQueryRepository names;
    private final ColumnNameDbKeys dbKeys;
    private final PhysicalNameRecommendationService recommendation;
    private final TableDesignService tableDesignService;
    private final ColumnRenameService columnRenameService;
    private final PhysicalModelService physicalModelService;
    private final ProjectRepository projectRepository;
    private final ProjectWriteGuard projectWriteGuard;
    private final com.lexorma.data.physical.sync.ModelRowLocks rowLocks;
    private final ModelVersionService versionService;
    private final ModelChangeRecorder recorder;
    private final DdlIdentifierQueryRepository identifierRepository;
    private final SchemaInfoRepository schemaRepository;
    @PersistenceContext
    private EntityManager entityManager;

    /** 미리보기 : 아무것도 바꾸지 않는다 */
    @Transactional(readOnly = true)
    public Plan preview(AccountDto account, Request request) {
        List<Item> items = validate(request, false);
        return compute(account, items).plan();
    }

    @Transactional
    public Result apply(AccountDto account, Request request) {
        List<Item> items = validate(request, true);
        Long projectId = account.getProjectId();
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        projectWriteGuard.lock(projectId);

        // 잠글 행 : 계획과 같은 조회(투영)로 정한다. 프로젝트를 배타로 잡았으므로 그 사이 커밋되는 모델 변경은 없다
        Set<Long> tableIds = ids(items, TargetType.TABLE);
        Set<Long> columnIds = ids(items, TargetType.COLUMN);
        List<TableTarget> tableTargets = targets.findTables(projectId, tableIds);
        List<ColumnTarget> columnTargets = targets.findColumns(projectId, columnIds);
        requireFound(tableIds, tableTargets.stream().map(TableTarget::tableId).collect(Collectors.toSet()), TargetType.TABLE);
        requireFound(columnIds, columnTargets.stream().map(ColumnTarget::columnId).collect(Collectors.toSet()), TargetType.COLUMN);
        TreeSet<Long> modelIds = new TreeSet<>();
        tableTargets.stream().map(TableTarget::modelId).filter(Objects::nonNull).forEach(modelIds::add);
        columnTargets.stream().map(ColumnTarget::modelId).filter(Objects::nonNull).forEach(modelIds::add);
        TreeSet<Long> schemaIds = tableTargets.stream().map(TableTarget::schemaId).collect(Collectors.toCollection(TreeSet::new));
        TreeSet<Long> lockedTables = new TreeSet<>(tableIds);
        columnTargets.forEach(c -> lockedTables.add(c.tableId()));
        Map<Long, TableInfo> tableEntities = new HashMap<>();
        Map<Long, ColumnInfo> columnEntities = new HashMap<>();
        try {
            for (Long modelId : modelIds) entityManager.find(LogicalModel.class, modelId, LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
            // 기본 스키마 행 = 프로젝트의 VIEW 참조 그래프 잠금 (VIEW 등록·수정·변경 요청 처리·삭제가 함께 잠근다 — ObjectNamespace). 그다음 나머지 스키마 ID 순
            Long defaultSchemaId = schemaRepository.findFirstByProjectIdAndIsDefaultTrue(projectId).map(SchemaInfo::getId).orElse(null);
            if (defaultSchemaId != null) {
                entityManager.find(SchemaInfo.class, defaultSchemaId, LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
                schemaIds.remove(defaultSchemaId);
            }
            for (Long schemaId : schemaIds) entityManager.find(SchemaInfo.class, schemaId, LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
            // 이름을 바꿀 테이블 행 : 키 강도(FOR UPDATE NOWAIT) 먼저 (M7-FK-D06). 이름 UPDATE 가 다른 트랜잭션의 FK KEY SHARE(그 테이블을 부모로 하는 관계 INSERT —
            // 엑셀 업로드의 청크 동기화 등)를 프로젝트 행을 쥔 채 기다리면 교착(S6b)이라, 같은 강도를 미리 기다리지 않고 잡는다
            rowLocks.tablesForKeyChange(tableIds);
            // 테이블 행 : 승인·CHECK·인덱스·관계·PK 를 바꾸는 쓰기와 컬럼 일괄 저장이 먼저 잠그는 행
            for (Long tableId : lockedTables) tableEntities.put(tableId, entityManager.find(TableInfo.class, tableId, LockModeType.PESSIMISTIC_WRITE, NO_WAIT));
            // 이름을 바꿀 컬럼 행 : 키 강도 먼저 (컬럼명 = UX_COL_01 의 열 — 같은 이유)
            rowLocks.columnsForKeyChange(columnIds);
            // 컬럼 행 : 이름을 바꿀 컬럼 + 이름을 바꿀 테이블의 컬럼 (테이블명 사본) — 한 번에 ID 순
            if (!columnIds.isEmpty() || !tableIds.isEmpty()) {
                entityManager.createQuery("select c from ColumnInfo c where c.id in :columnIds or c.tableInfo.id in :tableIds order by c.id", ColumnInfo.class)
                        .setParameter("columnIds", columnIds.isEmpty() ? Set.of(-1L) : columnIds)
                        .setParameter("tableIds", tableIds.isEmpty() ? Set.of(-1L) : tableIds)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", 0).getResultList()
                        .forEach(c -> columnEntities.put(c.getId(), c));
            }
        } catch (jakarta.persistence.PessimisticLockException | jakarta.persistence.LockTimeoutException e) {
            if (!lockNotAvailable(e)) throw e; // 교착 등 다른 오류는 409 로 바꾸지 않는다
            throw busy();
        }

        Computation computed = compute(account, items);
        if (!computed.plan().planToken().equals(request.planToken())) {
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("이름 변경 계획"));
        }
        // 기준값 (DEC-08) : 미리보기가 준 대상 행의 수정 시각과 잠근 행이 같아야 한다
        for (Item item : items) {
            if (item.type() == TargetType.TABLE) {
                TableInfo t = tableEntities.get(item.id());
                ModelBase.verify(t, item.baseModifiedAt(), t.getTableName());
            } else {
                ColumnInfo c = columnEntities.get(item.id());
                ModelBase.verify(c, item.baseModifiedAt(), c.getTableName() + "." + c.getColumnName());
            }
        }
        PlanItem blocked = computed.plan().items().stream().filter(i -> !i.applicability().selectable()).findFirst().orElse(null);
        if (blocked != null) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "적용할 수 없는 항목이 있습니다. 미리보기의 차단 사유를 확인하고 그 항목을 뺀 뒤 다시 미리보기해주세요. ({0} : {1})",
                    blocked.currentName(), blocked.applicability().blockers().get(0).reason());
        }

        // 이름으로 이 테이블을 가리키는 다른 컬럼 : 바꾸기 전 이름으로 찾고, 바꾸기 전에 잠근다 (참조 테이블명을 고친다 — 기존 이름 변경과 같은 동기화, P-11)
        Map<Long, List<ColumnInfo>> referencing = new HashMap<>();
        try {
            Set<Long> more = new TreeSet<>();
            for (Long tableId : tableIds) {
                List<ColumnInfo> list = tableDesignService.columnsReferencingTable(projectId, tableEntities.get(tableId));
                referencing.put(tableId, list);
                list.stream().map(ColumnInfo::getId).filter(id -> !columnEntities.containsKey(id)).forEach(more::add);
                // 새 이름을 미리 참조하던 컬럼 : 동기화가 관계를 새로 만들며 그 컬럼·테이블 행에 KEY SHARE 를 건다 — 기다리지 않고 미리 잠근다
                TableInfo renamed = tableEntities.get(tableId);
                String newName = computed.newTableNames().get(tableId).toUpperCase(Locale.ROOT);
                entityManager.createQuery("select c.id from ColumnInfo c where c.projectId = :projectId and upper(trim(c.refTableName)) in :names", Long.class)
                        .setParameter("projectId", projectId)
                        .setParameter("names", List.of(newName, renamed.getSchema().getSchemaName().toUpperCase(Locale.ROOT) + "." + newName))
                        .getResultList().stream().filter(id -> !columnEntities.containsKey(id)).forEach(more::add);
            }
            if (!more.isEmpty()) {
                entityManager.createQuery("select c from ColumnInfo c where c.id in :ids order by c.id", ColumnInfo.class).setParameter("ids", more)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint("jakarta.persistence.lock.timeout", 0).getResultList();
            }
        } catch (jakarta.persistence.PessimisticLockException | jakarta.persistence.LockTimeoutException e) {
            if (!lockNotAvailable(e)) throw e;
            throw busy();
        }

        // 직전 모델 버전 (P-06) : 바꾸기 전, 같은 트랜잭션 — 적용이 실패하면 버전도 남지 않는다
        String requestId = UUID.randomUUID().toString();
        String versionName = VERSION_PREFIX + requestId;
        ModelVersionDto.Summary version = versionService.createBeforeModelChange(projectId, versionName,
                Messages.get("물리명 추천 일괄 적용 직전 상태 (테이블 {0}개, 컬럼 {1}개, 요청 {2})", tableIds.size(), columnIds.size(), requestId), account.getUsername());
        recorder.markChangeGroup(versionName);

        for (Long tableId : tableIds.stream().sorted().toList()) {
            tableDesignService.renameLockedTable(tableEntities.get(tableId), computed.newTableNames().get(tableId), referencing.get(tableId));
        }
        for (Long columnId : columnIds.stream().sorted().toList()) {
            columnRenameService.renameLockedColumn(projectId, columnEntities.get(columnId), computed.newColumnNames().get(columnId));
        }
        if (!tableIds.isEmpty()) physicalModelService.syncReferenceRelations(projectId, tableIds);
        // DB UNIQUE(UX_COL_01) 최종 방어 : 위 판정·renameLockedColumn 과 같은 기준이라 보통은 걸리지 않는다. 걸리면 같은 중복 오류로 전체 롤백 (모델 버전도 남지 않는다)
        ColumnNameKey.flush(entityManager, () -> new CustomException(ErrorCode.SAVED_DATA_EXISTS, Messages.get("이름 변경 계획")));

        List<Warning> warnings = computed.plan().items().stream().filter(i -> !i.applicability().warnings().isEmpty())
                .map(i -> new Warning(i.type(), i.id(), i.currentName(), i.applicability().warnings())).toList();
        return new Result(tableIds.size(), columnIds.size(), items.size(), version.id(), version.versionName(), warnings);
    }

    // ------------------------------------------------------------------ 계획

    private record Computation(Plan plan, Map<Long, String> newTableNames, Map<Long, String> newColumnNames) {
    }

    /** 이름을 바꿀 후보 1개의 판정 자료 */
    private static final class Work {
        final Item item;
        Long schemaId;
        String schemaName;
        Long tableId;
        String tableName;
        Long logicalId;
        String logicalName;
        String current;
        String recommended;
        LocalDateTime modifiedAt;
        boolean noChange;
        /** 바꿀 이름이 정해졌고 지금 이름과 다르다 (이후 판정 대상) */
        boolean renames;
        final List<ApplyIssue> issues = new ArrayList<>();

        Work(Item item) {
            this.item = item;
        }
    }

    private Computation compute(AccountDto account, List<Item> items) {
        Long projectId = account.getProjectId();
        DbType db = recommendation.dbType(projectId);
        Set<Long> tableIds = ids(items, TargetType.TABLE);
        Set<Long> columnIds = ids(items, TargetType.COLUMN);
        Map<Long, TableTarget> tableById = new HashMap<>();
        targets.findTables(projectId, tableIds).forEach(t -> tableById.put(t.tableId(), t));
        Map<Long, ColumnTarget> columnById = new HashMap<>();
        targets.findColumns(projectId, columnIds).forEach(c -> columnById.put(c.columnId(), c));
        requireFound(tableIds, tableById.keySet(), TargetType.TABLE);
        requireFound(columnIds, columnById.keySet(), TargetType.COLUMN);

        Map<String, TableNameDto> tableNames = tableDesignService.generateTableNames(
                tableById.values().stream().map(TableTarget::entityName).filter(Objects::nonNull).collect(Collectors.toSet()), account);

        // 상속 매핑으로 이어진 컬럼 (M7-NAME-1, NAME-P07) : 목록에서 고를 수 없게 했지만 요청을 직접 보내도 여기서 막는다 (읽기 전용 — 이름 변경 미지원)
        Map<Long, String> readOnly = recommendation.inheritedReadOnly(projectId, columnIds);
        List<Work> works = new ArrayList<>();
        for (Item item : items) {
            Work w = new Work(item);
            if (item.type() == TargetType.TABLE) tableWork(w, tableById.get(item.id()), tableNames);
            else columnWork(w, columnById.get(item.id()), readOnly.get(item.id()));
            works.add(w);
        }
        List<Work> tableRenames = works.stream().filter(w -> w.renames && w.item.type() == TargetType.TABLE).toList();
        List<Work> columnRenames = works.stream().filter(w -> w.renames && w.item.type() == TargetType.COLUMN).toList();

        // 지금 이름 충돌 (선택과 관계없이 지금 다른 객체가 그 이름을 쓴다 — 맞바꾸기·연쇄 변경도 막힌다)
        Set<String> upperTableNames = tableRenames.stream().map(w -> upper(w.recommended)).collect(Collectors.toSet());
        List<PhysicalNameRecommendationQueryRepository.NamedTable> sameNameTables = names.findTablesByName(projectId, upperTableNames);
        List<PhysicalNameRecommendationQueryRepository.NamedView> sameNameViews = names.findViewsByName(projectId, upperTableNames);
        for (Work w : tableRenames) {
            sameNameTables.stream().filter(t -> !t.tableId().equals(w.item.id()) && Objects.equals(t.schemaId(), w.schemaId) && same(t.tableName(), w.recommended)).findFirst()
                    .ifPresent(t -> w.issues.add(new ApplyIssue(ApplyCode.NAME_CONFLICT, Messages.get("같은 스키마에 추천 테이블명과 이름이 같은 테이블 {0} 이(가) 이미 있습니다.", t.tableName()), List.of(t.tableName()))));
            sameNameViews.stream().filter(v -> Objects.equals(v.schemaId(), w.schemaId) && same(v.viewName(), w.recommended)).findFirst()
                    .ifPresent(v -> w.issues.add(new ApplyIssue(ApplyCode.NAME_CONFLICT,
                            Messages.get("같은 스키마에 추천 테이블명과 이름이 같은 VIEW {0} 이(가) 있습니다. 테이블과 VIEW 는 같은 이름을 쓸 수 없습니다.", v.viewName()), List.of(v.viewName()))));
        }
        // 컬럼 : DB 의 컬럼명 유일성 기준 (저장된 COL_NM_CMPR_VL ↔ 추천명의 DB 비교 값, M7-U10) — 추천 목록의 상태와 같은 기준, 한 번에 조회
        List<ColumnNameDbKeys.Taken> takenColumns = dbKeys.taken(columnRenames.stream().map(w -> w.tableId).collect(Collectors.toSet()),
                columnRenames.stream().map(w -> w.recommended).collect(Collectors.toSet()));
        for (Work w : columnRenames) {
            takenColumns.stream()
                    .filter(c -> c.tableId().equals(w.tableId) && !c.columnId().equals(w.item.id()) && c.candidate().equals(w.recommended)).findFirst()
                    .ifPresent(c -> w.issues.add(new ApplyIssue(ApplyCode.NAME_CONFLICT,
                            Messages.get("같은 테이블에 추천 컬럼명과 이름이 같은 컬럼 {0} 이(가) 이미 있습니다.", c.columnName()), List.of(c.columnName()))));
        }

        // 추천끼리 충돌 : 이 요청의 선택 집합 기준 (P-12) — 고르지 않은 후보는 보지 않는다
        duplicates(tableRenames, w -> w.schemaId, w -> w.recommended, "같은 스키마에서 함께 고른 다른 테이블도 같은 이름을 받습니다 : {0}. 추천끼리 겹치는 이름은 함께 적용할 수 없습니다.");
        Map<String, String> columnKeys = dbKeys.keys(columnRenames.stream().map(w -> w.recommended).toList());
        duplicates(columnRenames, w -> w.tableId, w -> columnKeys.getOrDefault(w.recommended, w.recommended),
                "같은 테이블에서 함께 고른 다른 컬럼도 같은 이름을 받습니다 : {0}. 추천끼리 겹치는 이름은 함께 적용할 수 없습니다.");

        // 식별자 · 승인 · 용어
        for (Work w : tableRenames) {
            w.issues.addAll(PhysicalNameRecommendationService.identifierIssues(db, DbIdentifierRules.Kind.TABLE, w.recommended));
            if (Boolean.TRUE.equals(tableById.get(w.item.id()).approval())) {
                w.issues.add(new ApplyIssue(ApplyCode.TABLE_APPROVED, Messages.get("승인된 테이블은 이름을 바꿀 수 없습니다. 승인을 취소한 뒤 적용할 수 있습니다."), List.of()));
            }
        }
        for (Work w : columnRenames) {
            ColumnTarget c = columnById.get(w.item.id());
            w.issues.addAll(PhysicalNameRecommendationService.identifierIssues(db, DbIdentifierRules.Kind.COLUMN, w.recommended));
            w.issues.addAll(PhysicalNameRecommendationService.columnPolicyIssues(c.approval(), c.tableApproval(), c.columnTermId(), c.attributeTermId()));
        }

        // CHECK · 인덱스 식 · VIEW 정의의 이름 참조 (P-08) : 목록 판정과 같은 자료·함수
        if (!tableRenames.isEmpty() || !columnRenames.isEmpty()) {
            Set<Long> safetyTables = new HashSet<>();
            tableRenames.forEach(w -> safetyTables.add(w.item.id()));
            columnRenames.forEach(w -> safetyTables.add(w.tableId));
            RenameSafety safety = recommendation.safety(projectId, db, safetyTables);
            tableRenames.forEach(w -> w.issues.addAll(safety.tableRename(w.item.id(), w.current)));
            columnRenames.forEach(w -> w.issues.addAll(safety.columnRename(w.tableId, w.tableName, w.item.id(), w.current)));
            constraintNames(projectId, db, tableRenames, columnRenames);
        }

        // 원본 매핑 (P-15, 경고) : 테이블이 원본 테이블과 이어져 있으면 그 테이블·그 테이블의 컬럼 이름 변경에 알린다
        Set<Long> sourceMapped = targets.findSourceMappedTables(projectId,
                java.util.stream.Stream.concat(tableRenames.stream().map(w -> w.item.id()), columnRenames.stream().map(w -> w.tableId)).collect(Collectors.toSet()));
        for (Work w : tableRenames) {
            if (sourceMapped.contains(w.item.id())) w.issues.add(new ApplyIssue(ApplyCode.SOURCE_MAPPED,
                    Messages.get("원본 DB 와 매핑된 테이블입니다. 이름을 바꾸면 원본 DB 의 이름과 달라지고, 원본 비교에서 이름이 다르게 보이거나 원본 전용·모델 전용 항목으로 나올 수 있습니다. 원본 DB 는 바꾸지 않습니다."), List.of()));
        }
        for (Work w : columnRenames) {
            if (sourceMapped.contains(w.tableId)) w.issues.add(new ApplyIssue(ApplyCode.SOURCE_MAPPED,
                    Messages.get("원본 DB 와 매핑된 테이블의 컬럼입니다. 이름을 바꾸면 원본 DB 의 컬럼명과 달라지고, 확정 컬럼 매핑이 없으면 원본 비교에서 원본 전용·모델 전용 컬럼으로 나올 수 있습니다. 원본 DB 는 바꾸지 않습니다."), List.of()));
        }

        // 결과
        List<PlanItem> planItems = new ArrayList<>();
        Map<Long, String> newTableNames = new LinkedHashMap<>();
        Map<Long, String> newColumnNames = new LinkedHashMap<>();
        int selectable = 0, warned = 0;
        for (Work w : works) {
            Applicability a = Applicability.of(w.issues);
            if (a.selectable()) selectable++;
            if (!a.warnings().isEmpty()) warned++;
            if (w.renames) (w.item.type() == TargetType.TABLE ? newTableNames : newColumnNames).put(w.item.id(), upper(w.recommended));
            planItems.add(new PlanItem(w.item.type(), w.item.id(), w.schemaId, w.schemaName, w.tableId, w.tableName, w.logicalId, w.logicalName,
                    w.current, w.recommended, w.item.recommendedName(), w.modifiedAt, w.noChange, a));
        }
        int tables = (int) items.stream().filter(i -> i.type() == TargetType.TABLE).count();
        String token = token(projectId, db, planItems);
        Plan plan = new Plan(planItems, items.size(), tables, items.size() - tables, selectable, items.size() - selectable, warned,
                selectable == items.size(), db.name(), token);
        return new Computation(plan, newTableNames, newColumnNames);
    }

    private void tableWork(Work w, TableTarget t, Map<String, TableNameDto> tableNames) {
        w.schemaId = t.schemaId();
        w.schemaName = t.schemaName();
        w.tableId = t.tableId();
        w.tableName = t.tableName();
        w.logicalId = t.entityId();
        w.logicalName = t.entityName();
        w.current = t.tableName();
        w.modifiedAt = t.modifiedAt();
        if (t.entityId() == null) {
            w.issues.add(new ApplyIssue(ApplyCode.NOT_MAPPED, Messages.get("테이블이 논리 엔터티에 매핑되지 않아 추천 테이블명이 없습니다."), List.of()));
            return;
        }
        TableNameDto name = tableNames.get(t.entityName());
        if (name == null || !name.valid()) {
            w.issues.add(new ApplyIssue(ApplyCode.NOT_RECOMMENDED, Messages.get("엔터티명을 승인된 표준 단어로 나눌 수 없어 추천 테이블명이 없습니다."), List.of()));
            return;
        }
        w.recommended = upper(name.tableName());
        // '바꿀 것 없음' 은 목록과 같은 판정 (M7-NAME-1) : 추천명을 대문자로 바꾸기 전 값으로 비교한다 — 대문자 변환이 ß → SS 처럼 글자 수를 바꾸면 목록은 일치, 미리보기는 변경으로 갈렸다
        if (PhysicalNameRecommendationService.sameName(t.tableName(), name.tableName())) {
            w.noChange = true;
            w.issues.add(new ApplyIssue(ApplyCode.NO_CHANGE, Messages.get("현재 테이블명이 표준 단어로 만든 테이블명과 같습니다."), List.of()));
            return;
        }
        w.renames = true;
        requestedName(w);
    }

    private void columnWork(Work w, ColumnTarget c, String readOnly) {
        w.schemaId = c.schemaId();
        w.schemaName = c.schemaName();
        w.tableId = c.tableId();
        w.tableName = c.tableName();
        w.logicalId = c.attributeId();
        w.logicalName = c.attributeName();
        w.current = c.columnName();
        w.modifiedAt = c.modifiedAt();
        if (readOnly != null) {
            w.issues.add(new ApplyIssue(ApplyCode.INHERITED_READ_ONLY, readOnly, List.of()));
            return;
        }
        if (c.attributeId() == null) {
            w.issues.add(new ApplyIssue(ApplyCode.NOT_MAPPED, Messages.get("컬럼이 논리 속성에 매핑되지 않아 추천 컬럼명이 없습니다."), List.of()));
            return;
        }
        PhysicalNameRecommendationService.ColumnName name = PhysicalNameRecommendationService.recommendColumnName(c.attributeTermId(), c.termAbbreviation());
        if (!name.available()) {
            w.issues.add(new ApplyIssue(ApplyCode.NOT_RECOMMENDED, name.unavailableReason(), List.of()));
            return;
        }
        w.recommended = upper(name.name());
        // '바꿀 것 없음' 은 목록 행과 같은 StandardNames.sameColumnName (M7-NAME-1 — 대문자로 바꾸기 전 추천명으로 비교)
        if (PhysicalNameRecommendationService.sameName(c.columnName(), name.name())) {
            w.noChange = true;
            w.issues.add(new ApplyIssue(ApplyCode.NO_CHANGE, Messages.get("현재 컬럼명이 표준 용어의 영문약어와 같습니다."), List.of()));
            return;
        }
        w.renames = true;
        requestedName(w);
    }

    /** 요청이 보낸 추천명(화면이 본 값)이 서버의 지금 추천명과 다르면 막는다 — 요청 값으로 바꾸지 않는다 */
    private static void requestedName(Work w) {
        if (!same(w.item.recommendedName(), w.recommended)) {
            w.issues.add(new ApplyIssue(ApplyCode.RECOMMENDATION_CHANGED,
                    Messages.get("추천 이름이 바뀌었습니다 (요청 {0}, 지금 {1}). 목록을 다시 불러와 확인해주세요.", w.item.recommendedName(), w.recommended), List.of(w.recommended)));
        }
    }

    private static void duplicates(List<Work> works, java.util.function.Function<Work, Long> namespace, java.util.function.Function<Work, String> compareName, String message) {
        Map<Long, Work> byId = new HashMap<>();
        works.forEach(w -> byId.put(w.item.id(), w));
        Map<Long, List<Long>> duplicates = RenameSafety.duplicates(works.stream().map(w -> new RenameSafety.Candidate(w.item.id(), namespace.apply(w), compareName.apply(w))).toList());
        duplicates.forEach((id, others) -> {
            List<String> otherNames = others.stream().map(o -> byId.get(o).current).toList();
            byId.get(id).issues.add(RenameSafety.issue(ApplyCode.RECOMMENDATION_DUPLICATE, Messages.get(message, RenameSafety.shown(otherNames)), otherNames));
        });
    }

    /**
     * DDL 자동 제약조건명 (P-13) : 고른 이름을 모두 바꾼 뒤의 모델로 {@link DbIdentifierCheck#check} 를 부르고, 바뀌는 자동 이름(이름을 바꿀 테이블의 PK_·UK_·FK_,
     * 이름을 바꿀 컬럼의 UK_·FK_)에 걸린 잘림·충돌만 그 대상에 붙인다 (DDL 생성기와 같은 이름 규칙·잘림·DBMS 이름 공간). 저장된 제약조건·인덱스 이름은 고치지 않는다
     */
    private void constraintNames(Long projectId, DbType db, List<Work> tableRenames, List<Work> columnRenames) {
        Map<Long, Work> tableWork = new HashMap<>();
        tableRenames.forEach(w -> tableWork.put(w.item.id(), w));
        Map<Long, Work> columnWork = new HashMap<>();
        columnRenames.forEach(w -> columnWork.put(w.item.id(), w));
        DbIdentifierCheck.Model current = identifierRepository.model(projectId, null);
        List<DbIdentifierCheck.Table> tables = current.tables().stream()
                .map(t -> tableWork.containsKey(t.id()) ? new DbIdentifierCheck.Table(t.id(), t.schemaId(), upper(tableWork.get(t.id()).recommended)) : t).toList();
        List<DbIdentifierCheck.Column> columns = current.columns().stream()
                .map(c -> columnWork.containsKey(c.id()) ? new DbIdentifierCheck.Column(c.id(), c.tableId(), upper(columnWork.get(c.id()).recommended), c.pk(), c.unique(), c.referenceFk()) : c)
                .toList();
        Map<Long, String> tableNames = new HashMap<>();
        tables.forEach(t -> tableNames.put(t.id(), t.name()));
        DbIdentifierCheck.Model after = new DbIdentifierCheck.Model(current.schemas(), tables, columns, current.storedNames());
        Map<Work, Set<String>> seen = new HashMap<>();
        for (DbIdentifierCheck.Finding finding : DbIdentifierCheck.check(db, after)) {
            DbIdentifierRules.Code code = finding.issue().code();
            if (code != DbIdentifierRules.Code.CONSTRAINT_NAME_TRUNCATED && code != DbIdentifierRules.Code.CONSTRAINT_NAME_COLLISION) continue;
            DbIdentifierRules.Kind kind = finding.issue().kind();
            boolean generated = kind == DbIdentifierRules.Kind.PRIMARY_KEY || finding.columnId() != null;
            if (!generated) continue; // 저장된 이름 자체의 잘림·충돌 : 이름 변경이 만든 것이 아니다
            List<Work> owners = new ArrayList<>();
            Work byTable = tableWork.get(finding.tableId());
            if (byTable != null) owners.add(byTable); // 테이블명이 들어가는 PK_·UK_·FK_
            Work byColumn = finding.columnId() == null ? null : columnWork.get(finding.columnId());
            if (byColumn != null) owners.add(byColumn); // 컬럼명이 들어가는 UK_·FK_
            if (owners.isEmpty()) continue;
            List<String> related = finding.others().stream()
                    .map(o -> o.ddlName() + " (" + DbIdentifierRules.kindLabel(o.kind()) + ", " + tableNames.getOrDefault(o.tableId(), "-") + ")").toList();
            ApplyCode applyCode = code == DbIdentifierRules.Code.CONSTRAINT_NAME_TRUNCATED ? ApplyCode.CONSTRAINT_NAME_INVALID : ApplyCode.CONSTRAINT_NAME_CONFLICT;
            String reason = Messages.get("이름을 바꾸면 DDL 이 만드는 제약조건명에 문제가 생깁니다 : {0}", DbIdentifierRules.reason(db, finding.issue(), related));
            for (Work owner : owners) {
                if (seen.computeIfAbsent(owner, k -> new HashSet<>()).add(applyCode + ":" + finding.issue().ddlName() + ":" + kind)) {
                    owner.issues.add(new ApplyIssue(applyCode, reason, finding.others().stream().map(DbIdentifierCheck.DdlName::ddlName).toList()));
                }
            }
        }
    }

    /**
     * 계획 해시 : 대상(종류·ID 순)마다 지금 이름·새 이름·수정 시각(µs)·판정 코드와 관련 이름. 잠근 뒤 같은 계산이 다르면 409 (미리보기 뒤 승인·용어·매핑·VIEW·CHECK·이름이 바뀌었다).
     * 클라이언트가 고친 토큰은 맞지 않는다
     */
    private static String token(Long projectId, DbType db, List<PlanItem> items) {
        StringBuilder canonical = new StringBuilder(TOKEN_VERSION).append('|').append(projectId).append('|').append(db.name());
        items.stream().sorted(Comparator.comparing((PlanItem i) -> i.type().name()).thenComparing(PlanItem::id)).forEach(i -> {
            canonical.append('\n').append(i.type()).append(':').append(i.id()).append('|').append(i.currentName()).append('|').append(i.recommendedName())
                    .append('|').append(i.modifiedAt() == null ? "" : i.modifiedAt().truncatedTo(ChronoUnit.MICROS)).append('|');
            java.util.stream.Stream.concat(i.applicability().blockers().stream(), i.applicability().warnings().stream())
                    .map(issue -> issue.code() + "=" + issue.related().stream().sorted().collect(Collectors.joining(",")))
                    .sorted().forEach(s -> canonical.append(s).append(';'));
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 요청 검사

    private static List<Item> validate(Request request, boolean apply) {
        if (request == null || request.items() == null || request.items().isEmpty()) throw invalid("이름을 바꿀 대상을 고르세요.");
        if (request.items().size() > MAX_ITEMS) throw invalid("한 번에 {0}개까지 적용할 수 있습니다. (요청 {1}개)", MAX_ITEMS, request.items().size());
        Set<String> seen = new HashSet<>();
        for (Item item : request.items()) {
            if (item == null || item.type() == null) throw invalid("대상 종류(TABLE·COLUMN)가 비어 있습니다.");
            if (item.id() == null) throw invalid("대상 ID 가 비어 있습니다.");
            if (item.recommendedName() == null || item.recommendedName().isBlank()) throw invalid("추천 이름이 비어 있습니다. ({0} ID {1})", item.type(), item.id());
            if (!seen.add(item.type() + ":" + item.id())) throw invalid("같은 대상이 두 번 들어 있습니다. ({0} ID {1})", item.type(), item.id());
        }
        if (apply) {
            if (request.planToken() == null || request.planToken().isBlank()) throw invalid("미리보기를 먼저 확인해주세요.");
            request.items().forEach(item -> ModelBase.require(true, item.baseModifiedAt()));
        }
        return request.items();
    }

    private static void requireFound(Set<Long> requested, Set<Long> found, TargetType type) {
        for (Long id : requested) {
            if (!found.contains(id)) {
                throw type == TargetType.TABLE ? new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED, "테이블을 찾을 수 없습니다. (ID {0})", id)
                        : new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED, "컬럼을 찾을 수 없습니다. (ID {0})", id);
            }
        }
    }

    private static Set<Long> ids(List<Item> items, TargetType type) {
        return items.stream().filter(i -> i.type() == type).map(Item::id).collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static String upper(String value) {
        return Objects.requireNonNullElse(value, "").trim().toUpperCase(Locale.ROOT);
    }

    /** NOWAIT 로 잠금을 바로 얻지 못했다 : PostgreSQL SQLSTATE 55P03, H2 오류 코드 50200 (PhysicalGenerationService 와 같은 판정) */
    private static boolean lockNotAvailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return "55P03".equals(sql.getSQLState()) || sql.getErrorCode() == 50200;
        }
        return false;
    }

    private static CustomException busy() {
        return new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("다른 사용자가 같은 모델·테이블·컬럼을 바꾸는 중입니다. 잠시 뒤 다시 미리보기해주세요."));
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, message, args);
    }
}
