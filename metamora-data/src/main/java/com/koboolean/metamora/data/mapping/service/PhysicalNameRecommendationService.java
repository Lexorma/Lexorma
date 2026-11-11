package com.koboolean.metamora.data.mapping.service;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.common.i18n.Messages;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.data.logical.repository.LogicalModelRepository;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSource;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.Source;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableRow;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableSearch;
import com.koboolean.metamora.data.mapping.repository.ModelMappingQueryRepository;
import com.koboolean.metamora.data.mapping.repository.PhysicalNameRecommendationQueryRepository;
import com.koboolean.metamora.data.mapping.repository.PhysicalNameRecommendationQueryRepository.NamedColumn;
import com.koboolean.metamora.data.mapping.repository.PhysicalNameRecommendationQueryRepository.NamedTable;
import com.koboolean.metamora.data.table.domain.dto.TableNameDto;
import com.koboolean.metamora.data.table.service.TableDesignService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 표준 사전 기반 물리명 추천 (M-7 3차, 조회 전용). 추천만 하고 테이블·컬럼의 이름을 바꾸지 않는다 — 변경은 기존 테이블·컬럼 관리 기능으로 사용자가 한다.
 * <ul>
 * <li>컬럼명 : 논리 속성에 연결된 표준 용어의 <b>영문약어</b> (컬럼 등록·M-5 진단과 같은 규칙 "컬럼명 = 표준 용어의 영문약어").
 *     용어·영문약어가 없으면 추천하지 않는다. 영문약어는 용어를 등록할 때 표준 단어를 조합해 저장한 값이라 여기서 다시 조합하지 않는다.</li>
 * <li>테이블명 : 엔터티명을 공백·밑줄로 나눈 단어가 모두 <b>승인된 표준 단어</b>일 때, 프로젝트 접두어 + 단어 영문약어
 *     (테이블 등록과 같은 규칙 {@link TableDesignService#generateTableName}). 나눌 수 없거나 미승인·미등록 단어가 있으면 추천하지 않는다.</li>
 * <li>충돌 : 추천명을 다른 물리 객체가 이미 쓰고 있다. 컬럼명은 같은 테이블 안, 테이블명은 같은 프로젝트 안 (기존 등록·수정의 중복 기준).
 *     DDL 이 식별자를 따옴표로 감싸지 않으므로 대소문자만 다른 이름은 같은 이름으로 본다. DBMS 별 식별자 길이 제한은 저장소에 규칙이 없어 판정하지 않는다.</li>
 * </ul>
 * 정합성 비교(M-7 2차)의 명칭 진단은 {@link #recommendColumnName} 을 기대 컬럼명으로 쓴다 (두 기능이 다른 이름을 계산하지 않는다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhysicalNameRecommendationService {

    private final PhysicalNameRecommendationQueryRepository queryRepository;
    private final ModelMappingQueryRepository mappingQueryRepository;
    private final LogicalModelRepository modelRepository;
    private final TableDesignService tableDesignService;

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
        String name = abbreviation == null ? "" : abbreviation.trim();
        if (name.isEmpty()) {
            return new ColumnName(null, Messages.get("논리 속성의 표준 용어에 영문약어가 없어 기대 컬럼명을 알 수 없습니다."));
        }
        return new ColumnName(name, null);
    }

    /** 현재 이름이 추천명과 같은가 (앞뒤 공백 제거, 대소문자 무시) — 명칭 진단의 일치 기준과 같다 */
    public static boolean sameName(String current, String recommended) {
        return recommended != null && recommended.trim().equalsIgnoreCase(current == null ? "" : current.trim());
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
        List<NamedColumn> columns = queryRepository.findColumnsByName(
                named.stream().map(ColumnSource::tableId).collect(Collectors.toSet()),
                named.stream().map(source -> upper(source.termAbbreviation())).collect(Collectors.toSet()));
        return sources.map(source -> columnRow(source, columns));
    }

    /** 상태 판정은 PhysicalNameRecommendationQueryRepository 의 조건식과 같다 : 추천 불가 → 일치 → 충돌 → 추천 가능 */
    static ColumnRow columnRow(ColumnSource s, List<NamedColumn> columnsByName) {
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
            NamedColumn other = columnsByName.stream()
                    .filter(column -> column.tableId().equals(s.tableId()) && !column.columnId().equals(s.columnId()) && sameName(column.columnName(), recommended.name()))
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
                s.columnId(), s.columnName(), recommended.name(), status, recommended.available() ? Source.STANDARD_TERM : null, reason);
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
        List<NamedTable> tables = queryRepository.findTablesByName(projectId,
                names.values().stream().filter(TableNameDto::valid).map(name -> upper(name.tableName())).collect(Collectors.toSet()));
        return entities.map(entity -> tableRow(entity, names.get(entity.entityName()), tables));
    }

    static TableRow tableRow(ModelMappingDto.EntityRow e, TableNameDto name, List<NamedTable> tablesByName) {
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
                    .filter(table -> !table.tableId().equals(e.tableId()) && sameName(table.tableName(), name.tableName()))
                    .findFirst().orElse(null);
            if (other != null) {
                status = Status.CONFLICT;
                reason = other.mappedEntityName() != null
                        ? Messages.get("엔터티 {0} 에 매핑된 테이블이 추천 테이블명을 이미 쓰고 있습니다.", other.mappedEntityName())
                        : Messages.get("추천 테이블명과 이름이 같은 테이블이 이미 있습니다. 그 테이블은 논리 엔터티에 매핑되지 않았습니다.");
            } else {
                status = Status.AVAILABLE;
                reason = e.tableId() != null ? Messages.get("표준 단어의 영문약어 기준입니다. 현재 테이블명과 다릅니다.")
                        : Messages.get("표준 단어의 영문약어 기준입니다. 엔터티가 물리 테이블에 매핑되지 않았습니다.");
            }
        }
        return new TableRow(String.valueOf(e.entityId()), e.modelId(), e.modelName(), e.entityId(), e.entityName(), e.mappingId(),
                e.schemaId(), e.schemaName(), e.tableId(), e.tableName(), name.valid() ? name.tableName() : null,
                status, name.valid() ? Source.STANDARD_WORD : null, reason, name.tablePrefix(), name.words());
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
