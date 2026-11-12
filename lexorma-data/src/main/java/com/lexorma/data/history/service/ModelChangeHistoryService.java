package com.lexorma.data.history.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.history.domain.dto.ModelChangeHistoryDto;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 모델 변경 이력 조회 (M-9 1차). 기록은 {@link com.lexorma.data.history.record.ModelChangeRecorder} 가 한다.
 * 모든 조회는 로그인한 프로젝트의 이력만 본다 : 권한은 현재 모델 객체가 아니라 이력에 저장된 프로젝트로 판단하므로 삭제된 객체의 이력도 조회된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModelChangeHistoryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 최신 순. 같은 시각(한 작업의 이력은 시각이 같다)은 나중에 기록된 것부터 */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("changedAt"), Sort.Order.desc("id"));

    private final ModelChangeHistoryRepository repository;

    /** 조회 조건 (null·빈 값은 조건에서 뺀다). 대상 종류 + 대상 ID 를 주면 객체별 이력이다 */
    public record Filter(String targetType, Long targetId, String action, String userId, LocalDate from, LocalDate to, String changeGroupId) {
    }

    public Page<ModelChangeHistoryDto.Summary> search(AccountDto accountDto, Filter filter, int page, int size) {
        Long projectId = accountDto.getProjectId();
        ModelChangeHistory.TargetType targetType = code(ModelChangeHistory.TargetType.class, filter.targetType());
        ModelChangeHistory.Action action = code(ModelChangeHistory.Action.class, filter.action());

        Specification<ModelChangeHistory> where = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("projectId"), projectId));
            if (targetType != null) predicates.add(builder.equal(root.get("targetType"), targetType));
            if (filter.targetId() != null) predicates.add(builder.equal(root.get("targetId"), filter.targetId()));
            if (action != null) predicates.add(builder.equal(root.get("action"), action));
            if (notBlank(filter.userId())) predicates.add(builder.equal(root.get("userId"), filter.userId().trim()));
            if (filter.from() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("changedAt"), filter.from().atStartOfDay()));
            if (filter.to() != null) predicates.add(builder.lessThan(root.get("changedAt"), filter.to().plusDays(1).atStartOfDay())); // 종료일 포함
            if (notBlank(filter.changeGroupId())) predicates.add(builder.equal(root.get("changeGroupId"), filter.changeGroupId().trim()));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(where, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500), LATEST_FIRST)).map(ModelChangeHistoryService::summary);
    }

    /** 한 작업(변경 그룹)의 이력 : 기록된 순서대로 */
    public List<ModelChangeHistoryDto.Summary> group(AccountDto accountDto, String changeGroupId) {
        return repository.findAllByProjectIdAndChangeGroupIdOrderByIdAsc(accountDto.getProjectId(), changeGroupId).stream()
                .map(ModelChangeHistoryService::summary).toList();
    }

    public ModelChangeHistoryDto.Detail detail(AccountDto accountDto, Long id) {
        // 다른 프로젝트의 이력은 ID 를 알아도 없는 것과 같다
        ModelChangeHistory history = repository.findByIdAndProjectId(id, accountDto.getProjectId())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "대상을 찾을 수 없습니다."));
        return new ModelChangeHistoryDto.Detail(summary(history), node(history.getBeforeData()), node(history.getAfterData()));
    }

    private static ModelChangeHistoryDto.Summary summary(ModelChangeHistory history) {
        JsonNode before = node(history.getBeforeData());
        JsonNode after = node(history.getAfterData());
        List<String> changed = new ArrayList<>();
        // 생성·삭제는 전체가 변경이라 필드를 나열하지 않는다
        if (before != null && after != null) {
            after.propertyNames().forEach(name -> {
                if (!Objects.equals(after.get(name), before.get(name))) changed.add(name);
            });
            before.propertyNames().forEach(name -> {
                if (!after.has(name)) changed.add(name);
            });
        }
        return ModelChangeHistoryDto.Summary.of(history, changed);
    }

    private static JsonNode node(String json) {
        return json == null ? null : JSON.readTree(json);
    }

    /** 모르는 코드는 400 (조건을 조용히 무시하면 전체가 조회된다) */
    private static <E extends Enum<E>> E code(Class<E> type, String value) {
        if (!notBlank(value)) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "대상을 찾을 수 없습니다.");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
