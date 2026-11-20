package com.lexorma.data.logical.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 논리 모델 슈퍼타입·서브타입 (M-6 후속 5차, docs/completed/logical-subtype.md).
 * 업무 용어 : overlapping 참 = Overlapping(중복 가능)·거짓 = Disjoint(배타), total 참 = Total(완전)·거짓 = Partial(부분). 둘 다 기본값이 없다 (저장 요청에 반드시 있다).
 * 분류 속성(classificationAttribute)은 슈퍼타입의 유효 속성(직접 또는 조상에서 상속)의 원본 속성이고, 분류 값은 실행하지 않는 메타데이터다.
 */
public final class LogicalSubtypeDto {

    private LogicalSubtypeDto() {
    }

    /** 서브타입 하나 (요청 순서 = 그룹 안 순서) */
    public record MemberInput(Long entityId, String classificationValue) {
    }

    /**
     * 그룹 등록·수정 (구성원 전체 교체, 한 트랜잭션). 등록은 modelId, 수정은 id + modifiedAt(DEC-08 기준값 — 없으면 400 MODEL_BASE_REQUIRED).
     * 구성원은 2개 이상. 수정 요청의 modelId 는 보지 않는다 (그룹의 모델은 바뀌지 않는다)
     */
    public record Save(Long id, Long modelId, Long supertypeEntityId, String groupName, String groupDescription, Boolean overlapping, Boolean total,
                       Long classificationAttributeId, List<MemberInput> subtypes, LocalDateTime modifiedAt) {
    }

    public record Member(Long entityId, String entityName, Boolean isApproval, String classificationValue, Integer sortOrder) {
    }

    /** 그룹 상세. classificationAttributeEntity* : 분류 속성을 실제로 정의한 엔터티 (슈퍼타입 또는 그 조상) */
    public record Group(Long id, Long modelId, Long supertypeEntityId, String supertypeEntityName, Boolean supertypeApproval, String groupName, String groupDescription,
                        Boolean overlapping, Boolean total, Long classificationAttributeId, String classificationAttributeName, Long classificationAttributeEntityId,
                        String classificationAttributeEntityName, Integer sortOrder, List<Member> subtypes, LocalDateTime modifiedAt) {
    }

    /** 그룹 목록 1행 (서버 페이징). subtypeCount : 그룹의 전체 구성원 수 */
    public record GroupRow(Long id, String groupName, Long supertypeEntityId, String supertypeEntityName, long subtypeCount, Boolean overlapping, Boolean total,
                           Long classificationAttributeId, String classificationAttributeName, LocalDateTime modifiedAt) {
    }

    /** 조상 한 단계 (level : 루트 = 1) */
    public record Ancestor(Long entityId, String entityName, Boolean isApproval, int level) {
    }

    /** 엔터티가 서브타입으로 속한 그룹 */
    public record Parent(Long groupId, String groupName, Long supertypeEntityId, String supertypeEntityName, Boolean overlapping, Boolean total,
                         Long classificationAttributeId, String classificationAttributeName, String classificationValue) {
    }

    /**
     * 유효 속성 1개. attributeId 는 원본 속성 ID (상속 속성도 복제하지 않는다), contextEntityId 는 조회한 엔터티, definedEntity* 는 실제로 정의한 엔터티.
     * depth : 0 = 직접 정의, 1 = 직접 슈퍼타입, 2 = 그 위. editable : 직접 정의이고 지금 사용자가 그 엔터티에서 고칠 수 있는지 (서버가 저장 때 다시 검사한다).
     * primary·keyOrder : 유효 주 식별자(루트의 주 식별자)에 속하는지·그 순서
     */
    public record EffectiveAttribute(Long attributeId, Long contextEntityId, Long definedEntityId, String definedEntityName, boolean inherited, int depth, boolean editable,
                                     String attributeName, String attributeDescription, String dataType, Boolean isRequired, Integer sortOrder,
                                     Long termId, String termName, String domainName, boolean primary, Integer keyOrder, LocalDateTime modifiedAt) {
    }

    /** 유효 식별자 (상속 주 식별자·상속 보조 식별자·직접 보조 식별자). attributeIds 는 원본 속성 ID (식별자 순서) */
    public record EffectiveIdentifier(Long identifierId, String identifierName, Boolean isPrimary, Long definedEntityId, String definedEntityName, boolean inherited,
                                      List<Long> attributeIds, List<String> attributeNames) {
    }

    /** 조상에 정의된 일반 관계 (레코드를 복제하지 않는다). role : 정의 엔터티가 관계의 SOURCE(부모)·TARGET(자식)·SELF 중 어느 쪽인지 */
    public record InheritedRelationship(Long definedEntityId, String definedEntityName, String role, LogicalModelDto.Relationship relationship) {
    }

    /**
     * 엔터티의 상속 정보 한 번에 : 위치(단계·루트·조상·직접 그룹), 슈퍼타입으로서의 그룹(구성원 포함), 유효 속성·식별자, 상속 관계.
     * rootPrimaryKeyMissing : 서브타입인데 루트에 주 식별자가 없다 (설계 중 허용 — 승인·속성 짝·물리 생성이 제한된다)
     */
    public record Inheritance(Long entityId, String entityName, Boolean isApproval, LocalDateTime modifiedAt, int level, Long rootEntityId, String rootEntityName,
                              Parent parent, List<Ancestor> ancestors, List<Group> groups, int descendantCount, boolean rootPrimaryKeyMissing,
                              List<EffectiveAttribute> attributes, List<EffectiveIdentifier> identifiers, List<InheritedRelationship> inheritedRelationships) {
    }

    public record EffectiveAttributes(Long entityId, List<EffectiveAttribute> attributes) {
    }
}
