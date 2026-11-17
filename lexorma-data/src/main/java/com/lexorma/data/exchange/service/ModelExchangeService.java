package com.lexorma.data.exchange.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.project.service.ProjectAuthority;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.erd.domain.entity.ErdLayout;
import com.lexorma.data.erd.service.ErdService;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Item;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.PlanItem;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Reason;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Status;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalErdLayout;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifier;
import com.lexorma.data.logical.domain.entity.LogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.LogicalModel;
import com.lexorma.data.logical.domain.entity.LogicalRelationship;
import com.lexorma.data.mapping.domain.entity.AttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import com.lexorma.data.view.ddl.ViewDefinitionSql;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewColumn;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import com.lexorma.core.project.domain.enums.DbType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 모델 Export · Import · 다른 프로젝트로 복사 (DEC-22, docs/completed/model-exchange-dec22.md).
 * <ul>
 *   <li>Export : 로그인 프로젝트의 모델을 {@link ModelExchangeDto.Document} 로 (VIEWER). 모델 버전과 같은 필드 목록에서 승인 상태를 뺀 것</li>
 *   <li>Import·복사 : 대상 프로젝트(로그인 프로젝트 또는 관리하는 다른 프로젝트)에 계획(plan, 읽기 전용) → 확정(apply). 확정은 대상 프로젝트 행을 배타로 잡고 다시 계획해
 *       planToken 이 같을 때만, 직전 모델 버전 {@code MODEL-IMPORT-<요청 ID>} 를 남기고 한 트랜잭션으로 만든다 (실패하면 모두 되돌린다)</li>
 *   <li>새 객체만 만든다 : 같은 이름(스키마+TABLE, 논리 모델)이 있으면 CONFLICT 로 가져오지 않는다 (덮어쓰지 않는다). 모든 객체는 미승인, ID 는 새로 받고 참조는 새 ID 로 다시 잇는다</li>
 *   <li>파일은 믿지 않는 입력이다 : 형식·버전·객체 수·값의 모양·이름 길이를 검사하고, 문장 구분·주석이 든 SQL 식(기본값·CHECK·인덱스 조건·표현식)은 가져오지 않는다 (UNSAFE_TEXT)</li>
 *   <li>표준 용어는 대상 프로젝트의 같은 이름 용어에만 잇는다 (없으면 연결 없이, 경고). 컬럼에서 파생되는 참조 관계(REF_COLUMN)·컬럼 인덱스(COLUMN)는 파일에서 받지 않고
 *       커밋 직전 동기화(ReferenceSyncBoundary)가 컬럼의 참조 테이블명·인덱스 여부로 다시 만든다 (그 규칙대로 승인 상태로 만들어진다 — 파일에서 물려받는 것이 아니다)</li>
 *   <li>모델 VIEW (형식 2, 7차) : VIEW 관리와 같은 정적 검사(ViewDefinitionSql — 조회 문 하나)를 통과한 정의만, 실행하지 않는다. 참조 객체는 같이 가져오는 TABLE·VIEW 면 새 ID,
 *       대상 프로젝트에 같은 이름 객체가 있으면 그 객체, 둘 다 아니면 모델 밖 참조(이름만)로 남긴다. 원본 DB 에서 수집한 VIEW 정의(DEC-16)와는 관계없다</li>
 *   <li>ERD 배치 (형식 2) : 내보낸 사용자의 저장된 배치를 새 ID 로 옮겨 가져오는 사용자 본인의 배치에 더한다. 기존 좌표는 바꾸지 않고(이미 배치가 있으면 그 오른쪽에 둔다),
 *       다른 사용자의 배치는 건드리지 않는다. 파일에 없는 객체의 좌표는 무시하고(경고), 숫자가 아닌·너무 큰 좌표는 파일 전체를 거절한다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ModelExchangeService {

    /** 한 파일의 최대 객체 수 (종류 합계, 자식 행 제외) */
    static final int MAX_OBJECTS = 50_000;
    static final int MAX_NAME = 128;
    static final int MAX_TEXT = 4_000;
    static final String VERSION_PREFIX = "MODEL-IMPORT-";

    static final List<String> TYPES = List.of(ModelChangeRecorder.SCHEMA, "PHYSICAL_TABLE", "PHYSICAL_COLUMN", "PHYSICAL_RELATION", "PHYSICAL_INDEX", "PHYSICAL_CONSTRAINT",
            "PHYSICAL_VIEW", "LOGICAL_MODEL", "LOGICAL_ENTITY", "LOGICAL_ATTRIBUTE", "LOGICAL_IDENTIFIER", "LOGICAL_RELATIONSHIP", "TABLE_MAPPING", "COLUMN_MAPPING");
    /** 파일에서 빼는 필드 : 승인 상태 (가져온 객체는 모두 미승인) */
    private static final Set<String> DROPPED = Set.of("isApproval");
    /** SQL 식에 있으면 가져오지 않는다 : 문장 구분·주석 (DDL 로 나갈 때 다른 문장을 끼워 넣을 수 있다) */
    private static final Pattern UNSAFE = Pattern.compile(";|--|/\\*|\\*/");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 배치 좌표 한도 : ERD 배치 저장(ErdServiceImpl·LogicalModelService)과 같다 */
    static final double MAX_COORDINATE = 10_000_000;
    static final double MIN_ZOOM = 0.02, MAX_ZOOM = 4;
    /** VIEW 검사 한도 : VIEW 관리(PhysicalViewService)와 같다 */
    private static final int VIEW_TEXT_MAX = 255, VIEW_DESCRIPTION_MAX = 1000, VIEW_COLUMN_MAX = 1000, VIEW_REFERENCE_MAX = 200;
    /** 이미 배치가 있는 ERD 에 더할 때 기존 노드 오른쪽으로 띄우는 거리 */
    private static final double LAYOUT_GAP = 600;

    private final ModelChangeRecorder recorder;
    private final ModelVersionService versionService;
    private final ProjectRepository projectRepository;
    private final ProjectWriteGuard projectWriteGuard;
    private final ProjectAuthority projectAuthority;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------ Export

    @Transactional(readOnly = true)
    public ModelExchangeDto.Document export(AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        Long accountId = accountDto.getId() == null ? null : Long.valueOf(accountDto.getId());
        Project project = projectRepository.findById(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        Map<String, List<ModelChangeRecorder.ObjectState>> state = recorder.currentState(projectId);
        Map<String, List<Item>> model = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String type : TYPES) {
            List<Item> items = new ArrayList<>();
            for (ModelChangeRecorder.ObjectState object : state.getOrDefault(type, List.of())) {
                Map<String, Object> values = new LinkedHashMap<>(object.state());
                DROPPED.forEach(values::remove);
                items.add(new Item(object.id(), object.name(), values));
            }
            model.put(type, items);
            counts.put(type, items.size());
        }
        return new ModelExchangeDto.Document(ModelExchangeDto.FORMAT, ModelExchangeDto.FORMAT_VERSION, OffsetDateTime.now().toString(),
                project.getDbType() == null ? null : project.getDbType().name(), counts, model, layouts(projectId, accountId, model));
    }

    /** 내보내는 사용자가 저장한 배치 : 파일에 들어간 TABLE·엔터티의 좌표만 (지워진 객체의 좌표는 뺀다) */
    private ModelExchangeDto.Layouts layouts(Long projectId, Long accountId, Map<String, List<Item>> model) {
        if (accountId == null) return null;
        Set<Long> tableIds = ids(model.get("PHYSICAL_TABLE"));
        ModelExchangeDto.PhysicalLayout physical = entityManager.createQuery("select l from ErdLayout l where l.projectId = :p and l.accountId = :a", ErdLayout.class)
                .setParameter("p", projectId).setParameter("a", accountId).getResultList().stream().findFirst()
                .map(l -> new ModelExchangeDto.PhysicalLayout(nodes(l.getNodePositions(), tableIds), new ModelExchangeDto.Viewport(l.getViewportX(), l.getViewportY(), l.getViewportZoom())))
                .filter(l -> !l.nodes().isEmpty()).orElse(null);
        Set<Long> modelIds = ids(model.get("LOGICAL_MODEL"));
        Set<Long> entityIds = ids(model.get("LOGICAL_ENTITY"));
        List<ModelExchangeDto.LogicalLayout> logical = new ArrayList<>();
        for (LogicalErdLayout l : entityManager.createQuery("select l from LogicalErdLayout l where l.projectId = :p and l.accountId = :a order by l.modelId", LogicalErdLayout.class)
                .setParameter("p", projectId).setParameter("a", accountId).getResultList()) {
            if (!modelIds.contains(l.getModelId())) continue;
            Map<Long, ModelExchangeDto.Point> nodes = nodes(l.getNodePositions(), entityIds);
            if (!nodes.isEmpty()) logical.add(new ModelExchangeDto.LogicalLayout(l.getModelId(), nodes, new ModelExchangeDto.Viewport(l.getViewportX(), l.getViewportY(), l.getViewportZoom())));
        }
        return physical == null && logical.isEmpty() ? null : new ModelExchangeDto.Layouts(physical, logical);
    }

    private static Set<Long> ids(List<Item> items) {
        Set<Long> ids = new HashSet<>();
        if (items != null) items.forEach(item -> ids.add(item.id()));
        return ids;
    }

    /** 저장된 배치 JSON({"ID": {"x", "y"}}) 중 keep 에 있는 것만, ID 순 */
    private static Map<Long, ModelExchangeDto.Point> nodes(String json, Set<Long> keep) {
        Map<Long, ModelExchangeDto.Point> nodes = new java.util.TreeMap<>();
        for (Map.Entry<String, tools.jackson.databind.JsonNode> e : JSON.readTree(json == null ? "{}" : json).properties()) {
            Long id = Long.valueOf(e.getKey());
            if (keep.contains(id)) nodes.put(id, new ModelExchangeDto.Point(e.getValue().path("x").asDouble(), e.getValue().path("y").asDouble()));
        }
        return nodes;
    }

    /** 가져올 수 있는 프로젝트 : 사용 중이고 지금 관리자·ADMIN 인 것 */
    @Transactional(readOnly = true)
    public List<ModelExchangeDto.TargetProject> targets(AccountDto accountDto) {
        Long accountId = Long.valueOf(accountDto.getId());
        return projectRepository.findByIsActive(true).stream().filter(project -> projectAuthority.canManage(project.getId(), accountId))
                .map(project -> new ModelExchangeDto.TargetProject(project.getId(), project.getProjectName(), project.getDbType() == null ? null : project.getDbType().name(),
                        project.getId().equals(accountDto.getProjectId()))).toList();
    }

    // ------------------------------------------------------------------ Import

    @Transactional(readOnly = true)
    public ModelExchangeDto.Plan plan(AccountDto accountDto, ModelExchangeDto.PlanRequest request) {
        Project target = target(accountDto, request.targetProjectId());
        Work work = new Work(target, Long.valueOf(accountDto.getId()), validate(request.document()), request.selection());
        work.plan();
        return work.result(UUID.randomUUID().toString());
    }

    @Transactional
    public ModelExchangeDto.Result apply(AccountDto accountDto, ModelExchangeDto.ApplyRequest request) {
        if (request.planToken() == null || request.requestId() == null || !request.requestId().matches("[0-9a-fA-F-]{36}")) {
            throw new CustomException(ErrorCode.MODEL_EXCHANGE_INVALID, "계획을 먼저 확인해주세요.");
        }
        Project target = target(accountDto, request.targetProjectId());
        // 대상 프로젝트 행을 배타로 : 그 사이의 모델 저장(커밋 때 프로젝트 행 공유 잠금)과 겹치지 않는다
        projectRepository.findByIdForUpdate(target.getId()).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        projectWriteGuard.lock(target.getId());
        Work work = new Work(target, Long.valueOf(accountDto.getId()), validate(request.document()), request.selection());
        work.plan();
        ModelExchangeDto.Plan plan = work.result(request.requestId());
        if (!plan.planToken().equals(request.planToken())) throw new CustomException(ErrorCode.MODEL_EXCHANGE_STALE);
        if (!plan.applicable()) throw new CustomException(ErrorCode.MODEL_EXCHANGE_INVALID, "가져올 객체가 없습니다.");
        ModelVersionDto.Summary version = versionService.createBeforeImport(target.getId(), VERSION_PREFIX + request.requestId(),
                "모델 가져오기 직전 상태", accountDto.getUsername());
        recorder.markImport(VERSION_PREFIX + request.requestId());
        Map<String, Integer> created = work.create();
        return new ModelExchangeDto.Result(target.getId(), VERSION_PREFIX + request.requestId(), version.versionName(), created);
    }

    /** 대상 프로젝트 : 사용 중이고, 지금 DB 로 관리자·ADMIN 인지 확인한다 (세션의 역할만 믿지 않는다) */
    private Project target(AccountDto accountDto, Long requested) {
        Long projectId = requested == null ? accountDto.getProjectId() : requested;
        Project project = projectRepository.findById(projectId).filter(found -> Boolean.TRUE.equals(found.getIsActive()))
                .orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        if (accountDto.getId() == null || !projectAuthority.canManage(projectId, Long.valueOf(accountDto.getId()))) {
            throw new CustomException(ErrorCode.MODEL_EXCHANGE_FORBIDDEN);
        }
        return project;
    }

    /** 파일 검사 : 형식·버전·종류·객체 수, 값은 글자·숫자·참/거짓과 자식 행 목록(같은 모양)만 */
    static ModelExchangeDto.Document validate(ModelExchangeDto.Document document) {
        if (document == null || !ModelExchangeDto.FORMAT.equals(document.format())) throw invalid("Lexorma 모델 파일이 아닙니다.");
        if (document.formatVersion() == null || !ModelExchangeDto.SUPPORTED_VERSIONS.contains(document.formatVersion())) {
            throw invalid("지원하지 않는 모델 파일 형식 버전입니다 : {0}", document.formatVersion());
        }
        if (document.model() == null) throw invalid("모델 내용이 없습니다.");
        int total = 0;
        for (Map.Entry<String, List<Item>> entry : document.model().entrySet()) {
            if (!TYPES.contains(entry.getKey())) throw invalid("알 수 없는 객체 종류입니다 : {0}", entry.getKey());
            List<Item> items = entry.getValue() == null ? List.of() : entry.getValue();
            total += items.size();
            if (total > MAX_OBJECTS) throw invalid("객체가 너무 많습니다 (최대 {0}개).", MAX_OBJECTS);
            Set<Long> ids = new HashSet<>();
            for (Item item : items) {
                if (item == null || item.id() == null || item.state() == null || !ids.add(item.id())) throw invalid("객체 번호가 없거나 겹칩니다 : {0}", entry.getKey());
                for (Map.Entry<String, Object> value : item.state().entrySet()) {
                    if (!flat(value.getValue(), 0)) throw invalid("값의 모양이 올바르지 않습니다 : {0}.{1}", entry.getKey(), value.getKey());
                }
            }
        }
        if (document.formatVersion() == 1 && (document.layouts() != null || document.model().containsKey("PHYSICAL_VIEW"))) {
            throw invalid("형식 버전 1 파일에는 VIEW·ERD 배치가 들어갈 수 없습니다.");
        }
        validateLayouts(document.layouts());
        return document;
    }

    /** 배치 : 좌표는 유한한 숫자이고 ±1,000만 안, 확대 0.02~4, 배치 하나에 5,000개까지 (ERD 배치 저장과 같은 한도). 어기면 파일 전체를 거절한다 */
    private static void validateLayouts(ModelExchangeDto.Layouts layouts) {
        if (layouts == null) return;
        List<ModelExchangeDto.LogicalLayout> logical = layouts.logical() == null ? List.of() : layouts.logical();
        if (logical.size() > 1_000) throw invalid("논리 ERD 배치가 너무 많습니다.");
        if (layouts.physical() != null) validateLayout(layouts.physical().nodes(), layouts.physical().viewport());
        Set<Long> models = new HashSet<>();
        for (ModelExchangeDto.LogicalLayout layout : logical) {
            if (layout == null || layout.modelId() == null || !models.add(layout.modelId())) throw invalid("논리 ERD 배치의 모델 번호가 없거나 겹칩니다.");
            validateLayout(layout.nodes(), layout.viewport());
        }
    }

    private static void validateLayout(Map<Long, ModelExchangeDto.Point> nodes, ModelExchangeDto.Viewport viewport) {
        if (nodes == null) throw invalid("ERD 배치 좌표가 없습니다.");
        if (nodes.size() > ErdService.MAX_LAYOUT_NODES) throw invalid("ERD 배치는 {0}개까지 가져올 수 있습니다.", ErdService.MAX_LAYOUT_NODES);
        for (Map.Entry<Long, ModelExchangeDto.Point> node : nodes.entrySet()) {
            if (node.getKey() == null || node.getValue() == null || !coordinate(node.getValue().x()) || !coordinate(node.getValue().y())) {
                throw invalid("ERD 배치 좌표가 올바르지 않습니다 : {0}", node.getKey());
            }
        }
        if (viewport != null && (!coordinate(viewport.x()) || !coordinate(viewport.y()) || viewport.zoom() == null
                || !Double.isFinite(viewport.zoom()) || viewport.zoom() < MIN_ZOOM || viewport.zoom() > MAX_ZOOM)) {
            throw invalid("ERD 화면 위치·확대 비율이 올바르지 않습니다.");
        }
    }

    private static boolean coordinate(Double value) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }

    /** 글자·숫자·참/거짓·null, 또는 그런 값만 가진 객체의 목록(자식 행) 한 단계 */
    @SuppressWarnings("unchecked")
    private static boolean flat(Object value, int depth) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return true;
        if (depth > 0) return false;
        if (!(value instanceof List<?> list) || list.size() > 1_000) return false;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> map)) return false;
            for (Object child : ((Map<String, Object>) map).values()) {
                if (!flat(child, 1)) return false;
            }
        }
        return true;
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.MODEL_EXCHANGE_INVALID, message, args);
    }

    // ------------------------------------------------------------------ 계획·반영 한 번

    private final class Work {

        private final Project target;
        private final Long projectId;
        /** 가져오는 사용자 : 배치는 이 사용자의 배치에만 더한다 */
        private final Long accountId;
        private final ModelExchangeDto.Document document;
        private final ModelExchangeDto.Selection selection;

        private final Map<Long, Item> schemas = new LinkedHashMap<>();
        private final Map<Long, Item> tables = new LinkedHashMap<>();
        private final Map<Long, List<Item>> columnsByTable = new LinkedHashMap<>();
        private final Map<Long, Item> columns = new HashMap<>();
        private final List<Item> relations = new ArrayList<>();
        private final Map<Long, List<Item>> indexesByTable = new HashMap<>();
        private final Map<Long, List<Item>> constraintsByTable = new HashMap<>();
        private final Map<Long, Item> models = new LinkedHashMap<>();
        private final Map<Long, List<Item>> entitiesByModel = new HashMap<>();
        private final Map<Long, Item> entities = new HashMap<>();
        private final Map<Long, List<Item>> attributesByEntity = new HashMap<>();
        private final Map<Long, List<Item>> identifiersByEntity = new HashMap<>();
        private final Map<Long, List<Item>> relationshipsByModel = new HashMap<>();
        private final List<Item> tableMappings = new ArrayList<>();
        private final Map<Long, List<Item>> columnMappingsByMapping = new HashMap<>();
        private final Map<Long, Item> views = new LinkedHashMap<>();

        /** 대상 프로젝트 : 스키마 이름(소문자) → ID, TABLE 키(스키마.테이블 소문자), 논리 모델 이름(소문자), 표준 용어 이름 → ID */
        private final Map<String, Long> targetSchemas = new HashMap<>();
        private final Set<String> targetTables = new HashSet<>();
        /** 대상 프로젝트의 TABLE·VIEW : 키(스키마.이름 소문자) → ID (VIEW 참조를 기존 객체에 이을 때) */
        private final Map<String, Long> targetTableIds = new HashMap<>();
        private final Map<String, Long> targetViewIds = new HashMap<>();
        /** 가져오는 사용자의 지금 물리 ERD 배치 (없으면 null) */
        private ErdLayout targetLayout;
        private Map<Long, ModelExchangeDto.Point> targetLayoutNodes = Map.of();
        private final Set<String> targetModels = new HashSet<>();
        private final Map<String, Long> targetTerms = new HashMap<>();

        private final List<PlanItem> items = new ArrayList<>();
        private final Set<Long> newTables = new HashSet<>();
        private final Set<Long> newModels = new HashSet<>();
        private final Set<Long> newSchemas = new HashSet<>();
        private final Set<Long> newRelations = new HashSet<>();
        private final Set<Long> newMappings = new HashSet<>();
        private final Set<Long> newViews = new LinkedHashSet<>();
        /** VIEW 참조의 대상 : 파일의 VIEW 번호 → 참조 행 순서대로 (NEW_TABLE / NEW_VIEW / 기존 TABLE·VIEW ID / 모델 밖 이름) */
        private final Map<Long, List<ViewRef>> viewRefs = new HashMap<>();
        /** 옮길 배치 : 파일 번호 → 좌표 (가져오는 객체만) */
        private final Map<Long, ModelExchangeDto.Point> physicalNodes = new LinkedHashMap<>();
        private final Map<Long, Map<Long, ModelExchangeDto.Point>> logicalNodes = new LinkedHashMap<>();
        private final Map<Long, ModelExchangeDto.Viewport> logicalViewports = new HashMap<>();
        private int ignoredPositions;
        /** 가져오지 않는 인덱스·제약조건 (안전하지 않은 식) */
        private final Set<Long> skipped = new HashSet<>();
        private final List<String> warnings = new ArrayList<>();
        private int missingTerms;

        Work(Project target, Long accountId, ModelExchangeDto.Document document, ModelExchangeDto.Selection selection) {
            this.target = target;
            this.projectId = target.getId();
            this.accountId = accountId;
            this.document = document;
            this.selection = selection;
            Map<String, List<Item>> model = document.model();
            model.getOrDefault(ModelChangeRecorder.SCHEMA, List.of()).forEach(item -> schemas.put(item.id(), item));
            model.getOrDefault("PHYSICAL_TABLE", List.of()).forEach(item -> tables.put(item.id(), item));
            for (Item column : model.getOrDefault("PHYSICAL_COLUMN", List.of())) {
                columns.put(column.id(), column);
                columnsByTable.computeIfAbsent(ref(column, "tableInfo"), id -> new ArrayList<>()).add(column);
            }
            // 참조 관계(REF_COLUMN)는 컬럼의 참조 테이블명으로 다시 만들어진다
            model.getOrDefault("PHYSICAL_RELATION", List.of()).stream().filter(item -> !"REF_COLUMN".equals(item.state().get("relationSource"))).forEach(relations::add);
            // 컬럼의 인덱스 여부에서 만든 인덱스(COLUMN)도 같은 동기화가 다시 만든다 : 사용자 인덱스(USER)만 받는다
            model.getOrDefault("PHYSICAL_INDEX", List.of()).stream().filter(item -> !"COLUMN".equals(item.state().get("indexSource")))
                    .forEach(item -> indexesByTable.computeIfAbsent(ref(item, "tableInfo"), id -> new ArrayList<>()).add(item));
            model.getOrDefault("PHYSICAL_CONSTRAINT", List.of()).forEach(item -> constraintsByTable.computeIfAbsent(ref(item, "tableInfo"), id -> new ArrayList<>()).add(item));
            model.getOrDefault("LOGICAL_MODEL", List.of()).forEach(item -> models.put(item.id(), item));
            for (Item entity : model.getOrDefault("LOGICAL_ENTITY", List.of())) {
                entities.put(entity.id(), entity);
                entitiesByModel.computeIfAbsent(ref(entity, "model"), id -> new ArrayList<>()).add(entity);
            }
            model.getOrDefault("LOGICAL_ATTRIBUTE", List.of()).forEach(item -> attributesByEntity.computeIfAbsent(ref(item, "entity"), id -> new ArrayList<>()).add(item));
            model.getOrDefault("LOGICAL_IDENTIFIER", List.of()).forEach(item -> identifiersByEntity.computeIfAbsent(ref(item, "entity"), id -> new ArrayList<>()).add(item));
            model.getOrDefault("LOGICAL_RELATIONSHIP", List.of()).forEach(item -> relationshipsByModel.computeIfAbsent(ref(item, "model"), id -> new ArrayList<>()).add(item));
            tableMappings.addAll(model.getOrDefault("TABLE_MAPPING", List.of()));
            model.getOrDefault("COLUMN_MAPPING", List.of()).forEach(item -> columnMappingsByMapping.computeIfAbsent(ref(item, "mapping"), id -> new ArrayList<>()).add(item));
            model.getOrDefault("PHYSICAL_VIEW", List.of()).forEach(item -> views.put(item.id(), item));

            for (Object[] row : entityManager.createQuery("select s.id, s.schemaName from SchemaInfo s where s.projectId = :p", Object[].class).setParameter("p", projectId).getResultList()) {
                targetSchemas.put(lower((String) row[1]), (Long) row[0]);
            }
            for (Object[] row : entityManager.createQuery("select s.schemaName, t.tableName, t.id from TableInfo t join t.schema s where t.projectId = :p", Object[].class)
                    .setParameter("p", projectId).getResultList()) {
                targetTables.add(lower((String) row[0]) + "." + lower((String) row[1]));
                targetTableIds.put(lower((String) row[0]) + "." + lower((String) row[1]), (Long) row[2]);
            }
            // TABLE 이름은 VIEW 와도 겹치면 안 된다
            for (Object[] row : entityManager.createQuery("select s.schemaName, v.viewName, v.id from PhysicalView v join v.schema s where v.projectId = :p", Object[].class)
                    .setParameter("p", projectId).getResultList()) {
                targetTables.add(lower((String) row[0]) + "." + lower((String) row[1]));
                targetViewIds.put(lower((String) row[0]) + "." + lower((String) row[1]), (Long) row[2]);
            }
            if (document.layouts() != null && document.layouts().physical() != null) {
                targetLayout = entityManager.createQuery("select l from ErdLayout l where l.projectId = :p and l.accountId = :a", ErdLayout.class)
                        .setParameter("p", projectId).setParameter("a", accountId).getResultList().stream().findFirst().orElse(null);
                if (targetLayout != null) targetLayoutNodes = nodes(targetLayout.getNodePositions(), targetTableIds.values().stream().collect(java.util.stream.Collectors.toSet()));
            }
            entityManager.createQuery("select m.modelName from LogicalModel m where m.projectId = :p", String.class).setParameter("p", projectId).getResultList()
                    .forEach(name -> targetModels.add(lower(name)));
            for (Object[] row : entityManager.createQuery("select t.id, t.commonStandardTermName from StandardTerm t where t.projectId = :p", Object[].class)
                    .setParameter("p", projectId).getResultList()) {
                targetTerms.putIfAbsent((String) row[1], (Long) row[0]);
            }
        }

        void plan() {
            if (document.dbType() != null && target.getDbType() != null && !document.dbType().equals(target.getDbType().name())) warnings.add("DB_TYPE_DIFFERS");
            Set<String> chosenTables = selection == null || selection.tables() == null ? null : new HashSet<>(selection.tables());
            Set<String> chosenModels = selection == null || selection.logicalModels() == null ? null : new HashSet<>(selection.logicalModels());

            // TABLE (컬럼·인덱스·제약조건 포함)
            for (Item table : tables.values()) {
                String schemaName = schemaName(table);
                String name = string(table, "tableName");
                String key = schemaName + "." + name;
                List<Item> tableColumns = columnsByTable.getOrDefault(table.id(), List.of());
                Reason reason = chosenTables != null && !chosenTables.contains(key) ? Reason.NOT_SELECTED
                        : !validTable(table, tableColumns) ? Reason.INVALID
                        : targetTables.contains(lower(schemaName) + "." + lower(name)) ? Reason.NAME_EXISTS : null;
                Status status = reason == null ? Status.NEW : reason == Reason.NAME_EXISTS ? Status.CONFLICT : Status.EXCLUDED;
                if (status == Status.NEW) {
                    newTables.add(table.id());
                    for (Item column : tableColumns) {
                        String term = string(column, "standardTermsName");
                        if (term != null && !targetTerms.containsKey(term)) missingTerms++;
                    }
                }
                int unsafe = 0;
                for (Item child : concat(indexesByTable.getOrDefault(table.id(), List.of()), constraintsByTable.getOrDefault(table.id(), List.of()))) {
                    if (!safeChild(child)) {
                        skipped.add(child.id());
                        unsafe++;
                    }
                }
                Map<String, Integer> sizes = new LinkedHashMap<>();
                sizes.put("columns", tableColumns.size());
                sizes.put("indexes", indexesByTable.getOrDefault(table.id(), List.of()).size() - (int) indexesByTable.getOrDefault(table.id(), List.of()).stream().filter(i -> skipped.contains(i.id())).count());
                sizes.put("constraints", constraintsByTable.getOrDefault(table.id(), List.of()).size()
                        - (int) constraintsByTable.getOrDefault(table.id(), List.of()).stream().filter(i -> skipped.contains(i.id())).count());
                sizes.put("unsafe", unsafe);
                items.add(new PlanItem("TABLE", key, name, status, reason, sizes));
            }
            planViews(selection == null ? null : selection.views());
            // 스키마 : 새 TABLE·VIEW 가 쓰는데 대상에 없는 이름만 만든다
            for (Item schema : schemas.values()) {
                String name = string(schema, "schemaName");
                boolean used = newTables.stream().anyMatch(id -> Objects.equals(ref(tables.get(id), "schema"), schema.id()))
                        || newViews.stream().anyMatch(id -> Objects.equals(ref(views.get(id), "schema"), schema.id()));
                if (!used) continue;
                if (targetSchemas.containsKey(lower(name))) {
                    items.add(new PlanItem("SCHEMA", name, name, Status.CONFLICT, Reason.NAME_EXISTS, null));
                } else {
                    newSchemas.add(schema.id());
                    items.add(new PlanItem("SCHEMA", name, name, Status.NEW, null, null));
                }
            }
            // 관계 : 양쪽 TABLE 을 모두 새로 만들 때만
            for (Item relation : relations) {
                Long source = ref(relation, "sourceTable");
                Long targetTable = ref(relation, "targetTable");
                boolean both = newTables.contains(source) && newTables.contains(targetTable) && validRelation(relation);
                if (both) newRelations.add(relation.id());
                items.add(new PlanItem("RELATION", tableKey(source) + "->" + tableKey(targetTable) + ":" + string(relation, "relationName"), string(relation, "relationName"),
                        both ? Status.NEW : Status.EXCLUDED, both ? null : Reason.RELATED_NOT_IMPORTED, null));
            }
            // 논리 모델 (엔터티·속성·식별자·관계 포함)
            for (Item model : models.values()) {
                String name = string(model, "modelName");
                List<Item> modelEntities = entitiesByModel.getOrDefault(model.id(), List.of());
                Reason reason = chosenModels != null && !chosenModels.contains(name) ? Reason.NOT_SELECTED
                        : !validModel(model, modelEntities) ? Reason.INVALID
                        : targetModels.contains(lower(name)) ? Reason.NAME_EXISTS : null;
                Status status = reason == null ? Status.NEW : reason == Reason.NAME_EXISTS ? Status.CONFLICT : Status.EXCLUDED;
                if (status == Status.NEW) {
                    newModels.add(model.id());
                    for (Item entity : modelEntities) {
                        for (Item attribute : attributesByEntity.getOrDefault(entity.id(), List.of())) {
                            String term = string(attribute, "standardTermName");
                            if (term != null && !targetTerms.containsKey(term)) missingTerms++;
                        }
                    }
                }
                items.add(new PlanItem("LOGICAL_MODEL", name, name, status, reason, ordered("entities", modelEntities.size(),
                        "relationships", relationshipsByModel.getOrDefault(model.id(), List.of()).size())));
            }
            // 논리 ↔ 물리 매핑 : 엔터티의 모델과 TABLE 을 모두 새로 만들 때만
            for (Item mapping : tableMappings) {
                Item entity = entities.get(ref(mapping, "entity"));
                Long table = ref(mapping, "table");
                boolean both = entity != null && newModels.contains(ref(entity, "model")) && newTables.contains(table);
                if (both) newMappings.add(mapping.id());
                items.add(new PlanItem("TABLE_MAPPING", (entity == null ? "?" : string(entity, "entityName")) + "->" + tableKey(table), null,
                        both ? Status.NEW : Status.EXCLUDED, both ? null : Reason.RELATED_NOT_IMPORTED, null));
            }
            planLayouts();
            if (missingTerms > 0) warnings.add("TERM_NOT_FOUND:" + missingTerms);
            if (ignoredPositions > 0) warnings.add("LAYOUT_IGNORED:" + ignoredPositions);
        }

        /**
         * VIEW : 이름(TABLE 과 같은 이름 공간)·정의(VIEW 관리와 같은 정적 검사)·출력 컬럼·참조를 검사한다. 같이 가져오는 VIEW 끼리 순환하면 가져오지 않는다.
         * 참조 대상은 같이 가져오는 객체 → 대상 프로젝트의 같은 이름 객체 → 모델 밖 참조(이름만) 순서로 정한다.
         */
        private void planViews(List<String> chosen) {
            Set<String> chosenViews = chosen == null ? null : new HashSet<>(chosen);
            Set<String> newTableKeys = new HashSet<>();
            newTables.forEach(id -> newTableKeys.add(lower(tableKey(id))));
            DbType dbType = DbType.orDefault(target.getDbType());
            Map<Long, Reason> reasons = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            for (Item view : views.values()) {
                String key = viewKey(view);
                Reason reason = chosenViews != null && !chosenViews.contains(key) ? Reason.NOT_SELECTED
                        : !validView(view) || newTableKeys.contains(lower(key)) || !seen.add(lower(key)) ? Reason.INVALID
                        : !safeDefinition(dbType, string(view, "definition")) ? Reason.UNSAFE_TEXT
                        : targetTables.contains(lower(key)) ? Reason.NAME_EXISTS : null;
                reasons.put(view.id(), reason);
                if (reason == null) newViews.add(view.id());
            }
            // 같이 가져오는 VIEW 끼리의 순환 (자기 자신 포함) : 순환에 든 VIEW 는 가져오지 않는다
            Map<Long, Set<Long>> edges = new HashMap<>();
            for (Long id : newViews) {
                for (Map<String, Object> reference : children(views.get(id), "references")) {
                    Long to = longValue(reference.get("referencedViewId"));
                    if (to != null && newViews.contains(to)) edges.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(to);
                }
            }
            for (Long id : new ArrayList<>(newViews)) {
                if (reaches(edges, id, id)) reasons.put(id, Reason.INVALID);
            }
            newViews.removeIf(id -> reasons.get(id) != null);
            for (Item view : views.values()) {
                Reason reason = reasons.get(view.id());
                Status status = reason == null ? Status.NEW : reason == Reason.NAME_EXISTS ? Status.CONFLICT : Status.EXCLUDED;
                List<Map<String, Object>> references = children(view, "references");
                Map<String, Integer> sizes = new LinkedHashMap<>();
                sizes.put("columns", children(view, "columns").size());
                sizes.put("references", references.size());
                if (status == Status.NEW) {
                    List<ViewRef> refs = new ArrayList<>();
                    for (Map<String, Object> reference : references) refs.add(resolve(view, reference));
                    viewRefs.put(view.id(), refs);
                    sizes.put("linked", (int) refs.stream().filter(r -> r.existingTable() != null || r.existingView() != null).count());
                    sizes.put("unresolved", (int) refs.stream().filter(r -> r.objectName() != null).count());
                }
                items.add(new PlanItem("VIEW", viewKey(view), string(view, "viewName"), status, reason, sizes));
            }
        }

        /** 참조 행 하나의 대상 */
        private ViewRef resolve(Item view, Map<String, Object> reference) {
            PhysicalViewReference.Type type = PhysicalViewReference.Type.valueOf((String) reference.get("referenceType"));
            Long tableRef = longValue(reference.get("referencedTableId"));
            Long viewRef = longValue(reference.get("referencedViewId"));
            String schemaName;
            String objectName;
            if (tableRef != null || viewRef != null) {
                if (tableRef != null && newTables.contains(tableRef)) return new ViewRef(type, tableRef, null, null, null, null, null);
                if (viewRef != null && newViews.contains(viewRef)) return new ViewRef(type, null, viewRef, null, null, null, null);
                Item referenced = tableRef != null ? tables.get(tableRef) : views.get(viewRef);
                // 파일에 없는 내부 참조 (파일이 고친 것) : 참조 행에 남은 이름으로
                schemaName = referenced != null ? schemaName(referenced) : string(view, "schemaName");
                objectName = referenced != null ? string(referenced, tableRef != null ? "tableName" : "viewName")
                        : (String) reference.get(tableRef != null ? "referencedTableName" : "referencedViewName");
            } else {
                schemaName = (String) reference.get("schemaName");
                objectName = (String) reference.get("objectName");
            }
            // 같이 가져오는 같은 이름 객체, 대상 프로젝트에 같은 이름 객체가 있으면 그 객체 (VIEW 관리도 모델에 있는 객체를 이름으로만 적지 못하게 한다)
            String key = lower((schemaName == null ? schemaName(view) : schemaName) + "." + objectName);
            for (Long id : newTables) if (lower(tableKey(id)).equals(key)) return new ViewRef(PhysicalViewReference.Type.TABLE, id, null, null, null, null, null);
            for (Long id : newViews) if (lower(viewKey(views.get(id))).equals(key)) return new ViewRef(PhysicalViewReference.Type.VIEW, null, id, null, null, null, null);
            if (targetTableIds.containsKey(key)) return new ViewRef(PhysicalViewReference.Type.TABLE, null, null, targetTableIds.get(key), null, null, null);
            if (targetViewIds.containsKey(key)) return new ViewRef(PhysicalViewReference.Type.VIEW, null, null, null, targetViewIds.get(key), null, null);
            return new ViewRef(type, null, null, null, null, schemaName, objectName);
        }

        /** 배치 : 가져오는 TABLE·엔터티의 좌표만 옮긴다. 파일에 없는 객체를 가리키는 좌표는 무시한다 (경고) */
        private void planLayouts() {
            ModelExchangeDto.Layouts layouts = document.layouts();
            if (layouts == null) return;
            if (layouts.physical() != null) {
                int ignored = 0;
                for (Map.Entry<Long, ModelExchangeDto.Point> node : layouts.physical().nodes().entrySet()) {
                    if (!tables.containsKey(node.getKey())) ignoredPositions++;
                    else if (newTables.contains(node.getKey())) physicalNodes.put(node.getKey(), node.getValue());
                    else ignored++;
                }
                boolean over = targetLayoutNodes.size() + physicalNodes.size() > ErdService.MAX_LAYOUT_NODES;
                Status status = physicalNodes.isEmpty() || over ? Status.EXCLUDED : Status.NEW;
                items.add(new PlanItem("ERD_LAYOUT", "PHYSICAL", null, status, over ? Reason.LIMIT_EXCEEDED : physicalNodes.isEmpty() ? Reason.RELATED_NOT_IMPORTED : null,
                        ordered("nodes", physicalNodes.size(), "ignored", ignored)));
                if (over) physicalNodes.clear();
            }
            for (ModelExchangeDto.LogicalLayout layout : layouts.logical() == null ? List.<ModelExchangeDto.LogicalLayout>of() : layouts.logical()) {
                Item model = models.get(layout.modelId());
                if (model == null) {
                    ignoredPositions += layout.nodes().size();
                    continue;
                }
                Set<Long> modelEntities = ids(entitiesByModel.get(model.id()));
                Map<Long, ModelExchangeDto.Point> nodes = new LinkedHashMap<>();
                for (Map.Entry<Long, ModelExchangeDto.Point> node : layout.nodes().entrySet()) {
                    if (!modelEntities.contains(node.getKey())) ignoredPositions++;
                    else nodes.put(node.getKey(), node.getValue());
                }
                boolean imported = newModels.contains(model.id()) && !nodes.isEmpty();
                if (imported) {
                    logicalNodes.put(model.id(), nodes);
                    if (layout.viewport() != null) logicalViewports.put(model.id(), layout.viewport());
                }
                items.add(new PlanItem("ERD_LAYOUT", "LOGICAL:" + string(model, "modelName"), string(model, "modelName"), imported ? Status.NEW : Status.EXCLUDED,
                        imported ? null : Reason.RELATED_NOT_IMPORTED, ordered("nodes", imported ? nodes.size() : 0, "ignored", imported ? 0 : nodes.size())));
            }
        }

        ModelExchangeDto.Plan result(String requestId) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("SCHEMA", newSchemas.size());
            counts.put("PHYSICAL_TABLE", newTables.size());
            counts.put("PHYSICAL_COLUMN", newTables.stream().mapToInt(id -> columnsByTable.getOrDefault(id, List.of()).size()).sum());
            counts.put("PHYSICAL_RELATION", newRelations.size());
            counts.put("LOGICAL_MODEL", newModels.size());
            counts.put("LOGICAL_ENTITY", newModels.stream().mapToInt(id -> entitiesByModel.getOrDefault(id, List.of()).size()).sum());
            counts.put("TABLE_MAPPING", newMappings.size());
            counts.put("PHYSICAL_VIEW", newViews.size());
            counts.put("ERD_LAYOUT_NODE", physicalNodes.size() + logicalNodes.values().stream().mapToInt(Map::size).sum());
            boolean applicable = !newTables.isEmpty() || !newModels.isEmpty() || !newViews.isEmpty();
            // 배치를 더할 사용자 배치의 버전 : 그 사이 사용자가 배치를 저장했으면 다시 계획한다 (오른쪽에 둘 위치가 달라진다)
            String layoutVersion = targetLayout == null || physicalNodes.isEmpty() ? "" : String.valueOf(targetLayout.getVersion());
            String token = sha256(JSON.writeValueAsString(List.of(projectId, sha256(JSON.writeValueAsString(document)),
                    selection == null ? "" : JSON.writeValueAsString(selection), JSON.writeValueAsString(items), warnings, layoutVersion)));
            return new ModelExchangeDto.Plan(projectId, target.getProjectName(), token, requestId, applicable, items, counts, warnings);
        }

        // -------------------------------------------------------------- 반영

        Map<String, Integer> create() {
            Map<String, Integer> created = new LinkedHashMap<>();
            Map<Long, SchemaInfo> schemaMap = new HashMap<>();
            // 기본 스키마는 처음 쓸 때 만든다 (SchemaService.resolveSchema) : 아직 없는 프로젝트에 DEFAULT 를 일반 스키마로 만들면 그 뒤 기본 스키마 생성이 같은 이름으로
            // UNIQUE 위반이 된다. 그래서 그때는 기본 스키마로 만든다 (프로젝트 행을 배타로 잡고 있어 resolveSchema 와 겹치지 않는다)
            boolean hasDefault = !entityManager.createQuery("select s.id from SchemaInfo s where s.projectId = :p and s.isDefault = true", Long.class)
                    .setParameter("p", projectId).setMaxResults(1).getResultList().isEmpty();
            for (Long id : newSchemas) {
                Item item = schemas.get(id);
                String name = string(item, "schemaName");
                boolean asDefault = !hasDefault && SchemaInfo.DEFAULT_NAME.equalsIgnoreCase(name);
                SchemaInfo schema = SchemaInfo.builder().projectId(projectId).schemaName(asDefault ? SchemaInfo.DEFAULT_NAME : name).schemaDescription(text(item, "schemaDescription"))
                        .isDefault(asDefault).build();
                entityManager.persist(schema);
                schemaMap.put(id, schema);
            }
            count(created, "SCHEMA", newSchemas.size());
            Integer last = entityManager.createQuery("select max(t.sortOrder) from TableInfo t where t.projectId = :p", Integer.class).setParameter("p", projectId).getSingleResult();
            int order = last == null ? 0 : last;
            Map<Long, TableInfo> tableMap = new HashMap<>();
            Map<Long, ColumnInfo> columnMap = new HashMap<>();
            Map<String, StandardTerm> terms = new HashMap<>();
            for (Item item : tables.values()) {
                if (!newTables.contains(item.id())) continue;
                Long schemaRef = ref(item, "schema");
                SchemaInfo schema = schemaMap.containsKey(schemaRef) ? schemaMap.get(schemaRef)
                        : entityManager.getReference(SchemaInfo.class, targetSchemas.get(lower(schemaName(item))));
                TableInfo table = TableInfo.builder().projectId(projectId).schema(schema).tableName(string(item, "tableName")).tableKorName(text(item, "tableKorName"))
                        .tableDescription(text(item, "tableDescription")).sourceComment(text(item, "sourceComment")).bizDescription(text(item, "bizDescription"))
                        .isMasterTable(bool(item, "isMasterTable")).sortOrder(++order).isApproval(false).columns(new ArrayList<>()).build();
                entityManager.persist(table);
                tableMap.put(item.id(), table);
                for (Item c : columnsByTable.getOrDefault(item.id(), List.of())) {
                    String termName = string(c, "standardTermsName");
                    StandardTerm term = termName == null || !targetTerms.containsKey(termName) ? null
                            : terms.computeIfAbsent(termName, n -> entityManager.getReference(StandardTerm.class, targetTerms.get(n)));
                    ColumnInfo column = ColumnInfo.builder().projectId(projectId).tableInfo(table).tableName(table.getTableName()).columnName(string(c, "columnName"))
                            .columnDesc(text(c, "columnDesc")).sourceComment(text(c, "sourceComment")).bizDescription(text(c, "bizDescription")).dataType(string(c, "dataType"))
                            .maxLength(integer(c, "maxLength")).precision(decimal(c, "precision")).scale(integer(c, "scale")).isNullable(bool(c, "isNullable"))
                            .defaultValue(safeText(c, "defaultValue")).identity(c.state().get("identity") == null ? null : ColumnInfo.Identity.valueOf((String) c.state().get("identity")))
                            .sortOrder(integer(c, "sortOrder")).isMasterData(bool(c, "isMasterData")).refTableName(string(c, "refTableName")).isRequired(bool(c, "isRequired"))
                            .isSensitive(bool(c, "isSensitive")).isPk(bool(c, "isPk")).isUnique(bool(c, "isUnique")).isIndex(bool(c, "isIndex")).isEncrypted(bool(c, "isEncrypted"))
                            .example(text(c, "example")).isApproval(false).standardTerms(term).build();
                    entityManager.persist(column);
                    table.getColumns().add(column);
                    columnMap.put(c.id(), column);
                }
            }
            count(created, "PHYSICAL_TABLE", tableMap.size());
            count(created, "PHYSICAL_COLUMN", columnMap.size());
            int indexes = 0;
            int constraints = 0;
            for (Map.Entry<Long, TableInfo> entry : tableMap.entrySet()) {
                for (Item item : constraintsByTable.getOrDefault(entry.getKey(), List.of())) {
                    if (skipped.contains(item.id())) continue;
                    TableConstraint constraint = TableConstraint.builder().projectId(projectId).tableInfo(entry.getValue()).constraintName(string(item, "constraintName"))
                            .constraintType(TableConstraint.Type.valueOf(string(item, "constraintType"))).checkExpression(string(item, "checkExpression")).isApproval(false)
                            .columns(new ArrayList<>()).build();
                    for (Map<String, Object> member : children(item, "columns")) {
                        ColumnInfo column = columnMap.get(longValue(member.get("columnId")));
                        if (column != null) constraint.getColumns().add(TableConstraintColumn.builder().tableConstraint(constraint).column(column)
                                .columnOrder(intValue(member.get("columnOrder"))).build());
                    }
                    entityManager.persist(constraint);
                    constraints++;
                }
                for (Item item : indexesByTable.getOrDefault(entry.getKey(), List.of())) {
                    if (skipped.contains(item.id())) continue;
                    TableIndex index = TableIndex.builder().projectId(projectId).tableInfo(entry.getValue()).indexName(string(item, "indexName")).indexType(string(item, "indexType"))
                            .isUnique(bool(item, "isUnique")).condition(string(item, "condition")).indexSource(TableIndex.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
                    for (Map<String, Object> member : children(item, "columns")) {
                        Long columnRef = longValue(member.get("columnId"));
                        index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column(columnRef == null ? null : columnMap.get(columnRef))
                                .expression((String) member.get("expression")).sort((String) member.get("sort")).columnOrder(intValue(member.get("columnOrder"))).build());
                    }
                    entityManager.persist(index);
                    indexes++;
                }
            }
            count(created, "PHYSICAL_INDEX", indexes);
            count(created, "PHYSICAL_CONSTRAINT", constraints);
            int relationCount = 0;
            for (Item item : relations) {
                if (!newRelations.contains(item.id())) continue;
                TableRelation relation = TableRelation.builder().projectId(projectId).sourceTable(tableMap.get(ref(item, "sourceTable"))).targetTable(tableMap.get(ref(item, "targetTable")))
                        .relationName(string(item, "relationName")).relationType(TableRelation.Type.valueOf(string(item, "relationType")))
                        .relationSource(TableRelation.Source.valueOf(string(item, "relationSource"))).isApproval(false)
                        .deleteRule(rule(item, "deleteRule")).updateRule(rule(item, "updateRule")).columns(new ArrayList<>()).build();
                for (Map<String, Object> pair : children(item, "columns")) {
                    relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(columnMap.get(longValue(pair.get("sourceColumnId"))))
                            .targetColumn(columnMap.get(longValue(pair.get("targetColumnId")))).columnOrder(intValue(pair.get("columnOrder"))).build());
                }
                entityManager.persist(relation);
                relationCount++;
            }
            count(created, "PHYSICAL_RELATION", relationCount);

            // VIEW : 먼저 모두 만들고 참조를 잇는다 (같이 가져오는 VIEW 끼리 참조할 수 있다). 정의 SQL 은 저장만 하고 실행하지 않는다
            Map<Long, PhysicalView> viewMap = new HashMap<>();
            for (Long id : newViews) {
                Item item = views.get(id);
                Long schemaRef = ref(item, "schema");
                SchemaInfo schema = schemaMap.containsKey(schemaRef) ? schemaMap.get(schemaRef)
                        : entityManager.getReference(SchemaInfo.class, targetSchemas.get(lower(schemaName(item))));
                PhysicalView view = PhysicalView.builder().projectId(projectId).schema(schema).viewName(string(item, "viewName")).viewKorName(string(item, "viewKorName"))
                        .viewDescription(string(item, "viewDescription")).definition(string(item, "definition")).isApproval(false)
                        .columns(new ArrayList<>()).references(new ArrayList<>()).build();
                for (Map<String, Object> c : children(item, "columns")) {
                    view.getColumns().add(PhysicalViewColumn.builder().view(view).columnName((String) c.get("columnName")).columnKorName((String) c.get("columnKorName"))
                            .dataType((String) c.get("dataType")).columnOrder(intValue(c.get("columnOrder"))).columnDescription((String) c.get("columnDescription")).build());
                }
                entityManager.persist(view);
                viewMap.put(id, view);
            }
            int viewReferences = 0;
            for (Long id : newViews) {
                PhysicalView view = viewMap.get(id);
                Set<String> added = new HashSet<>();
                for (ViewRef r : viewRefs.getOrDefault(id, List.of())) {
                    TableInfo table = r.newTable() != null ? tableMap.get(r.newTable()) : r.existingTable() != null ? entityManager.getReference(TableInfo.class, r.existingTable()) : null;
                    PhysicalView referenced = r.newView() != null ? viewMap.get(r.newView()) : r.existingView() != null ? entityManager.getReference(PhysicalView.class, r.existingView()) : null;
                    // 같은 대상을 두 번 잇지 않는다 (기존 객체에 이으면서 겹칠 수 있다)
                    String key = table != null ? "T" + System.identityHashCode(table) : referenced != null ? "V" + System.identityHashCode(referenced) : "X" + lower(r.schemaName() + "." + r.objectName());
                    if (!added.add(key)) continue;
                    PhysicalViewReference.Type type = table != null ? PhysicalViewReference.Type.TABLE : referenced != null ? PhysicalViewReference.Type.VIEW : r.type();
                    view.getReferences().add(PhysicalViewReference.builder().view(view).referenceType(type).referencedTable(table).referencedView(referenced)
                            .schemaName(table == null && referenced == null ? r.schemaName() : null).objectName(table == null && referenced == null ? r.objectName() : null).build());
                    viewReferences++;
                }
            }
            count(created, "PHYSICAL_VIEW", viewMap.size());
            count(created, "PHYSICAL_VIEW_REFERENCE", viewReferences);

            Map<Long, LogicalEntity> entityMap = new HashMap<>();
            Map<Long, LogicalAttribute> attributeMap = new HashMap<>();
            int relationships = 0;
            for (Item item : models.values()) {
                if (!newModels.contains(item.id())) continue;
                LogicalModel model = LogicalModel.builder().projectId(projectId).modelName(string(item, "modelName")).modelDescription(text(item, "modelDescription")).build();
                entityManager.persist(model);
                for (Item e : entitiesByModel.getOrDefault(item.id(), List.of())) {
                    LogicalEntity entity = LogicalEntity.builder().projectId(projectId).model(model).entityName(string(e, "entityName"))
                            .entityDescription(text(e, "entityDescription")).isApproval(false).identifiers(new ArrayList<>()).attributes(new ArrayList<>()).build();
                    entityManager.persist(entity);
                    entityMap.put(e.id(), entity);
                    for (Item a : attributesByEntity.getOrDefault(e.id(), List.of())) {
                        String termName = string(a, "standardTermName");
                        StandardTerm term = termName == null || !targetTerms.containsKey(termName) ? null
                                : terms.computeIfAbsent(termName, n -> entityManager.getReference(StandardTerm.class, targetTerms.get(n)));
                        LogicalAttribute attribute = LogicalAttribute.builder().entity(entity).attributeName(string(a, "attributeName"))
                                .attributeDescription(text(a, "attributeDescription")).dataType(string(a, "dataType")).isRequired(bool(a, "isRequired"))
                                .sortOrder(integer(a, "sortOrder")).standardTerm(term).build();
                        entityManager.persist(attribute);
                        entity.getAttributes().add(attribute);
                        attributeMap.put(a.id(), attribute);
                    }
                    for (Item i : identifiersByEntity.getOrDefault(e.id(), List.of())) {
                        LogicalIdentifier identifier = LogicalIdentifier.builder().entity(entity).identifierName(string(i, "identifierName")).isPrimary(bool(i, "isPrimary"))
                                .attributes(new ArrayList<>()).build();
                        for (Map<String, Object> member : children(i, "attributes")) {
                            LogicalAttribute attribute = attributeMap.get(longValue(member.get("attributeId")));
                            if (attribute != null) identifier.getAttributes().add(LogicalIdentifierAttribute.builder().identifier(identifier).attribute(attribute)
                                    .sortOrder(intValue(member.get("sortOrder"))).build());
                        }
                        entityManager.persist(identifier);
                        entity.getIdentifiers().add(identifier);
                    }
                }
                for (Item r : relationshipsByModel.getOrDefault(item.id(), List.of())) {
                    LogicalEntity source = entityMap.get(ref(r, "sourceEntity"));
                    LogicalEntity targetEntity = entityMap.get(ref(r, "targetEntity"));
                    if (source == null || targetEntity == null) continue;
                    entityManager.persist(LogicalRelationship.builder().projectId(projectId).model(model).sourceEntity(source).targetEntity(targetEntity)
                            .relationshipName(string(r, "relationshipName")).relationshipDescription(text(r, "relationshipDescription")).isIdentifying(bool(r, "isIdentifying"))
                            .sourceMin(integer(r, "sourceMin")).sourceMax(integer(r, "sourceMax")).targetMin(integer(r, "targetMin")).targetMax(integer(r, "targetMax"))
                            .isApproval(false).build());
                    relationships++;
                }
            }
            count(created, "LOGICAL_MODEL", newModels.size());
            count(created, "LOGICAL_ENTITY", entityMap.size());
            count(created, "LOGICAL_ATTRIBUTE", attributeMap.size());
            count(created, "LOGICAL_RELATIONSHIP", relationships);
            int columnMappings = 0;
            for (Item item : tableMappings) {
                if (!newMappings.contains(item.id())) continue;
                EntityTableMapping mapping = EntityTableMapping.builder().projectId(projectId).entity(entityMap.get(ref(item, "entity"))).table(tableMap.get(ref(item, "table"))).build();
                entityManager.persist(mapping);
                for (Item c : columnMappingsByMapping.getOrDefault(item.id(), List.of())) {
                    LogicalAttribute attribute = attributeMap.get(ref(c, "attribute"));
                    ColumnInfo column = columnMap.get(ref(c, "column"));
                    if (attribute == null || column == null) continue;
                    entityManager.persist(AttributeColumnMapping.builder().mapping(mapping).attribute(attribute).column(column).build());
                    columnMappings++;
                }
            }
            count(created, "TABLE_MAPPING", newMappings.size());
            count(created, "COLUMN_MAPPING", columnMappings);
            entityManager.flush();
            count(created, "ERD_LAYOUT_NODE", createLayouts(tableMap, entityMap));
            try {
                entityManager.flush();
            } catch (OptimisticLockException e) {
                // 계획 뒤에 같은 사용자가 다른 탭에서 배치를 저장했다 : 전체를 되돌리고 다시 계획하게 한다
                throw new CustomException(ErrorCode.MODEL_EXCHANGE_STALE);
            }
            return created;
        }

        /**
         * 배치를 새 ID 로 옮긴다. 물리 : 가져오는 사용자의 배치에 더한다 — 기존 좌표는 그대로 두고, 이미 배치가 있으면 기존 노드 오른쪽으로 띄운다 (화면 위치는 그대로).
         * 논리 : 새 논리 모델마다 그 사용자의 배치를 새로 만든다. 다른 사용자의 배치는 건드리지 않는다
         */
        private int createLayouts(Map<Long, TableInfo> tableMap, Map<Long, LogicalEntity> entityMap) {
            int placed = 0;
            if (!physicalNodes.isEmpty()) {
                Map<Long, ModelExchangeDto.Point> moved = shift(physicalNodes, targetLayoutNodes);
                ErdLayout layout = targetLayout;
                Map<Long, Map<String, Double>> merged = new java.util.TreeMap<>();
                if (layout != null) {
                    JSON.readTree(layout.getNodePositions()).properties().forEach(e -> merged.put(Long.valueOf(e.getKey()), Map.of("x", e.getValue().path("x").asDouble(), "y", e.getValue().path("y").asDouble())));
                } else {
                    ModelExchangeDto.Viewport viewport = document.layouts().physical().viewport();
                    layout = ErdLayout.builder().projectId(projectId).accountId(accountId).viewportX(viewport == null ? 0 : viewport.x()).viewportY(viewport == null ? 0 : viewport.y())
                            .viewportZoom(viewport == null ? 1 : viewport.zoom()).build();
                }
                for (Map.Entry<Long, ModelExchangeDto.Point> node : moved.entrySet()) {
                    TableInfo table = tableMap.get(node.getKey());
                    if (table == null || merged.containsKey(table.getId())) continue;
                    merged.put(table.getId(), Map.of("x", node.getValue().x(), "y", node.getValue().y()));
                    placed++;
                }
                layout.setNodePositions(JSON.writeValueAsString(merged));
                if (layout.getId() == null) entityManager.persist(layout);
            }
            for (Map.Entry<Long, Map<Long, ModelExchangeDto.Point>> entry : logicalNodes.entrySet()) {
                Map<Long, Map<String, Double>> nodes = new java.util.TreeMap<>();
                Long modelId = null;
                for (Map.Entry<Long, ModelExchangeDto.Point> node : entry.getValue().entrySet()) {
                    LogicalEntity entity = entityMap.get(node.getKey());
                    if (entity == null) continue;
                    modelId = entity.getModel().getId();
                    nodes.put(entity.getId(), Map.of("x", node.getValue().x(), "y", node.getValue().y()));
                }
                if (modelId == null) continue;
                ModelExchangeDto.Viewport viewport = logicalViewports.get(entry.getKey());
                entityManager.persist(LogicalErdLayout.builder().projectId(projectId).modelId(modelId).accountId(accountId).nodePositions(JSON.writeValueAsString(nodes))
                        .viewportX(viewport == null ? 0 : viewport.x()).viewportY(viewport == null ? 0 : viewport.y()).viewportZoom(viewport == null ? 1 : viewport.zoom()).build());
                placed += nodes.size();
            }
            return placed;
        }

        // -------------------------------------------------------------- 검사

        private boolean validTable(Item table, List<Item> tableColumns) {
            if (!name(string(table, "tableName")) || schemaName(table) == null || tableColumns.isEmpty()) return false;
            if (!texts(table, "tableKorName", "tableDescription", "sourceComment", "bizDescription")) return false;
            Set<String> seen = new HashSet<>();
            for (Item column : tableColumns) {
                String name = string(column, "columnName");
                if (!name(name) || !seen.add(lower(name)) || !name(string(column, "dataType"))) return false;
                if (!texts(column, "columnDesc", "sourceComment", "bizDescription", "example", "refTableName")) return false;
                Object identity = column.state().get("identity");
                if (identity != null && java.util.Arrays.stream(ColumnInfo.Identity.values()).noneMatch(value -> value.name().equals(identity))) return false;
                if (!safe(string(column, "defaultValue"))) return false;
            }
            return true;
        }

        private boolean validRelation(Item relation) {
            if (!name(string(relation, "relationName")) && relation.state().get("relationName") != null) return false;
            try {
                TableRelation.Type.valueOf(string(relation, "relationType"));
                TableRelation.Source.valueOf(string(relation, "relationSource"));
                rule(relation, "deleteRule");
                rule(relation, "updateRule");
            } catch (RuntimeException e) {
                return false;
            }
            for (Map<String, Object> pair : children(relation, "columns")) {
                if (!columns.containsKey(longValue(pair.get("sourceColumnId"))) || !columns.containsKey(longValue(pair.get("targetColumnId")))) return false;
            }
            return !children(relation, "columns").isEmpty();
        }

        private boolean validModel(Item model, List<Item> modelEntities) {
            if (!name(string(model, "modelName")) || !texts(model, "modelDescription")) return false;
            Set<String> seen = new HashSet<>();
            for (Item entity : modelEntities) {
                if (!name(string(entity, "entityName")) || !seen.add(lower(string(entity, "entityName"))) || !texts(entity, "entityDescription")) return false;
                for (Item attribute : attributesByEntity.getOrDefault(entity.id(), List.of())) {
                    if (!name(string(attribute, "attributeName")) || !texts(attribute, "attributeDescription")) return false;
                }
            }
            return true;
        }

        /** VIEW : 이름은 VIEW 관리와 같은 식별자 규칙, 글자 길이, 출력 컬럼(이름 중복 없음)·참조 행의 모양과 수 */
        private boolean validView(Item view) {
            String name = string(view, "viewName");
            if (name == null || !SchemaInfo.NAME_PATTERN.matcher(name).matches() || !name.equals(name.toUpperCase(Locale.ROOT)) || schemaName(view) == null) return false;
            if (!texts(view, "viewKorName", "viewDescription", "definition") || !within(string(view, "viewKorName"), VIEW_TEXT_MAX)
                    || !within(string(view, "viewDescription"), VIEW_DESCRIPTION_MAX)) return false;
            List<Map<String, Object>> columns = children(view, "columns");
            List<Map<String, Object>> references = children(view, "references");
            if (columns.size() > VIEW_COLUMN_MAX || references.size() > VIEW_REFERENCE_MAX) return false;
            Set<String> names = new HashSet<>();
            for (Map<String, Object> column : columns) {
                Object columnName = column.get("columnName");
                if (!(columnName instanceof String c) || !SchemaInfo.NAME_PATTERN.matcher(c).matches() || !names.add(c.toUpperCase(Locale.ROOT))) return false;
                for (String field : List.of("columnKorName", "dataType", "columnDescription")) {
                    Object value = column.get(field);
                    if (value != null && !(value instanceof String text && text.length() <= VIEW_TEXT_MAX)) return false;
                }
                if (column.get("columnOrder") != null && !(column.get("columnOrder") instanceof Number)) return false;
            }
            for (Map<String, Object> reference : references) {
                Object type = reference.get("referenceType");
                if (!"TABLE".equals(type) && !"VIEW".equals(type)) return false;
                Object tableRef = reference.get("referencedTableId");
                Object viewRef = reference.get("referencedViewId");
                if (tableRef != null && !(tableRef instanceof Number) || viewRef != null && !(viewRef instanceof Number) || tableRef != null && viewRef != null) return false;
                if (viewRef != null && ((Number) viewRef).longValue() == view.id()) return false; // 자기 자신
                if (tableRef == null && viewRef == null) {
                    // 모델 밖 참조 : VIEW 관리와 같은 이름 모양 (공백·따옴표·마침표 없음)
                    Object objectName = reference.get("objectName");
                    Object schemaName = reference.get("schemaName");
                    if (!(objectName instanceof String o) || !EXTERNAL_NAME.matcher(o).matches()) return false;
                    if (schemaName != null && !(schemaName instanceof String sn && EXTERNAL_NAME.matcher(sn).matches())) return false;
                }
                for (String field : List.of("referencedTableName", "referencedViewName", "objectName", "schemaName")) {
                    Object value = reference.get(field);
                    if (value != null && !(value instanceof String text && name(text))) return false;
                }
            }
            return true;
        }

        private String viewKey(Item view) {
            return schemaName(view) + "." + string(view, "viewName");
        }

        /** 인덱스·제약조건 : 이름·종류, SQL 식(조건·CHECK·표현식)에 문장 구분·주석이 없을 것 */
        private boolean safeChild(Item item) {
            if (item.state().containsKey("constraintType")) {
                if (!name(string(item, "constraintName"))) return false;
                try {
                    TableConstraint.Type.valueOf(string(item, "constraintType"));
                } catch (RuntimeException e) {
                    return false;
                }
                return safe(string(item, "checkExpression"));
            }
            if (!name(string(item, "indexName")) || !safe(string(item, "condition"))) return false;
            String source = string(item, "indexSource");
            if (source != null && !source.equals("USER")) return false;
            for (Map<String, Object> member : children(item, "columns")) {
                if (!safe((String) member.get("expression")) || !safe((String) member.get("sort"))) return false;
            }
            return true;
        }

        private String schemaName(Item table) {
            Item schema = schemas.get(ref(table, "schema"));
            return schema == null ? null : string(schema, "schemaName");
        }

        private String tableKey(Long tableId) {
            Item table = tables.get(tableId);
            return table == null ? "?" : schemaName(table) + "." + string(table, "tableName");
        }
    }

    // ------------------------------------------------------------------ 값 읽기 (파일의 값은 믿지 않는다 : 모양이 다르면 예외 → 400)

    private static Long ref(Item item, String field) {
        return longValue(item.state().get(field + "Id"));
    }

    private static Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        throw invalid("번호가 올바르지 않습니다.");
    }

    private static Integer intValue(Object value) {
        Long number = longValue(value);
        return number == null ? null : Math.toIntExact(number);
    }

    private static Integer integer(Item item, String field) {
        return intValue(item.state().get(field));
    }

    private static BigDecimal decimal(Item item, String field) {
        Object value = item.state().get(field);
        if (value == null) return null;
        if (value instanceof Number || value instanceof String) return new BigDecimal(String.valueOf(value));
        throw invalid("숫자가 올바르지 않습니다.");
    }

    private static Boolean bool(Item item, String field) {
        Object value = item.state().get(field);
        if (value == null || value instanceof Boolean) return (Boolean) value;
        throw invalid("참/거짓 값이 올바르지 않습니다.");
    }

    private static String string(Item item, String field) {
        Object value = item.state().get(field);
        if (value == null || value instanceof String) return (String) value;
        throw invalid("글자 값이 올바르지 않습니다.");
    }

    private static String text(Item item, String field) {
        String value = string(item, field);
        return value == null || value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }

    private static String safeText(Item item, String field) {
        String value = string(item, field);
        return safe(value) ? value : null;
    }

    private static TableRelation.Rule rule(Item item, String field) {
        String value = string(item, field);
        return value == null ? null : TableRelation.Rule.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Item item, String field) {
        Object value = item.state().get(field);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static boolean name(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_NAME && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean texts(Item item, String... fields) {
        for (String field : fields) {
            Object value = item.state().get(field);
            if (value != null && !(value instanceof String)) return false;
        }
        return true;
    }

    /** 모델 밖 참조 객체 이름 : VIEW 관리(PhysicalViewService.EXTERNAL_NAME)와 같다 */
    private static final Pattern EXTERNAL_NAME = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_$#]{0,254}$");

    /** VIEW 정의 : VIEW 관리와 같은 정적 검사 (조회 문 하나, 다른 명령·닫히지 않은 따옴표·주석 없음). 실행하지 않는다 */
    private static boolean safeDefinition(DbType dbType, String definition) {
        try {
            ViewDefinitionSql.check(dbType, definition);
            return true;
        } catch (CustomException e) {
            return false;
        }
    }

    private static boolean within(String value, int max) {
        return value == null || value.length() <= max;
    }

    /** from 에서 출발해 to 에 닿는가 (한 단계 이상) */
    private static boolean reaches(Map<Long, Set<Long>> edges, Long from, Long to) {
        Deque<Long> queue = new ArrayDeque<>(edges.getOrDefault(from, Set.of()));
        Set<Long> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (current.equals(to)) return true;
            if (seen.add(current)) queue.addAll(edges.getOrDefault(current, Set.of()));
        }
        return false;
    }

    /** 이미 배치가 있으면 가져온 좌표 묶음을 기존 노드의 오른쪽(같은 위쪽 높이)으로 옮긴다. 옮기면 한도를 넘는 경우는 그대로 둔다 */
    static Map<Long, ModelExchangeDto.Point> shift(Map<Long, ModelExchangeDto.Point> imported, Map<Long, ModelExchangeDto.Point> existing) {
        if (existing.isEmpty() || imported.isEmpty()) return imported;
        double right = existing.values().stream().mapToDouble(ModelExchangeDto.Point::x).max().orElse(0) + LAYOUT_GAP;
        double top = existing.values().stream().mapToDouble(ModelExchangeDto.Point::y).min().orElse(0);
        double minX = imported.values().stream().mapToDouble(ModelExchangeDto.Point::x).min().orElse(0);
        double minY = imported.values().stream().mapToDouble(ModelExchangeDto.Point::y).min().orElse(0);
        Map<Long, ModelExchangeDto.Point> moved = new LinkedHashMap<>();
        for (Map.Entry<Long, ModelExchangeDto.Point> e : imported.entrySet()) {
            double x = e.getValue().x() - minX + right;
            double y = e.getValue().y() - minY + top;
            if (!coordinate(x) || !coordinate(y)) return imported;
            moved.put(e.getKey(), new ModelExchangeDto.Point(x, y));
        }
        return moved;
    }

    /** VIEW 참조 대상 : newTable/newView(파일 번호, 같이 가져온다) · existingTable/existingView(대상 프로젝트 ID) · schemaName/objectName(모델 밖) 중 하나 */
    private record ViewRef(PhysicalViewReference.Type type, Long newTable, Long newView, Long existingTable, Long existingView, String schemaName, String objectName) {
    }

    private static boolean safe(String value) {
        return value == null || value.length() <= MAX_TEXT && !UNSAFE.matcher(value).find();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> concat(Collection<T> a, Collection<T> b) {
        List<T> list = new ArrayList<>(a);
        list.addAll(b);
        return list;
    }

    /** 계획 토큰(JSON)에 들어가므로 순서가 정해진 맵 */
    private static Map<String, Integer> ordered(String k1, int v1, String k2, int v2) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static void count(Map<String, Integer> counts, String type, int value) {
        counts.put(type, value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
