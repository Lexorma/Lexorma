package com.lexorma.ai.mcp.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.project.service.ProjectAuthority;
import com.lexorma.ai.tool.MetadataToolSession;
import com.lexorma.ai.tool.MetadataToolset;
import com.lexorma.ai.mcp.domain.entity.McpCallHistory;
import com.lexorma.ai.mcp.domain.entity.McpProjectSetting;
import com.lexorma.ai.mcp.domain.entity.McpToken;
import com.lexorma.ai.mcp.repository.McpCallHistoryRepository;
import com.lexorma.ai.mcp.repository.McpProjectSettingRepository;
import com.lexorma.ai.mcp.repository.McpTokenRepository;
import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 선택형 내장 MCP 서버 (DEC-31, docs/completed/embedded-mcp-dec31.md) : 같은 Spring Boot 안의 Streamable HTTP (JSON 응답만, SSE 스트림·세션 없음), JSON-RPC 2.0.
 * <ul>
 *   <li>인증 : Bearer 토큰 → 해시로 찾아 폐기·만료·프로젝트 MCP 사용·프로젝트 활성·<b>발급 계정이 지금도 그 프로젝트의 관리자·ADMIN(멤버)</b> 인지 요청마다 DB 로 확인한다.
 *       실패는 이유를 나누지 않고 401 하나로 (메타데이터·토큰 존재 여부를 알려 주지 않는다)</li>
 *   <li>도구 : AI-4 의 읽기 전용 메타데이터 Tool 11개(D-35 D35-3b 논리 모델 3개 포함 — 내부 AI 와 같은 구현 {@code LogicalMetadataService})와 AI-7 품질 진단 3개
 *       (내부 AI QUALITY 범위와 같은 {@code QualityMetadataTools})를 {@link MetadataToolset#run} 으로 (실행 컨텍스트 재검증 VIEWER · 읽기 전용 트랜잭션 · 결과 크기 상한) +
 *       MCP 전용 읽기 2개(getProjectInfo, getLogicalModel). 프로젝트는 토큰의 것만 : 도구 입력에 프로젝트를 받지 않는다. 원본 DB·원본 VIEW 정의·쓰기·SQL 실행 도구는 없다</li>
 *   <li>상한 : 토큰마다 분당 {@link #RATE_PER_MINUTE} 요청, 결과 {@link #RESULT_MAX_CHARS} 글자. 감사 : 호출마다 도구·결과·일시 (입력·응답·토큰 원문 없음)</li>
 * </ul>
 * AI 내부 Tool Calling(AI-4, 모델이 Lexorma 안에서 부름)과 달리 여기서는 외부 클라이언트가 부른다 : AI Provider 가 없어도 동작한다.
 */
@Service
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    public static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final List<String> SHARED_TOOLS = List.of("listSchemas", "searchTables", "getTableDetail", "getTableColumns", "getTableRelations",
            "searchStandardTerms", "searchStandardWords", "searchStandardDomains",
            "getLogicalModelOverview", "searchLogicalEntities", "getLogicalEntityDetail",
            // AI-7 (AI7-D05·D28) : 저장된 품질 진단 결과 — 내부 AI 의 기능 범위 선택과 무관하게 항상
            "getQualityInspectionOverview", "searchQualityFindings", "getQualityFindingDetail");
    static final int RATE_PER_MINUTE = 120;
    static final int RESULT_MAX_CHARS = 24_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpTokenRepository tokenRepository;
    private final McpProjectSettingRepository settingRepository;
    private final McpCallHistoryRepository callRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAuthority projectAuthority;
    private final ExecutionContextVerifier verifier;
    private final MetadataToolset toolset;
    private final LogicalSubtypeService subtypeService;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;
    private final Map<Long, Deque<Long>> rate = new ConcurrentHashMap<>();

    @PersistenceContext
    private EntityManager entityManager;

    public McpServerService(McpTokenRepository tokenRepository, McpProjectSettingRepository settingRepository, McpCallHistoryRepository callRepository,
                            ProjectRepository projectRepository, ProjectAuthority projectAuthority, ExecutionContextVerifier verifier, MetadataToolset toolset,
                            LogicalSubtypeService subtypeService, PlatformTransactionManager transactionManager) {
        this.tokenRepository = tokenRepository;
        this.settingRepository = settingRepository;
        this.callRepository = callRepository;
        this.projectRepository = projectRepository;
        this.projectAuthority = projectAuthority;
        this.verifier = verifier;
        this.toolset = toolset;
        this.subtypeService = subtypeService;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** 인증된 호출자 : 토큰·프로젝트·발급 계정 */
    public record Caller(Long tokenId, Long projectId, Long accountId) {
    }

    /** 인증 실패 (이유는 기록에만, 응답은 하나) */
    public static final class Denied extends RuntimeException {
        public Denied(String reason) {
            super(reason, null, false, false);
        }
    }

    /** 호출 상한 초과 */
    public static final class Limited extends RuntimeException {
        public Limited() {
            super("rate limited", null, false, false);
        }
    }

    // ------------------------------------------------------------------ 인증

    public Caller authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw new Denied("NO_TOKEN");
        String raw = authorization.substring(7).strip();
        if (!raw.startsWith(McpTokenService.TOKEN_PREFIX) || raw.length() > 100) throw new Denied("MALFORMED");
        String hash = McpTokenService.hash(raw);
        Caller caller = tx.execute(status -> {
            McpToken token = tokenRepository.findByHash(hash).orElse(null);
            if (token == null) return null;
            LocalDateTime now = LocalDateTime.now();
            String reason = token.getRevokedAt() != null ? "REVOKED"
                    : !token.getExpiresAt().isAfter(now) ? "EXPIRED"
                    : !settingRepository.findByProjectId(token.getProjectId()).map(McpProjectSetting::getEnabled).orElse(false) ? "PROJECT_DISABLED"
                    : !projectRepository.findById(token.getProjectId()).map(Project::getIsActive).orElse(false) ? "PROJECT_INACTIVE"
                    : !projectAuthority.canManage(token.getProjectId(), token.getAccountId()) ? "ISSUER_NOT_MANAGER" : null;
            if (reason != null) {
                audit(token.getProjectId(), token.getId(), "auth:" + reason, McpCallHistory.Result.DENIED);
                return new Caller(token.getId(), null, null);
            }
            // 마지막 사용 일시는 분 단위로만 갱신한다 (요청마다 UPDATE 하지 않는다)
            if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(now.minusMinutes(1))) token.setLastUsedAt(now);
            return new Caller(token.getId(), token.getProjectId(), token.getAccountId());
        });
        if (caller == null || caller.projectId() == null) {
            log.info("MCP 인증 거절 : token={}", caller == null ? "unknown" : caller.tokenId());
            throw new Denied("DENIED");
        }
        limit(caller.tokenId());
        return caller;
    }

    private void limit(Long tokenId) {
        long now = System.currentTimeMillis();
        Deque<Long> window = rate.computeIfAbsent(tokenId, id -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < now - 60_000) window.pollFirst();
            if (window.size() >= RATE_PER_MINUTE) throw new Limited();
            window.addLast(now);
        }
    }

    // ------------------------------------------------------------------ JSON-RPC

    /** @return 응답 메시지 (알림이면 null) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Caller caller, Map<String, Object> message) {
        Object id = message.get("id");
        Object method = message.get("method");
        if (!"2.0".equals(message.get("jsonrpc")) || !(method instanceof String name)) return id == null ? null : error(id, -32600, "Invalid Request");
        if (id == null) return null;   // 알림 (notifications/initialized 등) : 응답 없음
        Map<String, Object> params = message.get("params") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        return switch (name) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", tools()));
            case "tools/call" -> call(caller, id, params);
            default -> error(id, -32601, "Method not found");
        };
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        Object requested = params.get("protocolVersion");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", requested instanceof String v && SUPPORTED_VERSIONS.contains(v) ? v : SUPPORTED_VERSIONS.getFirst());
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "lexorma", "title", "Lexorma metadata (read-only)", "version", "1"));
        result.put("instructions", "Read-only metadata of one Lexorma project (the token's project): schemas, tables, columns, relations, standard terms/words/domains, "
                + "logical models, stored quality inspection results (not live checks). No SQL execution, no source database access, no changes.");
        return result;
    }

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCallback callback : toolset.mcpCallbacks()) {
            var definition = callback.getToolDefinition();
            if (!SHARED_TOOLS.contains(definition.name())) continue;
            tools.add(tool(definition.name(), definition.description(), JSON.readValue(definition.inputSchema(), Map.class)));
        }
        tools.add(tool("getProjectInfo", "Basic information about the project of this token: name, DBMS and object counts.",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)));
        tools.add(tool("getLogicalModel", "Logical models of the project. Without modelName: the list of models. With modelName: its entities "
                        + "(attributes and identifiers each entity defines directly) and relationships. Subtypes also inherit attributes and the root's primary "
                        + "identifier, which are not listed here: use getLogicalEntityDetail for effective attributes and identifiers, getLogicalModelOverview and "
                        + "searchLogicalEntities for large models.",
                Map.of("type", "object", "properties", Map.of("modelName", Map.of("type", "string", "description", "Logical model name (optional)")),
                        "additionalProperties", false)));
        return tools;
    }

    private static Map<String, Object> tool(String name, String description, Object schema) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        tool.put("annotations", Map.of("readOnlyHint", true, "destructiveHint", false, "openWorldHint", false));
        return tool;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Caller caller, Object id, Map<String, Object> params) {
        String name = params.get("name") instanceof String value ? value : null;
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        boolean known = name != null && (SHARED_TOOLS.contains(name) || name.equals("getProjectInfo") || name.equals("getLogicalModel"));
        if (!known) {
            audit(caller.projectId(), caller.tokenId(), "unknown", McpCallHistory.Result.ERROR);
            return error(id, -32602, "Unknown tool");
        }
        ExecutionContext context = ExecutionContext.async(caller.projectId(), caller.accountId(), "mcp-" + caller.tokenId());
        String text;
        boolean isError;
        try {
            if (SHARED_TOOLS.contains(name)) {
                text = toolset.run(name, arguments, new MetadataToolSession(context, "mcp-" + UUID.randomUUID(), MetadataToolset.TIMEOUT_SECONDS * 1000L));
            } else {
                verifier.verify(context, "VIEWER");
                text = readOnly.execute(status -> JSON.writeValueAsString(name.equals("getProjectInfo") ? projectInfo(caller.projectId())
                        : logicalModel(caller.projectId(), arguments.get("modelName") instanceof String model ? model : null)));
            }
            if (text != null && text.length() > RESULT_MAX_CHARS) text = name.equals("getLogicalModel") ? LOGICAL_MODEL_TOO_LARGE
                    : "{\"error\":{\"code\":\"RESULT_TOO_LARGE\",\"message\":\"The result was too large. Narrow the request.\"}}";
            isError = text == null || text.startsWith("{\"error\"");
        } catch (CustomException e) {
            // 권한·범위 오류 (그 사이 권한이 회수되었다 등) : 이유를 알려 주지 않는다
            audit(caller.projectId(), caller.tokenId(), name, McpCallHistory.Result.DENIED);
            return error(id, -32001, "Access denied");
        }
        audit(caller.projectId(), caller.tokenId(), name, isError ? McpCallHistory.Result.ERROR : McpCallHistory.Result.OK);
        return result(id, Map.of("content", List.of(Map.of("type", "text", "text", text == null ? "" : text)), "isError", isError));
    }

    // ------------------------------------------------------------------ MCP 전용 읽기 도구

    /** getLogicalModel 의 결과 상한 초과 (D35-D09) : 예전 형식 그대로 + 대신 쓸 Tool 안내. 서버가 그 Tool 을 대신 부르지 않는다 */
    static final String LOGICAL_MODEL_TOO_LARGE = "{\"error\":{\"code\":\"RESULT_TOO_LARGE\",\"message\":\"The result was too large. Narrow the request: use "
            + "getLogicalModelOverview (counts, subject areas, subtype groups), searchLogicalEntities (entities by page) and getLogicalEntityDetail (one entity, "
            + "by section) instead of the whole model.\",\"alternatives\":[\"getLogicalModelOverview\",\"searchLogicalEntities\",\"getLogicalEntityDetail\"]}}";

    /** getLogicalModel 상세의 범위 설명 (D35-D09 — 기존 칸은 그대로, 칸만 더한다) */
    static final String LOGICAL_MODEL_NOTE = "attributes and identifiers list only what each entity defines directly. A subtype (see supertype and "
            + "inheritanceLevel) also inherits the attributes of its supertypes and the root's primary identifier, so an empty identifier list does not mean it "
            + "has no primary identifier. Use getLogicalEntityDetail for effective attributes and identifiers.";

    private Map<String, Object> projectInfo(Long projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("projectName", project.getProjectName());
        info.put("dbType", project.getDbType() == null ? null : project.getDbType().name());
        info.put("schemas", count("select count(s) from SchemaInfo s where s.projectId = :p", projectId));
        info.put("tables", count("select count(t) from TableInfo t where t.projectId = :p", projectId));
        info.put("columns", count("select count(c) from ColumnInfo c where c.projectId = :p", projectId));
        info.put("relations", count("select count(r) from TableRelation r where r.projectId = :p", projectId));
        info.put("logicalModels", count("select count(m) from LogicalModel m where m.projectId = :p", projectId));
        info.put("standardTerms", count("select count(t) from StandardTerm t where t.projectId = :p", projectId));
        info.put("standardDomains", count("select count(d) from StandardDomain d where d.projectId = :p", projectId));
        return info;
    }

    private Map<String, Object> logicalModel(Long projectId, String modelName) {
        if (modelName == null) {
            List<Map<String, Object>> models = new ArrayList<>();
            for (Object[] row : entityManager.createQuery("select m.modelName, m.modelDescription, (select count(e) from LogicalEntity e where e.model = m) "
                    + "from LogicalModel m where m.projectId = :p order by m.modelName", Object[].class).setParameter("p", projectId).getResultList()) {
                models.add(Map.of("modelName", row[0], "description", row[1] == null ? "" : row[1], "entities", row[2]));
            }
            return Map.of("models", models);
        }
        List<Long> ids = entityManager.createQuery("select m.id from LogicalModel m where m.projectId = :p and m.modelName = :n", Long.class)
                .setParameter("p", projectId).setParameter("n", modelName).getResultList();
        if (ids.isEmpty()) return Map.of("error", Map.of("code", "NOT_FOUND", "message", "No logical model with that name."));
        Long modelId = ids.getFirst();
        // 상속 (D35-D09) : 기존 상속 계층(ID 투영)을 그대로 쓴다 — 상속 계산을 따로 만들지 않는다. 직접 슈퍼타입 이름과 단계만 더한다
        LogicalHierarchy hierarchy = subtypeService.hierarchy(modelId);
        Map<Long, Map<String, Object>> entities = new LinkedHashMap<>();
        for (Object[] row : entityManager.createQuery("select e.id, e.entityName, e.entityDescription, e.isApproval from LogicalEntity e where e.model.id = :m order by e.entityName",
                Object[].class).setParameter("m", modelId).getResultList()) {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("entityName", row[1]);
            entity.put("description", row[2]);
            entity.put("approved", row[3]);
            entity.put("attributes", new ArrayList<Map<String, Object>>());
            entity.put("identifiers", new ArrayList<Map<String, Object>>());
            entities.put((Long) row[0], entity);
        }
        for (Map.Entry<Long, Map<String, Object>> e : entities.entrySet()) {
            if (!hierarchy.participates(e.getKey())) continue;
            Map<String, Object> entity = e.getValue();
            Long parent = hierarchy.parent(e.getKey());
            Map<String, Object> supertype = parent == null ? null : entities.get(parent);
            if (supertype != null) entity.put("supertype", supertype.get("entityName"));
            entity.put("inheritanceLevel", hierarchy.level(e.getKey()));
        }
        for (Object[] row : entityManager.createQuery("select a.entity.id, a.attributeName, a.dataType, a.isRequired, st.commonStandardTermName from LogicalAttribute a "
                + "left join a.standardTerm st where a.entity.model.id = :m order by a.sortOrder, a.id", Object[].class).setParameter("m", modelId).getResultList()) {
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("attributeName", row[1]);
            attribute.put("dataType", row[2]);
            attribute.put("required", row[3]);
            attribute.put("standardTerm", row[4]);
            list(entities.get((Long) row[0]), "attributes").add(attribute);
        }
        for (Object[] row : entityManager.createQuery("select i.entity.id, i.identifierName, i.isPrimary, a.attributeName from LogicalIdentifierAttribute x join x.identifier i "
                + "join x.attribute a where i.entity.model.id = :m order by i.id, x.sortOrder", Object[].class).setParameter("m", modelId).getResultList()) {
            List<Map<String, Object>> identifiers = list(entities.get((Long) row[0]), "identifiers");
            Map<String, Object> identifier = identifiers.stream().filter(i -> i.get("identifierName").equals(row[1])).findFirst().orElseGet(() -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("identifierName", row[1]);
                created.put("primary", row[2]);
                created.put("attributes", new ArrayList<String>());
                identifiers.add(created);
                return created;
            });
            ((List<String>) (List<?>) identifier.get("attributes")).add((String) row[3]);
        }
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (Object[] row : entityManager.createQuery("select r.relationshipName, s.entityName, t.entityName, r.isIdentifying, r.sourceMin, r.sourceMax, r.targetMin, r.targetMax "
                + "from LogicalRelationship r join r.sourceEntity s join r.targetEntity t where r.model.id = :m order by r.id", Object[].class).setParameter("m", modelId).getResultList()) {
            Map<String, Object> relationship = new LinkedHashMap<>();
            relationship.put("name", row[0]);
            relationship.put("parent", row[1]);
            relationship.put("child", row[2]);
            relationship.put("identifying", row[3]);
            relationship.put("cardinality", row[4] + ".." + (row[5] == null ? "N" : row[5]) + " : " + row[6] + ".." + (row[7] == null ? "N" : row[7]));
            relationships.add(relationship);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelName", modelName);
        result.put("entities", entities.values());
        result.put("relationships", relationships);
        result.put("attributeScope", "DIRECT");
        result.put("hasInheritance", !hierarchy.isEmpty());
        result.put("note", LOGICAL_MODEL_NOTE);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> entity, String key) {
        return (List<Map<String, Object>>) entity.get(key);
    }

    private long count(String jpql, Long projectId) {
        return entityManager.createQuery(jpql, Long.class).setParameter("p", projectId).getSingleResult();
    }

    // ------------------------------------------------------------------

    private void audit(Long projectId, Long tokenId, String tool, McpCallHistory.Result result) {
        String name = tool.length() > 100 ? tool.substring(0, 100) : tool;
        tx.executeWithoutResult(status -> callRepository.save(McpCallHistory.builder().projectId(projectId).tokenId(tokenId).toolName(name).result(result)
                .calledAt(LocalDateTime.now()).build()));
    }

    static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("result", result);
        return message;
    }

    public static Map<String, Object> error(Object id, int code, String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("error", Map.of("code", code, "message", text));
        return message;
    }

    /** 응답에 넣지 않는 도구 이름 (테스트용) */
    static Set<String> exposed() {
        Set<String> names = new java.util.LinkedHashSet<>(SHARED_TOOLS);
        names.add("getProjectInfo");
        names.add("getLogicalModel");
        return names;
    }
}
