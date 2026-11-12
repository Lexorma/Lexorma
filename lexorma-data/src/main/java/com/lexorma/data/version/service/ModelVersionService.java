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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모델 버전 (M-9 3차) : 만들기·조회·비교. 수정·삭제는 없다.
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
    private static final List<String> RESULTS = List.of("ADDED", "REMOVED", "MODIFIED", "UNCHANGED");

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
            ModelVersion saved = repository.saveAndFlush(ModelVersion.builder()
                    .projectId(projectId).versionName(name).description(description).formatVersion(FORMAT_VERSION)
                    .counts(JSON.writeValueAsString(counts)).snapshot(JSON.writeValueAsString(objects))
                    .createdBy(accountDto.getUsername()).createdAt(LocalDateTime.now()).build());
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
        if (baseId == null || baseId.equals(targetId)) throw invalid("비교할 버전을 서로 다르게 선택해주세요.");
        ModelVersion base = version(accountDto, baseId);
        ModelVersion target = targetId == null ? null : version(accountDto, targetId);
        JsonNode before = JSON.readTree(base.getSnapshot());
        // 현재 모델도 저장할 때와 같은 JSON 을 거쳐 비교한다 (숫자·날짜 표현이 저장본과 같아진다)
        JsonNode after = JSON.readTree(target == null ? JSON.writeValueAsString(recorder.currentState(accountDto.getProjectId())) : target.getSnapshot());

        Map<String, Integer> totals = zero();
        Map<String, Map<String, Integer>> byType = new LinkedHashMap<>();
        List<ModelVersionDto.Change> changes = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>(before.propertyNames());
        types.addAll(after.propertyNames());
        for (String type : types) {
            Map<Long, JsonNode> olds = byId(before.get(type));
            Map<Long, JsonNode> news = byId(after.get(type));
            Map<String, Integer> count = zero();
            Set<Long> ids = new LinkedHashSet<>(olds.keySet());
            ids.addAll(news.keySet());
            for (Long id : ids) {
                JsonNode o = olds.get(id);
                JsonNode n = news.get(id);
                String result;
                List<String> changed = List.of();
                if (o == null) result = "ADDED";
                else if (n == null) result = "REMOVED";
                else {
                    changed = changedFields(type, o.get("state"), n.get("state"));
                    result = changed.isEmpty() ? "UNCHANGED" : "MODIFIED";
                }
                count.merge(result, 1, Integer::sum);
                totals.merge(result, 1, Integer::sum);
                if (result.equals("UNCHANGED")) continue;
                JsonNode shown = n != null ? n : o;
                changes.add(new ModelVersionDto.Change(type, id, shown.path("name").asString(null), result, changed,
                        o == null ? null : o.get("state"), n == null ? null : n.get("state")));
            }
            byType.put(type, count);
        }
        return new ModelVersionDto.Comparison(side(base), target == null ? new ModelVersionDto.Side(null, null, null) : side(target), totals, byType, changes);
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
