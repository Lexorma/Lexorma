package com.lexorma.data.mapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.quality.StandardNames;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.LogicalModel;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.entity.LogicalRelationship;
import com.lexorma.data.logical.domain.entity.LogicalRelationshipAttribute;
import com.lexorma.data.logical.repository.LogicalRelationshipAttributeRepository;
import com.lexorma.data.logical.service.LogicalRelationshipPairs;
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
import com.lexorma.data.table.ddl.DbIdentifierCheck;
import com.lexorma.data.table.ddl.DbIdentifierRules;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.dto.TableNameDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.DdlIdentifierQueryRepository;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 논리 → 물리 선택 생성 (DEC-10). 고른 엔터티마다 테이블 하나, 속성마다 컬럼 하나를 만들고 엔터티·속성 매핑(M-7)을 잇는다.
 * <ul>
 *   <li>이름 : 테이블명은 엔터티명으로 테이블 등록과 같은 규칙(표준 단어 + 프로젝트 접두어, {@link TableDesignService#generateTableName}), 컬럼명은 속성에 연결된 표준 용어의 영문 약어.
 *       타입·길이·정밀도는 그 용어의 도메인 (컬럼 등록 화면과 같은 해석). 표준 용어가 없는 속성은 만들지 않는다(오류 — 먼저 연결한다)</li>
 *   <li>PK : 엔터티의 주 식별자 속성. FK (M-7 보강 4차) : 논리 관계에 저장된 부모 주 식별자 ↔ 자식 속성 짝(3차)이 완성된 관계만 만든다 — 용어·이름으로 추론하지 않는다
 *       (1차에서 추론을 멈춘 이유 : 자기 참조에서 PK 자신을 고르고, 같은 부모의 여러 관계를 FK 하나로 합쳤다). 일반 단일 FK 는 자식 컬럼의 참조 테이블명(커밋 직전 참조 동기화가
 *       REF_COLUMN 관계로 만든다), 자기 참조·복합 FK 는 TableRelation (관계 등록과 같은 검사, 미승인). 만들 수 없는 관계는 사유와 함께 FK 없이 만든다(P1-a). {@link #relationPlans}</li>
 *   <li>식별자 진단 : 만들 테이블·컬럼 이름, DDL 이 만들 PK 제약조건명(대상 스키마의 기존 이름과의 충돌 포함), 대상 스키마 이름을 프로젝트 DBMS 기준으로 판정한다
 *       ({@link DbIdentifierCheck}). 경고만 하고 생성 가능 여부(creatable)는 바꾸지 않는다. 미승인 엔터티·관계도 만들되 경고한다 (P15)</li>
 *   <li>막는 문제 : 이름을 만들 수 없음, 옮길 스키마에 같은 이름의 테이블·VIEW, 계획 안의 테이블명·컬럼명 중복, 용어 없음, 도메인 타입 없음. 이미 매핑된 엔터티는 건너뛴다(덮어쓰지 않는다)</li>
 *   <li>컬럼 순서 (P3, M-7 보강 2차) : 주 식별자 속성을 식별자의 속성 순서(SORT_ORD, 같으면 식별자 속성 ID)대로 먼저, 나머지는 속성 순서(SORT_ORD, ID). 컬럼 정렬 순서가
 *       DDL 의 PK 순서라 복합 PK 가 논리 식별자 순서가 된다. 새로 만드는 테이블에만 적용 (기존 테이블·매핑은 그대로, 식별자 ↔ PK 강제 일치 없음)</li>
 *   <li>속성 고르기 (P7) : 요청의 excludedAttributeIds 는 만들지 않는다 (컬럼·매핑·용어/도메인 검사 모두에서 빠진다). 주 식별자 속성은 제외를 요청해도 만든다 (서버 정규화 + 경고).
 *       고른 엔터티의 속성이 아닌 ID·중복 ID 는 400</li>
 *   <li>반영 (P11) : 프로젝트 행 배타 → 논리 모델 행·대상 스키마 행 (기다리지 않음 — 일반 저장은 행을 잠근 채 커밋 직전에 프로젝트 행을 공유 잠금으로 확인하므로 기다리면 교착,
 *       잡지 못하면 409) 순으로 잠그고 계획을 다시 만들어 planToken 과 비교한다(다르면 409 MODEL_CONFLICT). 같은 트랜잭션에서 생성 직전 모델 버전(GENERATE-요청 ID)을 남긴 뒤
 *       모두 미승인으로 만들고, 하나라도 실패하면 버전까지 전체를 되돌린다 (원본·모델 가져오기와 같은 순서)</li>
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
    private final ProjectRepository projectRepository;
    private final DdlIdentifierQueryRepository identifierRepository;
    private final ProjectWriteGuard projectWriteGuard;
    private final ModelVersionService versionService;
    private final LogicalRelationshipAttributeRepository pairRepository;
    private final com.lexorma.data.physical.service.PhysicalModelService physicalModelService;
    @PersistenceContext
    private EntityManager entityManager;

    static final String VERSION_PREFIX = "GENERATE-";
    private static final Map<String, Object> NO_WAIT = Map.of("jakarta.persistence.lock.timeout", 0);

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
        // 잠금 순서 : 프로젝트 행(배타, 맨 먼저 — 생성 전 버전이 한 시점의 모델) → 논리 모델(ID 순) → 대상 스키마 이름 공간.
        // 프로젝트를 잡은 뒤의 행 잠금은 기다리지 않는다 (원본 가져오기 SourceImportService.apply 와 같은 이유 : 기다리면 교착)
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        projectWriteGuard.lock(projectId);
        SchemaInfo schema = schemaService.resolveSchema(projectId, request.schemaId());
        try {
            for (Long modelId : chosen.stream().map(e -> e.getModel().getId()).distinct().sorted().toList()) {
                LogicalModel model = entityManager.find(LogicalModel.class, modelId, LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
                if (model == null || !projectId.equals(model.getProjectId())) throw invalid("논리 모델을 찾을 수 없습니다.");
            }
            entityManager.find(SchemaInfo.class, schema.getId(), LockModeType.PESSIMISTIC_WRITE, NO_WAIT);
        } catch (jakarta.persistence.PessimisticLockException | jakarta.persistence.LockTimeoutException e) {
            if (!lockNotAvailable(e)) throw e; // 교착 등 다른 오류는 409 로 바꾸지 않는다
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("다른 사용자가 같은 논리 모델이나 스키마를 바꾸는 중입니다. 잠시 뒤 다시 미리보기해주세요."));
        }
        PhysicalGenerationDto.Plan plan = plan(account, request);
        if (!plan.planToken().equals(request.planToken())) {
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", Messages.get("생성 계획"));
        }
        if (!plan.creatable()) throw invalid("만들 수 없는 항목이 있습니다. 미리보기의 오류를 먼저 고쳐주세요.");

        // 생성 직전 모델 버전 (P11) : 모델을 바꾸기 전, 같은 트랜잭션 — 생성이 실패하면 버전도 남지 않는다. creatable 이면 만들 테이블이 하나 이상이다
        String requestId = UUID.randomUUID().toString();
        ModelVersionDto.Summary version = versionService.createBeforeModelChange(projectId, VERSION_PREFIX + requestId,
                Messages.get("논리 → 물리 생성 직전 상태 (엔터티 {0}개, 요청 {1})", plan.tables().stream().filter(t -> "CREATE".equals(t.status())).count(), requestId),
                account.getUsername());

        Map<Long, LogicalEntity> byId = new HashMap<>();
        chosen.forEach(e -> byId.put(e.getId(), e));
        int nextOrder = tables.findAllByProjectId(projectId, org.springframework.data.domain.Pageable.unpaged()).getContent().stream()
                .map(TableInfo::getSortOrder).filter(Objects::nonNull).max(Integer::compare).orElse(0);
        List<Long> created = new ArrayList<>();
        Map<Long, TableInfo> createdTables = new HashMap<>();
        Map<Long, ColumnInfo> createdColumns = new HashMap<>(); // 속성 ID → 만든 컬럼
        Map<ColumnInfo, Long> columnAttribute = new java.util.IdentityHashMap<>();
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
                columnAttribute.put(column, attribute.getId());
                attributeMappings.save(AttributeColumnMapping.builder().mapping(mapping).attribute(attribute).column(column).build());
                columnCount++;
            }
            created.add(table.getId());
            createdTables.put(entity.getId(), table);
            table.getColumns().forEach(c -> createdColumns.put(columnAttribute.get(c), c));
        }

        // FK (4차) : 모든 테이블·컬럼을 만든 뒤 (순환·자기 참조도 대상이 먼저 있다). 일반 단일 FK 는 컬럼의 참조 테이블명으로 이미 넣었다 (커밋 직전 참조 동기화가 관계로 만든다)
        int foreignKeys = 0;
        columns.flush();
        // 관계 등록 검사(중복·참조 키)의 조회마다 자동 플러시가 새로 만든 테이블·컬럼 전체를 더티 체크하지 않게 COMMIT 모드로 두고,
        // FK 하나를 저장할 때마다 직접 내보낸다 — 다음 FK 의 중복 검사가 앞서 만든 FK 를 지금처럼 본다 (M-7 보강 5차 측정 : 반영 시간의 72%)
        FlushModeType previous = entityManager.getFlushMode();
        entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            for (PhysicalGenerationDto.RelationPlan relation : plan.relations()) {
                if (!relation.fkCreated()) continue;
                foreignKeys++;
                if ("SINGLE".equals(relation.fkType())) continue;
                TableInfo child = createdTables.get(relation.childEntityId());
                TableInfo parent = relation.parentTableId() == null ? createdTables.get(relation.parentEntityId()) : tables.findById(relation.parentTableId()).orElseThrow();
                List<ColumnInfo> childColumns = relation.columns().stream().map(c -> createdColumns.get(c.childAttributeId())).toList();
                List<ColumnInfo> parentColumns = relation.columns().stream().map(c -> relation.parentTableId() == null ? createdColumns.get(c.parentAttributeId())
                        : parent.getColumns().stream().filter(col -> col.getColumnName().equals(c.parentColumnName())).findFirst().orElseThrow()).toList();
                physicalModelService.createGeneratedForeignKey(projectId, child, parent, childColumns, parentColumns, relation.constraintName());
                entityManager.flush();
            }
        } finally {
            entityManager.setFlushMode(previous);
        }
        return new PhysicalGenerationDto.Result(created.size(), columnCount, created, version.id(), version.versionName(), foreignKeys);
    }

    /**
     * 계획 계산은 읽기만 한다 : 계산하는 동안 플러시 모드를 COMMIT 으로 둔다. AUTO 면 조회(테이블·VIEW 이름 충돌, 표준 단어 …)마다 영속성 컨텍스트 전체를
     * 더티 체크해 엔터티 수 × 조회 수로 느려진다 (M-7 보강 5차 측정 : 100개 요청 미리보기 시간의 93% 이상). 반영의 쓰기는 계산이 끝난 뒤라 원래 모드로 돌린다.
     */
    private PhysicalGenerationDto.Plan plan(AccountDto account, PhysicalGenerationDto.Request request) {
        FlushModeType previous = entityManager.getFlushMode();
        entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            return computePlan(account, request);
        } finally {
            entityManager.setFlushMode(previous);
        }
    }

    private PhysicalGenerationDto.Plan computePlan(AccountDto account, PhysicalGenerationDto.Request request) {
        Long projectId = account.getProjectId();
        List<LogicalEntity> chosen = load(projectId, request == null ? null : request.entityIds());
        Set<Long> excludeRequested = exclusions(chosen, request.excludedAttributeIds());
        SchemaInfo schema = schemaService.resolveSchema(projectId, request.schemaId());
        // 기본 스키마를 이번에 만들었다면 아래 JDBC 조회(식별자 진단)도 보도록 그때만 내보낸다 (변경이 없는데 flush 하면 플러시 리스너가 돌며 문장이 늘었다)
        if (entityManager.unwrap(org.hibernate.Session.class).isDirty()) entityManager.flush();
        DbType db = projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);

        // 1) 테이블명 : 이미 매핑된 엔터티는 그 테이블명, 새로 만들 엔터티는 표준 단어 규칙
        Map<Long, String> tableNameByEntity = new HashMap<>();
        Map<Long, PhysicalGenerationDto.TablePlan> skipped = new LinkedHashMap<>();
        Map<Long, TableNameDto> generated = new HashMap<>();
        Map<Long, Optional<EntityTableMapping>> mappingByEntity = new HashMap<>();
        chosen.forEach(e -> mappingByEntity.put(e.getId(), Optional.empty()));
        entityMappings.findAllByEntityIds(mappingByEntity.keySet()).forEach(m -> mappingByEntity.put(m.getEntity().getId(), Optional.of(m)));
        for (LogicalEntity entity : chosen) {
            var existing = mappingByEntity.get(entity.getId());
            if (existing.isPresent()) {
                tableNameByEntity.put(entity.getId(), existing.get().getTable().getTableName());
                skipped.put(entity.getId(), new PhysicalGenerationDto.TablePlan(entity.getId(), entity.getEntityName(), "SKIP", existing.get().getTable().getTableName(),
                        existing.get().getTable().getSchema().getSchemaName(), List.of(), List.of(),
                        List.of(Messages.get("이미 테이블 {0} 에 매핑된 엔터티라 만들지 않습니다.", existing.get().getTable().getTableName())), List.of()));
                continue;
            }
            TableNameDto name = tableDesignService.generateTableName(entity.getEntityName(), account);
            generated.put(entity.getId(), name);
            if (name.valid()) tableNameByEntity.put(entity.getId(), name.tableName().toUpperCase(Locale.ROOT));
        }

        Map<String, Integer> nameUse = new HashMap<>();
        tableNameByEntity.forEach((id, name) -> { if (!skipped.containsKey(id)) nameUse.merge(name, 1, Integer::sum); });
        List<PhysicalGenerationDto.TablePlan> result = new ArrayList<>();
        // 스키마는 이름으로 (기본 스키마는 처음 조회 때 만들어질 수 있다). v2 : FK 추론 중단 + DBMS·식별자 진단·관계 계획 (M-7 보강 1차),
        // v3 : 정규화된 속성 선택·컬럼 순서 (2차), v4 : 관계는 속성 짝 상태로 (3차), v5 : FK 생성 계획 — 짝·컬럼·유형·이름·부모 테이블 (4차)
        StringBuilder canonical = new StringBuilder("v5|").append(schema.getSchemaName()).append('|').append(db).append('|');
        // 관계 → FK 계획을 테이블보다 먼저 정한다 : READY 관계의 자식 속성은 반드시 만든다 (P7), 일반 단일 FK 는 자식 컬럼의 참조 테이블명이 된다
        List<PhysicalGenerationDto.RelationPlan> relationPlans = relationPlans(db, schema, chosen, skipped.keySet(), tableNameByEntity, mappingByEntity, canonical);
        Map<Long, Set<Long>> foreignKeyAttributes = new HashMap<>();
        Map<Long, String> refTableByAttribute = new HashMap<>();
        relationPlans.stream().filter(PhysicalGenerationDto.RelationPlan::fkCreated).forEach(r -> {
            r.columns().forEach(c -> foreignKeyAttributes.computeIfAbsent(r.childEntityId(), id -> new HashSet<>()).add(c.childAttributeId()));
            if (r.refTableName() != null) refTableByAttribute.put(r.columns().get(0).childAttributeId(), r.refTableName());
        });
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

            List<LogicalAttribute> ordered = columnOrder(entity);
            Set<Long> primary = primaryAttributeIds(entity);
            if (primary.isEmpty()) warnings.add(Messages.get("주 식별자가 없어 PK 없이 만듭니다."));
            if (!Boolean.TRUE.equals(entity.getIsApproval())) warnings.add(Messages.get("엔터티 {0} 이(가) 승인 전입니다.", entity.getEntityName()));

            // 속성 선택 (P7) : 주 식별자 속성과 만들 FK 의 자식 속성은 제외할 수 없다 — 요청해도 만든다
            Set<Long> fkAttributes = foreignKeyAttributes.getOrDefault(entity.getId(), Set.of());
            List<PhysicalGenerationDto.AttributeChoice> choices = new ArrayList<>();
            for (LogicalAttribute attribute : ordered) {
                boolean requested = excludeRequested.contains(attribute.getId());
                boolean isPrimary = primary.contains(attribute.getId());
                boolean isForeignKey = fkAttributes.contains(attribute.getId());
                if (requested && isPrimary) warnings.add(Messages.get("주 식별자 속성 {0} 은(는) 제외할 수 없어 포함했습니다.", attribute.getAttributeName()));
                else if (requested && isForeignKey) warnings.add(Messages.get("FK 속성 {0} 은(는) 제외할 수 없어 포함했습니다.", attribute.getAttributeName()));
                choices.add(new PhysicalGenerationDto.AttributeChoice(attribute.getId(), attribute.getAttributeName(), isPrimary, requested && !isPrimary && !isForeignKey,
                        requested, isForeignKey));
            }
            Set<Long> excluded = new HashSet<>();
            choices.stream().filter(PhysicalGenerationDto.AttributeChoice::excluded).forEach(c -> excluded.add(c.attributeId()));
            canonical.append("x").append(excluded.stream().sorted().toList());

            List<PhysicalGenerationDto.ColumnPlan> columnPlans = new ArrayList<>();
            Set<String> columnNames = new HashSet<>();
            for (LogicalAttribute attribute : ordered) {
                if (excluded.contains(attribute.getId())) continue;
                StandardTerm term = attribute.getStandardTerm();
                canonical.append('a').append(attribute.getId()).append('@').append(attribute.modifiedAt());
                if (term != null) canonical.append('t').append(term.getId()).append('@').append(term.modifiedAt());
                ColumnSpec spec = columnSpec(attribute);
                if (spec.error() != null) {
                    errors.add(spec.error());
                    continue;
                }
                if (!columnNames.add(spec.columnName())) {
                    errors.add(Messages.get("같은 컬럼명({0})이 되는 속성이 둘 이상입니다.", spec.columnName()));
                    continue;
                }
                boolean pk = primary.contains(attribute.getId());
                boolean nullable = !pk && !Boolean.TRUE.equals(attribute.getIsRequired());
                if (!Boolean.TRUE.equals(term.getIsApproval())) warnings.add(Messages.get("속성 {0} 의 표준 용어가 승인 전입니다.", attribute.getAttributeName()));
                columnPlans.add(new PhysicalGenerationDto.ColumnPlan(attribute.getId(), attribute.getAttributeName(), term.getId(), spec.columnName(), spec.dataType(),
                        spec.maxLength(), spec.precision(), spec.scale(), pk, nullable, refTableByAttribute.get(attribute.getId()), term.getIsApproval()));
            }
            if (entity.getAttributes().isEmpty()) errors.add(Messages.get("속성이 없는 엔터티는 만들 수 없습니다."));
            else if (excluded.size() == entity.getAttributes().size()) errors.add(Messages.get("모든 속성을 제외해 만들 컬럼이 없습니다."));
            String status = errors.isEmpty() ? "CREATE" : "ERROR";
            canonical.append(status).append(tableName).append(columnPlans).append(';');
            result.add(new PhysicalGenerationDto.TablePlan(entity.getId(), entity.getEntityName(), status, tableName, schema.getSchemaName(), columnPlans, errors, warnings,
                    choices));
        }
        List<PhysicalGenerationDto.IdentifierIssue> identifiers = identifierIssues(projectId, db, schema, result, relationPlans);
        canonical.append("id").append(DbIdentifierRules.VERSION).append('|');
        identifiers.forEach(i -> canonical.append(i.code()).append(i.kind()).append(i.entityId()).append(i.attributeId()).append(i.name()).append(i.ddlName())
                .append(i.related()).append(';'));
        boolean creatable = result.stream().noneMatch(t -> "ERROR".equals(t.status())) && result.stream().anyMatch(t -> "CREATE".equals(t.status()));
        return new PhysicalGenerationDto.Plan(result, creatable, sha256(canonical.toString()), db.name(), identifiers, relationPlans);
    }

    /**
     * 고른 엔터티(자식, 건너뛰는 엔터티 제외)를 대상으로 하는 논리 관계마다 FK 생성 계획 (M-7 보강 4차, P1·P2). 명시적 속성 짝만 쓴다 — 용어·이름으로 추론하지 않는다.
     * <ol>
     *   <li>짝 판정({@link LogicalRelationshipPairs}) 이 COMPLETE 가 아니면 NO_PAIRS · PARTIAL_PAIRS · IDENTIFIER_MISMATCH</li>
     *   <li>부모 테이블 : 이번에 만들 테이블(고른 엔터티, 테이블명이 정해짐) 또는 이미 매핑된 테이블. 없으면 PARENT_TABLE_UNAVAILABLE</li>
     *   <li>부모 컬럼 : 새 테이블이면 부모 주 식별자 속성의 컬럼(항상 만든다), 기존 테이블이면 그 속성에 매핑된 컬럼 — 매핑된 컬럼 구성이 그 테이블 PK 전체와 같아야 한다
     *       (아니면 UNSUPPORTED · PARENT_KEY_NOT_TABLE_KEY, 매핑이 없으면 COLUMN_UNAVAILABLE). 기존 테이블은 고치지 않는다</li>
     *   <li>자식 컬럼 : 자식 속성으로 컬럼을 만들 수 있어야 한다 (용어·약어·도메인 — 아니면 COLUMN_UNAVAILABLE, 그 속성을 필수로 만들지 않는다)</li>
     *   <li>자기 참조에서 자식 속성이 모두 부모 키 속성 자신이면 UNSUPPORTED · SELF_KEY_ONLY, 타입이 FK 로 연결할 수 없는 조합이면 UNSUPPORTED · TYPE_INCOMPATIBLE
     *       (관계 등록과 같은 {@link TableDdlGenerator#isReferenceTypeCompatible})</li>
     *   <li>자식 컬럼은 FK 하나에만 : 앞선(자식 ID → 관계 ID 순) READY 관계가 이미 쓴 자식 속성이면 CONFLICT · CHILD_COLUMN_IN_USE (서로 다른 관계를 합치지 않는다)</li>
     * </ol>
     * 유형 (P2) : 일반 단일 = 자식 컬럼의 참조 테이블명 (부모 PK 가 그 컬럼 하나일 때만 표현된다 — 부모가 다른 스키마면 스키마명.테이블명), 자기 참조 단일·복합 = TableRelation.
     */
    private List<PhysicalGenerationDto.RelationPlan> relationPlans(DbType db, SchemaInfo schema, List<LogicalEntity> chosen, Set<Long> skipped,
                                                                   Map<Long, String> tableNameByEntity, Map<Long, Optional<EntityTableMapping>> mappingByEntity,
                                                                   StringBuilder canonical) {
        Map<Long, List<LogicalRelationship>> byModel = new HashMap<>();
        List<PhysicalGenerationDto.RelationPlan> plans = new ArrayList<>();
        Map<Long, List<LogicalRelationshipAttribute>> pairsByRelationship = new HashMap<>();
        Set<Long> modelIds = new java.util.TreeSet<>();
        chosen.stream().filter(e -> !skipped.contains(e.getId())).forEach(e -> modelIds.add(e.getModel().getId()));
        List<Long> relationshipIds = modelIds.stream().flatMap(id -> byModel.computeIfAbsent(id, relationships::findAllByModelId).stream()).map(LogicalRelationship::getId).toList();
        if (!relationshipIds.isEmpty()) {
            pairRepository.findAllByRelationshipIds(relationshipIds).forEach(p -> pairsByRelationship.computeIfAbsent(p.getRelationship().getId(), id -> new ArrayList<>()).add(p));
        }
        Set<Long> chosenIds = new HashSet<>();
        chosen.forEach(e -> chosenIds.add(e.getId()));
        Set<Long> usedChildAttributes = new HashSet<>();
        for (LogicalEntity child : chosen) {
            if (skipped.contains(child.getId())) continue;
            List<LogicalRelationship> incoming = byModel.computeIfAbsent(child.getModel().getId(), relationships::findAllByModelId).stream()
                    .filter(r -> r.getTargetEntity().getId().equals(child.getId()))
                    .sorted(Comparator.comparing(LogicalRelationship::getId)).toList();
            for (LogicalRelationship relationship : incoming) {
                LogicalEntity parent = relationship.getSourceEntity();
                boolean self = parent.getId().equals(child.getId());
                LogicalModelDto.PairState pairState = LogicalRelationshipPairs.evaluate(relationship, pairsByRelationship.getOrDefault(relationship.getId(), List.of()));
                List<LogicalRelationshipAttribute> pairs = pairsByRelationship.getOrDefault(relationship.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(LogicalRelationshipAttribute::getSortOrder)).toList();
                String childTable = tableNameByEntity.get(child.getId());

                String status = switch (pairState.status()) {
                    case "COMPLETE" -> "READY";
                    case "NONE" -> "NO_PAIRS";
                    case "PARTIAL" -> "PARTIAL_PAIRS";
                    default -> "IDENTIFIER_MISMATCH";
                };
                String reason = switch (status) {
                    case "NO_PAIRS" -> "ATTRIBUTE_PAIRS_MISSING";
                    case "PARTIAL_PAIRS" -> "ATTRIBUTE_PAIRS_INCOMPLETE";
                    case "IDENTIFIER_MISMATCH" -> "ATTRIBUTE_PAIRS_INVALID";
                    default -> null;
                };
                // 부모 테이블 : 이번에 만들 테이블 → 이미 매핑된 테이블
                boolean parentNew = chosenIds.contains(parent.getId()) && !skipped.contains(parent.getId());
                TableInfo parentExisting = parentNew ? null : mappingByEntity.computeIfAbsent(parent.getId(), entityMappings::findByEntity_Id)
                        .map(EntityTableMapping::getTable).orElse(null);
                String parentTable = parentNew ? tableNameByEntity.get(parent.getId()) : parentExisting == null ? null : parentExisting.getTableName();
                List<PhysicalGenerationDto.FkColumn> columns = new ArrayList<>();
                if (reason == null && parentTable == null) {
                    status = "PARENT_TABLE_UNAVAILABLE";
                    reason = "PARENT_TABLE_MISSING";
                }
                if (reason == null) {
                    List<ColumnInfo> parentColumns = new ArrayList<>();
                    List<ColumnInfo> childColumns = new ArrayList<>();
                    for (int i = 0; i < pairs.size() && reason == null; i++) {
                        LogicalRelationshipAttribute pair = pairs.get(i);
                        ColumnSpec childSpec = columnSpec(pair.getChildAttribute());
                        ColumnInfo parentColumn;
                        if (parentNew) {
                            ColumnSpec parentSpec = columnSpec(pair.getParentAttribute());
                            parentColumn = parentSpec.error() == null ? parentSpec.transientColumn() : null;
                        } else {
                            parentColumn = attributeMappings.findByAttribute_Id(pair.getParentAttribute().getId()).map(AttributeColumnMapping::getColumn)
                                    .filter(c -> c.getTableInfo() != null && c.getTableInfo().getId().equals(parentExisting.getId())).orElse(null);
                        }
                        if (parentColumn == null || childSpec.error() != null) {
                            status = "COLUMN_UNAVAILABLE";
                            reason = "COLUMN_UNAVAILABLE";
                            break;
                        }
                        parentColumns.add(parentColumn);
                        childColumns.add(childSpec.transientColumn());
                        columns.add(new PhysicalGenerationDto.FkColumn(i + 1, pair.getParentAttribute().getId(), parentColumn.getColumnName(), pair.getChildAttribute().getId(),
                                childSpec.columnName()));
                    }
                    if (reason == null && !parentNew) {
                        Set<String> tableKey = new HashSet<>(parentExisting.getColumns().stream().filter(c -> Boolean.TRUE.equals(c.getIsPk())).map(ColumnInfo::getColumnName).toList());
                        Set<String> referenced = new HashSet<>(parentColumns.stream().map(ColumnInfo::getColumnName).toList());
                        if (tableKey.isEmpty() || !tableKey.equals(referenced) || referenced.size() != parentColumns.size()) {
                            status = "UNSUPPORTED";
                            reason = "PARENT_KEY_NOT_TABLE_KEY";
                        }
                    }
                    if (reason == null && self && pairs.stream().allMatch(p -> p.getParentAttribute().getId().equals(p.getChildAttribute().getId()))) {
                        status = "UNSUPPORTED";
                        reason = "SELF_KEY_ONLY";
                    }
                    if (reason == null) {
                        for (int i = 0; i < childColumns.size(); i++) {
                            if (!TableDdlGenerator.isReferenceTypeCompatible(db, childColumns.get(i), parentColumns.get(i))) {
                                status = "UNSUPPORTED";
                                reason = "TYPE_INCOMPATIBLE";
                                break;
                            }
                        }
                    }
                    if (reason == null && pairs.stream().anyMatch(p -> usedChildAttributes.contains(p.getChildAttribute().getId()))) {
                        status = "CONFLICT";
                        reason = "CHILD_COLUMN_IN_USE";
                    }
                }
                boolean ready = reason == null;
                String fkType = pairs.isEmpty() || !"COMPLETE".equals(pairState.status()) ? null
                        : self ? (pairs.size() == 1 ? "SELF_SINGLE" : "SELF_COMPOSITE") : (pairs.size() == 1 ? "SINGLE" : "COMPOSITE");
                String refTableName = null;
                String constraintName = null;
                if (ready) {
                    pairs.forEach(p -> usedChildAttributes.add(p.getChildAttribute().getId()));
                    String firstChild = columns.get(0).childColumnName();
                    // SINGLE 은 DDL 이 FK_자식_컬럼 으로 만들고, TableRelation 은 관계 등록과 같은 기본 이름을 쓴다 — 둘 다 같은 꼴이다
                    constraintName = DbIdentifierRules.foreignKeyName(childTable == null ? "" : childTable, firstChild);
                    if ("SINGLE".equals(fkType)) {
                        boolean sameSchema = parentNew || parentExisting.getSchema() == null || Objects.equals(parentExisting.getSchema().getId(), schema.getId());
                        refTableName = sameSchema ? parentTable : parentExisting.getSchema().getSchemaName() + "." + parentTable;
                    }
                } else {
                    columns.clear();
                }

                List<String> reasons = new ArrayList<>();
                if (reason != null) reasons.add(reason);
                if (self) reasons.add("SELF_REFERENCE");
                if (incoming.stream().filter(r -> r.getSourceEntity().getId().equals(parent.getId())).count() > 1) reasons.add("MULTIPLE_RELATIONSHIPS");
                if (pairState.parentKeySize() > 1) reasons.add("COMPOSITE_PARENT_KEY");
                if (pairState.parentKeySize() == 0 && !reasons.contains("PARENT_KEY_MISSING")) reasons.add("PARENT_KEY_MISSING");
                if (parentTable == null && !reasons.contains("PARENT_TABLE_MISSING")) reasons.add("PARENT_TABLE_MISSING");

                // 계획 근거 : 관계 ID·방향, 상태·유형, 짝(속성 ID)·컬럼명, 부모 테이블(새 / 기존 ID), FK 이름·참조 테이블명 — 메시지·표시 이름은 넣지 않는다
                canonical.append('r').append(relationship.getId()).append(parent.getId()).append('>').append(child.getId()).append(status).append(fkType).append(reasons)
                        .append(parentNew ? "new" : parentExisting == null ? "-" : "t" + parentExisting.getId()).append(constraintName).append(refTableName);
                pairs.forEach(p -> canonical.append('p').append(p.getParentAttribute().getId()).append('>').append(p.getChildAttribute().getId()).append('#').append(p.getSortOrder()));
                columns.forEach(c -> canonical.append('c').append(c.parentColumnName()).append('<').append(c.childColumnName()));
                canonical.append(';');
                plans.add(new PhysicalGenerationDto.RelationPlan(relationship.getId(), relationship.getRelationshipName(), parent.getId(), parent.getEntityName(),
                        child.getId(), child.getEntityName(), ready, reasons, relationMessage(status, reason, fkType, parent, child), relationship.getIsApproval(),
                        pairState.status(), pairState.warnings(), pairState.pairs(), status, fkType, parentTable, parentExisting == null ? null : parentExisting.getId(),
                        childTable, constraintName, refTableName, columns));
            }
        }
        return plans;
    }

    private static String relationMessage(String status, String reason, String fkType, LogicalEntity parent, LogicalEntity child) {
        String p = parent.getEntityName();
        String c = child.getEntityName();
        return switch (status) {
            case "READY" -> Messages.get("관계 {0} → {1} : FK 를 만듭니다 ({2}).", p, c, fkTypeLabel(fkType));
            case "NO_PAIRS" -> Messages.get("관계 {0} → {1} : 부모 식별자 ↔ 자식 속성 짝이 지정되지 않아 FK 를 만들지 않습니다.", p, c);
            case "PARTIAL_PAIRS" -> Messages.get("관계 {0} → {1} : 속성 짝이 부모 주 식별자의 일부만 이어 FK 를 만들지 않습니다.", p, c);
            case "IDENTIFIER_MISMATCH" -> Messages.get("관계 {0} → {1} : 속성 짝이 지금 부모 주 식별자와 맞지 않아 FK 를 만들지 않습니다. 관계의 속성 짝을 고쳐주세요.", p, c);
            case "PARENT_TABLE_UNAVAILABLE" -> Messages.get("관계 {0} → {1} : 부모 테이블이 없어(이번에 만들지 않고 매핑도 없음) FK 를 만들지 않습니다.", p, c);
            case "COLUMN_UNAVAILABLE" -> Messages.get("관계 {0} → {1} : 짝의 부모·자식 컬럼을 정할 수 없어(용어·도메인 없음, 부모 속성 매핑 없음) FK 를 만들지 않습니다.", p, c);
            case "CONFLICT" -> Messages.get("관계 {0} → {1} : 자식 속성이 다른 관계의 FK 에 이미 쓰여 FK 를 만들지 않습니다.", p, c);
            default -> switch (reason) {
                case "SELF_KEY_ONLY" -> Messages.get("관계 {0} → {1} : 자기 참조인데 자식 속성이 부모 키 속성 자신이라 FK 를 만들지 않습니다.", p, c);
                case "TYPE_INCOMPATIBLE" -> Messages.get("관계 {0} → {1} : 부모·자식 컬럼의 데이터 타입이 FK 로 연결할 수 없는 조합이라 FK 를 만들지 않습니다.", p, c);
                default -> Messages.get("관계 {0} → {1} : 기존 부모 테이블의 PK 가 짝의 부모 컬럼과 달라 FK 를 만들지 않습니다 (기존 테이블은 고치지 않습니다).", p, c);
            };
        };
    }

    private static String fkTypeLabel(String fkType) {
        return switch (fkType == null ? "" : fkType) {
            case "SINGLE" -> Messages.get("일반 단일 FK");
            case "SELF_SINGLE" -> Messages.get("자기 참조 단일 FK");
            case "COMPOSITE" -> Messages.get("복합 FK");
            case "SELF_COMPOSITE" -> Messages.get("자기 참조 복합 FK");
            default -> "-";
        };
    }

    /** 속성으로 만들 컬럼 (화면의 컬럼 등록과 같은 해석). error 가 있으면 만들 수 없다 */
    private record ColumnSpec(String columnName, String dataType, Integer maxLength, Integer precision, Integer scale, String error) {
        ColumnInfo transientColumn() {
            return ColumnInfo.builder().columnName(columnName).dataType(dataType).maxLength(maxLength).precision(precision == null ? null : BigDecimal.valueOf(precision))
                    .scale(scale).build();
        }
    }

    /**
     * 컬럼명 : 표준 용어에 저장된 영문 약어(앞뒤 공백 제거, 대문자) — 화면·품질 진단의 기대 컬럼명({@link StandardNames#expectedColumnName})과 같은 값.
     * 단어 약어를 다시 이어 만들지 않는다 (M-7 후속, 2026-11-18 — 예전에는 단어 조합을 먼저 써 저장값이 다른 용어의 컬럼이 진단의 컬럼명 불일치가 됐다).
     * 약어가 없거나 비었으면 지금처럼 오류 (단어 조합으로 대신하지 않는다).
     * 도메인 : 타입명에 길이를 적었으면 그것, 아니면 길이·소수점 칸 (0 은 없음)
     */
    private static ColumnSpec columnSpec(LogicalAttribute attribute) {
        StandardTerm term = attribute.getStandardTerm();
        if (term == null) {
            return new ColumnSpec(null, null, null, null, null, Messages.get("속성 {0} 에 표준 용어가 없습니다. 논리 모델에서 표준 용어를 먼저 연결해주세요.", attribute.getAttributeName()));
        }
        String expected = StandardNames.expectedColumnName(term.getCommonStandardTermAbbreviation());
        String columnName = expected == null ? null : expected.toUpperCase(Locale.ROOT);
        var domain = term.getStandardDomain();
        Matcher type = TYPE.matcher(domain == null || domain.getDataType() == null ? "" : domain.getDataType());
        if (columnName == null) return new ColumnSpec(null, null, null, null, null, Messages.get("속성 {0} 의 표준 용어에 영문 약어가 없습니다.", attribute.getAttributeName()));
        if (!type.matches() || type.group(1).isBlank()) {
            return new ColumnSpec(null, null, null, null, null, Messages.get("속성 {0} 의 표준 용어에 도메인 데이터 타입이 없습니다.", attribute.getAttributeName()));
        }
        String base = type.group(1).toUpperCase(Locale.ROOT);
        Integer first = type.group(2) != null ? Integer.valueOf(type.group(2)) : positive(domain.getDataLength());
        Integer second = type.group(3) != null ? Integer.valueOf(type.group(3)) : positive(domain.getDataDecimalLength());
        boolean numeric = NUMERIC.contains(base);
        return new ColumnSpec(columnName, base, numeric ? null : first, numeric ? first : null, numeric ? second : null, null);
    }

    /**
     * 식별자 진단 : 대상 스키마의 기존 이름(테이블·컬럼·제약조건·인덱스)과 만들 테이블을 한 모델로 판정하고, 만들 객체와 대상 스키마 이름의 결과만 남긴다.
     * 만들 테이블·컬럼은 아직 ID 가 없어 음수 임시 ID(-엔터티 ID, -속성 ID)로 넣는다
     */
    private List<PhysicalGenerationDto.IdentifierIssue> identifierIssues(Long projectId, DbType db, SchemaInfo schema, List<PhysicalGenerationDto.TablePlan> plans,
                                                                       List<PhysicalGenerationDto.RelationPlan> relations) {
        DbIdentifierCheck.Model existing = identifierRepository.model(projectId, schema.getId());
        List<DbIdentifierCheck.Table> tables = new ArrayList<>(existing.tables());
        List<DbIdentifierCheck.Column> columns = new ArrayList<>(existing.columns());
        Map<Long, String> names = new HashMap<>();
        existing.tables().forEach(t -> names.put(t.id(), t.name()));
        for (PhysicalGenerationDto.TablePlan plan : plans) {
            if ("SKIP".equals(plan.status()) || plan.tableName() == null) continue;
            tables.add(new DbIdentifierCheck.Table(-plan.entityId(), schema.getId(), plan.tableName()));
            names.put(-plan.entityId(), plan.tableName());
            // 일반 단일 FK(참조 테이블명) 컬럼은 DDL 이 FK_테이블_컬럼 이름을 만든다
            plan.columns().forEach(c -> columns.add(new DbIdentifierCheck.Column(-c.attributeId(), -plan.entityId(), c.columnName(), c.pk(), false, c.refTableName() != null)));
        }
        // TableRelation 으로 만들 FK (자기 참조·복합) : 저장된 이름과 같이 본다
        List<DbIdentifierCheck.StoredName> stored = new ArrayList<>(existing.storedNames());
        relations.stream().filter(r -> r.fkCreated() && !"SINGLE".equals(r.fkType()))
                .forEach(r -> stored.add(new DbIdentifierCheck.StoredName(DbIdentifierRules.Kind.FOREIGN_KEY, -r.relationshipId(), -r.childEntityId(), r.constraintName())));
        DbIdentifierCheck.Model model = new DbIdentifierCheck.Model(existing.schemas(), tables, columns, stored);
        List<PhysicalGenerationDto.IdentifierIssue> result = new ArrayList<>();
        for (DbIdentifierCheck.Finding finding : DbIdentifierCheck.check(db, model)) {
            boolean planned = finding.tableId() != null && finding.tableId() < 0;
            if (!planned && finding.tableId() != null) continue; // 기존 객체의 진단은 표준화 품질 진단에서 본다
            DbIdentifierRules.Issue issue = finding.issue();
            List<String> related = finding.others().stream()
                    .map(o -> o.ddlName() + " (" + DbIdentifierRules.kindLabel(o.kind()) + ", " + names.getOrDefault(o.tableId(), "-") + ")").toList();
            result.add(new PhysicalGenerationDto.IdentifierIssue(planned ? -finding.tableId() : null,
                    finding.columnId() != null && finding.columnId() < 0 ? -finding.columnId() : null,
                    issue.code().name(), StandardizationRule.valueOf(issue.code().name()).severity().name(), issue.kind().name(), issue.name(), issue.ddlName(),
                    issue.length(), issue.maxLength(), DbIdentifierRules.basis(db, issue.code()), DbIdentifierRules.reason(db, issue, related),
                    DbIdentifierRules.guide(issue.code()), related));
        }
        return result;
    }

    /** 주 식별자 속성 (없으면 빈 집합) */
    private static Set<Long> primaryAttributeIds(LogicalEntity entity) {
        Set<Long> ids = new LinkedHashSet<>();
        entity.getIdentifiers().stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst()
                .ifPresent(i -> i.getAttributes().forEach(a -> ids.add(a.getAttribute().getId())));
        return ids;
    }

    /**
     * 컬럼을 만들 순서 (P3) : 주 식별자 속성을 식별자 순서(SORT_ORD, 같거나 없으면 식별자 속성 ID)대로, 같은 속성이 두 번 있으면 한 번만 → 나머지 속성을 속성 순서(SORT_ORD, ID)대로
     */
    static List<LogicalAttribute> columnOrder(LogicalEntity entity) {
        Map<Long, LogicalAttribute> own = new LinkedHashMap<>();
        entity.getAttributes().stream()
                .sorted(Comparator.comparing(LogicalAttribute::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(LogicalAttribute::getId))
                .forEach(a -> own.put(a.getId(), a));
        Map<Long, LogicalAttribute> ordered = new LinkedHashMap<>();
        entity.getIdentifiers().stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst().ifPresent(identifier -> identifier.getAttributes().stream()
                .sorted(Comparator.comparing(LogicalIdentifierAttribute::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LogicalIdentifierAttribute::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(a -> own.get(a.getAttribute().getId())).filter(Objects::nonNull)
                .forEach(a -> ordered.putIfAbsent(a.getId(), a)));
        own.values().forEach(a -> ordered.putIfAbsent(a.getId(), a));
        return new ArrayList<>(ordered.values());
    }

    /** 제외 요청 검증 : 고른 엔터티의 속성만, 중복·null 없이 (조용히 무시하지 않는다) */
    private static Set<Long> exclusions(List<LogicalEntity> chosen, List<Long> requested) {
        if (requested == null || requested.isEmpty()) return Set.of();
        Set<Long> known = new HashSet<>();
        chosen.forEach(e -> e.getAttributes().forEach(a -> known.add(a.getId())));
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : requested) {
            if (id == null) throw invalid("제외할 속성 ID 가 비어 있습니다.");
            if (!result.add(id)) throw invalid("제외할 속성 ID 가 중복되었습니다. (ID {0})", id);
            if (!known.contains(id)) throw invalid("제외할 속성을 고른 엔터티에서 찾을 수 없습니다. (ID {0})", id);
        }
        return result;
    }

    /** NOWAIT 로 잠금을 바로 얻지 못했다 : PostgreSQL SQLSTATE 55P03, H2 오류 코드 50200 (SourceImportService.lockNotAvailable 과 같은 판정) */
    private static boolean lockNotAvailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return "55P03".equals(sql.getSQLState()) || sql.getErrorCode() == 50200;
        }
        return false;
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private List<LogicalEntity> load(Long projectId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw invalid("만들 엔터티를 고르세요.");
        if (ids.size() > ENTITY_MAX) throw invalid("한 번에 {0}개 엔터티까지 만들 수 있습니다.", ENTITY_MAX);
        // 한 번에 읽는다 — 엔터티마다 따로 읽으면 100개 요청에 조회 100번 (M-7 보강 5차 측정)
        List<Long> sorted = ids.stream().distinct().sorted().toList();
        Map<Long, LogicalEntity> found = new HashMap<>();
        entities.findAllByIds(sorted, projectId).forEach(e -> found.put(e.getId(), e));
        List<LogicalEntity> result = new ArrayList<>();
        for (Long id : sorted) {
            LogicalEntity entity = found.get(id);
            if (entity == null) throw invalid("엔터티를 찾을 수 없습니다. (ID {0})", id);
            result.add(entity);
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
