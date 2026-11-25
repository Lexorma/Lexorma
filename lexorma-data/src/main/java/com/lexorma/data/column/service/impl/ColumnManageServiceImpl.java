package com.lexorma.data.column.service.impl;

import com.lexorma.data.column.service.ColumnListFilter;
import com.lexorma.data.column.domain.dto.TermResolveDto;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.column.service.ColumnNameKey;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.service.BizDescriptions;
import com.lexorma.data.table.service.TableNameResolver;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.common.utils.ExcelUtils;
import com.lexorma.common.i18n.Messages;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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

@Service
@RequiredArgsConstructor
public class ColumnManageServiceImpl implements ColumnManageService {

    private final ColumnInfoRepository columnInfoRepository;
    private final TableInfoRepository tableInfoRepository;
    private final StandardTermRepository standardTermRepository;
    private final EntityManager entityManager;
    private final PhysicalModelService physicalModelService;
    private final com.lexorma.core.project.repository.ProjectRepository projectRepository;
    private final com.lexorma.core.project.guard.ProjectWriteGuard projectWriteGuard;
    private final com.lexorma.data.physical.sync.ModelRowLocks rowLocks;
    private final com.lexorma.data.dictionary.service.StandardTermAbbreviationLookup termLookup;
    private static final int FIRST_ROW = 1;

    @Override
    @Transactional(readOnly = true) // DTO 가 소속 테이블·스키마(지연 로딩)를 읽는다
    public Page<ColumnInfoDto> selectTableData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery, Long tableId) {
        Long projectId = accountDto.getProjectId();

        Specification<ColumnInfo> where = ColumnListFilter.of(projectId, searchColumn, searchQuery, tableId);

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

        // 테이블 행을 먼저 잠그고 읽는다 (기다림) : 같은 테이블의 컬럼 구성을 바꾸는 쓰기의 공통 잠금이다 — 컬럼 일괄 저장·엑셀 업로드·컬럼 삭제·PK 변경,
        // 물리명 추천 일괄 적용(프로젝트 배타 뒤 이 행을 NOWAIT), 논리 → 물리 생성(M7-D39). 아래 이름 중복 검사는 이 잠금 뒤의 최신 상태로 한다 —
        // 같은 테이블에 같은 이름을 동시에 등록하거나, 그 이름으로 다른 컬럼의 이름을 바꾸는 적용과 함께 커밋되지 않는다 (M-7 물리명 일괄 적용 2차 후속, 2026-11-21).
        // 예전에는 새 PK 컬럼일 때만 잠갔다 (PK 가 아닌 등록은 업무 설명 직접 편집처럼 테이블 행을 쥔 짧은 작업을 이제 기다린다)
        // 새 PK 컬럼 등록은 PK 구성을 바꾸는 구조 변경이다 (M8-C4b-R2 R2-D01) : 일괄 저장의 PK 변경과 같은 경로 (프로젝트 배타 → 테이블·소속 컬럼·참조 컬럼 NOWAIT, 바쁘면 409)
        TableInfo tableInfo;
        if (columnInfoDto.getTableInfoId() != null && isY(columnInfoDto.getIsPk())) {
            tableInfo = lockForKeyChange(projectId, List.of(columnInfoDto.getTableInfoId()), Set.of(),
                    columnInfoRepository.findIdsByTableIds(List.of(columnInfoDto.getTableInfoId()))).stream().findFirst().orElse(null);
        } else {
            tableInfo = columnInfoDto.getTableInfoId() == null ? null : tableInfoRepository.findForUpdate(columnInfoDto.getTableInfoId(), projectId).orElse(null);
        }
        StandardTerm standardTerm = standardTermRepository.findByIdAndProjectId(columnInfoDto.getTermId(), projectId);

        // 테이블/표준용어를 선택하지 않았거나 다른 프로젝트의 정보면 등록할 수 없다.
        if(tableInfo == null){
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }
        if(standardTerm == null){
            throw new CustomException(ErrorCode.NOT_FOUND_TERM_DATA);
        }
        // 컬럼명 필수 (M7-U05) : null·빈 문자열·공백류만인 이름은 입력 오류 (중복 오류가 아니다). DB 도 NOT NULL·CK_COL_01 로 막는다
        if (ColumnNameKey.isBlankName(columnInfoDto.getColumnName())) {
            throw CustomException.withMessage(ErrorCode.INVALID_PHYSICAL_MODEL, "컬럼명을 입력해주세요."); // 코드·400 은 그대로, 안내는 이 상황 문구만
        }

        // 같은 테이블 안에서만 컬럼명 중복을 막는다. (다른 테이블에는 같은 표준 컬럼을 쓸 수 있다)
        // 비교 : 앞뒤 공백을 지우고 대문자로 (M7-U01 — CUST_NO 가 있으면 cust_no·" CUST_NO " 도 같은 이름이다). 저장하는 이름은 입력 그대로다
        List<ColumnInfo> duplicates = columnInfoRepository.findSameName(projectId, tableInfo.getId(), columnInfoDto.getColumnName());

        if(!duplicates.isEmpty()){
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableInfo.getTableName() + "." + columnInfoDto.getColumnName());
        }

        String identityProblem = identityProblem(columnInfoDto.getIdentity(), columnInfoDto.getDataType(), columnInfoDto.getScale(), columnInfoDto.getDefaultValue());
        if (identityProblem != null) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "{0} : {1}", columnInfoDto.getColumnName(), identityProblem);
        BizDescriptions.requireLength(columnInfoDto.getBizDescription(), null);
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
        // DB UNIQUE(UX_COL_01) 최종 방어 : 위 검사와 같은 기준이라 보통은 걸리지 않는다. 걸리면 같은 중복 오류로 (트랜잭션은 롤백)
        ColumnNameKey.flush(entityManager, () -> new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableInfo.getTableName() + "." + columnInfoDto.getColumnName()));
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
        // 등록과 같은 비교 기준 (앞뒤 공백 제거 + 대문자, M7-U01)
        return columnInfoRepository.findSameName(accountDto.getProjectId(), tableInfoId, columnName).stream()
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
        boolean isApproval = Boolean.parseBoolean(type);

        // 승인할 컬럼 행을 ID 순으로 잠그고 다시 읽은 뒤(M8-C2, X-02·X-03), 승인자가 본 컬럼(행의 modifiedAt)과 같을 때만 바꾼다 — 하나라도 다르면 409 (전체 롤백).
        // 예전에는 잠그지 않아, 그 사이 커밋된 수정(설명 등)을 승인자가 본 적 없이 승인했고 같은 행의 삭제와도 엇갈렸다. 이미 그 상태면 바꾸지 않는다
        Map<Long, java.time.LocalDateTime> bases = new HashMap<>();
        columnInfoDtos.forEach(dto -> {
            if (dto.getId() == null) throw new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED);
            bases.put(dto.getId(), dto.getModifiedAt());
        });
        for (Long id : new java.util.TreeSet<>(bases.keySet())) {
            ColumnInfo columnInfo = lockColumn(projectId, id);
            if (Boolean.TRUE.equals(columnInfo.getIsApproval()) == isApproval) continue;
            com.lexorma.common.jpa.ModelBase.verify(columnInfo, bases.get(id), columnInfo.getColumnName());
            columnInfo.setIsApproval(isApproval);
        }

    }

    @Override
    @Transactional
    public void deleteColumn(AccountDto accountDto, List<ColumnInfoDto> columnInfoDtos) {
//        if(!AuthUtil.isApprovalAvailable()){
//            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
//        }

        Long projectId = accountDto.getProjectId();
        Set<Long> tableIds = new HashSet<>();

        // PK 컬럼을 지우면 구조 변경이다 (M7-FK-D11) : 프로젝트 행 배타(맨 먼저) → 테이블 행 → 지울 컬럼(키 강도) → 참조 컬럼, 모두 기다리지 않는다 (바쁘면 409).
        // PK 가 아닌 컬럼 삭제는 예전 그대로 기다린다 — 소속 테이블 행을 ID 순으로 먼저 잡고(releaseColumns 가 잡던 잠금), 잡은 뒤 PK 여부를 다시 본다
        Set<Long> requested = columnInfoDtos.stream().map(ColumnInfoDto::getId).filter(Objects::nonNull).collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!requested.isEmpty()) {
            boolean keyChange = !columnInfoRepository.findPkColumnIds(projectId, requested).isEmpty();
            List<Long> ownerTables = columnInfoRepository.findTableIdsOfColumns(projectId, requested);
            if (keyChange) {
                Set<Long> deletes = new HashSet<>(columnInfoRepository.findIdsByTableIds(ownerTables));
                deletes.retainAll(requested); // 이 프로젝트의 컬럼만
                lockForKeyChange(projectId, ownerTables, deletes, Set.of());
            } else {
                ownerTables.stream().sorted().forEach(id -> tableInfoRepository.findForUpdate(id, projectId));
                if (!columnInfoRepository.findPkColumnIds(projectId, requested).isEmpty()) throw com.lexorma.data.physical.sync.ModelRowLocks.changedMeanwhile();
            }
        }

        // 지울 컬럼 행도 ID 순으로 잠그고 다시 읽는다 (M8-C2) : 컬럼 승인은 테이블 행이 아니라 컬럼 행을 잠그므로, 테이블 잠금만으로는 그 사이 승인된 컬럼을 지울 수 있었다 (X-03)
        Map<Long, ColumnInfo> locked = new HashMap<>();
        for (Long id : requested) locked.put(id, lockColumn(projectId, id));
        Set<Long> handled = new HashSet<>();

        columnInfoDtos.forEach(columnInfoDto -> {
            // 없는(그 사이 지워진·다른 프로젝트) 컬럼은 대상 없음 400 (예전에는 NullPointerException 으로 500 — M8-C1)
            ColumnInfo columnInfo = columnInfoDto.getId() == null ? null : locked.get(columnInfoDto.getId());
            if (columnInfo == null) throw new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED);
            if (!handled.add(columnInfo.getId())) return; // 같은 컬럼이 두 번 온 요청 : 한 번만 지운다

            if(columnInfo.getIsApproval()){
                throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
            }
            // 화면이 본 컬럼(행의 modifiedAt)과 같을 때만 지운다 (M8-C2, M8-D01) — 다르면 409, 앞에서 지운 컬럼도 롤백된다
            com.lexorma.common.jpa.ModelBase.verify(columnInfo, columnInfoDto.getModifiedAt(), columnInfo.getColumnName());

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

    /** 컬럼 행을 잠그고 다시 읽는다 (FOR NO KEY UPDATE — 이미 키 강도로 잡은 행이면 기다리지 않는다). 없거나 다른 프로젝트·그 사이 지워졌으면 대상 없음 400 */
    private ColumnInfo lockColumn(Long projectId, Long id) {
        ColumnInfo column = columnInfoRepository.findByProjectIdAndId(projectId, id);
        if (column == null) throw new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED);
        try {
            entityManager.refresh(column, LockModeType.PESSIMISTIC_WRITE);
        } catch (jakarta.persistence.EntityNotFoundException gone) {
            throw new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED);
        }
        return column;
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
                // S열 "엑셀 컬럼 헤더 표시명" : 담는 모델 속성이 없어 읽기만 하고 저장하지 않는다.
                // 예전에는 이 열을 example 로 읽어 템플릿의 T열 "데이터 예시" 가 버려졌다 (AI-1d 에서 T열로 바로잡음, 이미 저장된 값은 그대로)
                "excelHeaderLabel",
                "example",
                // U열 업무 설명 (AI-1d) : 열이 없는 예전 파일은 빈 값으로 읽힌다. 등록(INSERT)만 하므로 비면 NULL (이미 있는 컬럼은 예전처럼 업로드 전체를 막는다)
                "bizDescription"
        );

        // 파일 안 중복 확인용 : "테이블ID|비교 값" (앞뒤 공백 제거 + 대문자, M7-U01 — 컬럼명은 적은 그대로 저장한다)
        Set<String> uploadedColumns = new HashSet<>();

        // 업로드 중에는 테이블/표준용어가 바뀌지 않으므로 조회 결과(ID)를 캐시한다. (없으면 null 로 기록)
        Map<String, Long> tableIdByName = new HashMap<>();
        Map<Long, String> tableNameById = new HashMap<>();
        Set<String> ambiguousTableNames = new HashSet<>();
        // 컬럼명(적은 그대로) → 영문약어가 같은 표준 용어 ID 들 (M7-NAME-1 : 대소문자 무시, 여러 개면 정하지 않는다)
        Map<String, List<Long>> termIdsByColumnName = new HashMap<>();

        // 잠금 순서 (M7-U07) : 업로드 전체가 한 트랜잭션이라 청크마다 ID 순으로 잠가도 청크 사이에서 순서가 뒤집혀 교착이 날 수 있었다
        // (업로드 A 청크 1 = T1 → 청크 2 = T2, 업로드 B 청크 1 = T2 → 청크 2 = T1 — PostgreSQL 40P01 재현). 파일을 한 번 먼저 읽어 대상 테이블명만 모으고
        // (행은 메모리에 두지 않는다), 그 테이블 행을 모두 ID 순으로 잠근 뒤 청크 처리를 시작한다. 청크·저장·전체 롤백은 그대로다
        Set<String> writtenTables = new HashSet<>();
        // PK 행(PK 여부 Y)을 적은 테이블 (M8-C4b-R2) : 새 PK 컬럼 = PK 구성 변경. 같은 첫 읽기에서 모은다 (DB 잠금 전 — 파일 읽기 동안 잠그지 않는다)
        Set<String> pkTables = new HashSet<>();
        // 첫 열(테이블명)과 PK 여부 열만 값으로 만든다 (나머지 칸은 읽고 버린다 — 할당을 줄인다)
        ExcelUtils.readExcelRows(file, 0, tableExcelHeaders.subList(0, tableExcelHeaders.indexOf("isPk") + 1), FIRST_ROW, row -> {
            String written = row.get("tableName");
            if (written != null && !written.isEmpty()) {
                writtenTables.add(written.toUpperCase());
                if ("Y".equals(row.get("isPk"))) pkTables.add(written.toUpperCase());
            }
        });
        resolveUploadTables(projectId, writtenTables, tableIdByName, tableNameById, ambiguousTableNames);
        Set<Long> lockedTables = new HashSet<>();
        List<Long> uploadTables = writtenTables.stream().map(tableIdByName::get).filter(Objects::nonNull).distinct().sorted().toList();
        Set<Long> keyTables = pkTables.stream().map(tableIdByName::get).filter(Objects::nonNull).collect(Collectors.toCollection(java.util.TreeSet::new));
        boolean keyMode = !keyTables.isEmpty();
        if (keyMode) {
            // PK 행이 있는 업로드만 구조 변경 경로 (R2-D02 — PK 행이 없는 업로드는 예전처럼 테이블 행을 기다린다) : 프로젝트 배타(맨 먼저) → 업로드 테이블 NOWAIT(ID 순) →
            // PK 가 바뀌는 테이블은 키 강도 NOWAIT → 그 테이블을 참조 테이블명으로 가리키는 컬럼 NOWAIT. 업로드 전체가 한 트랜잭션(전체 롤백)이라 배타는 커밋까지 쥔다 —
            // 잠금은 파일 첫 읽기·테이블명 해석이 끝난 뒤에 잡는다 (파일 읽기 동안 쥐지 않는다)
            projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
            projectWriteGuard.lock(projectId);
            rowLocks.tablesNoWait(uploadTables);
            rowLocks.tablesForKeyChange(keyTables);
            Set<String> names = tableInfoRepository.findAllById(keyTables).stream().flatMap(t -> TableNameResolver.namesOf(t).stream()).collect(Collectors.toSet());
            if (!names.isEmpty()) {
                rowLocks.columnsNoWait(columnInfoRepository.findAllReferencing(projectId, names).stream().map(ColumnInfo::getId).toList());
            }
            lockedTables.addAll(uploadTables);
        } else {
            uploadTables.forEach(id -> {
                tableInfoRepository.findForUpdate(id, projectId);
                lockedTables.add(id);
            });
        }
        confirmUploadTables(projectId, writtenTables, tableIdByName, tableNameById, ambiguousTableNames);

        // 청크(500행) 단위 : 테이블/용어/중복은 청크당 IN 조회 한 번씩, 저장은 JDBC 배치, 저장 후 영속성 컨텍스트 비우기
        ExcelUtils.readExcelRowsInChunks(file, 0, tableExcelHeaders, FIRST_ROW, ExcelUtils.UPLOAD_CHUNK_SIZE, rows -> {
            List<Map<String, String>> columnRows = rows.stream().filter(map -> !map.get("tableName").isEmpty()).toList();

            loadColumnUploadReferences(projectId, columnRows, tableIdByName, tableNameById, ambiguousTableNames, termIdsByColumnName);
            // 이 청크가 컬럼을 더할 테이블 행은 위에서 업로드 전체 기준으로 이미 ID 순으로 잠갔다 (단건 등록과 같은 공통 잠금 — 그 뒤에 이미 있는 컬럼을 읽는다).
            // 먼저 읽은 테이블 집합에 없는 테이블은 없어야 하지만, 있으면 여기서 잠근다
            List<Long> unlocked = columnRows.stream().map(map -> tableIdByName.get(map.get("tableName").toUpperCase())).filter(java.util.Objects::nonNull).distinct().sorted()
                    .filter(id -> !lockedTables.contains(id)).toList();
            // 구조 변경 경로(프로젝트 배타)에서는 기다리지 않는다
            if (keyMode) rowLocks.tablesNoWait(unlocked);
            else unlocked.forEach(id -> tableInfoRepository.findForUpdate(id, projectId));
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

                // 컬럼명 필수 (M7-U05) : 빈 칸·공백류만은 입력 오류 — 업로드 전체 취소 (예전에는 용어 없음 오류로 끝났다)
                String columnName = map.get("columnName");
                if (ColumnNameKey.isBlankName(columnName)) {
                    throw CustomException.withMessage(ErrorCode.INVALID_PHYSICAL_MODEL, "{0} : 컬럼명이 비어 있는 행이 있습니다.", tableName);
                }
                // 표준용어 : 영문약어가 컬럼명과 같은 용어 (M7-NAME-1, NAME-P09 — 앞뒤 공백 제거 + 대소문자 무시, StandardNames.sameColumnName).
                // 엑셀 행에는 용어·도메인을 고를 칸이 없어 후보를 더 좁힐 문맥이 없다 : 여러 개면 승인 여부·DB 순서로 고르지 않고 업로드 전체를 취소한다
                List<Long> termIds = termIdsByColumnName.getOrDefault(columnName, List.of());
                if (termIds.isEmpty()) {
                    throw new CustomException(ErrorCode.TERM_IS_NOT_DEFINED, columnName);
                }
                if (termIds.size() > 1) {
                    throw new CustomException(ErrorCode.AMBIGUOUS_STANDARD_TERM, "{0}.{1} : 영문약어가 같은 표준 용어 {2}개", tableName, columnName, termIds.size());
                }
                Long termId = termIds.get(0);

                chunkTableIds.add(tableInfoId);

                // 같은 테이블 안에서만 컬럼명 중복을 막는다. (화면 등록과 같은 기준) - 하나라도 있으면 업로드 전체 취소
                String columnKey = tableInfoId + "|" + ColumnNameKey.of(columnName);

                if (!uploadedColumns.add(columnKey)) {
                    throw new CustomException(ErrorCode.COLUMN_DUPLICATE_IN_FILE, tableName + "." + columnName);
                }

                if (existingColumns.contains(columnKey)) {
                    throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableName + "." + columnName);
                }

                // 업무 설명 길이 초과 : 업로드 전체를 취소한다 (자르지 않는다)
                if (BizDescriptions.tooLong(map.get("bizDescription"), null)) throw BizDescriptions.tooLong(tableName + "." + columnName);

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
                        .bizDescription(BizDescriptions.normalize(map.get("bizDescription")))
                        .standardTerms(entityManager.getReference(StandardTerm.class, termId))
                        .tableInfo(entityManager.getReference(TableInfo.class, tableInfoId))
                        .isApproval(isApprovalAvailable)
                        .build());
            }

            // 테이블 행 잠금은 위(이미 있는 컬럼을 읽기 전)에서 이 청크의 모든 테이블에 걸었다 (PK 컬럼을 더하는 테이블 포함 — M-7 3차 M7-D39 의 잠금을 넓힌 것)
            columnInfoRepository.saveAll(columnInfos);
            // DB UNIQUE(UX_COL_01) 최종 방어 : 걸리면 같은 중복 오류로 업로드 전체를 취소한다 (기존처럼 하나라도 중복이면 전체 취소)
            ColumnNameKey.flush(entityManager, () -> new CustomException(ErrorCode.SAVED_DATA_EXISTS,
                    chunkTableIds.stream().sorted().map(tableNameById::get).collect(Collectors.joining(", "))));
            // 참조 테이블명 → 관계 (청크의 테이블 기준, 저장한 행을 DB 에서 다시 읽는다)
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
                                            Map<Long, String> tableNameById, Set<String> ambiguousTableNames, Map<String, List<Long>> termIdsByColumnName) {
        resolveUploadTables(projectId, columnRows.stream().map(map -> map.get("tableName").toUpperCase()).collect(Collectors.toSet()),
                tableIdByName, tableNameById, ambiguousTableNames);

        // 표준 용어 : 아직 찾지 않은 컬럼명만 한 번에 (청크당 조회 1번). 대소문자 무시 + 후보 전체 (예전에는 저장된 약어와 대소문자까지 같은 첫 행만 썼다)
        Set<String> columnNames = columnRows.stream()
                .map(map -> map.get("columnName"))
                .filter(name -> name != null && !termIdsByColumnName.containsKey(name))
                .collect(Collectors.toSet());

        if (!columnNames.isEmpty()) {
            termLookup.find(projectId, columnNames).forEach((name, terms) -> termIdsByColumnName.put(name, terms.stream().map(StandardTerm::getId).toList()));
        }
    }

    /** 엑셀에 적은 테이블명(대문자) → 테이블 ID (아직 찾지 않은 이름만 한 번에 조회, 없으면 null · 여러 스키마면 ambiguous 에 기록) */
    private void resolveUploadTables(Long projectId, Set<String> names, Map<String, Long> tableIdByName, Map<Long, String> tableNameById,
                                     Set<String> ambiguousTableNames) {
        Set<String> writtenNames = names.stream()
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
    }

    /**
     * 테이블 행을 잠근 뒤 엑셀에 적은 테이블명을 최신 데이터로 다시 해석한다 (M8-C4b P-03). 잠그기 전에 푼 이름 → ID 는 그 사이 커밋된 이름 변경·스키마 이름 변경·삭제를 모른다 :
     * 예전에는 지워진 테이블 ID 로 넣다 FK 위반(500)이 나거나, 이름이 바뀐 테이블에 예전 이름(TBL_NM 사본)으로 컬럼이 들어갔다.
     * 잠근 행은 더 이상 이름이 바뀌거나 지워지지 않는다 (이름 변경은 그 행을 NOWAIT 로, 삭제는 대기로 잡는다). 처음 찾은 이름만 다시 본다 :
     * <ul>
     *   <li>같은 테이블 → 최신 테이블명으로 (대소문자만 바뀐 이름 변경 포함)</li>
     *   <li>이제 없음·여러 스키마 → 처음부터 그랬던 것처럼 행 검증의 기존 오류 (대상 없음 400 — 그 변경 뒤에 올린 파일과 같은 결과)</li>
     *   <li>같은 이름이 다른 테이블 (지우고 같은 이름으로 다시 만듦·다른 스키마로 이동 뒤 같은 이름 생성) → 409. 이름만 보고 다른 테이블에 넣지 않는다</li>
     * </ul>
     * 처음에 못 찾은 이름은 그대로 둔다 (그 사이 생긴 테이블은 잠그지 않았다 — 기존 대상 없음 오류). 영속성 컨텍스트에는 첫 조회의 테이블이 남아 있어 비운다 (아직 쓴 것이 없다)
     */
    private void confirmUploadTables(Long projectId, Set<String> writtenTables, Map<String, Long> tableIdByName, Map<Long, String> tableNameById,
                                     Set<String> ambiguousTableNames) {
        if (tableIdByName.values().stream().noneMatch(Objects::nonNull)) return;
        entityManager.clear();
        Map<String, Long> freshIds = new HashMap<>();
        Map<Long, String> freshNames = new HashMap<>();
        Set<String> freshAmbiguous = new HashSet<>();
        resolveUploadTables(projectId, writtenTables.stream().filter(name -> tableIdByName.get(name) != null).collect(Collectors.toSet()), freshIds, freshNames, freshAmbiguous);
        for (String written : writtenTables.stream().sorted().toList()) {
            Long before = tableIdByName.get(written);
            if (before == null) continue;
            Long now = freshIds.get(written);
            if (now != null && !now.equals(before)) {
                throw CustomException.withMessage(ErrorCode.MODEL_CONFLICT, "업로드를 시작한 뒤 {0} 이(가) 다른 테이블로 바뀌어 업로드하지 않았습니다. 테이블을 확인한 뒤 다시 업로드해주세요.", written);
            }
            tableIdByName.put(written, now);
            if (now == null && freshAmbiguous.contains(written)) ambiguousTableNames.add(written);
            if (now != null) tableNameById.put(now, freshNames.get(now));
        }
    }

    /**
     * 청크의 (테이블, 컬럼명) 중 이미 등록된 조합 → "테이블ID|비교 값" (비교 값이 같으면 이미 있는 이름이다, M7-U01)
     */
    private Set<String> findExistingColumns(Long projectId, List<Map<String, String>> columnRows, Map<String, Long> tableIdByName) {
        Set<Long> tableIds = columnRows.stream()
                .map(map -> tableIdByName.get(map.get("tableName").toUpperCase()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> columnNames = columnRows.stream().map(map -> ColumnNameKey.of(map.get("columnName"))).collect(Collectors.toSet());

        if (tableIds.isEmpty() || columnNames.isEmpty()) {
            return Set.of();
        }

        return columnInfoRepository.findTableColumnKeys(projectId, tableIds, columnNames).stream()
                .map(key -> key[0] + "|" + ColumnNameKey.of((String) key[1]))
                .collect(Collectors.toSet());
    }

    @Override
    public List<ColumnInfoDto> selectColumnDetail(AccountDto accountDto, Long id) {

        Long projectId = accountDto.getProjectId();

        ColumnInfo columnInfo = columnInfoRepository.findByProjectIdAndId(projectId, id);
        // 다른 프로젝트·없는 컬럼 (예전에는 NullPointerException 으로 500 이었다)
        if (columnInfo == null) throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);

        TableInfo tableInfo = columnInfo.getTableInfo();

        // 저장된 정렬 순서대로 (같으면 ID 순) — 컬럼 순서 팝업이 이 순서로 카드를 보이고 그대로 저장한다 (M8-FINAL : 예전에는 정렬 없는 컬렉션 순서(사실상 ID 순)라
        // 팝업이 저장된 순서와 다르게 보였고, 그대로 저장하면 다른 사용자가 정한 순서가 ID 순으로 되돌아갔다. 409 비교의 '현재 서버 순서' 도 틀리게 보였다)
        List<ColumnInfoDto> collect = tableInfo.getColumns().stream()
                .sorted(java.util.Comparator.comparing((ColumnInfo c) -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder()).thenComparing(ColumnInfo::getId))
                .map(ColumnInfoDto::fromEntity).collect(Collectors.toList());

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
        if (columnInfoDtos == null || columnInfoDtos.isEmpty()) return;
        // M8-C1 P-01 : 오래된 순서 팝업이 그 사이 다른 사용자가 바꾼 순서를 되돌리지 않게 한다. 한 테이블의 컬럼 전체를 보내야 하고(팝업은 테이블의 컬럼 전체를 보여 준다),
        // 테이블 행을 먼저 잠근 뒤(컬럼 구성을 바꾸는 쓰기의 공통 잠금 — 등록·일괄 저장·삭제·엑셀) 컬럼 행을 ID 순으로 잠그고 다시 읽어 비교한다.
        // 컬럼이 더해지거나 지워졌으면, 또는 컬럼 하나라도 지금 순서가 팝업이 본 순서(baseSortOrder)와 다르면 409 — 아무것도 바꾸지 않는다 (한 트랜잭션)
        Map<Long, ColumnInfoDto> requested = new java.util.LinkedHashMap<>();
        for (ColumnInfoDto dto : columnInfoDtos) {
            if (dto == null || dto.getId() == null || requested.putIfAbsent(dto.getId(), dto) != null) {
                throw CustomException.withMessage(ErrorCode.INVALID_PHYSICAL_MODEL, "한 테이블의 컬럼 순서만 한 번에 저장할 수 있습니다.");
            }
        }
        ColumnInfo first = columnInfoRepository.findByIdAndProjectId(requested.keySet().iterator().next(), projectId);
        if (first == null || first.getTableInfo() == null) throw new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED);
        Long tableId = first.getTableInfo().getId();
        tableInfoRepository.findForUpdate(tableId, projectId).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
        List<ColumnInfo> current = columnInfoRepository.findAllByTableInfo_IdIn(List.of(tableId)).stream()
                .sorted(java.util.Comparator.comparing(ColumnInfo::getId)).toList();
        for (ColumnInfo column : current) entityManager.refresh(column, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        Set<Long> currentIds = current.stream().map(ColumnInfo::getId).collect(Collectors.toSet());
        for (Long id : requested.keySet()) {
            if (currentIds.contains(id)) continue;
            ColumnInfo elsewhere = columnInfoRepository.findByIdAndProjectId(id, projectId);
            // 다른 테이블의 컬럼을 섞어 보냈다 (요청 오류) — 없는 컬럼은 그 사이 지워진 것이다 (아래 409)
            if (elsewhere != null && elsewhere.getTableInfo() != null && !tableId.equals(elsewhere.getTableInfo().getId())) {
                throw CustomException.withMessage(ErrorCode.INVALID_PHYSICAL_MODEL, "한 테이블의 컬럼 순서만 한 번에 저장할 수 있습니다.");
            }
        }
        if (!currentIds.equals(requested.keySet())) {
            throw CustomException.withMessage(ErrorCode.MODEL_CONFLICT, "화면을 연 뒤 이 테이블의 컬럼이 추가되거나 삭제되어 순서를 저장하지 않았습니다. 컬럼 순서 변경을 다시 열어 주세요.");
        }
        for (ColumnInfo column : current) {
            if (Boolean.TRUE.equals(column.getIsApproval())) {
                throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
            }
            int seen = requested.get(column.getId()).getBaseSortOrder() == null ? 0 : requested.get(column.getId()).getBaseSortOrder();
            int now = column.getSortOrder() == null ? 0 : column.getSortOrder();
            if (seen != now) {
                throw CustomException.withMessage(ErrorCode.MODEL_CONFLICT, "화면을 연 뒤 다른 사용자가 컬럼 {0} 의 순서를 바꿔 순서를 저장하지 않았습니다. 컬럼 순서 변경을 다시 열어 주세요.", column.getColumnName());
            }
        }
        for (ColumnInfo column : current) column.setSortOrder(requested.get(column.getId()).getSortOrder());
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
                .collect(Collectors.toSet());

        Map<String, List<StandardTerm>> byName = standardTermRepository.findAllByProjectIdAndCommonStandardTermNameIn(projectId, names).stream()
                .collect(Collectors.groupingBy(StandardTerm::getCommonStandardTermName));
        // 영문약어 : 대소문자 무시 + 후보 전체 (M7-NAME-1 — 엑셀 업로드와 같은 StandardTermAbbreviationLookup. 저장된 약어가 소문자여도 찾는다)
        Map<String, List<StandardTerm>> byAbbreviation = abbreviations.isEmpty() ? Map.of() : termLookup.find(projectId, abbreviations);

        return lines.stream().map(input -> {
            // 용어명이 정확히 같은 용어를 먼저, 없으면 영문약어명
            List<StandardTerm> found = byName.getOrDefault(toTermName(input), List.of());
            if (found.isEmpty() && ABBREVIATION_INPUT.matcher(input).matches()) {
                found = byAbbreviation.getOrDefault(input, List.of());
            }
            // 여러 건(AMBIGUOUS)이면 화면이 고르게 한다 — 승인된 용어를 앞에 보이지만 서버·화면 모두 미리 고르지 않는다 (NAME-P09)
            return TermResolveDto.of(input, found.stream()
                    .sorted(java.util.Comparator.comparing((StandardTerm term) -> !Boolean.TRUE.equals(term.getIsApproval())) // 승인된 용어 먼저 (표시 순서만)
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
        // 테이블 행을 먼저 잠근다 (컬럼 구성·PK 를 바꾸는 쓰기의 공통 잠금 — M-7 3차, M7-D39. 순서 : 테이블 → 컬럼 행)
        // PK 컬럼을 지우는 저장은 구조 변경이다 (M7-FK-D11) : 잠그기 전에 판정해 프로젝트 행 배타(맨 먼저) → 테이블 행·컬럼 행 NOWAIT 로 잠근다 (바쁘면 409).
        // 지운 PK 를 다른 저장의 관계 동기화가 참조(KEY SHARE)하고, 그 저장이 지운 PK 를 이쪽 동기화가 참조하던 교차 교착(40P01)을 없앤다. 다른 저장은 예전 그대로다
        Set<Long> requestedDeletes = request.getDeletedIds() == null ? Set.of()
                : request.getDeletedIds().stream().filter(Objects::nonNull).collect(Collectors.toCollection(java.util.TreeSet::new));
        // 구조 변경 판정 (M7-FK-D11 + M8-C4b-R2 R2-D01) : 저장 전후의 실제 PK 구성이 달라지면 — PK 컬럼 삭제, PK 여부 N→Y·Y→N, 새 PK 컬럼 — 구조 변경 경로다.
        // 요청에 PK 컬럼이 들어 있다는 것만으로는 아니다 (설명만 고친 PK 행·Y→Y·N→N·unchanged 행·승인된 행(값을 쓰지 않는다)은 일반 저장). DB 의 저장 상태(스칼라 조회)와 비교한다
        boolean keyChange = request.getTableInfoId() != null
                && changesPrimaryKey(projectId, request.getTableInfoId(), request.getRows(), requestedDeletes);
        TableInfo tableInfo;
        if (keyChange) {
            List<Long> own = columnInfoRepository.findIdsByTableIds(List.of(request.getTableInfoId()));
            Set<Long> deletes = requestedDeletes.stream().filter(own::contains).collect(Collectors.toSet());
            tableInfo = lockForKeyChange(projectId, List.of(request.getTableInfoId()), deletes, own).stream().findFirst().orElse(null);
        } else {
            tableInfo = request.getTableInfoId() == null ? null : tableInfoRepository.findForUpdate(request.getTableInfoId(), projectId).orElse(null);
        }
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

        // 잠그기 전에는 PK 구성을 바꾸지 않던 저장이 잠근 뒤에는 바꾼다 (그 사이 다른 저장이 PK 를 바꿨다 — 지울 컬럼이 PK 가 됐거나, 보낸 PK 여부가 이제 지금 값과 다르다) :
        // 이 행 잠금을 쥔 채 프로젝트 배타를 뒤늦게 잡지 않고(잠금 승격 없음) 409 로 끝낸다 (D11 · R2). 전체 롤백 — 다시 읽고 다시 저장하면 구조 변경 경로로 간다
        if (!keyChange && changesPrimaryKey(existing, request.getRows(), deletedIds)) {
            throw com.lexorma.data.physical.sync.ModelRowLocks.changedMeanwhile();
        }

        // 남는 기존 컬럼명의 비교 값 (중복 확인용 — 앞뒤 공백 제거 + 대문자, M7-U01). 이 저장에서 지우는 컬럼의 이름은 새 컬럼이 쓸 수 있다
        Set<String> columnNames = existing.values().stream()
                .filter(column -> !deletedIds.contains(column.getId()))
                .map(column -> ColumnNameKey.of(column.getColumnName()))
                .collect(Collectors.toCollection(HashSet::new));

        // 2) 행 검증 : 기존 컬럼(수정) / 새 컬럼(추가)
        List<ColumnBulkSaveDto.Row> rows = request.getRows() == null ? List.of() : request.getRows();
        // 업무 설명을 고치는 행은 먼저 잠그고 지금 값을 다시 읽어 비교한다 : 같은 업무 설명을 동시에 고친 두 저장(또는 AI 제안 승인) 중 뒤의 것이
        // 앞의 것을 모르고 덮어쓰지 않도록 (AI-5). 교착을 피하려고 ID 순서로 잠근다. 다른 행은 잠그지 않는다 (@DynamicUpdate 로 바꾼 칸만 쓴다)
        // 기준값(baseModifiedAt, DEC-08)을 보낸 행도 같은 순서로 잠그고 다시 읽어 비교한다 — 하나라도 다르면 아무것도 저장하지 않고 409
        // 지울 컬럼(M8-C2)도 같은 ID 순 잠금에 넣고 화면이 읽은 수정 일시와 비교한다 — 그 사이 다른 사용자가 고친 컬럼을 예전 화면이 지우지 않는다
        Map<Long, java.time.LocalDateTime> deletedBases = request.getDeletedBaseModifiedAt() == null ? Map.of() : request.getDeletedBaseModifiedAt();
        java.util.stream.Stream.concat(
                        rows.stream().filter(row -> row.getId() != null && (row.getBizDescription() != null || row.getBaseModifiedAt() != null)).map(ColumnBulkSaveDto.Row::getId),
                        deletedIds.stream().filter(deletedBases::containsKey))
                .filter(existing::containsKey).distinct().sorted()
                .forEach(id -> entityManager.refresh(existing.get(id), LockModeType.PESSIMISTIC_WRITE));
        rows.stream().filter(row -> row.getId() != null && row.getBaseModifiedAt() != null && existing.containsKey(row.getId()))
                .forEach(row -> com.lexorma.common.jpa.ModelBase.verify(existing.get(row.getId()), row.getBaseModifiedAt(), existing.get(row.getId()).getColumnName()));
        deletedIds.stream().filter(id -> existing.containsKey(id) && deletedBases.get(id) != null).sorted()
                .forEach(id -> com.lexorma.common.jpa.ModelBase.verify(existing.get(id), deletedBases.get(id), existing.get(id).getColumnName()));
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
                } else if (Boolean.TRUE.equals(row.getUnchanged())) {
                    // 바꾸지 않은 행 (M8-D04) : 값을 보지 않는다 — 그 사이 다른 사용자가 고쳤어도 이 저장과 충돌하지 않고 그 값이 남는다
                } else if (!Boolean.TRUE.equals(column.getIsApproval())) {
                    validateRowFields(row, column.getColumnName(), errors);
                    if (BizDescriptions.conflict(column.getBizDescription(), row.getBizDescription(), row.getBizDescriptionBase())) {
                        errors.add(new ColumnBulkSaveDto.RowError(key, Messages.get("{0} : 다른 사용자가 업무 설명을 수정했습니다. 화면을 새로 열어 확인하세요.", column.getColumnName())));
                    } else if (BizDescriptions.tooLong(row.getBizDescription(), column.getBizDescription())) {
                        errors.add(new ColumnBulkSaveDto.RowError(key, BizDescriptions.tooLongRowMessage(column.getColumnName())));
                    }
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
            // 컬럼명 = 용어 약어 : 비어 있으면 입력 오류 (M7-U05, 중복 오류가 아니다)
            if (ColumnNameKey.isBlankName(columnName)) {
                errors.add(new ColumnBulkSaveDto.RowError(key, Messages.get("표준용어 {0} 에 영문약어가 없어 컬럼명을 만들 수 없습니다.", term.getCommonStandardTermName())));
                continue;
            }
            if (!columnNames.add(ColumnNameKey.of(columnName))) {
                errors.add(new ColumnBulkSaveDto.RowError(key, Messages.get("{0} : 이 테이블에 이미 같은 컬럼명이 있습니다.", columnName)));
                continue;
            }
            validateRowFields(row, columnName, errors);
            if (BizDescriptions.tooLong(row.getBizDescription(), null)) errors.add(new ColumnBulkSaveDto.RowError(key, BizDescriptions.tooLongRowMessage(columnName)));
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
        // 지운 컬럼의 이름을 새 컬럼이 다시 쓰는 저장(X 삭제 + X 추가)은 예전처럼 허용한다. Hibernate 는 커밋 때 INSERT 를 DELETE 보다 먼저 보내므로
        // UNIQUE(UX_COL_01) 에 걸리지 않게 삭제를 먼저 내보낸다 — 삭제가 있고 새 컬럼도 있을 때 한 번만. 삭제 가능 여부(승인·관계·인덱스·제약조건)는 위에서 이미 확인했고
        // 이 뒤의 등록이 실패하면 삭제도 함께 롤백된다 (한 트랜잭션)
        if (!deletedIds.isEmpty() && rows.stream().anyMatch(row -> row.getId() == null)) {
            entityManager.flush();
        }

        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();
        int created = 0;
        int updated = 0;
        int order = 0;
        // 요청에 없는 기존 컬럼 (M8-C4b P-05) : 편집기를 연 뒤 다른 사용자가 더한 컬럼이다 (편집기는 순서를 바꾸는 저장이면 기존 행 전부를 보낸다 — M8-D04).
        // 그 컬럼의 값·순서는 건드리지 않고, 이 저장이 매기는 번호가 그 컬럼의 번호와 겹치지 않게 비켜 간다 (예전에는 1..n 으로 매겨 같은 순서가 둘 생겼다).
        // 요청에 없는 컬럼이 없으면 번호는 예전과 같다. 번호를 연속으로 맞추거나 기존 겹침을 정리하지는 않는다
        Set<Long> inRequest = rows.stream().map(ColumnBulkSaveDto.Row::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> heldOrders = existing.values().stream()
                .filter(column -> !inRequest.contains(column.getId()) && !deletedIds.contains(column.getId()) && column.getSortOrder() != null)
                .map(ColumnInfo::getSortOrder).collect(Collectors.toSet());

        for (ColumnBulkSaveDto.Row row : rows) {
            if (row.getId() != null) {
                ColumnInfo column = existing.get(row.getId());

                if (Boolean.TRUE.equals(row.getUnchanged())) {
                    // 바꾸지 않은 행 (M8-D04) : 값·순서 모두 쓰지 않는다 (비어 있는 순서도 채우지 않는다). 뒤의 컬럼은 이 컬럼 다음 순서부터 매긴다
                    if (column.getSortOrder() != null) order = Math.max(order, column.getSortOrder());
                    continue;
                }
                if (Boolean.TRUE.equals(column.getIsApproval())) {
                    // 승인된 컬럼은 정해진 순서를 바꾸지 않는다. 뒤의 컬럼은 이 컬럼 다음 순서부터 매긴다.
                    // 단, 순서가 비어 있는 컬럼(예전 데이터)은 화면 순서대로 채운다. 비워 두면 목록 끝으로 밀려
                    // 새로 추가한 컬럼이 승인 컬럼보다 앞에 보인다. (값을 바꾸는 것이 아니라 없는 값을 채우는 것)
                    if (column.getSortOrder() == null) {
                        order = nextOrder(order, heldOrders);
                        column.setSortOrder(order);
                    } else {
                        order = Math.max(order, column.getSortOrder());
                    }
                    continue;
                }

                order = nextOrder(order, heldOrders);
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
            order = nextOrder(order, heldOrders);
            applyRow(column, row, order);

            columnInfoRepository.save(column);
            tableInfo.getColumns().add(column);
            if (term.getColumnInfos() != null) term.getColumnInfos().add(column);
            created++;
        }

        // DB UNIQUE(UX_COL_01) 최종 방어 (위 검사와 같은 기준이라 보통은 걸리지 않는다) : 걸리면 같은 중복 오류, 저장 전체 롤백
        ColumnNameKey.flush(entityManager, () -> new CustomException(ErrorCode.SAVED_DATA_EXISTS, tableInfo.getTableName()));
        physicalModelService.syncReferenceRelations(projectId, List.of(tableInfo.getId()));
        return new ColumnBulkSaveDto.Result(true, created, updated, deletedIds.size(), List.of());
    }

    /**
     * PK 컬럼 삭제(구조 변경, M7-FK-D11)의 잠금 — 테이블 이름 변경·물리명 일괄 적용과 같은 규칙 (physical/sync/ModelRowLocks) : 프로젝트 행 배타를 <b>맨 먼저</b> 잡고,
     * 그 뒤에는 어떤 행 잠금도 기다리지 않는다 (일반 저장은 행을 쥔 채 커밋 직전에 프로젝트 행을 공유 잠금으로 확인하므로, 기다리면 서로 기다린다).
     * <ol>
     *   <li>테이블 행 NOWAIT (FOR NO KEY UPDATE)</li>
     *   <li>지울 컬럼 행 FOR UPDATE NOWAIT — DELETE 는 키 강도라 다른 트랜잭션의 FK 검사(KEY SHARE, 그 PK 를 참조하는 관계 INSERT)를 기다린다. 미리 잡아 바쁘면 409</li>
     *   <li>남는 컬럼 + 이 테이블들을 참조 테이블명으로 가리키는 컬럼 NOWAIT — 이 저장이 고치거나, 관계 동기화가 그 컬럼의 관계를 지우거나 새로 만들(KEY SHARE) 행</li>
     * </ol>
     * 이 뒤 관계 동기화가 새 참조 관계로 거는 KEY SHARE 는 대상 테이블 행과 대상 PK 컬럼 행뿐이다. 그 행을 키 강도로 잡는 쓰기(PK 컬럼 삭제·이름 변경·스키마 이동·
     * 원본 삭제 반영)는 모두 프로젝트 배타를 먼저 잡으므로 여기서 기다릴 상대가 없다. 잠근 테이블 엔티티를 돌려준다 (ID 순)
     */
    /**
     * 일괄 저장이 실제 PK 구성을 바꾸는가 (잠그기 전 — DB 의 저장 상태를 스칼라로 읽는다, 영속성 컨텍스트에 옛 상태를 남기지 않는다) (M8-C4b-R2 R2-D01).
     * 바꾸는 것 : 지금 PK 인 컬럼 삭제 / 기존 행의 PK 여부가 지금 값과 다름 / PK 여부 Y 인 새 행. 바꾸지 않는 것 : unchanged 행, 승인된 컬럼의 행(값을 쓰지 않는다),
     * 요청에 없는 기존 컬럼, 다른 테이블·없는 컬럼의 행(행 오류가 된다). PK 순서는 PK 구성에 넣지 않는다 (FK 가 참조하는 키는 컬럼 집합 — TableDdlGenerator.referencesKey)
     */
    private boolean changesPrimaryKey(Long projectId, Long tableId, List<ColumnBulkSaveDto.Row> rows, Set<Long> deletes) {
        // PK 컬럼 삭제 (D11 의 기존 판정 그대로 — ID 만 읽는 스칼라 조회)
        if (!deletes.isEmpty() && !columnInfoRepository.findPkColumnIds(projectId, deletes).isEmpty()) return true;
        Map<Long, Object[]> current = new HashMap<>();
        for (Object[] row : columnInfoRepository.findPkStates(projectId, tableId)) {
            current.put((Long) row[0], row);
        }
        for (ColumnBulkSaveDto.Row row : rows == null ? List.<ColumnBulkSaveDto.Row>of() : rows) {
            if (row.getId() == null) {
                if (isY(row.getIsPk())) return true;
                continue;
            }
            Object[] now = current.get(row.getId());
            if (now == null || Boolean.TRUE.equals(row.getUnchanged()) || Boolean.TRUE.equals(now[2]) || deletes.contains(row.getId())) continue;
            if (isY(row.getIsPk()) != Boolean.TRUE.equals(now[1])) return true;
        }
        return false;
    }

    /** 같은 판정을 잠근 뒤 다시 읽은 컬럼으로 (잠그기 전 판정과 달라졌는지 보려고) */
    private static boolean changesPrimaryKey(Map<Long, ColumnInfo> existing, List<ColumnBulkSaveDto.Row> rows, Set<Long> deletes) {
        for (Long id : deletes) {
            ColumnInfo column = existing.get(id);
            if (column != null && Boolean.TRUE.equals(column.getIsPk())) return true;
        }
        for (ColumnBulkSaveDto.Row row : rows == null ? List.<ColumnBulkSaveDto.Row>of() : rows) {
            if (row.getId() == null) {
                if (isY(row.getIsPk())) return true;
                continue;
            }
            ColumnInfo column = existing.get(row.getId());
            if (column == null || Boolean.TRUE.equals(row.getUnchanged()) || Boolean.TRUE.equals(column.getIsApproval()) || deletes.contains(row.getId())) continue;
            if (isY(row.getIsPk()) != Boolean.TRUE.equals(column.getIsPk())) return true;
        }
        return false;
    }

    private List<TableInfo> lockForKeyChange(Long projectId, java.util.Collection<Long> tableIds, java.util.Collection<Long> deletedIds, java.util.Collection<Long> ownColumnIds) {
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        projectWriteGuard.lock(projectId);
        List<TableInfo> tables = rowLocks.tablesNoWait(tableIds).stream().filter(t -> projectId.equals(t.getProjectId())).toList();
        // PK 구성이 바뀌는 테이블 행은 키 강도로도 잡는다 (M8-C4b-R2) : 다른 저장의 관계 동기화는 이 테이블을 참조하는 관계를 계산하기 전에 이 행을 KEY SHARE 로 잡으므로
        // (PhysicalModelServiceImpl.syncReferenceRelations), 이쪽이 먼저면 그쪽이 커밋을 기다렸다가 바뀐 PK 로 계산하고, 그쪽이 먼저면 이쪽이 기다리지 않고 409 다
        rowLocks.tablesForKeyChange(tables.stream().map(TableInfo::getId).toList());
        rowLocks.columnsForKeyChange(deletedIds);
        // 이 저장이 고칠 수 있는 소속 컬럼은 읽기 전에 잠근다 (잠근 뒤 읽은 상태로 판정·저장)
        Set<Long> own = new java.util.TreeSet<>(ownColumnIds);
        own.removeAll(deletedIds);
        rowLocks.columnsNoWait(own);
        Set<String> names = tables.stream().flatMap(t -> TableNameResolver.namesOf(t).stream()).collect(Collectors.toSet());
        if (!names.isEmpty()) {
            Set<Long> referencing = new java.util.TreeSet<>();
            columnInfoRepository.findAllReferencing(projectId, names).forEach(c -> referencing.add(c.getId()));
            referencing.removeAll(deletedIds);
            referencing.removeAll(own);
            rowLocks.columnsNoWait(referencing);
        }
        return tables;
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
    /** 다음 순서 번호 : 요청에 없는 기존 컬럼(다른 사용자가 더한 컬럼)이 쓰는 번호는 건너뛴다 (M8-C4b P-05) */
    private static int nextOrder(int order, Set<Integer> held) {
        int next = order + 1;
        while (held.contains(next)) next++;
        return next;
    }

    private static boolean applyRow(ColumnInfo column, ColumnBulkSaveDto.Row row, int sortOrder) {
        boolean pk = isY(row.getIsPk());
        List<Object> before = snapshot(column);

        column.setColumnDesc(trimToNull(row.getColumnDesc()));
        if (row.getBizDescription() != null) column.setBizDescription(BizDescriptions.normalize(row.getBizDescription()));
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
        return java.util.Arrays.asList(c.getColumnDesc(), c.getBizDescription(), c.getDataType(), c.getMaxLength(),
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
