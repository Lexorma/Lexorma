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
    /** 정렬할 수 있는 화면 필드 = 화면(columns.ts)에 정렬 가능으로 정의한 열(숨긴 열 포함 — SmokeTest.gridSortAndSearch 계약) + 기본 정렬 열 (인덱스 등록·물리 ERD 의 tableName 포함) (PageableUtil 허용 목록 — 공통 페이징 안정성 개선) */
    java.util.Set<String> TABLE_SORT_FIELDS = java.util.Set.of("id", "schemaName", "tableName", "tableKorName", "tableDescription", "sortOrder");

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

    /**
     * 이 테이블을 이름으로 가리키는 다른 컬럼 (참조 테이블명이 이 테이블로 해석되는 컬럼 — {@link #updateTable} 이 이름을 바꿀 때 고치는 대상과 같다).
     * 물리명 추천 일괄 적용(M-7)이 이름을 바꾸기 전에 읽어 행을 잠근다
     */
    List<ColumnInfo> columnsReferencingTable(Long projectId, TableInfo table);

    /**
     * 잠근 테이블의 물리 테이블명만 바꾼다 (물리명 추천 일괄 적용 전용 내부 경로 — 검증·잠금·기준값은 부르는 쪽).
     * {@link #updateTable} 의 이름 변경과 같은 동기화를 한다 : 소속 컬럼의 테이블명 사본, referencing(바꾸기 전에 {@link #columnsReferencingTable} 로 읽은 것)의 참조 테이블명
     * (승인된 컬럼도 — 기존 동작, P-11 : 참조 이름 사본만 바꾸고 다른 속성은 건드리지 않는다). 한글명·설명·스키마·승인 상태는 그대로다 (P-02).
     * 참조 관계 동기화({@code PhysicalModelService.syncReferenceRelations})는 부르는 쪽이 바꾼 테이블 전체로 한 번 부른다
     */
    void renameLockedTable(TableInfo table, String newName, List<ColumnInfo> referencing);

    void updateTableIsApproval(boolean isApproval, AccountDto accountDto, List<TableInfoDto> tableInfoDtos);

    void deleteTable(List<TableInfoDto> tableInfoDtos, AccountDto accountDto);

    /** 삭제 확인 화면 (M8-C2) : 테이블마다 지금 기준값과 함께 지워질 인덱스·제약조건·관계 구성 */
    List<com.lexorma.common.jpa.ModelComposition.Deletion> selectTableDeletions(List<Long> tableIds, AccountDto accountDto);

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
