package com.lexorma.data.logical.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.common.jpa.ModelBase;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalErdLayout;
import com.lexorma.data.logical.domain.entity.LogicalModel;
import com.lexorma.data.logical.domain.entity.LogicalSubjectArea;
import com.lexorma.data.logical.domain.entity.LogicalSubjectAreaEntity;
import com.lexorma.data.logical.domain.entity.LogicalSubjectAreaLayout;
import com.lexorma.data.logical.repository.LogicalEntityRepository;
import com.lexorma.data.logical.repository.LogicalErdLayoutRepository;
import com.lexorma.data.logical.repository.LogicalModelRepository;
import com.lexorma.data.logical.repository.LogicalSubjectAreaEntityRepository;
import com.lexorma.data.logical.repository.LogicalSubjectAreaLayoutRepository;
import com.lexorma.data.logical.repository.LogicalSubjectAreaRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 논리 모델 주제 영역 (M-6 후속 4차, docs/completed/logical-subject-area.md) : 영역 계층 관리 · 엔터티 분류 · 영역별 내 ERD 배치.
 * <ul>
 * <li>영역은 논리 모델에 속하고 상위 영역으로 계층을 이룬다 : 최상위 = 1단계, 최대 {@value #MAX_DEPTH}단계. 같은 상위 아래 이름은 대소문자 무시로 하나 (최상위끼리도).
 *     옮길 때는 하위 영역 전체의 옮긴 뒤 깊이와 순환(자기 자신·자기 하위를 상위로)을 본다. 옮겨도 엔터티의 소속 영역 ID 는 그대로다</li>
 * <li>모든 변경은 논리 모델 행을 먼저 잠근다 (엔터티·관계 변경과 같은 잠금) : 이름 중복·깊이·순환·삭제·분류 검사가 동시 요청에도 맞는다. 영역 자체의 수정·삭제는 수정 일시 기준값을 비교한다 (DEC-08)</li>
 * <li>분류(소속)는 엔터티와 따로 저장한다 : 승인된 엔터티도 분류를 바꿀 수 있고(D-05), 엔터티의 수정 일시·승인 상태·정의는 바뀌지 않는다 (엔터티 수정 API 의 승인 검사는 그대로다)</li>
 * <li>일괄 분류는 엔터티마다 화면이 본 지금 소속과 비교해 하나라도 다르면 409, 하나라도 실패하면 아무것도 바꾸지 않는다 (D-20·D-21). 이미 그 영역이면 바꾸지 않는다 (이력 없음)</li>
 * <li>삭제는 직접 소속된 엔터티도 하위 영역도 없을 때만 (D-06). 그 영역의 배치(모든 사용자)만 함께 지운다</li>
 * <li>다른 프로젝트·다른 모델의 ID 는 논리 모델과 같은 '찾을 수 없음'이다 (LOGICAL_MODEL_IS_NOT_DEFINED)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogicalSubjectAreaService {

    static final int MAX_DEPTH = 3;
    /** 모델 하나의 영역 수 한도 : 조회 범위(영역 ID 목록)를 IN 조건 하나로 보내므로 둔 기술적 한도 */
    static final int MAX_AREAS = 1_000;
    /** 일괄 분류 한 번의 엔터티 수 (컬럼 용어 일괄 연결 DEC-26 과 같은 한도 — 화면은 한 페이지(최대 100)에서 고른다) */
    static final int MAX_ASSIGN = 500;

    private final LogicalModelRepository modelRepository;
    private final LogicalSubjectAreaRepository areaRepository;
    private final LogicalSubjectAreaEntityRepository memberRepository;
    private final LogicalSubjectAreaLayoutRepository areaLayoutRepository;
    private final LogicalErdLayoutRepository modelLayoutRepository;
    private final LogicalEntityRepository entityRepository;
    private final EntityManager entityManager;

    // ------------------------------------------------------------------ 조회

    /** 모델의 영역 계층과 수. 조회 수는 영역 수와 관계없이 4번 (모델, 영역, 영역별 직접 소속 수, 모델 엔터티 수) */
    @Transactional(readOnly = true)
    public LogicalSubjectAreaDto.Tree selectTree(AccountDto accountDto, Long modelId) {
        model(accountDto.getProjectId(), modelId);
        return tree(modelId);
    }

    private LogicalSubjectAreaDto.Tree tree(Long modelId) {
        Hierarchy hierarchy = new Hierarchy(areaRepository.findAllByModelId(modelId));
        Map<Long, Long> direct = new HashMap<>();
        long classified = 0;
        for (Object[] row : memberRepository.countByArea(modelId)) {
            direct.put((Long) row[0], (Long) row[1]);
            classified += (Long) row[1];
        }
        long entityCount = entityRepository.countByModel_Id(modelId);
        List<LogicalSubjectAreaDto.Area> areas = new ArrayList<>();
        for (LogicalSubjectArea area : hierarchy.ordered()) {
            long total = 0;
            for (Long id : hierarchy.subtree(area.getId())) total += direct.getOrDefault(id, 0L);
            areas.add(view(hierarchy, area, direct.getOrDefault(area.getId(), 0L), total));
        }
        return new LogicalSubjectAreaDto.Tree(modelId, entityCount, entityCount - classified, areas);
    }

    private static LogicalSubjectAreaDto.Area view(Hierarchy hierarchy, LogicalSubjectArea area, long direct, long total) {
        return new LogicalSubjectAreaDto.Area(area.getId(), hierarchy.modelId, hierarchy.parentId(area), area.getAreaName(), area.getAreaDescription(), area.getSortOrder(),
                hierarchy.depth(area.getId()), hierarchy.path(area.getId()), direct, total, hierarchy.children(area.getId()).size(), area.modifiedAt());
    }

    /**
     * 기존 목록·ERD 조회의 선택 조건 → 조회 범위. 조건이 없으면 null (모델 전체 — 예전과 같다).
     * 영역과 미분류를 함께 주면 400, 이 모델의 영역이 아니면(다른 모델·다른 프로젝트·지워진 영역) 찾을 수 없음 — 모델 전체로 바꿔 보여 주지 않는다
     */
    @Transactional(readOnly = true)
    public LogicalSubjectAreaDto.Scope scope(Long projectId, Long modelId, Long subjectAreaId, Boolean unclassified, Boolean includeChildren) {
        boolean onlyUnclassified = Boolean.TRUE.equals(unclassified);
        if (subjectAreaId != null && onlyUnclassified) throw LogicalModelService.invalid("주제 영역과 미분류를 함께 지정할 수 없습니다.");
        if (onlyUnclassified) return LogicalSubjectAreaDto.Scope.unclassifiedOnly();
        if (subjectAreaId == null) return null;
        if (!modelId.equals(areaRepository.findModelId(subjectAreaId, projectId).orElse(null))) throw notFound();
        if (Boolean.FALSE.equals(includeChildren)) return new LogicalSubjectAreaDto.Scope(false, Set.of(subjectAreaId));
        return new LogicalSubjectAreaDto.Scope(false, new Hierarchy(areaRepository.findAllByModelId(modelId)).subtree(subjectAreaId));
    }

    // ------------------------------------------------------------------ 등록·수정·이동·삭제

    @Transactional
    public LogicalSubjectAreaDto.Area save(AccountDto accountDto, LogicalSubjectAreaDto.Save request) {
        if (request == null) throw LogicalModelService.invalid("주제 영역 정보가 없습니다.");
        Long projectId = accountDto.getProjectId();
        LogicalModel model;
        LogicalSubjectArea area;
        if (request.id() == null) {
            model = lockModel(projectId, request.modelId());
            area = LogicalSubjectArea.builder().projectId(projectId).model(model).build();
        } else {
            // 모델은 바꿀 수 없다 (요청의 modelId 는 무시) : 영역의 모델을 잠근 뒤 다시 읽어 기준값 비교
            model = lockModel(projectId, areaRepository.findModelId(request.id(), projectId).orElseThrow(LogicalSubjectAreaService::notFound));
            area = areaRepository.findByIdAndProjectId(request.id(), projectId).orElseThrow(LogicalSubjectAreaService::notFound);
            entityManager.refresh(area);
            ModelBase.verify(area, request.modifiedAt(), area.getAreaName());
        }
        Hierarchy hierarchy = new Hierarchy(areaRepository.findAllByModelId(model.getId()));
        if (request.id() == null && hierarchy.size() >= MAX_AREAS) throw LogicalModelService.invalid("논리 모델 하나에 주제 영역은 {0}개까지 만들 수 있습니다.", MAX_AREAS);

        String name = LogicalModelService.name(request.areaName(), "주제 영역명");
        LogicalSubjectArea parent = null;
        if (request.parentId() != null) {
            parent = hierarchy.get(request.parentId());
            if (parent == null) throw notFound("상위 주제 영역"); // 다른 모델·다른 프로젝트·없는 영역
            if (area.getId() != null) {
                if (parent.getId().equals(area.getId())) throw LogicalModelService.invalid("주제 영역을 자기 자신의 하위로 옮길 수 없습니다.");
                if (hierarchy.subtree(area.getId()).contains(parent.getId())) throw LogicalModelService.invalid("주제 영역을 자기 하위 영역 아래로 옮길 수 없습니다.");
            }
        }
        // 깊이 : 상위의 깊이 + 1 이 이 영역, 하위 영역 전체가 함께 내려간다 (옮긴 뒤 가장 깊은 하위가 한도 안이어야 한다)
        int depth = parent == null ? 1 : hierarchy.depth(parent.getId()) + 1;
        int height = area.getId() == null ? 0 : hierarchy.height(area.getId());
        if (depth + height > MAX_DEPTH) {
            throw LogicalModelService.invalid(height == 0 ? "주제 영역은 {0}단계까지 만들 수 있습니다." : "하위 영역까지 {0}단계를 넘게 되어 옮길 수 없습니다.", MAX_DEPTH);
        }
        Long parentId = parent == null ? null : parent.getId();
        String key = name.toLowerCase(Locale.ROOT);
        for (LogicalSubjectArea sibling : hierarchy.children(parentId)) {
            if (sibling != area && sibling.getAreaName().toLowerCase(Locale.ROOT).equals(key)) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        }
        boolean moved = area.getId() == null || !Objects.equals(hierarchy.parentId(area), parentId);
        Integer sortOrder = request.sortOrder();
        if (sortOrder != null && (sortOrder < 0 || sortOrder > 1_000_000)) throw LogicalModelService.invalid("표시 순서는 0 이상 1,000,000 이하로 입력해주세요.");
        if (sortOrder == null) {
            sortOrder = moved ? hierarchy.children(parentId).stream().filter(a -> a != area).mapToInt(a -> a.getSortOrder() == null ? 0 : a.getSortOrder()).max().orElse(0) + 1
                    : area.getSortOrder();
        }
        area.setAreaName(name);
        area.setAreaDescription(LogicalModelService.description(request.areaDescription()));
        area.setParent(parent);
        area.setSortOrder(sortOrder);
        if (area.getId() == null) areaRepository.save(area);
        areaRepository.flush();
        entityManager.refresh(area); // 응답의 기준값은 DB 에 저장된 값이어야 한다 (timestamp(6) — LogicalModelService.saveRelationshipPairs 와 같은 이유)
        Long areaId = area.getId();
        return tree(model.getId()).areas().stream().filter(a -> a.id().equals(areaId)).findFirst().orElseThrow();
    }

    /** 영역 삭제 : 직접 소속된 엔터티도 하위 영역도 없을 때만. 엔터티·관계·매핑·모델 전체 배치는 건드리지 않고 이 영역의 배치만 지운다 */
    @Transactional
    public void delete(AccountDto accountDto, Long areaId, LocalDateTime modifiedAt) {
        Long projectId = accountDto.getProjectId();
        lockModel(projectId, areaRepository.findModelId(areaId, projectId).orElseThrow(LogicalSubjectAreaService::notFound));
        LogicalSubjectArea area = areaRepository.findByIdAndProjectId(areaId, projectId).orElseThrow(LogicalSubjectAreaService::notFound);
        entityManager.refresh(area);
        ModelBase.verify(area, modifiedAt, area.getAreaName());
        Hierarchy hierarchy = new Hierarchy(areaRepository.findAllByModelId(area.getModel().getId()));
        List<LogicalSubjectArea> children = hierarchy.children(areaId);
        if (!children.isEmpty()) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "하위 주제 영역 {0} 이(가) 있습니다. 하위 영역을 먼저 옮기거나 삭제해주세요.", children.getFirst().getAreaName());
        }
        long members = memberRepository.countBySubjectArea_Id(areaId);
        if (members > 0) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "주제 영역에 엔터티 {0}개가 소속되어 있습니다. 엔터티의 분류를 먼저 바꾸거나 해제해주세요.", members);
        }
        areaLayoutRepository.deleteBySubjectAreaId(areaId);
        areaRepository.delete(area);
    }

    /** 모델의 주제 영역 전체 (모델 삭제의 구성 기준값 — M8-C2. 삭제는 모델 행을 잠근 뒤 부른다) */
    List<LogicalSubjectArea> areasOf(Long modelId) {
        return areaRepository.findAllByModelId(modelId);
    }

    /** 논리 모델 삭제 (LogicalModelService.deleteModel 이 모델을 잠그고 엔터티가 없음을 확인한 뒤) : 영역 배치 → 영역(하위부터). 소속은 엔터티가 없으므로 없다 */
    void deleteForModel(Long modelId) {
        areaLayoutRepository.deleteByModelId(modelId);
        Hierarchy hierarchy = new Hierarchy(areaRepository.findAllByModelId(modelId));
        List<LogicalSubjectArea> areas = new ArrayList<>(hierarchy.ordered());
        areas.sort(Comparator.comparingInt((LogicalSubjectArea a) -> hierarchy.depth(a.getId())).reversed());
        for (LogicalSubjectArea area : areas) {
            areaRepository.delete(area);
            areaRepository.flush(); // 상위 영역을 가리키는 행을 먼저 내보낸다 (자기 참조 FK)
        }
    }

    // ------------------------------------------------------------------ 엔터티 분류

    /**
     * 일괄 분류 (D-20·D-21) : 모델 행을 잠근 뒤 고른 엔터티의 지금 소속을 한 번에 읽어 화면이 본 소속(expectedSubjectAreaId)과 비교한다.
     * 하나라도 다르거나 잘못된 엔터티가 있으면 아무것도 바꾸지 않는다. 같은 영역으로 다시 지정한 엔터티는 바꾸지 않는다 (수정 일시·이력이 생기지 않는다)
     */
    @Transactional
    public LogicalSubjectAreaDto.AssignmentResult assign(AccountDto accountDto, Long modelId, LogicalSubjectAreaDto.Assignment request) {
        Long projectId = accountDto.getProjectId();
        LogicalModel model = lockModel(projectId, modelId);
        if (request == null || request.subjectAreaId() == null) throw LogicalModelService.invalid("옮길 주제 영역을 지정해주세요. (미분류는 null)");
        List<LogicalSubjectAreaDto.AssignmentItem> items = request.entities() == null ? List.of() : request.entities();
        if (items.isEmpty()) throw LogicalModelService.invalid("분류할 엔터티를 1개 이상 골라주세요.");
        if (items.size() > MAX_ASSIGN) throw LogicalModelService.invalid("한 번에 엔터티 {0}개까지 분류할 수 있습니다.", MAX_ASSIGN);
        Long targetId = areaId(request.subjectAreaId(), "옮길 주제 영역");
        LogicalSubjectArea target = null;
        if (targetId != null) {
            target = areaRepository.findByIdAndProjectId(targetId, projectId).filter(a -> a.getModel().getId().equals(modelId)).orElseThrow(LogicalSubjectAreaService::notFound);
        }

        Map<Long, Long> expected = new LinkedHashMap<>();
        for (LogicalSubjectAreaDto.AssignmentItem item : items) {
            if (item == null || item.entityId() == null) throw LogicalModelService.invalid("엔터티를 지정해주세요.");
            if (item.expectedSubjectAreaId() == null) throw LogicalModelService.invalid("엔터티의 지금 주제 영역을 함께 보내야 합니다. (미분류는 null)");
            if (expected.containsKey(item.entityId())) throw LogicalModelService.invalid("같은 엔터티를 두 번 지정할 수 없습니다.");
            expected.put(item.entityId(), areaId(item.expectedSubjectAreaId(), "엔터티의 지금 주제 영역"));
        }
        // 엔터티 : 로그인 프로젝트의 이 모델 것만 (다른 프로젝트·모델 엔터티는 없는 것과 같다)
        Map<Long, LogicalEntity> entities = new HashMap<>();
        for (LogicalEntity entity : entityRepository.findAllByIds(expected.keySet(), projectId)) entities.put(entity.getId(), entity);
        for (Long entityId : expected.keySet()) {
            LogicalEntity entity = entities.get(entityId);
            if (entity == null || !entity.getModel().getId().equals(modelId)) throw LogicalModelService.notFound("엔터티");
        }
        Map<Long, LogicalSubjectAreaEntity> current = new HashMap<>();
        for (LogicalSubjectAreaEntity member : memberRepository.findAllByEntityIds(expected.keySet())) current.put(member.getEntity().getId(), member);
        for (Map.Entry<Long, Long> e : expected.entrySet()) {
            LogicalSubjectAreaEntity member = current.get(e.getKey());
            Long now = member == null ? null : member.getSubjectArea().getId();
            if (!Objects.equals(now, e.getValue())) {
                throw CustomException.withMessage(ErrorCode.MODEL_CONFLICT, "엔터티 {0} 의 주제 영역이 화면을 연 뒤 바뀌었습니다. 아무것도 바꾸지 않았습니다. 다시 불러와 확인한 뒤 지정해주세요.",
                        entities.get(e.getKey()).getEntityName());
            }
        }

        int changed = 0;
        for (Long entityId : expected.keySet()) {
            LogicalSubjectAreaEntity member = current.get(entityId);
            Long now = member == null ? null : member.getSubjectArea().getId();
            if (Objects.equals(now, targetId)) continue; // 이미 그 영역 (또는 이미 미분류)
            if (target == null) memberRepository.delete(member);
            else if (member != null) member.setSubjectArea(target);
            else memberRepository.save(LogicalSubjectAreaEntity.builder().projectId(projectId).modelId(model.getId()).subjectArea(target).entity(entities.get(entityId)).build());
            changed++;
        }
        memberRepository.flush(); // DB 제약 위반(엔터티 중복 소속 등)은 여기서 나고 전체가 롤백된다
        return new LogicalSubjectAreaDto.AssignmentResult(changed, items.size() - changed);
    }

    /** 새 엔터티의 처음 소속 (엔터티 등록과 같은 트랜잭션, 모델은 부르는 쪽이 잠갔다). 영역이 이 모델 것이 아니면 실패해 엔터티 등록도 되돌려진다 */
    void assignNew(LogicalEntity entity, Long subjectAreaId) {
        if (subjectAreaId == null) return;
        LogicalSubjectArea area = areaRepository.findByIdAndProjectId(subjectAreaId, entity.getProjectId())
                .filter(a -> a.getModel().getId().equals(entity.getModel().getId())).orElseThrow(LogicalSubjectAreaService::notFound);
        memberRepository.save(LogicalSubjectAreaEntity.builder().projectId(entity.getProjectId()).modelId(entity.getModel().getId()).subjectArea(area).entity(entity).build());
    }

    /** 엔터티 삭제 (삭제 조건을 모두 통과한 뒤, 같은 트랜잭션) : 소속을 함께 지운다 — 변경 이력에 남도록 엔티티로 지운다 */
    void removeEntity(Long entityId) {
        memberRepository.findByEntity_Id(entityId).ifPresent(member -> {
            memberRepository.delete(member);
            memberRepository.flush();
        });
    }

    /** 엔터티 하나의 소속 영역 ID (없으면 null) */
    Long areaOf(Long entityId) {
        return memberRepository.findByEntity_Id(entityId).map(m -> m.getSubjectArea().getId()).orElse(null);
    }

    /** 엔터티 → 소속 영역 ID (D-35 D35-2 : AI·MCP 엔터티 상세 — 조회 1번). 호출한 쪽이 프로젝트·모델을 확인한 엔터티만 넘긴다. 미분류 엔터티는 빠진다 */
    @Transactional(readOnly = true)
    public Map<Long, Long> selectAreaIds(java.util.Collection<Long> entityIds) {
        return areasOf(entityIds);
    }

    /** 엔터티 ID → 소속 영역 ID (없는 엔터티는 빠진다) */
    Map<Long, Long> areasOf(java.util.Collection<Long> entityIds) {
        Map<Long, Long> result = new HashMap<>();
        if (entityIds.isEmpty()) return result;
        for (Object[] row : memberRepository.findAreaIds(entityIds)) result.put((Long) row[0], (Long) row[1]);
        return result;
    }

    /** JSON 의 영역 번호 : 명시적 null = 미분류(null), 정수만 */
    private static Long areaId(JsonNode value, String label) {
        if (value.isNull()) return null;
        if (!value.canConvertToLong() || !value.isIntegralNumber()) throw LogicalModelService.invalid("{0} 번호가 올바르지 않습니다.", Messages.get(label));
        return value.asLong();
    }

    // ------------------------------------------------------------------ 주제 영역별 내 ERD 배치 (D-07, D-26)

    /**
     * 이 영역에 저장한 내 배치. 저장한 적 없으면 모델 전체 배치(내 것)의 좌표를 참고로 준다 (source MODEL, version·viewport null) — 아무것도 저장하지 않는다.
     * 지워진 엔터티의 좌표는 뺀다 (모델 전체 배치와 같은 정리 규칙)
     */
    @Transactional(readOnly = true)
    public LogicalSubjectAreaDto.Layout selectLayout(AccountDto accountDto, Long modelId, Long areaId) {
        Long projectId = accountDto.getProjectId();
        model(projectId, modelId);
        area(projectId, modelId, areaId);
        Long accountId = Long.valueOf(accountDto.getId());
        Set<Long> existing = entityRepository.findIdsByModelId(modelId);
        var saved = areaLayoutRepository.findBySubjectAreaIdAndAccountId(areaId, accountId);
        if (saved.isPresent()) {
            Map<Long, LogicalModelDto.Point> nodes = LogicalModelService.readNodes(saved.get().getNodePositions());
            nodes.keySet().retainAll(existing);
            return toDto(saved.get(), nodes);
        }
        return modelLayoutRepository.findByModelIdAndAccountId(modelId, accountId)
                .map(LogicalErdLayout::getNodePositions)
                .map(json -> {
                    Map<Long, LogicalModelDto.Point> nodes = LogicalModelService.readNodes(json);
                    nodes.keySet().retainAll(existing);
                    return new LogicalSubjectAreaDto.Layout(null, nodes, null, "MODEL");
                })
                .orElseGet(() -> new LogicalSubjectAreaDto.Layout(null, Map.of(), null, "NONE"));
    }

    /** 이 영역의 내 배치에만 저장한다 (모델 전체 배치·다른 영역·다른 사용자의 배치는 그대로). 개인 화면 설정이라 VIEWER 도 저장한다. 조회한 version 이 아니면 409 */
    @Transactional
    public LogicalSubjectAreaDto.Layout saveLayout(AccountDto accountDto, Long modelId, Long areaId, LogicalModelDto.Layout request) {
        LogicalModelService.validate(request);
        Long projectId = accountDto.getProjectId();
        Long accountId = Long.valueOf(accountDto.getId());
        model(projectId, modelId);
        area(projectId, modelId, areaId);
        LogicalSubjectAreaLayout layout = areaLayoutRepository.findBySubjectAreaIdAndAccountId(areaId, accountId)
                .orElseGet(() -> LogicalSubjectAreaLayout.builder().projectId(projectId).modelId(modelId).subjectAreaId(areaId).accountId(accountId).build());
        if (!Objects.equals(layout.getVersion(), request.version())) throw new CustomException(ErrorCode.ERD_LAYOUT_CONFLICT);
        // 저장된 좌표 + 요청 좌표 (요청 우선) : 화면에 없는 엔터티(다른 페이지·검색·하위 영역 포함 여부)의 좌표는 그대로 둔다. 지워진 엔터티는 정리하고, 이 모델에 없는 엔터티는 거절한다
        Map<Long, LogicalModelDto.Point> nodes = layout.getId() == null ? new LinkedHashMap<>() : LogicalModelService.readNodes(layout.getNodePositions());
        nodes.putAll(request.nodes());
        Set<Long> existing = entityRepository.findIdsByModelId(modelId);
        if (!existing.containsAll(request.nodes().keySet())) throw LogicalModelService.notFound("엔터티");
        nodes.keySet().retainAll(existing);
        layout.setNodePositions(LogicalModelService.writeNodes(nodes));
        layout.setViewportX(request.viewport().x());
        layout.setViewportY(request.viewport().y());
        layout.setViewportZoom(request.viewport().zoom());
        try {
            // 동시에 저장한 다른 탭 : 최초 저장은 (영역, 사용자) UNIQUE, 이후는 @Version 으로 걸린다
            return toDto(areaLayoutRepository.saveAndFlush(layout), nodes);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ERD_LAYOUT_CONFLICT);
        }
    }

    private static LogicalSubjectAreaDto.Layout toDto(LogicalSubjectAreaLayout layout, Map<Long, LogicalModelDto.Point> nodes) {
        return new LogicalSubjectAreaDto.Layout(layout.getVersion(), nodes, new LogicalModelDto.Viewport(layout.getViewportX(), layout.getViewportY(), layout.getViewportZoom()),
                "SUBJECT_AREA");
    }

    // ------------------------------------------------------------------ 보조

    private LogicalModel model(Long projectId, Long modelId) {
        if (modelId == null) throw LogicalModelService.notFound("논리 모델");
        return modelRepository.findByIdAndProjectId(modelId, projectId).orElseThrow(() -> LogicalModelService.notFound("논리 모델"));
    }

    private LogicalModel lockModel(Long projectId, Long modelId) {
        if (modelId == null) throw LogicalModelService.notFound("논리 모델");
        return modelRepository.findForUpdate(modelId, projectId).orElseThrow(() -> LogicalModelService.notFound("논리 모델"));
    }

    private void area(Long projectId, Long modelId, Long areaId) {
        if (areaId == null || !modelId.equals(areaRepository.findModelId(areaId, projectId).orElse(null))) throw notFound();
    }

    private static CustomException notFound() {
        return notFound("주제 영역");
    }

    private static CustomException notFound(String label) {
        return LogicalModelService.notFound(label);
    }

    /** 모델 하나의 영역 계층 (메모리) : 상위 → 하위 순서, 깊이·경로·하위 전체. 최대 3단계라 단순 순회로 충분하다 */
    static final class Hierarchy {
        private final Long modelId;
        private final Map<Long, LogicalSubjectArea> byId = new LinkedHashMap<>();
        private final Map<Long, List<LogicalSubjectArea>> children = new HashMap<>();

        Hierarchy(List<LogicalSubjectArea> areas) {
            this.modelId = areas.isEmpty() ? null : areas.getFirst().getModel().getId();
            areas.forEach(a -> byId.put(a.getId(), a));
            // 목록은 순서·이름·ID 순이라 형제 목록도 그 순서다
            for (LogicalSubjectArea area : areas) children.computeIfAbsent(parentId(area), k -> new ArrayList<>()).add(area);
        }

        int size() {
            return byId.size();
        }

        LogicalSubjectArea get(Long id) {
            return byId.get(id);
        }

        Long parentId(LogicalSubjectArea area) {
            return area.getParent() == null ? null : area.getParent().getId();
        }

        List<LogicalSubjectArea> children(Long parentId) {
            return children.getOrDefault(parentId, List.of());
        }

        /** 최상위 = 1. 상위를 따라 올라간다 (깨진 순환이 있어도 끝나도록 영역 수만큼만) */
        int depth(Long id) {
            int depth = 0;
            LogicalSubjectArea area = byId.get(id);
            while (area != null && depth <= byId.size()) {
                depth++;
                area = byId.get(parentId(area));
            }
            return depth;
        }

        /** 자기 아래로 몇 단계가 더 있는가 (하위 없음 = 0) */
        int height(Long id) {
            int height = 0;
            for (LogicalSubjectArea child : children(id)) height = Math.max(height, height(child.getId()) + 1);
            return height;
        }

        List<String> path(Long id) {
            List<String> path = new ArrayList<>();
            LogicalSubjectArea area = byId.get(id);
            while (area != null && path.size() <= byId.size()) {
                path.addFirst(area.getAreaName());
                area = byId.get(parentId(area));
            }
            return path;
        }

        /** 자기와 하위 전체 */
        Set<Long> subtree(Long id) {
            Set<Long> result = new LinkedHashSet<>();
            List<Long> queue = new ArrayList<>(List.of(id));
            while (!queue.isEmpty()) {
                Long next = queue.removeFirst();
                if (!result.add(next)) continue;
                children(next).forEach(c -> queue.add(c.getId()));
            }
            return result;
        }

        /** 상위 → 하위(깊이 우선), 같은 상위 안에서는 순서·이름·ID 순 */
        List<LogicalSubjectArea> ordered() {
            List<LogicalSubjectArea> result = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            walk(null, result, seen);
            return result;
        }

        private void walk(Long parentId, List<LogicalSubjectArea> result, Set<Long> seen) {
            for (LogicalSubjectArea child : children(parentId)) {
                if (!seen.add(child.getId())) continue;
                result.add(child);
                walk(child.getId(), result, seen);
            }
        }
    }
}
