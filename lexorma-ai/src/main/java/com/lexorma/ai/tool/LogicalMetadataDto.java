package com.lexorma.ai.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 논리 모델 AI·MCP 공통 조회 결과 (D-35 D35-2·D35-3a, docs/completed/ai-logical-model-query-d35.md). {@link LogicalMetadataService} 가 만든다.
 * <ul>
 *   <li>화면 DTO 를 그대로 보내지 않는다 : editable·수정 일시·정렬 순서·ERD 좌표·세션·계정 정보가 없다. 비밀번호·토큰·접속 정보는 다루지 않는다</li>
 *   <li>근거 검증에 쓸 수 있게 대상마다 DB ID 를 그대로 준다 (모델·주제 영역·엔터티·속성·식별자·관계·서브타입 그룹). AI 전용 식별자는 없다</li>
 *   <li>없는 값은 칸을 생략하고(NON_NULL) 목록은 조회한 경우에만 있다 — <b>칸 없음 = 이번 요청에서 조회하지 않음, 빈 목록 = 조회했는데 없음</b> (D35-D21)</li>
 *   <li>목록마다 {@link Paging} : 전체 수·반환 수·{@code complete}(이 응답에 전부 있음)·{@code hasMore}·나머지를 받는 정확한 다음 호출 {@code next} —
 *       일부를 전체처럼 보이지 않는다</li>
 *   <li>승인 상태는 객체마다 (속성은 정의한 엔터티의 승인 상태 — 상속 속성에 자식 엔터티의 승인 상태를 쓰지 않는다, D35-D13)</li>
 * </ul>
 */
public final class LogicalMetadataDto {

    private LogicalMetadataDto() {
    }

    // ------------------------------------------------------------------ 입력

    /** 모델 지정 : modelId(우선) 또는 modelName (정확한 이름, 없으면 대소문자 무시). 둘 다 주면 같은 모델이어야 한다 */
    public record ModelRef(Long modelId, String modelName) {
    }

    /**
     * 모델 개요 (D35-3a) : section = ALL(기본 — 수·주제 영역·서브타입 그룹, 목록마다 첫 묶음) · SUBJECT_AREAS · SUBTYPE_GROUPS (그 목록만 page·limit 으로).
     * ALL 에는 page·limit 을 주지 않는다
     */
    public record OverviewRequest(Long modelId, String modelName, String section, Integer page, Integer limit) {
        public OverviewRequest(Long modelId, String modelName) {
            this(modelId, modelName, null, null, null);
        }
    }

    /**
     * 엔터티 검색 조건. subjectAreaId 와 unclassifiedOnly=true 는 함께 쓸 수 없다 (400). includeChildren 은 영역을 줄 때만 의미가 있고 기본 true (기존 화면 규칙).
     * page 는 0부터, limit 1~20 (기본 10, 범위 밖은 기존 AI-4 규칙대로 가장 가까운 값)
     */
    public record EntitySearch(Long modelId, String modelName, String keyword, Long subjectAreaId, Boolean includeChildren, Boolean unclassifiedOnly, Integer page,
                               Integer limit) {
    }

    /**
     * 엔터티 지정 : entityId(우선) 또는 모델(modelId·modelName) + entityName. 함께 준 값은 모두 같은 대상을 가리켜야 한다.
     * section (D35-D17) : ALL(기본) · ATTRIBUTES · IDENTIFIERS · RELATIONS · INHERITANCE. page·limit 은 그 항목 목록에만 (ALL 은 받지 않는다).
     * includeAttributeDescriptions (D35-D16) : 유효 속성의 설명 포함 (기본 false — ALL·ATTRIBUTES 에서만).
     * subtypeGroupId : INHERITANCE 에서 이 엔터티가 슈퍼타입인 그룹 하나의 직접 서브타입을 page·limit 으로 (없으면 page·limit 은 그룹 목록에)
     */
    public record EntityRef(Long entityId, Long modelId, String modelName, String entityName, String section, Boolean includeAttributeDescriptions, Long subtypeGroupId,
                            Integer page, Integer limit) {
        public EntityRef(Long entityId, Long modelId, String modelName, String entityName) {
            this(entityId, modelId, modelName, entityName, null, null, null, null, null);
        }
    }

    // ------------------------------------------------------------------ 공통 조각

    /**
     * 나머지를 받는 정확한 다음 호출 (D35-3a 8.2) : 이 인자로 tool 을 부르면 이 목록의 바로 다음 항목부터 받는다 (같은 limit — 항목을 건너뛰지 않는다).
     * 없는 칸은 넣지 않는다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NextCall(String tool, Long modelId, Long entityId, String section, Long subtypeGroupId, Boolean includeAttributeDescriptions, Integer page,
                           Integer limit) {
    }

    /**
     * 목록 범위. totalCount = 조건에 맞는 전체 수, complete = 이 응답에 전체가 있음, hasMore·nextPage = 다음 페이지 (같은 조건·같은 limit),
     * next = 다른 인자로 불러야 할 때의 정확한 다음 호출 (상세·개요의 목록). 페이지 사이의 변경까지 고정한 스냅숏은 아니다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Paging(int page, int limit, int returnedCount, long totalCount, boolean hasMore, Integer nextPage, boolean complete, NextCall next) {
        public static Paging of(int page, int limit, int returned, long total, NextCall next) {
            boolean more = (long) page * limit + returned < total;
            return new Paging(page, limit, returned, total, more, more ? page + 1 : null, page == 0 && returned == total, more ? next : null);
        }

        public static Paging of(int page, int limit, int returned, long total) {
            return of(page, limit, returned, total, null);
        }
    }

    /** 다른 엔터티 가리키기 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntityName(Long entityId, String entityName) {
    }

    /** 소속 주제 영역. path = 최상위 → 이 영역 이름. 미분류 엔터티는 이 칸이 없다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubjectAreaRef(Long subjectAreaId, String name, List<String> path) {
    }

    // ------------------------------------------------------------------ 모델 목록·개요

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelItem(Long modelId, String modelName, String description, long entityCount, long relationshipCount, long subjectAreaCount, long subtypeGroupCount) {
    }

    /** 프로젝트의 논리 모델 (이름순, 같은 이름은 ID 순) */
    public record ModelList(List<ModelItem> models, Paging paging) {
    }

    /** 주제 영역 하나 (속성·엔터티 목록은 넣지 않는다 — D35-D02). depth : 최상위 = 1. 페이지로 받아도 parentId·path 로 계층을 안다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubjectAreaItem(Long subjectAreaId, String name, String description, Long parentId, int depth, List<String> path, long directEntityCount,
                                  long totalEntityCount) {
    }

    /** 서브타입 그룹 요약. overlapping : true = 한 인스턴스가 여러 서브타입일 수 있음, total : true = 모든 인스턴스가 어느 서브타입 (저장된 값 그대로) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubtypeGroupItem(Long groupId, String name, Long supertypeEntityId, String supertypeEntityName, long subtypeCount, Boolean overlapping, Boolean total,
                                   Long classificationAttributeId, String classificationAttributeName) {
    }

    /**
     * 모델 개요 : 수 + 주제 영역(트리 순서) + 서브타입 그룹 (엔터티 속성·식별자 없음 — D35-D02·D05). section 이 SUBJECT_AREAS·SUBTYPE_GROUPS 면 그 목록만 있다.
     * 목록의 전체 수·범위는 …Paging
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelOverview(Long modelId, String modelName, String description, String section, long entityCount, long relationshipCount,
                                long unclassifiedEntityCount, List<SubjectAreaItem> subjectAreas, Paging subjectAreasPaging,
                                List<SubtypeGroupItem> subtypeGroups, Paging subtypeGroupsPaging) {
    }

    // ------------------------------------------------------------------ 엔터티 목록

    /** 슈퍼타입으로서의 그룹 요약 : 직접 서브타입 (subtypesPaging.next 로 나머지) */
    public record GroupSummary(Long groupId, String name, List<EntityName> subtypes, Paging subtypesPaging) {
    }

    /**
     * 목록의 상속 요약 (D35-D04) : 상속 참여 여부, 단계(루트 = 1), 직접 슈퍼타입과 그 그룹, 슈퍼타입으로서의 그룹. 유효 속성·식별자는 없다 (상세에서만 — D35-D05)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InheritanceSummary(boolean participates, int level, EntityName supertype, Long parentGroupId, String parentGroupName, List<GroupSummary> subtypeGroups) {
    }

    /** 엔터티 한 행. directAttributeCount : 이 엔터티에 직접 정의한 속성 수 (상속 속성 제외), relationshipCount : 이 엔터티가 출발·도착인 관계 수 (상속 관계 제외) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntityItem(Long entityId, String entityName, String description, boolean approved, SubjectAreaRef subjectArea, long directAttributeCount,
                             long relationshipCount, InheritanceSummary inheritance) {
    }

    /** 검색 조건을 되돌려 준다 (AI 가 결과의 범위를 오해하지 않게) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntityScope(String keyword, Long subjectAreaId, Boolean includeChildren, boolean unclassifiedOnly) {
    }

    /** 엔터티 목록 (정렬 : 엔터티명 → ID, 기존 논리 엔터티 목록 기본 정렬). 다음 페이지 = 같은 조건 + page=nextPage */
    public record EntityList(Long modelId, String modelName, EntityScope scope, List<EntityItem> entities, Paging paging) {
    }

    // ------------------------------------------------------------------ 엔터티 상세

    /** 조상 한 단계 (level : 루트 = 1) */
    public record AncestorItem(Long entityId, String entityName, boolean approved, int level) {
    }

    /** 이 엔터티가 서브타입으로 속한 그룹 (classificationValue : 이 엔터티의 분류 값) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParentGroup(Long groupId, String name, Long supertypeEntityId, String supertypeEntityName, Boolean overlapping, Boolean total,
                              Long classificationAttributeId, String classificationAttributeName, String classificationValue) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemberItem(Long entityId, String entityName, boolean approved, String classificationValue) {
    }

    /** 이 엔터티가 슈퍼타입인 그룹과 직접 서브타입. classificationAttributeEntityId : 분류 속성을 정의한 엔터티 (이 엔터티 또는 조상) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupDetail(Long groupId, String name, String description, Boolean overlapping, Boolean total, Long classificationAttributeId,
                              String classificationAttributeName, Long classificationAttributeEntityId, List<MemberItem> subtypes, Paging subtypesPaging) {
    }

    /**
     * 상속 구조 (M-6 의미 그대로). ancestors = 루트부터 직접 슈퍼타입까지, root = 루트 슈퍼타입 (상속이 없으면 자기 자신), descendantCount = 모든 하위 서브타입 수.
     * rootPrimaryKeyMissing : 서브타입인데 루트에 주 식별자가 없다. subtypeGroupsPaging 은 그룹 하나만 고른 조회(subtypeGroupId)면 없다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InheritanceDetail(boolean participates, int level, EntityName root, List<AncestorItem> ancestors, ParentGroup parentGroup,
                                    List<GroupDetail> subtypeGroups, Paging subtypeGroupsPaging, int descendantCount, boolean rootPrimaryKeyMissing) {
    }

    /**
     * 유효 속성 하나 (직접·상속을 한 목록에 — 같은 속성을 두 번 주지 않는다). attributeId 는 원본 속성 ID, definedEntity* 는 정의한 엔터티,
     * depth 0 = 직접 정의 · 1 = 직접 슈퍼타입 · 2 = 그 위. approved = 정의한 엔터티의 승인 상태. primaryKeyOrder = 유효 주 식별자(루트의 주 식별자) 안의 순서 (아니면 생략).
     * hasDescription = 설명이 등록돼 있는지, description = 설명 (includeAttributeDescriptions 일 때만 — D35-D16. 등록 안 됨과 요청 안 함을 hasDescription 으로 구분).
     * 데이터 유형은 속성에 적은 값, 길이·정밀도는 논리 속성에 없다 (표준 용어의 도메인 — termId·domainName)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttributeItem(Long attributeId, String name, boolean hasDescription, String description, Long definedEntityId, String definedEntityName, boolean inherited,
                                int depth, boolean approved, String dataType, Boolean required, Long termId, String termName, String domainName, Integer primaryKeyOrder) {
    }

    /** 식별자 구성 속성 (order : 1부터, 식별자 순서 그대로 — 식별자는 페이지로 나눠도 구성 속성은 나누지 않는다) */
    public record IdentifierPart(Long attributeId, String name, int order) {
    }

    /**
     * 유효 식별자. kind : PRIMARY(직접 정의 주 식별자) · INHERITED_PRIMARY(루트의 주 식별자 상속) · ALTERNATE(자체 보조 식별자) · INHERITED_ALTERNATE(조상의 보조 식별자).
     * 상속 주 식별자를 이 엔터티의 직접 식별자로 표시하지 않는다 (definedEntity* = 정의한 엔터티)
     */
    public record IdentifierItem(Long identifierId, String name, String kind, boolean primary, boolean inherited, Long definedEntityId, String definedEntityName,
                                 List<IdentifierPart> attributes) {
    }

    /** 관계 속성 짝 하나 (부모 = 출발 엔터티 속성 → 자식 = 도착 엔터티 속성, 순서대로) */
    public record PairItem(Long parentAttributeId, String parentAttributeName, Long childAttributeId, String childAttributeName) {
    }

    /**
     * 논리 관계. source = 부모(출발), target = 자식(도착). cardinality : 저장된 최대값의 요약 (1:1·1:N·N:1·N:M), min·max 는 저장된 값 그대로 (max 생략 = N).
     * inherited = 조상에 정의된 관계 (복제하지 않는다 — inheritedFromEntity* 가 정의한 조상, role = 그 조상이 SOURCE·TARGET·SELF 중 어느 쪽인지).
     * pairStatus·attributePairs 는 이 엔터티의 직접 관계에만 (상속 관계의 짝은 정의한 조상의 상세에서)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelationshipItem(Long relationshipId, String name, String description, Long sourceEntityId, String sourceEntityName, Long targetEntityId,
                                   String targetEntityName, boolean identifying, String cardinality, Integer sourceMin, Integer sourceMax, Integer targetMin,
                                   Integer targetMax, boolean approved, boolean inherited, Long inheritedFromEntityId, String inheritedFromEntityName, String role,
                                   String pairStatus, List<PairItem> attributePairs) {
    }

    /**
     * 엔터티 상세 (D35-D05·D06·D16·D17). section = 이번에 조회한 항목 (ALL 이면 모두). 조회하지 않은 항목의 칸은 없다.
     * attributeDescriptions = INCLUDED·EXCLUDED (속성을 조회했을 때만). 목록마다 …Paging (전체 수·complete·next)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntityDetail(Long entityId, Long modelId, String modelName, String entityName, String description, boolean approved, String section,
                               SubjectAreaRef subjectArea, InheritanceDetail inheritance, String attributeDescriptions, List<AttributeItem> attributes,
                               Paging attributesPaging, List<IdentifierItem> identifiers, Paging identifiersPaging, List<RelationshipItem> relationships,
                               Paging relationshipsPaging) {
    }
}
