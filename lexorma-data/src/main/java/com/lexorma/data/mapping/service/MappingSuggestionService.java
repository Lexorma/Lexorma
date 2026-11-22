package com.lexorma.data.mapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.quality.StandardNames;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.AttributeLink;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.AttributeSuggestion;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.AttributeSuggestions;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.ColumnSuggestion;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.Difference;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.Issue;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.MappingStatus;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.EntityOverlay;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.Level;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.LinkKind;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.OverlayStatus;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.TableOverlay;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.TableSuggestion;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.TableSuggestions;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Source;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository.AttributeLinkRow;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository.CandidateColumn;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository.CandidateTable;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository.ColumnLinkRow;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository.EntityLink;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository.TableLink;
import com.lexorma.data.mapping.repository.ModelMappingQueryRepository;
import com.lexorma.data.table.domain.dto.TableNameDto;
import com.lexorma.data.table.service.TableDesignService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 표준 용어 기반 매핑 후보 추천과 ERD 매핑 표시 (M-7 자동 매핑 후보·ERD 연계, docs/completed/logical-physical-mapping-m7.md 18장).
 * <ul>
 * <li>조회 전용 (M7-R01) : 읽기 전용 트랜잭션, 투영 질의만. 매핑·승인 상태를 만들거나 바꾸지 않는다. 저장은 사용자가 고른 후보를 기존 매핑 저장 API 로 보낸다 (M7-R03).</li>
 * <li>근거는 규칙으로만 정한다 — 점수·확률·LLM 없음. 표준 용어 ID 근거(STANDARD)와 이름·명명 규칙 근거(REFERENCE)를 구분한다 (M7-R02, R07).</li>
 * <li>이미 매핑된 엔터티·속성은 후보를 내지 않고, 이미 매핑된 테이블·컬럼(상속 PK 매핑 포함)은 후보가 되지 않는다 (M7-R05). 후보가 여럿이면 모두 낸다 (M7-R04).</li>
 * <li>모든 조회는 로그인 프로젝트 범위다 (M7-R06).</li>
 * <li>ERD 표시는 화면에 보이는 객체 ID(최대 {@value #MAX_OVERLAY_IDS}개)를 한 번에 받아 종류별 일괄 조회하고, 상태는 정합성 비교와 같은 판정({@link ModelMappingQualityService#modelRows})을 모은다 (M7-E02, E04).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MappingSuggestionService {

    /** ERD 한 화면 범위 (ERD 범위 조회의 최대 300개와 같다) */
    public static final int MAX_OVERLAY_IDS = 300;
    /** 엔터티 하나의 테이블 후보 수 상한 */
    static final int MAX_TABLE_CANDIDATES = 20;
    /** 근거별 테이블 조회 상한 */
    private static final int TABLE_LOOKUP_LIMIT = 50;
    /** KEY_TERMS 판정을 위해 PK 컬럼을 읽을 테이블 상한 */
    private static final int KEY_TABLE_LOOKUP_LIMIT = 200;

    /** 컬럼 후보 근거 우선순위 (지시 8 : 같은 표준 용어 → 명명 규칙 컬럼명 → 논리명) */
    static final List<String> COLUMN_REASONS = List.of("STANDARD_TERM", "STANDARD_TERM_UNAPPROVED", "COLUMN_NAME", "LOGICAL_NAME");
    /** 테이블 후보 근거 우선순위 */
    static final List<String> TABLE_REASONS = List.of("KEY_TERMS", "TABLE_NAME", "TABLE_COMMENT");

    private final ModelMappingQueryRepository mappingQuery;
    private final MappingSuggestionQueryRepository repository;
    private final ModelMappingQualityService qualityService;
    private final ModelMappingService mappingService;
    private final TableDesignService tableDesignService;
    private final ProjectRepository projectRepository;

    // ------------------------------------------------------------------ 엔터티 ↔ 테이블 후보

    public TableSuggestions tableSuggestions(AccountDto accountDto, Long entityId, Long schemaId) {
        Long projectId = accountDto.getProjectId();
        ModelMappingDto.EntityRow row = entityId == null ? null : mappingQuery.findEntityRow(projectId, entityId, null);
        if (row == null) throw new CustomException(ErrorCode.INVALID_MODEL_MAPPING, "엔터티를 찾을 수 없습니다.");
        if (row.mappingId() != null) {
            return new TableSuggestions(row.modelId(), row.entityId(), row.entityName(), true, row.mappingId(), row.tableId(), row.tableName(),
                    null, 0, false, "ALREADY_MAPPED", false, List.of());
        }
        TableNameDto name = tableDesignService.generateTableNames(List.of(row.entityName()), accountDto).get(row.entityName());
        String recommended = name != null && name.valid() ? name.tableName() : null;

        Map<Long, Set<String>> reasons = new LinkedHashMap<>();
        if (recommended != null) {
            repository.unmappedTablesByName(projectId, schemaId, recommended.trim().toUpperCase(Locale.ROOT), TABLE_LOOKUP_LIMIT)
                    .forEach(id -> reasons.computeIfAbsent(id, k -> new HashSet<>()).add("TABLE_NAME"));
        }
        String commentKey = StandardNames.termNameKey(row.entityName());
        if (!commentKey.isEmpty()) {
            repository.unmappedTablesByComment(projectId, schemaId, commentKey, TABLE_LOOKUP_LIMIT)
                    .forEach(id -> reasons.computeIfAbsent(id, k -> new HashSet<>()).add("TABLE_COMMENT"));
        }
        // 주 식별자 용어 = PK 컬럼 용어 : 주 식별자 속성이 모두 용어를 가질 때만 (용어 없는 키 속성이 있으면 이 근거를 쓰지 않는다)
        List<Long> keyTerms = repository.primaryKeyTermIds(projectId, row.entityId());
        int keyTermCount = (int) keyTerms.stream().filter(Objects::nonNull).count();
        if (!keyTerms.isEmpty() && keyTermCount == keyTerms.size()) {
            Set<Long> expected = new HashSet<>(keyTerms);
            List<Long> ids = repository.unmappedTablesWithKeyTerms(projectId, schemaId, expected, KEY_TABLE_LOOKUP_LIMIT);
            repository.primaryKeyTerms(ids).forEach((tableId, terms) -> {
                if (sameKeyTerms(keyTerms, terms)) reasons.computeIfAbsent(tableId, k -> new HashSet<>()).add("KEY_TERMS");
            });
        }

        Set<Long> attributeTerms = new HashSet<>(repository.attributeTermIds(projectId, row.entityId()));
        Map<Long, Set<Long>> sharedTerms = repository.columnTerms(reasons.keySet(), attributeTerms);
        List<TableSuggestion> candidates = repository.tables(projectId, reasons.keySet()).stream()
                .map(t -> tableSuggestion(t, reasons.get(t.tableId()), sharedTerms.getOrDefault(t.tableId(), Set.of()).size(), attributeTerms.size()))
                .sorted(TABLE_ORDER)
                .limit(MAX_TABLE_CANDIDATES)
                .toList();
        return new TableSuggestions(row.modelId(), row.entityId(), row.entityName(), false, null, null, null, recommended, keyTermCount,
                !candidates.isEmpty(), candidates.isEmpty() ? "NO_CANDIDATE" : null, candidates.size() > 1, candidates);
    }

    /** 주 식별자 속성 용어(순서 무관)와 PK 컬럼 용어가 개수까지 같다 — 용어 없는 PK 컬럼이 있으면 다르다 */
    static boolean sameKeyTerms(List<Long> keyTerms, List<Long> pkTerms) {
        if (pkTerms.size() != keyTerms.size() || pkTerms.stream().anyMatch(Objects::isNull)) return false;
        List<Long> a = new ArrayList<>(keyTerms);
        List<Long> b = new ArrayList<>(pkTerms);
        a.sort(Comparator.naturalOrder());
        b.sort(Comparator.naturalOrder());
        return a.equals(b);
    }

    static TableSuggestion tableSuggestion(CandidateTable t, Set<String> reasons, int matchedTerms, int termAttributes) {
        List<String> ordered = TABLE_REASONS.stream().filter(reasons::contains).toList();
        return new TableSuggestion(t.tableId(), t.schemaId(), t.schemaName(), t.tableName(), t.tableKorName(), t.isApproval(),
                ordered.contains("KEY_TERMS") ? Level.STANDARD : Level.REFERENCE, ordered, matchedTerms, termAttributes);
    }

    /** 규칙 순서 : 가장 앞선 근거 → 근거 수(많은 쪽) → 스키마명 → 테이블명 → ID (점수 아님) */
    static final Comparator<TableSuggestion> TABLE_ORDER = Comparator
            .comparingInt((TableSuggestion s) -> TABLE_REASONS.indexOf(s.reasons().get(0)))
            .thenComparing(s -> -s.reasons().size())
            .thenComparing(TableSuggestion::schemaName, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TableSuggestion::tableName, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TableSuggestion::tableId);

    // ------------------------------------------------------------------ 속성 ↔ 컬럼 후보

    public AttributeSuggestions attributeSuggestions(AccountDto accountDto, Long mappingId) {
        Long projectId = accountDto.getProjectId();
        ModelMappingDto.EntityRow row = mappingId == null ? null : mappingQuery.findEntityRow(projectId, mappingId);
        if (row == null) throw new CustomException(ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        List<Source> sources = mappingQuery.findQualitySources(projectId, new Search(row.modelId(), row.entityId(), null, null, null, null)).stream()
                .filter(s -> s.attributeId() != null).toList();
        List<CandidateColumn> columns = repository.candidateColumns(row.tableId());
        Map<Long, Boolean> approvals = repository.termApprovals(projectId,
                sources.stream().map(Source::attributeTermId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        Set<Long> primary = mappingQuery.findPrimaryIdentifierAttributes(projectId, row.modelId()).getOrDefault(row.entityId(), Set.of());
        return new AttributeSuggestions(row.mappingId(), row.modelId(), row.entityId(), row.entityName(), row.tableId(), row.schemaName(), row.tableName(),
                suggest(dbType(projectId), sources, columns, approvals, primary));
    }

    /** 순수 판정 (단위 테스트 대상) : 속성마다 후보 컬럼·근거·충돌·예상 불일치 */
    static List<AttributeSuggestion> suggest(DbType db, List<Source> sources, List<CandidateColumn> columns, Map<Long, Boolean> approvals, Set<Long> primary) {
        Map<Long, List<ColumnSuggestion>> byAttribute = new LinkedHashMap<>();
        for (Source s : sources) {
            if (s.attributeMappingId() != null) continue;
            Boolean termApproval = s.attributeTermId() == null ? null : approvals.getOrDefault(s.attributeTermId(), false);
            List<ColumnSuggestion> candidates = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                CandidateColumn c = columns.get(i);
                List<String> reasons = columnReasons(s, c, termApproval);
                if (reasons.isEmpty()) continue;
                candidates.add(new ColumnSuggestion(c.columnId(), c.columnName(), c.columnDesc(), c.dataType(), c.maxLength(), c.precision(), c.scale(),
                        c.isPk(), c.isNullable(), c.termId(), c.termName(), c.termApproval(),
                        reasons.contains("STANDARD_TERM") ? Level.STANDARD : Level.REFERENCE, reasons, List.of(), differences(db, s, c, primary)));
            }
            // 컬럼 순서는 그대로 두고 가장 앞선 근거로만 정렬 (안정 정렬)
            candidates.sort(Comparator.comparingInt(c -> COLUMN_REASONS.indexOf(c.reasons().get(0))));
            byAttribute.put(s.attributeId(), candidates);
        }
        // 같은 컬럼이 여러 속성의 후보 : 이름을 서로 알려 준다
        Map<Long, List<String>> attributesByColumn = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        sources.forEach(s -> names.put(s.attributeId(), s.attributeName()));
        byAttribute.forEach((attributeId, list) -> list.forEach(c -> attributesByColumn.computeIfAbsent(c.columnId(), k -> new ArrayList<>()).add(names.get(attributeId))));

        List<AttributeSuggestion> result = new ArrayList<>(sources.size());
        for (Source s : sources) {
            Boolean termApproval = s.attributeTermId() == null ? null : approvals.getOrDefault(s.attributeTermId(), false);
            List<String> notes = new ArrayList<>();
            if (s.attributeTermId() == null) notes.add("NO_TERM");
            else if (!Boolean.TRUE.equals(termApproval)) notes.add("TERM_NOT_APPROVED");
            if (s.attributeMappingId() != null) {
                result.add(new AttributeSuggestion(s.attributeId(), s.attributeName(), s.attributeRequired(), s.attributeTermId(), s.attributeTermName(), termApproval,
                        true, s.columnId(), s.columnName(), false, "ALREADY_MAPPED", notes, List.of(), List.of()));
                continue;
            }
            List<ColumnSuggestion> candidates = byAttribute.get(s.attributeId()).stream().map(c -> withShared(c, attributesByColumn.get(c.columnId()), s.attributeName())).toList();
            result.add(new AttributeSuggestion(s.attributeId(), s.attributeName(), s.attributeRequired(), s.attributeTermId(), s.attributeTermName(), termApproval,
                    false, null, null, !candidates.isEmpty(), candidates.isEmpty() ? "NO_CANDIDATE" : null, notes, conflicts(s.attributeId(), candidates, byAttribute), candidates));
        }
        return result;
    }

    static List<String> columnReasons(Source s, CandidateColumn c, Boolean termApproval) {
        List<String> reasons = new ArrayList<>();
        if (s.attributeTermId() != null && s.attributeTermId().equals(c.termId())) {
            reasons.add(Boolean.TRUE.equals(termApproval) ? "STANDARD_TERM" : "STANDARD_TERM_UNAPPROVED");
        }
        if (StandardNames.sameColumnName(c.columnName(), StandardNames.expectedColumnName(s.attributeTermAbbreviation()))) reasons.add("COLUMN_NAME");
        String key = StandardNames.termNameKey(s.attributeName());
        if (!key.isEmpty() && key.equals(StandardNames.termNameKey(c.columnDesc()))) reasons.add("LOGICAL_NAME");
        return reasons;
    }

    private static ColumnSuggestion withShared(ColumnSuggestion c, List<String> attributes, String self) {
        List<String> others = attributes == null ? List.of() : attributes.stream().filter(name -> !Objects.equals(name, self)).distinct().toList();
        return new ColumnSuggestion(c.columnId(), c.columnName(), c.columnDesc(), c.dataType(), c.maxLength(), c.precision(), c.scale(), c.isPk(), c.isNullable(),
                c.termId(), c.termName(), c.termApproval(), c.level(), c.reasons(), others, c.differences());
    }

    /** 자동으로 하나를 고르지 않는 이유 (M7-R04, 지시 8 의 근거 충돌) */
    static List<String> conflicts(Long attributeId, List<ColumnSuggestion> candidates, Map<Long, List<ColumnSuggestion>> all) {
        List<String> conflicts = new ArrayList<>();
        if (candidates.stream().filter(c -> c.level() == Level.STANDARD).count() > 1) conflicts.add("MULTIPLE_STANDARD");
        // 근거마다 가리키는 컬럼 : 최우선 근거의 컬럼과 다른 컬럼을 다른 근거가 가리키면 충돌
        if (!candidates.isEmpty()) {
            String top = candidates.get(0).reasons().get(0);
            Set<Long> topColumns = new LinkedHashSet<>();
            candidates.stream().filter(c -> c.reasons().contains(top)).forEach(c -> topColumns.add(c.columnId()));
            if (candidates.stream().anyMatch(c -> !topColumns.contains(c.columnId()))) conflicts.add("REASONS_DIFFER");
            // 최우선 후보가 다른 속성의 최우선 후보이기도 함
            Long first = candidates.get(0).columnId();
            boolean shared = all.entrySet().stream().anyMatch(e -> !e.getKey().equals(attributeId) && !e.getValue().isEmpty() && e.getValue().get(0).columnId().equals(first));
            if (shared) conflicts.add("SHARED");
        }
        return conflicts;
    }

    /** 이 짝을 매핑했다고 놓고 정합성 비교의 판정(기존 {@link ModelMappingQualityService#diagnose})을 돌려 불일치 항목만 낸다 — 판정 규칙을 새로 만들지 않는다 */
    static List<Difference> differences(DbType db, Source s, CandidateColumn c, Set<Long> primary) {
        Source pair = new Source(s.modelId(), s.modelName(), s.entityId(), s.entityName(), s.mappingId(), s.schemaId(), s.schemaName(), s.tableId(), s.tableName(),
                s.attributeId(), s.attributeName(), s.attributeRequired(), s.attributeTermId(), s.attributeTermName(), s.attributeTermAbbreviation(),
                s.domainId(), s.domainName(), s.domainDataType(), s.domainLength(), s.domainDecimalLength(),
                -1L, c.columnId(), c.columnName(), c.dataType(), c.maxLength(), c.precision(), c.scale(), c.isNullable(), c.isRequired(), c.isPk(),
                c.termId(), c.termName());
        return ModelMappingQualityService.diagnose(db, List.of(pair), Map.of(s.entityId(), primary), false).stream()
                .filter(r -> r.status() == CheckStatus.MISMATCH)
                .map(r -> new Difference(r.type().name(), r.logicalValue(), r.physicalValue(), r.reason()))
                .toList();
    }

    // ------------------------------------------------------------------ ERD 매핑 표시

    public List<EntityOverlay> entityOverlays(AccountDto accountDto, Long modelId, List<Long> entityIds) {
        Long projectId = accountDto.getProjectId();
        List<Long> ids = overlayIds(entityIds);
        // 없는 모델·다른 프로젝트의 모델은 여기서 거절된다
        ModelMappingDto.Status model = mappingService.selectStatus(accountDto, requireModel(modelId)).get(0);
        List<EntityLink> links = repository.entityLinks(projectId, model.modelId(), ids);
        if (links.isEmpty()) return List.of();
        List<Long> found = links.stream().map(EntityLink::entityId).toList();
        Map<Long, List<AttributeLinkRow>> attributes = group(repository.attributeLinks(found), AttributeLinkRow::entityId);
        List<Long> mapped = links.stream().filter(l -> l.mappingId() != null).map(EntityLink::entityId).toList();
        Map<Long, List<AttributeLinkRow>> inherited = group(repository.inheritedKeyLinksByEntity(mapped), AttributeLinkRow::entityId);
        Judged judged = mapped.isEmpty() ? Judged.EMPTY : judge(qualityService.modelRows(projectId, model.modelId(), model.modelName()));

        List<EntityOverlay> result = new ArrayList<>(links.size());
        for (EntityLink l : links) {
            List<AttributeLinkRow> own = attributes.getOrDefault(l.entityId(), List.of());
            boolean isMapped = l.mappingId() != null;
            List<AttributeLink> links2 = own.stream().filter(a -> a.columnId() != null)
                    .map(a -> link(a.attributeId(), a.attributeName(), a.columnId(), a.columnName(), LinkKind.DIRECT, judged.attributeRows(a.attributeId()))).toList();
            List<AttributeLink> keys = inherited.getOrDefault(l.entityId(), List.of()).stream()
                    .map(a -> link(a.attributeId(), a.attributeName(), a.columnId(), a.columnName(), LinkKind.INHERITED_KEY, judged.inheritanceRows(l.entityId()))).toList();
            List<Row> rows = judged.byEntity().getOrDefault(l.entityId(), List.of());
            result.add(new EntityOverlay(l.entityId(), isMapped, l.mappingId(), l.schemaId(), l.schemaName(), l.tableId(), l.tableName(),
                    isMapped ? worst(rows) : OverlayStatus.UNMAPPED, own.size(), links2.size(), count(rows, CheckStatus.MISMATCH), count(rows, CheckStatus.NOT_COMPARABLE),
                    links2, keys, isMapped ? MappingStatus.MAPPED : MappingStatus.UNMAPPED, isMapped ? quality(rows) : CheckStatus.UNMAPPED,
                    isMapped ? entityIssues(rows) : List.of()));
        }
        return result;
    }

    public List<TableOverlay> tableOverlays(AccountDto accountDto, List<Long> tableIds) {
        Long projectId = accountDto.getProjectId();
        List<Long> ids = overlayIds(tableIds);
        List<TableLink> links = repository.tableLinks(projectId, ids);
        List<Long> mapped = links.stream().filter(l -> l.mappingId() != null).map(TableLink::tableId).toList();
        Map<Long, List<ColumnLinkRow>> direct = group(repository.columnLinks(mapped), ColumnLinkRow::tableId);
        Map<Long, List<ColumnLinkRow>> inherited = group(repository.inheritedKeyLinksByTable(mapped), ColumnLinkRow::tableId);
        // 테이블이 여러 논리 모델에 매핑될 수 있다 : 모델마다 정합성 판정 1번 (테이블마다가 아니다)
        Map<Long, Judged> judgedByModel = new HashMap<>();
        links.stream().filter(l -> l.mappingId() != null).map(l -> Map.entry(l.modelId(), l.modelName())).distinct()
                .forEach(e -> judgedByModel.put(e.getKey(), judge(qualityService.modelRows(projectId, e.getKey(), e.getValue()))));

        List<TableOverlay> result = new ArrayList<>(links.size());
        for (TableLink l : links) {
            int columnCount = l.columnCount() == null ? 0 : l.columnCount().intValue();
            if (l.mappingId() == null) {
                result.add(new TableOverlay(l.tableId(), false, null, null, null, null, null, OverlayStatus.UNMAPPED, columnCount, 0, 0, 0, List.of(),
                        MappingStatus.UNMAPPED, CheckStatus.UNMAPPED, List.of()));
                continue;
            }
            Judged judged = judgedByModel.get(l.modelId());
            List<AttributeLink> columns = new ArrayList<>();
            direct.getOrDefault(l.tableId(), List.of()).forEach(c -> columns.add(link(c.attributeId(), c.attributeName(), c.columnId(), c.columnName(),
                    LinkKind.DIRECT, judged.attributeRows(c.attributeId()))));
            inherited.getOrDefault(l.tableId(), List.of()).forEach(c -> columns.add(link(c.attributeId(), c.attributeName(), c.columnId(), c.columnName(),
                    LinkKind.INHERITED_KEY, judged.inheritanceRows(l.entityId()))));
            List<Row> rows = judged.byEntity().getOrDefault(l.entityId(), List.of());
            result.add(new TableOverlay(l.tableId(), true, l.mappingId(), l.modelId(), l.modelName(), l.entityId(), l.entityName(), worst(rows), columnCount,
                    columns.size(), count(rows, CheckStatus.MISMATCH), count(rows, CheckStatus.NOT_COMPARABLE), columns,
                    MappingStatus.MAPPED, quality(rows), entityIssues(rows)));
        }
        return result;
    }

    /** 정합성 행 묶음 : 엔터티별 · 속성별(속성·컬럼 진단만) · 엔터티별 상속 구조 */
    record Judged(Map<Long, List<Row>> byEntity, Map<Long, List<Row>> byAttribute, Map<Long, List<Row>> inheritanceByEntity) {
        static final Judged EMPTY = new Judged(Map.of(), Map.of(), Map.of());

        List<Row> attributeRows(Long attributeId) {
            return byAttribute.getOrDefault(attributeId, List.of());
        }

        List<Row> inheritanceRows(Long entityId) {
            return inheritanceByEntity.getOrDefault(entityId, List.of());
        }
    }

    /**
     * 있는 매핑 1개 (M7-F02) : 매핑 상태는 언제나 MAPPED — 정합성 판정(비교 불가 포함)과 무관하다. 정합성은 그 행들의 판정을 그대로 모은다.
     * status(예전 필드)는 뜻을 바꾸지 않고 그대로 낸다
     */
    static AttributeLink link(Long attributeId, String attributeName, Long columnId, String columnName, LinkKind kind, List<Row> rows) {
        return new AttributeLink(attributeId, attributeName, columnId, columnName, kind, worst(rows), MappingStatus.MAPPED, quality(rows), issues(rows));
    }

    /**
     * 매핑된 객체의 정합성 상태 (M7-F02-2) : 기존 판정 행의 MISMATCH > NOT_COMPARABLE > MATCH. 판정 값을 바꾸지 않고 모으기만 한다 —
     * 미매핑(UNMAPPED) 행(매핑된 엔터티 안의 미매핑 속성·대응 FK 없음 등)은 매핑된 객체 자신의 판정이 아니므로 세지 않는다 (매핑 수·건수로 보인다)
     */
    static CheckStatus quality(List<Row> rows) {
        if (rows.stream().anyMatch(r -> r.status() == CheckStatus.MISMATCH)) return CheckStatus.MISMATCH;
        if (rows.stream().anyMatch(r -> r.status() == CheckStatus.NOT_COMPARABLE)) return CheckStatus.NOT_COMPARABLE;
        return CheckStatus.MATCH;
    }

    /** 불일치·비교 불가 행 → 상세 사유 (기존 판정의 사유 그대로, 없으면 null — 만들지 않는다) */
    static List<Issue> issues(List<Row> rows) {
        return rows.stream().filter(r -> r.status() == CheckStatus.MISMATCH || r.status() == CheckStatus.NOT_COMPARABLE)
                .map(r -> new Issue(r.type().name(), r.status(), r.reason(), r.attributeName(), r.columnName())).toList();
    }

    /** 엔터티 단위 판정만 (속성에 붙은 행은 속성 쪽 issues 로 본다) : 엔터티 행·관계·보조 식별자·상속 구조. 화면 상세용이라 50건까지 */
    static List<Issue> entityIssues(List<Row> rows) {
        return issues(rows.stream().filter(r -> r.attributeId() == null || r.type().structure() || r.type() == CheckType.INHERITANCE).toList())
                .stream().limit(50).toList();
    }

    static Judged judge(List<Row> rows) {
        Map<Long, List<Row>> byEntity = new HashMap<>();
        Map<Long, List<Row>> byAttribute = new HashMap<>();
        Map<Long, List<Row>> inheritance = new HashMap<>();
        for (Row r : rows) {
            if (r.entityId() == null) continue;
            byEntity.computeIfAbsent(r.entityId(), k -> new ArrayList<>()).add(r);
            if (r.type() == CheckType.INHERITANCE) inheritance.computeIfAbsent(r.entityId(), k -> new ArrayList<>()).add(r);
            else if (r.attributeId() != null && !r.type().structure()) byAttribute.computeIfAbsent(r.attributeId(), k -> new ArrayList<>()).add(r);
        }
        return new Judged(byEntity, byAttribute, inheritance);
    }

    /**
     * 매핑된 객체의 표시 상태 : 불일치가 하나라도 있으면 MISMATCH, 아니면 비교 불가가 있으면 NOT_COMPARABLE, 아니면 MAPPED.
     * 미매핑(UNMAPPED) 행은 매핑된 객체의 상태를 바꾸지 않는다 — 매핑된 엔터티 안의 미매핑 속성·대응 FK 없음은 수로만 보인다
     */
    static OverlayStatus worst(List<Row> rows) {
        if (rows.stream().anyMatch(r -> r.status() == CheckStatus.MISMATCH)) return OverlayStatus.MISMATCH;
        if (rows.stream().anyMatch(r -> r.status() == CheckStatus.NOT_COMPARABLE)) return OverlayStatus.NOT_COMPARABLE;
        return OverlayStatus.MAPPED;
    }

    private static int count(List<Row> rows, CheckStatus status) {
        return (int) rows.stream().filter(r -> r.status() == status).count();
    }

    private static <T> Map<Long, List<T>> group(List<T> list, java.util.function.Function<T, Long> key) {
        Map<Long, List<T>> result = new HashMap<>();
        list.forEach(item -> result.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(item));
        return result;
    }

    private static List<Long> overlayIds(Collection<Long> ids) {
        List<Long> distinct = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.size() > MAX_OVERLAY_IDS) {
            throw new CustomException(ErrorCode.INVALID_MODEL_MAPPING, "매핑 표시는 한 번에 {0}개까지 조회할 수 있습니다.", MAX_OVERLAY_IDS);
        }
        return distinct;
    }

    private static Long requireModel(Long modelId) {
        if (modelId == null) throw new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", com.lexorma.common.i18n.Messages.get("논리 모델"));
        return modelId;
    }

    private DbType dbType(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
    }
}
