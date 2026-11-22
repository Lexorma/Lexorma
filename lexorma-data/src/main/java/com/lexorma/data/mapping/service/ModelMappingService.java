package com.lexorma.data.mapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.repository.LogicalAttributeRepository;
import com.lexorma.data.logical.repository.LogicalEntityRepository;
import com.lexorma.data.logical.repository.LogicalModelRepository;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.entity.AttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import com.lexorma.data.mapping.domain.entity.SubtypeIdentifierColumnMapping;
import com.lexorma.data.mapping.repository.AttributeColumnMappingRepository;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.repository.ModelMappingQueryRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 논리·물리 모델 매핑 (M-7 1차) : 엔터티 ↔ 테이블, 속성 ↔ 컬럼.
 * <ul>
 * <li>명시적으로 등록한 연결만 저장한다. 엔터티·테이블·속성·컬럼과 그 승인 상태는 바꾸지 않는다 (미승인 객체도 매핑할 수 있다).</li>
 * <li>1:1 : 엔터티·테이블·속성·컬럼은 각각 매핑 1건에만 쓰인다 (서비스 검사 + DB UNIQUE).</li>
 * <li>속성 매핑은 부모(엔터티 ↔ 테이블 매핑)의 엔터티에 속한 속성과 테이블에 속한 컬럼만 연결한다.</li>
 * <li>속성 매핑이 있는 엔터티 매핑은 테이블 변경·삭제를 막는다 (속성 매핑을 먼저 해제).</li>
 * <li>변경은 논리 모델 행 → 물리 테이블 행 순서로 잠근다. 논리 모델 변경(엔터티·속성 삭제)은 모델 행을, 테이블·컬럼 삭제는 테이블 행을 잠그므로
 * 매핑 등록과 원본 삭제가 엇갈려도 한쪽이 끝난 뒤의 상태로 검사한다.</li>
 * <li>상속 PK 매핑 (M-7 3차, M7-D40) : 서브타입 엔터티 매핑 아래에서 루트 주 식별자 속성 ↔ 서브타입 테이블 컬럼. 컬럼은 속성 매핑과 상속 PK 매핑 중 한 곳에만 쓰인다 —
 * 두 등록 모두 같은 엔터티 매핑의 논리 모델 행 → 테이블 행을 잠근 뒤 양쪽을 확인하므로 동시에 같은 컬럼을 가져가지 못한다 (테이블 하나는 엔터티 매핑 하나에만 쓰인다).
 * 등록은 참조 유효성(서브타입·지금의 루트 주 식별자 속성·그 테이블의 컬럼·중복)만 확인하고, 물리 구조(PK·타입·부모 FK)는 바꾸지도 막지도 않는다 — 조회의 정합성 사유로 보인다
 * (속성 매핑과 같은 계약). 상속 PK 매핑이 있는 엔터티 매핑은 해제·테이블 변경을 막는다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ModelMappingService {

    private final EntityTableMappingRepository mappingRepository;
    private final AttributeColumnMappingRepository attributeMappingRepository;
    private final ModelMappingQueryRepository queryRepository;
    private final LogicalModelRepository modelRepository;
    private final LogicalEntityRepository entityRepository;
    private final LogicalAttributeRepository attributeRepository;
    private final TableInfoRepository tableInfoRepository;
    private final ColumnInfoRepository columnInfoRepository;
    private final com.lexorma.data.mapping.repository.SubtypeIdentifierColumnMappingRepository subtypeKeyRepository;
    private final com.lexorma.data.logical.service.LogicalSubtypeService subtypeService;
    private final SubtypePlanContexts subtypeContexts;
    private final com.lexorma.core.project.repository.ProjectRepository projectRepository;
    private final com.lexorma.data.logical.repository.LogicalSubtypeGroupRepository subtypeGroupRepository;

    // ------------------------------------------------------------------ 조회

    /** 엔터티 목록 페이지 + 행마다 상속 역할·상속 PK 현황 (M-7 4차 — 페이지 단위 일괄 조회, 엔터티별 조회 없음) */
    @Transactional(readOnly = true)
    public Page<ModelMappingDto.EntityRow> selectEntityRows(AccountDto accountDto, ModelMappingDto.EntitySearch search, Pageable pageable) {
        Page<ModelMappingDto.EntityRow> page = queryRepository.findEntityRows(accountDto.getProjectId(), search, pageable);
        List<ModelMappingDto.EntityRow> rows = withInheritance(accountDto.getProjectId(), page.getContent());
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, page.getTotalElements());
    }

    /** 매핑 1건과 그 엔터티의 속성 전체(매핑된 컬럼 포함), 아직 연결하지 않은 테이블 컬럼 */
    @Transactional(readOnly = true)
    public ModelMappingDto.Detail selectEntityMapping(AccountDto accountDto, Long mappingId) {
        ModelMappingDto.EntityRow row = mappingId == null ? null : queryRepository.findEntityRow(accountDto.getProjectId(), mappingId);
        if (row == null) throw notFound();
        return new ModelMappingDto.Detail(withInheritance(accountDto.getProjectId(), List.of(row)).get(0), queryRepository.findAttributeRows(row.entityId()),
                queryRepository.findUnmappedColumns(row.tableId()));
    }

    /**
     * 행들의 상속 요약을 채운다 (M-7 4차). 모델마다 계층 1번({@link com.lexorma.data.logical.service.LogicalSubtypeService#hierarchy} — ID 투영),
     * 매핑된 엔터티의 상속 PK 매핑 1번, 판정은 {@link SubtypePlanContext#prepare} 의 종류별 일괄 조회 — 조회 수가 행 수에 비례하지 않는다.
     * 판정은 상세·물리 생성과 같은 {@link SubtypePlanContext#ancestor} 다
     */
    List<ModelMappingDto.EntityRow> withInheritance(Long projectId, List<ModelMappingDto.EntityRow> rows) {
        if (rows.isEmpty()) return rows;
        java.util.Map<Long, com.lexorma.data.logical.service.LogicalHierarchy> hierarchies = new java.util.HashMap<>();
        rows.stream().map(ModelMappingDto.EntityRow::modelId).distinct().forEach(m -> hierarchies.put(m, subtypeService.hierarchy(m)));
        java.util.Map<Long, List<SubtypeIdentifierColumnMapping>> links = new java.util.HashMap<>();
        List<Long> mappingIds = rows.stream().map(ModelMappingDto.EntityRow::mappingId).filter(java.util.Objects::nonNull).toList();
        if (!mappingIds.isEmpty()) subtypeKeyRepository.findAllByMappingIds(mappingIds).forEach(l -> links.computeIfAbsent(l.getMapping().getId(), k -> new java.util.ArrayList<>()).add(l));
        com.lexorma.core.project.domain.enums.DbType db = null;
        java.util.Map<Long, SubtypePlanContext> contexts = new java.util.HashMap<>();
        List<ModelMappingDto.EntityRow> result = new java.util.ArrayList<>(rows.size());
        for (ModelMappingDto.EntityRow row : rows) {
            com.lexorma.data.logical.service.LogicalHierarchy hierarchy = hierarchies.get(row.modelId());
            List<SubtypeIdentifierColumnMapping> own = row.mappingId() == null ? List.of() : links.getOrDefault(row.mappingId(), List.of());
            if (!hierarchy.participates(row.entityId()) && own.isEmpty()) {
                result.add(row);
                continue;
            }
            if (!contexts.containsKey(row.modelId())) {
                if (db == null) db = dbType(projectId);
                SubtypePlanContext context = subtypeContexts.light(projectId, hierarchy, db);
                context.prepare(rows.stream().filter(r -> r.modelId().equals(row.modelId())).map(ModelMappingDto.EntityRow::entityId).toList());
                contexts.put(row.modelId(), context);
            }
            result.add(row.withInheritance(inheritance(contexts.get(row.modelId()), row, own)));
        }
        return result;
    }

    private static ModelMappingDto.Inheritance inheritance(SubtypePlanContext context, ModelMappingDto.EntityRow row, List<SubtypeIdentifierColumnMapping> own) {
        com.lexorma.data.logical.service.LogicalHierarchy hierarchy = context.hierarchy();
        Long id = row.entityId();
        if (!hierarchy.participates(id)) {
            // 상속이 해제됐는데 남은 상속 PK 매핑 : 모두 유효하지 않은 기존 매핑이다 (숨기지 않는다)
            return new ModelMappingDto.Inheritance(null, false, false, null, null, null, null, 0, 0, own.size(), SubtypePlanContext.NOT_SUBTYPE, "MISMATCH");
        }
        boolean subtype = context.isSubtype(id);
        boolean supertype = !hierarchy.groupsOf(id).isEmpty();
        Long parentId = hierarchy.parent(id);
        Long rootId = hierarchy.root(id);
        String parentName = parentId == null ? null : context.entity(parentId).getEntityName();
        String rootName = context.entity(rootId).getEntityName();
        if (!subtype) {
            return new ModelMappingDto.Inheritance("ROOT", supertype, false, null, null, rootId, rootName, 0, 0, own.size(),
                    own.isEmpty() ? null : SubtypePlanContext.NOT_SUBTYPE, own.isEmpty() ? null : "MISMATCH");
        }
        List<LogicalAttribute> key = context.rootKey(id);
        java.util.Set<Long> current = new java.util.HashSet<>();
        key.forEach(a -> current.add(a.getId()));
        int mapped = (int) own.stream().filter(l -> current.contains(l.getAttribute().getId())).count();
        int invalid = own.size() - mapped;
        String status = null;
        String resultStatus = null;
        if (row.mappingId() != null) {
            SubtypePlanContext.Inspection inspection = context.inspect(id);
            status = inspection.code() == null ? "VALID" : inspection.code();
            resultStatus = inspection.result();
        }
        return new ModelMappingDto.Inheritance(context.role(id), supertype, true, parentId, parentName, rootId, rootName, key.size(), mapped, invalid, status, resultStatus);
    }

    /**
     * 엔터티 또는 테이블 하나의 매핑 (논리 모델·테이블 관리·ERD 의 매핑 표시용). 둘 중 하나만 준다.
     * 엔터티는 매핑이 없어도 1행(mappingId null), 테이블은 매핑된 경우만 나온다. 없거나 다른 프로젝트 것이면 null
     */
    @Transactional(readOnly = true)
    public ModelMappingDto.EntityRow selectEntityRow(AccountDto accountDto, Long entityId, Long tableId) {
        if ((entityId == null) == (tableId == null)) {
            throw invalid("엔터티와 테이블 중 하나만 지정해주세요.");
        }
        return queryRepository.findEntityRow(accountDto.getProjectId(), entityId, tableId);
    }

    @Transactional(readOnly = true)
    public Page<ModelMappingDto.TableCandidate> selectUnmappedTables(AccountDto accountDto, Long schemaId, String searchQuery, Pageable pageable) {
        return queryRepository.findUnmappedTables(accountDto.getProjectId(), schemaId, searchQuery, pageable);
    }

    @Transactional(readOnly = true)
    public List<ModelMappingDto.Status> selectStatus(AccountDto accountDto, Long modelId) {
        List<ModelMappingDto.Status> status = queryRepository.findStatus(accountDto.getProjectId(), modelId);
        if (modelId != null && status.isEmpty()) {
            throw new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("논리 모델"));
        }
        return status;
    }

    // ------------------------------------------------------------------ 엔터티 ↔ 테이블

    @Transactional
    public ModelMappingDto.EntityMapping saveEntityMapping(AccountDto accountDto, ModelMappingDto.EntityMapping request) {
        Long projectId = accountDto.getProjectId();
        EntityTableMapping mapping;
        if (request.id() == null) {
            LogicalEntity entity = lockEntity(projectId, request.entityId());
            mappingRepository.findByEntity_Id(entity.getId()).ifPresent(m -> {
                throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "엔터티 {0} 은(는) 이미 테이블 {1} 에 매핑되어 있습니다.", entity.getEntityName(), m.getTable().getTableName());
            });
            mapping = EntityTableMapping.builder().projectId(projectId).entity(entity).build();
        } else {
            mapping = lockMapping(projectId, request.id());
            if (!mapping.getTable().getId().equals(request.tableId()) && attributeMappingRepository.existsByMapping_Id(mapping.getId())) {
                // 기존 속성 매핑을 말없이 남기거나 다른 컬럼으로 옮기지 않는다
                throw invalid("속성 매핑이 있는 엔터티 매핑은 테이블을 바꿀 수 없습니다. 속성 매핑을 먼저 해제해주세요.");
            }
            if (!mapping.getTable().getId().equals(request.tableId()) && subtypeKeyRepository.existsByMapping_Id(mapping.getId())) {
                throw invalid("상속 PK 매핑이 있는 엔터티 매핑은 테이블을 바꿀 수 없습니다. 상속 PK 매핑을 먼저 해제해주세요.");
            }
        }
        TableInfo table = lockTable(projectId, request.tableId());
        mappingRepository.findByTable_Id(table.getId()).filter(m -> !m.getId().equals(mapping.getId())).ifPresent(m -> {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "테이블 {0} 은(는) 이미 엔터티 {1} 에 매핑되어 있습니다.", table.getTableName(), m.getEntity().getEntityName());
        });
        mapping.setTable(table);
        try {
            mappingRepository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException e) {
            // 잠금으로 직렬화하지만 최종 방어는 DB UNIQUE 다
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS);
        }
        return new ModelMappingDto.EntityMapping(mapping.getId(), mapping.getEntity().getId(), table.getId());
    }

    @Transactional
    public void deleteEntityMapping(AccountDto accountDto, Long mappingId) {
        EntityTableMapping mapping = lockMapping(accountDto.getProjectId(), mappingId);
        if (attributeMappingRepository.existsByMapping_Id(mapping.getId())) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "속성 매핑이 있는 엔터티 매핑입니다. 속성 매핑을 먼저 해제해주세요.");
        }
        if (subtypeKeyRepository.existsByMapping_Id(mapping.getId())) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "상속 PK 매핑이 있는 엔터티 매핑입니다. 상속 PK 매핑을 먼저 해제해주세요.");
        }
        mappingRepository.delete(mapping);
    }

    // ------------------------------------------------------------------ 속성 ↔ 컬럼

    @Transactional
    public ModelMappingDto.AttributeMapping saveAttributeMapping(AccountDto accountDto, ModelMappingDto.AttributeMapping request) {
        Long projectId = accountDto.getProjectId();
        EntityTableMapping parent = lockMapping(projectId, request.id() == null ? request.mappingId()
                : attributeMappingRepository.findParentId(request.id(), projectId).orElseThrow(ModelMappingService::notFound));

        AttributeColumnMapping mapping;
        if (request.id() == null) {
            // 부모 매핑은 로그인 프로젝트 것이므로, 그 엔터티·테이블에 속하는지만 보면 다른 프로젝트·다른 엔터티의 속성·컬럼이 함께 걸러진다
            LogicalAttribute attribute = (request.attributeId() == null ? null : attributeRepository.findById(request.attributeId()).orElse(null));
            if (attribute == null || !attribute.getEntity().getId().equals(parent.getEntity().getId())) {
                throw invalid("매핑된 엔터티 {0} 의 속성만 연결할 수 있습니다.", parent.getEntity().getEntityName());
            }
            attributeMappingRepository.findByAttribute_Id(attribute.getId()).ifPresent(m -> {
                throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "속성 {0} 은(는) 이미 컬럼 {1} 에 매핑되어 있습니다.", attribute.getAttributeName(), m.getColumn().getColumnName());
            });
            mapping = AttributeColumnMapping.builder().mapping(parent).attribute(attribute).build();
        } else {
            mapping = attributeMappingRepository.findById(request.id()).orElseThrow(ModelMappingService::notFound);
        }
        ColumnInfo column = request.columnId() == null ? null : columnInfoRepository.findById(request.columnId()).orElse(null);
        if (column == null || column.getTableInfo() == null || !column.getTableInfo().getId().equals(parent.getTable().getId())) {
            throw invalid("매핑된 테이블 {0} 의 컬럼만 연결할 수 있습니다.", parent.getTable().getTableName());
        }
        attributeMappingRepository.findByColumn_Id(column.getId()).filter(m -> !m.getId().equals(mapping.getId())).ifPresent(m -> {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "컬럼 {0} 은(는) 이미 속성 {1} 에 매핑되어 있습니다.", column.getColumnName(), m.getAttribute().getAttributeName());
        });
        rejectInheritedKeyColumn(column, null);
        mapping.setColumn(column);
        try {
            attributeMappingRepository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS);
        }
        return new ModelMappingDto.AttributeMapping(mapping.getId(), parent.getId(), mapping.getAttribute().getId(), column.getId());
    }

    @Transactional
    public void deleteAttributeMapping(AccountDto accountDto, Long attributeMappingId) {
        Long projectId = accountDto.getProjectId();
        if (attributeMappingId == null) throw notFound();
        lockMapping(projectId, attributeMappingRepository.findParentId(attributeMappingId, projectId).orElseThrow(ModelMappingService::notFound));
        attributeMappingRepository.delete(attributeMappingRepository.findById(attributeMappingId).orElseThrow(ModelMappingService::notFound));
    }

    // ------------------------------------------------------------------ 상속 PK 매핑 (M-7 3차)

    /**
     * 서브타입 엔터티 매핑의 상속 PK 매핑과 지금 메타데이터로 계산한 정합성 (지금 서브타입이 아니거나 루트 주 식별자가 바뀐 기존 매핑도 숨기지 않는다).
     * structure (M-7 4차) : 같은 판정의 항목별 점검과 테이블 PK·직계 상위 키·나가는 FK — 이 상세를 열 때만 계산한다
     */
    @Transactional(readOnly = true)
    public ModelMappingDto.SubtypeKeyDetail selectSubtypeKeys(AccountDto accountDto, Long mappingId) {
        Long projectId = accountDto.getProjectId();
        ModelMappingDto.EntityRow row = mappingId == null ? null : queryRepository.findEntityRow(projectId, mappingId);
        if (row == null) throw notFound();
        EntityTableMapping mapping = mappingRepository.findByIdAndProjectId(mappingId, projectId).orElseThrow(ModelMappingService::notFound);
        LogicalEntity entity = mapping.getEntity();
        com.lexorma.data.logical.service.LogicalHierarchy hierarchy = subtypeService.hierarchy(entity.getModel().getId());
        List<SubtypeIdentifierColumnMapping> links = subtypeKeyRepository.findAllByMappingId(mappingId);
        boolean subtype = hierarchy.parent(entity.getId()) != null;
        List<ModelMappingDto.SubtypeKeyRow> keys = new java.util.ArrayList<>();
        java.util.Map<Long, SubtypeIdentifierColumnMapping> byAttribute = new java.util.HashMap<>();
        links.forEach(l -> byAttribute.put(l.getAttribute().getId(), l));
        SubtypePlanContext context = subtypeContexts.light(projectId, hierarchy, dbType(projectId));
        context.prepare(List.of(entity.getId()));
        row = withInheritance(projectId, List.of(row)).get(0);
        if (!subtype) {
            for (SubtypeIdentifierColumnMapping link : links) keys.add(keyRow(link, 0, false, link.getAttribute(), null));
            return new ModelMappingDto.SubtypeKeyDetail(row, false, null, null, null, null, SubtypePlanContext.NOT_SUBTYPE,
                    links.isEmpty() ? Messages.get("엔터티 {0} 은(는) 서브타입이 아니라 상속 PK 매핑 대상이 아닙니다.", entity.getEntityName())
                            : Messages.get("엔터티 {0} 은(는) 지금 서브타입이 아닙니다. 상속 PK 매핑을 해제할 수 있습니다.", entity.getEntityName()), false, keys, null);
        }
        SubtypePlanContext.Inspection inspection = context.inspect(entity.getId());
        java.util.Map<Long, SubtypePlanContext.KeyState> states = new java.util.HashMap<>();
        inspection.keys().forEach(k -> states.put(k.attributeId(), k));
        List<LogicalAttribute> rootKey = context.rootKey(entity.getId());
        java.util.Set<Long> current = new java.util.HashSet<>();
        for (int i = 0; i < rootKey.size(); i++) {
            LogicalAttribute attribute = rootKey.get(i);
            current.add(attribute.getId());
            SubtypeIdentifierColumnMapping link = byAttribute.get(attribute.getId());
            SubtypePlanContext.KeyState state = states.get(attribute.getId());
            keys.add(link == null ? new ModelMappingDto.SubtypeKeyRow(null, i + 1, true, attribute.getId(), attribute.getAttributeName(), attribute.getEntity().getId(),
                    attribute.getEntity().getEntityName(), null, null, null, null, null, null, null, termName(attribute), attributeType(attribute), null,
                    state == null || state.parentColumn() == null ? null : state.parentColumn().getColumnName(), "UNMAPPED") : keyRow(link, i + 1, true, attribute, state));
        }
        for (SubtypeIdentifierColumnMapping link : links) {
            if (!current.contains(link.getAttribute().getId())) keys.add(keyRow(link, 0, false, link.getAttribute(), null));
        }
        Long parentId = hierarchy.parent(entity.getId());
        Long rootId = hierarchy.root(entity.getId());
        boolean complete = !rootKey.isEmpty() && rootKey.stream().allMatch(a -> byAttribute.containsKey(a.getId()));
        String status = inspection.code() == null ? "VALID" : inspection.code();
        SubtypePlanContext.AncestorState state = context.ancestor(entity.getId(), java.util.Set.of());
        String message = state.code() == null ? null : SubtypePlanContext.ANCESTOR_INVALID.equals(state.code())
                ? Messages.get("상위 엔터티 쪽 물리 구조가 맞지 않아 이 테이블의 상속 경로를 확인할 수 없습니다.")
                : context.message(state, entity);
        return new ModelMappingDto.SubtypeKeyDetail(row, true, parentId, context.entity(parentId).getEntityName(), rootId, context.entity(rootId).getEntityName(),
                status, message, complete, keys, structure(inspection, hierarchy, entity.getId()));
    }

    private ModelMappingDto.Structure structure(SubtypePlanContext.Inspection inspection, com.lexorma.data.logical.service.LogicalHierarchy hierarchy, Long entityId) {
        com.lexorma.data.table.domain.entity.TableInfo table = inspection.table();
        com.lexorma.data.table.domain.entity.TableInfo parent = inspection.parentTable();
        com.lexorma.core.project.domain.enums.DbType db = dbType(table.getProjectId());
        java.util.function.Function<ColumnInfo, ModelMappingDto.KeyColumn> key = c -> new ModelMappingDto.KeyColumn(c.getId(), c.getColumnName(),
                com.lexorma.data.table.ddl.TableDdlGenerator.mappedType(db, c), Boolean.TRUE.equals(c.getIsPk()), com.lexorma.data.table.ddl.TableDdlGenerator.isNotNull(c));
        List<ModelMappingDto.ForeignKey> fks = new java.util.ArrayList<>();
        java.util.Set<Long> linkColumns = new java.util.HashSet<>();
        inspection.keys().forEach(k -> { if (k.column() != null) linkColumns.add(k.column().getId()); });
        List<SubtypePlanContext.KeyState> keys = inspection.keys();
        java.util.Set<String> expected = new java.util.HashSet<>();
        if (inspection.parentKey().size() == keys.size()) {
            for (SubtypePlanContext.KeyState k : keys) if (k.column() != null) expected.add(k.column().getId() + ">" + k.parentColumn().getId());
        }
        for (SubtypePlanContext.Fk relation : inspection.foreignKeys()) {
            java.util.Set<String> pairs = new java.util.HashSet<>();
            for (int i = 0; i < relation.childIds().size(); i++) {
                if (relation.childIds().get(i) != null && relation.parentIds().get(i) != null) pairs.add(relation.childIds().get(i) + ">" + relation.parentIds().get(i));
            }
            boolean toParent = parent != null && parent.getId().equals(relation.targetTableId());
            boolean link = toParent && expected.size() == keys.size() && !expected.isEmpty() && pairs.equals(expected) && relation.childIds().size() == expected.size();
            // 상속과 무관한 일반 FK 는 상세에 넣지 않는다 (상속 PK 컬럼을 쓰거나 직계 상위 테이블을 참조하는 FK 만)
            if (!link && !toParent && relation.childIds().stream().noneMatch(id -> id != null && linkColumns.contains(id))) continue;
            fks.add(new ModelMappingDto.ForeignKey(relation.id(), relation.name(), relation.source(), relation.targetTableId(), relation.targetTableName(),
                    relation.childNames().stream().map(n -> n == null ? "?" : n).toList(), relation.parentNames().stream().map(n -> n == null ? "?" : n).toList(), link));
        }
        // 테이블 PK 의 타입·NOT NULL 은 상세에서만 컬럼을 읽어 채운다 (한 테이블)
        java.util.Map<Long, ColumnInfo> tableColumns = new java.util.HashMap<>();
        table.getColumns().forEach(c -> tableColumns.put(c.getId(), c));
        List<ModelMappingDto.KeyColumn> tableKey = inspection.tableKey().stream().map(k -> tableColumns.containsKey(k.id()) ? key.apply(tableColumns.get(k.id()))
                : new ModelMappingDto.KeyColumn(k.id(), k.name(), null, true, true)).toList();
        List<ModelMappingDto.StructureCheck> checks = inspection.checks().stream().map(c -> new ModelMappingDto.StructureCheck(c.code(), c.level(), c.message(), c.detail())).toList();
        com.lexorma.data.logical.service.LogicalHierarchy.Group group = hierarchy.parentGroup(entityId);
        String note = null;
        if (group != null) {
            com.lexorma.data.logical.domain.entity.LogicalSubtypeGroup row = subtypeGroupRepository.findById(group.id()).orElse(null);
            if (row != null && (!Boolean.TRUE.equals(row.getOverlapping()) || Boolean.TRUE.equals(row.getTotal()))) {
                List<String> rules = new java.util.ArrayList<>();
                if (!Boolean.TRUE.equals(row.getOverlapping())) rules.add(Messages.get("배타(Disjoint)"));
                if (Boolean.TRUE.equals(row.getTotal())) rules.add(Messages.get("완전(Total)"));
                note = Messages.get("상속 그룹 {0} 의 {1} 규칙은 PK·FK 로 보장되지 않습니다 (물리 미보장). 상속 PK·FK 구조 정합성과 따로 보며 불일치로 세지 않습니다.",
                        row.getGroupName(), String.join(", ", rules));
            }
        }
        return new ModelMappingDto.Structure(inspection.result(), table.getId(), table.getSchema() == null ? null : table.getSchema().getSchemaName(), table.getTableName(),
                inspection.parentMappingId(), parent == null ? null : parent.getId(), parent == null || parent.getSchema() == null ? null : parent.getSchema().getSchemaName(),
                parent == null ? null : parent.getTableName(), tableKey, inspection.parentKey().stream().map(key).toList(),
                fks, checks, note);
    }

    private static String termName(LogicalAttribute attribute) {
        return attribute.getStandardTerm() == null ? null : attribute.getStandardTerm().getCommonStandardTermName();
    }

    /** 원본 속성의 데이터 타입 표시 : 표준 용어의 도메인 타입(길이), 없으면 속성에 적은 데이터 유형 */
    private static String attributeType(LogicalAttribute attribute) {
        com.lexorma.data.dictionary.domain.entity.StandardDomain domain = attribute.getStandardTerm() == null ? null : attribute.getStandardTerm().getStandardDomain();
        if (domain == null || domain.getDataType() == null || domain.getDataType().isBlank()) return attribute.getDataType();
        Integer length = domain.getDataLength() != null && domain.getDataLength() != 0 ? domain.getDataLength() : null;
        if (length == null) return domain.getDataType();
        return domain.getDataType() + "(" + length + (domain.getDataDecimalLength() != null ? "," + domain.getDataDecimalLength() : "") + ")";
    }

    /** 상속 PK 매핑 후보 컬럼 (서버 페이징 — 그리드 목록 규칙). currentId : 변경할 상속 PK 매핑 (그 컬럼도 후보로 보인다) */
    @Transactional(readOnly = true)
    public Page<ModelMappingDto.KeyColumnCandidate> selectSubtypeKeyCandidates(AccountDto accountDto, Long mappingId, Long currentId, String searchQuery, Pageable pageable) {
        Long projectId = accountDto.getProjectId();
        EntityTableMapping mapping = mappingId == null ? null : mappingRepository.findByIdAndProjectId(mappingId, projectId).orElse(null);
        if (mapping == null) throw notFound();
        if (currentId != null && !subtypeKeyRepository.findParentId(currentId, projectId).map(mappingId::equals).orElse(false)) throw notFound();
        return queryRepository.findKeyColumnCandidates(mapping.getTable().getId(), currentId, searchQuery, pageable);
    }

    /**
     * 상속 PK 매핑 등록·변경 (M7-D40). 등록 : 지금 서브타입인 엔터티의 매핑, 지금의 루트 주 식별자 속성, 그 매핑 테이블의 컬럼, (매핑, 속성)·컬럼 중복 없음, 속성 매핑에 쓰인 컬럼이 아님.
     * 변경 : 컬럼만 바꾼다 (속성은 그대로 — 지금 주 식별자가 아니게 된 매핑도 컬럼을 바꾸거나 해제할 수 있다). 같은 컬럼으로 바꾸면 아무것도 바뀌지 않는다 (이력 없음)
     */
    @Transactional
    public ModelMappingDto.SubtypeKeyMapping saveSubtypeKeyMapping(AccountDto accountDto, ModelMappingDto.SubtypeKeyMapping request) {
        Long projectId = accountDto.getProjectId();
        if (request == null) throw invalid("상속 PK 매핑 내용이 없습니다.");
        EntityTableMapping parent = lockMapping(projectId, request.id() == null ? request.mappingId()
                : subtypeKeyRepository.findParentId(request.id(), projectId).orElseThrow(ModelMappingService::notFound));
        SubtypeIdentifierColumnMapping mapping;
        if (request.id() == null) {
            LogicalEntity entity = parent.getEntity();
            // 잠근 논리 모델의 지금 상속 구조로 판단한다 (요청의 엔터티·소속 정보를 믿지 않는다)
            com.lexorma.data.logical.service.LogicalHierarchy hierarchy = subtypeService.hierarchy(entity.getModel().getId());
            if (hierarchy.parent(entity.getId()) == null) throw invalid("엔터티 {0} 은(는) 서브타입이 아니라 상속 PK 매핑을 등록할 수 없습니다.", entity.getEntityName());
            SubtypePlanContext context = subtypeContexts.light(projectId, hierarchy, dbType(projectId));
            LogicalAttribute attribute = request.attributeId() == null ? null
                    : context.rootKey(entity.getId()).stream().filter(a -> a.getId().equals(request.attributeId())).findFirst().orElse(null);
            if (attribute == null) throw invalid("엔터티 {0} 의 루트 주 식별자 속성만 상속 PK 로 연결할 수 있습니다.", entity.getEntityName());
            subtypeKeyRepository.findByMapping_IdAndAttribute_Id(parent.getId(), attribute.getId()).ifPresent(m -> {
                throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "속성 {0} 은(는) 이미 컬럼 {1} 에 상속 PK 매핑되어 있습니다.", attribute.getAttributeName(), m.getColumn().getColumnName());
            });
            mapping = SubtypeIdentifierColumnMapping.builder().mapping(parent).attribute(attribute).build();
        } else {
            mapping = subtypeKeyRepository.findById(request.id()).orElseThrow(ModelMappingService::notFound);
        }
        ColumnInfo column = request.columnId() == null ? null : columnInfoRepository.findById(request.columnId()).orElse(null);
        if (column == null || column.getTableInfo() == null || !column.getTableInfo().getId().equals(parent.getTable().getId())) {
            throw invalid("매핑된 테이블 {0} 의 컬럼만 연결할 수 있습니다.", parent.getTable().getTableName());
        }
        attributeMappingRepository.findByColumn_Id(column.getId()).ifPresent(m -> {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "컬럼 {0} 은(는) 이미 속성 {1} 에 매핑되어 있어 상속 PK 매핑에 쓸 수 없습니다.", column.getColumnName(), m.getAttribute().getAttributeName());
        });
        rejectInheritedKeyColumn(column, mapping.getId());
        mapping.setColumn(column);
        try {
            subtypeKeyRepository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS);
        }
        return new ModelMappingDto.SubtypeKeyMapping(mapping.getId(), parent.getId(), mapping.getAttribute().getId(), column.getId());
    }

    /** 상속 PK 매핑 해제 : 매핑만 지운다 (물리 PK·FK·컬럼과 논리 식별자는 그대로). 정합하지 않게 된 매핑도 해제할 수 있다 */
    @Transactional
    public void deleteSubtypeKeyMapping(AccountDto accountDto, Long id) {
        Long projectId = accountDto.getProjectId();
        if (id == null) throw notFound();
        lockMapping(projectId, subtypeKeyRepository.findParentId(id, projectId).orElseThrow(ModelMappingService::notFound));
        subtypeKeyRepository.delete(subtypeKeyRepository.findById(id).orElseThrow(ModelMappingService::notFound));
    }

    private static ModelMappingDto.SubtypeKeyRow keyRow(SubtypeIdentifierColumnMapping link, int order, boolean current, LogicalAttribute attribute, SubtypePlanContext.KeyState state) {
        ColumnInfo column = link.getColumn();
        return new ModelMappingDto.SubtypeKeyRow(link.getId(), order, current, attribute.getId(), attribute.getAttributeName(), attribute.getEntity().getId(),
                attribute.getEntity().getEntityName(), column.getId(), column.getColumnName(), column.getDataType(), column.getMaxLength(), column.getPrecision(),
                column.getScale(), column.getIsPk(), termName(attribute), attributeType(attribute), com.lexorma.data.table.ddl.TableDdlGenerator.isNotNull(column),
                state == null || state.parentColumn() == null ? null : state.parentColumn().getColumnName(), !current ? "STALE" : state == null ? null : state.state());
    }

    /** 다른 상속 PK 매핑이 쓰는 컬럼은 속성 매핑·상속 PK 매핑에 다시 쓸 수 없다 (self : 바꾸는 상속 PK 매핑 자신) */
    private void rejectInheritedKeyColumn(ColumnInfo column, Long self) {
        subtypeKeyRepository.findByColumn_Id(column.getId()).filter(m -> !m.getId().equals(self)).ifPresent(m -> {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "컬럼 {0} 은(는) 이미 속성 {1} 의 상속 PK 매핑에 쓰이고 있습니다.", column.getColumnName(), m.getAttribute().getAttributeName());
        });
    }

    private com.lexorma.core.project.domain.enums.DbType dbType(Long projectId) {
        return projectRepository.findById(projectId).map(p -> p.getDbType()).map(com.lexorma.core.project.domain.enums.DbType::orDefault)
                .orElse(com.lexorma.core.project.domain.enums.DbType.DEFAULT);
    }

    // ------------------------------------------------------------------ 보조

    /** 엔터티의 논리 모델 행을 잠근 뒤 엔터티를 읽는다 (논리 모델 변경과 같은 잠금 : 엔터티·속성 삭제와 직렬화) */
    private LogicalEntity lockEntity(Long projectId, Long entityId) {
        Long modelId = entityId == null ? null : entityRepository.findModelId(entityId, projectId).orElse(null);
        if (modelId == null || modelRepository.findForUpdate(modelId, projectId).isEmpty()) {
            throw new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("엔터티"));
        }
        return entityRepository.findByIdAndProjectId(entityId, projectId)
                .orElseThrow(() -> new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("엔터티")));
    }

    private TableInfo lockTable(Long projectId, Long tableId) {
        Optional<TableInfo> table = tableId == null ? Optional.empty() : tableInfoRepository.findForUpdate(tableId, projectId);
        return table.orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
    }

    /** 매핑의 논리 모델 행 → 물리 테이블 행 순서로 잠근 뒤 매핑을 읽는다 (잠금을 기다리는 사이 지워졌을 수 있다) */
    private EntityTableMapping lockMapping(Long projectId, Long mappingId) {
        if (mappingId == null) throw notFound();
        lockEntity(projectId, mappingRepository.findEntityId(mappingId, projectId).orElseThrow(ModelMappingService::notFound));
        EntityTableMapping mapping = mappingRepository.findByIdAndProjectId(mappingId, projectId).orElseThrow(ModelMappingService::notFound);
        lockTable(projectId, mapping.getTable().getId());
        return mapping;
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_MODEL_MAPPING, message, args);
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
    }
}
