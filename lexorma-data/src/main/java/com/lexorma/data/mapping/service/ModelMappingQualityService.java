package com.lexorma.data.mapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Attribute;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.dictionary.service.StandardizationQualityService.DomainComparison;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Counts;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Structure;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.StructureSummary;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.InheritanceSummary;
import com.lexorma.data.mapping.domain.entity.SubtypeIdentifierColumnMapping;
import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Source;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Summary;
import com.lexorma.data.mapping.repository.ModelMappingQueryRepository;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 논리·물리 정합성 비교 (M-7 2차, 조회 전용) : 논리 모델 1개의 매핑된 속성 ↔ 컬럼을 비교한다. 아무것도 고치지 않고 저장하지 않는다.
 * <ul>
 * <li>매핑 여부 : 매핑하지 않은 엔터티·속성은 UNMAPPED (엔터티가 매핑되지 않으면 그 속성도 UNMAPPED)</li>
 * <li>표준 용어 : 양쪽 표준 용어 ID. 같으면 일치, 다르거나 한쪽만 있으면 불일치(사유를 구분), 둘 다 없으면 비교 불가</li>
 * <li>명칭 : 컬럼명 = 논리 속성의 표준 용어 영문약어 (컬럼 등록·M-5 진단과 같은 규칙, 대소문자 무시). 용어·약어가 없으면 비교 불가.
 *     논리명과 물리명의 문자열은 비교하지 않는다</li>
 * <li>데이터 타입·길이 : 논리 속성의 표준 도메인 ↔ 컬럼. M-5 와 같은 비교({@link StandardizationQualityService#compareDomain}).
 *     도메인에 정의되지 않은 항목, 타입이 다른 경우의 길이는 비교 불가. 논리 속성의 데이터 유형(자유 입력)은 쓰지 않는다</li>
 * <li>필수 여부 : 논리 필수(필수 속성 또는 주 식별자 속성) ↔ 컬럼 NOT NULL (DDL 과 같은 기준 : PK / 필수 입력 / NULL 비허용)</li>
 * <li>식별자 : 주 식별자 속성 ↔ PK 컬럼. 엔터티에 주 식별자가 없으면 비교 불가</li>
 * </ul>
 * 요약과 목록은 같은 계산({@link #diagnose})을 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModelMappingQualityService {

    /** 그리드 정렬 필드 (목록에 없는 필드는 무시한다) */
    private static final Map<String, Comparator<Row>> SORTS = Map.of(
            "entityName", Comparator.comparing(Row::entityName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "attributeName", Comparator.comparing(Row::attributeName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "columnName", Comparator.comparing(Row::columnName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "type", Comparator.comparing(Row::type),
            "status", Comparator.comparing(Row::status));

    /** 정렬할 수 있는 화면 필드 (PageableUtil 허용 목록 — 이 맵의 키, 공통 페이징 안정성 개선) */
    public static final java.util.Set<String> SORT_FIELDS = SORTS.keySet();

    private final ModelMappingService mappingService;
    private final ModelMappingQueryRepository queryRepository;
    private final ProjectRepository projectRepository;
    private final com.lexorma.data.logical.service.LogicalSubtypeService subtypeService;
    private final SubtypePlanContexts subtypeContexts;
    private final com.lexorma.data.mapping.repository.SubtypeIdentifierColumnMappingRepository subtypeKeyRepository;
    private final ModelStructureQualityQueryRepository structureRepository;

    public Summary selectSummary(AccountDto accountDto, Long modelId) {
        // 엔터티·속성 수는 매핑 현황과 같은 집계를 쓴다 (없는 모델·다른 프로젝트의 모델은 여기서 거절된다)
        ModelMappingDto.Status status = status(accountDto, modelId);
        Computed computed = compute(accountDto.getProjectId(), new Search(modelId, null, null, null, null, null));
        List<Row> rows = computed.rows();
        // 구조 진단 (M-7 구조 정합성) 은 따로 센다 : 위 네 건수(속성·컬럼 + 상속 구조)의 뜻을 바꾸지 않는다
        List<Row> structure = structureRows(accountDto.getProjectId(), modelId, status.modelName());
        return new Summary(status.modelId(), status.modelName(), status.totalEntities(), status.mappedEntities(), status.unmappedEntities(),
                status.totalAttributes(), status.mappedAttributes(), status.unmappedAttributes(),
                count(rows, CheckStatus.MATCH), count(rows, CheckStatus.MISMATCH), count(rows, CheckStatus.NOT_COMPARABLE), count(rows, CheckStatus.UNMAPPED),
                computed.participants(), computed.inheritance(),
                new StructureSummary(counts(structure, CheckGroup.RELATIONSHIP), counts(structure, CheckGroup.ALTERNATE_KEY), counts(structure, CheckGroup.PHYSICAL_ONLY),
                        structure.stream().filter(r -> r.type() == CheckType.PHYSICAL_FK).count(), structure.stream().filter(r -> r.type() == CheckType.PHYSICAL_UNIQUE).count()));
    }

    private static Counts counts(List<Row> rows, CheckGroup group) {
        List<Row> in = rows.stream().filter(r -> group.includes(r.type())).toList();
        return new Counts(in.size(), count(in, CheckStatus.MATCH), count(in, CheckStatus.MISMATCH), count(in, CheckStatus.NOT_COMPARABLE), count(in, CheckStatus.UNMAPPED));
    }

    /**
     * 구조 진단 행 (M-7 구조 정합성 — 논리 관계 ↔ FK, 보조 식별자 ↔ UNIQUE, 물리 기준 역방향). 모델 단위로 종류별 한 번씩 읽어({@link ModelStructureQualityQueryRepository})
     * 순수 판정({@link ModelStructureQualityJudge})에 넘긴다. 상속 FK 판단용 계층은 상속 구조 진단과 같은 것을 쓴다
     */
    List<Row> structureRows(Long projectId, Long modelId, String modelName) {
        LogicalHierarchy hierarchy = subtypeService.hierarchy(modelId);
        return ModelStructureQualityJudge.judge(new ModelStructureQualityJudge.Input(modelId, modelName,
                structureRepository.entities(projectId, modelId), structureRepository.relationships(projectId, modelId), structureRepository.pairs(projectId, modelId),
                structureRepository.identifiers(projectId, modelId), structureRepository.attributeColumns(projectId, modelId), structureRepository.inheritedKeys(projectId, modelId),
                structureRepository.foreignKeys(projectId, modelId), structureRepository.uniqueConstraints(projectId, modelId), structureRepository.uniqueColumns(projectId, modelId),
                structureRepository.primaryKeyColumns(projectId, modelId), hierarchy::parent, hierarchy::root, hierarchy.participants()));
    }

    /** 구조 진단 행의 엔터티·이름 조건 (목록 조회의 엔터티 ID · 검색 조건과 같은 뜻) */
    private static boolean matches(Row row, Search search) {
        if (search.entityId() != null && !search.entityId().equals(row.entityId())) return false;
        if (search.searchQuery() == null || search.searchQuery().isBlank()) return true;
        String keyword = search.searchQuery().trim().toLowerCase(java.util.Locale.ROOT);
        Structure s = row.structure();
        return switch (search.searchColumn() == null ? "" : search.searchColumn()) {
            case "attributeName" -> contains(s.logicalName(), keyword) || s.columns().stream()
                    .anyMatch(c -> contains(c.logicalAttributeName(), keyword) || contains(c.parentAttributeName(), keyword));
            case "columnName" -> contains(s.physicalName(), keyword) || s.columns().stream()
                    .anyMatch(c -> contains(c.physicalColumnName(), keyword) || contains(c.physicalParentColumnName(), keyword) || contains(c.expectedColumnName(), keyword));
            default -> contains(row.entityName(), keyword);
        };
    }

    public Page<Row> selectRows(AccountDto accountDto, Search search, Pageable pageable) {
        ModelMappingDto.Status model = status(accountDto, search.modelId());
        // shortcut: 모델 1개의 속성을 (모델·엔터티·이름 조건은 DB 에서 걸러) 한 번에 읽어 메모리에서 진단·상태 조건·정렬·페이징한다
        // (타입 매핑이 자바 코드라 SQL 로 걸러낼 수 없다). 프로젝트 전체가 아니라 모델 단위이며, 모델 하나의 속성이 수만 건이 되면 진단 결과 저장을 검토한다
        // 진단 범위(group) : 없으면 예전 목록(속성·컬럼 + 상속 구조). 구조 진단은 범위나 유형으로 고를 때만 계산한다 (M-7 구조 정합성)
        CheckGroup group = search.group();
        boolean legacy = group == null ? search.type() == null || !search.type().structure()
                : group == CheckGroup.ATTRIBUTE || group == CheckGroup.INHERITANCE || group == CheckGroup.ALL;
        List<Row> all = new ArrayList<>(legacy ? rows(accountDto.getProjectId(), search) : List.of());
        if (search.wantsStructure()) {
            structureRows(accountDto.getProjectId(), search.modelId(), model.modelName()).stream().filter(row -> matches(row, search)).forEach(all::add);
        }
        List<Row> rows = all.stream()
                .filter(row -> group == null || group.includes(row.type()))
                .filter(row -> search.type() == null || row.type() == search.type())
                .filter(row -> search.status() == null || row.status() == search.status())
                .sorted(order(pageable.getSort()))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        return new PageImpl<>(rows.subList(from, Math.min(from + pageable.getPageSize(), rows.size())), pageable, rows.size());
    }

    /**
     * 모델 전체의 정합성 행 (예전 목록 + 구조 진단, 조건 없음) — ERD 매핑 표시(M-7 ERD 연계)가 엔터티·속성별로 모은다. 목록·요약과 같은 판정이다.
     * 모델이 프로젝트에 있는지는 호출하는 쪽이 확인한다
     */
    List<Row> modelRows(Long projectId, Long modelId, String modelName) {
        List<Row> all = new ArrayList<>(rows(projectId, new Search(modelId, null, null, null, null, null)));
        all.addAll(structureRows(projectId, modelId, modelName));
        return all;
    }

    private ModelMappingDto.Status status(AccountDto accountDto, Long modelId) {
        if (modelId == null) {
            throw new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("논리 모델"));
        }
        return mappingService.selectStatus(accountDto, modelId).get(0);
    }

    private List<Row> rows(Long projectId, Search search) {
        return compute(projectId, search).rows();
    }

    private record Computed(List<Row> rows, long participants, InheritanceSummary inheritance) {
    }

    private Computed compute(Long projectId, Search search) {
        DbType db = projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
        // 속성명·컬럼명으로 찾을 때는 엔터티 단위 행(속성·컬럼이 없다)을 내지 않는다
        boolean entityRows = search.searchQuery() == null || search.searchQuery().isBlank()
                || !("attributeName".equals(search.searchColumn()) || "columnName".equals(search.searchColumn()));
        // 상속 구조 참여 엔터티 (D-48) : 직접 속성 판정은 그대로 두고 표시만 붙인다 (MISMATCH 로 바꾸지 않는다). 상속 PK·FK 는 INHERITANCE 행이 따로 본다 (M-7 4차)
        LogicalHierarchy hierarchy = subtypeService.hierarchy(search.modelId());
        java.util.Set<Long> inheritance = hierarchy.participants();
        List<Source> sources = queryRepository.findQualitySources(projectId, search);
        List<Row> rows = diagnose(db, sources, queryRepository.findPrimaryIdentifierAttributes(projectId, search.modelId()), entityRows);
        if (!inheritance.isEmpty()) rows = rows.stream().map(row -> row.withInheritance(inheritance.contains(row.entityId()))).toList();
        return inheritanceRows(projectId, db, hierarchy, search, sources, rows, entityRows);
    }

    /**
     * 상속 구조 행 (M-7 4차) : 매핑된 서브타입마다 구조 1행({@link SubtypePlanContext#inspect} — 기존 부모 재사용·상속 PK 매핑 상세와 같은 판정)과
     * 유효하지 않은 기존 상속 PK 매핑마다 1행. 상속 PK 매핑은 모델 단위 한 번, 판정 재료는 {@link SubtypePlanContext#prepare} 가 종류별로 한 번에 읽는다.
     * 행은 그 엔터티의 다른 진단 뒤에 둔다 (조회 순서 유지)
     */
    private Computed inheritanceRows(Long projectId, DbType db, LogicalHierarchy hierarchy, Search search, List<Source> sources, List<Row> rows, boolean entityRows) {
        List<SubtypeIdentifierColumnMapping> links = subtypeKeyRepository.findAllByModelId(projectId, search.modelId());
        long subtypeCount = hierarchy.participants().stream().filter(id -> hierarchy.parent(id) != null).count();
        if (hierarchy.isEmpty() && links.isEmpty()) {
            return new Computed(rows, 0, new InheritanceSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
        Map<Long, List<SubtypeIdentifierColumnMapping>> linksByMapping = new java.util.HashMap<>();
        links.forEach(l -> linksByMapping.computeIfAbsent(l.getMapping().getId(), k -> new ArrayList<>()).add(l));
        Map<Long, Source> firstSource = new java.util.LinkedHashMap<>();
        sources.forEach(source -> firstSource.putIfAbsent(source.entityId(), source));
        List<Long> mappedSubtypes = firstSource.values().stream().filter(source -> source.mappingId() != null && hierarchy.parent(source.entityId()) != null)
                .map(Source::entityId).toList();
        SubtypePlanContext context = subtypeContexts.lightForModel(projectId, search.modelId(), hierarchy, db);
        context.prepare(mappedSubtypes);
        String keyword = search.searchQuery() == null || search.searchQuery().isBlank() ? null : search.searchQuery().trim().toLowerCase(java.util.Locale.ROOT);
        Map<Long, List<Row>> extra = new java.util.HashMap<>();
        long requiredKeys = 0, mappedKeys = 0, invalid = 0, valid = 0, mismatch = 0, unmapped = 0, notComparable = 0;
        for (Source source : firstSource.values()) {
            if (source.mappingId() == null) continue;
            List<SubtypeIdentifierColumnMapping> own = linksByMapping.getOrDefault(source.mappingId(), List.of());
            boolean subtype = hierarchy.parent(source.entityId()) != null;
            if (!subtype && own.isEmpty()) continue;
            List<Row> list = extra.computeIfAbsent(source.entityId(), k -> new ArrayList<>());
            java.util.Set<Long> current = new HashSet<>();
            if (subtype) {
                SubtypePlanContext.Inspection inspection = context.inspect(source.entityId());
                List<com.lexorma.data.logical.domain.entity.LogicalAttribute> key = context.rootKey(source.entityId());
                key.forEach(a -> current.add(a.getId()));
                requiredKeys += key.size();
                mappedKeys += own.stream().filter(l -> current.contains(l.getAttribute().getId())).count();
                CheckStatus status = CheckStatus.valueOf(inspection.result());
                switch (status) {
                    case MATCH -> valid++;
                    case MISMATCH -> mismatch++;
                    case UNMAPPED -> unmapped++;
                    default -> notComparable++;
                }
                if (entityRows) {
                    String reason = inspection.checks().stream().filter(c -> !SubtypePlanContext.OK.equals(c.level()) && !SubtypePlanContext.WARNING.equals(c.level()))
                            .map(SubtypePlanContext.Check::message).findFirst().orElse(null);
                    list.add(new Row(CheckType.INHERITANCE + ":" + source.entityId() + ":", source.modelId(), source.modelName(), source.mappingId(), source.entityId(),
                            source.entityName(), null, null, source.schemaId(), source.schemaName(), source.tableId(), source.tableName(), null, null, CheckType.INHERITANCE, status,
                            Messages.get("상속 PK {0}개 중 {1}개 연결", key.size(), own.stream().filter(l -> current.contains(l.getAttribute().getId())).count()),
                            inspection.tableKey().isEmpty() ? "-" : "PK (" + String.join(", ", inspection.tableKey().stream().map(SubtypePlanContext.KeyCol::name).toList()) + ")",
                            reason, true));
                }
            }
            for (SubtypeIdentifierColumnMapping link : own) {
                if (current.contains(link.getAttribute().getId())) continue;
                invalid++;
                if (keyword != null && !entityRows && !(contains(link.getAttribute().getAttributeName(), keyword) || contains(link.getColumn().getColumnName(), keyword))) continue;
                list.add(new Row(CheckType.INHERITANCE + ":" + source.entityId() + ":" + link.getAttribute().getId(), source.modelId(), source.modelName(), source.mappingId(),
                        source.entityId(), source.entityName(), link.getAttribute().getId(), link.getAttribute().getAttributeName(), source.schemaId(), source.schemaName(),
                        source.tableId(), source.tableName(), link.getColumn().getId(), link.getColumn().getColumnName(), CheckType.INHERITANCE, CheckStatus.MISMATCH,
                        Messages.get("유효하지 않은 기존 상속 PK 매핑"), link.getColumn().getColumnName(),
                        subtype ? Messages.get("지금 루트 주 식별자에 없는 속성의 기존 상속 PK 매핑입니다. 매핑 관리에서 해제할 수 있습니다.")
                                : Messages.get("엔터티가 지금 서브타입이 아닌데 남은 상속 PK 매핑입니다. 매핑 관리에서 해제할 수 있습니다."), true));
            }
        }
        // 속성명·컬럼명으로 찾으면 조회 결과에 없는 엔터티(그 이름의 속성이 없는 서브타입)의 기존 매핑도 이름으로 찾는다 — 매핑에서 엔터티·테이블을 읽는다
        if (!entityRows && keyword != null) {
            java.util.Set<Long> visited = new HashSet<>();
            firstSource.values().forEach(source -> { if (source.mappingId() != null) visited.add(source.mappingId()); });
            for (Map.Entry<Long, List<SubtypeIdentifierColumnMapping>> entry : linksByMapping.entrySet()) {
                if (visited.contains(entry.getKey())) continue;
                for (SubtypeIdentifierColumnMapping link : entry.getValue()) {
                    com.lexorma.data.mapping.domain.entity.EntityTableMapping m = link.getMapping();
                    if (search.entityId() != null && !search.entityId().equals(m.getEntity().getId())) continue;
                    boolean subtype = hierarchy.parent(m.getEntity().getId()) != null;
                    if (subtype && context.rootKey(m.getEntity().getId()).stream().anyMatch(a -> a.getId().equals(link.getAttribute().getId()))) continue;
                    if (!(contains(link.getAttribute().getAttributeName(), keyword) || contains(link.getColumn().getColumnName(), keyword))) continue;
                    com.lexorma.data.table.domain.entity.TableInfo table = m.getTable();
                    extra.computeIfAbsent(m.getEntity().getId(), k -> new ArrayList<>()).add(new Row(CheckType.INHERITANCE + ":" + m.getEntity().getId() + ":" + link.getAttribute().getId(),
                            m.getEntity().getModel().getId(), m.getEntity().getModel().getModelName(), m.getId(), m.getEntity().getId(), m.getEntity().getEntityName(),
                            link.getAttribute().getId(), link.getAttribute().getAttributeName(), table.getSchema() == null ? null : table.getSchema().getId(),
                            table.getSchema() == null ? null : table.getSchema().getSchemaName(), table.getId(), table.getTableName(), link.getColumn().getId(),
                            link.getColumn().getColumnName(), CheckType.INHERITANCE, CheckStatus.MISMATCH, Messages.get("유효하지 않은 기존 상속 PK 매핑"), link.getColumn().getColumnName(),
                            subtype ? Messages.get("지금 루트 주 식별자에 없는 속성의 기존 상속 PK 매핑입니다. 매핑 관리에서 해제할 수 있습니다.")
                                    : Messages.get("엔터티가 지금 서브타입이 아닌데 남은 상속 PK 매핑입니다. 매핑 관리에서 해제할 수 있습니다."), true));
                }
            }
        }
        InheritanceSummary summary = new InheritanceSummary(subtypeCount, mappedSubtypes.size(), requiredKeys, mappedKeys, requiredKeys - mappedKeys, invalid,
                valid, mismatch, unmapped, notComparable);
        if (extra.isEmpty()) return new Computed(rows, hierarchy.participants().size(), summary);
        // 엔터티의 마지막 진단 행 뒤에 넣는다. 진단 행이 없는 엔터티(속성이 모두 상속 PK 인 서브타입 등)는 조회 순서 자리에 둔다
        Map<Long, List<Row>> byEntity = new java.util.LinkedHashMap<>();
        firstSource.keySet().forEach(id -> byEntity.put(id, new ArrayList<>()));
        extra.keySet().stream().filter(id -> !byEntity.containsKey(id)).sorted().forEach(id -> byEntity.put(id, new ArrayList<>()));
        rows.forEach(row -> byEntity.computeIfAbsent(row.entityId(), k -> new ArrayList<>()).add(row));
        List<Row> merged = new ArrayList<>(rows.size() + extra.size());
        byEntity.forEach((id, list) -> {
            merged.addAll(list);
            merged.addAll(extra.getOrDefault(id, List.of()));
        });
        return new Computed(merged, hierarchy.participants().size(), summary);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(keyword);
    }

    private static long count(List<Row> rows, CheckStatus status) {
        return rows.stream().filter(row -> row.status() == status).count();
    }

    /** 요청한 정렬(허용 필드만). 같은 값은 조회 순서(엔터티명 → 속성 순서 → 진단 유형)를 유지한다 (안정 정렬) */
    private static Comparator<Row> order(Sort sort) {
        Comparator<Row> order = (a, b) -> 0;
        for (Sort.Order requested : sort) {
            Comparator<Row> comparator = SORTS.get(requested.getProperty());
            if (comparator != null) {
                order = order.thenComparing(requested.isAscending() ? comparator : comparator.reversed());
            }
        }
        return order;
    }

    // ------------------------------------------------------------------ 판정

    /**
     * sources 순서를 유지한다. 매핑하지 않은 엔터티 1행(entityRows 일 때) + 그 속성마다 1행, 매핑된 엔터티의 매핑하지 않은 속성 1행,
     * 매핑된 속성은 진단 유형(MAPPING 제외)마다 1행.
     *
     * @param primaryIdentifiers 엔터티 ID → 주 식별자 속성 ID (주 식별자가 없는 엔터티는 키가 없다)
     */
    static List<Row> diagnose(DbType db, List<Source> sources, Map<Long, Set<Long>> primaryIdentifiers, boolean entityRows) {
        List<Row> rows = new ArrayList<>();
        Set<Long> unmappedEntities = new HashSet<>();
        for (Source source : sources) {
            if (source.mappingId() == null) {
                if (entityRows && unmappedEntities.add(source.entityId())) {
                    rows.add(row(source, false, CheckType.MAPPING, CheckStatus.UNMAPPED, null, null, Messages.get("엔터티가 물리 테이블에 매핑되지 않았습니다.")));
                }
                if (source.attributeId() != null) {
                    rows.add(row(source, true, CheckType.MAPPING, CheckStatus.UNMAPPED, null, null, Messages.get("엔터티가 매핑되지 않아 속성도 매핑되지 않았습니다.")));
                }
            } else if (source.attributeId() != null && source.attributeMappingId() == null) {
                rows.add(row(source, true, CheckType.MAPPING, CheckStatus.UNMAPPED, null, null, Messages.get("속성이 물리 컬럼에 매핑되지 않았습니다.")));
            } else if (source.attributeId() != null) {
                Set<Long> primary = primaryIdentifiers.get(source.entityId());
                rows.add(term(source));
                rows.add(name(source));
                domain(db, source, rows);
                rows.add(required(source, primary != null && primary.contains(source.attributeId())));
                rows.add(identifier(source, primary));
            }
        }
        return rows;
    }

    /** 표준 용어는 프로젝트 단위 데이터이고 속성·컬럼 모두 같은 프로젝트의 용어만 참조하므로 ID 로 비교한다 (이름이 같아도 다른 용어는 다르다) */
    private static Row term(Source s) {
        if (s.attributeTermId() == null && s.columnTermId() == null) {
            return row(s, true, CheckType.TERM, CheckStatus.NOT_COMPARABLE, null, null, Messages.get("양쪽 모두 표준 용어가 연결되지 않았습니다."));
        }
        if (s.attributeTermId() == null) {
            return row(s, true, CheckType.TERM, CheckStatus.MISMATCH, null, s.columnTermName(), Messages.get("물리 컬럼에만 표준 용어가 연결되어 있습니다."));
        }
        if (s.columnTermId() == null) {
            return row(s, true, CheckType.TERM, CheckStatus.MISMATCH, s.attributeTermName(), null, Messages.get("논리 속성에만 표준 용어가 연결되어 있습니다."));
        }
        boolean same = s.attributeTermId().equals(s.columnTermId());
        return row(s, true, CheckType.TERM, same ? CheckStatus.MATCH : CheckStatus.MISMATCH, s.attributeTermName(), s.columnTermName(),
                same ? null : Messages.get("논리 속성과 물리 컬럼의 표준 용어가 다릅니다."));
    }

    /**
     * 논리명(회원번호)과 물리명(MBR_ID)의 문자열은 비교하지 않는다. 물리명 규칙은 "컬럼명 = 표준 용어의 영문약어" 하나뿐이고,
     * 기대 컬럼명은 물리명 추천({@link PhysicalNameRecommendationService#recommendColumnName})이 계산한다 (추천과 진단이 같은 이름을 쓴다).
     * 추천할 수 없으면 불일치가 아니라 비교 불가다. 추천명 충돌은 명칭 일치 여부와 다른 개념이라 여기서 보지 않는다
     */
    private static Row name(Source s) {
        PhysicalNameRecommendationService.ColumnName expected = PhysicalNameRecommendationService.recommendColumnName(s.attributeTermId(), s.attributeTermAbbreviation());
        if (!expected.available()) {
            return row(s, true, CheckType.NAME, CheckStatus.NOT_COMPARABLE, null, s.columnName(), expected.unavailableReason());
        }
        boolean same = PhysicalNameRecommendationService.sameName(s.columnName(), expected.name());
        return row(s, true, CheckType.NAME, same ? CheckStatus.MATCH : CheckStatus.MISMATCH, expected.name(), s.columnName(),
                same ? null : Messages.get("컬럼명이 논리 속성의 표준 용어 영문약어와 다릅니다."));
    }

    /** 데이터 타입 1행 + 길이·정밀도 1행 */
    private static void domain(DbType db, Source s, List<Row> rows) {
        String blocked = s.attributeTermId() == null ? Messages.get("논리 속성에 표준 용어가 없어 표준 도메인을 알 수 없습니다.")
                : s.domainId() == null ? Messages.get("논리 속성의 표준 용어에 표준 도메인이 없습니다.")
                : s.columnDataType() == null || s.columnDataType().isBlank() ? Messages.get("물리 컬럼에 데이터 타입이 없습니다.") : null;
        if (blocked != null) {
            rows.add(row(s, true, CheckType.DATA_TYPE, CheckStatus.NOT_COMPARABLE, null, s.columnDataType(), blocked));
            rows.add(row(s, true, CheckType.LENGTH, CheckStatus.NOT_COMPARABLE, null, s.columnDataType(), blocked));
            return;
        }
        DomainComparison comparison = StandardizationQualityService.compareDomain(db, s.columnDataType(), s.columnMaxLength(), s.columnPrecision(), s.columnScale(),
                s.domainDataType(), s.domainLength(), s.domainDecimalLength());
        boolean domainHasType = s.domainDataType() != null && !s.domainDataType().isBlank();
        boolean typeDiffers = comparison.attributes().contains(Attribute.DATA_TYPE);
        String logicalType = TableDdlGenerator.baseType(comparison.expected());
        String physicalType = TableDdlGenerator.baseType(comparison.actual());
        if (!domainHasType) {
            rows.add(row(s, true, CheckType.DATA_TYPE, CheckStatus.NOT_COMPARABLE, null, physicalType, Messages.get("표준 도메인에 데이터 타입이 정의되지 않았습니다.")));
        } else {
            rows.add(row(s, true, CheckType.DATA_TYPE, typeDiffers ? CheckStatus.MISMATCH : CheckStatus.MATCH, logicalType, physicalType,
                    typeDiffers ? Messages.get("표준 도메인의 데이터 타입과 컬럼의 데이터 타입이 다릅니다.") : null));
        }

        // 길이 0 은 미정의다 (M-5 와 같다)
        boolean domainHasLength = (s.domainLength() != null && s.domainLength() != 0) || s.domainDecimalLength() != null;
        if (typeDiffers) {
            rows.add(row(s, true, CheckType.LENGTH, CheckStatus.NOT_COMPARABLE, null, null, Messages.get("데이터 타입이 달라 길이·정밀도를 비교하지 않습니다.")));
        } else if (!domainHasLength) {
            rows.add(row(s, true, CheckType.LENGTH, CheckStatus.NOT_COMPARABLE, null, comparison.actual(), Messages.get("표준 도메인에 길이·정밀도가 정의되지 않았습니다.")));
        } else {
            boolean differs = !comparison.attributes().isEmpty();
            rows.add(row(s, true, CheckType.LENGTH, differs ? CheckStatus.MISMATCH : CheckStatus.MATCH, comparison.expected(), comparison.actual(),
                    differs ? Messages.get("표준 도메인 정의와 다릅니다 : {0}", String.join(", ", comparison.attributes().stream().map(ModelMappingQualityService::label).toList())) : null));
        }
    }

    /** 논리 필수 = 필수 속성 또는 주 식별자 속성 (논리 ERD 의 필수 표시와 같다). 물리 NOT NULL = DDL 생성 기준(PK / 필수 입력 / NULL 비허용) */
    private static Row required(Source s, boolean primaryIdentifier) {
        boolean logical = Boolean.TRUE.equals(s.attributeRequired()) || primaryIdentifier;
        boolean physical = Boolean.TRUE.equals(s.columnPk()) || Boolean.TRUE.equals(s.columnRequired()) || Boolean.FALSE.equals(s.columnNullable());
        return row(s, true, CheckType.REQUIRED, logical == physical ? CheckStatus.MATCH : CheckStatus.MISMATCH,
                logical ? Messages.get("필수") : Messages.get("선택"), physical ? "NOT NULL" : "NULL",
                logical == physical ? null : logical ? Messages.get("필수 속성인데 컬럼이 NULL 을 허용합니다.") : Messages.get("선택 속성인데 컬럼이 NOT NULL 입니다."));
    }

    /**
     * 주 식별자 = PK 를 강제하는 정책은 없다 (대리 키처럼 의도한 차이가 있을 수 있다). 구성이 다른 것만 알려 준다.
     * 엔터티에 주 식별자가 없으면 기준이 없으므로 비교하지 않는다. 대체 식별자·UNIQUE 는 보지 않는다
     */
    private static Row identifier(Source s, Set<Long> primary) {
        boolean physical = Boolean.TRUE.equals(s.columnPk());
        String physicalValue = physical ? "PK" : "-";
        if (primary == null) {
            return row(s, true, CheckType.IDENTIFIER, CheckStatus.NOT_COMPARABLE, null, physicalValue, Messages.get("엔터티에 주 식별자가 없습니다."));
        }
        boolean logical = primary.contains(s.attributeId());
        return row(s, true, CheckType.IDENTIFIER, logical == physical ? CheckStatus.MATCH : CheckStatus.MISMATCH,
                logical ? Messages.get("주 식별자") : "-", physicalValue,
                logical == physical ? null : logical ? Messages.get("주 식별자 속성인데 컬럼이 PK 가 아닙니다.") : Messages.get("PK 컬럼인데 속성이 주 식별자가 아닙니다."));
    }

    private static String label(Attribute attribute) {
        return switch (attribute) {
            case DATA_TYPE -> Messages.get("데이터 타입");
            case LENGTH -> Messages.get("길이");
            case PRECISION -> Messages.get("정밀도");
            case SCALE -> Messages.get("소수 자릿수");
        };
    }

    /** attribute = false 는 엔터티 단위 행 (속성·컬럼 값을 비운다) */
    private static Row row(Source s, boolean attribute, CheckType type, CheckStatus status, String logicalValue, String physicalValue, String reason) {
        Long attributeId = attribute ? s.attributeId() : null;
        return new Row(type + ":" + s.entityId() + ":" + (attributeId == null ? "" : attributeId), s.modelId(), s.modelName(), s.mappingId(), s.entityId(), s.entityName(),
                attributeId, attribute ? s.attributeName() : null,
                s.schemaId(), s.schemaName(), s.tableId(), s.tableName(), attribute ? s.columnId() : null, attribute ? s.columnName() : null,
                type, status, logicalValue, physicalValue, reason);
    }
}
