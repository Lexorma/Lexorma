package com.lexorma.data.logical.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.entity.*;
import com.lexorma.data.logical.repository.*;
import com.lexorma.data.mapping.repository.AttributeColumnMappingRepository;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 논리 데이터 모델 (M-6 1차) : 논리 모델 · 엔터티 · 속성 · 식별자 · 관계 관리와 논리 ERD 조회.
 * <ul>
 * <li>물리 모델과 독립이다. 테이블·컬럼을 참조하지 않고, 표준 용어만 ID 로 연결한다 (도메인은 용어의 도메인).</li>
 * <li>프로젝트는 항상 로그인 프로젝트이고, 모든 ID 는 그 프로젝트 것인지 확인한다.</li>
 * <li>변경은 모델 행(모델 등록은 프로젝트 행)을 잠근 뒤 검사하므로 동시 요청에도 이름 중복·주 식별자·삭제 검사가 맞는다.</li>
 * <li>삭제 : 모델은 엔터티가 없을 때, 엔터티는 관계가 없을 때, 속성은 식별자에 쓰이지 않을 때만. 물리 테이블·컬럼에 매핑된 엔터티·속성은 매핑 해제 후에만 (M-7). 엔터티의 속성·식별자는 엔터티와 함께 지운다.</li>
 * <li>승인 권한자(관리자·매니저·프로젝트 관리자)가 등록한 엔터티·관계는 바로 승인된다 (테이블·컬럼·물리 관계와 같다).</li>
 * <li>승인된 엔터티·관계 자체(이름·설명·삭제, 관계 정의)는 승인 취소 후에만 바꿀 수 있다. 승인된 엔터티의 속성·식별자는 승인 권한자만 바꿀 수 있다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogicalModelService {

    private static final int NAME_MAX = 255;
    private static final int DESCRIPTION_MAX = 1000;
    static final int PAIR_MAX = 50;
    static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<Long, LogicalModelDto.Point>> NODES_TYPE = new TypeReference<>() {
    };
    private static final double MAX_COORDINATE = 10_000_000;
    private static final double MIN_ZOOM = 0.02, MAX_ZOOM = 4;

    private final LogicalModelRepository modelRepository;
    private final LogicalEntityRepository entityRepository;
    private final LogicalAttributeRepository attributeRepository;
    private final LogicalIdentifierRepository identifierRepository;
    private final LogicalRelationshipRepository relationshipRepository;
    private final StandardTermRepository standardTermRepository;
    private final ProjectRepository projectRepository;
    private final LogicalErdLayoutRepository layoutRepository;
    private final EntityTableMappingRepository entityTableMappingRepository;
    private final AttributeColumnMappingRepository attributeColumnMappingRepository;
    private final com.lexorma.data.mapping.repository.SubtypeIdentifierColumnMappingRepository subtypeKeyMappingRepository;
    private final LogicalRelationshipAttributeRepository pairRepository;
    private final LogicalModelQueryRepository queryRepository;
    private final LogicalSubjectAreaService subjectAreaService;
    private final LogicalSubtypeService subtypeService;
    private final jakarta.persistence.EntityManager entityManager;

    // ------------------------------------------------------------------ 논리 모델

    @Transactional(readOnly = true)
    public List<LogicalModelDto.Model> selectModels(AccountDto accountDto) {
        return modelRepository.findAllByProjectIdOrderByModelNameAscIdAsc(accountDto.getProjectId()).stream().map(LogicalModelDto.Model::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public LogicalModelDto.Model selectModel(AccountDto accountDto, Long modelId) {
        return LogicalModelDto.Model.fromEntity(model(accountDto.getProjectId(), modelId));
    }

    @Transactional
    public LogicalModelDto.Model saveModel(AccountDto accountDto, LogicalModelDto.Model request) {
        Long projectId = accountDto.getProjectId();
        projectRepository.findByIdForUpdate(projectId); // 같은 프로젝트의 모델명 중복 검사를 직렬화한다
        String name = name(request.modelName(), "모델명");
        LogicalModel model = request.id() == null ? LogicalModel.builder().projectId(projectId).build() : model(projectId, request.id());
        // DEC-08 : 모델 저장은 프로젝트 행 잠금으로 직렬화된다 — 잠근 뒤 다시 읽은 모델과 화면이 읽은 수정 일시 비교
        if (request.id() != null) {
            entityManager.refresh(model);
            com.lexorma.common.jpa.ModelBase.verify(model, request.modifiedAt(), model.getModelName());
        }
        if (modelRepository.existsByProjectIdAndModelNameIgnoreCaseAndIdNot(projectId, name, idOrNone(request.id()))) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        }
        model.setModelName(name);
        model.setModelDescription(description(request.modelDescription()));
        return LogicalModelDto.Model.fromEntity(modelRepository.save(model));
    }

    @Transactional
    public void deleteModel(AccountDto accountDto, Long modelId) {
        LogicalModel model = lockModel(accountDto.getProjectId(), modelId);
        if (entityRepository.existsByModel_Id(modelId)) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "{0} 에 엔터티가 있습니다.", model.getModelName());
        }
        layoutRepository.deleteByModelId(modelId); // 사용자들의 논리 ERD 배치는 모델에 딸린 화면 설정이다
        subjectAreaService.deleteForModel(modelId); // 주제 영역과 영역별 배치 (엔터티가 없으므로 소속은 없다 — D-19)
        modelRepository.delete(model);
    }

    // ------------------------------------------------------------------ 엔터티

    @Transactional(readOnly = true)
    public List<LogicalModelDto.Entity> selectEntities(AccountDto accountDto, Long modelId) {
        model(accountDto.getProjectId(), modelId);
        return entityRepository.findAllByModel_IdOrderByEntityNameAscIdAsc(modelId).stream().map(LogicalModelDto.Entity::summary).toList();
    }

    // ------------------------------------------------------------------ 논리 모델 관리 목록 (M-6 후속 3차)
    // 관리 화면은 전체 그래프(selectGraph)를 받지 않는다 : 요약(수 2개) + 엔터티 페이지(수는 DB 가 센다) + 관계 페이지 + 연 엔터티의 상세·관계.
    // 페이지 번호·크기(1~100)·정렬 필드 검사는 공용 PageableUtil 이 한다 (공통 페이징 안정성 개선 — 예전에는 여기서 따로 1~100 을 검사했다). 모델 확인이 먼저다 (순서 그대로)

    /** 모델 전체 엔터티 수·관계 수 (검색과 무관) */
    @Transactional(readOnly = true)
    public LogicalModelDto.Summary selectSummary(AccountDto accountDto, Long modelId) {
        model(accountDto.getProjectId(), modelId);
        return new LogicalModelDto.Summary(modelId, entityRepository.countByModel_Id(modelId), relationshipRepository.countByModel_Id(modelId));
    }

    /** 엔터티 목록 페이지 : 이름·설명 검색(논리 ERD 와 같은 규칙), 정렬 entityName·entityDescription·isApproval(기본 이름순), 같은 값은 ID 순 */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LogicalModelDto.EntityRow> selectEntityRows(AccountDto accountDto, Long modelId, String keyword, int page, int size, String sort) {
        return selectEntityRows(accountDto, modelId, keyword, page, size, sort, null, null, null);
    }

    /** 주제 영역 범위와 함께 (M-6 후속 4차) : 영역(기본 하위 포함)·미분류, 조건이 없으면 모델 전체. 검사 순서는 모델 → 검색어 → 페이지 요청 → 범위 */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LogicalModelDto.EntityRow> selectEntityRows(AccountDto accountDto, Long modelId, String keyword, int page, int size, String sort,
                                                                                            Long subjectAreaId, Boolean unclassified, Boolean includeChildren) {
        model(accountDto.getProjectId(), modelId);
        String pattern = likePattern(keyword);
        var pageable = com.lexorma.common.utils.PageableUtil.getGridPageable(page, size, sort, LogicalModelQueryRepository.ENTITY_SORT_FIELDS);
        var scope = subjectAreaService.scope(accountDto.getProjectId(), modelId, subjectAreaId, unclassified, includeChildren);
        return queryRepository.findEntityRows(modelId, pattern, scope, pageable);
    }

    /**
     * 관계 목록 페이지 : 검색어(관계명·출발·도착 엔터티명, 엔터티 검색과 같은 규칙), 식별 여부(null = 전체 — 저장된 값만 본다),
     * 정렬 relationshipName·sourceEntityName·targetEntityName(없으면 예전처럼 ID 순, 같은 값은 ID 순 — 후속 3차 보완).
     * 페이지의 관계를 먼저 고른 뒤 그 관계만 양 끝 엔터티·주 식별자(한 번)·속성 짝(한 번)과 함께 읽어 판정한다
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LogicalModelDto.Relationship> selectRelationshipRows(AccountDto accountDto, Long modelId, String keyword, Boolean identifying,
                                                                                                    int page, int size, String sort) {
        return selectRelationshipRows(accountDto, modelId, keyword, identifying, page, size, sort, null, null, null);
    }

    /**
     * 주제 영역 범위와 함께 (M-6 후속 4차, D-15) : 양 끝 엔터티 중 하나라도 범위 안인 관계 (한쪽이 범위 밖인 관계도 빠뜨리지 않는다).
     * 행마다 양 끝 엔터티의 소속 영역(sourceSubjectAreaId·targetSubjectAreaId)을 한 번에 읽어 채운다
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LogicalModelDto.Relationship> selectRelationshipRows(AccountDto accountDto, Long modelId, String keyword, Boolean identifying,
                                                                                                    int page, int size, String sort,
                                                                                                    Long subjectAreaId, Boolean unclassified, Boolean includeChildren) {
        model(accountDto.getProjectId(), modelId);
        String pattern = likePattern(keyword);
        var pageable = com.lexorma.common.utils.PageableUtil.getGridPageable(page, size, sort, LogicalModelQueryRepository.RELATIONSHIP_SORT_FIELDS);
        var scope = subjectAreaService.scope(accountDto.getProjectId(), modelId, subjectAreaId, unclassified, includeChildren);
        var ids = queryRepository.findRelationshipIds(modelId, pattern, identifying, scope, pageable);
        Map<Long, LogicalRelationship> found = new java.util.HashMap<>();
        if (!ids.isEmpty()) relationshipRepository.findWithEntities(ids.getContent()).forEach(r -> found.put(r.getId(), r));
        List<LogicalRelationship> rows = ids.getContent().stream().map(found::get).filter(Objects::nonNull).toList(); // 페이지 순서 그대로
        Set<Long> ends = new HashSet<>();
        rows.forEach(r -> {
            ends.add(r.getSourceEntity().getId());
            ends.add(r.getTargetEntity().getId());
        });
        Map<Long, Long> areas = subjectAreaService.areasOf(ends);
        List<LogicalModelDto.Relationship> result = withEndKeys(rows).stream()
                .map(r -> r.withSubjectAreas(areas.get(r.sourceEntityId()), areas.get(r.targetEntityId()))).toList();
        return new org.springframework.data.domain.PageImpl<>(result, ids.getPageable(), ids.getTotalElements());
    }

    /** 예전 호출 (검색·필터·정렬 없음 = ID 순) */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LogicalModelDto.Relationship> selectRelationshipRows(AccountDto accountDto, Long modelId, int page, int size) {
        return selectRelationshipRows(accountDto, modelId, null, null, page, size, null);
    }

    /** 엔터티 상세의 관계 탭 : 출발·도착 어느 쪽이든 이 엔터티인 관계와 속성 짝 (모델 전체 관계를 받아 거르지 않는다) */
    @Transactional(readOnly = true)
    public List<LogicalModelDto.Relationship> selectEntityRelationships(AccountDto accountDto, Long entityId) {
        LogicalEntity entity = entity(accountDto.getProjectId(), entityId);
        return withEndKeys(relationshipRepository.findAllByEntityId(entity.getId()));
    }

    /** 관계들 + 속성 짝 판정. 양 끝 엔터티(서브타입이면 루트)의 주 식별자를 한 번에 읽는다 (엔터티마다 식별자를 따로 읽지 않는다) */
    private List<LogicalModelDto.Relationship> withEndKeys(List<LogicalRelationship> relationships) {
        if (relationships.isEmpty()) return List.of();
        Set<Long> ends = new HashSet<>();
        relationships.forEach(r -> {
            ends.add(r.getSourceEntity().getId());
            ends.add(r.getTargetEntity().getId());
        });
        LogicalHierarchy hierarchy = subtypeService.hierarchy(relationships.getFirst().getModel().getId());
        return withPairs(relationships, keyLookup(hierarchy, ends));
    }

    /**
     * 짝 판정 조회 (M7-AK-D09) : 고른 엔터티들(과 그 루트)의 식별자를 한 번에 읽는다 — 유효 주 식별자 = 루트의 주 식별자, 참조할 수 있는 보조 식별자 = 상속 구조에
     * 참여하지 않는 엔터티 자체의 보조 식별자 (상속 계층을 부모로 하는 보조 식별자 참조는 보류 — M7-AK-D16)
     */
    private LogicalRelationshipPairs.KeyLookup keyLookup(LogicalHierarchy hierarchy, Set<Long> entityIds) {
        Set<Long> owners = new HashSet<>(entityIds);
        entityIds.forEach(id -> owners.add(hierarchy.root(id)));
        return keyLookup(owners.isEmpty() ? List.of() : identifierRepository.findAllByEntityIds(owners), hierarchy);
    }

    /** 이미 읽은 식별자로 만든 짝 판정 조회 (엔터티마다 식별자를 다시 읽지 않는다) */
    static LogicalRelationshipPairs.KeyLookup keyLookup(Iterable<LogicalIdentifier> identifiers, LogicalHierarchy hierarchy) {
        Map<Long, LogicalIdentifier> primary = new java.util.HashMap<>();
        Map<Long, List<ReferenceKeys.Key>> alternates = new java.util.HashMap<>();
        for (LogicalIdentifier identifier : identifiers) {
            Long entityId = identifier.getEntity().getId();
            if (Boolean.TRUE.equals(identifier.getIsPrimary())) primary.put(entityId, identifier);
            else alternates.computeIfAbsent(entityId, k -> new java.util.ArrayList<>()).add(new ReferenceKeys.Key(identifier.getId(), identifier.getIdentifierName(), false,
                    keyAttributeIds(identifier)));
        }
        alternates.values().forEach(list -> list.sort(java.util.Comparator.comparing(ReferenceKeys.Key::identifierId)));
        return new LogicalRelationshipPairs.KeyLookup() {
            @Override
            public List<Long> primary(LogicalEntity entity) {
                LogicalIdentifier key = primary.get(hierarchy.root(entity.getId()));
                return key == null ? List.of() : keyAttributeIds(key);
            }

            @Override
            public List<ReferenceKeys.Key> alternates(LogicalEntity entity) {
                return hierarchy.participates(entity.getId()) ? List.of() : alternates.getOrDefault(entity.getId(), List.of());
            }

            @Override
            public Long primaryIdentifierId(LogicalEntity entity) {
                LogicalIdentifier key = primary.get(hierarchy.root(entity.getId()));
                return key == null ? null : key.getId();
            }

            @Override
            public String primaryIdentifierName(LogicalEntity entity) {
                LogicalIdentifier key = primary.get(hierarchy.root(entity.getId()));
                return key == null ? null : key.getIdentifierName();
            }
        };
    }

    /** 유효 주 식별자를 가진 엔터티 (서브타입이면 루트 슈퍼타입). 계층은 호출한 쪽이 이미 읽은 것을 쓴다 (한 요청에서 계층을 다시 읽지 않는다) */
    private LogicalEntity keyOwner(LogicalEntity entity, LogicalHierarchy hierarchy) {
        Long root = hierarchy.root(entity.getId());
        return root.equals(entity.getId()) ? entity : entity(entity.getProjectId(), root);
    }

    /** 식별자 구성 속성 ID (식별자 순서) */
    private static List<Long> keyAttributeIds(LogicalIdentifier identifier) {
        return identifier.getAttributes().stream()
                .sorted(java.util.Comparator.comparing(LogicalIdentifierAttribute::getSortOrder, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(LogicalIdentifierAttribute::getId))
                .map(a -> a.getAttribute().getId()).distinct().toList();
    }

    @Transactional(readOnly = true)
    public LogicalModelDto.Entity selectEntity(AccountDto accountDto, Long entityId) {
        LogicalEntity entity = entity(accountDto.getProjectId(), entityId);
        return LogicalModelDto.Entity.detail(entity, subjectAreaService.areaOf(entity.getId()));
    }

    @Transactional
    public LogicalModelDto.Entity saveEntity(AccountDto accountDto, LogicalModelDto.Entity request) {
        Long projectId = accountDto.getProjectId();
        LogicalEntity entity;
        if (request.id() == null) {
            entity = LogicalEntity.builder().projectId(projectId).model(lockModel(projectId, request.modelId())).isApproval(AuthUtil.isApprovalAvailable()).build();
        } else {
            entity = lockEntity(projectId, request.id()); // 모델은 바꿀 수 없다 (요청의 modelId 는 무시)
            rejectApproved(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
            // 낙관적 충돌 검사 (DEC-08) : 논리 모델 행 잠금 뒤 다시 읽은 엔터티와 화면이 읽은 수정 일시 비교
            entityManager.refresh(entity);
            com.lexorma.common.jpa.ModelBase.verify(entity, request.modifiedAt(), entity.getEntityName());
        }
        String name = name(request.entityName(), "엔터티명");
        if (entityRepository.existsByModel_IdAndEntityNameIgnoreCaseAndIdNot(entity.getModel().getId(), name, idOrNone(request.id()))) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        }
        entity.setEntityName(name);
        entity.setEntityDescription(description(request.entityDescription()));
        boolean created = entity.getId() == null;
        LogicalEntity saved = entityRepository.save(entity);
        // 새 엔터티의 처음 소속 (D-17·D-24) : 같은 트랜잭션이라 영역 지정이 실패하면 엔터티 등록도 남지 않는다. 수정 요청의 subjectAreaId 는 보지 않는다
        if (created) subjectAreaService.assignNew(saved, request.subjectAreaId());
        return LogicalModelDto.Entity.detail(saved, created ? request.subjectAreaId() : subjectAreaService.areaOf(saved.getId()));
    }

    @Transactional
    public void deleteEntity(AccountDto accountDto, Long entityId) {
        LogicalEntity entity = lockEntity(accountDto.getProjectId(), entityId);
        rejectApproved(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        // 슈퍼타입·서브타입은 상속을 먼저 해제한다 (그룹·하위 엔터티를 함께 지우지 않는다 — M-6 후속 5차)
        subtypeService.requireNotParticipating(entity);
        // 관계는 함께 지우지 않는다 : 관계를 먼저 정리해야 한다
        relationshipRepository.findFirstBySourceEntity_IdOrTargetEntity_Id(entityId, entityId).ifPresent(r -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "관계 {0} 에서 사용 중인 엔터티입니다.", relationLabel(r));
        });
        // 물리 테이블에 매핑된 엔터티는 매핑을 먼저 해제해야 한다 (M-7). 속성 매핑은 엔터티 매핑이 있어야만 존재한다
        entityTableMappingRepository.findByEntity_Id(entityId).ifPresent(m -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "테이블 {0} 에 매핑된 엔터티입니다. 매핑을 먼저 해제해주세요.", m.getTable().getTableName());
        });
        // 주제 영역 소속은 엔터티를 지울 때만 함께 지운다 (삭제 조건을 모두 통과한 뒤 — 실패하면 소속도 그대로다)
        subjectAreaService.removeEntity(entityId);
        // 속성·식별자는 엔터티에 딸린 데이터라 함께 지운다. 식별자-속성 연결이 속성보다 먼저 지워지도록 식별자부터 내보낸다 (FK)
        entity.getIdentifiers().clear();
        entityRepository.flush();
        entityRepository.delete(entity);
    }

    @Transactional
    public void updateEntityApproval(AccountDto accountDto, Long entityId, boolean isApproval) {
        requireApprover();
        LogicalEntity entity = lockEntity(accountDto.getProjectId(), entityId);
        // 서브타입 승인은 조상이 모두 승인된 뒤, 승인 취소는 승인된 하위 엔터티가 없을 때 (자동으로 함께 바꾸지 않는다 — M-6 후속 5차)
        subtypeService.checkApprovalChange(entity, isApproval);
        entity.setIsApproval(isApproval);
    }

    // ------------------------------------------------------------------ 속성

    @Transactional(readOnly = true)
    public List<LogicalModelDto.Attribute> selectAttributes(AccountDto accountDto, Long entityId) {
        return entity(accountDto.getProjectId(), entityId).getAttributes().stream().map(LogicalModelDto.Attribute::fromEntity).toList();
    }

    @Transactional
    public LogicalModelDto.Attribute saveAttribute(AccountDto accountDto, LogicalModelDto.Attribute request) {
        Long projectId = accountDto.getProjectId();
        LogicalEntity entity = lockEntity(projectId, request.id() == null ? request.entityId()
                : attributeRepository.findEntityId(request.id(), projectId).orElseThrow(() -> notFound("속성")));
        // 다른 엔터티(예 : 서브타입 화면에서 상속 속성)를 문맥으로 고치는 요청은 받지 않는다 — 상속 속성은 정의 엔터티에서만 고친다 (M-6 후속 5차)
        if (request.id() != null && request.entityId() != null && !request.entityId().equals(entity.getId())) {
            throw invalid("속성 {0} 은(는) 엔터티 {1} 에 정의된 속성입니다. 정의한 엔터티에서 수정해주세요.", request.attributeName(), entity.getEntityName());
        }
        rejectApprovedDetail(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        subtypeService.requireNoApprovedDescendant(entity, ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE); // 하위 엔터티가 상속하는 정의
        // 상속 계층 : 조상의 속성·하위 엔터티의 속성과 이름이 겹치면 거절 (자동 병합·이름 변경 없음). 새 속성을 컬렉션에 넣기 전에 본다 (조회가 자동 플러시를 부른다)
        subtypeService.requireAttributeNameFree(entity, name(request.attributeName(), "속성명"), request.id());

        LogicalAttribute attribute;
        if (request.id() == null) {
            // 새 속성은 맨 뒤에 붙인다 (순서는 순서 변경 API 로 바꾼다)
            attribute = LogicalAttribute.builder().entity(entity)
                    .sortOrder(entity.getAttributes().stream().mapToInt(a -> a.getSortOrder() == null ? 0 : a.getSortOrder()).max().orElse(0) + 1).build();
            entity.getAttributes().add(attribute);
        } else {
            attribute = entity.getAttributes().stream().filter(a -> a.getId().equals(request.id())).findFirst().orElseThrow(() -> notFound("속성"));
            entityManager.refresh(attribute);
            com.lexorma.common.jpa.ModelBase.verify(attribute, request.modifiedAt(), attribute.getAttributeName()); // DEC-08
        }
        String name = name(request.attributeName(), "속성명");
        if (entity.getAttributes().stream().anyMatch(a -> a != attribute && a.getAttributeName().equalsIgnoreCase(name))) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        }
        attribute.setAttributeName(name);
        attribute.setAttributeDescription(description(request.attributeDescription()));
        attribute.setDataType(request.dataType() == null || request.dataType().isBlank() ? null : name(request.dataType(), "데이터 유형"));
        attribute.setIsRequired(Boolean.TRUE.equals(request.isRequired()));
        attribute.setStandardTerm(term(projectId, request.termId()));
        // 이미 영속 상태인 엔터티를 save(merge) 하면 새 자식이 복사본으로 저장돼 ID 를 돌려줄 수 없다 : 새 자식만 직접 저장한다
        if (attribute.getId() == null) attributeRepository.save(attribute);
        attributeRepository.flush();
        return LogicalModelDto.Attribute.fromEntity(attribute);
    }

    @Transactional
    public void deleteAttribute(AccountDto accountDto, Long attributeId) {
        Long projectId = accountDto.getProjectId();
        LogicalEntity entity = lockEntity(projectId, attributeRepository.findEntityId(attributeId, projectId).orElseThrow(() -> notFound("속성")));
        rejectApprovedDetail(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        subtypeService.requireNoApprovedDescendant(entity, ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        entity.getAttributes().stream().filter(a -> a.getId().equals(attributeId)).findFirst().ifPresent(subtypeService::requireNotClassification);
        // 식별자에서 먼저 빼야 한다 (식별자 구성을 말없이 바꾸지 않는다)
        entity.getIdentifiers().stream().filter(i -> i.getAttributes().stream().anyMatch(a -> a.getAttribute().getId().equals(attributeId))).findFirst().ifPresent(i -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "식별자 {0} 에서 사용 중인 속성입니다.", i.getIdentifierName());
        });
        attributeColumnMappingRepository.findByAttribute_Id(attributeId).ifPresent(m -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "컬럼 {0} 에 매핑된 속성입니다. 매핑을 먼저 해제해주세요.", m.getColumn().getColumnName());
        });
        // 상속 PK 매핑의 원본 속성 (M-7 3차, M7-D40) : 서브타입 테이블의 상속 PK 컬럼 매핑을 말없이 지우지 않는다 (같은 논리 모델 행 잠금 안에서 본다)
        subtypeKeyMappingRepository.findFirstByAttribute_IdIn(List.of(attributeId)).ifPresent(m -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "서브타입 테이블 컬럼 {0} 의 상속 PK 매핑에서 사용 중인 속성입니다. 상속 PK 매핑을 먼저 해제해주세요.", m.getColumn().getColumnName());
        });
        // 관계 속성 짝(M-7 보강 3차)에서 쓰는 속성 : 다른 관계의 짝을 말없이 지우지 않는다 — 짝을 먼저 해제한다
        pairRepository.findFirstUsing(attributeId).ifPresent(p -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "관계 {0} 의 속성 짝에서 사용 중인 속성입니다. 속성 짝을 먼저 해제해주세요.", relationLabel(p.getRelationship()));
        });
        entity.getAttributes().removeIf(a -> a.getId().equals(attributeId));
    }

    /** 엔터티의 속성 순서를 attributeIds 순서대로 다시 매긴다. 그 엔터티의 속성 전체를 한 번씩 보내야 한다 */
    @Transactional
    public List<LogicalModelDto.Attribute> reorderAttributes(AccountDto accountDto, Long entityId, List<Long> attributeIds) {
        LogicalEntity entity = lockEntity(accountDto.getProjectId(), entityId);
        rejectApprovedDetail(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        subtypeService.requireNoApprovedDescendant(entity, ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        Map<Long, LogicalAttribute> byId = entity.getAttributes().stream().collect(Collectors.toMap(LogicalAttribute::getId, Function.identity()));
        if (attributeIds == null || attributeIds.size() != byId.size() || !new HashSet<>(attributeIds).equals(byId.keySet())) {
            throw invalid("엔터티의 속성 전체를 한 번씩 지정해주세요.");
        }
        for (int i = 0; i < attributeIds.size(); i++) {
            byId.get(attributeIds.get(i)).setSortOrder(i + 1);
        }
        return attributeIds.stream().map(byId::get).map(LogicalModelDto.Attribute::fromEntity).toList();
    }

    // ------------------------------------------------------------------ 식별자

    @Transactional(readOnly = true)
    public List<LogicalModelDto.Identifier> selectIdentifiers(AccountDto accountDto, Long entityId) {
        return entity(accountDto.getProjectId(), entityId).getIdentifiers().stream().map(LogicalModelDto.Identifier::fromEntity).toList();
    }

    @Transactional
    public LogicalModelDto.Identifier saveIdentifier(AccountDto accountDto, LogicalModelDto.Identifier request) {
        Long projectId = accountDto.getProjectId();
        LogicalEntity entity = lockEntity(projectId, request.id() == null ? request.entityId()
                : identifierRepository.findEntityId(request.id(), projectId).orElseThrow(() -> notFound("식별자")));
        if (request.id() != null && request.entityId() != null && !request.entityId().equals(entity.getId())) {
            throw invalid("식별자 {0} 은(는) 엔터티 {1} 에 정의된 식별자입니다. 정의한 엔터티에서 수정해주세요.", request.identifierName(), entity.getEntityName());
        }
        rejectApprovedDetail(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        subtypeService.requireNoApprovedDescendant(entity, ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        // 서브타입은 루트의 주 식별자를 상속한다 (새 식별자를 컬렉션에 넣기 전에 본다)
        if (Boolean.TRUE.equals(request.isPrimary())) subtypeService.requireMayHavePrimaryIdentifier(entity);

        LogicalIdentifier identifier;
        if (request.id() == null) {
            identifier = LogicalIdentifier.builder().entity(entity).build();
            entity.getIdentifiers().add(identifier);
        } else {
            identifier = entity.getIdentifiers().stream().filter(i -> i.getId().equals(request.id())).findFirst().orElseThrow(() -> notFound("식별자"));
            entityManager.refresh(identifier);
            com.lexorma.common.jpa.ModelBase.verify(identifier, request.modifiedAt(), identifier.getIdentifierName()); // DEC-08 (엔터티의 모델 잠금 뒤)
        }
        String name = name(request.identifierName(), "식별자명");
        boolean primary = Boolean.TRUE.equals(request.isPrimary());
        for (LogicalIdentifier other : entity.getIdentifiers()) {
            if (other == identifier) continue;
            if (other.getIdentifierName().equalsIgnoreCase(name)) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
            if (primary && Boolean.TRUE.equals(other.getIsPrimary())) {
                throw invalid("주 식별자는 엔터티에 하나만 둘 수 있습니다. (현재 주 식별자 : {0})", other.getIdentifierName());
            }
        }

        // 구성 속성 : 1개 이상, 중복 없이, 모두 이 엔터티의 속성
        List<Long> attributeIds = request.attributeIds() == null ? List.of() : request.attributeIds();
        if (attributeIds.isEmpty()) throw invalid("식별자 속성을 1개 이상 지정해주세요.");
        if (new HashSet<>(attributeIds).size() != attributeIds.size()) throw invalid("같은 속성을 두 번 지정할 수 없습니다.");
        Map<Long, LogicalAttribute> owned = entity.getAttributes().stream().collect(Collectors.toMap(LogicalAttribute::getId, Function.identity()));
        if (!owned.keySet().containsAll(attributeIds)) throw invalid("식별자에는 같은 엔터티의 속성만 넣을 수 있습니다.");

        identifier.setIdentifierName(name);
        identifier.setIsPrimary(primary);
        // 기존 구성과 같으면 그대로 두고, 다르면 지우고 다시 넣는다 (UNIQUE(식별자, 속성) 충돌을 피하려고 먼저 지운 것을 내보낸다)
        if (!identifier.getAttributes().stream().map(a -> a.getAttribute().getId()).toList().equals(attributeIds)) {
            identifier.getAttributes().clear();
            identifierRepository.flush();
            for (int i = 0; i < attributeIds.size(); i++) {
                identifier.getAttributes().add(LogicalIdentifierAttribute.builder().identifier(identifier).attribute(owned.get(attributeIds.get(i))).sortOrder(i + 1).build());
            }
            if (identifier.getId() != null) identifier.markModified(); // 구성만 바뀌어도 기준값이 바뀐다 (DEC-08)
        }
        if (identifier.getId() == null) identifierRepository.save(identifier);
        identifierRepository.flush();
        return LogicalModelDto.Identifier.fromEntity(identifier);
    }

    @Transactional
    public void deleteIdentifier(AccountDto accountDto, Long identifierId) {
        Long projectId = accountDto.getProjectId();
        LogicalEntity entity = lockEntity(projectId, identifierRepository.findEntityId(identifierId, projectId).orElseThrow(() -> notFound("식별자")));
        rejectApprovedDetail(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        subtypeService.requireNoApprovedDescendant(entity, ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        entity.getIdentifiers().removeIf(i -> i.getId().equals(identifierId));
    }

    // ------------------------------------------------------------------ 관계

    @Transactional(readOnly = true)
    public List<LogicalModelDto.Relationship> selectRelationships(AccountDto accountDto, Long modelId) {
        model(accountDto.getProjectId(), modelId);
        return withEndKeys(relationshipRepository.findAllByModelId(modelId));
    }

    /** 관계 목록 + 관계마다 속성 짝과 판정 (짝은 한 번에 읽는다) */
    private List<LogicalModelDto.Relationship> withPairs(List<LogicalRelationship> relationships, LogicalRelationshipPairs.KeyLookup keys) {
        Map<Long, List<LogicalRelationshipAttribute>> pairs = new LinkedHashMap<>();
        if (!relationships.isEmpty()) {
            pairRepository.findAllByRelationshipIds(relationships.stream().map(LogicalRelationship::getId).toList())
                    .forEach(p -> pairs.computeIfAbsent(p.getRelationship().getId(), id -> new java.util.ArrayList<>()).add(p));
        }
        return relationships.stream()
                .map(r -> LogicalModelDto.Relationship.fromEntity(r, LogicalRelationshipPairs.evaluate(r, pairs.getOrDefault(r.getId(), List.of()), keys))).toList();
    }

    // ------------------------------------------------------------------ 관계 속성 짝 (M-7 보강 3차, P1)

    @Transactional(readOnly = true)
    public LogicalModelDto.RelationshipPairs selectRelationshipPairs(AccountDto accountDto, Long relationshipId) {
        LogicalRelationship relationship = relationshipRepository.findByIdAndProjectId(relationshipId, accountDto.getProjectId()).orElseThrow(() -> notFound("관계"));
        return pairsView(relationship);
    }

    /**
     * 관계의 속성 짝을 통째로 바꾼다 (관계 단위 일괄 교체). 순서 = 요청 목록 순서. 검사 (하나라도 어기면 아무것도 바꾸지 않는다, 400) :
     * 부모 속성은 관계의 부모 엔터티 주 식별자 속성, 자식 속성은 관계의 자식 엔터티 속성, 부모·자식 속성 각각 중복 없음. 부모 주 식별자의 일부만 잇는 것은 저장하되 PARTIAL 로 보인다.
     * 잠금·충돌 : 관계 저장과 같다 (논리 모델 행 잠금 → 관계를 다시 읽어 modifiedAt 비교, 다르면 409). 승인된 관계는 바꿀 수 없다.
     * 짝이 바뀌면 관계의 수정 일시를 올린다 (구성만 바뀌어도 기준값이 바뀐다 — 식별자 구성과 같은 규칙)
     */
    @Transactional
    public LogicalModelDto.RelationshipPairs saveRelationshipPairs(AccountDto accountDto, Long relationshipId, LogicalModelDto.PairSave request) {
        Long projectId = accountDto.getProjectId();
        lockModel(projectId, relationshipRepository.findModelId(relationshipId, projectId).orElseThrow(() -> notFound("관계")));
        LogicalRelationship relationship = relationshipRepository.findByIdAndProjectId(relationshipId, projectId).orElseThrow(() -> notFound("관계"));
        entityManager.refresh(relationship);
        rejectApproved(relationship.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        com.lexorma.common.jpa.ModelBase.verify(relationship, request == null ? null : request.modifiedAt(), relationLabel(relationship)); // DEC-08 (모델 잠금 뒤)
        rejectInheritedRelationshipChange(relationship.getSourceEntity(), relationship.getTargetEntity(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);

        List<LogicalModelDto.PairInput> inputs = request == null || request.pairs() == null ? List.of() : request.pairs();
        if (inputs.size() > PAIR_MAX) throw invalid("속성 짝은 {0}개까지 지정할 수 있습니다.", PAIR_MAX);
        LogicalEntity parent = relationship.getSourceEntity();
        LogicalEntity child = relationship.getTargetEntity();
        // 부모가 서브타입이면 루트 슈퍼타입의 주 식별자(상속 주 식별자 — 원본 속성 ID)를 짝의 부모 속성으로 쓴다 (M-6 후속 5차). 다른 엔터티의 속성 일반은 받지 않는다
        LogicalHierarchy hierarchy = subtypeService.hierarchy(relationship.getModel().getId()); // 모델 잠금 뒤 — 짝 저장은 계층을 바꾸지 않아 응답에도 그대로 쓴다
        LogicalEntity keyOwner = keyOwner(parent, hierarchy);
        List<LogicalAttribute> parentKey = LogicalRelationshipPairs.primaryKey(keyOwner);
        // 보조 식별자 참조 (M7-AK-D09) : 부모 자체의 보조 식별자 전체. 상속 구조에 참여하는 부모의 보조 식별자는 보류 (M7-AK-D16)
        boolean alternatesAllowed = !hierarchy.participates(parent.getId());
        List<LogicalIdentifier> alternates = alternatesAllowed
                ? parent.getIdentifiers().stream().filter(i -> !Boolean.TRUE.equals(i.getIsPrimary())).sorted(java.util.Comparator.comparing(LogicalIdentifier::getId)).toList()
                : List.of();
        if (!inputs.isEmpty() && parentKey.isEmpty() && alternates.isEmpty()) {
            if (keyOwner != parent) throw invalid("부모 엔터티 {0} 의 루트 슈퍼타입 {1} 에 주 식별자가 없어 속성 짝을 지정할 수 없습니다.", parent.getEntityName(), keyOwner.getEntityName());
            throw invalid("부모 엔터티 {0} 에 주 식별자가 없어 속성 짝을 지정할 수 없습니다.", parent.getEntityName());
        }
        Map<Long, LogicalAttribute> parentKeyById = new java.util.LinkedHashMap<>();
        parentKey.forEach(a -> parentKeyById.put(a.getId(), a));
        alternates.forEach(i -> LogicalRelationshipPairs.orderedAttributes(i).forEach(a -> parentKeyById.putIfAbsent(a.getId(), a)));
        Set<Long> heldAlternateAttributes = new HashSet<>();
        if (!alternatesAllowed) parent.getIdentifiers().stream().filter(i -> !Boolean.TRUE.equals(i.getIsPrimary()))
                .forEach(i -> i.getAttributes().forEach(a -> heldAlternateAttributes.add(a.getAttribute().getId())));
        Map<Long, LogicalAttribute> childById = child.getAttributes().stream().collect(Collectors.toMap(LogicalAttribute::getId, Function.identity()));
        Set<Long> parentAttributes = new HashSet<>();
        Set<Long> childAttributes = new HashSet<>();
        List<LogicalAttribute[]> resolved = new java.util.ArrayList<>();
        for (LogicalModelDto.PairInput input : inputs) {
            if (input == null || input.parentAttributeId() == null || input.childAttributeId() == null) throw invalid("속성 짝의 부모 속성과 자식 속성을 모두 골라주세요.");
            LogicalAttribute parentAttribute = parentKeyById.get(input.parentAttributeId());
            if (parentAttribute == null && heldAlternateAttributes.contains(input.parentAttributeId())) {
                throw invalid("상속 구조(슈퍼타입·서브타입)에 참여하는 부모 엔터티 {0} 의 보조 식별자를 참조하는 관계는 아직 지원하지 않습니다. (ID {1})", parent.getEntityName(), input.parentAttributeId());
            }
            if (parentAttribute == null) throw invalid("부모 속성은 부모 엔터티 {0} 의 (상속 포함) 주 식별자 또는 보조 식별자 속성이어야 합니다. (ID {1})", parent.getEntityName(), input.parentAttributeId());
            LogicalAttribute childAttribute = childById.get(input.childAttributeId());
            if (childAttribute == null) throw invalid("자식 속성은 자식 엔터티 {0} 의 속성이어야 합니다. (ID {1})", child.getEntityName(), input.childAttributeId());
            if (!parentAttributes.add(parentAttribute.getId())) throw invalid("같은 부모 속성 {0} 을(를) 두 번 지정할 수 없습니다.", parentAttribute.getAttributeName());
            if (!childAttributes.add(childAttribute.getId())) throw invalid("같은 자식 속성 {0} 을(를) 두 번 지정할 수 없습니다.", childAttribute.getAttributeName());
            resolved.add(new LogicalAttribute[]{parentAttribute, childAttribute});
        }
        if (!resolved.isEmpty()) {
            // 짝이 가리키는 식별자 : 주 식별자 전체·일부(기존 계약 — 일부는 PARTIAL 로 저장) 또는 보조 식별자 하나 전체. 보조 식별자의 일부·여러 식별자의 혼합은 받지 않는다
            ReferenceKeys.Resolution key = ReferenceKeys.resolve(resolved.stream().map(p -> p[0].getId()).toList(), null, null,
                    parentKey.stream().map(LogicalAttribute::getId).toList(), alternates.stream()
                            .map(i -> new ReferenceKeys.Key(i.getId(), i.getIdentifierName(), false, keyAttributeIds(i))).toList());
            if (!key.complete() && !"PARTIAL".equals(key.status())) {
                throw invalid("속성 짝의 부모 속성이 부모 엔터티 {0} 의 식별자 하나와 맞지 않습니다. 보조 식별자는 구성 속성 전체를 지정해야 하고, 서로 다른 식별자의 속성을 섞을 수 없습니다.",
                        parent.getEntityName());
            }
        }

        List<LogicalRelationshipAttribute> current = pairRepository.findAllByRelationshipIds(List.of(relationshipId));
        List<List<Long>> before = current.stream().map(p -> List.of(p.getParentAttribute().getId(), p.getChildAttribute().getId())).toList();
        List<List<Long>> after = resolved.stream().map(p -> List.of(p[0].getId(), p[1].getId())).toList();
        if (!before.equals(after)) {
            // 지운 것을 먼저 내보낸다 (UNIQUE(관계, 속성·순서) 충돌을 피한다)
            pairRepository.deleteAll(current);
            pairRepository.flush();
            for (int i = 0; i < resolved.size(); i++) {
                pairRepository.save(LogicalRelationshipAttribute.builder().relationship(relationship).parentAttribute(resolved.get(i)[0])
                        .childAttribute(resolved.get(i)[1]).sortOrder(i + 1).build());
            }
            relationship.markModified();
            pairRepository.flush();
            relationshipRepository.flush();
            // 응답의 기준값은 DB 에 저장된 값이어야 한다 : now() 는 OS 에 따라 나노초(Linux)인데 MDFCN_DT 는 timestamp(6) 라,
            // 메모리 값을 그대로 돌려주면 다음 요청이 다시 읽은 값과 달라진다 (같은 구성 재저장의 기준값 비교)
            entityManager.refresh(relationship);
        }
        return pairsView(relationship, hierarchy);
    }

    private LogicalModelDto.RelationshipPairs pairsView(LogicalRelationship relationship) {
        return pairsView(relationship, subtypeService.hierarchy(relationship.getModel().getId()));
    }

    private LogicalModelDto.RelationshipPairs pairsView(LogicalRelationship relationship, LogicalHierarchy hierarchy) {
        LogicalEntity parent = relationship.getSourceEntity();
        LogicalEntity child = relationship.getTargetEntity();
        // 유효 주 식별자 : 서브타입이면 루트의 주 식별자 (부모 후보는 상속 주 식별자 속성 — M-6 후속 5차)
        LogicalRelationshipPairs.KeyLookup keys = keyLookup(hierarchy, new HashSet<>(List.of(parent.getId(), child.getId()))); // 자기 참조면 같은 ID
        List<Long> parentKey = keys.primary(parent);
        List<Long> childKey = keys.primary(child);
        // 부모 후보 : (상속 포함) 주 식별자 속성(순서대로) → 부모 자체 보조 식별자 속성 (M7-AK-D09)
        List<LogicalAttribute> keyAttributes = LogicalRelationshipPairs.primaryKey(keyOwner(parent, hierarchy));
        List<LogicalModelDto.PairCandidate> parentCandidates = new java.util.ArrayList<>(keyAttributes.stream()
                .map(a -> new LogicalModelDto.PairCandidate(a.getId(), a.getAttributeName(), true, parentKey.indexOf(a.getId()) + 1, a.getIsRequired())).toList());
        Map<Long, LogicalAttribute> parentAttributes = new java.util.HashMap<>();
        keyAttributes.forEach(a -> parentAttributes.put(a.getId(), a));
        parent.getAttributes().forEach(a -> parentAttributes.put(a.getId(), a));
        List<LogicalModelDto.ParentKey> parentKeys = new java.util.ArrayList<>();
        if (!parentKey.isEmpty()) {
            parentKeys.add(new LogicalModelDto.ParentKey(keys.primaryIdentifierId(parent), keys.primaryIdentifierName(parent), ReferenceKeys.KeyType.PRIMARY.name(), parentKey,
                    parentKey.stream().map(id -> parentAttributes.containsKey(id) ? parentAttributes.get(id).getAttributeName() : String.valueOf(id)).toList()));
        }
        Set<Long> listed = new HashSet<>(parentKey);
        for (ReferenceKeys.Key alternate : keys.alternates(parent)) {
            parentKeys.add(new LogicalModelDto.ParentKey(alternate.identifierId(), alternate.identifierName(), ReferenceKeys.KeyType.ALTERNATE.name(), alternate.attributeIds(),
                    alternate.attributeIds().stream().map(id -> parentAttributes.containsKey(id) ? parentAttributes.get(id).getAttributeName() : String.valueOf(id)).toList()));
            for (Long id : alternate.attributeIds()) {
                LogicalAttribute a = parentAttributes.get(id);
                if (a != null && listed.add(id)) parentCandidates.add(new LogicalModelDto.PairCandidate(a.getId(), a.getAttributeName(), false, null, a.getIsRequired()));
            }
        }
        List<LogicalModelDto.PairCandidate> childCandidates = child.getAttributes().stream()
                .map(a -> new LogicalModelDto.PairCandidate(a.getId(), a.getAttributeName(), childKey.contains(a.getId()),
                        childKey.contains(a.getId()) ? childKey.indexOf(a.getId()) + 1 : null, a.getIsRequired())).toList();
        LogicalModelDto.PairState state = LogicalRelationshipPairs.evaluate(relationship, pairRepository.findAllByRelationshipIds(List.of(relationship.getId())), keys);
        return new LogicalModelDto.RelationshipPairs(relationship.getId(), relationship.modifiedAt(), parent.getId(), parent.getEntityName(), child.getId(),
                child.getEntityName(), relationship.getIsIdentifying(), state, parentCandidates, childCandidates, parentKeys);
    }

    @Transactional
    public LogicalModelDto.Relationship saveRelationship(AccountDto accountDto, LogicalModelDto.Relationship request) {
        Long projectId = accountDto.getProjectId();
        LogicalRelationship relationship;
        if (request.id() == null) {
            relationship = LogicalRelationship.builder().projectId(projectId).model(lockModel(projectId, request.modelId())).isApproval(AuthUtil.isApprovalAvailable()).build();
        } else {
            lockModel(projectId, relationshipRepository.findModelId(request.id(), projectId).orElseThrow(() -> notFound("관계")));
            relationship = relationshipRepository.findByIdAndProjectId(request.id(), projectId).orElseThrow(() -> notFound("관계"));
            entityManager.refresh(relationship);
            rejectApproved(relationship.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
            com.lexorma.common.jpa.ModelBase.verify(relationship, request.modifiedAt(),
                    relationship.getRelationshipName() == null ? String.valueOf(relationship.getId()) : relationship.getRelationshipName()); // DEC-08 (모델 잠금 뒤)
        }
        Long modelId = relationship.getModel().getId();
        // 상속되는 관계 (M-6 후속 5차) : 옛·새 양 끝 엔터티의 정의를 상속하는 승인된 하위 엔터티가 있으면 거절
        if (relationship.getId() != null) rejectInheritedRelationshipChange(relationship.getSourceEntity(), relationship.getTargetEntity(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        relationship.setSourceEntity(relationEntity(projectId, modelId, request.sourceEntityId()));
        relationship.setTargetEntity(relationEntity(projectId, modelId, request.targetEntityId())); // 자기 참조 허용
        rejectInheritedRelationshipChange(relationship.getSourceEntity(), relationship.getTargetEntity(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        relationship.setRelationshipName(request.relationshipName() == null || request.relationshipName().isBlank() ? null : name(request.relationshipName(), "관계명"));
        relationship.setRelationshipDescription(description(request.relationshipDescription()));
        relationship.setIsIdentifying(Boolean.TRUE.equals(request.isIdentifying()));
        relationship.setSourceMin(min(request.sourceMin()));
        relationship.setSourceMax(max(relationship.getSourceMin(), request.sourceMax()));
        relationship.setTargetMin(min(request.targetMin()));
        relationship.setTargetMax(max(relationship.getTargetMin(), request.targetMax()));
        return LogicalModelDto.Relationship.fromEntity(relationshipRepository.save(relationship));
    }

    @Transactional
    public void deleteRelationship(AccountDto accountDto, Long relationshipId) {
        Long projectId = accountDto.getProjectId();
        lockModel(projectId, relationshipRepository.findModelId(relationshipId, projectId).orElseThrow(() -> notFound("관계")));
        LogicalRelationship relationship = relationshipRepository.findByIdAndProjectId(relationshipId, projectId).orElseThrow(() -> notFound("관계"));
        rejectApproved(relationship.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        rejectInheritedRelationshipChange(relationship.getSourceEntity(), relationship.getTargetEntity(), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        // 관계의 속성 짝을 먼저 지운다 (엔티티 삭제라 변경 이력에 남는다 — DB 의 CASCADE 에 맡기지 않는다)
        pairRepository.deleteAll(pairRepository.findAllByRelationshipIds(List.of(relationshipId)));
        pairRepository.flush();
        relationshipRepository.delete(relationship);
    }

    @Transactional
    public void updateRelationshipApproval(AccountDto accountDto, Long relationshipId, boolean isApproval) {
        requireApprover();
        relationshipRepository.findByIdAndProjectId(relationshipId, accountDto.getProjectId()).orElseThrow(() -> notFound("관계")).setIsApproval(isApproval);
    }

    // ------------------------------------------------------------------ 논리 ERD

    /** 모델의 엔터티(속성·식별자 표시 포함)와 관계(속성 짝 포함). 조회 수는 데이터 크기와 관계없이 5번 (모델, 엔터티+속성, 식별자, 관계, 관계 속성 짝) */
    @Transactional(readOnly = true)
    public LogicalModelDto.Graph selectGraph(AccountDto accountDto, Long modelId) {
        LogicalModel model = model(accountDto.getProjectId(), modelId);
        Set<Long> identifierAttributes = new HashSet<>();
        Set<Long> primaryAttributes = new HashSet<>();
        Map<Long, List<Long>> primaryKeys = new java.util.HashMap<>(); // 엔터티 → 주 식별자 속성 (식별자 순서) : 관계 속성 짝 판정
        List<LogicalIdentifier> modelIdentifiers = identifierRepository.findAllByModelId(modelId);
        for (LogicalIdentifier identifier : modelIdentifiers) {
            for (LogicalIdentifierAttribute attribute : identifier.getAttributes()) {
                identifierAttributes.add(attribute.getAttribute().getId());
                if (Boolean.TRUE.equals(identifier.getIsPrimary())) primaryAttributes.add(attribute.getAttribute().getId());
            }
            if (Boolean.TRUE.equals(identifier.getIsPrimary())) {
                primaryKeys.put(identifier.getEntity().getId(), keyAttributeIds(identifier));
            }
        }
        LogicalHierarchy hierarchy = subtypeService.hierarchy(modelId); // 서브타입이 부모인 관계는 루트의 주 식별자로 짝을 판정한다 (M-6 후속 5차)
        return new LogicalModelDto.Graph(model.getId(), model.getModelName(),
                entityRepository.findGraph(modelId).stream().map(e -> LogicalModelDto.GraphEntity.of(e, identifierAttributes, primaryAttributes)).toList(),
                withPairs(relationshipRepository.findAllByModelId(modelId), keyLookup(modelIdentifiers, hierarchy)));
    }

    // ------------------------------------------------------------------ 논리 ERD 부분 조회 (M-6 후속 1차)
    // 물리 ERD(ErdService)와 같은 한도 : 한 번에 엔터티 300개, 이웃 1~3단계, 검색어 100자. 전체 조회(selectGraph)는 API 호환을 위해 그대로 둔다 (화면은 더 쓰지 않는다 — 후속 3차)

    public static final int ERD_MAX_ENTITIES = 300;
    public static final int ERD_MAX_DEPTH = 3;
    public static final int ERD_MAX_KEYWORD = 100;
    public static final int ERD_MAX_HITS = 50;

    /** 범위 조회 : 이름·설명에 검색어가 든 엔터티(없으면 모델 전체)를 이름순 page 번째 size 개 */
    @Transactional(readOnly = true)
    public LogicalModelDto.GraphPart selectGraphPart(AccountDto accountDto, Long modelId, String keyword, int page, int size) {
        return selectGraphPart(accountDto, modelId, keyword, page, size, null, null, null);
    }

    /**
     * 주제 영역 범위와 함께 (M-6 후속 4차) : 영역(기본 하위 포함)·미분류 안에서 같은 규칙(이름순, 한 번에 최대 300). 범위 밖 엔터티와의 관계는 선으로 그리지 않고
     * 엔터티별 범위 밖 관계 수(outsideRelationships)로 준다 (D-08 — 관계를 숨기지 않는다). 조건이 없으면 예전 쿼리 그대로
     */
    @Transactional(readOnly = true)
    public LogicalModelDto.GraphPart selectGraphPart(AccountDto accountDto, Long modelId, String keyword, int page, int size,
                                                     Long subjectAreaId, Boolean unclassified, Boolean includeChildren) {
        LogicalModel model = model(accountDto.getProjectId(), modelId);
        if (page < 0 || size < 1 || size > ERD_MAX_ENTITIES) throw invalid("한 번에 1~{0}개 엔터티까지 조회할 수 있습니다.", ERD_MAX_ENTITIES);
        String pattern = likePattern(keyword);
        var scope = subjectAreaService.scope(accountDto.getProjectId(), modelId, subjectAreaId, unclassified, includeChildren);
        long total = scope == null ? entityRepository.countSearch(modelId, pattern) : queryRepository.countScopedEntities(modelId, pattern, scope);
        long offset = (long) page * size;
        List<Long> ids = offset >= total ? List.of() : scope == null ? entityRepository.searchIds(modelId, pattern, org.springframework.data.domain.PageRequest.of(page, size))
                : queryRepository.findScopedEntityIds(modelId, pattern, scope, offset, size);
        return graphPart(model, ids, total, offset + ids.size() < total);
    }

    /** 이웃 탐색 : 관계(출발·도착 모두)로 depth 단계 안에 이어진 엔터티. 단계마다 관계 쿼리 1번, 한도를 넘으면 그 단계에서 멈춘다 */
    @Transactional(readOnly = true)
    public LogicalModelDto.GraphPart selectNeighbors(AccountDto accountDto, Long modelId, Long entityId, int depth) {
        Long projectId = accountDto.getProjectId();
        LogicalModel model = model(projectId, modelId);
        if (depth < 1 || depth > ERD_MAX_DEPTH) throw invalid("이웃 탐색은 1~{0}단계까지 가능합니다.", ERD_MAX_DEPTH);
        // 다른 모델·프로젝트의 엔터티는 없는 것과 같다
        if (entityId == null || !modelId.equals(entityRepository.findModelId(entityId, projectId).orElse(null))) throw notFound("엔터티");

        Set<Long> found = new java.util.LinkedHashSet<>(List.of(entityId));
        Set<Long> frontier = Set.of(entityId);
        boolean truncated = false;
        // 상속 연결(직접 슈퍼타입·서브타입)도 일반 관계와 같이 한 단계다 (M-6 후속 5차 — 그룹 기호는 단계를 쓰지 않는다). 같은 한도 300·1~3단계
        LogicalHierarchy hierarchy = subtypeService.hierarchy(modelId);
        for (int level = 0; level < depth && !frontier.isEmpty() && !truncated; level++) {
            Set<Long> next = new java.util.LinkedHashSet<>();
            List<Long> ends = new java.util.ArrayList<>();
            for (Object[] link : relationshipRepository.findLinks(modelId, frontier)) {
                ends.add((Long) link[1]);
                ends.add((Long) link[2]);
            }
            for (Long from : frontier) ends.addAll(hierarchy.links(from));
            for (Long end : ends) {
                if (found.contains(end)) continue; // 자기 참조·순환·여러 경로·관계와 상속이 같은 상대 : 한 번만
                if (found.size() >= ERD_MAX_ENTITIES) {
                    truncated = true;
                    break;
                }
                found.add(end);
                next.add(end);
            }
            frontier = next;
        }
        return graphPart(model, List.copyOf(found), found.size(), truncated, hierarchy);
    }

    /** 엔터티 찾기 (ERD 에 없는 엔터티도) : 이름·설명 검색, 이름순 최대 size 개(1~50) */
    @Transactional(readOnly = true)
    public LogicalModelDto.EntityHits searchEntities(AccountDto accountDto, Long modelId, String keyword, int size) {
        return searchEntities(accountDto, modelId, keyword, size, null, null, null);
    }

    /** 주제 영역 범위와 함께 (M-6 후속 4차) : 범위 안에서 같은 규칙 (최대 50). 조건이 없으면 모델 전체 (예전 쿼리 그대로) */
    @Transactional(readOnly = true)
    public LogicalModelDto.EntityHits searchEntities(AccountDto accountDto, Long modelId, String keyword, int size,
                                                     Long subjectAreaId, Boolean unclassified, Boolean includeChildren) {
        model(accountDto.getProjectId(), modelId);
        if (size < 1 || size > ERD_MAX_HITS) throw invalid("한 번에 1~{0}개 엔터티까지 찾을 수 있습니다.", ERD_MAX_HITS);
        String pattern = likePattern(keyword);
        var scope = subjectAreaService.scope(accountDto.getProjectId(), modelId, subjectAreaId, unclassified, includeChildren);
        if (scope != null) {
            return new LogicalModelDto.EntityHits(queryRepository.findScopedHits(modelId, pattern, scope, size), queryRepository.countScopedEntities(modelId, pattern, scope));
        }
        List<LogicalModelDto.EntityHit> items = entityRepository.searchHits(modelId, pattern, org.springframework.data.domain.PageRequest.of(0, size)).stream()
                .map(row -> new LogicalModelDto.EntityHit((Long) row[0], (String) row[1], (Boolean) row[2])).toList();
        return new LogicalModelDto.EntityHits(items, entityRepository.countSearch(modelId, pattern));
    }

    /** 고른 엔터티만 읽는다 (조회 수는 엔터티 수와 관계없이 일정 : 엔터티+속성, 식별자, 관계, 관계 속성 짝, 범위 밖 관계, 모델 엔터티 수) */
    private LogicalModelDto.GraphPart graphPart(LogicalModel model, List<Long> ids, long total, boolean truncated) {
        return graphPart(model, ids, total, truncated, subtypeService.hierarchy(model.getId()));
    }

    /**
     * 서브타입 (M-6 후속 5차) : 범위 안 서브타입의 상속 주 식별자(루트의 주 식별자)를 노드에 함께 준다 — 루트가 범위 밖이어도 그 식별자만 더 읽는다 (노드는 더하지 않는다).
     * 범위 안 그룹(슈퍼타입 + 서브타입 하나 이상)과 범위 밖 상속 연결 수는 일반 관계와 따로 준다. 상속이 없는 모델은 그룹 조회 1번만 늘어난다
     */
    private LogicalModelDto.GraphPart graphPart(LogicalModel model, List<Long> ids, long total, boolean truncated, LogicalHierarchy hierarchy) {
        Long modelId = model.getId();
        long modelEntityCount = entityRepository.countByModel_Id(modelId);
        if (ids.isEmpty()) return new LogicalModelDto.GraphPart(modelId, model.getModelName(), List.of(), List.of(), total, truncated, modelEntityCount, Map.of());
        Set<Long> identifierAttributes = new HashSet<>();
        Set<Long> primaryAttributes = new HashSet<>();
        Map<Long, List<Long>> primaryKeys = new java.util.HashMap<>();
        Map<Long, LogicalIdentifier> primaryIdentifiers = new java.util.HashMap<>();
        Set<Long> keyed = new java.util.LinkedHashSet<>(ids);
        ids.forEach(id -> keyed.add(hierarchy.root(id)));
        List<LogicalIdentifier> scopedIdentifiers = identifierRepository.findAllByEntityIds(keyed);
        for (LogicalIdentifier identifier : scopedIdentifiers) {
            for (LogicalIdentifierAttribute attribute : identifier.getAttributes()) {
                identifierAttributes.add(attribute.getAttribute().getId());
                if (Boolean.TRUE.equals(identifier.getIsPrimary())) primaryAttributes.add(attribute.getAttribute().getId());
            }
            if (Boolean.TRUE.equals(identifier.getIsPrimary())) {
                primaryKeys.put(identifier.getEntity().getId(), keyAttributeIds(identifier));
                primaryIdentifiers.put(identifier.getEntity().getId(), identifier);
            }
        }
        // 걸린 관계를 한 번 읽어 범위 안(양 끝 모두)·범위 밖(한 끝만)으로 나눈다. 범위 안 관계는 ID 로 읽는다
        // (양 끝을 각각 IN 으로 거르는 쿼리는 H2 에서 엔터티 300개일 때 0.1~0.7초 걸렸다 — 측정, 기록 m6 17장)
        Set<Long> scope = new HashSet<>(ids);
        Map<Long, Integer> outside = new java.util.TreeMap<>();
        Set<Long> within = new java.util.TreeSet<>();
        for (Object[] link : relationshipRepository.findLinks(modelId, scope)) {
            Long source = (Long) link[1];
            Long target = (Long) link[2];
            if (scope.contains(source) && scope.contains(target)) within.add((Long) link[0]);
            else if (scope.contains(source)) outside.merge(source, 1, Integer::sum);
            else outside.merge(target, 1, Integer::sum);
        }
        List<LogicalModelDto.GraphEntity> entities = entityRepository.findGraphByIds(modelId, scope).stream()
                .map(e -> LogicalModelDto.GraphEntity.of(e, identifierAttributes, primaryAttributes)).toList();
        if (!hierarchy.isEmpty()) {
            Set<Long> roots = new HashSet<>();
            ids.stream().filter(id -> hierarchy.parent(id) != null).forEach(id -> roots.add(hierarchy.root(id)));
            Map<Long, String> rootNames = new java.util.HashMap<>();
            if (!roots.isEmpty()) entityRepository.findAllByIds(roots, model.getProjectId()).forEach(e -> rootNames.put(e.getId(), e.getEntityName()));
            entities = entities.stream().map(e -> {
                Long parent = hierarchy.parent(e.id());
                if (parent == null) return e;
                Long root = hierarchy.root(e.id());
                LogicalIdentifier key = primaryIdentifiers.get(root);
                List<LogicalModelDto.InheritedKey> inheritedKey = new java.util.ArrayList<>();
                if (key != null) {
                    Map<Long, LogicalAttribute> parts = new java.util.HashMap<>();
                    key.getAttributes().forEach(a -> parts.put(a.getAttribute().getId(), a.getAttribute()));
                    List<Long> order = keyAttributeIds(key);
                    for (int i = 0; i < order.size(); i++) {
                        LogicalAttribute a = parts.get(order.get(i));
                        inheritedKey.add(new LogicalModelDto.InheritedKey(a.getId(), a.getAttributeName(), a.getDataType(), i + 1, root, rootNames.get(root)));
                    }
                }
                return e.withInheritance(parent, inheritedKey);
            }).toList();
        }
        return new LogicalModelDto.GraphPart(modelId, model.getModelName(), entities,
                withPairs(within.isEmpty() ? List.of() : relationshipRepository.findWithEntities(within), keyLookup(scopedIdentifiers, hierarchy)),
                total, truncated, modelEntityCount, outside, subtypeService.graphGroups(hierarchy, scope), LogicalSubtypeService.outsideLinks(hierarchy, scope));
    }

    /** 검색어 → like 패턴 (소문자, '!' 로 %·_·! 이스케이프). 비었으면 모두 */
    static String likePattern(String keyword) {
        String text = keyword == null ? "" : keyword.trim();
        if (text.length() > ERD_MAX_KEYWORD) throw invalid("검색어는 {0}자까지 입력할 수 있습니다.", ERD_MAX_KEYWORD);
        return "%" + text.toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    // ------------------------------------------------------------------ 논리 ERD 레이아웃 (내 배치, 모델별)

    /** 저장한 적 없으면 version·viewport 가 null. 지워진 엔터티의 좌표는 빼고 준다 */
    @Transactional(readOnly = true)
    public LogicalModelDto.Layout selectLayout(AccountDto accountDto, Long modelId) {
        model(accountDto.getProjectId(), modelId);
        return layoutRepository.findByModelIdAndAccountId(modelId, accountId(accountDto))
                .map(layout -> {
                    Map<Long, LogicalModelDto.Point> nodes = readNodes(layout);
                    nodes.keySet().retainAll(entityRepository.findIdsByModelId(modelId));
                    return toDto(layout, nodes);
                })
                .orElseGet(() -> new LogicalModelDto.Layout(null, Map.of(), null));
    }

    /** 개인 화면 설정이라 논리 모델은 바뀌지 않는다 (승인 상태와 무관, VIEWER 도 저장) */
    @Transactional
    public LogicalModelDto.Layout saveLayout(AccountDto accountDto, Long modelId, LogicalModelDto.Layout request) {
        validate(request);
        Long projectId = accountDto.getProjectId();
        Long accountId = accountId(accountDto);
        model(projectId, modelId);

        LogicalErdLayout layout = layoutRepository.findByModelIdAndAccountId(modelId, accountId)
                .orElseGet(() -> LogicalErdLayout.builder().projectId(projectId).modelId(modelId).accountId(accountId).build());
        if (!Objects.equals(layout.getVersion(), request.version())) {
            throw new CustomException(ErrorCode.ERD_LAYOUT_CONFLICT);
        }

        // 저장된 좌표 + 요청 좌표 (요청 우선). 지워진 엔터티는 정리하고, 이 모델에 없는 엔터티가 요청에 있으면 거절한다
        Map<Long, LogicalModelDto.Point> nodes = layout.getId() == null ? new LinkedHashMap<>() : readNodes(layout);
        nodes.putAll(request.nodes());
        Set<Long> existing = entityRepository.findIdsByModelId(modelId);
        if (!existing.containsAll(request.nodes().keySet())) {
            throw notFound("엔터티");
        }
        nodes.keySet().retainAll(existing);

        layout.setNodePositions(OBJECT_MAPPER.writeValueAsString(nodes));
        layout.setViewportX(request.viewport().x());
        layout.setViewportY(request.viewport().y());
        layout.setViewportZoom(request.viewport().zoom());
        try {
            // 동시에 저장한 다른 탭 : 최초 저장은 (모델, 사용자) UNIQUE, 이후는 @Version 으로 걸린다
            return toDto(layoutRepository.saveAndFlush(layout), nodes);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ERD_LAYOUT_CONFLICT);
        }
    }

    static void validate(LogicalModelDto.Layout request) {
        if (request == null || request.nodes() == null || request.viewport() == null) {
            throw new CustomException(ErrorCode.INVALID_ERD_REQUEST, "배치 정보가 없습니다.");
        }
        LogicalModelDto.Viewport viewport = request.viewport();
        boolean valid = coordinate(viewport.x()) && coordinate(viewport.y())
                && Double.isFinite(viewport.zoom()) && viewport.zoom() >= MIN_ZOOM && viewport.zoom() <= MAX_ZOOM
                && request.nodes().entrySet().stream().allMatch(e -> e.getKey() != null && e.getValue() != null
                        && coordinate(e.getValue().x()) && coordinate(e.getValue().y()));
        if (!valid) {
            throw new CustomException(ErrorCode.INVALID_ERD_REQUEST, "좌표 또는 확대 비율이 올바르지 않습니다.");
        }
    }

    private static boolean coordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }

    private static Map<Long, LogicalModelDto.Point> readNodes(LogicalErdLayout layout) {
        return readNodes(layout.getNodePositions());
    }

    /** 배치 JSON → 엔터티 ID → 좌표 (주제 영역별 배치도 같은 모양) */
    static Map<Long, LogicalModelDto.Point> readNodes(String json) {
        return new LinkedHashMap<>(OBJECT_MAPPER.readValue(json, NODES_TYPE));
    }

    static String writeNodes(Map<Long, LogicalModelDto.Point> nodes) {
        return OBJECT_MAPPER.writeValueAsString(nodes);
    }

    private static LogicalModelDto.Layout toDto(LogicalErdLayout layout, Map<Long, LogicalModelDto.Point> nodes) {
        return new LogicalModelDto.Layout(layout.getVersion(), nodes,
                new LogicalModelDto.Viewport(layout.getViewportX(), layout.getViewportY(), layout.getViewportZoom()));
    }

    private static Long accountId(AccountDto accountDto) {
        return Long.valueOf(accountDto.getId());
    }

    // ------------------------------------------------------------------ 보조

    private LogicalModel model(Long projectId, Long modelId) {
        return modelId == null ? fail("논리 모델") : modelRepository.findByIdAndProjectId(modelId, projectId).orElseThrow(() -> notFound("논리 모델"));
    }

    private LogicalModel lockModel(Long projectId, Long modelId) {
        return modelId == null ? fail("논리 모델") : modelRepository.findForUpdate(modelId, projectId).orElseThrow(() -> notFound("논리 모델"));
    }

    private LogicalEntity entity(Long projectId, Long entityId) {
        return entityId == null ? fail("엔터티") : entityRepository.findByIdAndProjectId(entityId, projectId).orElseThrow(() -> notFound("엔터티"));
    }

    /** 엔터티의 모델을 잠근 뒤 엔터티를 읽는다 (잠금 전에 읽은 상태로 검사하지 않도록) */
    private LogicalEntity lockEntity(Long projectId, Long entityId) {
        if (entityId == null) return fail("엔터티");
        lockModel(projectId, entityRepository.findModelId(entityId, projectId).orElseThrow(() -> notFound("엔터티")));
        return entity(projectId, entityId);
    }

    /** 관계의 양쪽 엔터티 : 로그인 프로젝트의 같은 논리 모델에 있어야 한다 */
    private LogicalEntity relationEntity(Long projectId, Long modelId, Long entityId) {
        LogicalEntity entity = entity(projectId, entityId);
        if (!entity.getModel().getId().equals(modelId)) throw invalid("관계의 엔터티는 같은 논리 모델에 있어야 합니다.");
        return entity;
    }

    /** 표준 용어 : 로그인 프로젝트의 용어만 (승인 여부는 컬럼과 같이 따지지 않는다) */
    private StandardTerm term(Long projectId, Long termId) {
        if (termId == null) return null;
        StandardTerm term = standardTermRepository.findByIdAndProjectId(termId, projectId);
        if (term == null) throw new CustomException(ErrorCode.NOT_FOUND_TERM_DATA);
        return term;
    }

    static String name(String value, String label) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) throw invalid("{0}을(를) 입력해주세요.", Messages.get(label));
        if (name.length() > NAME_MAX) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get(label), NAME_MAX);
        return name;
    }

    static String description(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > DESCRIPTION_MAX) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get("설명"), DESCRIPTION_MAX);
        return value;
    }

    /** 최소 참여 수 : 0(선택, 기본) 이상 */
    private static int min(Integer value) {
        if (value != null && value < 0) throw invalid("최소 참여 수는 0 이상이어야 합니다.");
        return value == null ? 0 : value;
    }

    /** 최대 참여 수 : null(다) 또는 1 이상이면서 최소 이상 */
    private static Integer max(int min, Integer value) {
        if (value != null && (value < 1 || value < min)) throw invalid("최대 참여 수는 1 이상이고 최소 참여 수보다 작을 수 없습니다.");
        return value;
    }

    private static Long idOrNone(Long id) {
        return id == null ? -1L : id; // 새로 등록할 때는 제외할 자기 자신이 없다
    }

    private static String relationLabel(LogicalRelationship r) {
        return r.getRelationshipName() != null ? r.getRelationshipName() : r.getSourceEntity().getEntityName() + " → " + r.getTargetEntity().getEntityName();
    }

    /** 관계는 양 끝 엔터티의 하위 엔터티에 상속된다 (M-6 후속 5차) : 어느 한 끝이라도 승인된 하위 엔터티가 있으면 관계를 바꿀 수 없다 */
    private void rejectInheritedRelationshipChange(LogicalEntity source, LogicalEntity target, ErrorCode errorCode) {
        subtypeService.requireNoApprovedDescendant(source, errorCode);
        if (!target.getId().equals(source.getId())) subtypeService.requireNoApprovedDescendant(target, errorCode);
    }

    private static void rejectApproved(Boolean isApproval, ErrorCode errorCode) {
        if (Boolean.TRUE.equals(isApproval)) throw new CustomException(errorCode);
    }

    /** 승인된 엔터티의 속성·식별자 : 승인 권한자는 승인 상태 그대로 바꿀 수 있다 (등록하면 바로 승인되므로). 그 외에는 승인 취소 후에만 */
    private static void rejectApprovedDetail(Boolean isApproval, ErrorCode errorCode) {
        if (Boolean.TRUE.equals(isApproval) && !AuthUtil.isApprovalAvailable()) throw new CustomException(errorCode);
    }

    private static void requireApprover() {
        if (!AuthUtil.isApprovalAvailable()) throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
    }

    static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_LOGICAL_MODEL, message, args);
    }

    static CustomException notFound(String label) {
        return new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get(label));
    }

    private static <T> T fail(String label) {
        throw notFound(label);
    }
}
