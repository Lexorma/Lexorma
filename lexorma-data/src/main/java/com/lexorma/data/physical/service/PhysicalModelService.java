package com.lexorma.data.physical.service;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.dto.TableDdlDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;

/**
 * 물리 모델의 관계·인덱스·제약조건 (M-1). 테이블·컬럼 자체는 기존 테이블/컬럼 관리 서비스가 그대로 관리한다.
 */
public interface PhysicalModelService {

    enum Kind { RELATION, INDEX, CONSTRAINT }

    PhysicalModelDto.TableModel selectTableModel(AccountDto accountDto, Long tableId);

    PhysicalModelDto.Relation saveRelation(AccountDto accountDto, PhysicalModelDto.Relation relation);

    /** FK 의 삭제·수정 규칙만 바꾼다 (참조 컬럼 관계 포함, 승인된 관계는 거절) */
    PhysicalModelDto.Relation updateRelationRules(AccountDto accountDto, Long relationId, PhysicalModelDto.RelationRules rules);

    PhysicalModelDto.Index saveIndex(AccountDto accountDto, PhysicalModelDto.Index index);

    /** 인덱스 수정 : 미승인은 바로, 승인된 인덱스는 변경 요청 (승인 권한자는 즉시 승인) — M-14 D-20 */
    PhysicalModelDto.IndexSaveResult updateIndex(AccountDto accountDto, Long id, PhysicalModelDto.Index index);

    /** 인덱스 변경 요청 승인(반영) / 반려 (승인 권한자만) */
    PhysicalModelDto.ChangeRequest processIndexChangeRequest(AccountDto accountDto, Long requestId, boolean approve);

    /** 인덱스 관리 목록 (프로젝트 범위, 스키마·테이블·승인·출처 조건, Grid 검색 indexName / tableName / columnNames) */
    Page<PhysicalModelDto.IndexRow> selectIndexes(AccountDto accountDto, Pageable pageable, String searchColumn, String searchQuery,
                                                  Long schemaId, Long tableId, String isApproval, String indexSource);

    PhysicalModelDto.IndexDetail selectIndex(AccountDto accountDto, Long id);

    TableDdlDto indexDdl(AccountDto accountDto, Long id, String defaultSchemaName);

    PhysicalModelDto.IndexTypes indexTypes(AccountDto accountDto);

    PhysicalModelDto.Constraint saveConstraint(AccountDto accountDto, PhysicalModelDto.Constraint constraint);

    void delete(AccountDto accountDto, Kind kind, Long id);

    void updateApproval(AccountDto accountDto, Kind kind, Long id, boolean isApproval);

    /**
     * 컬럼 삭제 전에 호출 : 사용자가 정의한 관계·인덱스·제약조건에 쓰인 컬럼이면 삭제를 막고,
     * 참조 테이블명에서 만든 관계(REF_COLUMN)와 인덱스 여부에서 만든 인덱스(COLUMN)는 지운다. (삭제 후 {@link #syncReferenceRelations} 로 다시 맞춘다)
     */
    void releaseColumns(Long projectId, Collection<ColumnInfo> columns);

    /**
     * 컬럼의 참조 테이블명(refTableName)을 관계(REF_COLUMN)로, tableIds 컬럼의 인덱스 여부(isIndex)를 COLUMN 인덱스로 맞춘다.
     * 관계는 tableIds 의 컬럼이 원본이거나 tableIds 를 참조하는 컬럼이 대상.
     * 컬럼을 저장·삭제한 뒤 호출한다 (참조 대상 PK 가 바뀌어도 반영).
     */
    void syncReferenceRelations(Long projectId, Collection<Long> tableIds);

    /** DDL 생성용 : 이 테이블의 UNIQUE·CHECK 제약, 인덱스(컬럼 인덱스 포함), 사용자 정의 관계(REF_COLUMN 제외) */
    TableDdlGenerator.Extras ddlExtras(TableInfo tableInfo);
}
