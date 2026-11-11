package com.koboolean.metamora.data.logical.domain.dto;

import com.koboolean.metamora.data.dictionary.domain.entity.StandardTerm;
import com.koboolean.metamora.data.logical.domain.entity.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 논리 모델 조회·저장 DTO. 저장 요청은 id 가 없으면 등록, 있으면 수정이다 (이름이 …Name 인 조회 전용 값은 보내지 않는다) */
public final class LogicalModelDto {

    private LogicalModelDto() {
    }

    public record Model(Long id, String modelName, String modelDescription) {
        public static Model fromEntity(LogicalModel m) {
            return new Model(m.getId(), m.getModelName(), m.getModelDescription());
        }
    }

    /** attributes·identifiers 는 상세 조회에서만 채운다 (목록은 null) */
    public record Entity(Long id, Long modelId, String entityName, String entityDescription, Boolean isApproval,
                         List<Attribute> attributes, List<Identifier> identifiers) {
        public static Entity summary(LogicalEntity e) {
            return new Entity(e.getId(), e.getModel().getId(), e.getEntityName(), e.getEntityDescription(), e.getIsApproval(), null, null);
        }

        public static Entity detail(LogicalEntity e) {
            return new Entity(e.getId(), e.getModel().getId(), e.getEntityName(), e.getEntityDescription(), e.getIsApproval(),
                    e.getAttributes().stream().map(Attribute::fromEntity).toList(),
                    e.getIdentifiers().stream().map(Identifier::fromEntity).toList());
        }
    }

    /** termName·domainName·domainDataType 은 조회 전용 : 표준 도메인은 연결한 표준 용어의 도메인이다 */
    public record Attribute(Long id, Long entityId, String attributeName, String attributeDescription, String dataType, Boolean isRequired,
                            Integer sortOrder, Long termId, String termName, String domainName, String domainDataType) {
        public static Attribute fromEntity(LogicalAttribute a) {
            StandardTerm term = a.getStandardTerm();
            var domain = term == null ? null : term.getStandardDomain();
            return new Attribute(a.getId(), a.getEntity().getId(), a.getAttributeName(), a.getAttributeDescription(), a.getDataType(),
                    a.getIsRequired(), a.getSortOrder(), term == null ? null : term.getId(), term == null ? null : term.getCommonStandardTermName(),
                    domain == null ? null : domain.getCommonStandardDomainName(), domain == null ? null : domain.getDataType());
        }
    }

    /** attributeIds : 식별자를 이루는 속성 (순서대로, 복합 가능). attributeNames 는 조회 전용 */
    public record Identifier(Long id, Long entityId, String identifierName, Boolean isPrimary, List<Long> attributeIds, List<String> attributeNames) {
        public static Identifier fromEntity(LogicalIdentifier i) {
            return new Identifier(i.getId(), i.getEntity().getId(), i.getIdentifierName(), i.getIsPrimary(),
                    i.getAttributes().stream().map(a -> a.getAttribute().getId()).toList(),
                    i.getAttributes().stream().map(a -> a.getAttribute().getAttributeName()).toList());
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
                               String cardinality, Boolean isApproval) {
        public static Relationship fromEntity(LogicalRelationship r) {
            return new Relationship(r.getId(), r.getModel().getId(), r.getRelationshipName(), r.getRelationshipDescription(),
                    r.getSourceEntity().getId(), r.getSourceEntity().getEntityName(), r.getTargetEntity().getId(), r.getTargetEntity().getEntityName(),
                    r.getIsIdentifying(), r.getSourceMin(), r.getSourceMax(), r.getTargetMin(), r.getTargetMax(),
                    cardinality(r.getSourceMax(), r.getTargetMax()), r.getIsApproval());
        }

        private static String cardinality(Integer sourceMax, Integer targetMax) {
            boolean sourceOne = sourceMax != null && sourceMax == 1;
            boolean targetOne = targetMax != null && targetMax == 1;
            return sourceOne ? (targetOne ? "1:1" : "1:N") : (targetOne ? "N:1" : "N:M");
        }
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
