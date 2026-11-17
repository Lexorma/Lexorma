package com.lexorma.data.mapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.LogicalRelationship;
import com.lexorma.data.logical.repository.LogicalEntityRepository;
import com.lexorma.data.logical.repository.LogicalModelRepository;
import com.lexorma.data.logical.repository.LogicalRelationshipRepository;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.entity.AttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import com.lexorma.data.mapping.repository.AttributeColumnMappingRepository;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.ObjectNamespace;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableNameDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 논리 → 물리 선택 생성 (DEC-10). 고른 엔터티마다 테이블 하나, 속성마다 컬럼 하나를 만들고 엔터티·속성 매핑(M-7)을 잇는다.
 * <ul>
 *   <li>이름 : 테이블명은 엔터티명으로 테이블 등록과 같은 규칙(표준 단어 + 프로젝트 접두어, {@link TableDesignService#generateTableName}), 컬럼명은 속성에 연결된 표준 용어의 영문 약어.
 *       타입·길이·정밀도는 그 용어의 도메인 (컬럼 등록 화면과 같은 해석). 표준 용어가 없는 속성은 만들지 않는다(오류 — 먼저 연결한다)</li>
 *   <li>PK : 엔터티의 주 식별자 속성. FK : 논리 관계(원본 = 부모, 대상 = 자식)의 부모가 단일 PK 이고 자식 테이블에 그 PK 와 같은 용어의 컬럼이 있으면 그 컬럼의 참조 테이블명
 *       (REF_COLUMN 관계 동기화가 관계로 만든다). 아니면 경고만</li>
 *   <li>막는 문제 : 이름을 만들 수 없음, 옮길 스키마에 같은 이름의 테이블·VIEW, 계획 안의 테이블명·컬럼명 중복, 용어 없음, 도메인 타입 없음. 이미 매핑된 엔터티는 건너뛴다(덮어쓰지 않는다)</li>
 *   <li>반영 : 논리 모델 행 → 스키마 이름 공간을 잠그고 계획을 다시 만들어 미리보기의 planToken 과 비교한다(다르면 409 MODEL_CONFLICT). 모두 미승인으로 만들고, 하나라도 실패하면 전체를 되돌린다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PhysicalGenerationService {

    static final int ENTITY_MAX = 100;
    private static final Pattern TYPE = Pattern.compile("^\\s*([^(]+?)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?\\s*$");
    private static final Set<String> NUMERIC = Set.of("NUMBER", "NUMERIC", "DECIMAL");

    private final LogicalEntityRepository entities;
    private final LogicalModelRepository models;
    private final LogicalRelationshipRepository relationships;
    private final EntityTableMappingRepository entityMappings;
    private final AttributeColumnMappingRepository attributeMappings;
    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;
    private final TableDesignService tableDesignService;
    private final SchemaService schemaService;
    private final ObjectNamespace namespace;

    /**
     * 미리보기 : 모델 객체를 저장하지 않는다. 읽기 전용 트랜잭션이 아닌 것은 기본 스키마 해석(SchemaService.resolveSchema)이 프로젝트 행을 잠그고
     * 기본 스키마가 없으면 만들기 때문이다 (다른 화면과 같은 동작 — PostgreSQL 은 읽기 전용 트랜잭션에서 행 잠금을 거절한다)
     */
    @Transactional
    public PhysicalGenerationDto.Plan preview(AccountDto account, PhysicalGenerationDto.Request request) {
        return plan(account, request);
    }

    @Transactional
    public PhysicalGenerationDto.Result apply(AccountDto account, PhysicalGenerationDto.Request request) {
        Long projectId = account.getProjectId();
        if (request == null || request.planToken() == null) throw invalid("미리보기를 먼저 확인해주세요.");
        List<LogicalEntity> chosen = load(projectId, request.entityIds());
        // 잠금 순서 : 논리 모델(ID 순) → 스키마 이름 공간 (엔터티·속성 저장과 테이블 등록이 같은 행을 먼저 잡는다)
        chosen.stream().map(e -> e.getModel().getId()).distinct().sorted()
                .forEach(modelId -> models.findForUpdate(modelId, projectId).orElseThrow(() -> invalid("논리 모델을 찾을 수 없습니다.")));
        SchemaInfo schema = schemaService.resolveSchema(projectId, request.schemaId());
        namespace.lock(projectId, false, schema.getId());
        PhysicalGenerationDto.Plan plan = plan(account, request);
        if (!plan.planToken().equals(request.planToken())) {
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("생성 계획"));
        }
        if (!plan.creatable()) throw invalid("만들 수 없는 항목이 있습니다. 미리보기의 오류를 먼저 고쳐주세요.");

        Map<Long, LogicalEntity> byId = new HashMap<>();
        chosen.forEach(e -> byId.put(e.getId(), e));
        int nextOrder = tables.findAllByProjectId(projectId, org.springframework.data.domain.Pageable.unpaged()).getContent().stream()
                .map(TableInfo::getSortOrder).filter(Objects::nonNull).max(Integer::compare).orElse(0);
        List<Long> created = new ArrayList<>();
        int columnCount = 0;
        for (PhysicalGenerationDto.TablePlan item : plan.tables()) {
            if (!"CREATE".equals(item.status())) continue;
            LogicalEntity entity = byId.get(item.entityId());
            TableInfo table = tables.save(TableInfo.builder().projectId(projectId).schema(schema).tableName(item.tableName())
                    .tableKorName(entity.getEntityName()).tableDescription(entity.getEntityDescription() == null ? entity.getEntityName() : entity.getEntityDescription())
                    .isMasterTable(false).sortOrder(++nextOrder).isApproval(false).columns(new ArrayList<>()).build());
            EntityTableMapping mapping = entityMappings.save(EntityTableMapping.builder().projectId(projectId).entity(entity).table(table).build());
            Map<Long, LogicalAttribute> attributes = new HashMap<>();
            entity.getAttributes().forEach(a -> attributes.put(a.getId(), a));
            int order = 0;
            for (PhysicalGenerationDto.ColumnPlan c : item.columns()) {
                LogicalAttribute attribute = attributes.get(c.attributeId());
                ColumnInfo column = columns.save(ColumnInfo.builder().projectId(projectId).tableInfo(table).tableName(table.getTableName())
                        .columnName(c.columnName()).columnDesc(attribute.getAttributeName()).dataType(c.dataType()).maxLength(c.maxLength())
                        .precision(c.precision() == null ? null : BigDecimal.valueOf(c.precision())).scale(c.scale())
                        .isPk(c.pk()).isNullable(c.nullable()).isRequired(!c.nullable()).refTableName(c.refTableName())
                        .isUnique(false).isIndex(false).isMasterData(false).isSensitive(false).isEncrypted(false)
                        .standardTerms(attribute.getStandardTerm()).sortOrder(++order).isApproval(false).build());
                table.getColumns().add(column);
                attributeMappings.save(AttributeColumnMapping.builder().mapping(mapping).attribute(attribute).column(column).build());
                columnCount++;
            }
            created.add(table.getId());
        }
        return new PhysicalGenerationDto.Result(created.size(), columnCount, created);
    }

    private PhysicalGenerationDto.Plan plan(AccountDto account, PhysicalGenerationDto.Request request) {
        Long projectId = account.getProjectId();
        List<LogicalEntity> chosen = load(projectId, request == null ? null : request.entityIds());
        SchemaInfo schema = schemaService.resolveSchema(projectId, request.schemaId());
        Set<Long> chosenIds = new HashSet<>();
        chosen.forEach(e -> chosenIds.add(e.getId()));

        // 1) 테이블명 : 이미 매핑된 엔터티는 그 테이블명, 새로 만들 엔터티는 표준 단어 규칙
        Map<Long, String> tableNameByEntity = new HashMap<>();
        Map<Long, PhysicalGenerationDto.TablePlan> skipped = new LinkedHashMap<>();
        Map<Long, TableNameDto> generated = new HashMap<>();
        for (LogicalEntity entity : chosen) {
            var existing = entityMappings.findByEntity_Id(entity.getId());
            if (existing.isPresent()) {
                tableNameByEntity.put(entity.getId(), existing.get().getTable().getTableName());
                skipped.put(entity.getId(), new PhysicalGenerationDto.TablePlan(entity.getId(), entity.getEntityName(), "SKIP", existing.get().getTable().getTableName(),
                        existing.get().getTable().getSchema().getSchemaName(), List.of(), List.of(),
                        List.of(Messages.get("이미 테이블 {0} 에 매핑된 엔터티라 만들지 않습니다.", existing.get().getTable().getTableName()))));
                continue;
            }
            TableNameDto name = tableDesignService.generateTableName(entity.getEntityName(), account);
            generated.put(entity.getId(), name);
            if (name.valid()) tableNameByEntity.put(entity.getId(), name.tableName().toUpperCase(Locale.ROOT));
        }

        Map<String, Integer> nameUse = new HashMap<>();
        tableNameByEntity.forEach((id, name) -> { if (!skipped.containsKey(id)) nameUse.merge(name, 1, Integer::sum); });
        List<PhysicalGenerationDto.TablePlan> result = new ArrayList<>();
        StringBuilder canonical = new StringBuilder("v1|").append(schema.getSchemaName()).append('|'); // 스키마는 이름으로 (기본 스키마는 처음 조회 때 만들어질 수 있다)
        for (LogicalEntity entity : chosen) {
            canonical.append(entity.getId()).append('@').append(entity.modifiedAt()).append(';');
            if (skipped.containsKey(entity.getId())) {
                result.add(skipped.get(entity.getId()));
                canonical.append("SKIP;");
                continue;
            }
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            String tableName = tableNameByEntity.get(entity.getId());
            if (tableName == null) errors.add(Messages.get("엔터티명 {0} 으로 표준 단어 기준 테이블명을 만들 수 없습니다. 표준 단어를 먼저 등록해주세요.", entity.getEntityName()));
            else if (nameUse.getOrDefault(tableName, 0) > 1) errors.add(Messages.get("고른 엔터티 중 같은 테이블명({0})이 되는 것이 있습니다.", tableName));
            else if (namespace.conflict(schema.getId(), tableName, null, null) != null) errors.add(Messages.get("스키마 {0} 에 같은 이름의 테이블·VIEW {1} 가 있습니다. 덮어쓰지 않습니다.", schema.getSchemaName(), tableName));

            Set<Long> primary = new HashSet<>();
            entity.getIdentifiers().stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst()
                    .ifPresent(i -> i.getAttributes().forEach(a -> primary.add(a.getAttribute().getId())));
            if (primary.isEmpty()) warnings.add(Messages.get("주 식별자가 없어 PK 없이 만듭니다."));
            Map<Long, String> references = references(projectId, entity, chosenIds, tableNameByEntity, warnings);

            List<PhysicalGenerationDto.ColumnPlan> columnPlans = new ArrayList<>();
            Set<String> columnNames = new HashSet<>();
            for (LogicalAttribute attribute : entity.getAttributes().stream()
                    .sorted(Comparator.comparing(LogicalAttribute::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(LogicalAttribute::getId)).toList()) {
                StandardTerm term = attribute.getStandardTerm();
                canonical.append('a').append(attribute.getId()).append('@').append(attribute.modifiedAt());
                if (term == null) {
                    errors.add(Messages.get("속성 {0} 에 표준 용어가 없습니다. 논리 모델에서 표준 용어를 먼저 연결해주세요.", attribute.getAttributeName()));
                    continue;
                }
                canonical.append('t').append(term.getId()).append('@').append(term.modifiedAt());
                // 컬럼명 : 화면의 용어 약어와 같은 값 (단어 약어를 _ 로 이은 것), 단어 연결이 없으면 용어에 적은 영문 약어
                String fromWords = StandardTermDto.fromEntity(term).getCommonStandardTermAbbreviation();
                String abbreviation = fromWords != null && !fromWords.isBlank() ? fromWords : term.getCommonStandardTermAbbreviation();
                String columnName = abbreviation == null || abbreviation.isBlank() ? null : abbreviation.strip().toUpperCase(Locale.ROOT);
                var domain = term.getStandardDomain();
                Matcher type = TYPE.matcher(domain == null || domain.getDataType() == null ? "" : domain.getDataType());
                if (columnName == null) {
                    errors.add(Messages.get("속성 {0} 의 표준 용어에 영문 약어가 없습니다.", attribute.getAttributeName()));
                    continue;
                }
                if (!type.matches() || type.group(1).isBlank()) {
                    errors.add(Messages.get("속성 {0} 의 표준 용어에 도메인 데이터 타입이 없습니다.", attribute.getAttributeName()));
                    continue;
                }
                if (!columnNames.add(columnName)) {
                    errors.add(Messages.get("같은 컬럼명({0})이 되는 속성이 둘 이상입니다.", columnName));
                    continue;
                }
                // 도메인 : 타입명에 길이를 적었으면 그것, 아니면 길이·소수점 칸 (0 은 없음)
                String base = type.group(1).toUpperCase(Locale.ROOT);
                Integer first = type.group(2) != null ? Integer.valueOf(type.group(2)) : positive(domain.getDataLength());
                Integer second = type.group(3) != null ? Integer.valueOf(type.group(3)) : positive(domain.getDataDecimalLength());
                boolean numeric = NUMERIC.contains(base);
                boolean pk = primary.contains(attribute.getId());
                boolean nullable = !pk && !Boolean.TRUE.equals(attribute.getIsRequired());
                if (!Boolean.TRUE.equals(term.getIsApproval())) warnings.add(Messages.get("속성 {0} 의 표준 용어가 승인 전입니다.", attribute.getAttributeName()));
                columnPlans.add(new PhysicalGenerationDto.ColumnPlan(attribute.getId(), attribute.getAttributeName(), term.getId(), columnName, base,
                        numeric ? null : first, numeric ? first : null, numeric ? second : null, pk, nullable, references.get(attribute.getId()), term.getIsApproval()));
            }
            if (entity.getAttributes().isEmpty()) errors.add(Messages.get("속성이 없는 엔터티는 만들 수 없습니다."));
            String status = errors.isEmpty() ? "CREATE" : "ERROR";
            canonical.append(status).append(tableName).append(columnPlans).append(';');
            result.add(new PhysicalGenerationDto.TablePlan(entity.getId(), entity.getEntityName(), status, tableName, schema.getSchemaName(), columnPlans, errors, warnings));
        }
        boolean creatable = result.stream().noneMatch(t -> "ERROR".equals(t.status())) && result.stream().anyMatch(t -> "CREATE".equals(t.status()));
        return new PhysicalGenerationDto.Plan(result, creatable, sha256(canonical.toString()));
    }

    /** 이 엔터티(자식)의 속성 → 참조 테이블명 : 논리 관계의 부모가 단일 주 식별자이고, 자식에 그 속성과 같은 표준 용어의 속성이 있을 때 */
    private Map<Long, String> references(Long projectId, LogicalEntity child, Set<Long> chosenIds, Map<Long, String> tableNameByEntity, List<String> warnings) {
        Map<Long, String> result = new HashMap<>();
        for (LogicalRelationship relationship : relationships.findAllByModelId(child.getModel().getId())) {
            if (!relationship.getTargetEntity().getId().equals(child.getId())) continue;
            LogicalEntity parent = relationship.getSourceEntity();
            String parentTable = tableNameByEntity.get(parent.getId());
            if (parentTable == null && !chosenIds.contains(parent.getId())) {
                parentTable = entityMappings.findByEntity_Id(parent.getId()).map(m -> m.getTable().getTableName()).orElse(null);
            }
            List<LogicalAttribute> parentKey = parent.getIdentifiers().stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst()
                    .map(i -> i.getAttributes().stream().map(LogicalIdentifierAttribute::getAttribute).toList()).orElse(List.of());
            if (parentTable == null || parentKey.size() != 1 || parentKey.get(0).getStandardTerm() == null) {
                warnings.add(Messages.get("관계 {0} → {1} 은(는) 부모 테이블이 없거나 주 식별자가 단일 속성이 아니라 FK 를 만들지 않습니다.", parent.getEntityName(), child.getEntityName()));
                continue;
            }
            Long termId = parentKey.get(0).getStandardTerm().getId();
            String table = parentTable;
            child.getAttributes().stream().filter(a -> a.getStandardTerm() != null && a.getStandardTerm().getId().equals(termId)).findFirst()
                    .ifPresentOrElse(a -> result.put(a.getId(), table),
                            () -> warnings.add(Messages.get("관계 {0} → {1} : 자식에 부모 식별자와 같은 용어의 속성이 없어 FK 를 만들지 않습니다.", parent.getEntityName(), child.getEntityName())));
        }
        return result;
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private List<LogicalEntity> load(Long projectId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw invalid("만들 엔터티를 고르세요.");
        if (ids.size() > ENTITY_MAX) throw invalid("한 번에 {0}개 엔터티까지 만들 수 있습니다.", ENTITY_MAX);
        List<LogicalEntity> result = new ArrayList<>();
        for (Long id : ids.stream().distinct().sorted().toList()) {
            result.add(entities.findByIdAndProjectId(id, projectId).orElseThrow(() -> invalid("엔터티를 찾을 수 없습니다. (ID {0})", id)));
        }
        return result;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, message, args);
    }
}
