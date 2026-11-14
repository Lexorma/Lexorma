package com.lexorma.data.table.service.impl;

import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.ObjectNamespace;
import com.lexorma.data.view.repository.PhysicalViewRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.dto.TableDdlDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.dto.TableNameDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.table.service.TableNameResolver;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.i18n.Messages;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.dto.ProjectMemberDto;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.common.utils.ExcelUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TableDesignServiceImpl implements TableDesignService {

    private final TableInfoRepository tableInfoRepository;
    private final ColumnInfoRepository columnInfoRepository;
    private final StandardWordRepository standardWordRepository;
    private final ProjectRepository projectRepository;
    private final EntityManager entityManager;
    private final SchemaService schemaService;
    private final PhysicalModelService physicalModelService;
    private final EntityTableMappingRepository entityTableMappingRepository;
    private final ObjectNamespace objectNamespace;
    private final PhysicalViewRepository viewRepository;
    private final TableRelationRepository relationRepository;
    private static final int FIRST_ROW = 1;

    /** 그리드 검색 컬럼 → 엔티티 필드 (부분 일치) */
    private static final Set<String> SEARCH_COLUMNS = Set.of("tableName", "tableDescription", "tableKorName");

    @Override
    @Transactional(readOnly = true)
    public Page<TableInfoDto> selectTableInfoData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery, Long schemaId) {
        Long projectId = accountDto.getProjectId();

        Specification<TableInfo> where = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("projectId"), projectId));
            if (schemaId != null) {
                // 다른 프로젝트의 스키마 ID 는 projectId 조건 때문에 결과가 없다
                predicates.add(builder.equal(root.get("schema").get("id"), schemaId));
            }
            if (searchQuery != null && !searchQuery.trim().isEmpty() && searchColumn != null && SEARCH_COLUMNS.contains(searchColumn)) {
                predicates.add(builder.like(root.get(searchColumn), "%" + searchQuery + "%"));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };

        // DTO 에 펼쳐진 스키마명을 실제 엔티티 연관관계 경로로 정렬한다.
        Sort sort = Sort.by(pageable.getSort().stream()
                .map(order -> "schemaName".equals(order.getProperty()) ? order.withProperty("schema.schemaName") : order)
                .toList());

        return tableInfoRepository.findAll(where, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort))
                .map(TableInfoDto::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public TableInfoDto selectTable(Long tableId, AccountDto accountDto) {
        TableInfo tableInfo = tableInfoRepository.findByIdAndProjectId(tableId, accountDto.getProjectId());
        if (tableInfo == null) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }
        return TableInfoDto.fromEntity(tableInfo);
    }

    @Override
    @Transactional
    public void saveTable(TableInfoDto tableInfoDto, AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();

        // 신규 테이블은 한글명(예: 사용_내역_관리)을 표준 단어로 변환한 이름으로만 등록한다.
        if(tableInfoDto.getTableKorName() == null || tableInfoDto.getTableKorName().isBlank()){
            throw new CustomException(ErrorCode.TABLE_KOR_NAME_REQUIRED);
        }
        applyStandardTableName(tableInfoDto, accountDto);

        String tableName = tableInfoDto.getTableName();
        // 스키마를 고르지 않으면(기존 화면·API) 프로젝트 기본 스키마. 다른 프로젝트 스키마는 거절
        SchemaInfo schema = schemaService.resolveSchema(projectId, tableInfoDto.getSchemaId());

        // 같은 스키마의 테이블·VIEW 는 같은 이름 공간이다 : 그 스키마의 이름 공간을 잠근 뒤 확인한다 (다른 스키마에는 같은 이름이 있어도 된다)
        objectNamespace.lock(projectId, false, schema.getId());
        if(objectNamespace.conflict(schema.getId(), tableName, null, null) != null){
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableName);
        }

        TableInfo tableInfo = tableInfoDto.toEntity();
        tableInfo.setProjectId(projectId);
        tableInfo.setSchema(schema);

        tableInfo.setIsApproval(true);

        if(!AuthUtil.isApprovalAvailable()){
            tableInfo.setIsApproval(false);
        }

        tableInfoRepository.save(tableInfo);
    }

    @Override
    public TableNameDto generateTableName(String tableKorName, AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        return generateTableName(tableKorName, tablePrefix(projectId),
                word -> standardWordRepository.findAllByProjectIdAndCommonStandardWordName(projectId, word));
    }

    @Override
    public Map<String, TableNameDto> generateTableNames(java.util.Collection<String> tableKorNames, AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        Set<String> wordNames = tableKorNames.stream().flatMap(name -> splitWords(name).stream()).collect(Collectors.toSet());
        // 단어마다 조회하지 않고 한 번에 읽는다 (같은 이름이 여러 건이면 ID 순 — 승인된 단어 우선은 toTableNameWord 가 고른다)
        Map<String, List<StandardWord>> wordsByName = wordNames.isEmpty() ? Map.of()
                : standardWordRepository.findAllByProjectIdAndCommonStandardWordNameIn(projectId, wordNames).stream()
                        .sorted(java.util.Comparator.comparing(StandardWord::getId))
                        .collect(Collectors.groupingBy(StandardWord::getCommonStandardWordName));
        String tablePrefix = tablePrefix(projectId);
        Map<String, TableNameDto> result = new java.util.LinkedHashMap<>();
        for (String name : tableKorNames) {
            result.computeIfAbsent(name, key -> generateTableName(key, tablePrefix, word -> wordsByName.getOrDefault(word, List.of())));
        }
        return result;
    }

    /** 프로젝트 설정의 접두어 (예: TB_) - 저장 시 정규화되어 있으므로 그대로 붙인다. 없으면 null */
    private String tablePrefix(Long projectId) {
        return projectRepository.findById(projectId)
                .map(Project::getTablePrefix)
                .filter(prefix -> !prefix.isBlank())
                .orElse(null);
    }

    /** "사용_내역_관리", "사용 내역 관리" 모두 허용 */
    private static List<String> splitWords(String tableKorName) {
        return Arrays.stream((tableKorName == null ? "" : tableKorName).trim().split("[_\\s]+"))
                .filter(word -> !word.isBlank())
                .toList();
    }

    private TableNameDto generateTableName(String tableKorName, String tablePrefix, java.util.function.Function<String, List<StandardWord>> wordLookup) {
        List<String> inputWords = splitWords(tableKorName);

        List<TableNameDto.Word> words = inputWords.stream()
                .map(word -> toTableNameWord(word, wordLookup.apply(word)))
                .toList();

        boolean isValid = !words.isEmpty() && words.stream().allMatch(word -> word.status() == TableNameDto.Status.APPROVED);

        String tableName = isValid
                ? (tablePrefix == null ? "" : tablePrefix)
                    + words.stream().map(word -> word.abbreviation().toUpperCase()).collect(Collectors.joining("_"))
                : null;

        return new TableNameDto(tableName, String.join(" ", inputWords), tablePrefix, words, isValid);
    }

    /**
     * 단어 하나를 표준 단어로 찾는다. 같은 이름이 여러 건이면 승인된 단어를 우선한다.
     */
    private TableNameDto.Word toTableNameWord(String word, List<StandardWord> standardWords) {

        Optional<StandardWord> approved = standardWords.stream()
                .filter(standardWord -> Boolean.TRUE.equals(standardWord.getIsApproval()))
                .findFirst();

        if(approved.isPresent()){
            StandardWord standardWord = approved.get();
            return new TableNameDto.Word(word, standardWord.getCommonStandardWordAbbreviation(),
                    standardWord.getCommonStandardWordEnglishName(), TableNameDto.Status.APPROVED);
        }

        if(!standardWords.isEmpty()){
            StandardWord standardWord = standardWords.get(0);
            return new TableNameDto.Word(word, standardWord.getCommonStandardWordAbbreviation(),
                    standardWord.getCommonStandardWordEnglishName(), TableNameDto.Status.NOT_APPROVED);
        }

        return new TableNameDto.Word(word, null, null, TableNameDto.Status.NOT_FOUND);
    }

    /**
     * 한글명을 표준 단어로 변환해 테이블명/한글명에 반영한다. 테이블 설명이 비어있으면 한글명으로 채운다.
     */
    private void applyStandardTableName(TableInfoDto tableInfoDto, AccountDto accountDto) {
        TableNameDto tableNameDto = generateTableName(tableInfoDto.getTableKorName(), accountDto);

        if(!tableNameDto.valid()){
            throw new CustomException(ErrorCode.TABLE_WORD_NOT_STANDARD, String.join(", ", tableNameDto.invalidWords()));
        }

        tableInfoDto.setTableName(tableNameDto.tableName());
        tableInfoDto.setTableKorName(tableNameDto.tableKorName());

        if(tableInfoDto.getTableDescription() == null || tableInfoDto.getTableDescription().isBlank()){
            tableInfoDto.setTableDescription(tableNameDto.tableKorName());
        }
    }

    @Override
    @Transactional
    public void updateTable(TableInfoDto tableInfoDto, AccountDto accountDto) {

        Long projectId = accountDto.getProjectId();

        TableInfo current = tableInfoDto.getId() == null ? null : tableInfoRepository.findByIdAndProjectId(tableInfoDto.getId(), projectId);
        if (current == null) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }
        // 스키마를 보내지 않으면(기존 화면·API) 지금 스키마를 유지한다
        SchemaInfo oldSchema = current.getSchema();
        SchemaInfo targetSchema = tableInfoDto.getSchemaId() == null ? oldSchema : schemaService.resolveSchema(projectId, tableInfoDto.getSchemaId());
        // 이름·스키마를 바꿀 수 있으므로 지금 스키마와 옮길 스키마의 이름 공간을 함께 잠근다 (잠금 순서 : 이름 공간 → 테이블 행)
        objectNamespace.lock(projectId, false, oldSchema.getId(), targetSchema.getId());
        // 행을 잠가 승인 처리와 엇갈리지 않게 한다 (승인이 먼저 끝났으면 여기서 승인된 상태를 본다)
        TableInfo tableInfo = tableInfoRepository.findForUpdate(tableInfoDto.getId(), projectId).orElse(null);
        if (tableInfo == null) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }
        entityManager.refresh(tableInfo);
        if (!tableInfo.getSchema().getId().equals(oldSchema.getId())) {
            // 잠그기 전에 다른 수정이 스키마를 옮겼다 (잠근 이름 공간이 맞지 않는다)
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "{0} : 다른 사용자가 이 테이블을 수정했습니다. 다시 열어 수정해주세요.", tableInfo.getTableName());
        }
        // 승인된 테이블은 수정할 수 없다 (승인취소 후 수정). 화면은 수정 버튼을 숨기지만 API 직접 호출도 막는다
        if (Boolean.TRUE.equals(tableInfo.getIsApproval())) {
            throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        }

        // 한글명을 입력(변경)한 경우에만 표준 단어 기준으로 테이블명을 다시 만든다. 비어있으면 기존 테이블명/한글명을 유지한다.
        boolean isKorNameChanged = tableInfoDto.getTableKorName() != null && !tableInfoDto.getTableKorName().isBlank();
        if(isKorNameChanged){
            applyStandardTableName(tableInfoDto, accountDto);
            tableInfo.setTableKorName(tableInfoDto.getTableKorName());
        }

        String tableName = tableInfoDto.getTableName().toUpperCase();

        // 옮길 스키마에서 확인한다. 수정 중인 테이블 자신은 중복 대상에서 제외한다 (테이블명을 바꾸지 않고 저장하는 경우). 같은 이름의 VIEW 도 중복이다
        boolean isDuplicated = objectNamespace.conflict(targetSchema.getId(), tableName, tableInfo.getId(), null) != null;

        if(isDuplicated){
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableName);
        }

        String oldTableName = tableInfo.getTableName();
        boolean renamed = !tableName.equals(oldTableName);
        boolean moved = !targetSchema.getId().equals(oldSchema.getId());
        // 이 테이블을 이름으로 가리키던 컬럼 (바꾸기 전 기준) : 이름·스키마가 바뀌어도 같은 테이블을 가리키게 참조 테이블명을 고친다
        List<ColumnInfo> referencing = renamed || moved ? columnsReferencing(projectId, tableInfo) : List.of();

        if(renamed){
            // 컬럼은 테이블명을 따로 저장하므로 소속 컬럼도 함께 변경한다.
            tableInfo.getColumns().forEach(column -> column.setTableName(tableName));
        }

        tableInfo.setTableName(tableName);
        tableInfo.setSchema(targetSchema);
        referencing.forEach(column -> column.setRefTableName(
                TableNameResolver.Name.parse(column.getRefTableName()).schemaName() == null ? tableName : targetSchema.getSchemaName().toUpperCase() + "." + tableName));
        if (renamed || moved) {
            // 새 이름을 참조 테이블명으로 적어 둔 컬럼이 있으면 관계가 새로 확정될 수 있다
            physicalModelService.syncReferenceRelations(projectId, List.of(tableInfo.getId()));
        }
        tableInfo.setTableDescription(tableInfoDto.getTableDescription());
        tableInfo.setIsMasterTable(tableInfoDto.getIsMasterTable().equals("Y"));
        tableInfo.setSortOrder(tableInfoDto.getSortOrder());
    }

    @Override
    @Transactional
    public void updateTableIsApproval(boolean isApproval, AccountDto accountDto, List<TableInfoDto> tableInfoDtos) {
        // 승인·승인취소는 승인 권한(관리자·매니저·프로젝트 관리자)만 (화면은 버튼을 숨기지만 API 직접 호출도 막는다)
        if(!AuthUtil.isApprovalAvailable()){
            throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
        }

        tableInfoDtos.forEach(tableInfoDto -> {
            // 행을 잠가 테이블 수정과 엇갈리지 않게 한다 (수정이 먼저 끝난 뒤에 승인된다)
            TableInfo tableInfo = tableInfoDto.getId() == null ? null
                    : tableInfoRepository.findForUpdate(tableInfoDto.getId(), accountDto.getProjectId()).orElse(null);
            if (tableInfo == null) {
                throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
            }

            tableInfo.setIsApproval(isApproval);
        });


    }

    @Override
    @Transactional
    public void deleteTable(List<TableInfoDto> tableInfoDtos, AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        // VIEW 의 참조 등록과 엇갈리지 않게 VIEW 참조 그래프와 테이블들의 스키마 이름 공간을 먼저 잠근다 (M-15)
        objectNamespace.lock(projectId, true, tableInfoDtos.stream()
                .map(dto -> dto.getId() == null ? null : tableInfoRepository.findByIdAndProjectId(dto.getId(), projectId))
                .filter(java.util.Objects::nonNull).map(t -> t.getSchema().getId()).toList());

        tableInfoDtos.forEach(tableInfoDto -> {
            // 행을 잠가 논리·물리 매핑 등록과 엇갈리지 않게 한다
            TableInfo tableInfo = tableInfoRepository.findForUpdate(tableInfoDto.getId(), projectId).orElse(null);

            entityTableMappingRepository.findByTable_Id(tableInfoDto.getId()).ifPresent(mapping -> {
                throw new CustomException(ErrorCode.RELATION_EXISTS, "엔터티 {0} 에 매핑된 테이블입니다. 매핑을 먼저 해제해주세요.", mapping.getEntity().getEntityName());
            });

            if(!tableInfo.getColumns().isEmpty()){
                // 컬럼이 등록되어있을경우 삭제 불가능
                throw new CustomException(ErrorCode.COLUMN_DEPENDENCY_EXISTS);
            }

            if(tableInfo.getIsApproval()){
                // 승인된 데이터는 삭제가 불가능하다.
                throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
            }

            // VIEW 가 참조하는 테이블은 지우지 않는다 (참조 관계를 자동으로 정리하지 않는다, M-15 D-24)
            List<String> views = viewRepository.findNamesReferencingTables(List.of(tableInfo.getId()));
            if (!views.isEmpty()) {
                throw new CustomException(ErrorCode.RELATION_EXISTS, "VIEW {0} 이(가) 참조하는 테이블입니다. VIEW 의 참조 객체에서 먼저 빼주세요.", String.join(", ", views));
            }

            tableInfoRepository.delete(tableInfo);
        });
    }

    @Override
    @Transactional
    public void uploadTableExcelFile(MultipartFile file, AccountDto accountDto) throws IOException {
        Long projectId = accountDto.getProjectId();

        // 관리자의 경우 승인으로 저장, 아닐경우 관리자가 승인할 수 있도록 저장
        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();

        List<String> tableExcelHeaders = List.of(
                "tableName",
                "tableDescription",
                "isMasterTable",
                "sortOrder"
        );

        // 업로드 템플릿에는 스키마가 없으므로 프로젝트 기본 스키마에 등록한다 (청크마다 영속성 컨텍스트를 비우므로 ID 로 참조)
        Long defaultSchemaId = schemaService.resolveSchema(projectId, null).getId();
        // 기본 스키마의 테이블·VIEW 이름 공간 (M-15) : 업로드가 끝날 때까지 잠근다
        objectNamespace.lock(projectId, false, defaultSchemaId);

        // 파일 안 중복 확인용 (같은 테이블명은 처음 행만 등록)
        Set<String> uploadedTableNames = new HashSet<>();

        // 청크(500행) 단위 : 기존 테이블명은 청크당 IN 조회 한 번, 저장은 JDBC 배치, 저장 후 영속성 컨텍스트 비우기
        ExcelUtils.readExcelRowsInChunks(file, 0, tableExcelHeaders, FIRST_ROW, ExcelUtils.UPLOAD_CHUNK_SIZE, rows -> {

            Set<String> chunkTableNames = rows.stream()
                    .map(map -> map.get("tableName").toUpperCase())
                    .filter(tableName -> !tableName.isEmpty())
                    .collect(Collectors.toSet());

            Set<String> existingTableNames = chunkTableNames.isEmpty() ? new HashSet<>()
                    : tableInfoRepository.findAllBySchema_IdAndTableNameIn(defaultSchemaId, chunkTableNames).stream()
                        .map(TableInfo::getTableName)
                        .collect(Collectors.toCollection(HashSet::new));
            // 기본 스키마에 같은 이름의 VIEW 가 있는 행도 이미 등록된 이름으로 보고 건너뛴다 (다른 스키마의 같은 이름은 상관없다)
            if (!chunkTableNames.isEmpty()) existingTableNames.addAll(viewRepository.findNamesIn(defaultSchemaId, chunkTableNames));

            List<TableInfo> tableInfos = new ArrayList<>();

            for (Map<String, String> map : rows) {
                String s = map.get("sortOrder").replaceAll("[^0-9]", "");

                // 값이 입력되어있지 않으면 0을 입력한다.
                if(s.isEmpty()){
                    s = "0";
                }

                String tableName = map.get("tableName").toUpperCase();

                // 빈 테이블명 / 이미 등록된 테이블 / 파일 안에서 앞에 나온 테이블은 건너뛴다.
                if(tableName.isEmpty() || existingTableNames.contains(tableName) || !uploadedTableNames.add(tableName)){
                    continue;
                }

                tableInfos.add(TableInfo.builder()
                        .projectId(projectId)
                        .tableName(tableName)
                        .tableDescription(map.get("tableDescription"))
                        .isMasterTable(map.get("isMasterTable").equals("Y"))
                        .sortOrder(Integer.parseInt(s))
                        .isApproval(isApprovalAvailable)
                        .schema(entityManager.getReference(SchemaInfo.class, defaultSchemaId))
                        .build());
            }

            tableInfoRepository.saveAll(tableInfos);
            entityManager.flush();
            entityManager.clear();
        });
    }

    /**
     * 이 테이블 컬럼의 참조 테이블명(적은 그대로, 대문자) → 참조 테이블 PK 컬럼(정렬 순서, 타입 비교용). 이름은 이 테이블의 스키마 기준으로 정한다
     * ({@link TableNameResolver} : 관계 동기화와 같은 규칙, 이미 확정된 REF_COLUMN 관계의 대상은 모호해도 유지). 이름 후보는 IN 조회 한 번.
     */
    @Override
    public ReferencedKeys referencedKeys(Long projectId, TableInfo table) {
        List<ColumnInfo> columns = table.getColumns().stream().filter(c -> c.getRefTableName() != null && !c.getRefTableName().isBlank()).toList();
        Set<String> names = columns.stream().map(c -> TableNameResolver.tableNameOf(c.getRefTableName())).collect(Collectors.toSet());
        if (names.isEmpty()) return new ReferencedKeys(Map.of(), Set.of());

        List<TableInfo> candidates = tableInfoRepository.findAllByProjectIdAndTableNameIn(projectId, names);
        Map<Long, Long> currentTargets = new java.util.HashMap<>();
        relationRepository.findAllByDerivedColumnIdIn(columns.stream().map(ColumnInfo::getId).filter(java.util.Objects::nonNull).toList())
                .forEach(r -> currentTargets.put(r.getDerivedColumnId(), r.getTargetTable().getId()));

        Map<String, List<ColumnInfo>> pkColumns = new java.util.HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (ColumnInfo column : columns) {
            String key = column.getRefTableName().trim().toUpperCase();
            TableNameResolver.Result target = TableNameResolver.resolve(column.getRefTableName(), table.getSchema(), candidates, currentTargets.get(column.getId()));
            if (target.status() == TableNameResolver.Status.AMBIGUOUS) ambiguous.add(key);
            if (target.status() != TableNameResolver.Status.FOUND) continue;
            pkColumns.putIfAbsent(key, target.table().getColumns().stream()
                    .filter(c -> Boolean.TRUE.equals(c.getIsPk()))
                    .sorted(java.util.Comparator.comparing(ColumnInfo::getSortOrder, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                    .toList());
        }
        return new ReferencedKeys(pkColumns, ambiguous);
    }

    /** 참조 테이블명이 지금 이 테이블로 정해지는 컬럼 (이름 변경·스키마 이동 전에 부른다) */
    private List<ColumnInfo> columnsReferencing(Long projectId, TableInfo table) {
        List<ColumnInfo> referencing = columnInfoRepository.findAllReferencing(projectId, TableNameResolver.namesOf(table));
        if (referencing.isEmpty()) return List.of();
        List<TableInfo> candidates = tableInfoRepository.findAllByProjectIdAndTableNameIn(projectId, List.of(table.getTableName().toUpperCase()));
        Map<Long, Long> currentTargets = new java.util.HashMap<>();
        relationRepository.findAllByDerivedColumnIdIn(referencing.stream().map(ColumnInfo::getId).toList())
                .forEach(r -> currentTargets.put(r.getDerivedColumnId(), r.getTargetTable().getId()));
        return referencing.stream()
                .filter(c -> c.getTableInfo() != null)
                .filter(c -> {
                    TableNameResolver.Result target = TableNameResolver.resolve(c.getRefTableName(), c.getTableInfo().getSchema(), candidates, currentTargets.get(c.getId()));
                    return target.status() == TableNameResolver.Status.FOUND && target.table().getId().equals(table.getId());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TableDdlDto generateTableDdl(Long tableId, AccountDto accountDto, String defaultSchemaName) {
        Long projectId = accountDto.getProjectId();

        // DDL 에 그대로 들어가는 값이라 스키마명 규칙을 그대로 적용한다
        String schemaName = defaultSchemaName == null || defaultSchemaName.isBlank() ? null : defaultSchemaName.trim();
        if (schemaName != null && !SchemaInfo.NAME_PATTERN.matcher(schemaName).matches()) {
            throw new CustomException(ErrorCode.INVALID_SCHEMA_NAME);
        }

        // 로그인 프로젝트의 테이블만 (다른 프로젝트 테이블 ID 로는 조회할 수 없다)
        TableInfo tableInfo = tableInfoRepository.findByIdAndProjectId(tableId, projectId);
        if (tableInfo == null) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }

        DbType dbType = projectRepository.findById(projectId)
                .map(Project::getDbType)
                .map(DbType::orDefault)
                .orElse(DbType.DEFAULT);

        ReferencedKeys keys = referencedKeys(projectId, tableInfo);
        TableDdlGenerator.Extras extras = physicalModelService.ddlExtras(tableInfo).withAmbiguousRefTables(keys.ambiguous());
        TableDdlGenerator.Result result = TableDdlGenerator.generate(dbType, tableInfo, tableInfo.getColumns(),
                keys.pkColumns(), extras, schemaName);
        return new TableDdlDto(dbType.name(), dbType.getLabel(), tableInfo.getTableName(), result.ddl(), result.warnings(),
                pendingApprovals(tableInfo, extras));
    }

    /**
     * DDL 에 들어 있는 승인 대기 항목. DDL 은 설계 중인 모델을 그대로 보여 주는 출력이라 승인 여부로 거르지 않는다
     * (일부만 빼면 남은 제약의 의미가 달라진다). 대신 무엇이 승인 전인지 알려 준다.
     */
    private static List<String> pendingApprovals(TableInfo table, TableDdlGenerator.Extras extras) {
        List<String> pending = new ArrayList<>();
        if (!Boolean.TRUE.equals(table.getIsApproval())) pending.add(Messages.get("테이블 : {0}", table.getTableName()));
        addPending(pending, "컬럼 : {0}", table.getColumns().stream()
                .sorted(java.util.Comparator.comparing(ColumnInfo::getSortOrder, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .filter(c -> !Boolean.TRUE.equals(c.getIsApproval())).map(ColumnInfo::getColumnName));
        addPending(pending, "관계(FK) : {0}", extras.relations().stream().filter(r -> !Boolean.TRUE.equals(r.getIsApproval())).map(r -> r.getRelationName()));
        addPending(pending, "인덱스 : {0}", extras.indexes().stream().filter(i -> !Boolean.TRUE.equals(i.getIsApproval())).map(i -> i.getIndexName()));
        addPending(pending, "제약조건 : {0}", extras.constraints().stream().filter(c -> !Boolean.TRUE.equals(c.getIsApproval())).map(c -> c.getConstraintName()));
        return pending;
    }

    private static void addPending(List<String> pending, String message, java.util.stream.Stream<String> names) {
        String joined = names.collect(Collectors.joining(", "));
        if (!joined.isEmpty()) pending.add(Messages.get(message, joined));
    }
}
