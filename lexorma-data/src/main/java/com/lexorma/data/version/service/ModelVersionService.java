package com.lexorma.data.version.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.domain.entity.ModelVersion;
import com.lexorma.data.version.repository.ModelVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 모델 버전 (M-9 3차) : 만들기·조회·비교. 수정·삭제는 없다. 비교 결과를 ERD 로 그릴 범위(M-10 ③)도 여기서 같은 판정으로 만든다.
 * 모델 내용은 변경 이력과 같은 필드 목록({@link ModelChangeRecorder#currentState})으로 읽은 프로젝트 전체 상태를 JSON 으로 저장한 것이다.
 * 모든 조회는 로그인한 프로젝트의 버전만 본다.
 */
@Service
@RequiredArgsConstructor
public class ModelVersionService {

    /** 모델 내용 JSON 의 형식 번호 (대상 종류 → [{id, name, state}]) */
    static final int FORMAT_VERSION = 1;
    private static final int NAME_MAX = 100;
    private static final int DESCRIPTION_MAX = 1000;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UNCHANGED = "UNCHANGED";
    private static final List<String> RESULTS = List.of("ADDED", "REMOVED", "MODIFIED", UNCHANGED);

    private final ModelVersionRepository repository;
    private final ProjectRepository projectRepository;
    private final ModelChangeRecorder recorder;

    /**
     * 현재 모델을 새 버전으로 저장한다.
     * REPEATABLE_READ : 여러 번의 조회가 같은 시점의 모델을 본다 (만드는 동안 커밋된 다른 사용자의 변경이 일부만 섞이지 않는다).
     * 내용과 버전 정보는 한 행이라 일부만 저장되는 일이 없다.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ModelVersionDto.Summary create(AccountDto accountDto, ModelVersionDto.Create request) {
        Long projectId = accountDto.getProjectId();
        String name = request == null || request.versionName() == null ? "" : request.versionName().trim();
        if (name.isEmpty()) throw invalid("{0}을(를) 입력해주세요.", Messages.get("버전명"));
        if (name.length() > NAME_MAX) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get("버전명"), NAME_MAX);
        String description = request.description() == null || request.description().isBlank() ? null : request.description().trim();
        if (description != null && description.length() > DESCRIPTION_MAX) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get("설명"), DESCRIPTION_MAX);

        // 같은 프로젝트의 버전명 중복 검사를 직렬화한다 (UNIQUE 인덱스가 마지막 보루). 그사이 지워진 프로젝트에는 만들지 않는다
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        if (repository.existsByProjectIdAndVersionName(projectId, name)) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);

        Map<String, List<ModelChangeRecorder.ObjectState>> objects = recorder.currentState(projectId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        objects.forEach((type, list) -> counts.put(type, list.size()));
        try {
            // REG_DT 는 timestamp(6) : 나노초까지 주는 시계(Linux)에서도 응답과 저장된 값이 같도록 마이크로초로 맞춘다
            ModelVersion saved = repository.saveAndFlush(ModelVersion.builder()
                    .projectId(projectId).versionName(name).description(description).formatVersion(FORMAT_VERSION)
                    .counts(JSON.writeValueAsString(counts)).snapshot(JSON.writeValueAsString(objects))
                    .createdBy(accountDto.getUsername()).createdAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)).build());
            return summary(new Object[]{saved.getId(), saved.getVersionName(), saved.getDescription(), saved.getFormatVersion(), saved.getCounts(), saved.getCreatedBy(), saved.getCreatedAt()});
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        }
    }

    @Transactional(readOnly = true)
    public Page<ModelVersionDto.Summary> search(AccountDto accountDto, int page, int size) {
        return repository.findRows(accountDto.getProjectId(), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500))).map(ModelVersionService::summary);
    }

    @Transactional(readOnly = true)
    public ModelVersionDto.Summary detail(AccountDto accountDto, Long id) {
        return repository.findRow(accountDto.getProjectId(), id).stream().findFirst().map(ModelVersionService::summary).orElseThrow(ModelVersionService::notFound);
    }

    @Transactional(readOnly = true)
    public ModelVersionDto.Snapshot snapshot(AccountDto accountDto, Long id) {
        ModelVersion version = version(accountDto, id);
        return new ModelVersionDto.Snapshot(summary(version), JSON.readTree(version.getSnapshot()));
    }

    /**
     * 기준 버전과 대상(다른 버전, targetId 가 없으면 현재 모델)을 비교한다.
     * 객체는 내부 ID 로 짝짓는다 : 이름을 바꾼 객체는 MODIFIED, 지우고 같은 이름으로 다시 만든 객체는 REMOVED + ADDED 다 (이름이 같다고 같은 객체로 보지 않는다).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelVersionDto.Comparison compare(AccountDto accountDto, Long baseId, Long targetId) {
        Models models = models(accountDto, baseId, targetId);

        Map<String, Integer> totals = zero();
        Map<String, Map<String, Integer>> byType = new LinkedHashMap<>();
        List<ModelVersionDto.Change> changes = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>(models.before().propertyNames());
        types.addAll(models.after().propertyNames());
        for (String type : types) {
            Map<String, Integer> count = zero();
            diff(models, type).values().forEach(change -> {
                count.merge(change.change(), 1, Integer::sum);
                totals.merge(change.change(), 1, Integer::sum);
                if (!change.change().equals(UNCHANGED)) changes.add(change);
            });
            byType.put(type, count);
        }
        return new ModelVersionDto.Comparison(models.base(), models.target(), totals, byType, changes);
    }

    /** ERD 로 그리는 모델 종류 : 노드(테이블·엔터티), 노드 안의 행(컬럼·속성), 관계선, 그리고 노드에 딸린 그 밖의 객체 */
    private record ErdKind(String node, String item, String edge, String edgeSource, String edgeTarget, List<String> extras, boolean changedExtrasOnly, String parent) {}

    private static final Map<String, ErdKind> ERD_KINDS = Map.of(
            // 인덱스·제약조건은 ERD 에 그리지 않으므로 달라진 것만 준다 (테이블에 "변경 있음" 을 표시하고 상세에서 본다)
            "PHYSICAL", new ErdKind("PHYSICAL_TABLE", "PHYSICAL_COLUMN", "PHYSICAL_RELATION", "sourceTableId", "targetTableId",
                    List.of("PHYSICAL_INDEX", "PHYSICAL_CONSTRAINT"), true, "tableInfoId"),
            // 식별자는 속성의 식별자 표시(PI·AI)에 필요해 모두 준다
            "LOGICAL", new ErdKind("LOGICAL_ENTITY", "LOGICAL_ATTRIBUTE", "LOGICAL_RELATIONSHIP", "sourceEntityId", "targetEntityId",
                    List.of("LOGICAL_IDENTIFIER"), false, "entityId"));
    private static final int ERD_LIMIT = 100;
    private static final int ERD_LIMIT_MAX = 300;

    /**
     * 비교 결과를 ERD 로 그릴 범위 (M-10 ③). 판정은 {@link #compare} 와 같은 것을 쓰고, 그리는 데 필요한 달라지지 않은 객체(UNCHANGED)를 더한다.
     * 모델 전체가 아니라 변경 영역만 준다 : 달라진 노드(자신 또는 행·관계·인덱스·제약조건·식별자가 달라진 것)와 관계로 이어진 이웃을
     * 우선순위(지정한 객체 → 그 이웃 → 달라진 노드 → 달라진 관계의 양 끝 → 달라진 노드의 이웃) 순으로 limit 개까지.
     * 범위를 넘는 객체는 focusType·focusId 로 지정하면 그 객체가 속한 노드부터 다시 채운다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelVersionDto.ErdComparison compareErd(AccountDto accountDto, Long baseId, Long targetId, String kindCode, String focusType, Long focusId, Integer limit) {
        ErdKind kind = kindCode == null ? null : ERD_KINDS.get(kindCode);
        if (kind == null) throw invalid("ERD 로 비교할 모델 종류가 올바르지 않습니다.");
        int max = Math.min(Math.max(limit == null ? ERD_LIMIT : limit, 1), ERD_LIMIT_MAX);
        Models models = models(accountDto, baseId, targetId);

        Map<Long, ModelVersionDto.Change> nodes = diff(models, kind.node());
        Map<Long, ModelVersionDto.Change> items = diff(models, kind.item());
        Map<Long, ModelVersionDto.Change> edges = diff(models, kind.edge());
        List<ModelVersionDto.Change> extras = new ArrayList<>();
        kind.extras().forEach(type -> diff(models, type).values().stream()
                .filter(change -> !kind.changedExtrasOnly() || !change.change().equals(UNCHANGED)).forEach(extras::add));

        // 달라진 노드 : 자신이 달라졌거나, 딸린 행·그 밖의 객체가 달라진 것 (ID 순)
        Set<Long> affected = new TreeSet<>();
        nodes.forEach((id, change) -> { if (!change.change().equals(UNCHANGED)) affected.add(id); });
        Stream.concat(items.values().stream(), extras.stream()).filter(change -> !change.change().equals(UNCHANGED))
                .forEach(change -> affected.addAll(references(change, kind.parent())));
        Set<Long> changedEnds = new TreeSet<>();
        Map<Long, Set<Long>> neighbors = new LinkedHashMap<>();
        edges.values().forEach(edge -> {
            for (JsonNode state : new JsonNode[]{edge.before(), edge.after()}) {
                Long source = reference(state, kind.edgeSource());
                Long target = reference(state, kind.edgeTarget());
                if (source == null || target == null) continue;
                neighbors.computeIfAbsent(source, key -> new TreeSet<>()).add(target);
                neighbors.computeIfAbsent(target, key -> new TreeSet<>()).add(source);
            }
            if (!edge.change().equals(UNCHANGED)) changedEnds.addAll(ends(edge, kind));
        });

        Set<Long> focus = new LinkedHashSet<>();
        if (focusType != null && focusId != null) {
            if (focusType.equals(kind.node())) focus.add(focusId);
            else if (focusType.equals(kind.edge()) && edges.containsKey(focusId)) focus.addAll(ends(edges.get(focusId), kind));
            else if (focusType.equals(kind.item()) && items.containsKey(focusId)) focus.addAll(references(items.get(focusId), kind.parent()));
            else extras.stream().filter(change -> change.targetType().equals(focusType) && change.targetId().equals(focusId)).forEach(change -> focus.addAll(references(change, kind.parent())));
        }

        Set<Long> chosen = new LinkedHashSet<>();
        List<Set<Long>> priority = new ArrayList<>(List.of(focus, around(focus, neighbors), affected, changedEnds, around(affected, neighbors)));
        for (Set<Long> ids : priority) {
            for (Long id : ids) {
                if (chosen.size() >= max) break;
                if (nodes.containsKey(id)) chosen.add(id);
            }
        }
        Set<Long> changedNodes = new TreeSet<>(affected);
        changedNodes.addAll(changedEnds);
        changedNodes.retainAll(nodes.keySet());

        Map<Long, List<ModelVersionDto.Change>> itemsByNode = byNode(items.values(), kind.parent(), chosen);
        Map<Long, List<ModelVersionDto.Change>> extrasByNode = byNode(extras, kind.parent(), chosen);
        List<ModelVersionDto.ErdNode> shownNodes = chosen.stream()
                .map(id -> new ModelVersionDto.ErdNode(nodes.get(id), itemsByNode.getOrDefault(id, List.of()), extrasByNode.getOrDefault(id, List.of()))).toList();
        // 관계 : 범위 안의 노드에 한쪽 끝이라도 걸린 것 (상대가 범위 밖인 관계는 선으로 그리지 못해도 FK 표시와 상세에 쓴다)
        List<ModelVersionDto.Change> shownEdges = edges.values().stream().filter(edge -> ends(edge, kind).stream().anyMatch(chosen::contains)).toList();
        return new ModelVersionDto.ErdComparison(models.base(), models.target(), kindCode, changedNodes.size(), !chosen.containsAll(changedNodes), shownNodes, shownEdges);
    }

    private static Set<Long> around(Set<Long> ids, Map<Long, Set<Long>> neighbors) {
        Set<Long> result = new LinkedHashSet<>();
        ids.forEach(id -> result.addAll(neighbors.getOrDefault(id, Set.of())));
        return result;
    }

    private static Set<Long> ends(ModelVersionDto.Change edge, ErdKind kind) {
        Set<Long> result = references(edge, kind.edgeSource());
        result.addAll(references(edge, kind.edgeTarget()));
        return result;
    }

    /** 변경 전후 상태가 가리키는 대상 ID (노드를 옮긴 행이면 양쪽 노드 모두) */
    private static Set<Long> references(ModelVersionDto.Change change, String field) {
        Set<Long> result = new LinkedHashSet<>();
        for (JsonNode state : new JsonNode[]{change.before(), change.after()}) {
            Long id = reference(state, field);
            if (id != null) result.add(id);
        }
        return result;
    }

    private static Long reference(JsonNode state, String field) {
        JsonNode value = state == null ? null : state.get(field);
        return value == null || value.isNull() ? null : (Long) value.asLong();
    }

    private static Map<Long, List<ModelVersionDto.Change>> byNode(java.util.Collection<ModelVersionDto.Change> changes, String parent, Set<Long> chosen) {
        Map<Long, List<ModelVersionDto.Change>> result = new LinkedHashMap<>();
        changes.forEach(change -> references(change, parent).stream().filter(chosen::contains).forEach(id -> result.computeIfAbsent(id, key -> new ArrayList<>()).add(change)));
        return result;
    }

    /** 비교할 두 모델 : 기준 버전의 저장된 내용과 대상(다른 버전의 저장된 내용 또는 현재 모델). 로그인한 프로젝트의 버전만 읽는다 */
    private record Models(ModelVersionDto.Side base, ModelVersionDto.Side target, JsonNode before, JsonNode after) {}

    private Models models(AccountDto accountDto, Long baseId, Long targetId) {
        if (baseId == null || baseId.equals(targetId)) throw invalid("비교할 버전을 서로 다르게 선택해주세요.");
        ModelVersion base = version(accountDto, baseId);
        ModelVersion target = targetId == null ? null : version(accountDto, targetId);
        JsonNode before = JSON.readTree(base.getSnapshot());
        // 현재 모델도 저장할 때와 같은 JSON 을 거쳐 비교한다 (숫자·날짜 표현이 저장본과 같아진다)
        JsonNode after = JSON.readTree(target == null ? JSON.writeValueAsString(recorder.currentState(accountDto.getProjectId())) : target.getSnapshot());
        return new Models(side(base), target == null ? new ModelVersionDto.Side(null, null, null) : side(target), before, after);
    }

    /** 대상 종류 하나의 객체를 내부 ID 로 짝지은 판정 (UNCHANGED 포함, 기준 쪽 순서 다음에 새로 생긴 것) */
    private static Map<Long, ModelVersionDto.Change> diff(Models models, String type) {
        Map<Long, JsonNode> olds = byId(models.before().get(type));
        Map<Long, JsonNode> news = byId(models.after().get(type));
        Set<Long> ids = new LinkedHashSet<>(olds.keySet());
        ids.addAll(news.keySet());
        Map<Long, ModelVersionDto.Change> result = new LinkedHashMap<>();
        for (Long id : ids) {
            JsonNode o = olds.get(id);
            JsonNode n = news.get(id);
            String change;
            List<String> changed = List.of();
            if (o == null) change = "ADDED";
            else if (n == null) change = "REMOVED";
            else {
                changed = changedFields(type, o.get("state"), n.get("state"));
                change = changed.isEmpty() ? UNCHANGED : "MODIFIED";
            }
            JsonNode shown = n != null ? n : o;
            result.put(id, new ModelVersionDto.Change(type, id, shown.path("name").asString(null), change, changed, o == null ? null : o.get("state"), n == null ? null : n.get("state")));
        }
        return result;
    }

    /** 값이 실제로 다른 필드. 필드 순서는 보지 않고, 연관 대상의 이름 스냅샷("<필드>Name")은 대상 자신의 변경으로만 본다 */
    private static List<String> changedFields(String type, JsonNode before, JsonNode after) {
        JsonNode o = comparable(type, before);
        JsonNode n = comparable(type, after);
        Set<String> names = new LinkedHashSet<>(n.propertyNames());
        names.addAll(o.propertyNames());
        return names.stream().filter(name -> !java.util.Objects.equals(o.get(name), n.get(name))).toList();
    }

    private static JsonNode comparable(String type, JsonNode state) {
        ObjectNode result = JSON.createObjectNode();
        if (state == null) return result;
        for (String name : state.propertyNames()) {
            if (name.endsWith("Name") && state.has(name.substring(0, name.length() - 4) + "Id")) continue;
            JsonNode value = state.get(name);
            if (value.isArray()) {
                List<JsonNode> rows = new ArrayList<>();
                value.forEach(row -> rows.add(row.isObject() ? comparable(null, row) : row));
                // UNIQUE·CHECK 제약조건의 컬럼은 순서에 뜻이 없다 (변경 이력과 같은 기준)
                if ("PHYSICAL_CONSTRAINT".equals(type) && name.equals("columns")) {
                    rows.forEach(row -> { if (row instanceof ObjectNode object) object.remove("columnOrder"); });
                    rows.sort(Comparator.comparing(JsonNode::toString));
                }
                ArrayNode array = JSON.createArrayNode();
                rows.forEach(array::add);
                result.set(name, array);
            } else {
                result.set(name, value);
            }
        }
        return result;
    }

    private static Map<Long, JsonNode> byId(JsonNode objects) {
        Map<Long, JsonNode> result = new LinkedHashMap<>();
        if (objects != null) objects.forEach(object -> result.put(object.get("id").asLong(), object));
        return result;
    }

    private static Map<String, Integer> zero() {
        Map<String, Integer> result = new LinkedHashMap<>();
        RESULTS.forEach(key -> result.put(key, 0));
        return result;
    }

    private ModelVersion version(AccountDto accountDto, Long id) {
        // 다른 프로젝트의 버전은 ID 를 알아도 없는 것과 같다
        return repository.findByIdAndProjectId(id, accountDto.getProjectId()).orElseThrow(ModelVersionService::notFound);
    }

    private static ModelVersionDto.Side side(ModelVersion version) {
        return new ModelVersionDto.Side(version.getId(), version.getVersionName(), version.getCreatedAt());
    }

    private static ModelVersionDto.Summary summary(ModelVersion version) {
        return summary(new Object[]{version.getId(), version.getVersionName(), version.getDescription(), version.getFormatVersion(), version.getCounts(), version.getCreatedBy(), version.getCreatedAt()});
    }

    private static ModelVersionDto.Summary summary(Object[] row) {
        return new ModelVersionDto.Summary((Long) row[0], (String) row[1], (String) row[2], (Integer) row[3], JSON.readTree((String) row[4]), (String) row[5], (LocalDateTime) row[6]);
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.MODEL_VERSION_NOT_FOUND);
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_MODEL_VERSION, message, args);
    }
}
