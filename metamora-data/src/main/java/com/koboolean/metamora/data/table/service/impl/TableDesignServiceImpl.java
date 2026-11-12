package com.koboolean.metamora.data.table.service.impl;

import com.koboolean.metamora.data.mapping.repository.EntityTableMappingRepository;
import com.koboolean.metamora.core.project.domain.enums.DbType;
import com.koboolean.metamora.data.physical.service.PhysicalModelService;
import com.koboolean.metamora.data.schema.domain.entity.SchemaInfo;
import com.koboolean.metamora.data.schema.service.SchemaService;
import com.koboolean.metamora.data.table.ddl.TableDdlGenerator;
import com.koboolean.metamora.data.table.domain.dto.TableDdlDto;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.column.repository.ColumnInfoRepository;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardWord;
import com.koboolean.metamora.data.dictionary.repository.StandardWordRepository;
import com.koboolean.metamora.data.table.domain.dto.TableInfoDto;
import com.koboolean.metamora.data.table.domain.dto.TableNameDto;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
import com.koboolean.metamora.data.table.service.TableDesignService;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.i18n.Messages;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.project.domain.dto.ProjectMemberDto;
import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.project.domain.entity.ProjectMember;
import com.koboolean.metamora.core.util.AuthUtil;
import com.koboolean.metamora.common.utils.ExcelUtils;
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

        if(!tableInfoRepository.findAllByTableNameAndProjectId(tableName, projectId).isEmpty()){
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableName);
        }

        TableInfo tableInfo = tableInfoDto.toEntity();
        tableInfo.setProjectId(projectId);
        // 스키마를 고르지 않으면(기존 화면·API) 프로젝트 기본 스키마
        tableInfo.setSchema(schemaService.resolveSchema(projectId, tableInfoDto.getSchemaId()));

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

        // 행을 잠가 승인 처리와 엇갈리지 않게 한다 (승인이 먼저 끝났으면 여기서 승인된 상태를 본다)
        TableInfo tableInfo = tableInfoDto.getId() == null ? null : tableInfoRepository.findForUpdate(tableInfoDto.getId(), projectId).orElse(null);
        if (tableInfo == null) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
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

        // 수정 중인 테이블 자신은 중복 대상에서 제외한다 (테이블명을 바꾸지 않고 저장하는 경우)
        boolean isDuplicated = tableInfoRepository.findAllByTableNameAndProjectId(tableName, projectId).stream()
                .anyMatch(other -> !other.getId().equals(tableInfo.getId()));

        if(isDuplicated){
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableName);
        }

        String oldTableName = tableInfo.getTableName();

        if(!tableName.equals(oldTableName)){
            // 컬럼은 테이블명을 따로 저장하므로 소속 컬럼과 참조 테이블명도 함께 변경한다.
            tableInfo.getColumns().forEach(column -> column.setTableName(tableName));
            columnInfoRepository.findAllByProjectIdAndRefTableName(projectId, oldTableName)
                    .forEach(column -> column.setRefTableName(tableName));
        }

        tableInfo.setTableName(tableName);
        // 스키마를 보내지 않으면(기존 화면·API) 지금 스키마를 유지한다
        if (tableInfoDto.getSchemaId() != null) {
            tableInfo.setSchema(schemaService.resolveSchema(projectId, tableInfoDto.getSchemaId()));
        }
        if (!tableName.equals(oldTableName)) {
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

        // 파일 안 중복 확인용 (같은 테이블명은 처음 행만 등록)
        Set<String> uploadedTableNames = new HashSet<>();

        // 청크(500행) 단위 : 기존 테이블명은 청크당 IN 조회 한 번, 저장은 JDBC 배치, 저장 후 영속성 컨텍스트 비우기
        ExcelUtils.readExcelRowsInChunks(file, 0, tableExcelHeaders, FIRST_ROW, ExcelUtils.UPLOAD_CHUNK_SIZE, rows -> {

            Set<String> chunkTableNames = rows.stream()
                    .map(map -> map.get("tableName").toUpperCase())
                    .filter(tableName -> !tableName.isEmpty())
                    .collect(Collectors.toSet());

            Set<String> existingTableNames = chunkTableNames.isEmpty() ? Set.of()
                    : tableInfoRepository.findAllByProjectIdAndTableNameIn(projectId, chunkTableNames).stream()
                        .map(TableInfo::getTableName)
                        .collect(Collectors.toSet());

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
     * 컬럼의 참조 테이블명(대문자) → 참조 테이블 PK 컬럼(정렬 순서, 타입 비교용). 같은 프로젝트에 등록된 테이블만 IN 조회 한 번으로 찾는다.
     */
    private Map<String, List<ColumnInfo>> referencedPkColumns(Long projectId, List<ColumnInfo> columns) {
        Set<String> refTableNames = columns.stream()
                .map(ColumnInfo::getRefTableName)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toUpperCase())
                .collect(Collectors.toSet());

        if (refTableNames.isEmpty()) return Map.of();

        Map<String, List<ColumnInfo>> pkColumns = new java.util.HashMap<>();
        for (TableInfo refTable : tableInfoRepository.findAllByProjectIdAndTableNameIn(projectId, refTableNames)) {
            pkColumns.putIfAbsent(refTable.getTableName().toUpperCase(), refTable.getColumns().stream()
                    .filter(column -> Boolean.TRUE.equals(column.getIsPk()))
                    .sorted(java.util.Comparator.comparing(ColumnInfo::getSortOrder, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                    .toList());
        }
        return pkColumns;
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

        TableDdlGenerator.Extras extras = physicalModelService.ddlExtras(tableInfo);
        TableDdlGenerator.Result result = TableDdlGenerator.generate(dbType, tableInfo, tableInfo.getColumns(),
                referencedPkColumns(projectId, tableInfo.getColumns()), extras, schemaName);
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
