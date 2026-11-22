package com.lexorma.data.logical.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.common.jpa.ModelBase;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.entity.*;
import com.lexorma.data.logical.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 논리 모델 슈퍼타입·서브타입 (M-6 후속 5차, docs/completed/logical-subtype.md).
 * <ul>
 *   <li>범위 : 같은 논리 모델 안에서만. 단일 상속(엔터티는 그룹 하나의 서브타입 — DB UNIQUE), 루트 = 1단계·최대 3단계, 슈퍼타입마다 그룹 여러 개, 그룹은 서브타입 2개 이상</li>
 *   <li>상속 속성·식별자는 저장하지 않는다 : 루트 → 자기 자신 순서로 각 엔터티의 원본 행을 모아 보여 준다 (원본 속성 ID 그대로). 주 식별자는 루트의 것을 상속하고 서브타입은 자체 주 식별자를 둘 수 없다</li>
 *   <li>잠금 : 모든 상속 쓰기는 논리 모델 행을 먼저 잠근다 (엔터티·속성·식별자·관계 저장과 같은 잠금 — 순환·단일 상속·이름 충돌·승인 검사가 직렬화된다). 그룹 수정은 그룹의 수정 일시(DEC-08)를 비교한다</li>
 *   <li>승인 : 그룹에는 승인 상태가 없다. 상속 변경은 관련 엔터티(슈퍼타입·서브타입과 유효 정의가 바뀌는 후손)의 정의 변경이라 그중 승인된 엔터티가 있으면 누구든 거절한다 (승인 권한자도 우회 불가)</li>
 *   <li>참조 무결성 : 상속 변경으로 유효 주 식별자가 바뀌는 엔터티가 부모인 관계에 속성 짝이 있거나, 그룹의 분류 속성이 더 이상 유효 속성이 아니게 되면 거절한다 (자동 정리·복제 없음)</li>
 * </ul>
 * 다른 서비스(속성·식별자·관계·승인·삭제)가 쓰는 검사도 여기 있다 : 모두 호출한 쪽이 논리 모델 행을 잠근 뒤 부른다.
 */
@Service
@RequiredArgsConstructor
public class LogicalSubtypeService {

    /** 최소 구성원 수 (D-09·D-44). 업무상 최대 수는 없다 (D-51) — 요청 본문·Import 객체 수 같은 공통 제한만 적용된다 */
    static final int MEMBER_MIN = 2;
    private static final int VALUE_MAX = 255;
    private static final String GROUP = "서브타입 그룹";

    private final LogicalModelRepository modelRepository;
    private final LogicalEntityRepository entityRepository;
    private final LogicalAttributeRepository attributeRepository;
    private final LogicalIdentifierRepository identifierRepository;
    private final LogicalRelationshipRepository relationshipRepository;
    private final LogicalRelationshipAttributeRepository pairRepository;
    private final LogicalSubtypeGroupRepository groupRepository;
    private final LogicalSubtypeRepository subtypeRepository;
    private final LogicalModelQueryRepository queryRepository;
    private final jakarta.persistence.EntityManager entityManager;

    // ------------------------------------------------------------------ 계층

    /** 모델의 상속 계층. 그룹이 없는 모델은 조회 1번으로 끝난다 */
    public LogicalHierarchy hierarchy(Long modelId) {
        // ID 만 읽는다 (그룹·구성원 엔티티를 만들지 않는다 — 큰 모델에서 요청마다 수천 개의 객체를 만들던 것을 줄였다)
        List<Object[]> groups = groupRepository.findLinksByModelId(modelId);
        return groups.isEmpty() ? LogicalHierarchy.empty() : LogicalHierarchy.ofLinks(groups, subtypeRepository.findLinksByModelId(modelId));
    }

    /** 여러 모델의 계층 (물리 생성 — 모델마다 따로 만들지 않고 한 번에 읽는다). 엔터티는 한 모델에만 있으므로 합쳐도 섞이지 않는다 */
    public LogicalHierarchy hierarchyOf(Collection<Long> modelIds) {
        if (modelIds.isEmpty()) return LogicalHierarchy.empty();
        List<Object[]> groups = groupRepository.findLinksByModelIds(modelIds);
        return groups.isEmpty() ? LogicalHierarchy.empty() : LogicalHierarchy.ofLinks(groups, subtypeRepository.findLinksByModelIds(modelIds));
    }

    // ------------------------------------------------------------------ 그룹 조회

    /** 그룹 목록 페이지 : 그룹명·슈퍼타입명 검색(엔터티 검색과 같은 규칙), 정렬 groupName·supertypeEntityName·modifiedAt (기본 슈퍼타입명 → 그룹 순서), 같은 값은 ID 순 */
    @Transactional(readOnly = true)
    public Page<LogicalSubtypeDto.GroupRow> selectGroupRows(AccountDto accountDto, Long modelId, String keyword, int page, int size, String sort) {
        model(accountDto.getProjectId(), modelId);
        String pattern = LogicalModelService.likePattern(keyword);
        var pageable = com.lexorma.common.utils.PageableUtil.getGridPageable(page, size, sort, LogicalModelQueryRepository.GROUP_SORT_FIELDS);
        return queryRepository.findGroupRows(modelId, pattern, pageable);
    }

    @Transactional(readOnly = true)
    public LogicalSubtypeDto.Group selectGroup(AccountDto accountDto, Long groupId) {
        LogicalSubtypeGroup group = groupRepository.findByIdAndProjectId(groupId, accountDto.getProjectId()).orElseThrow(() -> LogicalModelService.notFound(GROUP));
        return groups(List.of(group.getId())).getFirst();
    }

    /** 그룹 상세 여러 개 (슈퍼타입·분류 속성·구성원을 종류마다 한 번에) — 요청 순서 그대로 */
    private List<LogicalSubtypeDto.Group> groups(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, LogicalSubtypeGroup> byId = groupRepository.findWithEntities(ids).stream().collect(Collectors.toMap(LogicalSubtypeGroup::getId, Function.identity()));
        Map<Long, List<LogicalSubtypeDto.Member>> members = new HashMap<>();
        for (LogicalSubtype m : subtypeRepository.findWithEntities(ids)) {
            members.computeIfAbsent(m.getGroup().getId(), id -> new ArrayList<>()).add(new LogicalSubtypeDto.Member(m.getSubtypeEntity().getId(),
                    m.getSubtypeEntity().getEntityName(), m.getSubtypeEntity().getIsApproval(), m.getClassificationValue(), m.getSortOrder()));
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).map(g -> {
            LogicalAttribute a = g.getClassificationAttribute();
            return new LogicalSubtypeDto.Group(g.getId(), g.getModel().getId(), g.getSupertypeEntity().getId(), g.getSupertypeEntity().getEntityName(),
                    g.getSupertypeEntity().getIsApproval(), g.getGroupName(), g.getGroupDescription(), g.getOverlapping(), g.getTotal(),
                    a == null ? null : a.getId(), a == null ? null : a.getAttributeName(), a == null ? null : a.getEntity().getId(), a == null ? null : a.getEntity().getEntityName(),
                    g.getSortOrder(), members.getOrDefault(g.getId(), List.of()), g.modifiedAt());
        }).toList();
    }

    // ------------------------------------------------------------------ 그룹 저장 (등록·수정 — 구성원 전체 교체, 한 트랜잭션)

    @Transactional
    public LogicalSubtypeDto.Group saveGroup(AccountDto accountDto, LogicalSubtypeDto.Save request) {
        Long projectId = accountDto.getProjectId();
        if (request == null) throw LogicalModelService.invalid("저장할 서브타입 그룹이 없습니다.");
        LogicalSubtypeGroup group;
        LogicalModel model;
        if (request.id() == null) {
            model = lockModel(projectId, request.modelId());
            group = LogicalSubtypeGroup.builder().projectId(projectId).model(model).build();
        } else {
            model = lockModel(projectId, groupRepository.findModelId(request.id(), projectId).orElseThrow(() -> LogicalModelService.notFound(GROUP)));
            group = groupRepository.findByIdAndProjectId(request.id(), projectId).orElseThrow(() -> LogicalModelService.notFound(GROUP));
            entityManager.refresh(group);
            ModelBase.verify(group, request.modifiedAt(), group.getGroupName()); // DEC-08 : 모델 잠금 뒤 다시 읽은 그룹과 화면이 읽은 수정 일시
        }
        Long modelId = model.getId();
        State state = state(modelId);
        LogicalHierarchy before = state.hierarchy();

        LogicalEntity supertype = modelEntity(projectId, modelId, request.supertypeEntityId());
        String name = LogicalModelService.name(request.groupName(), "그룹명");
        String description = LogicalModelService.description(request.groupDescription());
        if (request.overlapping() == null) throw LogicalModelService.invalid("배타성(Disjoint·Overlapping)을 선택해주세요.");
        if (request.total() == null) throw LogicalModelService.invalid("완전성(Total·Partial)을 선택해주세요.");

        // 구성원 : 2개 이상, 겹치지 않게, 같은 모델의 엔터티, 슈퍼타입 자신이 아닌 것
        List<LogicalSubtypeDto.MemberInput> inputs = request.subtypes() == null ? List.of() : request.subtypes();
        if (inputs.size() < MEMBER_MIN) throw LogicalModelService.invalid("서브타입을 {0}개 이상 지정해주세요.", MEMBER_MIN);
        List<Long> memberIds = new ArrayList<>();
        Set<Long> memberSet = new HashSet<>(); // 구성원이 많아도 중복 검사가 구성원 수에 비례하도록
        List<String> values = new ArrayList<>();
        for (LogicalSubtypeDto.MemberInput input : inputs) {
            if (input == null || input.entityId() == null) throw LogicalModelService.invalid("서브타입 엔터티를 골라주세요.");
            if (!memberSet.add(input.entityId())) throw LogicalModelService.invalid("같은 엔터티를 서브타입으로 두 번 지정할 수 없습니다.");
            memberIds.add(input.entityId());
            values.add(value(input.classificationValue()));
        }
        if (memberSet.contains(supertype.getId())) throw LogicalModelService.invalid("엔터티 {0} 은(는) 자기 자신의 서브타입이 될 수 없습니다.", supertype.getEntityName());
        Map<Long, LogicalEntity> members = new HashMap<>();
        entityRepository.findAllByIds(memberIds, projectId).forEach(e -> members.put(e.getId(), e));
        for (Long id : memberIds) {
            LogicalEntity member = members.get(id);
            if (member == null) throw LogicalModelService.notFound("엔터티");
            if (!member.getModel().getId().equals(modelId)) throw LogicalModelService.invalid("서브타입은 슈퍼타입과 같은 논리 모델의 엔터티여야 합니다.");
        }

        // 그룹명 : 같은 슈퍼타입 안에서 대소문자 무시로 하나
        for (LogicalSubtypeGroup other : state.groups()) {
            if (!other.getId().equals(group.getId()) && other.getSupertypeEntity().getId().equals(supertype.getId()) && other.getGroupName().equalsIgnoreCase(name)) {
                throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
            }
        }
        // 단일 상속 : 다른 그룹의 서브타입이면 거절 (DB UNIQUE 는 잠금을 우회한 입력을 막는 마지막 방어)
        for (Long id : memberIds) {
            LogicalHierarchy.Group current = before.parentGroup(id);
            if (current != null && !current.id().equals(group.getId())) {
                throw LogicalModelService.invalid("엔터티 {0} 은(는) 이미 그룹 {1} 의 서브타입입니다. 엔터티 하나는 하나의 슈퍼타입만 가질 수 있습니다.",
                        members.get(id).getEntityName(), groupName(state, current.id()));
            }
        }

        LogicalHierarchy after = before.with(group.getId(), supertype.getId(), memberIds);
        EntityNames names = new EntityNames(modelId);
        // 순환 : 슈퍼타입의 조상에 서브타입(또는 그 후손)이 있으면
        if (after.cyclic(supertype.getId())) {
            Long member = memberIds.stream().filter(id -> before.subtree(id).contains(supertype.getId())).findFirst().orElse(memberIds.getFirst());
            throw LogicalModelService.invalid("상속이 순환합니다 : {0} 은(는) 이미 {1} 의 상위 엔터티입니다.", names.get(member), supertype.getEntityName());
        }
        // 깊이 : 서브타입과 그 후손 전체 (하위 트리를 옮기는 경우 포함)
        for (Long id : memberIds) {
            for (Long d : after.subtree(id)) {
                int level = after.level(d);
                if (level > LogicalHierarchy.MAX_LEVEL) {
                    throw LogicalModelService.invalid("상속은 최대 {0}단계까지 가능합니다 : 엔터티 {1} 이(가) {2}단계가 됩니다.", LogicalHierarchy.MAX_LEVEL, names.get(d), level);
                }
            }
        }

        LogicalHierarchy.Group old = group.getId() == null ? null : before.group(group.getId());
        Set<Long> touched = new LinkedHashSet<>(memberIds);
        if (old != null) touched.addAll(old.memberIds());
        Set<Long> changed = changedEntities(before, after, touched);
        Map<Long, LogicalEntity> loaded = graph(modelId, union(changed, chains(before, changed), chains(after, changed), List.of(supertype.getId())));
        Map<Long, List<LogicalIdentifier>> identifiers = identifiers(loaded.keySet());

        // 승인 : 슈퍼타입(옛 슈퍼타입 포함), 구성원(빠지는 것 포함), 유효 정의가 바뀌는 후손 중 승인된 것이 있으면 거절
        Set<Long> approvalTargets = new LinkedHashSet<>(List.of(supertype.getId()));
        if (old != null) approvalTargets.add(old.supertypeId());
        approvalTargets.addAll(touched);
        approvalTargets.addAll(changed);
        rejectApproved(approvalTargets, names);

        // 자체 주 식별자가 있는 엔터티는 서브타입이 될 수 없다 (자동 삭제·변환 없음)
        for (Long id : memberIds) {
            primary(identifiers.get(id)).ifPresent(i -> {
                throw LogicalModelService.invalid("엔터티 {0} 에 자체 주 식별자 {1} 이(가) 있어 서브타입으로 연결할 수 없습니다. 서브타입은 슈퍼타입의 주 식별자를 상속합니다.",
                        names.get(id), i.getIdentifierName());
            });
        }
        // 속성명 : 유효 정의가 바뀌는 엔터티의 직접 속성 ↔ 새 조상의 속성 (대소문자 무시, 기존 속성명 규칙)
        for (Long id : changed) {
            for (Long up : after.ancestors(id)) {
                for (LogicalAttribute own : attributes(loaded, id)) {
                    for (LogicalAttribute inherited : attributes(loaded, up)) {
                        if (own.getAttributeName().equalsIgnoreCase(inherited.getAttributeName())) {
                            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "엔터티 {0} 의 속성 {1} 이(가) 상위 엔터티 {2} 의 속성 {3} 과(와) 이름이 같아 상속할 수 없습니다.",
                                    names.get(id), own.getAttributeName(), names.get(up), inherited.getAttributeName());
                        }
                    }
                }
            }
        }
        // 분류 속성 : 슈퍼타입의 유효 속성 (슈퍼타입 또는 새 조상의 원본 속성)
        LogicalAttribute classification = null;
        if (request.classificationAttributeId() != null) {
            Long attributeId = request.classificationAttributeId();
            List<Long> chain = after.chain(supertype.getId());
            classification = chain.stream().flatMap(id -> attributes(loaded, id).stream()).filter(a -> a.getId().equals(attributeId)).findFirst()
                    .orElseThrow(() -> LogicalModelService.invalid("분류 속성은 슈퍼타입 {0} 의 유효 속성(직접 정의 또는 상속)이어야 합니다.", supertype.getEntityName()));
        }
        // 분류 값 : 배타(Disjoint) 그룹 안에서 같은 값(앞뒤 공백·대소문자 무시)을 두 서브타입에 줄 수 없다
        if (!request.overlapping()) {
            Set<String> seen = new HashSet<>();
            for (String v : values) {
                if (v != null && !seen.add(v.toLowerCase(Locale.ROOT))) throw LogicalModelService.invalid("배타(Disjoint) 그룹에서는 분류 값이 겹칠 수 없습니다 : {0}", v);
            }
        }
        // 참조 무결성 (D-43) : 유효 주 식별자가 바뀌는 엔터티가 부모인 속성 짝, 다른 그룹의 분류 속성
        requireReferencesKept(state, before, after, changed, loaded, identifiers, names, group.getId());

        // ---- 저장
        boolean created = group.getId() == null;
        Long oldSupertype = old == null ? null : old.supertypeId();
        if (created || !Objects.equals(oldSupertype, supertype.getId())) {
            group.setSortOrder(state.groups().stream().filter(g -> g.getSupertypeEntity().getId().equals(supertype.getId()) && !g.getId().equals(group.getId()))
                    .mapToInt(LogicalSubtypeGroup::getSortOrder).max().orElse(0) + 1);
        }
        group.setSupertypeEntity(supertype);
        group.setGroupName(name);
        group.setGroupDescription(description);
        group.setOverlapping(request.overlapping());
        group.setTotal(request.total());
        group.setClassificationAttribute(classification);
        LogicalSubtypeGroup saved = groupRepository.save(group);

        Map<Long, LogicalSubtype> current = new HashMap<>();
        state.members().stream().filter(m -> m.getGroup().getId().equals(saved.getId())).forEach(m -> current.put(m.getSubtypeEntity().getId(), m));
        boolean composition = false;
        List<LogicalSubtype> removed = current.values().stream().filter(m -> !memberSet.contains(m.getSubtypeEntity().getId())).toList();
        if (!removed.isEmpty()) {
            subtypeRepository.deleteAll(removed);
            subtypeRepository.flush();
            composition = true;
        }
        for (int i = 0; i < memberIds.size(); i++) {
            LogicalSubtype row = current.get(memberIds.get(i));
            if (row == null) {
                subtypeRepository.save(LogicalSubtype.builder().projectId(projectId).modelId(modelId).group(saved).subtypeEntity(members.get(memberIds.get(i)))
                        .classificationValue(values.get(i)).sortOrder(i + 1).build());
                composition = true;
            } else if (!Objects.equals(row.getClassificationValue(), values.get(i)) || !Objects.equals(row.getSortOrder(), i + 1)) {
                row.setClassificationValue(values.get(i));
                row.setSortOrder(i + 1);
                composition = true;
            }
        }
        if (!created && composition) saved.markModified(); // 구성원만 바뀌어도 그룹 기준값이 바뀐다 (DEC-08)
        flush();
        entityManager.refresh(saved); // 응답의 기준값은 저장된 값 (timestamp(6))
        return groups(List.of(saved.getId())).getFirst();
    }

    /** 그룹 삭제 : 명시적 요청만 (구성원 제거로 그룹이 저절로 지워지지 않는다). 구성원 엔터티는 지우지 않는다. 검사는 구성원 전부를 빼는 것과 같다 */
    @Transactional
    public void deleteGroup(AccountDto accountDto, Long groupId, java.time.LocalDateTime modifiedAt) {
        Long projectId = accountDto.getProjectId();
        LogicalModel model = lockModel(projectId, groupRepository.findModelId(groupId, projectId).orElseThrow(() -> LogicalModelService.notFound(GROUP)));
        LogicalSubtypeGroup group = groupRepository.findByIdAndProjectId(groupId, projectId).orElseThrow(() -> LogicalModelService.notFound(GROUP));
        entityManager.refresh(group);
        ModelBase.verify(group, modifiedAt, group.getGroupName());
        Long modelId = model.getId();
        State state = state(modelId);
        LogicalHierarchy before = state.hierarchy();
        LogicalHierarchy.Group old = before.group(groupId);
        LogicalHierarchy after = before.with(groupId, null, List.of());
        Set<Long> changed = changedEntities(before, after, old.memberIds());
        EntityNames names = new EntityNames(modelId);
        Set<Long> approvalTargets = new LinkedHashSet<>(List.of(old.supertypeId()));
        approvalTargets.addAll(old.memberIds());
        approvalTargets.addAll(changed);
        rejectApproved(approvalTargets, names);
        Map<Long, LogicalEntity> loaded = graph(modelId, union(changed, chains(before, changed), chains(after, changed), List.of()));
        requireReferencesKept(state, before, after, changed, loaded, identifiers(loaded.keySet()), names, groupId);
        subtypeRepository.deleteAll(state.members().stream().filter(m -> m.getGroup().getId().equals(groupId)).toList());
        subtypeRepository.flush();
        groupRepository.delete(group);
        flush();
    }

    // ------------------------------------------------------------------ 엔터티의 상속 정보

    @Transactional(readOnly = true)
    public LogicalSubtypeDto.Inheritance selectInheritance(AccountDto accountDto, Long entityId) {
        LogicalEntity entity = entity(accountDto.getProjectId(), entityId);
        Long modelId = entity.getModel().getId();
        LogicalHierarchy h = hierarchy(modelId);
        List<Long> chain = h.chain(entityId);
        Map<Long, LogicalEntity> loaded = graph(modelId, chain);
        Map<Long, List<LogicalIdentifier>> identifiers = identifiers(chain);
        Long root = h.root(entityId);
        boolean subtype = chain.size() > 1;
        boolean approvedBelow = !approvedAmong(h.descendants(entityId)).isEmpty();

        List<LogicalSubtypeDto.Ancestor> ancestors = new ArrayList<>();
        for (int i = 0; i < chain.size() - 1; i++) {
            LogicalEntity a = loaded.get(chain.get(i));
            ancestors.add(new LogicalSubtypeDto.Ancestor(a.getId(), a.getEntityName(), a.getIsApproval(), i + 1));
        }
        LogicalSubtypeDto.Parent parent = null;
        LogicalHierarchy.Group parentGroup = h.parentGroup(entityId);
        if (parentGroup != null) {
            // 부모 그룹의 정의와 이 엔터티의 분류 값만 읽는다 (구성원이 많은 그룹의 다른 구성원은 읽지 않는다, D-51)
            LogicalSubtypeGroup g = groupRepository.findWithEntities(List.of(parentGroup.id())).getFirst();
            String value = subtypeRepository.findValues(List.of(g.getId()), List.of(entityId)).stream().findFirst().map(row -> (String) row[1]).orElse(null);
            LogicalAttribute a = g.getClassificationAttribute();
            parent = new LogicalSubtypeDto.Parent(g.getId(), g.getGroupName(), g.getSupertypeEntity().getId(), g.getSupertypeEntity().getEntityName(), g.getOverlapping(), g.getTotal(),
                    a == null ? null : a.getId(), a == null ? null : a.getAttributeName(), value);
        }
        List<LogicalSubtypeDto.Group> groups = groups(h.groupsOf(entityId).stream().map(LogicalHierarchy.Group::id).toList());
        boolean rootKeyMissing = subtype && primary(identifiers.get(root)).isEmpty();
        List<LogicalSubtypeDto.EffectiveAttribute> attributes = effectiveAttributes(entity, chain, loaded, identifiers, editable(entity, approvedBelow));
        List<LogicalSubtypeDto.EffectiveIdentifier> effectiveIdentifiers = effectiveIdentifiers(entityId, chain, loaded, identifiers);

        // 상속 관계 : 조상에 정의된 일반 관계 (복제하지 않는다). 가까운 조상부터 역할을 정한다
        List<LogicalSubtypeDto.InheritedRelationship> inherited = new ArrayList<>();
        List<Long> up = h.ancestors(entityId);
        if (!up.isEmpty()) {
            for (LogicalRelationship r : relationshipRepository.findAllByEntityIds(up)) {
                for (Long a : up) {
                    boolean source = r.getSourceEntity().getId().equals(a);
                    boolean target = r.getTargetEntity().getId().equals(a);
                    if (!source && !target) continue;
                    inherited.add(new LogicalSubtypeDto.InheritedRelationship(a, loaded.get(a).getEntityName(), source && target ? "SELF" : source ? "SOURCE" : "TARGET",
                            LogicalModelDto.Relationship.fromEntity(r)));
                    break;
                }
            }
        }
        return new LogicalSubtypeDto.Inheritance(entity.getId(), entity.getEntityName(), entity.getIsApproval(), entity.modifiedAt(), chain.size(), root,
                loaded.get(root).getEntityName(), parent, ancestors, groups, h.descendants(entityId).size(), rootKeyMissing, attributes, effectiveIdentifiers, inherited);
    }

    @Transactional(readOnly = true)
    public LogicalSubtypeDto.EffectiveAttributes selectEffectiveAttributes(AccountDto accountDto, Long entityId) {
        LogicalEntity entity = entity(accountDto.getProjectId(), entityId);
        Long modelId = entity.getModel().getId();
        LogicalHierarchy h = hierarchy(modelId);
        List<Long> chain = h.chain(entityId);
        Map<Long, LogicalEntity> loaded = graph(modelId, chain);
        boolean approvedBelow = !approvedAmong(h.descendants(entityId)).isEmpty();
        return new LogicalSubtypeDto.EffectiveAttributes(entityId, effectiveAttributes(entity, chain, loaded, identifiers(chain), editable(entity, approvedBelow)));
    }

    /** 루트 → 자기 자신 순서, 엔터티 안은 속성 순서. 유효 주 식별자 = 루트의 주 식별자 */
    private static List<LogicalSubtypeDto.EffectiveAttribute> effectiveAttributes(LogicalEntity context, List<Long> chain, Map<Long, LogicalEntity> loaded,
                                                                                Map<Long, List<LogicalIdentifier>> identifiers, boolean ownEditable) {
        List<Long> key = primary(identifiers.get(chain.getFirst())).map(LogicalSubtypeService::keyIds).orElse(List.of());
        List<LogicalSubtypeDto.EffectiveAttribute> result = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            LogicalEntity owner = loaded.get(chain.get(i));
            int depth = chain.size() - 1 - i;
            for (LogicalAttribute a : attributes(loaded, owner.getId())) {
                LogicalModelDto.Attribute dto = LogicalModelDto.Attribute.fromEntity(a);
                int order = key.indexOf(a.getId());
                result.add(new LogicalSubtypeDto.EffectiveAttribute(a.getId(), context.getId(), owner.getId(), owner.getEntityName(), depth > 0, depth, depth == 0 && ownEditable,
                        dto.attributeName(), dto.attributeDescription(), dto.dataType(), dto.isRequired(), dto.sortOrder(), dto.termId(), dto.termName(), dto.domainName(),
                        order >= 0, order >= 0 ? order + 1 : null, a.modifiedAt()));
            }
        }
        return result;
    }

    /** 상속 주 식별자(서브타입이면 루트의 것) → 조상의 보조 식별자 → 자기 식별자 */
    private static List<LogicalSubtypeDto.EffectiveIdentifier> effectiveIdentifiers(Long entityId, List<Long> chain, Map<Long, LogicalEntity> loaded,
                                                                                  Map<Long, List<LogicalIdentifier>> identifiers) {
        List<LogicalSubtypeDto.EffectiveIdentifier> result = new ArrayList<>();
        for (Long owner : chain) {
            boolean inherited = !owner.equals(entityId);
            for (LogicalIdentifier i : identifiers.getOrDefault(owner, List.of())) {
                if (inherited && Boolean.TRUE.equals(i.getIsPrimary()) && !owner.equals(chain.getFirst())) continue; // 중간 조상의 주 식별자는 없다 (방어)
                List<LogicalIdentifierAttribute> parts = i.getAttributes().stream().sorted(Comparator.comparing(LogicalIdentifierAttribute::getSortOrder)
                        .thenComparing(LogicalIdentifierAttribute::getId)).toList();
                result.add(new LogicalSubtypeDto.EffectiveIdentifier(i.getId(), i.getIdentifierName(), i.getIsPrimary(), owner, loaded.get(owner).getEntityName(), inherited,
                        parts.stream().map(p -> p.getAttribute().getId()).toList(), parts.stream().map(p -> p.getAttribute().getAttributeName()).toList()));
            }
        }
        result.sort(Comparator.comparing((LogicalSubtypeDto.EffectiveIdentifier i) -> !Boolean.TRUE.equals(i.isPrimary())).thenComparing(i -> !i.inherited()));
        return result;
    }

    /** 직접 속성을 지금 사용자가 고칠 수 있는지 : 승인되지 않았거나 승인 권한자(속성·식별자 규칙), 그리고 승인된 후손이 없을 때 */
    private static boolean editable(LogicalEntity entity, boolean approvedBelow) {
        return (!Boolean.TRUE.equals(entity.getIsApproval()) || AuthUtil.isApprovalAvailable()) && !approvedBelow;
    }

    // ------------------------------------------------------------------ 다른 서비스가 쓰는 검사 (호출한 쪽이 모델 행을 잠근 뒤)

    /** 엔터티 삭제 : 슈퍼타입이거나 서브타입이면 거절 (상속을 먼저 해제한다 — 하위 엔터티·그룹을 자동으로 지우지 않는다) */
    public void requireNotParticipating(LogicalEntity entity) {
        LogicalHierarchy h = hierarchy(entity.getModel().getId());
        if (!h.groupsOf(entity.getId()).isEmpty()) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "엔터티 {0} 은(는) 서브타입 그룹 {1} 의 슈퍼타입입니다. 서브타입 그룹을 먼저 삭제해주세요.",
                    entity.getEntityName(), groupName(h.groupsOf(entity.getId()).getFirst().id()));
        }
        LogicalHierarchy.Group parent = h.parentGroup(entity.getId());
        if (parent != null) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "엔터티 {0} 은(는) 서브타입 그룹 {1} 의 서브타입입니다. 상속을 먼저 해제해주세요.",
                    entity.getEntityName(), groupName(parent.id()));
        }
    }

    /** 정의 변경(속성·식별자·상속되는 관계) : 이 엔터티의 유효 정의를 상속하는 승인된 후손이 있으면 거절 (승인 권한자도 — 하위 엔터티부터 승인을 취소한다) */
    public void requireNoApprovedDescendant(LogicalEntity entity, ErrorCode errorCode) {
        LogicalHierarchy h = hierarchy(entity.getModel().getId());
        List<LogicalEntity> approved = approvedAmong(h.descendants(entity.getId()));
        if (!approved.isEmpty()) {
            throw new CustomException(errorCode, "승인된 하위 엔터티 {0} 이(가) 엔터티 {1} 의 정의를 상속하고 있습니다. 하위 엔터티의 승인을 먼저 취소해주세요.",
                    approved.getFirst().getEntityName(), entity.getEntityName());
        }
    }

    /** 속성명 : 조상 사슬의 속성과 후손 전체의 직접 속성 중 같은 이름(대소문자 무시)이 있으면 거절. 형제 서브타입끼리는 겹쳐도 된다 */
    public void requireAttributeNameFree(LogicalEntity entity, String name, Long attributeId) {
        LogicalHierarchy h = hierarchy(entity.getModel().getId());
        if (!h.participates(entity.getId())) return;
        List<Long> related = new ArrayList<>(h.ancestors(entity.getId()));
        related.addAll(h.descendants(entity.getId()));
        if (related.isEmpty()) return;
        Map<Long, LogicalEntity> loaded = graph(entity.getModel().getId(), related);
        for (Long id : related) {
            for (LogicalAttribute a : attributes(loaded, id)) {
                if (!a.getId().equals(attributeId) && a.getAttributeName().equalsIgnoreCase(name)) {
                    throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "상속 계층의 엔터티 {0} 에 같은 이름의 속성 {1} 이(가) 있습니다.", loaded.get(id).getEntityName(), a.getAttributeName());
                }
            }
        }
    }

    /** 주 식별자 : 서브타입은 자체 주 식별자를 둘 수 없다 (루트의 주 식별자를 상속한다) */
    public void requireMayHavePrimaryIdentifier(LogicalEntity entity) {
        LogicalHierarchy h = hierarchy(entity.getModel().getId());
        Long parent = h.parent(entity.getId());
        if (parent != null) {
            throw LogicalModelService.invalid("서브타입 {0} 은(는) 자체 주 식별자를 둘 수 없습니다. 루트 슈퍼타입 {1} 의 주 식별자를 상속합니다.",
                    entity.getEntityName(), new EntityNames(entity.getModel().getId()).get(h.root(entity.getId())));
        }
    }

    /** 속성 삭제 : 서브타입 그룹의 분류 속성이면 거절 */
    public void requireNotClassification(LogicalAttribute attribute) {
        groupRepository.findFirstByClassificationAttribute_Id(attribute.getId()).ifPresent(g -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "서브타입 그룹 {0} 의 분류 속성입니다. 그룹의 분류 속성을 먼저 바꿔주세요.", g.getGroupName());
        });
    }

    /**
     * 승인·승인 취소 : 서브타입 승인은 모든 조상이 승인되어 있고 루트에 주 식별자가 있을 때만 (조상을 자동 승인하지 않는다),
     * 승인 취소는 승인된 후손이 없을 때만 (하위 엔터티부터 취소 — 자동 취소하지 않는다)
     */
    public void checkApprovalChange(LogicalEntity entity, boolean approve) {
        LogicalHierarchy h = hierarchy(entity.getModel().getId());
        if (!h.participates(entity.getId())) return;
        if (approve) {
            List<Long> up = h.ancestors(entity.getId());
            if (up.isEmpty()) return;
            if (h.cyclic(entity.getId()) || h.level(entity.getId()) > LogicalHierarchy.MAX_LEVEL) {
                throw LogicalModelService.invalid("엔터티 {0} 의 상속 구조가 올바르지 않아 승인할 수 없습니다.", entity.getEntityName());
            }
            Map<Long, LogicalEntity> ancestors = new HashMap<>();
            entityRepository.findAllByIds(up, entity.getProjectId()).forEach(e -> ancestors.put(e.getId(), e));
            for (Long id : up) {
                LogicalEntity a = ancestors.get(id);
                if (a != null && !Boolean.TRUE.equals(a.getIsApproval())) {
                    throw LogicalModelService.invalid("상위 엔터티 {0} 이(가) 승인되지 않아 서브타입 {1} 을(를) 승인할 수 없습니다. 상위 엔터티를 먼저 승인해주세요.",
                            a.getEntityName(), entity.getEntityName());
                }
            }
            Long root = h.root(entity.getId());
            if (identifierRepository.findAllByEntityIds(List.of(root)).stream().noneMatch(i -> Boolean.TRUE.equals(i.getIsPrimary()))) {
                throw LogicalModelService.invalid("루트 슈퍼타입 {0} 에 주 식별자가 없어 서브타입 {1} 을(를) 승인할 수 없습니다.", ancestors.get(root).getEntityName(), entity.getEntityName());
            }
        } else {
            List<LogicalEntity> approved = approvedAmong(h.descendants(entity.getId()));
            if (!approved.isEmpty()) {
                throw LogicalModelService.invalid("승인된 하위 엔터티 {0} 이(가) 있어 {1} 의 승인을 취소할 수 없습니다. 하위 엔터티의 승인을 먼저 취소해주세요.",
                        approved.getFirst().getEntityName(), entity.getEntityName());
            }
        }
    }

    // ------------------------------------------------------------------ 논리 ERD (범위 조회·이웃 탐색)

    /** 범위의 서브타입 그룹 : 슈퍼타입이 범위 안이고 서브타입이 하나라도 범위 안인 그룹만, 서브타입은 범위 안의 것만 (범위 밖 엔터티를 노드로 더하지 않는다) */
    public List<LogicalModelDto.GraphSubtypeGroup> graphGroups(LogicalHierarchy h, Set<Long> scope) {
        List<LogicalHierarchy.Group> shown = h.groups().stream()
                .filter(g -> scope.contains(g.supertypeId()) && g.memberIds().stream().anyMatch(scope::contains)).toList();
        if (shown.isEmpty()) return List.of();
        List<Long> ids = shown.stream().map(LogicalHierarchy.Group::id).toList();
        Map<Long, LogicalSubtypeGroup> rows = groupRepository.findWithEntities(ids).stream().collect(Collectors.toMap(LogicalSubtypeGroup::getId, Function.identity()));
        Map<Long, String> values = new HashMap<>();
        Set<Long> shownMembers = shown.stream().flatMap(g -> g.memberIds().stream()).filter(scope::contains).collect(Collectors.toSet());
        subtypeRepository.findValues(ids, shownMembers).forEach(row -> values.put((Long) row[0], (String) row[1]));
        return shown.stream().map(g -> {
            LogicalSubtypeGroup row = rows.get(g.id());
            return new LogicalModelDto.GraphSubtypeGroup(g.id(), row.getGroupName(), g.supertypeId(), row.getOverlapping(), row.getTotal(),
                    row.getClassificationAttribute() == null ? null : row.getClassificationAttribute().getAttributeName(), g.memberIds().size(),
                    g.memberIds().stream().filter(scope::contains).map(id -> new LogicalModelDto.GraphSubtype(id, values.get(id))).toList());
        }).toList();
    }

    /** 범위 안 엔터티 → 상대가 범위 밖인 직접 상속 연결 수 (슈퍼타입 1 + 서브타입마다 1, 그룹 기호는 세지 않는다) */
    public static Map<Long, Integer> outsideLinks(LogicalHierarchy h, Collection<Long> scope) {
        Set<Long> in = new HashSet<>(scope);
        Map<Long, Integer> result = new TreeMap<>();
        for (Long id : in) {
            int outside = (int) h.links(id).stream().filter(other -> !in.contains(other)).count();
            if (outside > 0) result.put(id, outside);
        }
        return result;
    }

    // ------------------------------------------------------------------ 보조

    /** 모델의 그룹·구성원 행과 그 계층 (저장 검사 — 한 번 읽어 여러 번 쓴다) */
    private record State(List<LogicalSubtypeGroup> groups, List<LogicalSubtype> members, LogicalHierarchy hierarchy) {
    }

    private State state(Long modelId) {
        List<LogicalSubtypeGroup> groups = groupRepository.findAllByModelId(modelId);
        List<LogicalSubtype> members = groups.isEmpty() ? List.of() : subtypeRepository.findAllByModelId(modelId);
        return new State(groups, members, LogicalHierarchy.of(groups, members));
    }

    /** 상속 사슬(조상)이 바뀌는 엔터티 : 건드린 서브타입과 그 후손 중 변경 전후 조상이 다른 것 */
    private static Set<Long> changedEntities(LogicalHierarchy before, LogicalHierarchy after, Collection<Long> touched) {
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : touched) {
            Set<Long> tree = new LinkedHashSet<>(before.subtree(id));
            tree.addAll(after.subtree(id));
            for (Long e : tree) {
                if (!before.sameChain(after, e)) result.add(e);
            }
        }
        return result;
    }

    /**
     * 상속 변경이 지금 참조를 깨지 않는지 : (1) 유효 주 식별자가 바뀌는 엔터티가 부모인 관계에 속성 짝이 있으면 거절,
     * (2) 다른 그룹(슈퍼타입이 바뀌는 엔터티)의 분류 속성이 새 사슬에서 유효 속성이 아니면 거절. skipGroup 은 지금 저장하는 그룹 (자기 분류 속성은 따로 검사)
     */
    private void requireReferencesKept(State state, LogicalHierarchy before, LogicalHierarchy after, Set<Long> changed, Map<Long, LogicalEntity> loaded,
                                       Map<Long, List<LogicalIdentifier>> identifiers, EntityNames names, Long skipGroup) {
        if (changed.isEmpty()) return;
        Set<Long> keyChanged = new LinkedHashSet<>();
        for (Long id : changed) {
            List<Long> beforeKey = primary(identifiers.get(before.root(id))).map(LogicalSubtypeService::keyIds).orElse(List.of());
            List<Long> afterKey = primary(identifiers.get(after.root(id))).map(LogicalSubtypeService::keyIds).orElse(List.of());
            if (!beforeKey.equals(afterKey)) keyChanged.add(id);
        }
        if (!keyChanged.isEmpty()) {
            // 엔터티 자체의 보조 식별자 하나 전체를 잇는 짝은 유효 주 식별자가 바뀌어도 그대로 유효하다 (M7-INH-02, D-INH-03·04 — ReferenceKeys.referenceable). 그 밖의 짝만 막는다
            List<LogicalRelationshipAttribute> pairs = pairRepository.findAllBySourceEntityIds(keyChanged);
            Map<Long, List<Long>> parentsByRelationship = new java.util.LinkedHashMap<>();
            pairs.forEach(p -> parentsByRelationship.computeIfAbsent(p.getRelationship().getId(), k -> new java.util.ArrayList<>()).add(p.getParentAttribute().getId()));
            Map<Long, List<LogicalIdentifier>> own = new HashMap<>();
            pairs.stream().map(p -> p.getRelationship().getSourceEntity().getId()).distinct()
                    .forEach(id -> own.put(id, identifiers.containsKey(id) ? identifiers.get(id) : identifierRepository.findAllByEntityIds(List.of(id))));
            pairs.stream().filter(p -> {
                Long source = p.getRelationship().getSourceEntity().getId();
                Set<Long> parents = new java.util.HashSet<>(parentsByRelationship.get(p.getRelationship().getId()));
                List<ReferenceKeys.Key> alternates = ReferenceKeys.referenceable(source, id -> own.getOrDefault(id, List.of()).stream()
                        .filter(i -> !Boolean.TRUE.equals(i.getIsPrimary())).map(i -> new ReferenceKeys.Key(i.getId(), i.getIdentifierName(), false, keyIds(i))).toList());
                return ReferenceKeys.representative(alternates, parents) == null || parents.size() != parentsByRelationship.get(p.getRelationship().getId()).size();
            }).findFirst().ifPresent(p -> {
                LogicalRelationship r = p.getRelationship();
                throw new CustomException(ErrorCode.RELATION_EXISTS, "관계 {0} 의 속성 짝이 엔터티 {1} 의 상속 주 식별자를 쓰고 있어 상속을 바꿀 수 없습니다. 속성 짝을 먼저 해제해주세요.",
                        r.getRelationshipName() != null ? r.getRelationshipName() : r.getSourceEntity().getEntityName() + " → " + r.getTargetEntity().getEntityName(),
                        names.get(r.getSourceEntity().getId()));
            });
        }
        for (LogicalSubtypeGroup g : state.groups()) {
            if (g.getId().equals(skipGroup) || g.getClassificationAttribute() == null || !changed.contains(g.getSupertypeEntity().getId())) continue;
            Long attributeId = g.getClassificationAttribute().getId();
            boolean valid = after.chain(g.getSupertypeEntity().getId()).stream().anyMatch(id -> attributes(loaded, id).stream().anyMatch(a -> a.getId().equals(attributeId)));
            if (!valid) {
                throw new CustomException(ErrorCode.RELATION_EXISTS, "서브타입 그룹 {0} 의 분류 속성이 상속 변경 뒤 슈퍼타입 {1} 의 유효 속성이 아니게 됩니다. 그룹의 분류 속성을 먼저 바꿔주세요.",
                        g.getGroupName(), names.get(g.getSupertypeEntity().getId()));
            }
        }
    }

    /** 고른 엔터티 중 승인된 것이 있으면 거절 (상속 정의 변경 — 승인 권한자도 우회하지 않는다) */
    private void rejectApproved(Collection<Long> ids, EntityNames names) {
        List<LogicalEntity> approved = approvedAmong(ids);
        if (!approved.isEmpty()) {
            throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE, "승인된 엔터티 {0} 의 상속 정의는 바꿀 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.",
                    approved.getFirst().getEntityName());
        }
    }

    /** 승인된 엔터티 (ID 순서대로) */
    private List<LogicalEntity> approvedAmong(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, LogicalEntity> found = new HashMap<>();
        entityManager.createQuery("select e from LogicalEntity e where e.id in :ids and e.isApproval = true", LogicalEntity.class).setParameter("ids", ids)
                .getResultList().forEach(e -> found.put(e.getId(), e));
        return ids.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    /** 엔터티 + 속성(표준 용어·도메인)을 한 번에 */
    private Map<Long, LogicalEntity> graph(Long modelId, Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, LogicalEntity> result = new LinkedHashMap<>();
        entityRepository.findGraphByIds(modelId, new LinkedHashSet<>(ids)).forEach(e -> result.put(e.getId(), e));
        return result;
    }

    private Map<Long, List<LogicalIdentifier>> identifiers(Collection<Long> entityIds) {
        if (entityIds.isEmpty()) return Map.of();
        Map<Long, List<LogicalIdentifier>> result = new HashMap<>();
        identifierRepository.findAllByEntityIds(entityIds).stream().sorted(Comparator.comparing(LogicalIdentifier::getId))
                .forEach(i -> result.computeIfAbsent(i.getEntity().getId(), id -> new ArrayList<>()).add(i));
        return result;
    }

    private static List<LogicalAttribute> attributes(Map<Long, LogicalEntity> loaded, Long entityId) {
        LogicalEntity e = loaded.get(entityId);
        return e == null ? List.of() : e.getAttributes();
    }

    private static Optional<LogicalIdentifier> primary(List<LogicalIdentifier> identifiers) {
        return identifiers == null ? Optional.empty() : identifiers.stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst();
    }

    /** 식별자 구성 속성 ID (식별자 순서 — 복합 주 식별자의 순서를 바꾸지 않는다) */
    static List<Long> keyIds(LogicalIdentifier identifier) {
        return identifier.getAttributes().stream().sorted(Comparator.comparing(LogicalIdentifierAttribute::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LogicalIdentifierAttribute::getId)).map(a -> a.getAttribute().getId()).distinct().toList();
    }

    private static List<Long> chains(LogicalHierarchy h, Collection<Long> ids) {
        List<Long> result = new ArrayList<>();
        ids.forEach(id -> result.addAll(h.ancestors(id)));
        return result;
    }

    @SafeVarargs
    private static Set<Long> union(Collection<Long>... parts) {
        Set<Long> result = new LinkedHashSet<>();
        for (Collection<Long> part : parts) result.addAll(part);
        return result;
    }

    /**
     * 오류 문구에 쓰는 엔터티 이름 : 필요한 엔터티만 그때 한 건씩 읽는다 (D-51 — 저장할 때마다 모델의 엔터티 이름 전체를 읽지 않는다).
     * 같은 모델의 엔터티만 찾는다
     */
    private final class EntityNames {
        private final Long modelId;
        private final Map<Long, String> cache = new HashMap<>();

        private EntityNames(Long modelId) {
            this.modelId = modelId;
        }

        String get(Long entityId) {
            if (entityId == null) return null;
            return cache.computeIfAbsent(entityId, id -> entityManager.createQuery("select e.entityName from LogicalEntity e where e.id = :id and e.model.id = :m", String.class)
                    .setParameter("id", id).setParameter("m", modelId).getResultStream().findFirst().orElse(null));
        }
    }

    private static String groupName(State state, Long groupId) {
        return state.groups().stream().filter(g -> g.getId().equals(groupId)).map(LogicalSubtypeGroup::getGroupName).findFirst().orElse(String.valueOf(groupId));
    }

    private String groupName(Long groupId) {
        return groupRepository.findById(groupId).map(LogicalSubtypeGroup::getGroupName).orElse(String.valueOf(groupId));
    }

    /** 분류 값 : 앞뒤 공백을 뺀다, 비면 없음, 255자까지 */
    private static String value(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > VALUE_MAX) throw LogicalModelService.invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get("분류 값"), VALUE_MAX);
        return value;
    }

    private void flush() {
        try {
            entityManager.flush();
        } catch (DataIntegrityViolationException | org.hibernate.exception.ConstraintViolationException e) {
            // 잠금을 우회한 동시 입력이 단일 상속 UNIQUE 등에 걸렸다 : 다시 불러와 확인하게 한다
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get(GROUP));
        }
    }

    private LogicalModel model(Long projectId, Long modelId) {
        if (modelId == null) throw LogicalModelService.notFound("논리 모델");
        return modelRepository.findByIdAndProjectId(modelId, projectId).orElseThrow(() -> LogicalModelService.notFound("논리 모델"));
    }

    private LogicalModel lockModel(Long projectId, Long modelId) {
        if (modelId == null) throw LogicalModelService.notFound("논리 모델");
        return modelRepository.findForUpdate(modelId, projectId).orElseThrow(() -> LogicalModelService.notFound("논리 모델"));
    }

    private LogicalEntity entity(Long projectId, Long entityId) {
        if (entityId == null) throw LogicalModelService.notFound("엔터티");
        return entityRepository.findByIdAndProjectId(entityId, projectId).orElseThrow(() -> LogicalModelService.notFound("엔터티"));
    }

    /** 슈퍼타입 : 로그인 프로젝트의 같은 논리 모델 엔터티 */
    private LogicalEntity modelEntity(Long projectId, Long modelId, Long entityId) {
        if (entityId == null) throw LogicalModelService.invalid("슈퍼타입 엔터티를 골라주세요.");
        LogicalEntity entity = entity(projectId, entityId);
        if (!entity.getModel().getId().equals(modelId)) throw LogicalModelService.invalid("슈퍼타입은 그룹과 같은 논리 모델의 엔터티여야 합니다.");
        return entity;
    }
}
