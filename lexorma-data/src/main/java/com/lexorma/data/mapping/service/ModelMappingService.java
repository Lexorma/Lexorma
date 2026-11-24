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
import java.util.Map;
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
    private final com.lexorma.data.mapping.repository.SubtypeGroupConversionRepository conversionRepository;
    private final com.lexorma.data.mapping.repository.SubtypeAttributeColumnMappingRepository subtypeAttributeRepository;
    private final jakarta.persistence.EntityManager entityManager;

    /** 논리 모델의 상속 변환 해석 (계층 ID 투영 + 변환 기록 — 모델당 두 번 조회) */
    InheritanceConversions conversions(Long projectId, Long modelId) {
        return InheritanceConversions.ofRows(subtypeService.hierarchy(modelId), conversionRepository.findAllByModelId(projectId, modelId));
    }

    // ------------------------------------------------------------------ 조회

    /** 엔터티 목록 페이지 + 행마다 상속 역할·상속 PK 현황 (M-7 4차 — 페이지 단위 일괄 조회, 엔터티별 조회 없음) */
    @Transactional(readOnly = true)
    public Page<ModelMappingDto.EntityRow> selectEntityRows(AccountDto accountDto, ModelMappingDto.EntitySearch search, Pageable pageable) {
        Page<ModelMappingDto.EntityRow> page = queryRepository.findEntityRows(accountDto.getProjectId(), search, pageable);
        List<ModelMappingDto.EntityRow> rows = withPhysical(accountDto.getProjectId(), withInheritance(accountDto.getProjectId(), page.getContent()));
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, page.getTotalElements());
    }

    /** 매핑 1건과 그 엔터티의 속성 전체(매핑된 컬럼 포함), 아직 연결하지 않은 테이블 컬럼 */
    @Transactional(readOnly = true)
    public ModelMappingDto.Detail selectEntityMapping(AccountDto accountDto, Long mappingId) {
        ModelMappingDto.EntityRow row = mappingId == null ? null : queryRepository.findEntityRow(accountDto.getProjectId(), mappingId);
        if (row == null) throw notFound();
        // A형 루트 매핑 (M-7 상속 A) : 통합 테이블에 들어간 계층 구성원의 직접 속성도 이 매핑 아래 (소유 엔터티와 함께)
        InheritanceConversions conversions = conversions(accountDto.getProjectId(), row.modelId());
        List<Long> owners = conversions.kind(row.entityId()) == InheritanceConversions.Kind.SINGLE_TABLE && conversions.hierarchy().parent(row.entityId()) == null
                ? conversions.hierarchy().subtree(row.entityId()) : List.of(row.entityId());
        ModelMappingDto.EntityRow shown = withPhysical(accountDto.getProjectId(), withInheritance(accountDto.getProjectId(), List.of(row))).get(0);
        return new ModelMappingDto.Detail(shown, queryRepository.findAttributeRows(owners), queryRepository.findUnmappedColumns(row.tableId()));
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

    /**
     * 행들의 물리 매핑 문맥 (M-7 상속 A·C, D-AC-16) : 직접 · A형 통합 · C형 분산 · 미매핑. 모델마다 계층·변환 기록 한 번, A·C 행이 있을 때만 그 계층 엔터티의 매핑을 한 번에 읽는다
     * (행마다 조회하지 않는다). 통합·분산이 실제 매핑으로 성립하지 않으면 valid false (미매핑으로 센다 — 정상으로 위장하지 않는다)
     */
    List<ModelMappingDto.EntityRow> withPhysical(Long projectId, List<ModelMappingDto.EntityRow> rows) {
        if (rows.isEmpty()) return rows;
        java.util.Map<Long, InheritanceConversions> byModel = new java.util.HashMap<>();
        rows.stream().map(ModelMappingDto.EntityRow::modelId).distinct().forEach(m -> byModel.put(m, conversions(projectId, m)));
        java.util.Set<Long> needed = new java.util.TreeSet<>();
        for (ModelMappingDto.EntityRow row : rows) {
            InheritanceConversions c = byModel.get(row.modelId());
            if (c.kind(row.entityId()) == InheritanceConversions.Kind.NONE) continue;
            needed.add(c.hierarchy().root(row.entityId()));
            needed.addAll(c.hierarchy().subtree(c.hierarchy().root(row.entityId())));
        }
        java.util.Map<Long, EntityTableMapping> mappings = new java.util.HashMap<>();
        if (!needed.isEmpty()) mappingRepository.findAllByEntityIds(needed).forEach(m -> mappings.put(m.getEntity().getId(), m));
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        if (!needed.isEmpty()) entityRepository.findAllByIds(new java.util.ArrayList<>(needed), projectId).forEach(e -> names.put(e.getId(), e.getEntityName()));
        List<ModelMappingDto.EntityRow> result = new java.util.ArrayList<>(rows.size());
        for (ModelMappingDto.EntityRow row : rows) {
            InheritanceConversions c = byModel.get(row.modelId());
            ModelMappingDto.EntityRow next = row.withPhysical(physical(c, row, mappings, names));
            // A형 루트 : 속성 매핑 분자(통합 속성 포함)와 같은 범위의 분모 — 계층 전체 직접 속성 수 (기록이 있는 루트만, 목록 페이지의 루트 수만큼 한 번씩)
            if (c.kind(row.entityId()) == InheritanceConversions.Kind.SINGLE_TABLE && c.hierarchy().parent(row.entityId()) == null && row.attributeCount() != null) {
                next = next.withAttributeCount(attributeRepository.countByEntityIds(c.hierarchy().subtree(row.entityId())));
            }
            result.add(next);
        }
        return result;
    }

    /** 엔터티들의 물리 문맥 ([엔터티 ID, 매핑 ID, 테이블 ID] — ERD 매핑 표시). 모델의 계층·기록은 한 번 */
    Map<Long, ModelMappingDto.Physical> physicalOf(Long projectId, Long modelId, List<Long[]> entities) {
        List<ModelMappingDto.EntityRow> rows = entities.stream().map(e -> new ModelMappingDto.EntityRow(e[1], modelId, null, e[0], null, null, null, null, e[2], null, null, 0L, 0L))
                .toList();
        Map<Long, ModelMappingDto.Physical> result = new java.util.HashMap<>();
        withPhysical(projectId, rows).forEach(r -> result.put(r.entityId(), r.physical()));
        return result;
    }

    /** A형 루트에 통합된 구성원 (루트 제외, 계층 순서) */
    List<ModelMappingDto.PhysicalTable> mergedInto(Long projectId, Long modelId, Long rootId) {
        InheritanceConversions c = conversions(projectId, modelId);
        if (c.kind(rootId) != InheritanceConversions.Kind.SINGLE_TABLE || c.hierarchy().parent(rootId) != null) return List.of();
        List<Long> members = c.hierarchy().descendants(rootId);
        Map<Long, String> names = new java.util.HashMap<>();
        if (!members.isEmpty()) entityRepository.findAllByIds(members, projectId).forEach(e -> names.put(e.getId(), e.getEntityName()));
        return members.stream().map(id -> new ModelMappingDto.PhysicalTable(id, names.get(id), null, null, null, null)).toList();
    }

    static ModelMappingDto.Physical physical(InheritanceConversions c, ModelMappingDto.EntityRow row, java.util.Map<Long, EntityTableMapping> mappings, java.util.Map<Long, String> names) {
        InheritanceConversions.Kind kind = c.kind(row.entityId());
        if (kind == InheritanceConversions.Kind.NONE) return new ModelMappingDto.Physical(row.mappingId() == null ? "UNMAPPED" : "DIRECT", null, row.mappingId() != null, null, null, List.of(), null);
        Long root = c.hierarchy().root(row.entityId());
        String rootName = names.get(root);
        java.util.function.Function<Long, ModelMappingDto.PhysicalTable> table = id -> {
            EntityTableMapping m = mappings.get(id);
            return new ModelMappingDto.PhysicalTable(id, names.get(id), m == null ? null : m.getId(), m == null || m.getTable().getSchema() == null ? null : m.getTable().getSchema().getId(),
                    m == null ? null : m.getTable().getId(), m == null ? null : m.getTable().getTableName());
        };
        if (kind == InheritanceConversions.Kind.INCONSISTENT) {
            return new ModelMappingDto.Physical(row.mappingId() == null ? "UNMAPPED" : "DIRECT", null, false, root, rootName, row.mappingId() == null ? List.of() : List.of(table.apply(row.entityId())),
                    "STRATEGY_STRUCTURE_MISMATCH");
        }
        String strategy = kind.name();
        return switch (c.role(row.entityId())) {
            case MERGED -> {
                EntityTableMapping rootMapping = mappings.get(root);
                boolean valid = rootMapping != null && rootMapping.getTable().getId().equals(c.integratedTable(row.entityId())) && row.mappingId() == null;
                yield new ModelMappingDto.Physical("MERGED", strategy, valid, root, rootName, rootMapping == null ? List.of() : List.of(table.apply(root)),
                        valid ? null : row.mappingId() != null ? "STRATEGY_STRUCTURE_MISMATCH" : "INTEGRATED_TABLE_UNMAPPED");
            }
            case DISTRIBUTED -> {
                List<Long> leaves = c.leavesUnder(row.entityId());
                boolean valid = row.mappingId() == null && !leaves.isEmpty() && leaves.stream().allMatch(mappings::containsKey);
                yield new ModelMappingDto.Physical("DISTRIBUTED", strategy, valid, root, rootName, leaves.stream().filter(mappings::containsKey).map(table).toList(),
                        valid ? null : row.mappingId() != null ? "STRATEGY_STRUCTURE_MISMATCH" : "CONCRETE_TABLE_UNMAPPED");
            }
            default -> {
                boolean valid = row.mappingId() != null && (kind != InheritanceConversions.Kind.SINGLE_TABLE || row.tableId().equals(c.integratedTable(row.entityId())));
                yield new ModelMappingDto.Physical(row.mappingId() == null ? "UNMAPPED" : "DIRECT", strategy, valid, root, rootName,
                        row.mappingId() == null ? List.of() : List.of(table.apply(row.entityId())), valid || row.mappingId() == null ? null : "STRATEGY_STRUCTURE_MISMATCH");
            }
        };
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
        ModelMappingDto.EntityRow row = queryRepository.findEntityRow(accountDto.getProjectId(), entityId, tableId);
        return row == null ? null : withPhysical(accountDto.getProjectId(), List.of(row)).get(0);
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
        // 매핑률 (D-AC-16) : 유효한 A형 통합 = 매핑, 유효한 C형 분산 = 분모에서 뺀다 (기록이 없거나 실제 매핑이 모자라면 그대로 미매핑)
        List<ModelMappingDto.Status> result = new java.util.ArrayList<>(status.size());
        for (ModelMappingDto.Status s : status) {
            InheritanceConversions c = conversions(accountDto.getProjectId(), s.modelId());
            if (c.records().isEmpty()) {
                result.add(s);
                continue;
            }
            java.util.Set<Long> members = new java.util.TreeSet<>();
            c.records().forEach(r -> {
                var group = c.hierarchy().group(r.groupId());
                if (group != null) members.addAll(c.hierarchy().subtree(c.hierarchy().root(group.supertypeId())));
            });
            java.util.Map<Long, EntityTableMapping> mappings = new java.util.HashMap<>();
            mappingRepository.findAllByEntityIds(members).forEach(m -> mappings.put(m.getEntity().getId(), m));
            long merged = 0;
            long distributed = 0;
            List<Long> distributedIds = new java.util.ArrayList<>();
            for (Long id : members) {
                EntityTableMapping own = mappings.get(id);
                ModelMappingDto.EntityRow row = new ModelMappingDto.EntityRow(own == null ? null : own.getId(), s.modelId(), null, id, null, null, null, null,
                        own == null ? null : own.getTable().getId(), null, null, 0L, 0L);
                ModelMappingDto.Physical p = physical(c, row, mappings, java.util.Map.of());
                if (!p.valid()) continue;
                if ("MERGED".equals(p.kind())) merged++;
                if ("DISTRIBUTED".equals(p.kind())) {
                    distributed++;
                    distributedIds.add(id);
                }
            }
            long distributedAttributes = distributedIds.isEmpty() ? 0 : attributeRepository.countByEntityIds(distributedIds);
            result.add(s.withStrategies(merged, distributed, distributedAttributes));
        }
        return result;
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
            // A형 통합·C형 분산 엔터티 (M-7 상속 A·C) : 직접 테이블 매핑을 만들면 기록된 물리 구조와 맞지 않는다 — 변환 기록을 먼저 지운다
            InheritanceConversions.Role role = conversions(projectId, entity.getModel().getId()).role(entity.getId());
            if (role == InheritanceConversions.Role.MERGED) throw invalid("엔터티 {0} 은(는) A형 통합 테이블에 들어간 서브타입이라 따로 매핑할 수 없습니다.", entity.getEntityName());
            if (role == InheritanceConversions.Role.DISTRIBUTED) throw invalid("엔터티 {0} 은(는) C형으로 말단 테이블에 분산된 엔터티라 테이블에 매핑할 수 없습니다.", entity.getEntityName());
            mapping = EntityTableMapping.builder().projectId(projectId).entity(entity).build();
        } else {
            // 지금 테이블과 바꿀 테이블을 ID 오름차순으로 잠근다 (M8-C4a, K-01) : 예전에는 "지금 → 새 테이블" 순서라 두 매핑이 테이블을 맞바꾸면 (다른 논리 모델이면) 서로 기다렸다
            mapping = lockMapping(projectId, request.id(), request.tableId());
            // M8-C2 (M8-C2-M01) : 화면이 본 매핑(목록의 mappingModifiedAt)과 같을 때만 바꾼다 — 그 사이 다른 사용자가 바꾼 매핑을 덮어쓰지 않는다 (409)
            com.lexorma.common.jpa.ModelBase.verify(mapping, request.modifiedAt(), mapping.getEntity().getEntityName());
            if (!mapping.getTable().getId().equals(request.tableId()) && attributeMappingRepository.existsByMapping_Id(mapping.getId())) {
                // 기존 속성 매핑을 말없이 남기거나 다른 컬럼으로 옮기지 않는다
                throw invalid("속성 매핑이 있는 엔터티 매핑은 테이블을 바꿀 수 없습니다. 속성 매핑을 먼저 해제해주세요.");
            }
            if (!mapping.getTable().getId().equals(request.tableId()) && subtypeKeyRepository.existsByMapping_Id(mapping.getId())) {
                throw invalid("상속 PK 매핑이 있는 엔터티 매핑은 테이블을 바꿀 수 없습니다. 상속 PK 매핑을 먼저 해제해주세요.");
            }
            if (!mapping.getTable().getId().equals(request.tableId()) && subtypeAttributeRepository.existsByMapping_Id(mapping.getId())) {
                throw invalid("상속 일반 속성 매핑이 있는 엔터티 매핑은 테이블을 바꿀 수 없습니다. 상속 일반 속성 매핑을 먼저 해제해주세요.");
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
        return new ModelMappingDto.EntityMapping(mapping.getId(), mapping.getEntity().getId(), table.getId(), mapping.modifiedAt());
    }

    /** 엔터티 매핑 해제 (M8-C2) : 모델 행 → 테이블 행 잠금 → 다시 읽기 → 화면이 본 매핑(modifiedAt)과 같을 때만 (다르면 409) */
    @Transactional
    public void deleteEntityMapping(AccountDto accountDto, Long mappingId, java.time.LocalDateTime modifiedAt) {
        EntityTableMapping mapping = lockMapping(accountDto.getProjectId(), mappingId);
        com.lexorma.common.jpa.ModelBase.verify(mapping, modifiedAt, mapping.getEntity().getEntityName());
        if (attributeMappingRepository.existsByMapping_Id(mapping.getId())) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "속성 매핑이 있는 엔터티 매핑입니다. 속성 매핑을 먼저 해제해주세요.");
        }
        if (subtypeKeyRepository.existsByMapping_Id(mapping.getId())) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "상속 PK 매핑이 있는 엔터티 매핑입니다. 상속 PK 매핑을 먼저 해제해주세요.");
        }
        if (subtypeAttributeRepository.existsByMapping_Id(mapping.getId())) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "상속 일반 속성 매핑이 있는 엔터티 매핑입니다. 상속 일반 속성 매핑을 먼저 해제해주세요.");
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
            if (attribute == null) throw invalid("매핑된 엔터티 {0} 의 속성만 연결할 수 있습니다.", parent.getEntity().getEntityName());
            if (!attribute.getEntity().getId().equals(parent.getEntity().getId())) {
                // A형 (M-7 상속 A, D-AC-02 5.3) : 루트 매핑 아래에는 같은 통합 계층 구성원의 직접 속성도 — 변환 기록과 계층 소속이 실제로 확인될 때만 (같은 프로젝트라는 이유만으로 허용하지 않는다)
                InheritanceConversions c = conversions(projectId, parent.getEntity().getModel().getId());
                boolean merged = c.sameSingleTableTree(parent.getEntity().getId(), attribute.getEntity().getId())
                        && parent.getTable().getId().equals(c.integratedTable(parent.getEntity().getId()));
                if (!merged) throw invalid("매핑된 엔터티 {0} 의 속성만 연결할 수 있습니다.", parent.getEntity().getEntityName());
            }
            attributeMappingRepository.findByAttribute_Id(attribute.getId()).ifPresent(m -> {
                throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "속성 {0} 은(는) 이미 컬럼 {1} 에 매핑되어 있습니다.", attribute.getAttributeName(), m.getColumn().getColumnName());
            });
            mapping = AttributeColumnMapping.builder().mapping(parent).attribute(attribute).build();
        } else {
            mapping = refreshed(attributeMappingRepository.findById(request.id()).orElseThrow(ModelMappingService::notFound));
            com.lexorma.common.jpa.ModelBase.verify(mapping, request.modifiedAt(), mapping.getAttribute().getAttributeName()); // M8-C2 : 부모 매핑 잠금 뒤
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
        return new ModelMappingDto.AttributeMapping(mapping.getId(), parent.getId(), mapping.getAttribute().getId(), column.getId(), mapping.modifiedAt());
    }

    /** 속성 매핑 해제 (M8-C2) : 부모 매핑 잠금 → 다시 읽기 → 화면이 본 매핑(modifiedAt)과 같을 때만 (다르면 409 — 그 사이 다른 컬럼으로 바뀐 매핑을 지우지 않는다) */
    @Transactional
    public void deleteAttributeMapping(AccountDto accountDto, Long attributeMappingId, java.time.LocalDateTime modifiedAt) {
        Long projectId = accountDto.getProjectId();
        if (attributeMappingId == null) throw notFound();
        lockMapping(projectId, attributeMappingRepository.findParentId(attributeMappingId, projectId).orElseThrow(ModelMappingService::notFound));
        AttributeColumnMapping mapping = refreshed(attributeMappingRepository.findById(attributeMappingId).orElseThrow(ModelMappingService::notFound));
        com.lexorma.common.jpa.ModelBase.verify(mapping, modifiedAt, mapping.getAttribute().getAttributeName());
        attributeMappingRepository.delete(mapping);
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
                    state == null || state.parentColumn() == null ? null : state.parentColumn().getColumnName(), "UNMAPPED", null) : keyRow(link, i + 1, true, attribute, state));
        }
        for (SubtypeIdentifierColumnMapping link : links) {
            if (!current.contains(link.getAttribute().getId())) keys.add(keyRow(link, 0, false, link.getAttribute(), null));
        }
        Long parentId = hierarchy.parent(entity.getId());
        Long rootId = hierarchy.root(entity.getId());
        boolean complete = !rootKey.isEmpty() && rootKey.stream().allMatch(a -> byAttribute.containsKey(a.getId()));
        if (conversions(projectId, entity.getModel().getId()).kind(entity.getId()) == InheritanceConversions.Kind.TABLE_PER_CONCRETE) {
            // C형 말단 : 상속 FK·직계 상위 테이블이 없는 것이 정상이다 — B형 상속 구조 판정을 내지 않는다 (구조는 정합성 비교 [상속 구조] 의 C형 판정)
            return new ModelMappingDto.SubtypeKeyDetail(row, true, parentId, context.entity(parentId).getEntityName(), rootId, context.entity(rootId).getEntityName(),
                    "CONCRETE", Messages.get("C형 말단 테이블 : 루트 {0} 의 주 식별자를 이 테이블의 PK 로 복제합니다. 상속 FK 와 상위 테이블은 없습니다 (구조는 정합성 비교의 [상속 구조] 에서 C형 기준으로 판정).",
                    context.entity(rootId).getEntityName()), complete, keys, null);
        }
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
            mapping = refreshed(subtypeKeyRepository.findById(request.id()).orElseThrow(ModelMappingService::notFound));
            com.lexorma.common.jpa.ModelBase.verify(mapping, request.modifiedAt(), mapping.getAttribute().getAttributeName()); // M8-C2 : 부모 매핑 잠금 뒤
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
        return new ModelMappingDto.SubtypeKeyMapping(mapping.getId(), parent.getId(), mapping.getAttribute().getId(), column.getId(), mapping.modifiedAt());
    }

    /** 상속 PK 매핑 해제 : 매핑만 지운다 (물리 PK·FK·컬럼과 논리 식별자는 그대로). 정합하지 않게 된 매핑도 해제할 수 있다 */
    @Transactional
    public void deleteSubtypeKeyMapping(AccountDto accountDto, Long id, java.time.LocalDateTime modifiedAt) {
        Long projectId = accountDto.getProjectId();
        if (id == null) throw notFound();
        lockMapping(projectId, subtypeKeyRepository.findParentId(id, projectId).orElseThrow(ModelMappingService::notFound));
        SubtypeIdentifierColumnMapping mapping = refreshed(subtypeKeyRepository.findById(id).orElseThrow(ModelMappingService::notFound));
        com.lexorma.common.jpa.ModelBase.verify(mapping, modifiedAt, mapping.getAttribute().getAttributeName()); // M8-C2
        subtypeKeyRepository.delete(mapping);
    }

    private static ModelMappingDto.SubtypeKeyRow keyRow(SubtypeIdentifierColumnMapping link, int order, boolean current, LogicalAttribute attribute, SubtypePlanContext.KeyState state) {
        ColumnInfo column = link.getColumn();
        return new ModelMappingDto.SubtypeKeyRow(link.getId(), order, current, attribute.getId(), attribute.getAttributeName(), attribute.getEntity().getId(),
                attribute.getEntity().getEntityName(), column.getId(), column.getColumnName(), column.getDataType(), column.getMaxLength(), column.getPrecision(),
                column.getScale(), column.getIsPk(), termName(attribute), attributeType(attribute), com.lexorma.data.table.ddl.TableDdlGenerator.isNotNull(column),
                state == null || state.parentColumn() == null ? null : state.parentColumn().getColumnName(), !current ? "STALE" : state == null ? null : state.state(), link.modifiedAt());
    }

    /** 다른 상속 PK 매핑이 쓰는 컬럼은 속성 매핑·상속 PK 매핑에 다시 쓸 수 없다 (self : 바꾸는 상속 PK 매핑 자신). 상속 일반 속성 매핑에 쓰인 컬럼도 같다 */
    private void rejectInheritedKeyColumn(ColumnInfo column, Long self) {
        subtypeKeyRepository.findByColumn_Id(column.getId()).filter(m -> !m.getId().equals(self)).ifPresent(m -> {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "컬럼 {0} 은(는) 이미 속성 {1} 의 상속 PK 매핑에 쓰이고 있습니다.", column.getColumnName(), m.getAttribute().getAttributeName());
        });
        rejectInheritedAttributeColumn(column, null);
    }

    /** 상속 일반 속성 매핑(C형)이 쓰는 컬럼은 다른 매핑에 쓸 수 없다 (self : 바꾸는 상속 일반 속성 매핑 자신) */
    private void rejectInheritedAttributeColumn(ColumnInfo column, Long self) {
        subtypeAttributeRepository.findByColumn_Id(column.getId()).filter(m -> !m.getId().equals(self)).ifPresent(m -> {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "컬럼 {0} 은(는) 이미 속성 {1} 의 상속 일반 속성 매핑에 쓰이고 있습니다.", column.getColumnName(), m.getAttribute().getAttributeName());
        });
    }

    // ------------------------------------------------------------------ C형 상속 일반 속성 매핑 (M-7 상속 C)

    /** C형 말단 매핑의 상속 일반 속성 매핑 : 지금 조상의 일반 속성마다 1행(루트 → 직계 부모, 속성 순서), 그 뒤 지금 조상 속성이 아닌 기존 매핑 */
    @Transactional(readOnly = true)
    public ModelMappingDto.SubtypeAttributeDetail selectSubtypeAttributes(AccountDto accountDto, Long mappingId) {
        Long projectId = accountDto.getProjectId();
        ModelMappingDto.EntityRow row = mappingId == null ? null : queryRepository.findEntityRow(projectId, mappingId);
        if (row == null) throw notFound();
        EntityTableMapping mapping = mappingRepository.findByIdAndProjectId(mappingId, projectId).orElseThrow(ModelMappingService::notFound);
        LogicalEntity entity = mapping.getEntity();
        InheritanceConversions c = conversions(projectId, entity.getModel().getId());
        List<com.lexorma.data.mapping.domain.entity.SubtypeAttributeColumnMapping> links = subtypeAttributeRepository.findAllByMappingId(mappingId);
        java.util.Map<Long, com.lexorma.data.mapping.domain.entity.SubtypeAttributeColumnMapping> byAttribute = new java.util.HashMap<>();
        links.forEach(l -> byAttribute.put(l.getAttribute().getId(), l));
        row = withPhysical(projectId, withInheritance(projectId, List.of(row))).get(0);
        boolean concrete = c.kind(entity.getId()) == InheritanceConversions.Kind.TABLE_PER_CONCRETE && c.leaf(entity.getId());
        List<ModelMappingDto.SubtypeAttributeRow> rows = new java.util.ArrayList<>();
        java.util.Set<Long> current = new java.util.HashSet<>();
        if (concrete) {
            java.util.Map<Long, LogicalEntity> owners = new java.util.HashMap<>();
            entityRepository.findAllByIds(c.hierarchy().ancestors(entity.getId()), projectId).forEach(e -> owners.put(e.getId(), e));
            LogicalEntity root = owners.get(c.hierarchy().root(entity.getId()));
            java.util.Set<Long> rootKey = root == null ? java.util.Set.of() : PhysicalGenerationService.primaryAttributeIds(root);
            for (Long ownerId : c.hierarchy().ancestors(entity.getId()).reversed()) {
                LogicalEntity owner = owners.get(ownerId);
                if (owner == null) continue;
                for (LogicalAttribute attribute : PhysicalGenerationService.columnOrder(owner)) {
                    if (rootKey.contains(attribute.getId())) continue; // 상속 PK 는 상속 PK 매핑에서
                    current.add(attribute.getId());
                    var link = byAttribute.get(attribute.getId());
                    rows.add(attributeRow(link, true, attribute, owner, mapping));
                }
            }
        }
        for (var link : links) if (!current.contains(link.getAttribute().getId())) rows.add(attributeRow(link, false, link.getAttribute(), link.getAttribute().getEntity(), mapping));
        String message = concrete ? null : links.isEmpty() ? Messages.get("엔터티 {0} 은(는) C형 계층의 말단이 아니라 상속 일반 속성 매핑 대상이 아닙니다.", entity.getEntityName())
                : Messages.get("엔터티 {0} 은(는) 지금 C형 계층의 말단이 아닙니다. 상속 일반 속성 매핑을 해제할 수 있습니다.", entity.getEntityName());
        return new ModelMappingDto.SubtypeAttributeDetail(row, concrete, concrete ? InheritanceConversions.Kind.TABLE_PER_CONCRETE.name() : null, rows, message);
    }

    private static ModelMappingDto.SubtypeAttributeRow attributeRow(com.lexorma.data.mapping.domain.entity.SubtypeAttributeColumnMapping link, boolean current,
                                                                    LogicalAttribute attribute, LogicalEntity owner, EntityTableMapping mapping) {
        ColumnInfo column = link == null ? null : link.getColumn();
        String check = !current ? "STALE" : column == null ? "UNMAPPED"
                : column.getTableInfo() == null || !column.getTableInfo().getId().equals(mapping.getTable().getId()) ? "OTHER_TABLE" : "OK";
        return new ModelMappingDto.SubtypeAttributeRow(link == null ? null : link.getId(), current, attribute.getId(), attribute.getAttributeName(), owner.getId(), owner.getEntityName(),
                column == null ? null : column.getId(), column == null ? null : column.getColumnName(), column == null ? null : column.getDataType(),
                column == null ? null : column.getMaxLength(), termName(attribute), attributeType(attribute), attribute.getIsRequired(),
                column == null ? null : com.lexorma.data.table.ddl.TableDdlGenerator.isNotNull(column), check, link == null ? null : link.modifiedAt());
    }

    /**
     * 상속 일반 속성 매핑 등록·변경 (D-AC-02 5.4·5.5). 등록 : 지금 C형 계층 말단의 매핑, 지금 조상의 일반 속성(루트 주 식별자 제외), 그 매핑 테이블의 컬럼,
     * (매핑, 속성)·컬럼 중복 없음, 다른 매핑(속성·상속 PK)에 쓰인 컬럼이 아님. 변경 : 컬럼만. 컬럼명으로 추론하지 않는다
     */
    @Transactional
    public ModelMappingDto.SubtypeAttributeMapping saveSubtypeAttributeMapping(AccountDto accountDto, ModelMappingDto.SubtypeAttributeMapping request) {
        Long projectId = accountDto.getProjectId();
        if (request == null) throw invalid("상속 일반 속성 매핑 내용이 없습니다.");
        EntityTableMapping parent = lockMapping(projectId, request.id() == null ? request.mappingId()
                : subtypeAttributeRepository.findParentId(request.id(), projectId).orElseThrow(ModelMappingService::notFound));
        com.lexorma.data.mapping.domain.entity.SubtypeAttributeColumnMapping mapping;
        if (request.id() == null) {
            LogicalEntity entity = parent.getEntity();
            InheritanceConversions c = conversions(projectId, entity.getModel().getId());
            if (c.kind(entity.getId()) != InheritanceConversions.Kind.TABLE_PER_CONCRETE || !c.leaf(entity.getId())) {
                throw invalid("엔터티 {0} 은(는) C형 계층의 말단이 아니라 상속 일반 속성 매핑을 등록할 수 없습니다.", entity.getEntityName());
            }
            LogicalAttribute attribute = request.attributeId() == null ? null : attributeRepository.findById(request.attributeId()).orElse(null);
            if (attribute == null || !c.concreteAncestor(entity.getId(), attribute.getEntity().getId())) {
                throw invalid("엔터티 {0} 의 조상 엔터티 속성만 상속 일반 속성으로 연결할 수 있습니다.", entity.getEntityName());
            }
            LogicalEntity root = entityRepository.findByIdAndProjectId(c.hierarchy().root(entity.getId()), projectId).orElseThrow(ModelMappingService::notFound);
            if (PhysicalGenerationService.primaryAttributeIds(root).contains(attribute.getId())) {
                throw invalid("루트 주 식별자 속성 {0} 은(는) 상속 PK 매핑으로 연결해주세요.", attribute.getAttributeName());
            }
            subtypeAttributeRepository.findByMapping_IdAndAttribute_Id(parent.getId(), attribute.getId()).ifPresent(m -> {
                throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "속성 {0} 은(는) 이미 컬럼 {1} 에 상속 일반 속성 매핑되어 있습니다.", attribute.getAttributeName(), m.getColumn().getColumnName());
            });
            mapping = com.lexorma.data.mapping.domain.entity.SubtypeAttributeColumnMapping.builder().mapping(parent).attribute(attribute).build();
        } else {
            mapping = refreshed(subtypeAttributeRepository.findById(request.id()).orElseThrow(ModelMappingService::notFound));
            com.lexorma.common.jpa.ModelBase.verify(mapping, request.modifiedAt(), mapping.getAttribute().getAttributeName()); // M8-C2 : 부모 매핑 잠금 뒤
        }
        ColumnInfo column = request.columnId() == null ? null : columnInfoRepository.findById(request.columnId()).orElse(null);
        if (column == null || column.getTableInfo() == null || !column.getTableInfo().getId().equals(parent.getTable().getId())) {
            throw invalid("매핑된 테이블 {0} 의 컬럼만 연결할 수 있습니다.", parent.getTable().getTableName());
        }
        attributeMappingRepository.findByColumn_Id(column.getId()).ifPresent(m -> {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "컬럼 {0} 은(는) 이미 속성 {1} 에 매핑되어 있어 상속 일반 속성 매핑에 쓸 수 없습니다.", column.getColumnName(), m.getAttribute().getAttributeName());
        });
        subtypeKeyRepository.findByColumn_Id(column.getId()).ifPresent(m -> {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "컬럼 {0} 은(는) 이미 속성 {1} 의 상속 PK 매핑에 쓰이고 있습니다.", column.getColumnName(), m.getAttribute().getAttributeName());
        });
        rejectInheritedAttributeColumn(column, mapping.getId());
        mapping.setColumn(column);
        try {
            subtypeAttributeRepository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS);
        }
        return new ModelMappingDto.SubtypeAttributeMapping(mapping.getId(), parent.getId(), mapping.getAttribute().getId(), column.getId(), mapping.modifiedAt());
    }

    /** 상속 일반 속성 매핑 해제 : 매핑만 지운다 (물리 컬럼·논리 속성 그대로) */
    @Transactional
    public void deleteSubtypeAttributeMapping(AccountDto accountDto, Long id, java.time.LocalDateTime modifiedAt) {
        Long projectId = accountDto.getProjectId();
        if (id == null) throw notFound();
        lockMapping(projectId, subtypeAttributeRepository.findParentId(id, projectId).orElseThrow(ModelMappingService::notFound));
        var mapping = refreshed(subtypeAttributeRepository.findById(id).orElseThrow(ModelMappingService::notFound));
        com.lexorma.common.jpa.ModelBase.verify(mapping, modifiedAt, mapping.getAttribute().getAttributeName()); // M8-C2
        subtypeAttributeRepository.delete(mapping);
    }

    /** 상속 일반 속성 매핑 후보 컬럼 (서버 페이징) : 세 매핑 어디에도 쓰이지 않은 매핑 테이블 컬럼. currentId 의 컬럼은 후보로 둔다 */
    @Transactional(readOnly = true)
    public Page<ModelMappingDto.KeyColumnCandidate> selectSubtypeAttributeCandidates(AccountDto accountDto, Long mappingId, Long currentId, String searchQuery, Pageable pageable) {
        Long projectId = accountDto.getProjectId();
        EntityTableMapping mapping = mappingId == null ? null : mappingRepository.findByIdAndProjectId(mappingId, projectId).orElse(null);
        if (mapping == null) throw notFound();
        if (currentId != null && !subtypeAttributeRepository.findParentId(currentId, projectId).map(mappingId::equals).orElse(false)) throw notFound();
        return queryRepository.findKeyColumnCandidates(mapping.getTable().getId(), null, currentId, searchQuery, pageable);
    }

    // ------------------------------------------------------------------ 변환 기록 (D-AC-18)

    /** 논리 모델의 상속 계층별 변환 기록과 삭제를 막는 매핑 */
    @Transactional(readOnly = true)
    public List<ModelMappingDto.ConversionTree> selectConversions(AccountDto accountDto, Long modelId) {
        Long projectId = accountDto.getProjectId();
        if (modelId == null || modelRepository.findByIdAndProjectId(modelId, projectId).isEmpty()) {
            throw new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("논리 모델"));
        }
        List<com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion> rows = conversionRepository.findAllByModelId(projectId, modelId);
        InheritanceConversions c = InheritanceConversions.ofRows(subtypeService.hierarchy(modelId), rows);
        java.util.Map<Long, List<com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion>> byRoot = new java.util.LinkedHashMap<>();
        for (var row : rows) {
            var group = c.hierarchy().group(row.getGroup().getId());
            Long root = group == null ? row.getGroup().getSupertypeEntity().getId() : c.hierarchy().root(group.supertypeId());
            byRoot.computeIfAbsent(root, k -> new java.util.ArrayList<>()).add(row);
        }
        List<ModelMappingDto.ConversionTree> result = new java.util.ArrayList<>();
        byRoot.forEach((root, list) -> {
            List<String> dependencies = conversionDependencies(projectId, c, root);
            LogicalEntity rootEntity = entityRepository.findByIdAndProjectId(root, projectId).orElse(null);
            result.add(new ModelMappingDto.ConversionTree(root, rootEntity == null ? null : rootEntity.getEntityName(), c.kindOfRoot(root).name(),
                    list.stream().map(r -> new ModelMappingDto.Conversion(r.getId(), r.getGroup().getId(), r.getGroup().getGroupName(), r.getGroup().getSupertypeEntity().getId(),
                            r.getGroup().getSupertypeEntity().getEntityName(), r.getStrategy().name(), r.getTable() == null ? null : r.getTable().getId(),
                            r.getTable() == null ? null : r.getTable().getTableName(), r.getClassificationColumn() == null ? null : r.getClassificationColumn().getId(),
                            r.getClassificationColumn() == null ? null : r.getClassificationColumn().getColumnName())).toList(), dependencies, dependencies.isEmpty(),
                    conversionComposition(list).token()));
        });
        return result;
    }

    /**
     * 상속 계층의 변환 기록 삭제 (D-AC-18) : 관련 매핑(계층 엔터티의 엔터티 매핑, 계층 속성의 속성·상속 PK·상속 일반 속성 매핑)이 남아 있으면 막는다 — 매핑을 먼저 해제한다.
     * 기록만 지운다 (물리 테이블·컬럼·FK·UNIQUE·논리 엔터티를 지우거나 다른 전략으로 바꾸지 않는다). 논리 모델 행을 잠근 뒤 판단한다 (매핑 등록과 직렬화)
     */
    /** 변환 기록 삭제의 구성 기준값 (M8-C2) : 계층의 기록 행 (ID·수정 일시). 기록은 생성 적용만 만든다 (논리 모델 행 NOWAIT — 삭제의 모델 잠금과 직렬화) */
    private static com.lexorma.common.jpa.ModelComposition conversionComposition(List<com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion> records) {
        return com.lexorma.common.jpa.ModelComposition.of().addEntities("conversions", records, com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion::getId);
    }

    /** composition : 목록(selectConversions)이 준 구성 기준값 (M8-C2 — 그 사이 기록이 바뀌었으면 409, null = 내부 호출) */
    @Transactional
    public int deleteConversions(AccountDto accountDto, Long rootEntityId, String composition) {
        Long projectId = accountDto.getProjectId();
        LogicalEntity root = lockEntity(projectId, rootEntityId);
        InheritanceConversions c = conversions(projectId, root.getModel().getId());
        if (c.hierarchy().parent(root.getId()) != null) throw invalid("변환 기록은 상속 계층의 루트 엔터티로 지웁니다.");
        List<Long> groups = c.groupsOfTree(root.getId()).stream().map(com.lexorma.data.logical.service.LogicalHierarchy.Group::id).toList();
        List<com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion> records = conversionRepository.findAllByModelId(projectId, root.getModel().getId()).stream()
                .filter(r -> groups.contains(r.getGroup().getId())).toList();
        if (records.isEmpty()) throw notFound();
        conversionComposition(records).verify(composition, root.getEntityName());
        List<String> dependencies = conversionDependencies(projectId, c, root.getId());
        if (!dependencies.isEmpty()) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "변환 기록에 의존하는 매핑이 있어 지울 수 없습니다. 매핑을 먼저 해제해주세요 : {0}", String.join(", ", dependencies));
        }
        conversionRepository.deleteAll(records);
        conversionRepository.flush();
        return records.size();
    }

    /** 변환 기록 삭제를 막는 매핑 (계층 엔터티의 엔터티 매핑, 계층 속성의 세 종류 매핑) — 사람이 읽는 목록 */
    private List<String> conversionDependencies(Long projectId, InheritanceConversions c, Long root) {
        List<Long> tree = c.hierarchy().subtree(root);
        List<String> result = new java.util.ArrayList<>();
        mappingRepository.findAllByEntityIds(tree).forEach(m -> result.add(Messages.get("엔터티 매핑 {0} ↔ {1}", m.getEntity().getEntityName(), m.getTable().getTableName())));
        long attributes = attributeMappingRepository.countByAttributeEntityIds(tree);
        if (attributes > 0) result.add(Messages.get("속성 매핑 {0}건", attributes));
        long keys = subtypeKeyRepository.countByAttributeEntityIds(tree);
        if (keys > 0) result.add(Messages.get("상속 PK 매핑 {0}건", keys));
        long inherited = subtypeAttributeRepository.countByAttributeEntityIds(tree);
        if (inherited > 0) result.add(Messages.get("상속 일반 속성 매핑 {0}건", inherited));
        return result;
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
        return lockMapping(projectId, mappingId, null);
    }

    /** 논리 모델 행 → 매핑 테이블 행(과 바꿀 테이블 행 — 둘이면 ID 오름차순) 순서로 잠근 뒤 매핑을 다시 읽는다. 바꿀 테이블이 없거나 다른 프로젝트면 그 행은 잠그지 않는다 (뒤에서 대상 없음) */
    private EntityTableMapping lockMapping(Long projectId, Long mappingId, Long otherTableId) {
        if (mappingId == null) throw notFound();
        lockEntity(projectId, mappingRepository.findEntityId(mappingId, projectId).orElseThrow(ModelMappingService::notFound));
        EntityTableMapping mapping = mappingRepository.findByIdAndProjectId(mappingId, projectId).orElseThrow(ModelMappingService::notFound);
        Long current = mapping.getTable().getId();
        java.util.stream.Stream.of(current, otherTableId).filter(java.util.Objects::nonNull).distinct().sorted()
                .forEach(id -> { if (id.equals(current)) lockTable(projectId, id); else tableInfoRepository.findForUpdate(id, projectId); });
        return refreshed(mapping); // 잠근 뒤의 값 (M8-C2 기준값 비교) — 같은 트랜잭션에서 먼저 읽어 둔 행일 수 있다
    }

    /** 잠근 뒤 다시 읽는다 : 그 사이 지워졌으면 대상 없음 */
    private <T> T refreshed(T entity) {
        try {
            entityManager.refresh(entity);
        } catch (jakarta.persistence.EntityNotFoundException gone) {
            throw notFound();
        }
        return entity;
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_MODEL_MAPPING, message, args);
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
    }
}
