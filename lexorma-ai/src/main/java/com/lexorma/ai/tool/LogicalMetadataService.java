package com.lexorma.ai.tool;

import com.lexorma.ai.tool.LogicalMetadataDto.*;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.repository.LogicalEntityRepository;
import com.lexorma.data.logical.repository.LogicalModelQueryRepository;
import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 논리 모델 AI·MCP 공통 조회 (D-35 D35-2·D35-3a, docs/completed/ai-logical-model-query-d35.md). 내부 AI 와 내장 MCP 의 {@code getLogicalModelOverview}·
 * {@code searchLogicalEntities}·{@code getLogicalEntityDetail} 이 모두 이 서비스를 부른다 (MCP 가 꺼져 있어도 내부 AI 가 쓴다 — 네트워크로 자기 MCP 를 부르지 않는다).
 * <ul>
 *   <li><b>범위</b> : 모든 메서드는 호출한 쪽이 검증한 프로젝트 ID 를 받는다 (내부 AI = {@link MetadataToolSession#scope()} 의 실행 컨텍스트, MCP = 토큰의 프로젝트 —
 *       둘 다 {@link MetadataToolset} 경계가 호출마다 VIEWER 를 다시 확인한다). 모델·엔터티·주제 영역·그룹 ID 는 기존 서비스가 그 프로젝트·모델로 다시 확인한다
 *       (다른 프로젝트 것은 '없음'). 이 서비스는 컨트롤러가 없다 — 새 외부 진입점이 아니다</li>
 *   <li><b>재사용</b> : 기존 논리 모델 조회 서비스(요약·엔터티 목록·주제 영역 트리와 범위 규칙·그룹 목록·상속 계층·상속 계산·엔터티 관계)를 그대로 부른다.
 *       상속 계산은 {@link LogicalSubtypeService#selectInheritanceView} (화면과 같은 계산, editable·{@code AuthUtil} 없음 — 로그인 세션 없이 동작)</li>
 *   <li><b>상한</b> (D35-D18 — 기존 값) : 모델·엔터티 목록 20, 유효 속성 100, 직접 서브타입 20, 관계 50, 설명 300자. 표시 상한을 넘는 나머지는 section·page 로 받는다
 *       ({@link Paging#next()} 가 정확한 다음 호출). 서버가 다음 페이지를 스스로 이어 읽어 합치지 않는다 (D35-D22). Tool 호출 횟수·결과 24,000자·트랜잭션 30초는
 *       실행 경계({@link MetadataToolset})가 관리한다</li>
 *   <li><b>조회 수</b> : 엔터티마다 상세를 반복 조회하지 않는다 — 목록은 페이지 엔터티를 묶어 읽고, 상속 요약은 계층(ID 투영)과 그룹 묶음 조회로 만든다</li>
 *   <li><b>트랜잭션</b> : 읽기 전용. 실행 경계의 읽기 전용 트랜잭션에 합류한다 (READ COMMITTED 그대로, 잠금 없음 — D35-D12·D20)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogicalMetadataService {

    /** 유효 속성·식별자·영역·그룹 한 번의 최대 수 : 기존 테이블당 컬럼 상한과 같은 값 */
    static final int MAX_ITEMS = MetadataTools.MAX_COLUMNS;
    /** 관계 최대 수 : 기존 테이블당 관계 상한과 같은 값 */
    static final int MAX_RELATIONSHIPS = MetadataTools.MAX_RELATIONS;
    /** 직접 서브타입·엔터티의 그룹 한 번의 최대 수 : 기존 목록 상한과 같은 값 */
    static final int MAX_SUBTYPES = MetadataTools.MAX_LIMIT;
    /** 항목별 조회(section)의 기본 limit : 기존 목록 최대값 */
    static final int SECTION_DEFAULT_LIMIT = MetadataTools.MAX_LIMIT;

    public static final String OVERVIEW_TOOL = "getLogicalModelOverview";
    public static final String DETAIL_TOOL = "getLogicalEntityDetail";

    /** 엔터티 상세의 조회 항목 (D35-D17) */
    public enum Section { ALL, ATTRIBUTES, IDENTIFIERS, RELATIONS, INHERITANCE }

    /** 모델 개요의 조회 항목 */
    public enum OverviewSection { ALL, SUBJECT_AREAS, SUBTYPE_GROUPS }

    private final LogicalModelService modelService;
    private final LogicalSubjectAreaService subjectAreaService;
    private final LogicalSubtypeService subtypeService;
    private final LogicalModelQueryRepository queryRepository;
    private final LogicalEntityRepository entityRepository;

    // ------------------------------------------------------------------ 모델 목록·개요

    /** 프로젝트의 논리 모델 목록 (이름 → ID 순). 조회 5번 (모델, 모델별 엔터티·관계·영역·그룹 수) */
    @Transactional(readOnly = true)
    public ModelList models(Long projectId, Integer page, Integer limit) {
        AccountDto scope = scope(projectId);
        int size = limit(limit);
        int p = page(page, size);
        List<LogicalModelDto.Model> all = modelService.selectModels(scope);
        Map<Long, LogicalModelQueryRepository.ModelCounts> counts = queryRepository.modelCounts(projectId);
        var none = new LogicalModelQueryRepository.ModelCounts(0, 0, 0, 0);
        List<ModelItem> items = slice(all, p, size).stream().map(m -> {
            var c = counts.getOrDefault(m.id(), none);
            return new ModelItem(m.id(), m.modelName(), MetadataTools.text(m.modelDescription()), c.entities(), c.relationships(), c.subjectAreas(), c.subtypeGroups());
        }).toList();
        return new ModelList(items, Paging.of(p, size, items.size(), all.size()));
    }

    /** 예전 호출 (ALL) */
    @Transactional(readOnly = true)
    public ModelOverview overview(Long projectId, ModelRef ref) {
        return overview(projectId, new OverviewRequest(ref == null ? null : ref.modelId(), ref == null ? null : ref.modelName()));
    }

    /**
     * 모델 하나의 개요 : 수 + 주제 영역 계층(영역마다 직접·하위 포함 엔터티 수) + 서브타입 그룹 요약. 속성·식별자는 넣지 않는다.
     * ALL 은 목록마다 첫 {@value #MAX_ITEMS}개와 나머지를 받는 next, SUBJECT_AREAS·SUBTYPE_GROUPS 는 그 목록만 page·limit (기본 20, 최대 100) 으로
     */
    @Transactional(readOnly = true)
    public ModelOverview overview(Long projectId, OverviewRequest request) {
        AccountDto scope = scope(projectId);
        OverviewRequest r = request == null ? new OverviewRequest(null, null) : request;
        OverviewSection section = enumValue(OverviewSection.class, r.section(), "section", OverviewSection.ALL);
        if (section == OverviewSection.ALL && (r.page() != null || r.limit() != null)) {
            throw invalidArgument("section 이 ALL 이면 page·limit 을 줄 수 없습니다. SUBJECT_AREAS 나 SUBTYPE_GROUPS 와 함께 주세요.");
        }
        int size = section == OverviewSection.ALL ? MAX_ITEMS : sectionLimit(r.limit(), MAX_ITEMS);
        int p = section == OverviewSection.ALL ? 0 : page(r.page(), size);
        LogicalModelDto.Model model = resolveModel(scope, new ModelRef(r.modelId(), r.modelName()));
        LogicalModelDto.Summary summary = modelService.selectSummary(scope, model.id());
        LogicalSubjectAreaDto.Tree tree = subjectAreaService.selectTree(scope, model.id());

        List<SubjectAreaItem> areas = null;
        Paging areasPaging = null;
        if (section != OverviewSection.SUBTYPE_GROUPS) {
            areas = slice(tree.areas(), p, size).stream()
                    .map(a -> new SubjectAreaItem(a.id(), a.areaName(), MetadataTools.text(a.areaDescription()), a.parentId(), a.depth(), a.path(), a.directEntityCount(),
                            a.totalEntityCount()))
                    .toList();
            areasPaging = Paging.of(p, size, areas.size(), tree.areas().size(),
                    new NextCall(OVERVIEW_TOOL, model.id(), null, OverviewSection.SUBJECT_AREAS.name(), null, null, p + 1, size));
        }
        List<SubtypeGroupItem> groups = null;
        Paging groupsPaging = null;
        if (section != OverviewSection.SUBJECT_AREAS) {
            // 그룹 목록은 기존 목록 조회(슈퍼타입명 → 그룹 순서, 같은 값은 ID 순)의 그 페이지 — 전체 수는 페이지의 totalElements
            Page<LogicalSubtypeDto.GroupRow> rows = subtypeService.selectGroupRows(scope, model.id(), null, p, size, null);
            groups = rows.getContent().stream()
                    .map(g -> new SubtypeGroupItem(g.id(), g.groupName(), g.supertypeEntityId(), g.supertypeEntityName(), g.subtypeCount(), g.overlapping(), g.total(),
                            g.classificationAttributeId(), g.classificationAttributeName()))
                    .toList();
            groupsPaging = Paging.of(p, size, groups.size(), rows.getTotalElements(),
                    new NextCall(OVERVIEW_TOOL, model.id(), null, OverviewSection.SUBTYPE_GROUPS.name(), null, null, p + 1, size));
        }
        return new ModelOverview(model.id(), model.modelName(), MetadataTools.text(model.modelDescription()), section.name(), summary.entityCount(),
                summary.relationshipCount(), tree.unclassifiedCount(), areas, areasPaging, groups, groupsPaging);
    }

    // ------------------------------------------------------------------ 엔터티 목록

    /**
     * 엔터티 검색 : 기존 논리 엔터티 목록 조회(이름·설명 검색, 주제 영역 범위 — 기본 하위 포함, 미분류, 정렬 엔터티명 → ID)를 그대로 쓴다.
     * 행마다 소속 영역 경로와 상속 요약을 붙인다. 조회 수는 페이지 크기와 무관하다 (목록 5 + 범위 0~1 + 영역 4 + 계층 1~2 + 그룹 0~3)
     */
    @Transactional(readOnly = true)
    public EntityList searchEntities(Long projectId, EntitySearch request) {
        AccountDto scope = scope(projectId);
        EntitySearch r = request == null ? new EntitySearch(null, null, null, null, null, null, null, null) : request;
        int size = limit(r.limit());
        int p = page(r.page(), size);
        LogicalModelDto.Model model = resolveModel(scope, new ModelRef(r.modelId(), r.modelName()));
        boolean unclassified = Boolean.TRUE.equals(r.unclassifiedOnly());
        // 영역·미분류를 함께 주면 기존 범위 규칙이 400 으로 거절한다 (무시하지 않는다). 다른 모델·프로젝트의 영역은 '없음'
        Page<LogicalModelDto.EntityRow> rows = modelService.selectEntityRows(scope, model.id(), r.keyword(), p, size, null, r.subjectAreaId(), unclassified,
                r.includeChildren());
        Map<Long, LogicalSubjectAreaDto.Area> areas = rows.getContent().stream().anyMatch(row -> row.subjectAreaId() != null)
                ? areaMap(subjectAreaService.selectTree(scope, model.id())) : Map.of();
        LogicalHierarchy hierarchy = subtypeService.hierarchy(model.id());
        Set<Long> groupIds = new LinkedHashSet<>();
        for (LogicalModelDto.EntityRow row : rows.getContent()) {
            LogicalHierarchy.Group parent = hierarchy.parentGroup(row.id());
            if (parent != null) groupIds.add(parent.id());
            hierarchy.groupsOf(row.id()).forEach(g -> groupIds.add(g.id()));
        }
        Map<Long, LogicalSubtypeDto.Group> groups = subtypeService.selectGroups(projectId, model.id(), groupIds).stream()
                .collect(Collectors.toMap(LogicalSubtypeDto.Group::id, Function.identity()));
        List<EntityItem> items = rows.getContent().stream().map(row -> new EntityItem(row.id(), row.entityName(), MetadataTools.text(row.entityDescription()),
                Boolean.TRUE.equals(row.isApproval()), row.subjectAreaId() == null ? null : areaRef(areas.get(row.subjectAreaId())), row.attributeCount(),
                row.relationshipCount(), inheritanceSummary(row.id(), hierarchy, groups))).toList();
        String keyword = r.keyword() == null || r.keyword().isBlank() ? null : r.keyword().trim();
        EntityScope echo = new EntityScope(keyword, r.subjectAreaId(), r.subjectAreaId() == null ? null : !Boolean.FALSE.equals(r.includeChildren()), unclassified);
        return new EntityList(model.id(), model.modelName(), echo, items, Paging.of(p, size, items.size(), rows.getTotalElements()));
    }

    /** 목록의 상속 요약 : 계층과 미리 읽은 그룹만 쓴다 (엔터티마다 조회하지 않는다). 그룹·직접 서브타입은 각 {@value #MAX_SUBTYPES}개까지, 나머지는 상세의 INHERITANCE 로 */
    private static InheritanceSummary inheritanceSummary(Long entityId, LogicalHierarchy h, Map<Long, LogicalSubtypeDto.Group> groups) {
        LogicalHierarchy.Group parent = h.parentGroup(entityId);
        LogicalSubtypeDto.Group parentGroup = parent == null ? null : groups.get(parent.id());
        EntityName supertype = parentGroup == null ? null : new EntityName(parentGroup.supertypeEntityId(), parentGroup.supertypeEntityName());
        List<GroupSummary> own = h.groupsOf(entityId).stream().map(g -> groups.get(g.id())).filter(Objects::nonNull).limit(MAX_SUBTYPES).map(g -> {
            List<EntityName> subtypes = g.subtypes().stream().limit(MAX_SUBTYPES).map(m -> new EntityName(m.entityId(), m.entityName())).toList();
            return new GroupSummary(g.id(), g.groupName(), subtypes, Paging.of(0, MAX_SUBTYPES, subtypes.size(), g.subtypes().size(),
                    new NextCall(DETAIL_TOOL, null, entityId, Section.INHERITANCE.name(), g.id(), null, 1, MAX_SUBTYPES)));
        }).toList();
        return new InheritanceSummary(h.participates(entityId), h.level(entityId), supertype, parentGroup == null ? null : parentGroup.id(),
                parentGroup == null ? null : parentGroup.groupName(), own);
    }

    // ------------------------------------------------------------------ 엔터티 상세

    /**
     * 엔터티 상세 (D35-D16·D17) : section 에 따라 상속 정보(공통 계산 — 유효 속성·유효 식별자·조상·그룹·상속 관계), 직접 관계(속성 짝 판정 포함), 소속 주제 영역.
     * ALL 은 항목마다 첫 묶음(속성·식별자 100, 관계 50, 그룹·직접 서브타입 20)과 나머지를 받는 next 를, 항목 조회는 그 항목만 page·limit 으로 준다.
     * 조회 수는 상속 깊이(최대 3)·속성 수·관계 수와 무관한 상수 범위다
     */
    @Transactional(readOnly = true)
    public EntityDetail entityDetail(Long projectId, EntityRef ref) {
        AccountDto scope = scope(projectId);
        Section section = enumValue(Section.class, ref == null ? null : ref.section(), "section", Section.ALL);
        boolean descriptions = Boolean.TRUE.equals(ref == null ? null : ref.includeAttributeDescriptions());
        Integer pageArg = ref == null ? null : ref.page();
        Integer limitArg = ref == null ? null : ref.limit();
        Long groupArg = ref == null ? null : ref.subtypeGroupId();
        if (section == Section.ALL && (pageArg != null || limitArg != null)) {
            throw invalidArgument("section 이 ALL 이면 page·limit 을 줄 수 없습니다. ATTRIBUTES·IDENTIFIERS·RELATIONS·INHERITANCE 중 하나와 함께 주세요.");
        }
        if (descriptions && section != Section.ALL && section != Section.ATTRIBUTES) {
            throw invalidArgument("includeAttributeDescriptions 는 section 이 ALL 이나 ATTRIBUTES 일 때만 쓸 수 있습니다.");
        }
        if (groupArg != null && section != Section.INHERITANCE) throw invalidArgument("subtypeGroupId 는 section 이 INHERITANCE 일 때만 쓸 수 있습니다.");
        int max = switch (section) {
            case ALL, ATTRIBUTES, IDENTIFIERS -> MAX_ITEMS;
            case RELATIONS -> MAX_RELATIONSHIPS;
            case INHERITANCE -> MAX_SUBTYPES;
        };
        int size = section == Section.ALL ? max : sectionLimit(limitArg, max);
        int p = section == Section.ALL ? 0 : page(pageArg, size);

        Long entityId = resolveEntity(scope, ref);
        LogicalSubtypeDto.Inheritance inh = subtypeService.selectInheritanceView(projectId, entityId);
        Long modelId = entityRepository.findModelId(entityId, projectId).orElseThrow(() -> notFound("엔터티"));
        LogicalModelDto.Model model = modelService.selectModel(scope, modelId);
        boolean all = section == Section.ALL;

        String description = null;
        SubjectAreaRef area = null;
        if (all) {
            description = entityRepository.findByIdAndProjectId(entityId, projectId).map(e -> e.getEntityDescription()).orElse(null);
            Long areaId = subjectAreaService.selectAreaIds(List.of(entityId)).get(entityId);
            area = areaId == null ? null : areaRef(areaMap(subjectAreaService.selectTree(scope, modelId)).get(areaId));
        }

        // 상속 구조
        InheritanceDetail inheritance = null;
        if (all || section == Section.INHERITANCE) inheritance = inheritance(inh, all ? 0 : p, all ? MAX_SUBTYPES : size, all ? null : groupArg);

        // 유효 속성 (속성의 승인 상태 = 정의한 엔터티의 승인 상태)
        List<AttributeItem> attributes = null;
        Paging attributesPaging = null;
        if (all || section == Section.ATTRIBUTES) {
            Map<Long, Boolean> approval = new HashMap<>();
            inh.ancestors().forEach(a -> approval.put(a.entityId(), Boolean.TRUE.equals(a.isApproval())));
            approval.put(inh.entityId(), Boolean.TRUE.equals(inh.isApproval()));
            attributes = slice(inh.attributes(), p, size).stream().map(a -> {
                String text = MetadataTools.text(a.attributeDescription());
                return new AttributeItem(a.attributeId(), a.attributeName(), text != null, descriptions ? text : null, a.definedEntityId(), a.definedEntityName(),
                        a.inherited(), a.depth(), approval.getOrDefault(a.definedEntityId(), false), a.dataType(), a.isRequired(), a.termId(), a.termName(),
                        a.domainName(), a.primary() ? a.keyOrder() : null);
            }).toList();
            attributesPaging = Paging.of(p, size, attributes.size(), inh.attributes().size(),
                    new NextCall(DETAIL_TOOL, null, entityId, Section.ATTRIBUTES.name(), null, descriptions ? true : null, p + 1, size));
        }

        // 유효 식별자 (식별자 목록만 페이지로 — 한 식별자의 구성 속성은 나누지 않는다)
        List<IdentifierItem> identifiers = null;
        Paging identifiersPaging = null;
        if (all || section == Section.IDENTIFIERS) {
            identifiers = slice(inh.identifiers(), p, size).stream().map(LogicalMetadataService::identifier).toList();
            identifiersPaging = Paging.of(p, size, identifiers.size(), inh.identifiers().size(),
                    new NextCall(DETAIL_TOOL, null, entityId, Section.IDENTIFIERS.name(), null, null, p + 1, size));
        }

        // 관계 : 직접(관계 ID 순) → 상속(관계 ID 순)
        List<RelationshipItem> relationships = null;
        Paging relationshipsPaging = null;
        if (all || section == Section.RELATIONS) {
            List<RelationshipItem> every = new ArrayList<>();
            modelService.selectEntityRelationships(scope, entityId).forEach(r -> every.add(relationship(r, false, null, null, null)));
            inh.inheritedRelationships().forEach(ir -> every.add(relationship(ir.relationship(), true, ir.definedEntityId(), ir.definedEntityName(), ir.role())));
            int relationSize = all ? MAX_RELATIONSHIPS : size; // ALL 의 관계는 기존 관계 상한 50 (D35-D18)
            relationships = slice(every, p, relationSize);
            relationshipsPaging = Paging.of(p, relationSize, relationships.size(), every.size(),
                    new NextCall(DETAIL_TOOL, null, entityId, Section.RELATIONS.name(), null, null, p + 1, relationSize));
        }

        return new EntityDetail(inh.entityId(), model.id(), model.modelName(), inh.entityName(), MetadataTools.text(description), Boolean.TRUE.equals(inh.isApproval()),
                section.name(), area, inheritance, attributes == null ? null : descriptions ? "INCLUDED" : "EXCLUDED", attributes, attributesPaging, identifiers,
                identifiersPaging, relationships, relationshipsPaging);
    }

    /**
     * 상속 구조. 그룹 하나(groupId)를 고르면 그 그룹의 직접 서브타입을 page·limit 으로, 아니면 그룹 목록을 page·limit 으로 (각 그룹의 직접 서브타입은 첫 {@value #MAX_SUBTYPES}개 + next).
     * 이 엔터티가 슈퍼타입이 아닌 그룹 ID 는 '없음'
     */
    private InheritanceDetail inheritance(LogicalSubtypeDto.Inheritance inh, int page, int size, Long groupId) {
        boolean participates = inh.level() > 1 || !inh.groups().isEmpty();
        List<AncestorItem> ancestors = inh.ancestors().stream().map(a -> new AncestorItem(a.entityId(), a.entityName(), Boolean.TRUE.equals(a.isApproval()), a.level())).toList();
        LogicalSubtypeDto.Parent p = inh.parent();
        ParentGroup parent = p == null ? null : new ParentGroup(p.groupId(), p.groupName(), p.supertypeEntityId(), p.supertypeEntityName(), p.overlapping(), p.total(),
                p.classificationAttributeId(), p.classificationAttributeName(), p.classificationValue());
        List<GroupDetail> groups;
        Paging groupsPaging = null;
        if (groupId != null) {
            LogicalSubtypeDto.Group g = inh.groups().stream().filter(x -> x.id().equals(groupId)).findFirst().orElseThrow(() -> notFound("서브타입 그룹"));
            groups = List.of(group(inh.entityId(), g, page, size));
        } else {
            groups = slice(inh.groups(), page, size).stream().map(g -> group(inh.entityId(), g, 0, MAX_SUBTYPES)).toList();
            groupsPaging = Paging.of(page, size, groups.size(), inh.groups().size(),
                    new NextCall(DETAIL_TOOL, null, inh.entityId(), Section.INHERITANCE.name(), null, null, page + 1, size));
        }
        return new InheritanceDetail(participates, inh.level(), new EntityName(inh.rootEntityId(), inh.rootEntityName()), ancestors, parent, groups, groupsPaging,
                inh.descendantCount(), inh.rootPrimaryKeyMissing());
    }

    private static GroupDetail group(Long entityId, LogicalSubtypeDto.Group g, int page, int size) {
        List<MemberItem> members = slice(g.subtypes(), page, size).stream()
                .map(m -> new MemberItem(m.entityId(), m.entityName(), Boolean.TRUE.equals(m.isApproval()), m.classificationValue())).toList();
        return new GroupDetail(g.id(), g.groupName(), MetadataTools.text(g.groupDescription()), g.overlapping(), g.total(), g.classificationAttributeId(),
                g.classificationAttributeName(), g.classificationAttributeEntityId(), members,
                Paging.of(page, size, members.size(), g.subtypes().size(), new NextCall(DETAIL_TOOL, null, entityId, Section.INHERITANCE.name(), g.id(), null, page + 1, size)));
    }

    private static IdentifierItem identifier(LogicalSubtypeDto.EffectiveIdentifier i) {
        boolean primary = Boolean.TRUE.equals(i.isPrimary());
        String kind = primary ? (i.inherited() ? "INHERITED_PRIMARY" : "PRIMARY") : (i.inherited() ? "INHERITED_ALTERNATE" : "ALTERNATE");
        List<IdentifierPart> parts = new ArrayList<>();
        for (int k = 0; k < i.attributeIds().size(); k++) parts.add(new IdentifierPart(i.attributeIds().get(k), i.attributeNames().get(k), k + 1));
        return new IdentifierItem(i.identifierId(), i.identifierName(), kind, primary, i.inherited(), i.definedEntityId(), i.definedEntityName(), parts);
    }

    private static RelationshipItem relationship(LogicalModelDto.Relationship r, boolean inherited, Long fromId, String fromName, String role) {
        LogicalModelDto.PairState state = r.attributePairs();
        List<PairItem> pairs = state == null || state.pairs() == null ? null
                : state.pairs().stream().map(p -> new PairItem(p.parentAttributeId(), p.parentAttributeName(), p.childAttributeId(), p.childAttributeName())).toList();
        return new RelationshipItem(r.id(), r.relationshipName(), MetadataTools.text(r.relationshipDescription()), r.sourceEntityId(), r.sourceEntityName(), r.targetEntityId(),
                r.targetEntityName(), Boolean.TRUE.equals(r.isIdentifying()), r.cardinality(), r.sourceMin(), r.sourceMax(), r.targetMin(), r.targetMax(),
                Boolean.TRUE.equals(r.isApproval()), inherited, fromId, fromName, role, state == null ? null : state.status(), pairs);
    }

    // ------------------------------------------------------------------ 대상 해석

    /** 모델 : ID(이 프로젝트의 것) 또는 이름. 둘 다 주면 같은 모델이어야 한다 (한쪽을 고르지 않는다) */
    LogicalModelDto.Model resolveModel(AccountDto scope, ModelRef ref) {
        Long id = ref == null ? null : ref.modelId();
        String name = ref == null ? null : blankToNull(ref.modelName());
        if (id == null && name == null) throw invalidArgument("modelId 나 modelName 이 필요합니다.");
        LogicalModelDto.Model byId = id == null ? null : modelService.selectModel(scope, id);
        if (name == null) return byId;
        LogicalModelDto.Model byName = modelByName(scope, name);
        if (byId != null && !byId.id().equals(byName.id())) throw invalidArgument("modelId 와 modelName 이 서로 다른 논리 모델을 가리킵니다.");
        return byName;
    }

    /** 이름 : 정확히 같은 이름, 없으면 대소문자 무시 (저장 때 대소문자 무시 중복이 막히므로 보통 1건). 2건 이상이면 후보와 함께 거절 */
    private LogicalModelDto.Model modelByName(AccountDto scope, String name) {
        List<LogicalModelDto.Model> all = modelService.selectModels(scope);
        Optional<LogicalModelDto.Model> exact = all.stream().filter(m -> m.modelName().equals(name)).findFirst();
        if (exact.isPresent()) return exact.get();
        List<LogicalModelDto.Model> candidates = all.stream().filter(m -> m.modelName().equalsIgnoreCase(name)).toList();
        if (candidates.isEmpty()) throw notFound("논리 모델");
        if (candidates.size() > 1) {
            throw invalidArgument("같은 이름의 논리 모델이 여러 개입니다. modelId 로 지정하세요 : {0}",
                    candidates.stream().map(m -> m.modelName() + "(" + m.id() + ")").collect(Collectors.joining(", ")));
        }
        return candidates.getFirst();
    }

    /** 엔터티 : ID(이 프로젝트의 것) 또는 모델 + 이름. 함께 준 모델·이름은 그 엔터티와 같아야 한다 */
    Long resolveEntity(AccountDto scope, EntityRef ref) {
        Long id = ref == null ? null : ref.entityId();
        String name = ref == null ? null : blankToNull(ref.entityName());
        boolean modelGiven = ref != null && (ref.modelId() != null || blankToNull(ref.modelName()) != null);
        if (id == null && name == null) throw invalidArgument("entityId 나 entityName 이 필요합니다.");
        if (id == null && !modelGiven) throw invalidArgument("entityName 으로 찾을 때는 modelId 나 modelName 이 필요합니다.");
        Long entityModel = id == null ? null : entityRepository.findModelId(id, scope.getProjectId()).orElseThrow(() -> notFound("엔터티"));
        LogicalModelDto.Model model = modelGiven ? resolveModel(scope, new ModelRef(ref.modelId(), ref.modelName())) : null;
        if (entityModel != null && model != null && !entityModel.equals(model.id())) throw invalidArgument("엔터티가 지정한 논리 모델에 없습니다.");
        if (name == null) return id;
        Long byName = entityByName(model == null ? entityModel : model.id(), name);
        if (id != null && !id.equals(byName)) throw invalidArgument("entityId 와 entityName 이 서로 다른 엔터티를 가리킵니다.");
        return byName;
    }

    private Long entityByName(Long modelId, String name) {
        List<Object[]> rows = entityRepository.findByNameIgnoreCase(modelId, name);
        for (Object[] row : rows) if (name.equals(row[1])) return (Long) row[0];
        if (rows.isEmpty()) throw notFound("엔터티");
        if (rows.size() > 1) {
            throw invalidArgument("같은 이름의 엔터티가 여러 개입니다. entityId 로 지정하세요 : {0}",
                    rows.stream().map(row -> row[1] + "(" + row[0] + ")").collect(Collectors.joining(", ")));
        }
        return (Long) rows.getFirst()[0];
    }

    // ------------------------------------------------------------------ 보조

    /** 기존 서비스에 넘길 조회 범위 : 프로젝트만 (계정·역할 없음 — 화면 권한 계산을 하는 메서드는 부르지 않는다) */
    private static AccountDto scope(Long projectId) {
        if (projectId == null) throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
        return AccountDto.builder().projectId(projectId).build();
    }

    private static Map<Long, LogicalSubjectAreaDto.Area> areaMap(LogicalSubjectAreaDto.Tree tree) {
        Map<Long, LogicalSubjectAreaDto.Area> result = new HashMap<>();
        tree.areas().forEach(a -> result.put(a.id(), a));
        return result;
    }

    private static SubjectAreaRef areaRef(LogicalSubjectAreaDto.Area area) {
        return area == null ? null : new SubjectAreaRef(area.id(), area.areaName(), area.path());
    }

    /** 0 이상, 첫 항목 위치(page × limit)가 int 범위 안 (D35-3a 8.3 — 산술 넘침 방지) */
    private static int page(Integer page, int size) {
        if (page == null) return 0;
        if (page < 0) throw invalidArgument("page 는 0 이상이어야 합니다.");
        if ((long) page * size > Integer.MAX_VALUE) throw invalidArgument("page 가 너무 큽니다.");
        return page;
    }

    /** 목록 Tool 의 limit : 기존 AI-4 규칙 (없으면 10, 1~20 밖이면 가까운 값) */
    private static int limit(Integer limit) {
        if (limit == null) return MetadataTools.DEFAULT_LIMIT;
        return Math.max(1, Math.min(MetadataTools.MAX_LIMIT, limit));
    }

    /** 항목 조회의 limit : 없으면 20, 1~그 항목의 상한 밖이면 가까운 값 (기존 규칙과 같은 방식) */
    private static int sectionLimit(Integer limit, int max) {
        if (limit == null) return Math.min(SECTION_DEFAULT_LIMIT, max);
        return Math.max(1, Math.min(max, limit));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalidArgument("{0} 는 {1} 중 하나입니다.", label, Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    private static <T> List<T> slice(List<T> all, int page, int size) {
        long from = (long) page * size;
        if (from >= all.size()) return List.of();
        return all.subList((int) from, (int) Math.min(all.size(), from + size));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CustomException invalidArgument(String message, Object... args) {
        return new CustomException(ErrorCode.AI_QUERY_INVALID, message, args);
    }

    private static CustomException notFound(String label) {
        return new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get(label));
    }
}
