package com.lexorma.data.sourcemapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.core.source.SourceCatalogReader.NameKind;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.ObjectNamespace;
import com.lexorma.data.sourcemapping.compare.CompareModel;
import com.lexorma.data.sourcemapping.compare.PostgresCompareRules;
import com.lexorma.data.sourcemapping.compare.PostgresCompareRules.PgType;
import com.lexorma.data.sourcemapping.compare.SourceModelComparator;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.AttributeDiff;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.AttributeResult;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectDiff;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectKind;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Action;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Item;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Judgement;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Reason;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Status;
import com.lexorma.data.sourcemapping.domain.entity.SourceImportExecution;
import com.lexorma.data.sourcemapping.domain.entity.SourceSchemaMapping;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.sourcemapping.repository.SourceCompareModelRepository;
import com.lexorma.data.sourcemapping.repository.SourceImportExecutionRepository;
import com.lexorma.data.sourcemapping.repository.SourceTableMappingRepository;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.regex.Pattern;

/**
 * 원본 메타데이터 선택 가져오기 (M-4e, docs/source-import-m4e.md). 원본 DB 에 접속하지 않고 저장된 마지막 반영 수집을, 사용자가 고른 항목만 물리 모델에 반영한다.
 * <ul>
 *   <li>대상 = M-4d 비교 결과(SourceModelComparator) 그대로 : 원본에만 있는 객체는 추가, 다른 속성은 고른 속성만 수정. 판단 불가·이름 변경 후보·충돌·교체가 필요한 것·
 *       모델에만 있는 것은 고를 수 없다. 삭제·이름 변경·기존 객체 교체는 하지 않는다</li>
 *   <li>새 TABLE : 매핑된 원본 스키마의 미매핑 원본 TABLE 을 매핑된 모델 스키마에 만들고, 같은 트랜잭션에서 TABLE 매핑을 확정한다 (사용자가 TABLE:CREATE 를 고른 경우만)</li>
 *   <li>승인 : 만든 객체는 모두 미승인 (정책 5.4). 승인된 TABLE·컬럼의 속성은 바꾸지 않는다 (승인 취소 후 다시 가져오기) — 기존 승인 규칙과 같다</li>
 *   <li>원본 COMMENT 는 원본 COMMENT 칸(V3)에만 쓴다. 업무 설명·논리명은 바꾸지 않는다. 원본 COMMENT 가 없으면 기존 값을 지우지 않는다</li>
 *   <li>확정 : 프로젝트 관리자·ADMIN (USER 역할 이상). 잠금 프로젝트(<b>배타</b>) → 원본 연결(공유) → 기준 연결 행 → 새 TABLE 스키마의 이름 공간 → 대상 TABLE 행 → 그 컬럼 행 순서로 잡은 뒤
 *       계획을 다시 만들어 미리보기의 planToken 과 같을 때만 반영한다 (수집·매핑·모델이 바뀌었으면 SOURCE_COMPARE_STALE). 반영 직전에 프로젝트 전체 모델 버전을 남기고
 *       (프로젝트 배타 잠금이라 그동안 다른 쓰기가 커밋되지 않아 한 시점의 상태다), 모델 변경·매핑·변경 이력·버전·실행 이력이 한 트랜잭션이다</li>
 *   <li>모델이 표현하지 못하는 원본 속성(IDENTITY·생성 컬럼·NO ACTION 이 아닌 FK 규칙·모델 기본값으로 같은 식을 만들 수 없는 DEFAULT)이 있는 객체는 만들지 않는다
 *       (빼고 만들면 원본과 의미가 다르다). 기존 컬럼의 다른 속성(원본 COMMENT 등)은 그 속성과 무관하므로 고를 수 있다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SourceImportService {

    /** 한 요청의 TABLE 수 (측정 근거 : docs/source-import-m4e.md 9장) */
    static final int MAX_TABLES = 50;
    /** 가져오기 직전 모델 버전의 이름 앞부분 (뒤에 요청 식별자 : 요청마다 하나, 같은 요청은 다시 적용되지 않는다) */
    static final String VERSION_PREFIX = "IMPORT-";
    private static final int PAGE_MAX = 100;
    private static final Pattern OBJECT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,127}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> TYPE_ATTRIBUTES = Set.of("dataType", "length", "precision", "scale");

    private final SourceMappingService mappingService;
    private final SourceCatalogReader reader;
    private final SourceCompareModelRepository modelRepository;
    private final SourceTableMappingRepository tableMappingRepository;
    private final SourceImportExecutionRepository executionRepository;
    private final ModelChangeHistoryRepository historyRepository;
    private final ObjectNamespace objectNamespace;
    private final ExecutionContextVerifier verifier;
    private final ProjectWriteGuard projectWriteGuard;
    private final ModelChangeRecorder recorder;
    private final ModelVersionService versionService;
    private final ProjectRepository projectRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------ 조회 · 계획 (읽기 전용)

    /** 원본 TABLE 하나의 가져오기 대상 (고를 수 있는 항목과 고를 수 없는 이유) */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceImportDto.TableCandidates candidates(AccountDto accountDto, Long connectionId, Integer reflectedOrder, String key) {
        Work work = work(accountDto.getProjectId(), connectionId, reflectedOrder, List.of(new SourceImportDto.TableSelection(key, null, List.of())));
        TableWork table = work.tables.get(0);
        return new SourceImportDto.TableCandidates(work.connection.id(), work.order, work.snapshot.reflectedAt(), table.rowDto(), table.schemaName, table.modelName,
                table.create, table.blocked, work.baselines.last.get(table.key()), List.copyOf(table.candidates.values()));
    }

    /** 적용 계획 (저장하지 않는다). 확정할 때 쓸 requestId 를 새로 정해 준다 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceImportDto.Plan plan(AccountDto accountDto, SourceImportDto.Request request) {
        Work work = work(accountDto.getProjectId(), request.connectionId(), request.reflectedOrder(), selections(request));
        return work.plan(UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------------ 확정

    @Transactional
    public SourceImportDto.Result apply(AccountDto accountDto, SourceImportDto.Request request) {
        if (request == null || request.planToken() == null || request.requestId() == null || request.requestId().isBlank() || request.requestId().length() > 64) {
            throw invalid("미리보기에서 받은 계획으로 확정해주세요.");
        }
        Long projectId = verifier.verifyProjectManager(ExecutionContext.user(accountDto)).projectId();
        // 프로젝트 행 배타 잠금 (맨 먼저) : 가져오기 전 버전이 한 시점의 모델이 되고, 버전 만들기(배타 잠금)와 순서가 같아 교착이 없다
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        projectWriteGuard.lock(projectId);
        Long base = mappingService.lockBase(projectId);
        if (!base.equals(request.connectionId())) throw stale();
        if (executionRepository.existsByChangeGroupId(request.requestId())) throw new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "이미 적용된 가져오기입니다. 비교 결과를 새로 고쳐주세요.");

        // 잠글 대상을 정하고(새 TABLE 의 스키마, 바꾸거나 참조할 모델 TABLE) 잠근 뒤, 최신 상태로 계획을 다시 만든다
        Work first = work(projectId, request.connectionId(), request.reflectedOrder(), selections(request));
        TreeSet<Long> schemas = new TreeSet<>();
        TreeSet<Long> tables = new TreeSet<>();
        for (TableWork table : first.tables) {
            if (table.create && table.schemaId != null) schemas.add(table.schemaId);
            if (table.model != null && table.model.id() != null) tables.add(table.model.id());
        }
        tables.addAll(first.targetModelIds);
        // 프로젝트를 배타로 잡은 뒤의 행 잠금은 기다리지 않는다 (NOWAIT) : 일반 모델 저장은 행을 잠근 채 커밋 직전에 프로젝트 행을 공유 잠금으로 확인하므로,
        // 그 행을 기다리면 서로를 기다린다 (교착). 지금 다른 사용자가 같은 모델을 바꾸는 중이면 기다리지 않고 409 로 끝낸다 (그 저장은 그대로 커밋된다)
        try {
            if (!schemas.isEmpty()) lockSchemas(first, schemas);
            for (Long id : tables) entityManager.find(TableInfo.class, id, LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
            if (!tables.isEmpty()) {
                entityManager.createQuery("select c from ColumnInfo c where c.tableInfo.id in :ids order by c.id", ColumnInfo.class).setParameter("ids", tables)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE).setHint(LOCK_TIMEOUT, 0).getResultList();
            }
        } catch (jakarta.persistence.PessimisticLockException | jakarta.persistence.LockTimeoutException e) {
            if (!lockNotAvailable(e)) throw e; // 교착(40P01·H2 40001) 등 다른 오류는 409 로 바꾸지 않는다
            throw new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "다른 사용자가 같은 모델을 바꾸는 중입니다. 잠시 뒤 비교 결과를 새로 고쳐 다시 확정해주세요.");
        }
        Work work = work(projectId, request.connectionId(), request.reflectedOrder(), selections(request));
        SourceImportDto.Plan plan = work.plan(request.requestId());
        if (!plan.planToken().equals(request.planToken())) throw stale();
        if (!plan.applicable()) throw invalid("적용할 수 없는 항목이 있습니다. 미리보기에서 확인해주세요.");
        if (plan.tables().stream().allMatch(t -> t.items().isEmpty())) throw invalid("가져올 항목을 골라주세요.");

        // 가져오기 직전 모델 버전 (모델을 바꾸기 전, 같은 트랜잭션)
        ModelVersionDto.Summary version = versionService.createBeforeImport(projectId, VERSION_PREFIX + request.requestId(),
                Messages.get("원본 가져오기 직전 상태 (기준 연결 #{0}, 반영 순서 {1}, 요청 {2})", work.connection.id(), work.order, request.requestId()), accountDto.getUsername());
        recorder.markImport(request.requestId());
        work.apply();
        Map<String, Object> summary = summary(plan);
        summary.put("modelVersionId", version.id());
        summary.put("modelVersionName", version.versionName());
        SourceImportExecution execution = SourceImportExecution.builder().projectId(projectId).connectionId(work.connection.id()).executionId(work.snapshot.executionId())
                .reflectedOrder(work.order).changeGroupId(request.requestId()).summary(JSON.writeValueAsString(summary)).build();
        try {
            executionRepository.saveAndFlush(execution);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "이미 적용된 가져오기입니다. 비교 결과를 새로 고쳐주세요.");
        }
        return new SourceImportDto.Result(execution.getId(), request.requestId(), work.connection.id(), work.order, version.id(), version.versionName(), plan.counts(),
                plan.tables());
    }

    private static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";
    private static final Map<String, Object> NO_WAIT = Map.of(LOCK_TIMEOUT, 0);

    /** NOWAIT 로 잠금을 바로 얻지 못했다 : PostgreSQL SQLSTATE 55P03 (lock_not_available), H2 오류 코드 50200 (잠금 대기 시간 초과) */
    static boolean lockNotAvailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return "55P03".equals(sql.getSQLState()) || sql.getErrorCode() == 50200;
        }
        return false;
    }

    /** 새 TABLE 을 만들 스키마의 이름 공간 잠금 (ObjectNamespace 와 같은 행·순서 : 기본 스키마 → 나머지 ID 오름차순), 기다리지 않는다 */
    private void lockSchemas(Work work, Set<Long> schemaIds) {
        Long defaultSchema = work.state.modelSchemas.stream().filter(s -> Boolean.TRUE.equals(s.getIsDefault())).map(SchemaInfo::getId).findFirst().orElse(null);
        List<Long> order = new ArrayList<>();
        if (defaultSchema != null && schemaIds.contains(defaultSchema)) order.add(defaultSchema);
        schemaIds.stream().filter(id -> !id.equals(defaultSchema)).sorted().forEach(order::add);
        for (Long id : order) {
            if (entityManager.find(SchemaInfo.class, id, LockModeType.PESSIMISTIC_WRITE, NO_WAIT) == null) throw new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED);
        }
    }

    // ------------------------------------------------------------------ 이력

    @Transactional(readOnly = true)
    public SourceImportDto.ExecutionPage executions(AccountDto accountDto, int page, int size) {
        Page<SourceImportExecution> found = executionRepository.findAllByProjectIdOrderByIdDesc(accountDto.getProjectId(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), PAGE_MAX)));
        return new SourceImportDto.ExecutionPage(found.getContent().stream().map(SourceImportService::execution).toList(), found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public SourceImportDto.ExecutionDetail execution(AccountDto accountDto, Long id) {
        Long projectId = accountDto.getProjectId();
        SourceImportExecution execution = executionRepository.findByIdAndProjectId(id, projectId).orElseThrow(() -> invalid("가져오기 이력을 찾을 수 없습니다."));
        List<SourceImportDto.Change> changes = historyRepository.findAllByProjectIdAndChangeGroupIdOrderByIdAsc(projectId, execution.getChangeGroupId()).stream()
                .map(h -> new SourceImportDto.Change(h.getTargetType().name(), h.getTargetId(), h.getTargetName(), h.getAction().name(), h.getSource().name(),
                        h.getBeforeData(), h.getAfterData())).toList();
        return new SourceImportDto.ExecutionDetail(execution(execution), changes);
    }

    @SuppressWarnings("unchecked")
    private static SourceImportDto.Execution execution(SourceImportExecution e) {
        Map<String, Object> summary = e.getSummary() == null ? Map.of() : (Map<String, Object>) JSON.readValue(e.getSummary(), Map.class);
        return new SourceImportDto.Execution(e.getId(), e.getConnectionId(), e.getExecutionId(), e.getReflectedOrder(), e.getChangeGroupId(), summary, e.appliedBy(),
                e.appliedAt());
    }

    private static Map<String, Object> summary(SourceImportDto.Plan plan) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("counts", plan.counts());
        summary.put("tables", plan.tables().stream().map(t -> {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("key", t.key());
            table.put("sourceTable", t.sourceTableName());
            table.put("modelTable", t.modelSchemaName() + "." + t.modelTableName());
            table.put("create", t.create());
            table.put("items", t.items().stream().map(Item::id).toList());
            return table;
        }).toList());
        return summary;
    }

    // ------------------------------------------------------------------ 계산

    private static List<SourceImportDto.TableSelection> selections(SourceImportDto.Request request) {
        if (request == null || request.tables() == null || request.tables().isEmpty()) throw invalid("가져올 TABLE 을 골라주세요.");
        if (request.tables().size() > MAX_TABLES) throw invalid("한 번에 {0}개 TABLE 까지 가져올 수 있습니다.", MAX_TABLES);
        Set<String> keys = new LinkedHashSet<>();
        for (SourceImportDto.TableSelection selection : request.tables()) {
            if (selection == null || selection.key() == null || !keys.add(selection.key())) throw invalid("가져올 TABLE 을 골라주세요.");
        }
        return request.tables();
    }

    private Work work(Long projectId, Long expectedConnection, Integer expectedOrder, List<SourceImportDto.TableSelection> selections) {
        SourceMappingService.State state = mappingService.basis(projectId);
        if (state == null || state.connection == null) {
            if (expectedConnection != null) throw stale();
            throw invalid("가져오기 기준 연결을 먼저 정해주세요.");
        }
        Integer order = state.snapshot.reflectedOrder();
        if ((expectedConnection != null && !expectedConnection.equals(state.connection.id())) || (expectedOrder != null && !expectedOrder.equals(order))) throw stale();
        if (order == null) throw invalid("기준 연결에 완료된 수집이 없어 가져올 수 없습니다. 먼저 메타데이터를 수집해주세요.");
        Work work = new Work(projectId, state);
        work.prepare(selections);
        return work;
    }

    /** 한 요청의 계산 : 고른 TABLE 만 원본 상세·모델을 읽고 M-4d 비교로 대상을 만든다 */
    private final class Work {

        final Long projectId;
        final SourceMappingService.State state;
        final SourceCatalogReader.Connection connection;
        final SourceCatalogReader.Snapshot snapshot;
        final Integer order;
        final boolean supported;
        final List<TableWork> tables = new ArrayList<>();
        final Map<Long, TableWork> bySource = new HashMap<>();
        final Map<Long, Long> mappedModelBySource = new HashMap<>();
        final Map<String, Long> sourceByName = new HashMap<>();
        final Set<Long> targetModelIds = new TreeSet<>();
        Map<Long, CompareModel.Column> modelColumns = Map.of();
        Map<Long, CompareModel.Table> modelTables = Map.of();
        final Map<String, String> folded = new HashMap<>();
        Baselines baselines;

        Work(Long projectId, SourceMappingService.State state) {
            this.projectId = projectId;
            this.state = state;
            this.connection = state.connection;
            this.snapshot = state.snapshot;
            this.order = snapshot.reflectedOrder();
            this.supported = "POSTGRESQL".equals(connection.dbType());
        }

        String fold(String name) {
            return name == null ? null : folded.computeIfAbsent(name, n -> reader.compareName(connection, NameKind.COLUMN, n));
        }

        void prepare(List<SourceImportDto.TableSelection> selections) {
            Map<Long, SourceMappingDto.TableRow> rows = new HashMap<>();
            for (SourceMappingDto.TableRow row : state.tableRows()) {
                rows.put(row.sourceTableId(), row);
                if (row.status() == Status.MAPPED) mappedModelBySource.put(row.sourceTableId(), row.tableId());
            }
            for (SourceCatalogReader.Table table : snapshot.tables()) {
                SourceCatalogReader.Schema schema = state.sourceSchemas.get(table.schemaId());
                if (schema != null) sourceByName.put(schema.schemaName() + "\u0000" + table.name(), table.id());
            }
            for (SourceImportDto.TableSelection selection : selections) {
                Long id = selection.key().startsWith("S") ? number(selection.key().substring(1)) : null;
                SourceMappingDto.TableRow row = id == null ? null : rows.get(id);
                if (row == null) throw invalid("비교 대상에 없는 TABLE 입니다.");
                TableWork table = new TableWork(this, selection, row, state.sourceTables.get(id));
                tables.add(table);
                bySource.put(id, table);
            }
            Map<Long, SourceCatalogReader.TableDetail> details = reader.tableDetails(projectId, connection.id(), order, bySource.keySet());
            Set<Long> modelIds = new TreeSet<>();
            for (TableWork table : tables) {
                table.detail = details.get(table.row.sourceTableId());
                if (table.row.status() == Status.MAPPED && table.row.tableId() != null) modelIds.add(table.row.tableId());
                if (table.detail == null) continue;
                for (SourceCatalogReader.ForeignKey fk : table.detail.foreignKeys()) {
                    Long target = mappedModelBySource.get(sourceByName.get(fk.referencedSchema() + "\u0000" + fk.referencedTable()));
                    if (target != null) {
                        modelIds.add(target);
                        targetModelIds.add(target);
                    }
                }
            }
            SourceCompareModelRepository.Loaded loaded = modelRepository.load(projectId, modelIds);
            modelColumns = loaded.columns();
            modelTables = loaded.tables();
            baselines = new Baselines(projectId, connection.id(), tables.stream().map(TableWork::key).toList());
            List<String> views = new ArrayList<>();
            Map<Long, List<String>> viewNames = modelRepository.viewNames(projectId);
            for (TableWork table : tables) table.classify(viewNames.getOrDefault(table.schemaId, views));
            SourceModelComparator.Context context = context();
            for (TableWork table : tables) table.candidates(context);
        }

        SourceModelComparator.Context context() {
            return new SourceModelComparator.Context() {
                @Override
                public String fold(String modelName) {
                    return Work.this.fold(modelName);
                }

                @Override
                public CompareModel.Column modelColumn(Long id) {
                    return modelColumns.get(id);
                }

                @Override
                public String modelTableName(Long tableId) {
                    SourceMappingService.ModelTable table = tableId == null ? null : state.modelTables.get(tableId);
                    if (table == null) return null;
                    SchemaInfo schema = state.modelSchemaById.get(table.schemaId());
                    return (schema == null ? "?" : schema.getSchemaName()) + "." + table.name();
                }

                @Override
                public SourceModelComparator.Target resolve(SourceCatalogReader.ForeignKey foreignKey) {
                    Long target = mappedModelBySource.get(sourceByName.get(foreignKey.referencedSchema() + "\u0000" + foreignKey.referencedTable()));
                    return new SourceModelComparator.Target(target, target != null);
                }
            };
        }

        /** FK 참조 대상 : 매핑된 모델 TABLE 또는 이 요청에서 새로 만드는 TABLE */
        TableWork plannedTarget(SourceCatalogReader.ForeignKey fk) {
            Long source = sourceByName.get(fk.referencedSchema() + "\u0000" + fk.referencedTable());
            TableWork table = source == null ? null : bySource.get(source);
            return table != null && table.create && table.selected.contains("TABLE:CREATE") ? table : null;
        }

        SourceImportDto.Plan plan(String requestId) {
            List<SourceImportDto.PlanTable> planTables = new ArrayList<>();
            for (TableWork table : tables) planTables.add(table.plan());
            boolean applicable = planTables.stream().allMatch(t -> t.items().stream().allMatch(Item::selectable));
            int created = 0;
            int changed = 0;
            int columns = 0;
            int attributes = 0;
            int constraints = 0;
            int indexes = 0;
            int comments = 0;
            for (SourceImportDto.PlanTable table : planTables) {
                if (table.items().isEmpty()) continue;
                if (table.create()) created++;
                else changed++;
                for (Item item : table.items()) {
                    if (!item.selectable() || item.kind() == null) continue;
                    switch (item.kind()) {
                        case TABLE -> {
                            if ("comment".equals(item.attribute())) comments++;
                        }
                        case COLUMN -> {
                            if (item.action() == Action.ADD) columns++;
                            else if ("comment".equals(item.attribute())) comments++;
                            else attributes++;
                        }
                        case INDEX -> indexes++;
                        default -> constraints++;
                    }
                }
            }
            SourceImportDto.Counts counts = new SourceImportDto.Counts(created, changed, columns, attributes, constraints, indexes, comments);
            return new SourceImportDto.Plan(connection.id(), order, token(planTables), requestId, applicable, counts, planTables);
        }

        /** 계획의 지문 : 기준(연결·반영 순서)과 TABLE·항목마다 대상·변경 전후·적용 가능 여부 */
        String token(List<SourceImportDto.PlanTable> planTables) {
            StringBuilder text = new StringBuilder().append(connection.id()).append('|').append(order);
            for (SourceImportDto.PlanTable table : planTables) {
                text.append("\n#").append(table.key()).append('|').append(table.tableId()).append('|').append(table.modelSchemaName()).append('|').append(table.modelTableName())
                        .append('|').append(table.create());
                TableWork work = bySource.get(table.sourceTableId());
                if (work != null && work.model != null) text.append('|').append(work.model.approved()).append('|').append(work.row.mappingId());
                for (Item item : table.items()) {
                    text.append("\n").append(item.id()).append('|').append(item.selectable()).append('|').append(item.reason()).append('|').append(item.before()).append('|')
                            .append(item.after()).append('|').append(item.excluded()).append('|').append(item.dependsOn()).append('|').append(item.judgement()).append('|')
                            .append(item.baseline());
                }
            }
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 반영 : 새 TABLE·매핑 → 컬럼 → 컬럼 속성 → PK → UNIQUE·CHECK → 인덱스 → FK (FK 는 새 TABLE 이 모두 생긴 뒤) */
        void apply() {
            for (TableWork table : tables) table.applyTableAndColumns();
            for (TableWork table : tables) table.applyKeysAndIndexes();
            for (TableWork table : tables) table.applyForeignKeys();
            entityManager.flush();
        }
    }

    /**
     * 재가져오기 기준값 (M-4f, docs/source-reimport-m4f.md) : 저장된 실행 이력과 변경 이력만으로 만든다 (새 저장 구조 없음).
     * 같은 기준 연결의 실행을 최근 것부터 읽어, TABLE(key)·항목 ID 마다 그 항목을 고른 가장 최근 실행의 변경 그룹을 정하고, 그 그룹의 IMPORT 이력(변경 후 상태)을
     * 대상 ID 로 찾는다. 기준 연결이 바뀌었거나(다른 연결의 실행은 보지 않는다) 다시 매핑해 모델 객체가 다르면(그 ID 의 이력이 없다) 기준값이 없다.
     */
    private final class Baselines {

        private static final List<TargetType> TYPES = List.of(TargetType.PHYSICAL_TABLE, TargetType.PHYSICAL_COLUMN, TargetType.PHYSICAL_CONSTRAINT,
                TargetType.PHYSICAL_INDEX, TargetType.PHYSICAL_RELATION);

        /** key → 마지막 가져오기 */
        final Map<String, SourceImportDto.LastImport> last = new HashMap<>();
        /** key → 항목 ID → 그 항목을 고른 가장 최근 실행 (실행 ID, 변경 그룹) */
        private final Map<String, Map<String, Map.Entry<Long, String>>> latest = new HashMap<>();
        /** key → 그 TABLE 을 다룬 실행의 변경 그룹 */
        private final Map<String, Set<String>> groups = new HashMap<>();
        /** 변경 그룹 → 대상 종류 → 대상 ID → 변경 후 상태(JSON, 읽을 때 푼다) */
        private final Map<String, Map<TargetType, Map<Long, String>>> after = new HashMap<>();
        private final Map<String, Map<String, Object>> parsed = new HashMap<>();

        @SuppressWarnings("unchecked")
        Baselines(Long projectId, Long connectionId, List<String> keys) {
            Set<String> wanted = new LinkedHashSet<>(keys);
            for (SourceImportExecution execution : executionRepository.findAllByProjectIdAndConnectionIdOrderByIdDesc(projectId, connectionId)) {
                if (execution.getSummary() == null) continue;
                Map<String, Object> summary = (Map<String, Object>) JSON.readValue(execution.getSummary(), Map.class);
                Object tables = summary.get("tables");
                if (!(tables instanceof List<?> list)) continue;
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> table) || !wanted.contains(String.valueOf(table.get("key")))) continue;
                    String key = String.valueOf(table.get("key"));
                    last.putIfAbsent(key, new SourceImportDto.LastImport(execution.getId(), execution.getReflectedOrder(), execution.appliedBy(), execution.appliedAt(),
                            (String) summary.get("modelVersionName")));
                    groups.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(execution.getChangeGroupId());
                    Map<String, Map.Entry<Long, String>> byItem = latest.computeIfAbsent(key, k -> new HashMap<>());
                    if (table.get("items") instanceof List<?> items) {
                        for (Object item : items) byItem.putIfAbsent(String.valueOf(item), Map.entry(execution.getId(), execution.getChangeGroupId()));
                    }
                }
            }
            Set<String> all = new LinkedHashSet<>();
            groups.values().forEach(all::addAll);
            if (all.isEmpty()) return;
            for (ModelChangeHistory history : historyRepository.findAllByGroupsAndTypes(projectId, all, TYPES)) {
                if (history.getSource() != ModelChangeHistory.Source.IMPORT || history.getAfterData() == null) continue;
                after.computeIfAbsent(history.getChangeGroupId(), g -> new HashMap<>()).computeIfAbsent(history.getTargetType(), t -> new HashMap<>())
                        .put(history.getTargetId(), history.getAfterData());
            }
        }

        /** 기준값 : covering 항목 중 가장 최근에 고른 실행이 이 대상에 남긴 변경 후 상태. 없으면 null */
        @SuppressWarnings("unchecked")
        Map<String, Object> after(String key, List<String> covering, TargetType type, Long targetId) {
            Map<String, Map.Entry<Long, String>> byItem = latest.get(key);
            if (byItem == null || targetId == null) return null;
            Map.Entry<Long, String> chosen = covering.stream().map(byItem::get).filter(Objects::nonNull).max(Map.Entry.comparingByKey()).orElse(null);
            if (chosen == null) return null;
            String json = after.getOrDefault(chosen.getValue(), Map.of()).getOrDefault(type, Map.of()).get(targetId);
            return json == null ? null : parsed.computeIfAbsent(chosen.getValue() + "|" + type + "|" + targetId, k -> (Map<String, Object>) JSON.readValue(json, Map.class));
        }

        /** 이 TABLE 을 다룬 가져오기가 이 모델 객체를 만들거나 바꾼 적이 있는지 */
        boolean imported(String key, TargetType type, Long targetId) {
            for (String group : groups.getOrDefault(key, Set.of())) {
                if (after.getOrDefault(group, Map.of()).getOrDefault(type, Map.of()).containsKey(targetId)) return true;
            }
            return false;
        }
    }

    /** 원본 TABLE 하나 */
    private final class TableWork {

        final Work work;
        final SourceMappingDto.TableRow row;
        final SourceCatalogReader.Table source;
        final Set<String> selected = new LinkedHashSet<>();
        final String requestedName;
        SourceCatalogReader.TableDetail detail;
        CompareModel.Table model;
        boolean create;
        Reason blocked;
        Long schemaId;
        String schemaName;
        String modelName;
        SourceModelComparator.Result result;
        final Map<String, Item> candidates = new LinkedHashMap<>();
        /** 적용 때 : 원본 컬럼 비교 이름 → 모델 컬럼 엔티티 */
        final Map<String, ColumnInfo> columnEntities = new HashMap<>();
        TableInfo entity;

        TableWork(Work work, SourceImportDto.TableSelection selection, SourceMappingDto.TableRow row, SourceCatalogReader.Table source) {
            this.work = work;
            this.row = row;
            this.source = source;
            this.requestedName = selection.tableName() == null || selection.tableName().isBlank() ? null : selection.tableName().trim();
            if (selection.items() != null) selection.items().stream().filter(Objects::nonNull).forEach(selected::add);
        }

        String key() {
            return "S" + row.sourceTableId();
        }

        SourceCompareDto.TableRow rowDto() {
            return new SourceCompareDto.TableRow("S" + row.sourceTableId(), row.sourceTableId(), row.sourceSchemaId(), row.sourceSchemaName(), row.sourceTableName(),
                    row.sourceComment(), row.mappingId(), row.status(), row.reason(), row.tableId(), row.schemaName(), row.tableName(), row.tableKorName(), null, null, 0, 0,
                    Map.of());
        }

        /** 매핑 상태로 기존 TABLE 반영 / 새 TABLE 만들기 / 가져올 수 없음을 정한다 */
        void classify(List<String> viewNames) {
            if (!work.supported) {
                blocked = Reason.UNSUPPORTED_DBMS;
                return;
            }
            if (detail == null || source == null) {
                blocked = row.status() == Status.SOURCE_MISSING ? Reason.SOURCE_REMOVED : Reason.NOT_FOUND;
                return;
            }
            switch (row.status()) {
                case MAPPED -> {
                    model = work.modelTables.get(row.tableId());
                    if (model == null) blocked = Reason.MODEL_DELETED;
                    schemaName = row.schemaName();
                    modelName = row.tableName();
                }
                case UNMAPPED -> {
                    SourceSchemaMapping schemaMapping = work.state.schemaMappingBySource.get(row.sourceSchemaId());
                    if (schemaMapping == null || work.state.schemaStatus(schemaMapping) != Status.MAPPED) {
                        blocked = Reason.SCHEMA_UNMAPPED;
                        return;
                    }
                    if (row.candidate() != null) {
                        blocked = row.candidate().taken() ? Reason.NAME_TAKEN : Reason.MAPPING_CANDIDATE;
                        return;
                    }
                    create = true;
                    schemaId = schemaMapping.getSchemaId();
                    SchemaInfo schema = work.state.modelSchemaById.get(schemaId);
                    schemaName = schema == null ? null : schema.getSchemaName();
                    modelName = requestedName != null ? requestedName : modelName(source.name(), source.compareName(), NameKind.TABLE);
                    model = CompareModel.Table.empty(modelName);
                    if (modelName == null) {
                        blocked = Reason.QUOTED_NAME;
                    } else if (!OBJECT_NAME.matcher(modelName).matches()) {
                        blocked = Reason.INVALID_NAME;
                    } else if (!Objects.equals(reader.compareName(work.connection, NameKind.TABLE, modelName), source.compareName())) {
                        blocked = Reason.QUOTED_NAME; // 그 이름으로 만들면 원본과 다른 객체가 된다 (이름 변경은 하지 않는다)
                    } else if (objectNamespace.conflict(schemaId, modelName, null, null) == ObjectNamespace.Kind.TABLE) {
                        blocked = Reason.NAME_TAKEN;
                    } else if (objectNamespace.conflict(schemaId, modelName, null, null) == ObjectNamespace.Kind.VIEW
                            || viewNames.stream().anyMatch(v -> v.equalsIgnoreCase(modelName))) {
                        blocked = Reason.VIEW_NAME_TAKEN;
                    }
                }
                case RECHECK -> blocked = Reason.RECHECK;
                case CONFLICT -> blocked = Reason.MAPPING_CONFLICT;
                case SOURCE_MISSING -> blocked = Reason.SOURCE_REMOVED;
                default -> blocked = Reason.MODEL_DELETED;
            }
        }

        /** 원본 이름 → 모델 이름 : 따옴표 없이 쓴 이름이 원본과 같은 비교 이름이 되는 표기 (대문자 우선). 없으면 null (따옴표 식별자) */
        String modelName(String name, String compareName, NameKind kind) {
            if (name == null) return null;
            String upper = name.toUpperCase(Locale.ROOT);
            String target = compareName == null ? name : compareName;
            if (Objects.equals(reader.compareName(work.connection, kind, upper), target)) return upper;
            if (Objects.equals(reader.compareName(work.connection, kind, name), target)) return name;
            return null;
        }

        String objectName(String sourceName) {
            String name = modelName(sourceName, sourceName, NameKind.COLUMN);
            return name != null && OBJECT_NAME.matcher(name).matches() ? name : null;
        }

        // ---------------------------------------------------------- 대상

        void candidates(SourceModelComparator.Context context) {
            if (blocked != null) return;
            result = SourceModelComparator.compare(detail, source.name(), source.comment(), model, context);
            if (create) {
                candidates.put("TABLE:CREATE", new Item("TABLE:CREATE", ObjectKind.TABLE, source.name(), schemaName + "." + modelName, Action.CREATE, null, source.name(),
                        null, schemaName + "." + modelName, true, null, List.of(), List.of(), null, null));
            }
            Map<String, SourceCatalogReader.Column> columns = new HashMap<>();
            detail.columns().forEach(c -> columns.put(c.name(), c));
            for (ObjectDiff object : result.objects()) {
                if (object.sourceName() == null && object.kind() != ObjectKind.TABLE) {
                    removedInSource(object);
                    continue;
                }
                switch (object.kind()) {
                    case TABLE -> tableComment(object);
                    case COLUMN -> column(object, columns.get(object.sourceName()));
                    case PRIMARY_KEY -> primaryKey(object);
                    default -> constraintOrIndex(object);
                }
            }
        }

        void put(Item item) {
            candidates.put(item.id(), item);
        }

        Item item(String id, ObjectKind kind, String sourceName, String modelName, Action action, String attribute, String source, String before, String after,
                  Reason reason, List<String> excluded, List<String> dependsOn) {
            return new Item(id, kind, sourceName, modelName, action, attribute, source, before, after, reason == null, reason, excluded, dependsOn, null, null);
        }

        List<String> createDependency() {
            return create ? List.of("TABLE:CREATE") : List.of();
        }

        void tableComment(ObjectDiff object) {
            AttributeDiff comment = attribute(object, "comment");
            if (comment == null || comment.result() == AttributeResult.SAME) return;
            Reason reason = comment.source() == null ? Reason.EMPTY_COMMENT : model.approved() ? Reason.APPROVED : null;
            Item item = item("TABLE:COMMENT", ObjectKind.TABLE, source.name(), modelName, Action.UPDATE, "comment", comment.source(), comment.model(), comment.source(), reason,
                    List.of(), createDependency());
            if (!create) {
                Map<String, Object> b = work.baselines.after(key(), List.of("TABLE:COMMENT"), TargetType.PHYSICAL_TABLE, model.id());
                item = judge(item, b == null ? NO_VALUE : (String) b.get("sourceComment"), model.sourceComment(), source.comment());
            }
            put(item);
        }

        void column(ObjectDiff object, SourceCatalogReader.Column column) {
            if (object.sourceName() == null || column == null) return; // 모델에만 있는 컬럼 : 지우지 않는다
            String prefix = "COLUMN:" + column.name() + ":";
            CompareModel.Column modelColumn = modelColumn(column.compareName());
            if (object.status() == CompareStatus.ADDED_IN_SOURCE || object.status() == CompareStatus.RENAME_CANDIDATE || object.status() == CompareStatus.CONFLICT) {
                Converted converted = convert(column);
                String name = modelName(column.name(), column.compareName(), NameKind.COLUMN);
                Reason reason = object.status() == CompareStatus.RENAME_CANDIDATE ? Reason.RENAME_CANDIDATE : object.status() == CompareStatus.CONFLICT ? Reason.CONFLICT
                        : name == null ? Reason.QUOTED_NAME : !OBJECT_NAME.matcher(name).matches() ? Reason.INVALID_NAME : converted.type == null ? Reason.TYPE_NOT_REPRESENTABLE
                        : !converted.excluded.isEmpty() ? Reason.UNSUPPORTED_ATTRIBUTE : null;
                put(item(prefix + "ADD", ObjectKind.COLUMN, column.name(), name, Action.ADD, null, describe(column), null,
                        converted.type == null ? null : converted.describe(column.nullable()), reason, converted.excluded, createDependency()));
                if (column.comment() != null) {
                    put(item(prefix + "COMMENT", ObjectKind.COLUMN, column.name(), name, Action.UPDATE, "comment", column.comment(), null, column.comment(),
                            reason, List.of(), List.of(prefix + "ADD")));
                }
                return;
            }
            if (modelColumn == null) return;
            Reason approved = modelColumn.approved() ? Reason.APPROVED : null;
            List<AttributeDiff> attributes = object.attributes();
            // 타입 (타입·길이·정밀도·소수를 함께)
            List<AttributeDiff> typeAttributes = attributes.stream().filter(a -> TYPE_ATTRIBUTES.contains(a.attribute())).toList();
            if (typeAttributes.stream().anyMatch(a -> a.result() == AttributeResult.DIFFERENT || a.result() == AttributeResult.UNKNOWN)) {
                Converted converted = convert(column);
                Reason reason = typeAttributes.stream().anyMatch(a -> a.result() == AttributeResult.UNKNOWN) ? Reason.UNKNOWN
                        : converted.type == null ? Reason.TYPE_NOT_REPRESENTABLE : approved;
                Item item = item(prefix + "TYPE", ObjectKind.COLUMN, column.name(), modelColumn.name(), Action.UPDATE, "dataType", column.dataType(), modelType(modelColumn),
                        converted.type == null ? null : converted.type.text(), reason, List.of(), List.of());
                Map<String, Object> b = baseline(column, modelColumn, "TYPE");
                put(judge(item, b == null ? NO_VALUE : typeText(b), modelType(modelColumn), converted.type == null ? UNREPRESENTABLE : typeText(converted.type)));
            }
            AttributeDiff nullable = attribute(object, "nullable");
            if (nullable != null && nullable.result() != AttributeResult.SAME) {
                Reason reason = nullable.result() == AttributeResult.UNKNOWN ? Reason.UNKNOWN
                        : Boolean.TRUE.equals(column.nullable()) && Boolean.TRUE.equals(modelColumn.pk()) ? Reason.PK_NOT_NULL : approved;
                Item item = item(prefix + "NULLABLE", ObjectKind.COLUMN, column.name(), modelColumn.name(), Action.UPDATE, "nullable", nullable.source(), nullable.model(),
                        nullable.source(), reason, List.of(), List.of());
                Map<String, Object> b = baseline(column, modelColumn, "NULLABLE");
                put(judge(item, b == null ? NO_VALUE : nullText(b.get("isNullable"), b.get("isPk"), b.get("isRequired")),
                        nullText(modelColumn.nullable(), modelColumn.pk(), modelColumn.required()), column.nullable() == null ? UNREPRESENTABLE : nullText(column.nullable(), false, false)));
            }
            AttributeDiff defaultValue = attribute(object, "default");
            if (defaultValue != null && defaultValue.result() != AttributeResult.SAME) {
                Converted converted = convert(column);
                Reason reason = defaultValue.result() == AttributeResult.UNKNOWN ? Reason.UNKNOWN
                        : column.defaultValue() != null && converted.defaultValue == null ? Reason.DEFAULT_NOT_REPRESENTABLE : approved;
                Item item = item(prefix + "DEFAULT", ObjectKind.COLUMN, column.name(), modelColumn.name(), Action.UPDATE, "default", column.defaultValue(), modelColumn.defaultValue(),
                        converted.defaultValue, reason, List.of(), List.of());
                Map<String, Object> b = baseline(column, modelColumn, "DEFAULT");
                put(judge(item, b == null ? NO_VALUE : (String) b.get("defaultValue"), modelColumn.defaultValue(),
                        column.defaultValue() != null && converted.defaultValue == null ? UNREPRESENTABLE : converted.defaultValue));
            }
            AttributeDiff comment = attribute(object, "comment");
            if (comment != null && comment.result() != AttributeResult.SAME) {
                Reason reason = comment.source() == null ? Reason.EMPTY_COMMENT : approved;
                Item item = item(prefix + "COMMENT", ObjectKind.COLUMN, column.name(), modelColumn.name(), Action.UPDATE, "comment", comment.source(), comment.model(), comment.source(),
                        reason, List.of(), List.of());
                Map<String, Object> b = baseline(column, modelColumn, "COMMENT");
                put(judge(item, b == null ? NO_VALUE : (String) b.get("sourceComment"), modelColumn.sourceComment(), column.comment()));
            }
            AttributeDiff auto = attribute(object, "autoIncrement");
            if (auto != null && auto.result() != AttributeResult.SAME) {
                put(item(prefix + "AUTO_INCREMENT", ObjectKind.COLUMN, column.name(), modelColumn.name(), Action.UPDATE, "autoIncrement", auto.source(), auto.model(),
                        null, Reason.NO_FIELD, List.of(), List.of()));
            }
            AttributeDiff order = attribute(object, "order");
            if (order != null && order.result() == AttributeResult.DIFFERENT) {
                put(item(prefix + "ORDER", ObjectKind.COLUMN, column.name(), modelColumn.name(), Action.UPDATE, "order", order.source(), order.model(), null,
                        Reason.ORDER_NOT_SUPPORTED, List.of(), List.of()));
            }
        }

        void primaryKey(ObjectDiff object) {
            if (object.sourceName() == null) return;
            SourceCatalogReader.Constraint pk = detail.constraints().stream().filter(c -> "PRIMARY_KEY".equals(c.type())).findFirst().orElse(null);
            if (pk == null) return;
            String id = "PRIMARY_KEY:" + pk.name();
            if (object.status() == CompareStatus.SAME) return;
            if (object.status() != CompareStatus.ADDED_IN_SOURCE) {
                put(item(id, ObjectKind.PRIMARY_KEY, pk.name(), object.modelName(), Action.ADD, null, String.join(", ", pk.columns()), null, null,
                        object.status() == CompareStatus.UNKNOWN ? Reason.UNKNOWN : Reason.REPLACE_REQUIRED, List.of(), List.of()));
                return;
            }
            Reason reason = pk.columns().stream().map(this::modelColumn).anyMatch(c -> c != null && c.approved()) ? Reason.APPROVED : null;
            put(item(id, ObjectKind.PRIMARY_KEY, pk.name(), "PK_" + modelName, Action.ADD, null, String.join(", ", pk.columns()), null,
                    String.join(", ", pk.columns()), reason, List.of(), columnDependencies(pk.columns())));
        }

        void constraintOrIndex(ObjectDiff object) {
            if (object.sourceName() == null) return; // 모델에만 있는 객체 : 지우지 않는다
            String id = object.kind().name() + ":" + object.sourceName();
            if (object.status() == CompareStatus.SAME) return;
            String sourceText = text(object.attributes(), true);
            if (object.status() != CompareStatus.ADDED_IN_SOURCE) {
                Reason reason = switch (object.status()) {
                    case RENAME_CANDIDATE -> Reason.RENAME_CANDIDATE;
                    case UNKNOWN -> Reason.UNKNOWN;
                    case CONFLICT -> Reason.CONFLICT;
                    default -> Reason.REPLACE_REQUIRED;
                };
                put(item(id, object.kind(), object.sourceName(), object.modelName(), Action.ADD, null, sourceText, null, null, reason, List.of(), List.of()));
                return;
            }
            String name = objectName(object.sourceName());
            Reason reason = name == null ? Reason.QUOTED_NAME : null;
            List<String> excluded = new ArrayList<>();
            List<String> dependencies;
            switch (object.kind()) {
                case UNIQUE, CHECK -> {
                    SourceCatalogReader.Constraint constraint = detail.constraints().stream().filter(c -> object.sourceName().equals(c.name())).findFirst().orElseThrow();
                    dependencies = columnDependencies(constraint.columns());
                }
                case INDEX -> {
                    SourceCatalogReader.Index index = detail.indexes().stream().filter(i -> object.sourceName().equals(i.name())).findFirst().orElseThrow();
                    dependencies = columnDependencies(index.columns().stream().filter(Objects::nonNull).toList());
                    String type = index.indexType() == null ? "BTREE" : index.indexType().toUpperCase(Locale.ROOT);
                    if (reason == null && !TableDdlGenerator.supportedIndexTypes(DbType.POSTGRESQL).contains(type)) reason = Reason.UNSUPPORTED_INDEX_TYPE;
                }
                default -> {
                    SourceCatalogReader.ForeignKey fk = detail.foreignKeys().stream().filter(f -> object.sourceName().equals(f.name())).findFirst().orElseThrow();
                    dependencies = new ArrayList<>(columnDependencies(fk.columns()));
                    if (fk.deleteRule() != null && !"NO ACTION".equals(fk.deleteRule())) excluded.add("ON DELETE " + fk.deleteRule());
                    if (fk.updateRule() != null && !"NO ACTION".equals(fk.updateRule())) excluded.add("ON UPDATE " + fk.updateRule());
                    // 모델 FK 는 규칙이 없다 (DDL 은 NO ACTION) : 다른 규칙을 빼고 만들면 삭제·갱신 동작이 달라진다
                    if (reason == null && !excluded.isEmpty()) reason = Reason.UNSUPPORTED_ATTRIBUTE;
                    Long target = work.mappedModelBySource.get(work.sourceByName.get(fk.referencedSchema() + "\u0000" + fk.referencedTable()));
                    Long targetSource = work.sourceByName.get(fk.referencedSchema() + "\u0000" + fk.referencedTable());
                    if (target == null) {
                        if (targetSource != null && work.bySource.containsKey(targetSource)) dependencies.add("S" + targetSource + "/TABLE:CREATE");
                        else if (reason == null) reason = Reason.UNRESOLVED_TARGET;
                    }
                }
            }
            put(item(id, object.kind(), object.sourceName(), name, Action.ADD, null, sourceText, null, sourceText, reason, List.copyOf(excluded), dependencies));
        }

        /** 컬럼 속성의 기준값 B : 그 속성을 쓴 가장 최근 가져오기(그 속성 항목 또는 컬럼 추가)가 이 모델 컬럼에 남긴 변경 후 상태 */
        Map<String, Object> baseline(SourceCatalogReader.Column column, CompareModel.Column modelColumn, String attribute) {
            String prefix = "COLUMN:" + column.name() + ":";
            List<String> covering = "COMMENT".equals(attribute) ? List.of(prefix + "COMMENT") : List.of(prefix + attribute, prefix + "ADD");
            return work.baselines.after(key(), covering, TargetType.PHYSICAL_COLUMN, modelColumn.id());
        }

        /**
         * M-4f 판정 : 값은 모두 모델 표현 (b : 기준값, NO_VALUE 면 없음 / m : 지금 모델 / s : 지금 원본을 모델로 바꾼 값, UNREPRESENTABLE 이면 바꿀 수 없음).
         * 이미 같으면 SAME (실제 변경 없음). 기준값이 있고 모델이 그 뒤 바뀌었으면 고를 수 없다 (MODEL_EDITED·BOTH_CHANGED). 다른 이유(승인 등)가 있으면 그 이유가 앞선다
         */
        Item judge(Item item, String b, String m, String s) {
            Judgement judgement;
            if (NO_VALUE.equals(b)) {
                judgement = Judgement.NO_BASELINE;
            } else {
                boolean modelKept = Objects.equals(m, b);
                boolean sourceChanged = !Objects.equals(s, b);
                judgement = modelKept && sourceChanged ? Judgement.SOURCE_CHANGED : !modelKept && !sourceChanged ? Judgement.MODEL_EDITED
                        : !modelKept ? Judgement.BOTH_CHANGED : Judgement.NO_BASELINE; // 셋이 같은데 비교가 다르다고 본다 : 기준값으로 판단하지 않는다
            }
            Reason reason = item.reason();
            if (reason == null && !UNREPRESENTABLE.equals(s) && Objects.equals(s, m)) reason = Reason.SAME; // 반영해도 바뀌는 것이 없다
            if (reason == null && judgement == Judgement.MODEL_EDITED) reason = Reason.MODEL_EDITED;
            if (reason == null && judgement == Judgement.BOTH_CHANGED) reason = Reason.BOTH_CHANGED;
            return new Item(item.id(), item.kind(), item.sourceName(), item.modelName(), item.action(), item.attribute(), item.source(), item.before(), item.after(),
                    reason == null, reason, item.excluded(), item.dependsOn(), NO_VALUE.equals(b) ? null : b, judgement);
        }

        /** 모델에만 있는 객체 중 전에 이 TABLE 의 가져오기가 만들거나 바꾼 것 = 원본에서 없어졌다 (알림만, 지우지 않는다) */
        void removedInSource(ObjectDiff object) {
            if (create || model == null || object.modelName() == null) return;
            String name = object.modelName();
            Long id = null;
            TargetType type = null;
            switch (object.kind()) {
                case COLUMN -> {
                    type = TargetType.PHYSICAL_COLUMN;
                    id = model.columns().stream().filter(c -> name.equals(c.name())).map(CompareModel.Column::id).findFirst().orElse(null);
                }
                case UNIQUE, CHECK -> {
                    type = TargetType.PHYSICAL_CONSTRAINT;
                    id = model.constraints().stream().filter(c -> name.equals(c.name())).map(CompareModel.Constraint::id).findFirst().orElse(null);
                }
                case INDEX -> {
                    type = TargetType.PHYSICAL_INDEX;
                    id = model.indexes().stream().filter(c -> name.equals(c.name())).map(CompareModel.Index::id).findFirst().orElse(null);
                }
                case FOREIGN_KEY -> {
                    type = TargetType.PHYSICAL_RELATION;
                    id = model.relations().stream().filter(c -> name.equals(c.name())).map(CompareModel.Relation::id).findFirst().orElse(null);
                }
                default -> {
                }
            }
            if (id == null || !work.baselines.imported(key(), type, id)) return;
            put(new Item(object.kind().name() + ":" + name + ":REMOVED", object.kind(), null, name, null, null, null, null, null, false, Reason.REMOVED_IN_SOURCE,
                    List.of(), List.of(), null, null));
        }

        List<String> columnDependencies(List<String> sourceColumns) {
            List<String> dependencies = new ArrayList<>(createDependency());
            for (String compareName : sourceColumns) {
                if (modelColumn(compareName) != null) continue;
                SourceCatalogReader.Column column = detail.columns().stream().filter(c -> c.compareName().equals(compareName)).findFirst().orElse(null);
                dependencies.add("COLUMN:" + (column == null ? compareName : column.name()) + ":ADD");
            }
            return List.copyOf(dependencies);
        }

        CompareModel.Column modelColumn(String compareName) {
            if (model == null) return null;
            return model.columns().stream().filter(c -> Objects.equals(work.fold(c.name()), compareName)).findFirst().orElse(null);
        }

        // ---------------------------------------------------------- 계획

        SourceImportDto.PlanTable plan() {
            List<Item> items = new ArrayList<>();
            for (String id : selected) {
                Item candidate = blocked != null ? null : candidates.get(id);
                if (candidate == null) {
                    items.add(new Item(id, null, null, null, null, null, null, null, null, false, blocked != null ? blocked : Reason.NOT_FOUND, List.of(), List.of(), null, null));
                    continue;
                }
                if (!candidate.selectable()) {
                    items.add(candidate);
                    continue;
                }
                List<String> missing = candidate.dependsOn().stream().filter(d -> !satisfied(d)).toList();
                Reason reason = !missing.isEmpty() ? Reason.MISSING_DEPENDENCY : extraCheck(candidate);
                items.add(reason == null ? candidate : new Item(candidate.id(), candidate.kind(), candidate.sourceName(), candidate.modelName(), candidate.action(),
                        candidate.attribute(), candidate.source(), candidate.before(), candidate.after(), false, reason, candidate.excluded(), missing.isEmpty() ? candidate.dependsOn() : missing,
                        candidate.baseline(), candidate.judgement()));
            }
            return new SourceImportDto.PlanTable("S" + row.sourceTableId(), row.sourceTableId(), row.sourceSchemaName() + "." + row.sourceTableName(),
                    model == null ? null : model.id(), schemaName, modelName, create, List.copyOf(items));
        }

        /** 의존 항목이 모델에 이미 있거나 함께 골랐는지 ("S12/항목" 은 다른 TABLE 의 항목) */
        boolean satisfied(String dependency) {
            int slash = dependency.indexOf('/');
            if (slash < 0) return selected.contains(dependency) && candidates.containsKey(dependency) && candidates.get(dependency).selectable();
            TableWork other = work.bySource.get(number(dependency.substring(1, slash)));
            String id = dependency.substring(slash + 1);
            return other != null && other.blocked == null && other.selected.contains(id) && other.candidates.containsKey(id);
        }

        /** 고른 항목끼리의 조건 : PK 순서, FK 참조 대상의 키 */
        Reason extraCheck(Item item) {
            if (item.kind() == ObjectKind.PRIMARY_KEY) {
                SourceCatalogReader.Constraint pk = detail.constraints().stream().filter(c -> "PRIMARY_KEY".equals(c.type())).findFirst().orElseThrow();
                List<String> planned = plannedColumnOrder();
                List<String> ordered = planned.stream().filter(pk.columns()::contains).toList();
                return ordered.equals(pk.columns()) ? null : Reason.PK_ORDER;
            }
            if (item.kind() == ObjectKind.FOREIGN_KEY) {
                SourceCatalogReader.ForeignKey fk = detail.foreignKeys().stream().filter(f -> item.sourceName().equals(f.name())).findFirst().orElseThrow();
                Set<String> wanted = Set.copyOf(fk.referencedColumns());
                Long target = work.mappedModelBySource.get(work.sourceByName.get(fk.referencedSchema() + "\u0000" + fk.referencedTable()));
                TableWork planned = work.plannedTarget(fk);
                List<Set<String>> keys = target != null ? existingKeys(work.modelTables.get(target)) : new ArrayList<>();
                TableWork targetWork = planned != null ? planned : target == null ? null : work.tables.stream().filter(t -> t.model != null && target.equals(t.model.id()))
                        .findFirst().orElse(null);
                if (targetWork != null) keys.addAll(targetWork.selectedKeys());
                if (target == null && planned == null) return Reason.UNRESOLVED_TARGET;
                return keys.stream().anyMatch(k -> k.equals(wanted)) ? null : Reason.TARGET_NOT_KEY;
            }
            return null;
        }

        /** 적용 후 모델 컬럼 순서 (원본 비교 이름) : 기존 컬럼은 그 순서 그대로, 새 컬럼은 원본 순서로 뒤에 */
        List<String> plannedColumnOrder() {
            List<String> order = new ArrayList<>();
            model.columns().stream().sorted(Comparator.comparing(CompareModel.Column::sortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(CompareModel.Column::id)).forEach(c -> order.add(work.fold(c.name())));
            detail.columns().stream().sorted(Comparator.comparing(SourceCatalogReader.Column::ordinal, Comparator.nullsLast(Comparator.naturalOrder())))
                    .filter(c -> selected.contains("COLUMN:" + c.name() + ":ADD")).forEach(c -> order.add(c.compareName()));
            return order;
        }

        /** 이 요청에서 이 TABLE 에 생기는 키 (PK·UNIQUE) */
        List<Set<String>> selectedKeys() {
            List<Set<String>> keys = new ArrayList<>();
            for (SourceCatalogReader.Constraint constraint : detail.constraints()) {
                String id = "PRIMARY_KEY".equals(constraint.type()) ? "PRIMARY_KEY:" + constraint.name() : "UNIQUE".equals(constraint.type()) ? "UNIQUE:" + constraint.name() : null;
                if (id != null && selected.contains(id) && candidates.containsKey(id)) keys.add(Set.copyOf(constraint.columns()));
            }
            return keys;
        }

        /** 모델 TABLE 의 지금 키 (원본 비교 이름) : PK, 고유값 컬럼, UNIQUE 제약조건, 컬럼만으로 된 조건 없는 고유 인덱스 */
        List<Set<String>> existingKeys(CompareModel.Table table) {
            List<Set<String>> keys = new ArrayList<>();
            if (table == null) return keys;
            Set<String> pk = new LinkedHashSet<>();
            for (CompareModel.Column column : table.columns()) {
                if (Boolean.TRUE.equals(column.pk())) pk.add(work.fold(column.name()));
                if (Boolean.TRUE.equals(column.unique())) keys.add(Set.of(work.fold(column.name())));
            }
            if (!pk.isEmpty()) keys.add(pk);
            for (CompareModel.Constraint constraint : table.constraints()) {
                if (!"UNIQUE".equals(constraint.type())) continue;
                keys.add(constraint.columnIds().stream().map(id -> work.fold(work.modelColumns.get(id).name())).collect(java.util.stream.Collectors.toSet()));
            }
            for (CompareModel.Index index : table.indexes()) {
                if (!index.unique() || index.condition() != null || index.members().stream().anyMatch(m -> m.columnId() == null || "INCLUDE".equals(m.sort()))) continue;
                keys.add(index.members().stream().map(m -> work.fold(work.modelColumns.get(m.columnId()).name())).collect(java.util.stream.Collectors.toSet()));
            }
            return keys;
        }

        // ---------------------------------------------------------- 반영

        boolean chosen(String id) {
            return blocked == null && selected.contains(id) && candidates.containsKey(id);
        }

        void applyTableAndColumns() {
            if (blocked != null || selected.isEmpty()) return;
            if (create) {
                Integer last = entityManager.createQuery("select max(t.sortOrder) from TableInfo t where t.projectId = :projectId", Integer.class)
                        .setParameter("projectId", work.projectId).getSingleResult();
                entity = TableInfo.builder().projectId(work.projectId).schema(entityManager.getReference(SchemaInfo.class, schemaId)).tableName(modelName)
                        .isMasterTable(false).sortOrder(last == null ? 1 : last + 1).isApproval(false).columns(new ArrayList<>())
                        .sourceComment(chosen("TABLE:COMMENT") ? source.comment() : null).build();
                entityManager.persist(entity);
                tableMappingRepository.save(SourceTableMapping.builder().projectId(work.projectId).connectionId(work.connection.id()).sourceTableId(source.id())
                        .sourceSchemaId(source.schemaId()).pathKey(source.pathKey()).compareName(source.compareName()).creationCycle(source.creationCycle())
                        .reflectedOrder(work.order).tableId(entity.getId()).build());
            } else {
                entity = entityManager.find(TableInfo.class, model.id());
                if (chosen("TABLE:COMMENT")) entity.setSourceComment(source.comment());
                for (ColumnInfo column : entity.getColumns()) columnEntities.put(work.fold(column.getColumnName()), column);
            }
            int next = entity.getColumns().stream().map(ColumnInfo::getSortOrder).filter(Objects::nonNull).max(Integer::compare).orElse(0);
            for (SourceCatalogReader.Column column : detail.columns().stream()
                    .sorted(Comparator.comparing(SourceCatalogReader.Column::ordinal, Comparator.nullsLast(Comparator.naturalOrder()))).toList()) {
                String prefix = "COLUMN:" + column.name() + ":";
                if (chosen(prefix + "ADD")) {
                    Converted converted = convert(column);
                    ColumnInfo created = ColumnInfo.builder().projectId(work.projectId).tableInfo(entity).tableName(entity.getTableName())
                            .columnName(candidates.get(prefix + "ADD").modelName()).dataType(converted.type.dataType()).maxLength(converted.type.maxLength())
                            .precision(converted.type.precision() == null ? null : java.math.BigDecimal.valueOf(converted.type.precision())).scale(converted.type.scale())
                            .isNullable(!Boolean.FALSE.equals(column.nullable())).isRequired(false).isPk(false).isUnique(false).isIndex(false)
                            .defaultValue(converted.defaultValue).sortOrder(++next).isMasterData(false).isSensitive(false).isEncrypted(false).isApproval(false)
                            .sourceComment(chosen(prefix + "COMMENT") ? column.comment() : null).build();
                    entityManager.persist(created);
                    entity.getColumns().add(created);
                    columnEntities.put(column.compareName(), created);
                    continue;
                }
                ColumnInfo existing = columnEntities.get(column.compareName());
                if (existing == null) continue;
                if (chosen(prefix + "TYPE")) {
                    Converted converted = convert(column);
                    existing.setDataType(converted.type.dataType());
                    existing.setMaxLength(converted.type.maxLength());
                    existing.setPrecision(converted.type.precision() == null ? null : java.math.BigDecimal.valueOf(converted.type.precision()));
                    existing.setScale(converted.type.scale());
                }
                if (chosen(prefix + "NULLABLE")) {
                    boolean nullable = !Boolean.FALSE.equals(column.nullable());
                    existing.setIsNullable(nullable);
                    if (nullable) existing.setIsRequired(false);
                }
                if (chosen(prefix + "DEFAULT")) existing.setDefaultValue(convert(column).defaultValue);
                if (chosen(prefix + "COMMENT")) existing.setSourceComment(column.comment());
            }
        }

        void applyKeysAndIndexes() {
            if (blocked != null || entity == null) return;
            for (SourceCatalogReader.Constraint constraint : detail.constraints()) {
                switch (constraint.type()) {
                    case "PRIMARY_KEY" -> {
                        if (!chosen("PRIMARY_KEY:" + constraint.name())) continue;
                        for (String column : constraint.columns()) {
                            ColumnInfo pk = columnEntities.get(column);
                            pk.setIsPk(true);
                            pk.setIsNullable(false);
                        }
                    }
                    case "UNIQUE", "CHECK" -> {
                        String id = constraint.type() + ":" + constraint.name();
                        if (!chosen(id)) continue;
                        TableConstraint created = TableConstraint.builder().projectId(work.projectId).tableInfo(entity).constraintName(candidates.get(id).modelName())
                                .constraintType("UNIQUE".equals(constraint.type()) ? TableConstraint.Type.UNIQUE : TableConstraint.Type.CHECK)
                                .checkExpression(constraint.checkExpression()).isApproval(false).columns(new ArrayList<>()).build();
                        int order = 0;
                        for (String column : constraint.columns()) {
                            ColumnInfo member = columnEntities.get(column);
                            if (member != null) created.getColumns().add(TableConstraintColumn.builder().tableConstraint(created).column(member).columnOrder(++order).build());
                        }
                        entityManager.persist(created);
                    }
                    default -> {
                    }
                }
            }
            for (SourceCatalogReader.Index index : detail.indexes()) {
                String id = "INDEX:" + index.name();
                if (!chosen(id)) continue;
                String type = index.indexType() == null ? null : index.indexType().toUpperCase(Locale.ROOT);
                TableIndex created = TableIndex.builder().projectId(work.projectId).tableInfo(entity).indexName(candidates.get(id).modelName())
                        .indexType("BTREE".equals(type) ? null : type).isUnique(index.unique()).indexSource(TableIndex.Source.USER).condition(index.predicate())
                        .isApproval(false).columns(new ArrayList<>()).build();
                for (int i = 0; i < index.columns().size(); i++) {
                    String column = index.columns().get(i);
                    String expression = index.expressions() == null ? null : index.expressions().get(i);
                    String sort = index.sorts() == null ? null : index.sorts().get(i);
                    created.getColumns().add(TableIndexColumn.builder().tableIndex(created).column(column == null ? null : columnEntities.get(column))
                            .expression(column == null ? expression : null).sort(sort).columnOrder(i + 1).build());
                }
                entityManager.persist(created);
            }
        }

        void applyForeignKeys() {
            if (blocked != null || entity == null) return;
            for (SourceCatalogReader.ForeignKey fk : detail.foreignKeys()) {
                String id = "FOREIGN_KEY:" + fk.name();
                if (!chosen(id)) continue;
                TableWork planned = work.plannedTarget(fk);
                Long targetId = work.mappedModelBySource.get(work.sourceByName.get(fk.referencedSchema() + "\u0000" + fk.referencedTable()));
                TableInfo target = planned != null ? planned.entity : entityManager.find(TableInfo.class, targetId);
                Map<String, ColumnInfo> targetColumns = new HashMap<>();
                if (planned != null) targetColumns.putAll(planned.columnEntities);
                for (ColumnInfo column : target.getColumns()) targetColumns.putIfAbsent(work.fold(column.getColumnName()), column);
                TableRelation relation = TableRelation.builder().projectId(work.projectId).sourceTable(entity).targetTable(target).relationName(candidates.get(id).modelName())
                        .relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.IMPORTED).isApproval(false).columns(new ArrayList<>()).build();
                for (int i = 0; i < fk.columns().size(); i++) {
                    relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(columnEntities.get(fk.columns().get(i)))
                            .targetColumn(targetColumns.get(fk.referencedColumns().get(i))).columnOrder(i + 1).build());
                }
                entityManager.persist(relation);
            }
        }
    }

    // ------------------------------------------------------------------ 타입·기본값 변환 (원본 → 모델)

    /** 모델 칸에 넣을 타입 : mapType(POSTGRESQL) 로 되돌렸을 때 원본과 같은 타입이 되는 표현 */
    record ModelType(String dataType, Integer maxLength, Integer precision, Integer scale) {
        String text() {
            return dataType + (maxLength != null ? "(" + maxLength + ")" : precision != null ? "(" + precision + (scale != null && scale != 0 ? "," + scale : "") + ")" : "");
        }
    }

    /** 변환 결과 : type 이 null 이면 모델로 표현할 수 없다. excluded = 표현하지 못해 빼는 원본 속성 */
    record Converted(ModelType type, String defaultValue, List<String> excluded) {
        String describe(Boolean nullable) {
            return type.text() + (Boolean.FALSE.equals(nullable) ? " NOT NULL" : "") + (defaultValue == null ? "" : " DEFAULT " + defaultValue);
        }
    }

    static Converted convert(SourceCatalogReader.Column column) {
        PgType target = PostgresCompareRules.type(column.dataType());
        ModelType type = null;
        if (target != null) {
            List<ModelType> candidates = new ArrayList<>();
            if (target.known() && target.arrayDims() == 0) {
                switch (target.base()) {
                    case "VARCHAR", "CHAR", "BIT", "VARBIT" -> candidates.add(new ModelType(target.base(), target.length(), null, null));
                    case "NUMERIC" -> candidates.add(new ModelType("NUMERIC", null, target.precision(), target.scale()));
                    case "TIMESTAMP", "TIME" -> {
                        if (Integer.valueOf(6).equals(target.precision())) candidates.add(new ModelType(target.base(), null, null, null));
                    }
                    case "REAL" -> candidates.add(new ModelType("FLOAT4", null, null, null));
                    default -> candidates.add(new ModelType(target.base(), null, null, null));
                }
            }
            candidates.add(new ModelType(column.dataType().trim(), null, null, null));
            for (ModelType candidate : candidates) {
                ColumnInfo probe = ColumnInfo.builder().columnName("X").dataType(candidate.dataType()).maxLength(candidate.maxLength())
                        .precision(candidate.precision() == null ? null : java.math.BigDecimal.valueOf(candidate.precision())).scale(candidate.scale()).build();
                if (target.equals(PostgresCompareRules.type(TableDdlGenerator.mapType(DbType.POSTGRESQL, probe, new ArrayList<>())))) {
                    type = candidate;
                    break;
                }
            }
        }
        List<String> excluded = new ArrayList<>();
        String defaultValue = null;
        String raw = column.defaultValue();
        if (PostgresCompareRules.identity(raw) != null) {
            excluded.add(raw.trim());
        } else if (PostgresCompareRules.generated(raw)) {
            excluded.add(raw.trim());
        } else if (raw != null) {
            for (String candidate : List.of(raw.trim(), PostgresCompareRules.stripLiteralCasts(raw))) {
                if (Objects.equals(PostgresCompareRules.expression(raw), PostgresCompareRules.expression(TableDdlGenerator.defaultLiteral(candidate)))) {
                    defaultValue = candidate;
                    break;
                }
            }
            if (defaultValue == null) excluded.add("DEFAULT " + raw.trim());
        }
        return new Converted(type, defaultValue, List.copyOf(excluded));
    }

    /** 판정 값 표지 : 기준값 없음 / 원본 값을 모델로 바꿀 수 없음 (어떤 모델 값과도 같지 않다) */
    private static final String NO_VALUE = "\u0000none";
    private static final String UNREPRESENTABLE = "\u0000unrepresentable";

    private static String typeText(ModelType type) {
        ColumnInfo probe = ColumnInfo.builder().columnName("X").dataType(type.dataType()).maxLength(type.maxLength())
                .precision(type.precision() == null ? null : java.math.BigDecimal.valueOf(type.precision())).scale(type.scale()).build();
        return TableDdlGenerator.mapType(DbType.POSTGRESQL, probe, new ArrayList<>());
    }

    /** 변경 이력의 컬럼 상태(필드 이름 그대로) → 모델 타입 표현 */
    private static String typeText(Map<String, Object> state) {
        ColumnInfo probe = ColumnInfo.builder().columnName("X").dataType((String) state.get("dataType")).maxLength(integer(state.get("maxLength")))
                .precision(state.get("precision") == null ? null : new java.math.BigDecimal(String.valueOf(state.get("precision")))).scale(integer(state.get("scale"))).build();
        return TableDdlGenerator.mapType(DbType.POSTGRESQL, probe, new ArrayList<>());
    }

    /** 모델의 실제 NULL 허용 (DDL 과 같다 : PK·필수면 NOT NULL) */
    private static String nullText(Object nullable, Object pk, Object required) {
        ColumnInfo probe = ColumnInfo.builder().columnName("X").isNullable((Boolean) nullable).isPk((Boolean) pk).isRequired((Boolean) required).build();
        return TableDdlGenerator.isNotNull(probe) ? "NOT NULL" : "NULL";
    }

    private static Integer integer(Object value) {
        return value == null ? null : value instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(value));
    }

    private static String modelType(CompareModel.Column column) {
        ColumnInfo probe = ColumnInfo.builder().columnName(column.name()).dataType(column.dataType()).maxLength(column.maxLength()).precision(column.precision())
                .scale(column.scale()).build();
        return TableDdlGenerator.mapType(DbType.POSTGRESQL, probe, new ArrayList<>());
    }

    private static String describe(SourceCatalogReader.Column column) {
        return column.dataType() + (Boolean.FALSE.equals(column.nullable()) ? " NOT NULL" : "") + (column.defaultValue() == null ? "" : " DEFAULT " + column.defaultValue());
    }

    private static AttributeDiff attribute(ObjectDiff object, String name) {
        return object.attributes().stream().filter(a -> name.equals(a.attribute())).findFirst().orElse(null);
    }

    private static String text(List<AttributeDiff> attributes, boolean source) {
        List<String> parts = new ArrayList<>();
        for (AttributeDiff attribute : attributes) {
            String value = source ? attribute.source() : attribute.model();
            if (value != null) parts.add(attribute.attribute() + "=" + value);
        }
        return String.join("; ", parts);
    }

    private static Long number(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static CustomException stale() {
        return new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "기준 연결·수집 또는 모델이 미리보기 이후 바뀌었습니다. 비교 결과를 새로 고친 뒤 다시 미리보기 해주세요.");
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.SOURCE_IMPORT_INVALID, message, args);
    }
}
