package com.lexorma.data.column.service.impl;

import com.lexorma.data.column.domain.dto.TermResolveDto;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.service.TableNameResolver;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.common.utils.ExcelUtils;
import com.lexorma.common.i18n.Messages;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ColumnManageServiceImpl implements ColumnManageService {

    private final ColumnInfoRepository columnInfoRepository;
    private final TableInfoRepository tableInfoRepository;
    private final StandardTermRepository standardTermRepository;
    private final EntityManager entityManager;
    private final PhysicalModelService physicalModelService;
    private static final int FIRST_ROW = 1;

    @Override
    @Transactional(readOnly = true) // DTO 가 소속 테이블·스키마(지연 로딩)를 읽는다
    public Page<ColumnInfoDto> selectTableData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery, Long tableId) {
        Long projectId = accountDto.getProjectId();

        Specification<ColumnInfo> where = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("projectId"), projectId));
            if (tableId != null) {
                // 테이블 ID 로 정확히 한 테이블의 컬럼만 (테이블명 검색은 부분 일치라 이름이 겹치는 테이블이 섞인다)
                predicates.add(builder.equal(root.get("tableInfo").get("id"), tableId));
            }
            if (searchQuery != null && !searchQuery.trim().isEmpty() && searchColumn != null) {
                String keyword = "%" + searchQuery + "%";
                switch (searchColumn) {
                    case "tableName" -> predicates.add(builder.like(root.get("tableName"), keyword.toUpperCase()));
                    case "columnName" -> predicates.add(builder.like(builder.upper(root.get("columnName")), keyword.toUpperCase()));
                    case "columnDesc", "dataType" -> predicates.add(builder.like(root.get(searchColumn), keyword));
                    default -> { } // 모르는 검색 컬럼은 무시
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };

        return columnInfoRepository.findAll(where, pageable).map(ColumnInfoDto::fromEntity);
    }

    @Override
    public List<TableInfoDto> selectTable(AccountDto accountDto, String tableName) {

        Long projectId = accountDto.getProjectId();

        String searchTableName = null;
        // "스키마명.테이블명" 으로 찾을 수도 있다 (테이블명은 스키마 안에서만 하나다)
        TableNameResolver.Name name = TableNameResolver.Name.parse(tableName);

        if(name != null){
            searchTableName = "%" + name.tableName() + "%";
        }

        List<TableInfo> tableInfos = tableInfoRepository.findAllByProjectIdAndTableNameLikeAndIsApproval(projectId, searchTableName, true);

        return tableInfos.stream()
                .filter(table -> name == null || name.schemaName() == null || (table.getSchema() != null && table.getSchema().getSchemaName().equalsIgnoreCase(name.schemaName())))
                .map(TableInfoDto::fromEntity).toList();
    }

    @Override
    public List<ColumnInfoDto> selectTableColumn(AccountDto accountDto, Long tableId) {
        TableInfo tableInfo = tableInfoRepository.findALlByProjectIdAndId(accountDto.getProjectId(), tableId);
        // 다른 프로젝트·없는 테이블 (예전에는 NullPointerException 으로 500 이었다)
        if (tableInfo == null) throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        return tableInfo.getColumns().stream().map(ColumnInfoDto::fromEntity).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void insertColumn(AccountDto accountDto, ColumnInfoDto columnInfoDto) {
        Long projectId = accountDto.getProjectId();

        TableInfo tableInfo = tableInfoRepository.findByIdAndProjectId(columnInfoDto.getTableInfoId(), projectId);
        StandardTerm standardTerm = standardTermRepository.findByIdAndProjectId(columnInfoDto.getTermId(), projectId);

        // 테이블/표준용어를 선택하지 않았거나 다른 프로젝트의 정보면 등록할 수 없다.
        if(tableInfo == null){
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }
        if(standardTerm == null){
            throw new CustomException(ErrorCode.NOT_FOUND_TERM_DATA);
        }

        // 같은 테이블 안에서만 컬럼명 중복을 막는다. (다른 테이블에는 같은 표준 컬럼을 쓸 수 있다)
        List<ColumnInfo> duplicates = columnInfoRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(projectId, tableInfo.getId(), columnInfoDto.getColumnName());

        if(!duplicates.isEmpty()){
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableInfo.getTableName() + "." + columnInfoDto.getColumnName());
        }

        String identityProblem = identityProblem(columnInfoDto.getIdentity(), columnInfoDto.getDataType(), columnInfoDto.getScale(), columnInfoDto.getDefaultValue());
        if (identityProblem != null) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "{0} : {1}", columnInfoDto.getColumnName(), identityProblem);
        ColumnInfo columnInfo = columnInfoDto.toEntity();
        rejectAmbiguousReferences(projectId, tableInfo, java.util.Collections.singleton(columnInfo.getRefTableName()));
        columnInfo.setTableInfo(tableInfo);
        columnInfo.setProjectId(projectId);

        columnInfo.setStandardTerms(standardTerm);

        columnInfo.setIsApproval(true);

        if(!AuthUtil.isApprovalAvailable()){
            columnInfo.setIsApproval(false);
        }

        columnInfoRepository.save(columnInfo);
        tableInfo.getColumns().add(columnInfo);
        standardTerm.getColumnInfos().add(columnInfo);
        // 참조 테이블명 → 관계, 새 PK 로 다른 테이블의 참조가 확정될 수도 있다
        physicalModelService.syncReferenceRelations(projectId, List.of(tableInfo.getId()));
    }

    @Override
    public List<StandardTermDto> selectTermData(AccountDto accountDto, String type, String data) {

        List<StandardTerm> terms = switch(type){
            // 표준용어명은 단어를 "_" 로 잇는다 : "사용자 이름" 으로 검색해도 "사용자_이름" 을 찾도록
            case "termName" -> standardTermRepository.findAllByProjectIdAndCommonStandardTermNameContaining(accountDto.getProjectId(), data.trim().replaceAll("\\s+", "_"));
            case "termAbbreviation" -> standardTermRepository.findAllByProjectIdAndCommonStandardTermAbbreviationContainingIgnoreCase(accountDto.getProjectId(), data);
            default -> null;
        };

        return terms.stream().map(StandardTermDto::fromEntity).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true) // DTO 가 소속 테이블·스키마(지연 로딩)를 읽는다
    public List<ColumnInfoDto> selectDuplicateColumns(AccountDto accountDto, Long tableInfoId, String columnName) {
        return columnInfoRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(accountDto.getProjectId(), tableInfoId, columnName).stream()
                .map(ColumnInfoDto::fromEntity)
                .toList();
    }

    @Override
    @Transactional
    public void updateColumnApproval(AccountDto accountDto, List<ColumnInfoDto> columnInfoDtos, String type) {
        // 승인·승인취소는 승인 권한(관리자·매니저·프로젝트 관리자)만 (화면은 버튼을 숨기지만 API 직접 호출도 막는다)
        if(!AuthUtil.isApprovalAvailable()){
            throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
        }

        Long projectId = accountDto.getProjectId();

        columnInfoDtos.forEach(columnInfoDto -> {
            ColumnInfo columnInfo = columnInfoRepository.findByProjectIdAndId(projectId, columnInfoDto.getId());

            columnInfo.setIsApproval(Boolean.parseBoolean(type));
        });

    }

    @Override
    @Transactional
    public void deleteColumn(AccountDto accountDto, List<ColumnInfoDto> columnInfoDtos) {
//        if(!AuthUtil.isApprovalAvailable()){
//            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
//        }

        Long projectId = accountDto.getProjectId();
        Set<Long> tableIds = new HashSet<>();

        columnInfoDtos.forEach(columnInfoDto -> {
            ColumnInfo columnInfo = columnInfoRepository.findByProjectIdAndId(projectId, columnInfoDto.getId());

            if(columnInfo.getIsApproval()){
                throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
            }

            // 관계·인덱스·제약조건에 쓰인 컬럼은 삭제할 수 없다 (참조 테이블명에서 만든 관계는 함께 지운다)
            physicalModelService.releaseColumns(projectId, List.of(columnInfo));

            // 테이블명은 변경될 수 있으므로 이름이 아닌 연관관계(table_info_id)로 소속 테이블을 찾는다.
            // 표준용어도 요청 값(그리드 행에는 termId 가 없다)이 아닌 컬럼의 연관관계로 찾는다. (일괄 저장의 삭제와 같음)
            TableInfo tableInfo = columnInfo.getTableInfo();
            StandardTerm standardTerm = columnInfo.getStandardTerms();

            columnInfoRepository.delete(columnInfo);

            if(tableInfo != null){
                tableInfo.getColumns().remove(columnInfo);
                tableIds.add(tableInfo.getId());
            }
            if (standardTerm != null && standardTerm.getColumnInfos() != null) {
                standardTerm.getColumnInfos().remove(columnInfo);
            }
        });

        // 지운 PK 를 참조하던 다른 테이블의 관계를 다시 맞춘다
        physicalModelService.syncReferenceRelations(projectId, tableIds);
    }

    @Override
    @Transactional
    public void uploadColumnExcelFile(MultipartFile file, AccountDto accountDto) throws IOException {
        Long projectId = accountDto.getProjectId();

        // 관리자의 경우 승인으로 저장, 아닐경우 관리자가 승인할 수 있도록 저장
        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();

        List<String> tableExcelHeaders = List.of(
                "tableName",
                "columnName",
                "columnDesc",
                "dataType",
                "maxLength",
                "precision",
                "scale",
                "isNullable",
                "defaultValue",
                "sortOrder",
                "isMasterData",
                "refTableName",
                "isRequired",
                "isSensitive",
                "isPk",
                "isUnique",
                "isIndex",
                "isEncrypted",
                "example"
        );

        // 파일 안 중복 확인용 : "테이블ID|컬럼명"
        Set<String> uploadedColumns = new HashSet<>();

        // 업로드 중에는 테이블/표준용어가 바뀌지 않으므로 조회 결과(ID)를 캐시한다. (없으면 null 로 기록)
        Map<String, Long> tableIdByName = new HashMap<>();
        Map<Long, String> tableNameById = new HashMap<>();
        Set<String> ambiguousTableNames = new HashSet<>();
        Map<String, Long> termIdByAbbreviation = new HashMap<>();

        // 청크(500행) 단위 : 테이블/용어/중복은 청크당 IN 조회 한 번씩, 저장은 JDBC 배치, 저장 후 영속성 컨텍스트 비우기
        ExcelUtils.readExcelRowsInChunks(file, 0, tableExcelHeaders, FIRST_ROW, ExcelUtils.UPLOAD_CHUNK_SIZE, rows -> {
            List<Map<String, String>> columnRows = rows.stream().filter(map -> !map.get("tableName").isEmpty()).toList();

            loadColumnUploadReferences(projectId, columnRows, tableIdByName, tableNameById, ambiguousTableNames, termIdByAbbreviation);
            Set<String> existingColumns = findExistingColumns(projectId, columnRows, tableIdByName);

            List<ColumnInfo> columnInfos = new ArrayList<>();
            Set<Long> chunkTableIds = new HashSet<>();

            // 행마다 기존과 같은 순서로 검증한다 : 숫자/Y·N 형식 → 테이블 → 표준용어 → 파일 안 중복 → 등록된 컬럼 중복
            for (Map<String, String> map : columnRows) {
                String[] numberData = {"maxLength", "precision", "scale", "sortOrder"};

                for (String key : numberData) {
                    String trim = map.get(key).replaceAll("[^0-9]", "").trim();
                    map.put(key, trim.isEmpty() ? "0" : trim);
                }

                String[] booleanData = {"isNullable", "isMasterData", "isRequired", "isSensitive", "isPk", "isUnique", "isIndex", "isEncrypted"};

                for (String key : booleanData) {
                    if (map.get(key).isEmpty()) {
                        map.put(key, "N");
                    }

                    if (!map.get(key).equals("Y") && !map.get(key).equals("N")) {
                        throw new CustomException(ErrorCode.COLUMN_BOOLEAN_TYPE_DEF);
                    }
                }

                String writtenTableName = map.get("tableName").toUpperCase();
                Long tableInfoId = tableIdByName.get(writtenTableName);

                if (ambiguousTableNames.contains(writtenTableName)) {
                    throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED, "{0} : 여러 스키마에 같은 이름의 테이블이 있습니다. 스키마명.테이블명으로 적어주세요.", writtenTableName);
                }
                if (tableInfoId == null) {
                    throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED, writtenTableName);
                }
                String tableName = tableNameById.get(tableInfoId);

                // 표준용어 : 영문약어(대문자) 우선, 없으면 입력값 그대로
                String columnName = map.get("columnName");
                Long termId = termIdByAbbreviation.get(columnName.toUpperCase());
                if (termId == null) {
                    termId = termIdByAbbreviation.get(columnName);
                }

                if (termId == null) {
                    throw new CustomException(ErrorCode.TERM_IS_NOT_DEFINED, columnName);
                }

                chunkTableIds.add(tableInfoId);

                // 같은 테이블 안에서만 컬럼명 중복을 막는다. (화면 등록과 같은 기준) - 하나라도 있으면 업로드 전체 취소
                String columnKey = tableInfoId + "|" + columnName;

                if (!uploadedColumns.add(columnKey)) {
                    throw new CustomException(ErrorCode.COLUMN_DUPLICATE_IN_FILE, tableName + "." + columnName);
                }

                if (existingColumns.contains(columnKey)) {
                    throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableName + "." + columnName);
                }

                if (map.get("refTableName") != null && !map.get("refTableName").isBlank()) {
                    TableInfo owner = entityManager.find(TableInfo.class, tableInfoId);
                    rejectAmbiguousReferences(projectId, owner, java.util.Collections.singleton(map.get("refTableName")));
                }

                // 연관은 조회 없이 ID 참조로 연결한다. (테이블/용어의 컬럼 목록 컬렉션은 건드리지 않는다 - FK 가 기준)
                columnInfos.add(ColumnInfo.builder()
                        .projectId(projectId)
                        .tableName(tableName)
                        .columnName(columnName)
                        .columnDesc(map.get("columnDesc"))
                        .dataType(map.get("dataType"))
                        .maxLength(Integer.parseInt(map.get("maxLength")))
                        .precision(new BigDecimal(map.get("precision")))
                        .scale(Integer.parseInt(map.get("scale")))
                        .isNullable(map.get("isNullable").equals("Y"))
                        .defaultValue(map.get("defaultValue"))
                        .sortOrder(Integer.parseInt(map.get("sortOrder")))
                        .isMasterData(map.get("isMasterData").equals("Y"))
                        .refTableName(map.get("refTableName"))
                        .isRequired(map.get("isPk").equals("Y") || map.get("isRequired").equals("Y"))
                        .isSensitive(map.get("isSensitive").equals("Y"))
                        .isPk(map.get("isPk").equals("Y"))
                        .isUnique(map.get("isPk").equals("Y") || map.get("isUnique").equals("Y"))
                        .isIndex(map.get("isIndex").equals("Y"))
                        .isEncrypted(map.get("isEncrypted").equals("Y"))
                        .example(map.get("example"))
                        .standardTerms(entityManager.getReference(StandardTerm.class, termId))
                        .tableInfo(entityManager.getReference(TableInfo.class, tableInfoId))
                        .isApproval(isApprovalAvailable)
                        .build());
            }

            columnInfoRepository.saveAll(columnInfos);
            // 참조 테이블명 → 관계 (청크의 테이블 기준, 조회 전에 자동 flush 되어 저장한 행을 DB 에서 다시 읽는다)
            physicalModelService.syncReferenceRelations(projectId, chunkTableIds);
            entityManager.flush();
            entityManager.clear();
        });
    }

    /** 참조 테이블명 중 이 테이블의 스키마 기준으로 여러 스키마의 테이블이 되어 정할 수 없는 것 (대문자). 없는 테이블은 예전처럼 허용한다 (관계가 생기지 않을 뿐) */
    private Set<String> ambiguousReferences(Long projectId, TableInfo table, java.util.Collection<String> references) {
        List<String> written = references.stream().filter(r -> r != null && !r.isBlank()).map(r -> r.trim().toUpperCase()).toList();
        if (written.isEmpty()) return Set.of();
        List<TableInfo> candidates = tableInfoRepository.findAllByProjectIdAndTableNameIn(projectId,
                written.stream().map(TableNameResolver::tableNameOf).collect(Collectors.toSet()));
        return written.stream()
                .filter(r -> TableNameResolver.resolve(r, table.getSchema(), candidates, null).status() == TableNameResolver.Status.AMBIGUOUS)
                .collect(Collectors.toSet());
    }

    private void rejectAmbiguousReferences(Long projectId, TableInfo table, java.util.Collection<String> references) {
        Set<String> ambiguous = ambiguousReferences(projectId, table, references);
        if (!ambiguous.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "참조 테이블 {0} 이(가) 여러 스키마에 있습니다. 스키마명.테이블명으로 적어주세요.", ambiguous.iterator().next());
        }
    }

    private static String ambiguousMessage(String reference) {
        return Messages.get("참조 테이블 {0} 이(가) 여러 스키마에 있습니다. 스키마명.테이블명으로 적어주세요.", reference.trim().toUpperCase());
    }

    /**
     * 청크에서 쓰는 테이블명/표준용어(영문약어) 중 캐시에 없는 것을 IN 조회 한 번씩으로 불러온다.
     * 테이블명은 "테이블명" 또는 "스키마명.테이블명" : 테이블명은 스키마 안에서만 하나라서, 여러 스키마에 있는 이름은 스키마를 적어야 한다 (TableNameResolver).
     */
    private void loadColumnUploadReferences(Long projectId, List<Map<String, String>> columnRows, Map<String, Long> tableIdByName,
                                            Map<Long, String> tableNameById, Set<String> ambiguousTableNames, Map<String, Long> termIdByAbbreviation) {
        Set<String> writtenNames = columnRows.stream()
                .map(map -> map.get("tableName").toUpperCase())
                .filter(name -> !tableIdByName.containsKey(name))
                .collect(Collectors.toSet());

        if (!writtenNames.isEmpty()) {
            List<TableInfo> candidates = tableInfoRepository.findAllByProjectIdAndTableNameIn(projectId,
                    writtenNames.stream().map(TableNameResolver::tableNameOf).collect(Collectors.toSet()));
            for (String written : writtenNames) {
                TableNameResolver.Result target = TableNameResolver.resolve(written, null, candidates, null);
                tableIdByName.put(written, target.status() == TableNameResolver.Status.FOUND ? target.table().getId() : null);
                if (target.status() == TableNameResolver.Status.FOUND) tableNameById.put(target.table().getId(), target.table().getTableName());
                if (target.status() == TableNameResolver.Status.AMBIGUOUS) ambiguousTableNames.add(written);
            }
        }

        Set<String> abbreviations = columnRows.stream()
                .flatMap(map -> Stream.of(map.get("columnName").toUpperCase(), map.get("columnName")))
                .filter(abbreviation -> !termIdByAbbreviation.containsKey(abbreviation))
                .collect(Collectors.toSet());

        if (!abbreviations.isEmpty()) {
            abbreviations.forEach(abbreviation -> termIdByAbbreviation.put(abbreviation, null));
            standardTermRepository.findAllByProjectIdAndCommonStandardTermAbbreviationIn(projectId, abbreviations)
                    .forEach(term -> {
                        if (termIdByAbbreviation.get(term.getCommonStandardTermAbbreviation()) == null) {
                            termIdByAbbreviation.put(term.getCommonStandardTermAbbreviation(), term.getId());
                        }
                    });
        }
    }

    /**
     * 청크의 (테이블, 컬럼명) 중 이미 등록된 조합 → "테이블ID|컬럼명"
     */
    private Set<String> findExistingColumns(Long projectId, List<Map<String, String>> columnRows, Map<String, Long> tableIdByName) {
        Set<Long> tableIds = columnRows.stream()
                .map(map -> tableIdByName.get(map.get("tableName").toUpperCase()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> columnNames = columnRows.stream().map(map -> map.get("columnName")).collect(Collectors.toSet());

        if (tableIds.isEmpty() || columnNames.isEmpty()) {
            return Set.of();
        }

        return columnInfoRepository.findTableColumnKeys(projectId, tableIds, columnNames).stream()
                .map(key -> key[0] + "|" + key[1])
                .collect(Collectors.toSet());
    }

    @Override
    public List<ColumnInfoDto> selectColumnDetail(AccountDto accountDto, Long id) {

        Long projectId = accountDto.getProjectId();

        ColumnInfo columnInfo = columnInfoRepository.findByProjectIdAndId(projectId, id);
        // 다른 프로젝트·없는 컬럼 (예전에는 NullPointerException 으로 500 이었다)
        if (columnInfo == null) throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);

        TableInfo tableInfo = columnInfo.getTableInfo();

        List<ColumnInfoDto> collect = tableInfo.getColumns().stream().map(ColumnInfoDto::fromEntity).collect(Collectors.toList());

        for(ColumnInfoDto dto : collect){
            if(dto.getIsApproval().equals("Y")){
                return List.of();
            }
        }

        return collect;
    }

    @Override
    @Transactional
    public void updateSortOrder(AccountDto accountDto, List<ColumnInfoDto> columnInfoDtos) {
        Long projectId = accountDto.getProjectId();

        columnInfoDtos.forEach(columnInfoDto -> {
            ColumnInfo columnInfo = columnInfoRepository.findByIdAndProjectId(columnInfoDto.getId(), projectId);

            if(columnInfo.getIsApproval()){
                throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
            }

            columnInfo.setSortOrder(columnInfoDto.getSortOrder());
        });
    }

    // ------------------------------------------------------------------ 컬럼 일괄 편집

    /** 붙여넣기 한 번에 찾을 수 있는 최대 줄 수 */
    static final int RESOLVE_MAX_INPUTS = 300;

    private static final java.util.regex.Pattern ABBREVIATION_INPUT = java.util.regex.Pattern.compile("^[A-Za-z0-9_]+$");

    @Override
    public List<TermResolveDto> resolveTerms(AccountDto accountDto, List<String> inputs) {
        Long projectId = accountDto.getProjectId();
        List<String> lines = inputs == null ? List.of() : inputs.stream()
                .map(input -> input == null ? "" : input.trim())
                .filter(input -> !input.isEmpty())
                .limit(RESOLVE_MAX_INPUTS)
                .toList();
        if (lines.isEmpty()) return List.of();

        // 용어명은 단어를 "_" 로 잇는다 : "사용자 이름" / "사용자_이름" 모두 같은 용어
        Set<String> names = lines.stream().map(ColumnManageServiceImpl::toTermName).collect(Collectors.toSet());
        Set<String> abbreviations = lines.stream()
                .filter(input -> ABBREVIATION_INPUT.matcher(input).matches())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        Map<String, List<StandardTerm>> byName = standardTermRepository.findAllByProjectIdAndCommonStandardTermNameIn(projectId, names).stream()
                .collect(Collectors.groupingBy(StandardTerm::getCommonStandardTermName));
        Map<String, List<StandardTerm>> byAbbreviation = abbreviations.isEmpty() ? Map.of()
                : standardTermRepository.findAllByProjectIdAndCommonStandardTermAbbreviationIn(projectId, abbreviations).stream()
                .collect(Collectors.groupingBy(term -> term.getCommonStandardTermAbbreviation().toUpperCase()));

        return lines.stream().map(input -> {
            // 용어명이 정확히 같은 용어를 먼저, 없으면 영문약어명
            List<StandardTerm> found = byName.getOrDefault(toTermName(input), List.of());
            if (found.isEmpty() && ABBREVIATION_INPUT.matcher(input).matches()) {
                found = byAbbreviation.getOrDefault(input.toUpperCase(), List.of());
            }
            return TermResolveDto.of(input, found.stream()
                    .sorted(java.util.Comparator.comparing((StandardTerm term) -> !Boolean.TRUE.equals(term.getIsApproval())) // 승인된 용어 먼저
                            .thenComparing(StandardTerm::getId))
                    .map(StandardTermDto::fromEntity)
                    .toList());
        }).toList();
    }

    private static String toTermName(String input) {
        return input.trim().replaceAll("[\\s_]+", "_");
    }

    @Override
    @Transactional
    public ColumnBulkSaveDto.Result saveColumnsBulk(AccountDto accountDto, ColumnBulkSaveDto request) {
        Long projectId = accountDto.getProjectId();

        // 로그인 프로젝트의 승인된 테이블에만 컬럼을 등록한다. (컬럼 등록 화면과 같은 기준)
        TableInfo tableInfo = request.getTableInfoId() == null ? null : tableInfoRepository.findByIdAndProjectId(request.getTableInfoId(), projectId);
        if (tableInfo == null) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }
        if (!Boolean.TRUE.equals(tableInfo.getIsApproval())) {
            return ColumnBulkSaveDto.Result.fail(List.of(new ColumnBulkSaveDto.RowError(null, Messages.get("승인된 테이블에만 컬럼을 등록할 수 있습니다."))));
        }

        Map<Long, ColumnInfo> existing = tableInfo.getColumns().stream()
                .collect(Collectors.toMap(ColumnInfo::getId, column -> column, (a, b) -> a, HashMap::new));
        List<ColumnBulkSaveDto.RowError> errors = new ArrayList<>();

        // 1) 삭제 대상 : 이 테이블의 승인 전 컬럼만
        Set<Long> deletedIds = new HashSet<>(request.getDeletedIds() == null ? List.of() : request.getDeletedIds());
        for (Long id : deletedIds) {
            ColumnInfo column = existing.get(id);
            if (column == null) {
                errors.add(new ColumnBulkSaveDto.RowError(String.valueOf(id), Messages.get("삭제할 컬럼이 이 테이블에 없습니다. (ID {0})", id)));
            } else if (Boolean.TRUE.equals(column.getIsApproval())) {
                errors.add(new ColumnBulkSaveDto.RowError(String.valueOf(id), Messages.get("{0} : 승인된 컬럼은 삭제할 수 없습니다.", column.getColumnName())));
            }
        }

        // 남는 기존 컬럼명 (중복 확인용)
        Set<String> columnNames = existing.values().stream()
                .filter(column -> !deletedIds.contains(column.getId()))
                .map(ColumnInfo::getColumnName)
                .collect(Collectors.toCollection(HashSet::new));

        // 2) 행 검증 : 기존 컬럼(수정) / 새 컬럼(추가)
        List<ColumnBulkSaveDto.Row> rows = request.getRows() == null ? List.of() : request.getRows();
        Map<String, StandardTerm> termByRowKey = new HashMap<>();
        Set<Long> seenIds = new HashSet<>();
        // 새로 적거나 바꾼 참조 테이블명 (행 키 → 값) : 여러 스키마에 있어 정할 수 없는 이름은 스키마명을 적게 한다
        Map<String, String> changedReferences = new java.util.LinkedHashMap<>();

        for (ColumnBulkSaveDto.Row row : rows) {
            String key = row.getKey();

            if (row.getId() != null) {
                ColumnInfo column = existing.get(row.getId());
                if (column == null) {
                    errors.add(new ColumnBulkSaveDto.RowError(key, Messages.get("이 테이블에 없는 컬럼입니다. 화면을 새로 열어주세요.")));
                } else if (deletedIds.contains(row.getId())) {
                    errors.add(new ColumnBulkSaveDto.RowError(key, Messages.get("{0} : 삭제와 수정을 함께 할 수 없습니다.", column.getColumnName())));
                } else if (!seenIds.add(row.getId())) {
                    errors.add(new ColumnBulkSaveDto.RowError(key, Messages.get("{0} : 같은 컬럼이 두 번 들어 있습니다.", column.getColumnName())));
                } else if (!Boolean.TRUE.equals(column.getIsApproval())) {
                    validateRowFields(row, column.getColumnName(), errors);
                    String reference = trimToNull(row.getRefTableName());
                    if (reference != null && !reference.equalsIgnoreCase(String.valueOf(column.getRefTableName()))) changedReferences.put(key, reference);
                }
                continue;
            }

            StandardTerm term = row.getTermId() == null ? null : standardTermRepository.findByIdAndProjectId(row.getTermId(), projectId);
            if (term == null) {
                errors.add(new ColumnBulkSaveDto.RowError(key, Messages.get("표준용어를 찾을 수 없습니다.")));
                continue;
            }

            String columnName = term.getCommonStandardTermAbbreviation();
            if (!columnNames.add(columnName)) {
                errors.add(new ColumnBulkSaveDto.RowError(key, Messages.get("{0} : 이 테이블에 이미 같은 컬럼명이 있습니다.", columnName)));
                continue;
            }
            validateRowFields(row, columnName, errors);
            if (trimToNull(row.getRefTableName()) != null) changedReferences.put(key, trimToNull(row.getRefTableName()));
            termByRowKey.put(key, term);
        }
        Set<String> ambiguous = ambiguousReferences(projectId, tableInfo, changedReferences.values());
        changedReferences.forEach((key, reference) -> {
            if (ambiguous.contains(reference.toUpperCase())) errors.add(new ColumnBulkSaveDto.RowError(key, ambiguousMessage(reference)));
        });

        if (!errors.isEmpty()) {
            return ColumnBulkSaveDto.Result.fail(errors);
        }

        // 관계·인덱스·제약조건에 쓰인 컬럼은 삭제할 수 없다 (참조 테이블명에서 만든 관계는 함께 지운다)
        physicalModelService.releaseColumns(projectId, deletedIds.stream().map(existing::get).toList());

        // 3) 저장 : 삭제 → 수정/추가 (화면 순서대로 정렬 순서 부여)
        for (Long id : deletedIds) {
            ColumnInfo column = existing.get(id);
            StandardTerm term = column.getStandardTerms();
            tableInfo.getColumns().remove(column);
            if (term != null && term.getColumnInfos() != null) term.getColumnInfos().remove(column);
            columnInfoRepository.delete(column);
        }

        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();
        int created = 0;
        int updated = 0;
        int order = 0;

        for (ColumnBulkSaveDto.Row row : rows) {
            if (row.getId() != null) {
                ColumnInfo column = existing.get(row.getId());

                if (Boolean.TRUE.equals(column.getIsApproval())) {
                    // 승인된 컬럼은 정해진 순서를 바꾸지 않는다. 뒤의 컬럼은 이 컬럼 다음 순서부터 매긴다.
                    // 단, 순서가 비어 있는 컬럼(예전 데이터)은 화면 순서대로 채운다. 비워 두면 목록 끝으로 밀려
                    // 새로 추가한 컬럼이 승인 컬럼보다 앞에 보인다. (값을 바꾸는 것이 아니라 없는 값을 채우는 것)
                    if (column.getSortOrder() == null) {
                        column.setSortOrder(++order);
                    } else {
                        order = Math.max(order, column.getSortOrder());
                    }
                    continue;
                }

                order++;
                if (applyRow(column, row, order)) updated++;
                continue;
            }

            StandardTerm term = termByRowKey.get(row.getKey());
            ColumnInfo column = ColumnInfo.builder()
                    .projectId(projectId)
                    .tableName(tableInfo.getTableName())
                    .columnName(term.getCommonStandardTermAbbreviation())
                    .isApproval(isApprovalAvailable)
                    .build();
            column.setTableInfo(tableInfo);
            column.setStandardTerms(term);
            applyRow(column, row, ++order);

            columnInfoRepository.save(column);
            tableInfo.getColumns().add(column);
            if (term.getColumnInfos() != null) term.getColumnInfos().add(column);
            created++;
        }

        physicalModelService.syncReferenceRelations(projectId, List.of(tableInfo.getId()));
        return new ColumnBulkSaveDto.Result(true, created, updated, deletedIds.size(), List.of());
    }

    /**
     * 필수 항목 : 컬럼 설명, 데이터 타입 (컬럼 등록 화면과 같은 기준) / 숫자 항목은 0 이상
     */
    private static void validateRowFields(ColumnBulkSaveDto.Row row, String columnName, List<ColumnBulkSaveDto.RowError> errors) {
        List<String> problems = new ArrayList<>();
        if (row.getColumnDesc() == null || row.getColumnDesc().isBlank()) problems.add(Messages.get("컬럼 설명"));
        if (row.getDataType() == null || row.getDataType().isBlank()) problems.add(Messages.get("데이터 타입"));

        if (!problems.isEmpty()) {
            errors.add(new ColumnBulkSaveDto.RowError(row.getKey(), Messages.get("{0} : {1}을(를) 입력해주세요.", columnName, String.join(", ", problems))));
        }
        if ((row.getMaxLength() != null && row.getMaxLength() < 0)
                || (row.getScale() != null && row.getScale() < 0)
                || (row.getPrecision() != null && row.getPrecision().signum() < 0)) {
            errors.add(new ColumnBulkSaveDto.RowError(row.getKey(), Messages.get("{0} : 길이/정밀도/소수 자릿수는 0 이상이어야 합니다.", columnName)));
        }
        String identityProblem = identityProblem(row.getIdentity(), row.getDataType(), row.getScale(), row.getDefaultValue());
        if (identityProblem != null) errors.add(new ColumnBulkSaveDto.RowError(row.getKey(), columnName + " : " + identityProblem));
    }

    private static final java.util.Set<String> IDENTITY_TYPES = java.util.Set.of("SMALLINT", "INT", "INTEGER", "BIGINT", "NUMBER", "NUMERIC", "DECIMAL");

    /**
     * IDENTITY 검증 : 정수 타입(SMALLINT·INTEGER·BIGINT, 소수 자릿수 없는 NUMBER·NUMERIC·DECIMAL)만, 기본값과 함께 쓰지 않는다
     * (SERIAL 타입은 이미 자동 증가라 함께 쓰지 않는다). 문제가 없으면 null
     */
    static String identityProblem(String identity, String dataType, Integer scale, String defaultValue) {
        if (identity == null || identity.isBlank()) return null;
        if (ColumnInfo.Identity.parse(identity) == null) return Messages.get("IDENTITY 는 ALWAYS 또는 BY_DEFAULT 입니다.");
        String type = dataType == null ? "" : dataType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!IDENTITY_TYPES.contains(type) || (scale != null && scale > 0)) return Messages.get("IDENTITY 는 정수 타입(SMALLINT·INTEGER·BIGINT 등)에만 쓸 수 있습니다.");
        if (defaultValue != null && !defaultValue.isBlank()) return Messages.get("IDENTITY 컬럼에는 기본값을 쓸 수 없습니다.");
        return null;
    }

    /**
     * 행 값을 컬럼에 반영한다. PK 는 NULL 비허용 + 필수 + 고유값으로 본다. (단건 등록 ColumnInfoDto.toEntity 와 같은 규칙)
     * @return 값이 바뀌었는지
     */
    private static boolean applyRow(ColumnInfo column, ColumnBulkSaveDto.Row row, int sortOrder) {
        boolean pk = isY(row.getIsPk());
        List<Object> before = snapshot(column);

        column.setColumnDesc(trimToNull(row.getColumnDesc()));
        column.setDataType(row.getDataType() == null ? null : row.getDataType().trim().toUpperCase());
        column.setMaxLength(row.getMaxLength());
        column.setPrecision(row.getPrecision());
        column.setScale(row.getScale());
        column.setDefaultValue(trimToNull(row.getDefaultValue()));
        column.setIdentity(ColumnInfo.Identity.parse(row.getIdentity()));
        column.setRefTableName(row.getRefTableName() == null || row.getRefTableName().isBlank() ? null : row.getRefTableName().trim().toUpperCase());
        column.setExample(trimToNull(row.getExample()));
        column.setIsPk(pk);
        column.setIsNullable(!pk && isY(row.getIsNullable()));
        column.setIsRequired(pk || isY(row.getIsRequired()));
        column.setIsUnique(pk || isY(row.getIsUnique()));
        column.setIsIndex(isY(row.getIsIndex()));
        column.setIsMasterData(isY(row.getIsMasterData()));
        column.setIsSensitive(isY(row.getIsSensitive()));
        column.setIsEncrypted(isY(row.getIsEncrypted()));
        column.setSortOrder(sortOrder);

        return !before.equals(snapshot(column));
    }

    private static List<Object> snapshot(ColumnInfo c) {
        return java.util.Arrays.asList(c.getColumnDesc(), c.getDataType(), c.getMaxLength(),
                c.getPrecision() == null ? null : c.getPrecision().stripTrailingZeros(),
                c.getScale(), c.getDefaultValue(), c.getIdentity(), c.getRefTableName(), c.getExample(), c.getIsPk(), c.getIsNullable(),
                c.getIsRequired(), c.getIsUnique(), c.getIsIndex(), c.getIsMasterData(), c.getIsSensitive(), c.getIsEncrypted(),
                c.getSortOrder());
    }

    private static boolean isY(String value) {
        return "Y".equalsIgnoreCase(value);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
