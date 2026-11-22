package com.lexorma.data.sourcemapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectKind;
import com.lexorma.data.sourcemapping.domain.dto.SourceDeletionDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceDeletionDto.Blocker;
import com.lexorma.data.sourcemapping.domain.dto.SourceDeletionDto.Kind;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Status;
import com.lexorma.data.sourcemapping.domain.dto.SourceRenameDto;
import com.lexorma.data.sourcemapping.compare.SourceModelComparator;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import com.lexorma.data.view.repository.PhysicalViewRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 원본 삭제 후보 선택 반영 (DEC-04, docs/completed/source-sync-dec04-05-07.md). 원본에서 없어진 객체를 삭제 후보로 보이고, 사용자가 고르고 확인한 <b>미승인</b> 모델 객체만 지운다.
 * 자동 삭제·원본 DB 변경은 없다.
 * <ul>
 *   <li>후보 : TABLE = 원본 없음인 TABLE 매핑 중 이름 변경 후보(DEC-06)가 없는 것의 모델 TABLE, 컬럼 = 매핑됨 TABLE 의 M-4d 비교에서 모델에만 있는 컬럼(이름 변경 후보·확정된 컬럼 매핑 제외).
 *       기준은 마지막 반영(COMPLETED) 수집 — 실패·진행 중 수집은 반영되지 않아 삭제로 보지 않고, PARTIAL 수집의 범위 밖 객체도 원본 없음이 아니다 (M-4c 규칙)</li>
 *   <li>고를 수 없는 것 : 승인 객체·승인 컬럼이 있는 TABLE, 논리 매핑, VIEW 참조, 다른 TABLE 의 관계가 참조, 관계·인덱스·제약조건이 쓰는 컬럼 (참조 무결성을 자동으로 고치지 않는다)</li>
 *   <li>DEC-17 : 기준 수집이 직전 정상 수집보다 객체가 50% 미만으로 줄었으면 계획을 그대로 믿지 않는다 — 확정에 acknowledgeDrop 이 필요하다</li>
 *   <li>확정 : 프로젝트 관리자·ADMIN. 프로젝트 행(배타) → 원본 연결(공유) → 기준 연결 행 → 스키마 이름 공간 → TABLE 행 → 컬럼 행 (NOWAIT, 가져오기와 같은 순서) 을 잡은 뒤
 *       다시 계획해 planToken 이 같을 때만 반영한다. 반영 직전 프로젝트 전체 모델 버전(DELETE-요청 ID)을 남기고, 모델 삭제·매핑 해제·변경 이력이 한 트랜잭션이다 (실패 시 전체 롤백)</li>
 *   <li>삭제는 기존 컬럼 삭제·TABLE 삭제 서비스를 쓴다 (같은 검사·모델 변경 이력). TABLE 은 그 인덱스·제약조건·나가는 관계를 먼저 지운다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SourceDeletionService {

    /** 한 요청의 항목 수 */
    static final int MAX_ITEMS = 200;
    static final String VERSION_PREFIX = "DELETE-";
    private static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";
    private static final Map<String, Object> NO_WAIT = Map.of(LOCK_TIMEOUT, 0);

    private final SourceMappingService mappingService;
    private final SourceCompareService compareService;
    private final SourceRenameService renameService;
    private final SourceCatalogReader reader;
    private final ExecutionContextVerifier verifier;
    private final ProjectWriteGuard projectWriteGuard;
    private final com.lexorma.data.physical.sync.ModelRowLocks rowLocks;
    private final ProjectRepository projectRepository;
    private final PhysicalViewRepository viewRepository;
    private final ColumnManageService columnManageService;
    private final TableDesignService tableDesignService;
    private final ModelVersionService versionService;
    private final ModelChangeRecorder recorder;
    private final org.springframework.beans.factory.ObjectProvider<RelatedRecordCounter> relatedRecordCounters;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------ 조회 · 계획

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceDeletionDto.Candidates candidates(AccountDto accountDto) {
        Work work = work(accountDto, null, null);
        if (work == null) return new SourceDeletionDto.Candidates(null, null, false, 0, List.of());
        return new SourceDeletionDto.Candidates(work.connectionId, work.order, work.dropSuspected, work.renameCandidates, List.copyOf(work.items.values()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceDeletionDto.Plan plan(AccountDto accountDto, SourceDeletionDto.Request request) {
        verifier.verifyProjectManager(ExecutionContext.user(accountDto));
        Work work = work(accountDto, request == null ? null : request.connectionId(), request == null ? null : request.reflectedOrder());
        if (work == null) throw invalid("기준 연결과 완료된 수집이 있어야 원본 삭제를 반영할 수 있습니다.");
        return work.plan(selected(request), request.requestId() == null || request.requestId().isBlank() ? UUID.randomUUID().toString() : request.requestId());
    }

    // ------------------------------------------------------------------ 확정

    @Transactional
    public SourceDeletionDto.Result apply(AccountDto accountDto, SourceDeletionDto.Request request) {
        if (request == null || request.planToken() == null || request.requestId() == null || request.requestId().isBlank() || request.requestId().length() > 64) {
            throw invalid("미리보기에서 받은 계획으로 확정해주세요.");
        }
        Long projectId = verifier.verifyProjectManager(ExecutionContext.user(accountDto)).projectId();
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        projectWriteGuard.lock(projectId);
        Long base = mappingService.lockBase(projectId);
        if (!base.equals(request.connectionId())) throw stale();
        List<String> selected = selected(request);
        Work first = work(accountDto, request.connectionId(), request.reflectedOrder());
        if (first == null) throw stale();
        if (first.dropSuspected && !Boolean.TRUE.equals(request.acknowledgeDrop())) {
            throw invalid("기준 수집의 객체 수가 직전 정상 수집의 절반 미만입니다. 수집 결과를 확인한 뒤 급감 경고를 확인하고 다시 확정해주세요.");
        }
        // 잠글 대상 : 지울 TABLE 과 지울 컬럼의 TABLE, 그 스키마 (기본 스키마 = VIEW 참조 그래프 잠금과 같은 행)
        TreeSet<Long> tables = new TreeSet<>();
        for (String id : selected) {
            SourceDeletionDto.Candidate item = first.items.get(id);
            if (item != null) tables.add(item.tableId());
        }
        try {
            lockSchemas(first, tables);
            // 지울 행 : 키 강도(FOR UPDATE NOWAIT) 먼저 (M7-FK-D07). DELETE 는 PostgreSQL 에서 FOR UPDATE 강도라, 프로젝트 행을 쥔 채 다른 트랜잭션의 FK KEY SHARE
            // (그 컬럼·테이블을 대상으로 하는 관계 INSERT)를 기다리면 교착이다 — 물리명 일괄 적용(S6b)과 같은 구조. 바쁘면 다른 잠금 경합과 같은 409
            keyLockDeleted(projectId, selected, first);
            for (Long id : tables) entityManager.find(TableInfo.class, id, LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
            if (!tables.isEmpty()) {
                entityManager.createQuery("select c from ColumnInfo c where c.tableInfo.id in :ids order by c.id", ColumnInfo.class).setParameter("ids", tables)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint(LOCK_TIMEOUT, 0).getResultList();
            }
        } catch (jakarta.persistence.PessimisticLockException | jakarta.persistence.LockTimeoutException e) {
            if (!SourceImportService.lockNotAvailable(e)) throw e;
            throw new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "다른 사용자가 같은 모델을 바꾸는 중입니다. 잠시 뒤 비교 결과를 새로 고쳐 다시 확정해주세요.");
        } catch (CustomException e) {
            if (e.getErrorCode() != ErrorCode.MODEL_CONFLICT) throw e;
            throw new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "다른 사용자가 같은 모델을 바꾸는 중입니다. 잠시 뒤 비교 결과를 새로 고쳐 다시 확정해주세요.");
        }
        Work work = work(accountDto, request.connectionId(), request.reflectedOrder());
        if (work == null) throw stale();
        SourceDeletionDto.Plan plan = work.plan(selected, request.requestId());
        if (!plan.planToken().equals(request.planToken())) throw stale();
        if (!plan.applicable()) throw invalid("지울 수 없는 항목이 있습니다. 미리보기에서 확인해주세요.");

        ModelVersionDto.Summary version;
        try {
            version = versionService.createBeforeImport(projectId, VERSION_PREFIX + request.requestId(),
                    Messages.get("원본 삭제 반영 직전 상태 (기준 연결 #{0}, 반영 순서 {1}, 요청 {2})", work.connectionId, work.order, request.requestId()), accountDto.getUsername());
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "이미 적용된 요청입니다. 화면을 새로 고쳐주세요.");
        }
        recorder.markImport(request.requestId());
        int columns = 0;
        int deletedTables = 0;
        List<SourceDeletionDto.Candidate> items = plan.items();
        // 컬럼 먼저 (그 TABLE 을 함께 지우는 경우는 TABLE 쪽에서 지운다)
        Set<Long> tableItems = new HashSet<>();
        items.stream().filter(i -> i.kind() == Kind.TABLE).forEach(i -> tableItems.add(i.tableId()));
        List<ColumnInfoDto> columnDtos = items.stream().filter(i -> i.kind() == Kind.COLUMN && !tableItems.contains(i.tableId()))
                .map(i -> ColumnInfoDto.builder().id(i.columnId()).build()).toList();
        if (!columnDtos.isEmpty()) {
            columnManageService.deleteColumn(accountDto, columnDtos);
            columns += columnDtos.size();
        }
        for (SourceDeletionDto.Candidate item : items) {
            if (item.kind() != Kind.TABLE) continue;
            columns += deleteTable(accountDto, projectId, item.tableId());
            SourceTableMapping mapping = work.state.tableMappingById.get(item.tableMappingId());
            if (mapping != null) mappingService.releaseForDeletion(projectId, work.connectionId, mapping);
            deletedTables++;
        }
        entityManager.flush();
        return new SourceDeletionDto.Result(work.connectionId, work.order, request.requestId(), version.id(), version.versionName(), deletedTables, columns);
    }

    /** 지울 TABLE 행과 지울 컬럼 행(컬럼 항목 + 지울 TABLE 의 모든 컬럼)을 키 강도로 기다리지 않고 잠근다 */
    private void keyLockDeleted(Long projectId, List<String> selected, Work work) {
        TreeSet<Long> deletedTables = new TreeSet<>();
        TreeSet<Long> deletedColumns = new TreeSet<>();
        for (String id : selected) {
            SourceDeletionDto.Candidate item = work.items.get(id);
            if (item == null) continue;
            if (item.kind() == Kind.TABLE) deletedTables.add(item.tableId());
            else if (item.columnId() != null) deletedColumns.add(item.columnId());
        }
        if (!deletedTables.isEmpty()) {
            deletedColumns.addAll(entityManager.createQuery("select c.id from ColumnInfo c where c.projectId = :p and c.tableInfo.id in :t", Long.class)
                    .setParameter("p", projectId).setParameter("t", deletedTables).getResultList());
        }
        rowLocks.tablesForKeyChange(deletedTables);
        rowLocks.columnsForKeyChange(deletedColumns);
    }

    /** TABLE 하나 : 나가는 관계·제약조건·인덱스 → 컬럼 → TABLE. 들어오는 관계는 계획이 막았다. 지운 컬럼 수 */
    private int deleteTable(AccountDto accountDto, Long projectId, Long tableId) {
        for (TableRelation relation : entityManager.createQuery("select r from TableRelation r where r.projectId = :p and r.sourceTable.id = :t", TableRelation.class)
                .setParameter("p", projectId).setParameter("t", tableId).getResultList()) {
            entityManager.remove(relation);
        }
        for (TableConstraint constraint : entityManager.createQuery("select c from TableConstraint c where c.projectId = :p and c.tableInfo.id = :t", TableConstraint.class)
                .setParameter("p", projectId).setParameter("t", tableId).getResultList()) {
            entityManager.remove(constraint);
        }
        for (TableIndex index : entityManager.createQuery("select i from TableIndex i where i.projectId = :p and i.tableInfo.id = :t", TableIndex.class)
                .setParameter("p", projectId).setParameter("t", tableId).getResultList()) {
            entityManager.remove(index);
        }
        entityManager.flush();
        List<ColumnInfoDto> columns = entityManager.createQuery("select c.id from ColumnInfo c where c.projectId = :p and c.tableInfo.id = :t order by c.id", Long.class)
                .setParameter("p", projectId).setParameter("t", tableId).getResultList().stream().map(id -> ColumnInfoDto.builder().id(id).build()).toList();
        if (!columns.isEmpty()) columnManageService.deleteColumn(accountDto, columns);
        entityManager.flush();
        tableDesignService.deleteTable(List.of(TableInfoDto.builder().id(tableId).build()), accountDto);
        return columns.size();
    }

    private void lockSchemas(Work work, Set<Long> tableIds) {
        Set<Long> schemaIds = new TreeSet<>();
        for (Long id : tableIds) {
            SourceMappingService.ModelTable table = work.state.modelTables.get(id);
            if (table != null) schemaIds.add(table.schemaId());
        }
        Long defaultSchema = work.state.modelSchemas.stream().filter(s -> Boolean.TRUE.equals(s.getIsDefault())).map(SchemaInfo::getId).findFirst().orElse(null);
        List<Long> order = new ArrayList<>();
        if (defaultSchema != null) order.add(defaultSchema);
        schemaIds.stream().filter(id -> !id.equals(defaultSchema)).forEach(order::add);
        for (Long id : order) entityManager.find(SchemaInfo.class, id, LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
    }

    // ------------------------------------------------------------------ 계산

    /** 기준이 없으면 null. 요청이 본 기준(연결·반영 순서)과 다르면 409 */
    private Work work(AccountDto accountDto, Long expectedConnection, Integer expectedOrder) {
        Long projectId = accountDto.getProjectId();
        SourceMappingService.State state = mappingService.basis(projectId);
        if (state == null || state.connection == null || state.snapshot.reflectedOrder() == null) {
            if (expectedConnection != null) throw stale();
            return null;
        }
        Integer order = state.snapshot.reflectedOrder();
        if ((expectedConnection != null && !expectedConnection.equals(state.connection.id())) || (expectedOrder != null && !expectedOrder.equals(order))) throw stale();
        Work work = new Work(projectId, state);
        work.dropSuspected = reader.dropSuspected(projectId, state.connection.id(), order);
        work.collect(accountDto);
        return work;
    }

    private final class Work {
        final Long projectId;
        final SourceMappingService.State state;
        final Long connectionId;
        final Integer order;
        boolean dropSuspected;
        int renameCandidates;
        final Map<String, SourceDeletionDto.Candidate> items = new LinkedHashMap<>();

        Work(Long projectId, SourceMappingService.State state) {
            this.projectId = projectId;
            this.state = state;
            this.connectionId = state.connection.id();
            this.order = state.snapshot.reflectedOrder();
        }

        void collect(AccountDto accountDto) {
            SourceRenameDto.Candidates renames = renameService.candidates(accountDto);
            renameCandidates = renames.tables().size() + renames.columns().size();
            Map<Long, Long> tableMappingByModel = new HashMap<>();
            Map<Long, String> removedNames = new HashMap<>();
            for (SourceRenameDto.RemovedTable removed : renames.removedTables()) {
                if (removed.hasCandidate()) continue;
                tableMappingByModel.put(removed.tableId(), removed.mappingId());
                removedNames.put(removed.tableId(), removed.removedSourceTableName());
            }
            // 모델에만 있는 컬럼 : 매핑됨 TABLE 의 비교 결과 (이름 변경 후보는 RENAME_CANDIDATE 라 빠진다)
            Map<Long, Long> mappedTables = new HashMap<>();
            state.tableRows().forEach(r -> {
                if (r.status() == Status.MAPPED && r.tableId() != null) mappedTables.put(r.sourceTableId(), r.tableId());
            });
            Map<Long, Set<String>> onlyModel = new HashMap<>();
            for (Map.Entry<Long, SourceModelComparator.Result> e : compareService.results(projectId, connectionId, order).entrySet()) {
                Long tableId = mappedTables.get(e.getKey());
                if (tableId == null) continue;
                e.getValue().objects().stream().filter(o -> o.kind() == ObjectKind.COLUMN && o.sourceName() == null && o.status() == CompareStatus.ADDED_IN_MODEL)
                        .forEach(o -> onlyModel.computeIfAbsent(tableId, id -> new LinkedHashSet<>()).add(o.modelName()));
            }
            Set<Long> tableIds = new TreeSet<>(tableMappingByModel.keySet());
            tableIds.addAll(onlyModel.keySet());
            if (tableIds.isEmpty()) return;
            Model model = new Model(tableIds);
            for (Long tableId : tableMappingByModel.keySet().stream().sorted(Comparator.comparing(id -> model.tableName(id))).toList()) {
                items.put("TABLE:" + tableId, model.table(tableId, tableMappingByModel.get(tableId), removedNames.get(tableId)));
            }
            Map<Long, Long> mappingByModel = new HashMap<>();
            state.tableMappings.forEach(m -> {
                if (m.getTableId() != null) mappingByModel.put(m.getTableId(), m.getId());
            });
            for (Map.Entry<Long, Set<String>> e : onlyModel.entrySet()) {
                for (Object[] column : model.columnsOf(e.getKey())) {
                    if (!e.getValue().contains((String) column[2])) continue;
                    items.put("COLUMN:" + column[0], model.column(column, mappingByModel.get(e.getKey())));
                }
            }
        }

        SourceDeletionDto.Plan plan(List<String> selected, String requestId) {
            List<SourceDeletionDto.Candidate> planned = new ArrayList<>();
            for (String id : selected) {
                SourceDeletionDto.Candidate item = items.get(id);
                planned.add(item != null ? item : new SourceDeletionDto.Candidate(id, null, null, null, null, null, null, null, null, false, List.of(Blocker.NOT_FOUND),
                        List.of(), null));
            }
            boolean applicable = !planned.isEmpty() && planned.stream().allMatch(SourceDeletionDto.Candidate::selectable);
            return new SourceDeletionDto.Plan(connectionId, order, token(planned), requestId, applicable, dropSuspected, planned);
        }

        /** 계획의 지문 : 기준과 항목마다 대상·고를 수 있는지·이유·영향 */
        String token(List<SourceDeletionDto.Candidate> planned) {
            StringBuilder text = new StringBuilder().append(connectionId).append('|').append(order).append('|').append(dropSuspected);
            for (SourceDeletionDto.Candidate c : planned) {
                text.append('\n').append(c.id()).append('|').append(c.tableMappingId()).append('|').append(c.tableName()).append('|').append(c.columnName()).append('|')
                        .append(c.selectable()).append('|').append(c.blockers()).append('|').append(c.details()).append('|').append(c.impact());
            }
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 후보 TABLE 들의 모델 정보를 종류마다 한 번에 읽는다 */
        final class Model {
            final Map<Long, Object[]> tables = new HashMap<>();
            final Map<Long, List<Object[]>> columns = new HashMap<>();
            final Set<Long> entityMapped = new HashSet<>();
            final Set<Long> attributeMapped = new HashSet<>();
            final Map<Long, List<String>> relationUsers = new HashMap<>();
            final Map<Long, List<String>> incoming = new HashMap<>();
            final Map<Long, Integer> outgoing = new HashMap<>();
            final Map<Long, Long> derivedRelations = new HashMap<>();
            final Map<Long, Integer> indexes = new HashMap<>();
            final Map<Long, Integer> constraints = new HashMap<>();
            final Map<Long, List<String>> indexUsers = new HashMap<>();
            final Map<Long, List<String>> constraintUsers = new HashMap<>();
            final Map<Long, Integer> proposalsByTable = new HashMap<>();
            final Map<Long, Integer> proposalsByColumn = new HashMap<>();

            Model(Set<Long> tableIds) {
                for (Object[] row : list("select t.id, t.tableName, t.isApproval, t.bizDescription, t.schema.schemaName from TableInfo t where t.projectId = :p and t.id in :ids", tableIds)) {
                    tables.put((Long) row[0], row);
                }
                List<Long> columnIds = new ArrayList<>();
                for (Object[] row : list("select c.id, c.tableInfo.id, c.columnName, c.isApproval, c.bizDescription, st.id from ColumnInfo c left join c.standardTerms st "
                        + "where c.projectId = :p and c.tableInfo.id in :ids order by c.sortOrder, c.id", tableIds)) {
                    columns.computeIfAbsent((Long) row[1], id -> new ArrayList<>()).add(row);
                    columnIds.add((Long) row[0]);
                }
                for (Object id : entityManager.createQuery("select m.table.id from EntityTableMapping m where m.table.id in :ids").setParameter("ids", tableIds).getResultList()) {
                    entityMapped.add((Long) id);
                }
                for (Object[] row : list("select r.id, r.sourceTable.id, r.targetTable.id, r.relationName, r.relationSource, r.derivedColumnId, r.sourceTable.tableName from TableRelation r "
                        + "where r.projectId = :p and (r.sourceTable.id in :ids or r.targetTable.id in :ids)", tableIds)) {
                    Long source = (Long) row[1];
                    Long target = (Long) row[2];
                    if (tableIds.contains(source)) outgoing.merge(source, 1, Integer::sum);
                    if (tableIds.contains(target) && !Objects.equals(source, target)) incoming.computeIfAbsent(target, id -> new ArrayList<>()).add(row[6] + "." + row[3]);
                    if (row[5] != null) derivedRelations.put((Long) row[5], (Long) row[0]);
                }
                for (Object[] row : list("select i.tableInfo.id, count(i) from TableIndex i where i.projectId = :p and i.tableInfo.id in :ids group by i.tableInfo.id", tableIds)) {
                    indexes.put((Long) row[0], ((Number) row[1]).intValue());
                }
                for (Object[] row : list("select c.tableInfo.id, count(c) from TableConstraint c where c.projectId = :p and c.tableInfo.id in :ids group by c.tableInfo.id", tableIds)) {
                    constraints.put((Long) row[0], ((Number) row[1]).intValue());
                }
                // 다른 모듈이 이 TABLE·컬럼을 가리키는 기록 (lexorma-ai 의 AI 설명 제안) : 이 모듈은 그 모듈을 모른다 — 계약으로 받는다 (8차)
                relatedRecordCounters.orderedStream().forEach(counter -> {
                    RelatedRecordCounter.Counts counts = counter.count(projectId, tableIds);
                    counts.byTable().forEach((id, n) -> proposalsByTable.merge(id, n, Integer::sum));
                    counts.byColumn().forEach((id, n) -> proposalsByColumn.merge(id, n, Integer::sum));
                });
                if (columnIds.isEmpty()) return;
                for (Object id : entityManager.createQuery("select m.column.id from AttributeColumnMapping m where m.column.id in :ids").setParameter("ids", columnIds).getResultList()) {
                    attributeMapped.add((Long) id);
                }
                // 상속 PK 매핑에 쓰인 컬럼도 매핑된 컬럼이다 (M-7 3차 — 삭제 확정은 일반 컬럼 삭제가 같은 이유로 다시 거절한다)
                for (Object id : entityManager.createQuery("select m.column.id from SubtypeIdentifierColumnMapping m where m.column.id in :ids").setParameter("ids", columnIds).getResultList()) {
                    attributeMapped.add((Long) id);
                }
                for (Object[] row : entityManager.createQuery("select rc.sourceColumn.id, rc.targetColumn.id, r.relationName, r.relationSource, r.derivedColumnId from TableRelationColumn rc "
                        + "join rc.relation r where rc.sourceColumn.id in :ids or rc.targetColumn.id in :ids", Object[].class).setParameter("ids", columnIds).getResultList()) {
                    boolean derivedFromSource = row[3] == TableRelation.Source.REF_COLUMN && Objects.equals(row[4], row[0]);
                    if (!derivedFromSource) relationUsers.computeIfAbsent((Long) row[0], id -> new ArrayList<>()).add((String) row[2]);
                    relationUsers.computeIfAbsent((Long) row[1], id -> new ArrayList<>()).add((String) row[2]);
                }
                for (Object[] row : entityManager.createQuery("select ic.column.id, i.indexName, i.indexSource, i.derivedColumnId from TableIndexColumn ic join ic.tableIndex i "
                        + "where ic.column.id in :ids", Object[].class).setParameter("ids", columnIds).getResultList()) {
                    boolean derived = row[2] == TableIndex.Source.COLUMN && Objects.equals(row[3], row[0]);
                    if (!derived) indexUsers.computeIfAbsent((Long) row[0], id -> new ArrayList<>()).add((String) row[1]);
                }
                for (Object[] row : entityManager.createQuery("select cc.column.id, c.constraintName from TableConstraintColumn cc join cc.tableConstraint c where cc.column.id in :ids",
                        Object[].class).setParameter("ids", columnIds).getResultList()) {
                    constraintUsers.computeIfAbsent((Long) row[0], id -> new ArrayList<>()).add((String) row[1]);
                }
            }

            List<Object[]> list(String jpql, Set<Long> ids) {
                return entityManager.createQuery(jpql, Object[].class).setParameter("p", projectId).setParameter("ids", ids).getResultList();
            }

            String tableName(Long id) {
                Object[] row = tables.get(id);
                return row == null ? "" : (String) row[1];
            }

            List<Object[]> columnsOf(Long tableId) {
                return columns.getOrDefault(tableId, List.of());
            }

            SourceDeletionDto.Candidate table(Long tableId, Long mappingId, String sourceName) {
                Object[] row = tables.get(tableId);
                List<Blocker> blockers = new ArrayList<>();
                List<String> details = new ArrayList<>();
                if (Boolean.TRUE.equals(row[2])) blockers.add(Blocker.APPROVED);
                List<Object[]> cols = columnsOf(tableId);
                List<String> approved = cols.stream().filter(c -> Boolean.TRUE.equals(c[3])).map(c -> (String) c[2]).toList();
                if (!approved.isEmpty()) {
                    blockers.add(Blocker.APPROVED_COLUMN);
                    details.addAll(approved);
                }
                List<String> logical = cols.stream().filter(c -> attributeMapped.contains((Long) c[0])).map(c -> (String) c[2]).toList();
                if (entityMapped.contains(tableId) || !logical.isEmpty()) {
                    blockers.add(Blocker.LOGICAL_MAPPED);
                    details.addAll(logical);
                }
                List<String> views = viewRepository.findNamesReferencingTables(List.of(tableId));
                if (!views.isEmpty()) {
                    blockers.add(Blocker.VIEW_REFERENCE);
                    details.addAll(views);
                }
                List<String> referencing = incoming.getOrDefault(tableId, List.of());
                if (!referencing.isEmpty()) {
                    blockers.add(Blocker.REFERENCED);
                    details.addAll(referencing);
                }
                int bizDescriptions = (row[3] != null && !((String) row[3]).isBlank() ? 1 : 0)
                        + (int) cols.stream().filter(c -> c[4] != null && !((String) c[4]).isBlank()).count();
                int terms = (int) cols.stream().filter(c -> c[5] != null).count();
                SourceDeletionDto.Impact impact = new SourceDeletionDto.Impact(cols.size(), indexes.getOrDefault(tableId, 0), constraints.getOrDefault(tableId, 0),
                        outgoing.getOrDefault(tableId, 0), bizDescriptions, terms, proposalsByTable.getOrDefault(tableId, 0));
                return new SourceDeletionDto.Candidate("TABLE:" + tableId, Kind.TABLE, mappingId, tableId, (String) row[4], (String) row[1], null, null, sourceName,
                        blockers.isEmpty(), List.copyOf(blockers), List.copyOf(details), impact);
            }

            SourceDeletionDto.Candidate column(Object[] column, Long mappingId) {
                Long id = (Long) column[0];
                Long tableId = (Long) column[1];
                Object[] table = tables.get(tableId);
                List<Blocker> blockers = new ArrayList<>();
                List<String> details = new ArrayList<>();
                if (Boolean.TRUE.equals(column[3])) blockers.add(Blocker.APPROVED);
                if (attributeMapped.contains(id)) blockers.add(Blocker.LOGICAL_MAPPED);
                List<String> users = new ArrayList<>();
                users.addAll(relationUsers.getOrDefault(id, List.of()));
                users.addAll(indexUsers.getOrDefault(id, List.of()));
                users.addAll(constraintUsers.getOrDefault(id, List.of()));
                if (!users.isEmpty()) {
                    blockers.add(Blocker.IN_USE);
                    details.addAll(users.stream().distinct().toList());
                }
                SourceDeletionDto.Impact impact = new SourceDeletionDto.Impact(1, 0, 0, derivedRelations.containsKey(id) ? 1 : 0,
                        column[4] != null && !((String) column[4]).isBlank() ? 1 : 0, column[5] != null ? 1 : 0, proposalsByColumn.getOrDefault(id, 0));
                return new SourceDeletionDto.Candidate("COLUMN:" + id, Kind.COLUMN, mappingId, tableId, (String) table[4], (String) table[1], id, (String) column[2], null,
                        blockers.isEmpty(), List.copyOf(blockers), List.copyOf(details), impact);
            }
        }
    }

    private static List<String> selected(SourceDeletionDto.Request request) {
        List<String> items = request == null || request.items() == null ? List.of() : request.items().stream().filter(Objects::nonNull).distinct().toList();
        if (items.isEmpty()) throw invalid("지울 항목을 골라주세요.");
        if (items.size() > MAX_ITEMS) throw invalid("한 번에 {0}개까지 고를 수 있습니다.", MAX_ITEMS);
        return items;
    }

    private static CustomException stale() {
        return new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "기준 연결이 바뀌었거나 새 수집이 반영되었습니다. 화면을 새로 고쳐주세요.");
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.SOURCE_MAPPING_INVALID, message, args);
    }
}
