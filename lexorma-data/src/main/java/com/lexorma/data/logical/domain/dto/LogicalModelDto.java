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
    /**
     * subjectAreaId (M-6 후속 4차) : 응답은 지금 소속 주제 영역(없으면 미분류), 등록 요청은 처음 소속(null = 미분류, 엔터티 등록과 한 트랜잭션).
     * 수정 요청에서는 보지 않는다 — 분류는 일괄 분류 API 로만 바꾼다 (엔터티 수정의 승인 검사·기준값과 섞지 않는다)
     */
    public record Entity(Long id, Long modelId, String entityName, String entityDescription, Boolean isApproval,
                         List<Attribute> attributes, List<Identifier> identifiers, java.time.LocalDateTime modifiedAt, Long subjectAreaId) {
        public Entity(Long id, Long modelId, String entityName, String entityDescription, Boolean isApproval,
                      List<Attribute> attributes, List<Identifier> identifiers) {
            this(id, modelId, entityName, entityDescription, isApproval, attributes, identifiers, null, null);
        }

        public Entity(Long id, Long modelId, String entityName, String entityDescription, Boolean isApproval,
                      List<Attribute> attributes, List<Identifier> identifiers, java.time.LocalDateTime modifiedAt) {
            this(id, modelId, entityName, entityDescription, isApproval, attributes, identifiers, modifiedAt, null);
        }

        public static Entity summary(LogicalEntity e) {
            return new Entity(e.getId(), e.getModel().getId(), e.getEntityName(), e.getEntityDescription(), e.getIsApproval(), null, null, e.modifiedAt());
        }

        public static Entity detail(LogicalEntity e) {
            return detail(e, null);
        }

        public static Entity detail(LogicalEntity e, Long subjectAreaId) {
            return new Entity(e.getId(), e.getModel().getId(), e.getEntityName(), e.getEntityDescription(), e.getIsApproval(),
                    e.getAttributes().stream().map(Attribute::fromEntity).toList(),
                    e.getIdentifiers().stream().map(Identifier::fromEntity).toList(), e.modifiedAt(), subjectAreaId);
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

    /**
     * 속성 순서 변경의 한 행 (M8-C4b L-02) : id = 속성, baseSortOrder = 화면이 순서를 읽었을 때의 그 속성 정렬 순서 (없던 순서는 0).
     * 요청 목록의 위치가 새 순서다 (1부터)
     */
    public record AttributeOrder(Long id, Integer baseSortOrder) {
        /** 서비스를 직접 부르는 내부 호출·테스트용 : 기준값 없이 (DEC-08 과 같은 경계 — HTTP 는 기준값 필수) */
        public static List<AttributeOrder> withoutBase(List<Long> ids) {
            return ids.stream().map(id -> new AttributeOrder(id, null)).toList();
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
     * cardinality 는 조회 전용 요약 ("1:1" / "1:N" / "N:1" / "N:M").
     * sourceSubjectAreaId·targetSubjectAreaId (M-6 후속 4차, 조회 전용) : 관계 목록 페이지에서만 채우는 양 끝 엔터티의 소속 주제 영역 (없으면 미분류·null)
     */
    public record Relationship(Long id, Long modelId, String relationshipName, String relationshipDescription,
                               Long sourceEntityId, String sourceEntityName, Long targetEntityId, String targetEntityName,
                               Boolean isIdentifying, Integer sourceMin, Integer sourceMax, Integer targetMin, Integer targetMax,
                               String cardinality, Boolean isApproval, java.time.LocalDateTime modifiedAt, PairState attributePairs,
                               Long sourceSubjectAreaId, Long targetSubjectAreaId) {
        public Relationship(Long id, Long modelId, String relationshipName, String relationshipDescription,
                            Long sourceEntityId, String sourceEntityName, Long targetEntityId, String targetEntityName,
                            Boolean isIdentifying, Integer sourceMin, Integer sourceMax, Integer targetMin, Integer targetMax,
                            String cardinality, Boolean isApproval, java.time.LocalDateTime modifiedAt, PairState attributePairs) {
            this(id, modelId, relationshipName, relationshipDescription, sourceEntityId, sourceEntityName, targetEntityId, targetEntityName,
                    isIdentifying, sourceMin, sourceMax, targetMin, targetMax, cardinality, isApproval, modifiedAt, attributePairs, null, null);
        }

        /** 양 끝 엔터티의 소속 주제 영역을 채운 사본 */
        public Relationship withSubjectAreas(Long sourceArea, Long targetArea) {
            return new Relationship(id, modelId, relationshipName, relationshipDescription, sourceEntityId, sourceEntityName, targetEntityId, targetEntityName,
                    isIdentifying, sourceMin, sourceMax, targetMin, targetMax, cardinality, isApproval, modifiedAt, attributePairs, sourceArea, targetArea);
        }

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
     * warnings : ORDER_DIFFERS · SELF_KEY_REUSED · IDENTIFYING_CHILD_NOT_KEY · REQUIRED_CHILD_NULLABLE, parentKeySize : 참조 식별자 속성 수 (해석되지 않으면 부모 주 식별자 속성 수).
     * keyType : 짝이 가리키는 부모 식별자 PRIMARY · ALTERNATE (M7-AK-D09 — 해석되지 않으면 null), keyIdentifierId·keyIdentifierName : 그 식별자 (상속 주 식별자면 null 일 수 있다)
     */
    public record PairState(String status, List<String> warnings, List<AttributePair> pairs, int parentKeySize, String keyType, Long keyIdentifierId,
                            String keyIdentifierName) {
        public PairState(String status, List<String> warnings, List<AttributePair> pairs, int parentKeySize) {
            this(status, warnings, pairs, parentKeySize, null, null, null);
        }
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

    /**
     * 짝이 참조할 수 있는 부모 식별자 (M7-AK-D09) : 주 식별자(상속 주 식별자 포함) · 부모 자체의 보조 식별자. attributeIds·attributeNames = 식별자 순서.
     * 화면의 입력 보조용 (식별자 ID 는 저장하지 않는다 — 저장된 짝의 부모 속성 집합으로 다시 해석한다).
     * ownerEntityId·ownerEntityName : 식별자를 정의한 엔터티 (M7-INH-02 — 서브타입 부모의 주 식별자는 루트, 보조 식별자는 부모 자신). 상속받은 보조 식별자는 후보에 없다 (D-INH-05)
     */
    public record ParentKey(Long identifierId, String identifierName, String keyType, List<Long> attributeIds, List<String> attributeNames, Long ownerEntityId,
                            String ownerEntityName) {
        public ParentKey(Long identifierId, String identifierName, String keyType, List<Long> attributeIds, List<String> attributeNames) {
            this(identifierId, identifierName, keyType, attributeIds, attributeNames, null, null);
        }
    }

    /**
     * 관계 속성 짝 조회·저장 응답 : 부모 후보 = 부모 주 식별자 속성(순서대로) + 부모 자체의 보조 식별자 속성, 자식 후보 = 자식 엔터티의 모든 속성.
     * parentKeys : 참조할 수 있는 부모 식별자 (주 식별자 먼저, 보조 식별자는 식별자 ID 순)
     */
    public record RelationshipPairs(Long relationshipId, java.time.LocalDateTime modifiedAt, Long parentEntityId, String parentEntityName, Long childEntityId,
                                    String childEntityName, Boolean isIdentifying, PairState state, List<PairCandidate> parentCandidates,
                                    List<PairCandidate> childCandidates, List<ParentKey> parentKeys) {
    }

    // ------------------------------------------------------------------ 논리 ERD

    /** identifier : 어느 식별자에든 속함, primaryIdentifier : 주 식별자에 속함 */
    public record GraphAttribute(Long id, String name, String dataType, Boolean isRequired, Integer sortOrder,
                                 Long termId, String termName, String domainName, boolean identifier, boolean primaryIdentifier) {
    }

    /**
     * supertypeEntityId·inheritedKey (M-6 후속 5차) : 서브타입이면 직접 슈퍼타입과 상속 주 식별자(루트의 주 식별자 속성, 순서대로 — 원본 속성 ID).
     * 서브타입이 아니면 null·빈 목록. 루트에 주 식별자가 없으면 빈 목록 (임의의 키를 만들지 않는다)
     */
    public record GraphEntity(Long id, String name, String description, Boolean isApproval, List<GraphAttribute> attributes,
                              Long supertypeEntityId, List<InheritedKey> inheritedKey) {
        public GraphEntity(Long id, String name, String description, Boolean isApproval, List<GraphAttribute> attributes) {
            this(id, name, description, isApproval, attributes, null, List.of());
        }

        public static GraphEntity of(LogicalEntity e, Set<Long> identifierAttributes, Set<Long> primaryAttributes) {
            return new GraphEntity(e.getId(), e.getEntityName(), e.getEntityDescription(), e.getIsApproval(),
                    e.getAttributes().stream().map(a -> {
                        Attribute dto = Attribute.fromEntity(a);
                        return new GraphAttribute(dto.id(), dto.attributeName(), dto.dataType(), dto.isRequired(), dto.sortOrder(),
                                dto.termId(), dto.termName(), dto.domainName(), identifierAttributes.contains(a.getId()), primaryAttributes.contains(a.getId()));
                    }).toList());
        }

        public GraphEntity withInheritance(Long supertypeEntityId, List<InheritedKey> inheritedKey) {
            return new GraphEntity(id, name, description, isApproval, attributes, supertypeEntityId, inheritedKey);
        }
    }

    /** 상속 주 식별자 속성 1개 (원본 속성 ID, 정의한 루트 엔터티) */
    public record InheritedKey(Long attributeId, String name, String dataType, int keyOrder, Long definedEntityId, String definedEntityName) {
    }

    /**
     * 논리 ERD 의 서브타입 그룹 (M-6 후속 5차) : 슈퍼타입이 범위 안이고 서브타입이 하나라도 범위 안인 그룹만. subtypes 는 범위 안의 서브타입만, subtypeCount 는 그룹 전체 수
     */
    public record GraphSubtypeGroup(Long id, String groupName, Long supertypeEntityId, Boolean overlapping, Boolean total, String classificationAttributeName,
                                    int subtypeCount, List<GraphSubtype> subtypes) {
    }

    public record GraphSubtype(Long entityId, String classificationValue) {
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

    /**
     * 논리 ERD 부분 조회 (M-6 후속 1차 — 범위 조회·이웃 탐색). entities·relationships 는 {@link Graph} 와 같은 모양이다.
     * relationships : 양 끝이 모두 entities 에 있는 관계만 (그릴 수 없는 선은 주지 않는다).
     * totalCount : 범위 조회는 조건에 맞는 전체 엔터티 수, 이웃 탐색은 담은 엔터티 수. truncated : 한도·페이지 때문에 일부만 담았는지.
     * modelEntityCount : 모델 전체 엔터티 수. outsideRelationships : 엔터티 ID → 상대가 범위 밖이라 빠진 관계 수 (0 은 없음)
     */
    /** subtypeGroups·outsideInheritances (M-6 후속 5차) : 범위 안 서브타입 그룹, 엔터티 ID → 상대가 범위 밖인 직접 상속 연결 수 (일반 관계 수와 따로 — 0 은 없음) */
    public record GraphPart(Long modelId, String modelName, List<GraphEntity> entities, List<Relationship> relationships,
                            long totalCount, boolean truncated, long modelEntityCount, Map<Long, Integer> outsideRelationships,
                            List<GraphSubtypeGroup> subtypeGroups, Map<Long, Integer> outsideInheritances) {
        public GraphPart(Long modelId, String modelName, List<GraphEntity> entities, List<Relationship> relationships,
                         long totalCount, boolean truncated, long modelEntityCount, Map<Long, Integer> outsideRelationships) {
            this(modelId, modelName, entities, relationships, totalCount, truncated, modelEntityCount, outsideRelationships, List.of(), Map.of());
        }
    }

    /** 논리 ERD 엔터티 찾기 결과 1건 (속성 없이) */
    public record EntityHit(Long id, String name, Boolean isApproval) {
    }

    /** 엔터티 찾기 : 이름순 앞부분 items 와 조건에 맞는 전체 수 */
    public record EntityHits(List<EntityHit> items, long totalCount) {
    }

    /**
     * 논리 모델 관리 엔터티 목록 1행 (M-6 후속 3차). 수는 DB 가 센다 — attributeCount : 이 엔터티의 속성,
     * relationshipCount : 출발·도착 어느 쪽이든 이 엔터티인 관계 (자기 참조는 1번 — 예전 화면이 전체 조회로 세던 것과 같다)
     */
    /** subjectAreaId (M-6 후속 4차) : 소속 주제 영역 (없으면 미분류·null) — 경로는 화면이 영역 목록으로 만든다 */
    public record EntityRow(Long id, String entityName, String entityDescription, Boolean isApproval, Long attributeCount, Long relationshipCount,
                            java.time.LocalDateTime modifiedAt, Long subjectAreaId) {
    }

    /** 논리 모델 관리 상단 요약 : 모델 전체 엔터티 수·관계 수 (검색과 무관) */
    public record Summary(Long modelId, long entityCount, long relationshipCount) {
    }
}
