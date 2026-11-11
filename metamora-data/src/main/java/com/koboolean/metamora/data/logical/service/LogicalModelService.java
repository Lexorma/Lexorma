package com.koboolean.metamora.data.logical.service;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.common.i18n.Messages;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.util.AuthUtil;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardTerm;
import com.koboolean.metamora.data.dictionary.repository.StandardTermRepository;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto;
import com.koboolean.metamora.data.logical.domain.entity.*;
import com.koboolean.metamora.data.logical.repository.*;
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
 * <li>삭제 : 모델은 엔터티가 없을 때, 엔터티는 관계가 없을 때, 속성은 식별자에 쓰이지 않을 때만. 엔터티의 속성·식별자는 엔터티와 함께 지운다.</li>
 * <li>승인 권한자(관리자·매니저·프로젝트 관리자)가 등록한 엔터티·관계는 바로 승인된다 (테이블·컬럼·물리 관계와 같다).</li>
 * <li>승인된 엔터티·관계 자체(이름·설명·삭제, 관계 정의)는 승인 취소 후에만 바꿀 수 있다. 승인된 엔터티의 속성·식별자는 승인 권한자만 바꿀 수 있다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LogicalModelService {

    private static final int NAME_MAX = 255;
    private static final int DESCRIPTION_MAX = 1000;
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
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
        modelRepository.delete(model);
    }

    // ------------------------------------------------------------------ 엔터티

    @Transactional(readOnly = true)
    public List<LogicalModelDto.Entity> selectEntities(AccountDto accountDto, Long modelId) {
        model(accountDto.getProjectId(), modelId);
        return entityRepository.findAllByModel_IdOrderByEntityNameAscIdAsc(modelId).stream().map(LogicalModelDto.Entity::summary).toList();
    }

    @Transactional(readOnly = true)
    public LogicalModelDto.Entity selectEntity(AccountDto accountDto, Long entityId) {
        return LogicalModelDto.Entity.detail(entity(accountDto.getProjectId(), entityId));
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
        }
        String name = name(request.entityName(), "엔터티명");
        if (entityRepository.existsByModel_IdAndEntityNameIgnoreCaseAndIdNot(entity.getModel().getId(), name, idOrNone(request.id()))) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        }
        entity.setEntityName(name);
        entity.setEntityDescription(description(request.entityDescription()));
        return LogicalModelDto.Entity.detail(entityRepository.save(entity));
    }

    @Transactional
    public void deleteEntity(AccountDto accountDto, Long entityId) {
        LogicalEntity entity = lockEntity(accountDto.getProjectId(), entityId);
        rejectApproved(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        // 관계는 함께 지우지 않는다 : 관계를 먼저 정리해야 한다
        relationshipRepository.findFirstBySourceEntity_IdOrTargetEntity_Id(entityId, entityId).ifPresent(r -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "관계 {0} 에서 사용 중인 엔터티입니다.", relationLabel(r));
        });
        // 속성·식별자는 엔터티에 딸린 데이터라 함께 지운다. 식별자-속성 연결이 속성보다 먼저 지워지도록 식별자부터 내보낸다 (FK)
        entity.getIdentifiers().clear();
        entityRepository.flush();
        entityRepository.delete(entity);
    }

    @Transactional
    public void updateEntityApproval(AccountDto accountDto, Long entityId, boolean isApproval) {
        requireApprover();
        lockEntity(accountDto.getProjectId(), entityId).setIsApproval(isApproval);
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
        rejectApprovedDetail(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);

        LogicalAttribute attribute;
        if (request.id() == null) {
            // 새 속성은 맨 뒤에 붙인다 (순서는 순서 변경 API 로 바꾼다)
            attribute = LogicalAttribute.builder().entity(entity)
                    .sortOrder(entity.getAttributes().stream().mapToInt(a -> a.getSortOrder() == null ? 0 : a.getSortOrder()).max().orElse(0) + 1).build();
            entity.getAttributes().add(attribute);
        } else {
            attribute = entity.getAttributes().stream().filter(a -> a.getId().equals(request.id())).findFirst().orElseThrow(() -> notFound("속성"));
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
        // 식별자에서 먼저 빼야 한다 (식별자 구성을 말없이 바꾸지 않는다)
        entity.getIdentifiers().stream().filter(i -> i.getAttributes().stream().anyMatch(a -> a.getAttribute().getId().equals(attributeId))).findFirst().ifPresent(i -> {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "식별자 {0} 에서 사용 중인 속성입니다.", i.getIdentifierName());
        });
        entity.getAttributes().removeIf(a -> a.getId().equals(attributeId));
    }

    /** 엔터티의 속성 순서를 attributeIds 순서대로 다시 매긴다. 그 엔터티의 속성 전체를 한 번씩 보내야 한다 */
    @Transactional
    public List<LogicalModelDto.Attribute> reorderAttributes(AccountDto accountDto, Long entityId, List<Long> attributeIds) {
        LogicalEntity entity = lockEntity(accountDto.getProjectId(), entityId);
        rejectApprovedDetail(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
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
        rejectApprovedDetail(entity.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);

        LogicalIdentifier identifier;
        if (request.id() == null) {
            identifier = LogicalIdentifier.builder().entity(entity).build();
            entity.getIdentifiers().add(identifier);
        } else {
            identifier = entity.getIdentifiers().stream().filter(i -> i.getId().equals(request.id())).findFirst().orElseThrow(() -> notFound("식별자"));
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
        entity.getIdentifiers().removeIf(i -> i.getId().equals(identifierId));
    }

    // ------------------------------------------------------------------ 관계

    @Transactional(readOnly = true)
    public List<LogicalModelDto.Relationship> selectRelationships(AccountDto accountDto, Long modelId) {
        model(accountDto.getProjectId(), modelId);
        return relationshipRepository.findAllByModelId(modelId).stream().map(LogicalModelDto.Relationship::fromEntity).toList();
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
            rejectApproved(relationship.getIsApproval(), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        }
        Long modelId = relationship.getModel().getId();
        relationship.setSourceEntity(relationEntity(projectId, modelId, request.sourceEntityId()));
        relationship.setTargetEntity(relationEntity(projectId, modelId, request.targetEntityId())); // 자기 참조 허용
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
        relationshipRepository.delete(relationship);
    }

    @Transactional
    public void updateRelationshipApproval(AccountDto accountDto, Long relationshipId, boolean isApproval) {
        requireApprover();
        relationshipRepository.findByIdAndProjectId(relationshipId, accountDto.getProjectId()).orElseThrow(() -> notFound("관계")).setIsApproval(isApproval);
    }

    // ------------------------------------------------------------------ 논리 ERD

    /** 모델의 엔터티(속성·식별자 표시 포함)와 관계. 조회 수는 데이터 크기와 관계없이 4번 (모델, 엔터티+속성, 식별자, 관계) */
    @Transactional(readOnly = true)
    public LogicalModelDto.Graph selectGraph(AccountDto accountDto, Long modelId) {
        LogicalModel model = model(accountDto.getProjectId(), modelId);
        Set<Long> identifierAttributes = new HashSet<>();
        Set<Long> primaryAttributes = new HashSet<>();
        for (LogicalIdentifier identifier : identifierRepository.findAllByModelId(modelId)) {
            for (LogicalIdentifierAttribute attribute : identifier.getAttributes()) {
                identifierAttributes.add(attribute.getAttribute().getId());
                if (Boolean.TRUE.equals(identifier.getIsPrimary())) primaryAttributes.add(attribute.getAttribute().getId());
            }
        }
        return new LogicalModelDto.Graph(model.getId(), model.getModelName(),
                entityRepository.findGraph(modelId).stream().map(e -> LogicalModelDto.GraphEntity.of(e, identifierAttributes, primaryAttributes)).toList(),
                relationshipRepository.findAllByModelId(modelId).stream().map(LogicalModelDto.Relationship::fromEntity).toList());
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

    private static void validate(LogicalModelDto.Layout request) {
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
        return new LinkedHashMap<>(OBJECT_MAPPER.readValue(layout.getNodePositions(), NODES_TYPE));
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

    private static String name(String value, String label) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) throw invalid("{0}을(를) 입력해주세요.", Messages.get(label));
        if (name.length() > NAME_MAX) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get(label), NAME_MAX);
        return name;
    }

    private static String description(String value) {
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

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_LOGICAL_MODEL, message, args);
    }

    private static CustomException notFound(String label) {
        return new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get(label));
    }

    private static <T> T fail(String label) {
        throw notFound(label);
    }
}
