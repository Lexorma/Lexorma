package com.lexorma.data.table.service;

import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.domain.dto.TableDdlDto;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.dto.TableNameDto;
import com.lexorma.core.security.domain.dto.AccountDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface TableDesignService {
    /** schemaId : 스키마별 조회 (없으면 전체) */
    Page<TableInfoDto> selectTableInfoData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery, Long schemaId);

    /** 테이블 1건 (다른 화면에서 테이블 ID 로 상세를 열 때) */
    TableInfoDto selectTable(Long tableId, AccountDto accountDto);

    /**
     * 테이블 한글명(예: 사용_내역_관리)을 승인된 표준 단어의 영문약어로 변환한다.
     */
    TableNameDto generateTableName(String tableKorName, AccountDto accountDto);

    /**
     * 여러 한글명을 같은 규칙({@link #generateTableName})으로 변환한다. 표준 단어는 한 번에 조회한다 (물리명 추천 목록용).
     *
     * @return 입력한 한글명 → 변환 결과 (입력 순서)
     */
    java.util.Map<String, TableNameDto> generateTableNames(java.util.Collection<String> tableKorNames, AccountDto accountDto);

    void saveTable(TableInfoDto tableInfoDto, AccountDto accountDto);

    void updateTable(TableInfoDto tableInfoDto, AccountDto accountDto);

    void updateTableIsApproval(boolean isApproval, AccountDto accountDto, List<TableInfoDto> tableInfoDtos);

    void deleteTable(List<TableInfoDto> tableInfoDtos, AccountDto accountDto);

    void uploadTableExcelFile(MultipartFile file, AccountDto accountDto) throws IOException;

    /**
     * 테이블과 연결된 컬럼으로 프로젝트 대상 DB 기준 DDL 을 만든다.
     */
    /** defaultSchemaName : 기본 스키마 테이블에 붙일 스키마명 (비우면 붙이지 않는다) */
    TableDdlDto generateTableDdl(Long tableId, AccountDto accountDto, String defaultSchemaName);

    /**
     * DDL 생성용 : 이 테이블 컬럼의 참조 테이블명(적은 그대로, 대문자) → 참조 테이블의 PK 컬럼(정렬 순서). 이름은 이 테이블의 스키마 기준으로 정한다 (TableNameResolver).
     * 통합 DDL Export(M-11)도 같은 기준을 쓴다.
     * @param ambiguous 여러 스키마에 있어 정할 수 없는 참조 테이블명
     */
    record ReferencedKeys(Map<String, List<ColumnInfo>> pkColumns, Set<String> ambiguous) {}

    ReferencedKeys referencedKeys(Long projectId, TableInfo table);
}
