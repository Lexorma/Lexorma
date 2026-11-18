package com.lexorma.data.logical.domain.dto;

import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.logical.domain.entity.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 논리 모델 조회·저장 DTO. 저장 요청은 id 가 없으면 등록, 있으면 수정이다 (이름이 …Name 인 조회 전용 값은 보내지 않는다) */
public final class LogicalModelDto {

    private LogicalModelDto() {
    }

    /** modifiedAt : 응답은 지금 수정 일시, 수정 요청은 화면이 읽은 값 (DEC-08 기준값) */
    public record Model(Long id, String modelName, String modelDescription, java.time.LocalDateTime modifiedAt) {
        public Model(Long id, String modelName, String modelDescription) {
            this(id, modelName, modelDescription, null);
        }

        public static Model fromEntity(LogicalModel m) {
            return new Model(m.getId(), m.getModelName(), m.getModelDescription(), m.modifiedAt());
        }
    }

    /** attributes·identifiers 는 상세 조회에서만 채운다 (목록은 null) */
    /** modifiedAt : 응답은 지금 수정 일시, 수정 요청은 화면이 읽은 값 (DEC-08 기준값 — 없으면 검사하지 않는다) */
    public record Entity(Long id, Long modelId, String entityName, String entityDescription, Boolean isApproval,
                         List<Attribute> attributes, List<Identifier> identifiers, java.time.LocalDateTime modifiedAt) {
        public Entity(Long id, Long modelId, String entityName, String entityDescription, Boolean isApproval,
                      List<Attribute> attributes, List<Identifier> identifiers) {
            this(id, modelId, entityName, entityDescription, isApproval, attributes, identifiers, null);
        }

        public static Entity summary(LogicalEntity e) {
            return new Entity(e.getId(), e.getModel().getId(), e.getEntityName(), e.getEntityDescription(), e.getIsApproval(), null, null, e.modifiedAt());
        }

        public static Entity detail(LogicalEntity e) {
            return new Entity(e.getId(), e.getModel().getId(), e.getEntityName(), e.getEntityDescription(), e.getIsApproval(),
                    e.getAttributes().stream().map(Attribute::fromEntity).toList(),
                    e.getIdentifiers().stream().map(Identifier::fromEntity).toList(), e.modifiedAt());
        }
    }

    /** termName·domainName·domainDataType 은 조회 전용 : 표준 도메인은 연결한 표준 용어의 도메인이다 */
    public record Attribute(Long id, Long entityId, String attributeName, String attributeDescription, String dataType, Boolean isRequired,
                            Integer sortOrder, Long termId, String termName, String domainName, String domainDataType, java.time.LocalDateTime modifiedAt) {
        public Attribute(Long id, Long entityId, String attributeName, String attributeDescription, String dataType, Boolean isRequired,
                         Integer sortOrder, Long termId, String termName, String domainName, String domainDataType) {
            this(id, entityId, attributeName, attributeDescription, dataType, isRequired, sortOrder, termId, termName, domainName, domainDataType, null);
        }

        public static Attribute fromEntity(LogicalAttribute a) {
            StandardTerm term = a.getStandardTerm();
            var domain = term == null ? null : term.getStandardDomain();
            return new Attribute(a.getId(), a.getEntity().getId(), a.getAttributeName(), a.getAttributeDescription(), a.getDataType(),
                    a.getIsRequired(), a.getSortOrder(), term == null ? null : term.getId(), term == null ? null : term.getCommonStandardTermName(),
                    domain == null ? null : domain.getCommonStandardDomainName(), domain == null ? null : domain.getDataType(), a.modifiedAt());
        }
    }

    /** attributeIds : 식별자를 이루는 속성 (순서대로, 복합 가능). attributeNames 는 조회 전용 */
    public record Identifier(Long id, Long entityId, String identifierName, Boolean isPrimary, List<Long> attributeIds, List<String> attributeNames,
                             java.time.LocalDateTime modifiedAt) {
        public Identifier(Long id, Long entityId, String identifierName, Boolean isPrimary, List<Long> attributeIds, List<String> attributeNames) {
            this(id, entityId, identifierName, isPrimary, attributeIds, attributeNames, null);
        }

        public static Identifier fromEntity(LogicalIdentifier i) {
            return new Identifier(i.getId(), i.getEntity().getId(), i.getIdentifierName(), i.getIsPrimary(),
                    i.getAttributes().stream().map(a -> a.getAttribute().getId()).toList(),
                    i.getAttributes().stream().map(a -> a.getAttribute().getAttributeName()).toList(), i.modifiedAt());
        }
    }

    /**
     * 카디널리티 : 원본 쪽(sourceMin..sourceMax) = 대상 1건에 연결되는 원본 건수, 대상 쪽 = 원본 1건에 연결되는 대상 건수.
     * 최소 0 = 선택 / 1 이상 = 필수, 최대 null = 다(N). 1:N = 원본 max 1·대상 max null, N:M = 양쪽 max null.
     * cardinality 는 조회 전용 요약 ("1:1" / "1:N" / "N:1" / "N:M")
     */
    public record Relationship(Long id, Long modelId, String relationshipName, String relationshipDescription,
                               Long sourceEntityId, String sourceEntityName, Long targetEntityId, String targetEntityName,
                               Boolean isIdentifying, Integer sourceMin, Integer sourceMax, Integer targetMin, Integer targetMax,
                               String cardinality, Boolean isApproval, java.time.LocalDateTime modifiedAt, PairState attributePairs) {
        public Relationship(Long id, Long modelId, String relationshipName, String relationshipDescription,
                            Long sourceEntityId, String sourceEntityName, Long targetEntityId, String targetEntityName,
                            Boolean isIdentifying, Integer sourceMin, Integer sourceMax, Integer targetMin, Integer targetMax,
                            String cardinality, Boolean isApproval) {
            this(id, modelId, relationshipName, relationshipDescription, sourceEntityId, sourceEntityName, targetEntityId, targetEntityName,
                    isIdentifying, sourceMin, sourceMax, targetMin, targetMax, cardinality, isApproval, null, null);
        }

        public Relationship(Long id, Long modelId, String relationshipName, String relationshipDescription,
                            Long sourceEntityId, String sourceEntityName, Long targetEntityId, String targetEntityName,
                            Boolean isIdentifying, Integer sourceMin, Integer sourceMax, Integer targetMin, Integer targetMax,
                            String cardinality, Boolean isApproval, java.time.LocalDateTime modifiedAt) {
            this(id, modelId, relationshipName, relationshipDescription, sourceEntityId, sourceEntityName, targetEntityId, targetEntityName,
                    isIdentifying, sourceMin, sourceMax, targetMin, targetMax, cardinality, isApproval, modifiedAt, null);
        }

        /** 속성 짝 없이 (저장 응답 등 — 속성 짝은 따로 저장·조회한다) */
        public static Relationship fromEntity(LogicalRelationship r) {
            return fromEntity(r, null);
        }

        public static Relationship fromEntity(LogicalRelationship r, PairState attributePairs) {
            return new Relationship(r.getId(), r.getModel().getId(), r.getRelationshipName(), r.getRelationshipDescription(),
                    r.getSourceEntity().getId(), r.getSourceEntity().getEntityName(), r.getTargetEntity().getId(), r.getTargetEntity().getEntityName(),
                    r.getIsIdentifying(), r.getSourceMin(), r.getSourceMax(), r.getTargetMin(), r.getTargetMax(),
                    cardinality(r.getSourceMax(), r.getTargetMax()), r.getIsApproval(), r.modifiedAt(), attributePairs);
        }

        private static String cardinality(Integer sourceMax, Integer targetMax) {
            boolean sourceOne = sourceMax != null && sourceMax == 1;
            boolean targetOne = targetMax != null && targetMax == 1;
            return sourceOne ? (targetOne ? "1:1" : "1:N") : (targetOne ? "N:1" : "N:M");
        }
    }

    // ------------------------------------------------------------------ 관계 속성 짝 (M-7 보강 3차, P1)

    /** 부모(원본) 속성 → 자식(대상) 속성, 순서 */
    public record AttributePair(Long parentAttributeId, String parentAttributeName, Long childAttributeId, String childAttributeName, Integer sortOrder) {
    }

    /**
     * 관계의 속성 짝과 판정 ({@code LogicalRelationshipPairs}). status : NONE · PARENT_KEY_MISSING · PARENT_KEY_MISMATCH · PARTIAL · COMPLETE,
     * warnings : ORDER_DIFFERS · SELF_KEY_REUSED · IDENTIFYING_CHILD_NOT_KEY · REQUIRED_CHILD_NULLABLE, parentKeySize : 부모 주 식별자 속성 수
     */
    public record PairState(String status, List<String> warnings, List<AttributePair> pairs, int parentKeySize) {
    }

    /** 짝 하나의 입력 (순서는 목록 순서) */
    public record PairInput(Long parentAttributeId, Long childAttributeId) {
    }

    /** 관계의 속성 짝 전체 교체. modifiedAt : 화면이 읽은 관계의 수정 일시 (DEC-08 기준값, 필수). pairs 가 비면 짝을 모두 지운다 */
    public record PairSave(java.time.LocalDateTime modifiedAt, List<PairInput> pairs) {
    }

    /** 편집 화면용 속성 후보. primary : 그 엔터티 주 식별자 속성, keyOrder : 주 식별자 안의 순서 (아니면 null) */
    public record PairCandidate(Long attributeId, String attributeName, boolean primary, Integer keyOrder, Boolean isRequired) {
    }

    /** 관계 속성 짝 조회·저장 응답 : 부모 후보 = 부모 주 식별자 속성(순서대로), 자식 후보 = 자식 엔터티의 모든 속성 */
    public record RelationshipPairs(Long relationshipId, java.time.LocalDateTime modifiedAt, Long parentEntityId, String parentEntityName, Long childEntityId,
                                    String childEntityName, Boolean isIdentifying, PairState state, List<PairCandidate> parentCandidates,
                                    List<PairCandidate> childCandidates) {
    }

    // ------------------------------------------------------------------ 논리 ERD

    /** identifier : 어느 식별자에든 속함, primaryIdentifier : 주 식별자에 속함 */
    public record GraphAttribute(Long id, String name, String dataType, Boolean isRequired, Integer sortOrder,
                                 Long termId, String termName, String domainName, boolean identifier, boolean primaryIdentifier) {
    }

    public record GraphEntity(Long id, String name, String description, Boolean isApproval, List<GraphAttribute> attributes) {
        public static GraphEntity of(LogicalEntity e, Set<Long> identifierAttributes, Set<Long> primaryAttributes) {
            return new GraphEntity(e.getId(), e.getEntityName(), e.getEntityDescription(), e.getIsApproval(),
                    e.getAttributes().stream().map(a -> {
                        Attribute dto = Attribute.fromEntity(a);
                        return new GraphAttribute(dto.id(), dto.attributeName(), dto.dataType(), dto.isRequired(), dto.sortOrder(),
                                dto.termId(), dto.termName(), dto.domainName(), identifierAttributes.contains(a.getId()), primaryAttributes.contains(a.getId()));
                    }).toList());
        }
    }

    public record Point(double x, double y) {
    }

    public record Viewport(double x, double y, double zoom) {
    }

    /**
     * 내 논리 ERD 배치 (모델별). version 은 저장한 적 없으면 null (viewport 도 null) - 저장 요청에 조회한 version 을 그대로 보낸다.
     * nodes : 엔터티 ID → 좌표. 저장 요청은 화면의 엔터티만 보내도 된다 (기존 저장 좌표와 합친다).
     */
    public record Layout(Long version, Map<Long, Point> nodes, Viewport viewport) {
    }

    /** 논리 모델 1개의 엔터티(속성 포함)와 관계. 물리 ERD(ErdDto.Graph)와 따로 조회한다 */
    public record Graph(Long modelId, String modelName, List<GraphEntity> entities, List<Relationship> relationships) {
    }
}
