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
import java.util.HashMap;
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
    private final com.lexorma.data.mapping.repository.SubtypeAttributeColumnMappingRepository subtypeAttributeRepository;
    private final com.lexorma.data.mapping.repository.EntityTableMappingRepository entityMappingRepository;
    private final com.lexorma.data.logical.repository.LogicalSubtypeGroupRepository subtypeGroupRepository;
    private final com.lexorma.data.logical.repository.LogicalSubtypeRepository subtypeRepository;
    private final com.lexorma.data.history.repository.ModelChangeHistoryRepository historyRepository;

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

    /** 논리 모델의 상속 변환 해석 (보정 기능이 A·C 영향 대상을 막는 데 쓴다) */
    InheritanceConversions conversions(Long projectId, Long modelId) {
        return mappingService.conversions(projectId, modelId);
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
        // 상속 A·C형 문맥 (M-7 상속 A·C) : 기록이 없으면 예전 판정 그대로 (조회도 늘지 않는다)
        InheritanceConversions conversions = mappingService.conversions(projectId, modelId);
        ModelStructureQualityJudge.Strategies strategies = conversions.records().isEmpty() ? ModelStructureQualityJudge.Strategies.NONE
                : new ModelStructureQualityJudge.Strategies(id -> conversions.role(id).name(),
                id -> conversions.kind(id) == InheritanceConversions.Kind.SINGLE_TABLE ? conversions.hierarchy().root(id) : null,
                conversions::leavesUnder, structureRepository.inheritedAttributes(projectId, modelId));
        List<Row> rows = ModelStructureQualityJudge.judge(new ModelStructureQualityJudge.Input(modelId, modelName,
                structureRepository.entities(projectId, modelId), structureRepository.relationships(projectId, modelId), structureRepository.pairs(projectId, modelId),
                structureRepository.identifiers(projectId, modelId), structureRepository.attributeColumns(projectId, modelId), structureRepository.inheritedKeys(projectId, modelId),
                structureRepository.foreignKeys(projectId, modelId), structureRepository.uniqueConstraints(projectId, modelId), structureRepository.uniqueColumns(projectId, modelId),
                structureRepository.primaryKeyColumns(projectId, modelId), hierarchy::parent, hierarchy::root, hierarchy.participants(),
                structureRepository.uniqueIndexes(projectId, modelId), strategies));
        if (conversions.records().isEmpty()) return rows;
        // 전략 표시 : 구조가 기대와 같아도 DB 가 강제하지 않는 범위 (A형 서브타입 AK = 통합 테이블 전체 UNIQUE, C형 상속 AK = 말단 안에서만)
        return rows.stream().map(row -> {
            String code = row.structure() == null ? null : row.structure().code();
            InheritanceConversions.Role role = row.entityId() == null ? InheritanceConversions.Role.DIRECT : conversions.role(row.entityId());
            if (row.type() == CheckType.ALTERNATE_KEY && role == InheritanceConversions.Role.MERGED) {
                return row.withStrategy(row.status(), row.reason(), code, List.of("SUBTYPE_SCOPE_NOT_ENFORCED"));
            }
            if (row.type() == CheckType.ALTERNATE_KEY && conversions.kind(row.entityId()) == InheritanceConversions.Kind.TABLE_PER_CONCRETE) {
                return row.withStrategy(row.status(), row.reason(), code, List.of("GLOBAL_KEY_NOT_ENFORCED"));
            }
            return code == null ? row : row.withStrategy(row.status(), row.reason(), code, row.notEnforced());
        }).toList();
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
        Map<Long, Set<Long>> primary = queryRepository.findPrimaryIdentifierAttributes(projectId, search.modelId());
        InheritanceConversions conversions = mappingService.conversions(projectId, search.modelId());
        List<Row> rows;
        List<Row> strategyRows = List.of();
        if (conversions.records().isEmpty()) {
            rows = diagnose(db, sources, primary, entityRows);
        } else {
            // 상속 A·C형 (M-7 상속 A·C) : A형 구성원은 통합 테이블 문맥, C형 분산 엔터티의 속성은 말단마다 복제 컬럼으로 (StrategyQualityRows)
            Map<Long, StrategyQualityRows.Leaf> leaves = concreteLeaves(projectId, search.modelId(), conversions);
            List<Long> distributed = conversions.hierarchy().participants().stream().filter(id -> conversions.role(id) == InheritanceConversions.Role.DISTRIBUTED).toList();
            List<Source> distributedSources = new ArrayList<>();
            boolean leafScoped = search.entityId() == null || leaves.containsKey(search.entityId());
            if (!distributed.isEmpty() && leafScoped) {
                for (Source s : queryRepository.findQualitySources(projectId, new Search(search.modelId(), null, null, null, null, null))) {
                    if (distributed.contains(s.entityId())) distributedSources.add(s);
                }
            }
            java.util.function.Predicate<Row> match = row -> (search.entityId() == null || search.entityId().equals(row.entityId())) && textMatches(row, search);
            rows = new ArrayList<>(StrategyQualityRows.rows(db, conversions, sources, primary, entityRows, leaves, distributedSources, match));
            rows.addAll(invalidConcreteLinks(projectId, search.modelId(), conversions, match));
            if (entityRows) strategyRows = strategyInheritanceRows(projectId, search, conversions, leaves);
        }
        if (!inheritance.isEmpty()) rows = rows.stream().map(row -> row.withInheritance(inheritance.contains(row.entityId()))).toList();
        Computed computed = inheritanceRows(projectId, db, hierarchy, search, sources, rows, entityRows, conversions);
        if (strategyRows.isEmpty()) return computed;
        List<Row> all = new ArrayList<>(computed.rows());
        all.addAll(strategyRows);
        return new Computed(all, computed.participants(), computed.inheritance());
    }

    /** 이름 검색 조건 (복제 행 — DB 에서 거르지 못한 말단 문맥 행) */
    private static boolean textMatches(Row row, Search search) {
        if (search.searchQuery() == null || search.searchQuery().isBlank()) return true;
        String keyword = search.searchQuery().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (search.searchColumn() == null ? "" : search.searchColumn()) {
            case "attributeName" -> contains(row.attributeName(), keyword);
            case "columnName" -> contains(row.columnName(), keyword);
            default -> contains(row.entityName(), keyword);
        };
    }

    /** C형 말단 문맥 : 매핑된 말단마다 그 매핑의 상속 PK·상속 일반 속성 매핑 (원본 속성 → 컬럼) — 모델 단위 두 번 조회 */
    private Map<Long, StrategyQualityRows.Leaf> concreteLeaves(Long projectId, Long modelId, InheritanceConversions c) {
        Map<Long, StrategyQualityRows.Leaf> leaves = new HashMap<>();
        boolean any = c.hierarchy().participants().stream().anyMatch(id -> c.kind(id) == InheritanceConversions.Kind.TABLE_PER_CONCRETE);
        if (!any) return leaves;
        Map<Long, Map<Long, com.lexorma.data.column.domain.entity.ColumnInfo>> copied = new HashMap<>();
        subtypeKeyRepository.findAllByModelId(projectId, modelId).forEach(k -> copied.computeIfAbsent(k.getMapping().getId(), x -> new HashMap<>()).put(k.getAttribute().getId(), k.getColumn()));
        subtypeAttributeRepository.findAllByModelId(projectId, modelId).forEach(k -> copied.computeIfAbsent(k.getMapping().getId(), x -> new HashMap<>()).put(k.getAttribute().getId(), k.getColumn()));
        for (com.lexorma.data.mapping.domain.entity.EntityTableMapping m : entityMappingRepository.findAllByModelId(projectId, modelId)) {
            Long id = m.getEntity().getId();
            if (c.kind(id) != InheritanceConversions.Kind.TABLE_PER_CONCRETE || !c.leaf(id)) continue;
            var table = m.getTable();
            leaves.put(id, new StrategyQualityRows.Leaf(id, m.getEntity().getEntityName(), m.getId(), table.getSchema() == null ? null : table.getSchema().getId(),
                    table.getSchema() == null ? null : table.getSchema().getSchemaName(), table.getId(), table.getTableName(), copied.getOrDefault(m.getId(), Map.of())));
        }
        return leaves;
    }

    /**
     * AC-P03 : 유효하지 않은 기존 상속 일반 속성 매핑 (C형) — 매핑 엔터티가 지금 C형 말단이 아니거나 원본 속성이 그 말단의 조상 속성이 아니다 (생성 뒤 말단을 그룹에서 뺀 경우 등).
     * 지우거나 고치지 않고 MISMATCH 로만 보인다 (상속 PK 매핑의 '유효하지 않은 기존 상속 PK 매핑' 과 같은 계약)
     */
    private List<Row> invalidConcreteLinks(Long projectId, Long modelId, InheritanceConversions c, java.util.function.Predicate<Row> match) {
        List<Row> out = new ArrayList<>();
        for (com.lexorma.data.mapping.domain.entity.SubtypeAttributeColumnMapping link : subtypeAttributeRepository.findAllByModelId(projectId, modelId)) {
            var mapping = link.getMapping();
            Long entity = mapping.getEntity().getId();
            if (c.concreteAncestor(entity, link.getAttribute().getEntity().getId())) continue;
            var table = mapping.getTable();
            var column = link.getColumn();
            Row row = new Row(CheckType.INHERITANCE + ":" + entity + ":" + link.getAttribute().getId(), modelId, null, mapping.getId(), entity, mapping.getEntity().getEntityName(),
                    link.getAttribute().getId(), link.getAttribute().getAttributeName(), table.getSchema() == null ? null : table.getSchema().getId(),
                    table.getSchema() == null ? null : table.getSchema().getSchemaName(), table.getId(), table.getTableName(), column.getId(), column.getColumnName(),
                    CheckType.INHERITANCE, CheckStatus.MISMATCH, Messages.get("유효하지 않은 기존 상속 일반 속성 매핑"), column.getColumnName(),
                    Messages.get("엔터티 {0} 은(는) 지금 속성 {1} 을(를) 물려받는 C형 말단이 아닌데 상속 일반 속성 매핑이 남아 있습니다. 매핑 관리에서 해제할 수 있습니다.",
                            mapping.getEntity().getEntityName(), link.getAttribute().getAttributeName()), true, null, "STRATEGY_STRUCTURE_MISMATCH", List.of());
            if (match.test(row)) out.add(row);
        }
        return out;
    }

    /** A·C 계층 엔터티의 [상속 구조] 행 (엔터티마다 1행). 진단 원본은 모델 전체를 한 번만 읽는다 (검색 조건과 무관 — 행을 만든 뒤 거른다) */
    private List<Row> strategyInheritanceRows(Long projectId, Search search, InheritanceConversions c, Map<Long, StrategyQualityRows.Leaf> leaves) {
        List<Source> all = queryRepository.findQualitySources(projectId, new Search(search.modelId(), null, null, null, null, null));
        Map<Long, Source> context = new java.util.LinkedHashMap<>();
        Map<Long, Long> columnOfAttribute = new HashMap<>();
        Map<Long, Long> attributeCount = new HashMap<>();
        Set<Long> tablesWithPk = new HashSet<>();
        for (Source s : all) {
            if (s.tableId() != null && Boolean.TRUE.equals(s.columnPk())) tablesWithPk.add(s.tableId());
            if (c.kind(s.entityId()) != InheritanceConversions.Kind.NONE) context.putIfAbsent(s.entityId(), s);
            if (s.attributeId() != null) attributeCount.merge(s.entityId(), 1L, Long::sum);
            if (s.attributeId() != null && s.columnId() != null) columnOfAttribute.put(s.attributeId(), s.columnId());
        }
        // 분류 컬럼 : 그룹 분류 속성의 매핑 컬럼 (A형 — 루트 매핑 아래 속성 매핑)
        Map<Long, Long> classification = new HashMap<>();
        subtypeGroupRepository.findClassificationByModelId(search.modelId()).forEach(row -> {
            if (row[1] != null) classification.put((Long) row[0], columnOfAttribute.get((Long) row[1]));
        });
        // C형 말단 기대 복제 수 = 조상(루트·중간)의 속성 수 (루트 PK 포함)
        Map<Long, Integer> expected = new HashMap<>();
        for (Long leaf : leaves.keySet()) {
            int count = 0;
            for (Long ancestor : c.hierarchy().ancestors(leaf)) count += attributeCount.getOrDefault(ancestor, 0L).intValue();
            expected.put(leaf, count);
        }
        String modelName = all.isEmpty() ? null : all.get(0).modelName();
        Map<Long, ConversionBasis.Result> basis = conversionBasis(projectId, search.modelId(), c);
        return StrategyQualityRows.inheritanceRows(c, context, classification, expected, leaves, basis, tablesWithPk, search.modelId(), modelName).stream()
                .filter(r -> (search.entityId() == null || search.entityId().equals(r.entityId())) && textMatches(r, search)).toList();
    }

    /**
     * AC-P03 : 그룹마다 변환 당시 상태(모델 변경 이력)와 지금 상태 비교 — 그룹 ID → 결과. 모델 단위 조회 4번 (그룹·구성원·변환 기록 이력·그룹 이력)
     */
    private Map<Long, ConversionBasis.Result> conversionBasis(Long projectId, Long modelId, InheritanceConversions c) {
        Map<Long, ConversionBasis.Result> result = new HashMap<>();
        List<InheritanceConversions.Record> records = c.records().stream().filter(r -> !c.orphan(r)).toList();
        if (records.isEmpty()) return result;
        Set<Long> groupIds = new HashSet<>();
        records.forEach(r -> groupIds.add(r.groupId()));
        Map<Long, ConversionBasis.History> created = new HashMap<>();
        for (Object[] row : historyRepository.findStates(projectId, com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType.SUBTYPE_CONVERSION,
                records.stream().map(InheritanceConversions.Record::id).toList())) {
            if (row[5] != com.lexorma.data.history.domain.entity.ModelChangeHistory.Action.CREATE) continue;
            ConversionBasis.History h = history(row);
            created.merge(h.targetId(), h, (a, b) -> a.id() > b.id() ? a : b);
        }
        Map<Long, List<ConversionBasis.History>> groupHistory = new HashMap<>();
        for (Object[] row : historyRepository.findStates(projectId, com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType.SUBTYPE_GROUP, groupIds)) {
            ConversionBasis.History h = history(row);
            groupHistory.computeIfAbsent(h.targetId(), k -> new ArrayList<>()).add(h);
        }
        Map<Long, Map<Long, String>> values = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (var m : subtypeRepository.findWithEntities(groupIds)) {
            values.computeIfAbsent(m.getGroup().getId(), k -> new java.util.LinkedHashMap<>()).put(m.getSubtypeEntity().getId(), m.getClassificationValue());
            names.put(m.getSubtypeEntity().getId(), m.getSubtypeEntity().getEntityName());
        }
        Map<Long, ConversionBasis.GroupState> current = new HashMap<>();
        for (var g : subtypeGroupRepository.findWithEntities(groupIds)) {
            current.put(g.getId(), new ConversionBasis.GroupState(g.getId(), g.getGroupName(), g.getOverlapping(), g.getTotal(),
                    g.getClassificationAttribute() == null ? null : g.getClassificationAttribute().getId(),
                    g.getClassificationAttribute() == null ? null : g.getClassificationAttribute().getAttributeName(), values.getOrDefault(g.getId(), Map.of()), names));
        }
        for (InheritanceConversions.Record r : records) {
            result.put(r.groupId(), ConversionBasis.compare(r.strategy(), created.get(r.id()), groupHistory.getOrDefault(r.groupId(), List.of()), current.get(r.groupId())));
        }
        return result;
    }

    private static ConversionBasis.History history(Object[] row) {
        return new ConversionBasis.History((Long) row[0], (Long) row[1], (String) row[2], (java.time.LocalDateTime) row[3], (String) row[4]);
    }

    private Computed inheritanceRows(Long projectId, DbType db, LogicalHierarchy hierarchy, Search search, List<Source> sources, List<Row> rows, boolean entityRows,
                                     InheritanceConversions conversions) {
        List<SubtypeIdentifierColumnMapping> links = subtypeKeyRepository.findAllByModelId(projectId, search.modelId());
        // B형(기록 없는) 서브타입만 — A형 통합·C형 계층의 서브타입은 상속 PK 매핑을 쓰지 않거나 [상속 구조] 를 StrategyQualityRows 가 따로 낸다
        long subtypeCount = hierarchy.participants().stream().filter(id -> hierarchy.parent(id) != null && conversions.kind(id) == InheritanceConversions.Kind.NONE).count();
        if (hierarchy.isEmpty() && links.isEmpty()) {
            return new Computed(rows, 0, new InheritanceSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
        Map<Long, List<SubtypeIdentifierColumnMapping>> linksByMapping = new java.util.HashMap<>();
        links.forEach(l -> linksByMapping.computeIfAbsent(l.getMapping().getId(), k -> new ArrayList<>()).add(l));
        Map<Long, Source> firstSource = new java.util.LinkedHashMap<>();
        sources.forEach(source -> firstSource.putIfAbsent(source.entityId(), source));
        List<Long> mappedSubtypes = firstSource.values().stream().filter(source -> source.mappingId() != null && hierarchy.parent(source.entityId()) != null
                        && conversions.kind(source.entityId()) == InheritanceConversions.Kind.NONE).map(Source::entityId).toList();
        SubtypePlanContext context = subtypeContexts.lightForModel(projectId, search.modelId(), hierarchy, db);
        context.prepare(mappedSubtypes);
        String keyword = search.searchQuery() == null || search.searchQuery().isBlank() ? null : search.searchQuery().trim().toLowerCase(java.util.Locale.ROOT);
        Map<Long, List<Row>> extra = new java.util.HashMap<>();
        // 상속 PK 명칭 진단 (M7-NAME-1, NAME-P07) : 원본(루트 주 식별자) 속성의 표준 용어 — 모델의 상속 PK 매핑 속성을 한 번에
        Map<Long, Object[]> keyTerms = queryRepository.findAttributeTerms(links.stream().map(l -> l.getAttribute().getId()).collect(java.util.stream.Collectors.toSet()));
        long requiredKeys = 0, mappedKeys = 0, invalid = 0, valid = 0, mismatch = 0, unmapped = 0, notComparable = 0;
        for (Source source : firstSource.values()) {
            if (source.mappingId() == null) continue;
            // A·C 계층 (상속 FK 없음) : B형 상속 구조 판정을 하지 않는다 — [상속 구조] 행은 StrategyQualityRows 가 낸다
            if (conversions.kind(source.entityId()) != InheritanceConversions.Kind.NONE) continue;
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
                // B형 상속 PK 컬럼의 명칭 (M7-NAME-1) : 지금 루트 주 식별자에 있는 원본 속성의 매핑만 — 컬럼마다 1행. 상속 PK·FK 구조는 위 [상속 구조] 행이 그대로 본다
                for (SubtypeIdentifierColumnMapping link : own) {
                    if (!current.contains(link.getAttribute().getId())) continue;
                    if (keyword != null && !entityRows && !("attributeName".equals(search.searchColumn()) ? contains(link.getAttribute().getAttributeName(), keyword)
                            : contains(link.getColumn().getColumnName(), keyword))) continue;
                    list.add(inheritedKeyName(source, link, keyTerms.get(link.getAttribute().getId())));
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
                    com.lexorma.data.table.domain.entity.TableInfo table = m.getTable();
                    if (subtype && context.rootKey(m.getEntity().getId()).stream().anyMatch(a -> a.getId().equals(link.getAttribute().getId()))) {
                        // 유효한 상속 PK : 명칭 행만 이름으로 찾는다 (M7-NAME-1 — B형만, A·C 계층은 위와 같이 건너뛴다)
                        if (conversions.kind(m.getEntity().getId()) != InheritanceConversions.Kind.NONE) continue;
                        if (!("attributeName".equals(search.searchColumn()) ? contains(link.getAttribute().getAttributeName(), keyword)
                                : contains(link.getColumn().getColumnName(), keyword))) continue;
                        extra.computeIfAbsent(m.getEntity().getId(), k -> new ArrayList<>()).add(inheritedKeyName(m.getEntity().getModel().getId(), m.getEntity().getModel().getModelName(),
                                m.getId(), m.getEntity().getId(), m.getEntity().getEntityName(), table.getSchema() == null ? null : table.getSchema().getId(),
                                table.getSchema() == null ? null : table.getSchema().getSchemaName(), table.getId(), table.getTableName(), link, keyTerms.get(link.getAttribute().getId())));
                        continue;
                    }
                    if (!(contains(link.getAttribute().getAttributeName(), keyword) || contains(link.getColumn().getColumnName(), keyword))) continue;
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

    /**
     * 상속 PK 컬럼 1개의 명칭 행 (M7-NAME-1, NAME-P03·P07) : 기대 컬럼명 = 원본 속성(루트 주 식별자)의 표준 용어 영문약어 — 직접 매핑 행의 {@link #name} 과 같은 규칙.
     * 엔터티 = 서브타입, 속성 = 원본 속성, 컬럼 = 서브타입 테이블의 상속 PK 컬럼. 코드 INHERITED_KEY 로 직접 속성 행과 구분한다 (같은 컬럼에 NAME 행은 하나뿐 — 컬럼은 한 매핑만 쓴다)
     */
    static Row inheritedKeyName(Source s, SubtypeIdentifierColumnMapping link, Object[] term) {
        return inheritedKeyName(s.modelId(), s.modelName(), s.mappingId(), s.entityId(), s.entityName(), s.schemaId(), s.schemaName(), s.tableId(), s.tableName(), link, term);
    }

    static Row inheritedKeyName(Long modelId, String modelName, Long mappingId, Long entityId, String entityName, Long schemaId, String schemaName, Long tableId,
                                String tableName, SubtypeIdentifierColumnMapping link, Object[] term) {
        Long termId = term == null ? null : (Long) term[0];
        String abbreviation = term == null ? null : (String) term[2];
        var column = link.getColumn();
        PhysicalNameRecommendationService.ColumnName expected = PhysicalNameRecommendationService.recommendColumnName(termId, abbreviation);
        CheckStatus status = !expected.available() ? CheckStatus.NOT_COMPARABLE
                : PhysicalNameRecommendationService.sameName(column.getColumnName(), expected.name()) ? CheckStatus.MATCH : CheckStatus.MISMATCH;
        String reason = !expected.available() ? expected.unavailableReason()
                : status == CheckStatus.MATCH ? null : Messages.get("상속 PK 컬럼명이 원본 속성(루트 주 식별자)의 표준 용어 영문약어와 다릅니다.");
        return new Row(CheckType.NAME + ":" + entityId + ":" + link.getAttribute().getId(), modelId, modelName, mappingId, entityId, entityName,
                link.getAttribute().getId(), link.getAttribute().getAttributeName(), schemaId, schemaName, tableId, tableName, column.getId(), column.getColumnName(),
                CheckType.NAME, status, expected.name(), column.getColumnName(), reason, true, null, "INHERITED_KEY", List.of());
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
