package com.lexorma.data.mapping.service;

import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.logical.repository.LogicalModelRepository;
import com.lexorma.data.dictionary.quality.StandardNames;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Applicability;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSource;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Source;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableSearch;
import com.lexorma.data.mapping.repository.ModelMappingQueryRepository;
import com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository;
import com.lexorma.data.column.repository.ColumnNameDbKeys;
import com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository.NamedTable;
import com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository.NamedView;
import com.lexorma.data.table.ddl.DbIdentifierRules;
import com.lexorma.data.table.ddl.SqlIdentifierTokens.Tokens;
import com.lexorma.data.view.domain.dto.ViewDto;
import tools.jackson.databind.json.JsonMapper;
import com.lexorma.data.table.domain.dto.TableNameDto;
import com.lexorma.data.table.service.TableDesignService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 표준 사전 기반 물리명 추천 (M-7 3차, 조회 전용). 추천만 하고 테이블·컬럼의 이름을 바꾸지 않는다 — 변경은 기존 테이블·컬럼 관리 기능으로 사용자가 한다.
 * <ul>
 * <li>컬럼명 : 논리 속성에 연결된 표준 용어의 <b>영문약어</b> (컬럼 등록·M-5 진단과 같은 규칙 "컬럼명 = 표준 용어의 영문약어").
 *     용어·영문약어가 없으면 추천하지 않는다. 영문약어는 용어를 등록할 때 표준 단어를 조합해 저장한 값이라 여기서 다시 조합하지 않는다.</li>
 * <li>테이블명 : 엔터티명을 공백·밑줄로 나눈 단어가 모두 <b>승인된 표준 단어</b>일 때, 프로젝트 접두어 + 단어 영문약어
 *     (테이블 등록과 같은 규칙 {@link TableDesignService#generateTableName}). 나눌 수 없거나 미승인·미등록 단어가 있으면 추천하지 않는다.</li>
 * <li>충돌 : 추천명을 다른 물리 객체가 이미 쓰고 있다. 컬럼명은 같은 테이블의 컬럼, 테이블명은 같은 스키마의 테이블·VIEW (저장 경로 {@code ObjectNamespace} 의 이름 공간, M-15).
 *     DDL 이 식별자를 따옴표로 감싸지 않으므로 대소문자·앞뒤 공백만 다른 이름도 같은 이름으로 본다 (저장 경로의 대문자 일치보다 넓다 — 저장이 거절하는 이름은 여기서도 충돌이다).</li>
 * <li>일괄 적용 판정 (M-7 물리명 추천 일괄 적용 1차, 행마다 {@link Applicability}) : 상태와 별개로 "추천명으로 바꿔도 되는가" — 매핑된 대상, 추천끼리 충돌(전체 추천 후보 기준),
 *     DBMS 식별자 규칙({@link DbIdentifierRules#check}), 승인 상태(P-04), 표준 용어 일치(P-03), CHECK·인덱스 식·VIEW 정의의 이름 참조({@link RenameSafety}, P-08).
 *     판정만 하고 이름을 바꾸지 않는다 (적용은 2차). 상태·요약·조건·정렬·페이징은 3차 그대로다.</li>
 * </ul>
 * 정합성 비교(M-7 2차)의 명칭 진단은 {@link #recommendColumnName} 을 기대 컬럼명으로 쓴다 (두 기능이 다른 이름을 계산하지 않는다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhysicalNameRecommendationService {
    /** 테이블명 추천 정렬 필드 : 화면 필드(logicalName·currentPhysicalName) + 매핑 목록 필드를 그대로 (아래 selectTableRows 의 변환과 같다 — 공통 페이징 안정성 개선) */
    public static final java.util.Set<String> TABLE_SORT_FIELDS = java.util.stream.Stream.concat(java.util.stream.Stream.of("logicalName", "currentPhysicalName"),
            com.lexorma.data.mapping.repository.ModelMappingQueryRepository.ENTITY_SORT_FIELDS.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());


    private final PhysicalNameRecommendationQueryRepository queryRepository;
    private final ColumnNameDbKeys dbKeys;
    private final ModelMappingQueryRepository mappingQueryRepository;
    private final LogicalModelRepository modelRepository;
    private final TableDesignService tableDesignService;
    private final SchemaInfoRepository schemaInfoRepository;
    private final ProjectRepository projectRepository;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 컬럼명 규칙 (정합성 비교와 공용)

    /** 추천 컬럼명과 근거. name 이 null 이면 추천할 수 없고 unavailableReason 에 사유가 있다 */
    public record ColumnName(String name, String unavailableReason) {
        public boolean available() {
            return name != null;
        }
    }

    /**
     * 논리 속성의 표준 용어 → 추천 컬럼명 (영문약어, 앞뒤 공백 제거). 표준 사전에 없는 이름은 만들지 않는다.
     *
     * @param termId       논리 속성에 연결된 표준 용어 (없으면 null)
     * @param abbreviation 그 용어의 영문약어
     */
    public static ColumnName recommendColumnName(Long termId, String abbreviation) {
        if (termId == null) {
            return new ColumnName(null, Messages.get("논리 속성에 표준 용어가 없어 기대 컬럼명을 알 수 없습니다."));
        }
        String name = StandardNames.expectedColumnName(abbreviation);
        if (name == null) {
            return new ColumnName(null, Messages.get("논리 속성의 표준 용어에 영문약어가 없어 기대 컬럼명을 알 수 없습니다."));
        }
        return new ColumnName(name, null);
    }

    /** 현재 이름이 추천명과 같은가 (앞뒤 공백 제거, 대소문자 무시) — 명칭 진단의 일치 기준과 같은 함수 {@link StandardNames#sameColumnName} */
    public static boolean sameName(String current, String recommended) {
        return StandardNames.sameColumnName(current, recommended);
    }

    // ------------------------------------------------------------------ 컬럼명 추천

    public ColumnSummary selectColumnSummary(AccountDto accountDto, Long modelId) {
        Long projectId = accountDto.getProjectId();
        requireModel(projectId, modelId);
        long available = queryRepository.countColumns(projectId, modelId, Status.AVAILABLE);
        long matched = queryRepository.countColumns(projectId, modelId, Status.ALREADY_MATCHED);
        long unavailable = queryRepository.countColumns(projectId, modelId, Status.UNAVAILABLE);
        long conflict = queryRepository.countColumns(projectId, modelId, Status.CONFLICT);
        return new ColumnSummary(modelId, available + matched + unavailable + conflict, available, matched, unavailable, conflict);
    }

    public Page<ColumnRow> selectColumnRows(AccountDto accountDto, ColumnSearch search, Pageable pageable) {
        Long projectId = accountDto.getProjectId();
        if (search.modelId() != null) {
            requireModel(projectId, search.modelId());
        } else if (search.attributeId() == null && search.columnId() == null) {
            throw modelNotFound();
        }
        Page<ColumnSource> sources = queryRepository.findColumnSources(projectId, search, pageable);
        // 충돌 사유 : 이 쪽의 추천명을 쓰는 컬럼을 한 번에 읽는다 (행마다 조회하지 않는다)
        List<ColumnSource> named = sources.getContent().stream()
                .filter(source -> source.tableId() != null && recommendColumnName(source.termId(), source.termAbbreviation()).available()).toList();
        // 이름 충돌 : DB 의 컬럼명 유일성 기준 (저장된 COL_NM_CMPR_VL ↔ 추천명의 DB 비교 값, M7-U10) — 목록 SQL 의 상태·필터와 같은 기준, 한 번에 조회
        List<ColumnNameDbKeys.Taken> columns = dbKeys.taken(
                named.stream().map(ColumnSource::tableId).collect(Collectors.toSet()),
                named.stream().map(source -> recommendColumnName(source.termId(), source.termAbbreviation()).name()).collect(Collectors.toSet()));
        List<ColumnRow> rows = sources.getContent().stream().map(source -> columnRow(source, columns, null)).toList();
        Map<Long, Applicability> applicability = assessColumns(projectId, sources.getContent(), rows);
        Map<Long, ColumnRow> byAttribute = new HashMap<>();
        rows.forEach(row -> byAttribute.put(row.logicalAttributeId(), withApplicability(row, applicability.get(row.logicalAttributeId()))));
        return sources.map(source -> byAttribute.get(source.attributeId()));
    }

    /** 상태 판정은 PhysicalNameRecommendationQueryRepository 의 조건식과 같다 : 추천 불가 → 일치 → 충돌 → 추천 가능 */
    static ColumnRow columnRow(ColumnSource s, List<ColumnNameDbKeys.Taken> columnsByName, Applicability applicability) {
        ColumnName recommended = recommendColumnName(s.termId(), s.termAbbreviation());
        Status status;
        String reason;
        if (!recommended.available()) {
            status = Status.UNAVAILABLE;
            reason = recommended.unavailableReason();
        } else if (s.columnId() != null && sameName(s.columnName(), recommended.name())) {
            status = Status.ALREADY_MATCHED;
            reason = Messages.get("현재 컬럼명이 표준 용어의 영문약어와 같습니다.");
        } else {
            ColumnNameDbKeys.Taken other = columnsByName.stream()
                    .filter(column -> column.tableId().equals(s.tableId()) && !column.columnId().equals(s.columnId()) && column.candidate().equals(recommended.name()))
                    .findFirst().orElse(null);
            if (other != null) {
                status = Status.CONFLICT;
                reason = other.mappedAttributeName() != null
                        ? Messages.get("같은 테이블에서 속성 {0} 에 매핑된 컬럼이 추천 컬럼명을 이미 쓰고 있습니다.", other.mappedAttributeName())
                        : Messages.get("같은 테이블에 추천 컬럼명과 이름이 같은 컬럼이 이미 있습니다. 그 컬럼은 논리 속성에 매핑되지 않았습니다.");
            } else {
                status = Status.AVAILABLE;
                reason = s.columnId() != null ? Messages.get("표준 용어의 영문약어 기준입니다. 현재 컬럼명과 다릅니다.")
                        : s.tableId() != null ? Messages.get("표준 용어의 영문약어 기준입니다. 속성이 물리 컬럼에 매핑되지 않았습니다.")
                        : Messages.get("표준 용어의 영문약어 기준입니다. 엔터티가 물리 테이블에 매핑되지 않았습니다.");
            }
        }
        return new ColumnRow(String.valueOf(s.attributeId()), s.modelId(), s.modelName(), s.entityId(), s.entityName(), s.mappingId(),
                s.schemaId(), s.schemaName(), s.tableId(), s.tableName(),
                s.attributeId(), s.attributeName(), s.termId(), s.termName(), s.termAbbreviation(),
                s.columnId(), s.columnName(), recommended.name(), status, recommended.available() ? Source.STANDARD_TERM : null, reason, applicability);
    }

    private static ColumnRow withApplicability(ColumnRow r, Applicability a) {
        return new ColumnRow(r.id(), r.modelId(), r.modelName(), r.entityId(), r.entityName(), r.mappingId(), r.schemaId(), r.schemaName(), r.tableId(), r.tableName(),
                r.logicalAttributeId(), r.logicalName(), r.termId(), r.termName(), r.termAbbreviation(), r.physicalColumnId(), r.currentPhysicalName(),
                r.recommendedPhysicalName(), r.status(), r.source(), r.reason(), a);
    }

    /**
     * 컬럼명 일괄 적용 판정 (한 쪽 분량). 이름을 바꿀 대상 = 매핑된 컬럼이 있고 추천명이 현재 이름과 다른 행 (AVAILABLE·CONFLICT).
     * 추천끼리 충돌은 그 테이블의 매핑된 컬럼 전체(페이지·검색 조건과 무관)를 후보로 본다. 참조 판정 자료는 대상이 있을 때만 한 번 읽는다
     */
    private Map<Long, Applicability> assessColumns(Long projectId, List<ColumnSource> sources, List<ColumnRow> rows) {
        Map<Long, ColumnSource> sourceByAttribute = new HashMap<>();
        sources.forEach(source -> sourceByAttribute.put(source.attributeId(), source));
        List<ColumnRow> renames = rows.stream().filter(PhysicalNameRecommendationService::renamesColumn).toList();
        Map<Long, Applicability> result = new HashMap<>();
        if (renames.isEmpty()) {
            rows.forEach(row -> result.put(row.logicalAttributeId(), Applicability.of(fixedIssues(row.status(), row.reason(), row.physicalColumnId() != null, true))));
            return result;
        }
        DbType db = dbType(projectId);
        Set<Long> tableIds = renames.stream().map(ColumnRow::tableId).collect(Collectors.toSet());
        // 추천끼리 충돌 : 그 테이블들의 매핑된 컬럼 전체 중 추천명이 현재 이름과 다른 것
        List<RenameSafety.Candidate> candidates = new ArrayList<>();
        Map<Long, String> columnNames = new HashMap<>();
        List<PhysicalNameRecommendationQueryRepository.MappedColumn> mappedColumns = queryRepository.findMappedColumns(tableIds);
        // 추천끼리 겹침도 그 DB 의 컬럼명 비교 값으로 본다 (M7-U10 — 미리보기·적용과 같은 기준)
        Map<String, String> keys = dbKeys.keys(mappedColumns.stream().map(m -> StandardNames.expectedColumnName(m.termAbbreviation())).filter(Objects::nonNull).toList());
        for (PhysicalNameRecommendationQueryRepository.MappedColumn mapped : mappedColumns) {
            columnNames.put(mapped.columnId(), mapped.columnName());
            String name = StandardNames.expectedColumnName(mapped.termAbbreviation());
            if (name != null && !sameName(mapped.columnName(), name)) candidates.add(new RenameSafety.Candidate(mapped.columnId(), mapped.tableId(), keys.getOrDefault(name, name)));
        }
        Map<Long, List<Long>> duplicates = RenameSafety.duplicates(candidates);
        RenameSafety safety = safety(projectId, db, tableIds);
        for (ColumnRow row : rows) {
            if (!renamesColumn(row)) {
                result.put(row.logicalAttributeId(), Applicability.of(fixedIssues(row.status(), row.reason(), row.physicalColumnId() != null, true)));
                continue;
            }
            ColumnSource source = sourceByAttribute.get(row.logicalAttributeId());
            List<ApplyIssue> issues = new ArrayList<>(fixedIssues(row.status(), row.reason(), true, true));
            List<Long> others = duplicates.getOrDefault(row.physicalColumnId(), List.of());
            if (!others.isEmpty()) {
                List<String> names = others.stream().map(id -> columnNames.getOrDefault(id, String.valueOf(id))).toList();
                issues.add(RenameSafety.issue(ApplyCode.RECOMMENDATION_DUPLICATE,
                        Messages.get("같은 테이블의 다른 컬럼도 같은 추천 컬럼명을 받습니다 : {0}. 추천끼리 겹치는 이름은 함께 적용할 수 없습니다.", RenameSafety.shown(names)), names));
            }
            issues.addAll(identifierIssues(db, DbIdentifierRules.Kind.COLUMN, row.recommendedPhysicalName()));
            issues.addAll(columnPolicyIssues(source.columnApproval(), source.tableApproval(), source.columnTermId(), source.termId()));
            issues.addAll(safety.columnRename(row.tableId(), row.tableName(), row.physicalColumnId(), row.currentPhysicalName()));
            result.put(row.logicalAttributeId(), Applicability.of(issues));
        }
        return result;
    }

    /**
     * 컬럼 이름 변경의 승인·용어 판정 (목록·일괄 적용 미리보기·적용 공용) : 컬럼 승인(P-04)·소속 테이블 승인(P-10)·컬럼 용어 없음(P-09)·용어 불일치(P-03)
     */
    static List<ApplyIssue> columnPolicyIssues(Boolean columnApproval, Boolean tableApproval, Long columnTermId, Long attributeTermId) {
        List<ApplyIssue> issues = new ArrayList<>();
        if (Boolean.TRUE.equals(columnApproval)) {
            issues.add(new ApplyIssue(ApplyCode.COLUMN_APPROVED, Messages.get("승인된 컬럼은 이름을 바꿀 수 없습니다. 승인을 취소한 뒤 적용할 수 있습니다."), List.of()));
        }
        if (Boolean.TRUE.equals(tableApproval)) {
            issues.add(new ApplyIssue(ApplyCode.TABLE_APPROVED, Messages.get("컬럼이 속한 테이블이 승인되어 있어 컬럼 이름을 바꿀 수 없습니다. 테이블 승인을 취소한 뒤 적용할 수 있습니다."), List.of()));
        }
        if (columnTermId == null) {
            issues.add(new ApplyIssue(ApplyCode.COLUMN_TERM_MISSING,
                    Messages.get("컬럼에 표준 용어가 연결되어 있지 않아 이름을 바꿀 수 없습니다. 논리 속성과 같은 표준 용어를 컬럼에 연결한 뒤 적용할 수 있습니다."), List.of()));
        } else if (!columnTermId.equals(attributeTermId)) {
            issues.add(new ApplyIssue(ApplyCode.TERM_MISMATCH,
                    Messages.get("컬럼의 표준 용어가 논리 속성의 표준 용어와 달라 이름을 바꿀 수 없습니다. 용어 연결을 먼저 맞춰주세요."), List.of()));
        }
        return issues;
    }

    /** 매핑된 컬럼의 이름을 추천명으로 바꾸는 행인가 (추천 가능 또는 지금 이름 충돌) */
    private static boolean renamesColumn(ColumnRow row) {
        return row.physicalColumnId() != null && (row.status() == Status.AVAILABLE || row.status() == Status.CONFLICT);
    }

    // ------------------------------------------------------------------ 테이블명 추천

    /**
     * 엔터티 1개 = 1행. 엔터티 목록은 DB 에서 페이징하고, 한 쪽 분량의 엔터티명을 표준 단어 조회 1번으로 변환한다.
     * 표준 단어 변환이 자바 규칙이라 상태 조건은 받지 않는다.
     */
    public Page<TableRow> selectTableRows(AccountDto accountDto, TableSearch search, Pageable pageable) {
        Long projectId = accountDto.getProjectId();
        if (search.modelId() != null) {
            requireModel(projectId, search.modelId());
        } else if (search.entityId() == null) {
            throw modelNotFound();
        }
        boolean byTable = "tableName".equals(search.searchColumn()) || "currentPhysicalName".equals(search.searchColumn());
        // 그리드 필드명 → 매핑 목록의 정렬 필드
        Sort sort = Sort.by(pageable.getSort().stream()
                .map(order -> new Sort.Order(order.getDirection(), switch (order.getProperty()) {
                    case "logicalName" -> "entityName";
                    case "currentPhysicalName" -> "tableName";
                    default -> order.getProperty();
                })).toList());
        Page<ModelMappingDto.EntityRow> entities = mappingQueryRepository.findEntityRows(projectId,
                new ModelMappingDto.EntitySearch(search.modelId(), null, null, byTable ? "tableName" : "entityName", search.searchQuery(), search.entityId()),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort));

        Map<String, TableNameDto> names = tableDesignService.generateTableNames(entities.getContent().stream().map(ModelMappingDto.EntityRow::entityName).toList(), accountDto);
        Set<String> upperNames = names.values().stream().filter(TableNameDto::valid).map(name -> upper(name.tableName())).collect(Collectors.toSet());
        List<NamedTable> tables = queryRepository.findTablesByName(projectId, upperNames);
        List<NamedView> views = queryRepository.findViewsByName(projectId, upperNames);
        // 테이블명은 스키마 안에서만 하나다 : 매핑된 테이블의 스키마(매핑이 없으면 새 테이블이 들어갈 기본 스키마)의 같은 이름만 충돌이다
        Long defaultSchemaId = schemaInfoRepository.findFirstByProjectIdAndIsDefaultTrue(projectId).map(SchemaInfo::getId).orElse(null);
        List<TableRow> rows = entities.getContent().stream()
                .map(entity -> tableRow(entity, names.get(entity.entityName()), tables, views, entity.schemaId() != null ? entity.schemaId() : defaultSchemaId, null)).toList();
        Map<Long, Applicability> applicability = assessTables(projectId, entities.getContent(), rows, accountDto);
        Map<Long, TableRow> byEntity = new HashMap<>();
        rows.forEach(row -> byEntity.put(row.logicalEntityId(), withApplicability(row, applicability.get(row.logicalEntityId()))));
        return entities.map(entity -> byEntity.get(entity.entityId()));
    }

    static TableRow tableRow(ModelMappingDto.EntityRow e, TableNameDto name, List<NamedTable> tablesByName, List<NamedView> viewsByName, Long schemaId,
                             Applicability applicability) {
        Status status;
        String reason;
        if (!name.valid()) {
            status = Status.UNAVAILABLE;
            List<String> missing = name.words().stream().filter(word -> word.status() == TableNameDto.Status.NOT_FOUND).map(TableNameDto.Word::word).toList();
            List<String> notApproved = name.words().stream().filter(word -> word.status() == TableNameDto.Status.NOT_APPROVED).map(TableNameDto.Word::word).toList();
            reason = name.words().isEmpty() ? Messages.get("엔터티명에서 단어를 찾을 수 없습니다.")
                    : !missing.isEmpty() ? Messages.get("엔터티명의 단어가 표준 단어로 등록되지 않았습니다 : {0}. 단어를 공백으로 나눠 적었는지 확인해주세요.", String.join(", ", missing))
                    : Messages.get("엔터티명의 단어가 승인되지 않은 표준 단어입니다 : {0}", String.join(", ", notApproved));
        } else if (e.tableId() != null && sameName(e.tableName(), name.tableName())) {
            status = Status.ALREADY_MATCHED;
            reason = Messages.get("현재 테이블명이 표준 단어로 만든 테이블명과 같습니다.");
        } else {
            NamedTable other = tablesByName.stream()
                    .filter(table -> !table.tableId().equals(e.tableId()) && java.util.Objects.equals(table.schemaId(), schemaId) && sameName(table.tableName(), name.tableName()))
                    .findFirst().orElse(null);
            NamedView sameView = viewsByName.stream()
                    .filter(v -> java.util.Objects.equals(v.schemaId(), schemaId) && sameName(v.viewName(), name.tableName())).findFirst().orElse(null);
            if (other != null) {
                status = Status.CONFLICT;
                reason = other.mappedEntityName() != null
                        ? Messages.get("엔터티 {0} 에 매핑된 테이블이 추천 테이블명을 이미 쓰고 있습니다.", other.mappedEntityName())
                        : Messages.get("추천 테이블명과 이름이 같은 테이블이 이미 있습니다. 그 테이블은 논리 엔터티에 매핑되지 않았습니다.");
            } else if (sameView != null) {
                // 같은 스키마의 테이블과 VIEW 는 이름을 나눠 쓴다 (ObjectNamespace — 테이블 등록·수정이 거절한다)
                status = Status.CONFLICT;
                reason = Messages.get("같은 스키마에 추천 테이블명과 이름이 같은 VIEW {0} 이(가) 있습니다. 테이블과 VIEW 는 같은 이름을 쓸 수 없습니다.", sameView.viewName());
            } else {
                status = Status.AVAILABLE;
                reason = e.tableId() != null ? Messages.get("표준 단어의 영문약어 기준입니다. 현재 테이블명과 다릅니다.")
                        : Messages.get("표준 단어의 영문약어 기준입니다. 엔터티가 물리 테이블에 매핑되지 않았습니다.");
            }
        }
        return new TableRow(String.valueOf(e.entityId()), e.modelId(), e.modelName(), e.entityId(), e.entityName(), e.mappingId(),
                e.schemaId(), e.schemaName(), e.tableId(), e.tableName(), name.valid() ? name.tableName() : null,
                status, name.valid() ? Source.STANDARD_WORD : null, reason, name.tablePrefix(), name.words(), applicability);
    }

    private static TableRow withApplicability(TableRow r, Applicability a) {
        return new TableRow(r.id(), r.modelId(), r.modelName(), r.logicalEntityId(), r.logicalName(), r.mappingId(), r.schemaId(), r.schemaName(),
                r.physicalTableId(), r.currentPhysicalName(), r.recommendedPhysicalName(), r.status(), r.source(), r.reason(), r.tablePrefix(), r.words(), a);
    }

    /**
     * 테이블명 일괄 적용 판정 (한 쪽 분량). 이름을 바꿀 대상 = 매핑된 테이블이 있고 추천명이 현재 이름과 다른 행.
     * 추천끼리 충돌은 그 스키마들에 매핑된 테이블 전체(페이지·검색 조건과 무관)의 추천명을 후보로 본다 (엔터티명 → 테이블명 변환은 표준 단어 조회 1번)
     */
    private Map<Long, Applicability> assessTables(Long projectId, List<ModelMappingDto.EntityRow> entities, List<TableRow> rows, AccountDto accountDto) {
        Map<Long, ModelMappingDto.EntityRow> entityById = new HashMap<>();
        entities.forEach(e -> entityById.put(e.entityId(), e));
        List<TableRow> renames = rows.stream().filter(PhysicalNameRecommendationService::renamesTable).toList();
        Map<Long, Applicability> result = new HashMap<>();
        if (renames.isEmpty()) {
            rows.forEach(row -> result.put(row.logicalEntityId(), Applicability.of(fixedIssues(row.status(), row.reason(), row.physicalTableId() != null, false))));
            return result;
        }
        DbType db = dbType(projectId);
        Set<Long> schemaIds = renames.stream().map(TableRow::schemaId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<PhysicalNameRecommendationQueryRepository.MappedTable> mapped = queryRepository.findMappedTables(projectId, schemaIds);
        Map<String, TableNameDto> names = tableDesignService.generateTableNames(mapped.stream().map(PhysicalNameRecommendationQueryRepository.MappedTable::entityName)
                .filter(Objects::nonNull).collect(Collectors.toSet()), accountDto);
        List<RenameSafety.Candidate> candidates = new ArrayList<>();
        Map<Long, String> tableNames = new HashMap<>();
        for (PhysicalNameRecommendationQueryRepository.MappedTable table : mapped) {
            tableNames.put(table.tableId(), table.tableName());
            TableNameDto name = table.entityName() == null ? null : names.get(table.entityName());
            if (name != null && name.valid() && !sameName(table.tableName(), name.tableName())) {
                candidates.add(new RenameSafety.Candidate(table.tableId(), table.schemaId(), name.tableName()));
            }
        }
        Map<Long, List<Long>> duplicates = RenameSafety.duplicates(candidates);
        RenameSafety safety = safety(projectId, db, renames.stream().map(TableRow::physicalTableId).collect(Collectors.toSet()));
        for (TableRow row : rows) {
            if (!renamesTable(row)) {
                result.put(row.logicalEntityId(), Applicability.of(fixedIssues(row.status(), row.reason(), row.physicalTableId() != null, false)));
                continue;
            }
            List<ApplyIssue> issues = new ArrayList<>(fixedIssues(row.status(), row.reason(), true, false));
            List<Long> others = duplicates.getOrDefault(row.physicalTableId(), List.of());
            if (!others.isEmpty()) {
                List<String> otherNames = others.stream().map(id -> tableNames.getOrDefault(id, String.valueOf(id))).toList();
                issues.add(RenameSafety.issue(ApplyCode.RECOMMENDATION_DUPLICATE,
                        Messages.get("같은 스키마의 다른 테이블도 같은 추천 테이블명을 받습니다 : {0}. 추천끼리 겹치는 이름은 함께 적용할 수 없습니다.", RenameSafety.shown(otherNames)), otherNames));
            }
            issues.addAll(identifierIssues(db, DbIdentifierRules.Kind.TABLE, row.recommendedPhysicalName()));
            if (Boolean.TRUE.equals(entityById.get(row.logicalEntityId()).tableApproval())) {
                issues.add(new ApplyIssue(ApplyCode.TABLE_APPROVED, Messages.get("승인된 테이블은 이름을 바꿀 수 없습니다. 승인을 취소한 뒤 적용할 수 있습니다."), List.of()));
            }
            issues.addAll(safety.tableRename(row.physicalTableId(), row.currentPhysicalName()));
            result.put(row.logicalEntityId(), Applicability.of(issues));
        }
        return result;
    }

    private static boolean renamesTable(TableRow row) {
        return row.physicalTableId() != null && (row.status() == Status.AVAILABLE || row.status() == Status.CONFLICT);
    }

    // ------------------------------------------------------------------ 일괄 적용 판정 공용

    /** 상태에서 바로 정해지는 판정 : 추천 없음·바꿀 것 없음·매핑 없음·지금 이름 충돌 (사유는 상태의 사유를 그대로 쓴다) */
    static List<ApplyIssue> fixedIssues(Status status, String reason, boolean mapped, boolean column) {
        List<ApplyIssue> issues = new ArrayList<>();
        switch (status) {
            case UNAVAILABLE -> issues.add(new ApplyIssue(ApplyCode.NOT_RECOMMENDED, reason, List.of()));
            case ALREADY_MATCHED -> issues.add(new ApplyIssue(ApplyCode.NO_CHANGE, reason, List.of()));
            default -> {
                if (!mapped) {
                    issues.add(new ApplyIssue(ApplyCode.NOT_MAPPED, column
                            ? Messages.get("이름을 바꿀 물리 컬럼이 없습니다. 속성이 컬럼에 매핑되지 않았습니다.")
                            : Messages.get("이름을 바꿀 물리 테이블이 없습니다. 엔터티가 테이블에 매핑되지 않았습니다."), List.of()));
                }
                if (status == Status.CONFLICT) issues.add(new ApplyIssue(ApplyCode.NAME_CONFLICT, reason, List.of()));
            }
        }
        return issues;
    }

    /** 추천명의 DBMS 식별자 판정 (저장·DDL 검사와 같은 DbIdentifierRules). 문자·길이 위반은 적용 불가, 권장 형식 아님은 경고 */
    static List<ApplyIssue> identifierIssues(DbType db, DbIdentifierRules.Kind kind, String name) {
        List<ApplyIssue> issues = new ArrayList<>();
        for (DbIdentifierRules.Issue issue : DbIdentifierRules.check(db, kind, name)) {
            ApplyCode code = issue.code() == DbIdentifierRules.Code.IDENTIFIER_NOT_RECOMMENDED ? ApplyCode.IDENTIFIER_NOT_RECOMMENDED : ApplyCode.IDENTIFIER_INVALID;
            issues.add(new ApplyIssue(code, DbIdentifierRules.reason(db, issue, List.of()), List.of()));
        }
        return issues;
    }

    /** 이름 문자열 참조 판정 자료 : 프로젝트의 모델 VIEW 전체(지금 정의 + 처리 전 변경 요청), 대상 테이블들의 CHECK·인덱스 식 */
    RenameSafety safety(Long projectId, DbType db, Set<Long> tableIds) {
        Map<Long, Tokens> tokens = new HashMap<>();
        Map<Long, String> viewNames = new HashMap<>();
        Map<Long, Set<Long>> tableRefs = new HashMap<>();
        Map<Long, Set<Long>> viewRefs = new HashMap<>();
        for (PhysicalNameRecommendationQueryRepository.ViewDefinition v : queryRepository.findViewDefinitions(projectId)) {
            viewNames.put(v.viewId(), v.viewName());
            tokens.put(v.viewId(), RenameSafety.tokens(db, v.definition()));
        }
        for (PhysicalNameRecommendationQueryRepository.ViewLink link : queryRepository.findViewLinks(projectId)) {
            if (link.tableId() != null) tableRefs.computeIfAbsent(link.viewId(), k -> new HashSet<>()).add(link.tableId());
            if (link.referencedViewId() != null) viewRefs.computeIfAbsent(link.viewId(), k -> new HashSet<>()).add(link.referencedViewId());
        }
        // 처리 전 변경 요청 : 승인하면 그 정의·참조 객체가 반영되므로 지금 정의와 함께 본다. 읽을 수 없으면 판정할 수 없는 정의로 본다
        for (PhysicalNameRecommendationQueryRepository.PendingViewRequest request : queryRepository.findPendingViewRequests(projectId)) {
            Tokens requested;
            try {
                ViewDto.Definition after = JSON.readValue(request.afterData(), ViewDto.Definition.class);
                requested = RenameSafety.tokens(db, after.definition());
                if (after.references() != null) {
                    for (ViewDto.Reference reference : after.references()) {
                        if (reference.tableId() != null) tableRefs.computeIfAbsent(request.viewId(), k -> new HashSet<>()).add(reference.tableId());
                        if (reference.viewId() != null) viewRefs.computeIfAbsent(request.viewId(), k -> new HashSet<>()).add(reference.viewId());
                    }
                }
            } catch (RuntimeException e) {
                requested = new Tokens(Set.of(), false, false, true);
            }
            tokens.merge(request.viewId(), requested, Tokens::merge);
        }
        List<RenameSafety.ViewText> views = tokens.entrySet().stream()
                .map(entry -> new RenameSafety.ViewText(entry.getKey(), viewNames.getOrDefault(entry.getKey(), String.valueOf(entry.getKey())), entry.getValue(),
                        tableRefs.getOrDefault(entry.getKey(), Set.of()), viewRefs.getOrDefault(entry.getKey(), Set.of()))).toList();

        Map<Long, Set<Long>> checkColumns = new HashMap<>();
        for (com.querydsl.core.Tuple t : queryRepository.findCheckColumns(tableIds)) {
            checkColumns.computeIfAbsent(t.get(0, Long.class), k -> new HashSet<>()).add(t.get(1, Long.class));
        }
        List<RenameSafety.CheckText> checks = queryRepository.findCheckExpressions(tableIds).stream()
                .map(c -> new RenameSafety.CheckText(c.tableId(), c.constraintName(), RenameSafety.tokens(db, c.expression()), checkColumns.getOrDefault(c.constraintId(), Set.of())))
                .toList();
        Map<Long, RenameSafety.IndexText> indexes = new HashMap<>();
        for (PhysicalNameRecommendationQueryRepository.IndexExpression e : queryRepository.findIndexExpressions(tableIds)) {
            Tokens t = RenameSafety.tokens(db, e.text());
            indexes.merge(e.indexId(), new RenameSafety.IndexText(e.tableId(), e.indexName(), t),
                    (a, b) -> new RenameSafety.IndexText(a.tableId(), a.indexName(), a.tokens().merge(b.tokens())));
        }
        return new RenameSafety(views, checks, indexes.values());
    }

    DbType dbType(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
    }

    // ------------------------------------------------------------------ 보조

    private void requireModel(Long projectId, Long modelId) {
        if (modelId == null || modelRepository.findByIdAndProjectId(modelId, projectId).isEmpty()) {
            throw modelNotFound();
        }
    }

    private static CustomException modelNotFound() {
        return new CustomException(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED, "{0}을(를) 찾을 수 없습니다.", Messages.get("논리 모델"));
    }

    private static String upper(String value) {
        return Objects.requireNonNullElse(value, "").trim().toUpperCase(Locale.ROOT);
    }
}
