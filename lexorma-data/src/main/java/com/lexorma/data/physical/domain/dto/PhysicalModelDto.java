package com.lexorma.data.physical.domain.dto;

import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;

import java.time.LocalDateTime;
import java.util.List;

/** 물리 모델(관계·인덱스·제약조건) 조회·등록 DTO. 등록 요청은 id·이름(…Name) 없이 보낸다. */
public final class PhysicalModelDto {

    private PhysicalModelDto() {
    }

    public record ColumnRef(Long columnId, String columnName) {
        public static ColumnRef of(ColumnInfo column) {
            return new ColumnRef(column.getId(), column.getColumnName());
        }
    }

    public record ColumnPair(Long sourceColumnId, String sourceColumnName, Long targetColumnId, String targetColumnName) {
    }

    /** relationType : FK(기본) / LOGICAL, relationSource : 조회 전용 (등록은 항상 USER) */
    public record Relation(Long id, String relationName, String relationType, String relationSource,
                           Long sourceTableId, String sourceTableName, Long targetTableId, String targetTableName,
                           List<ColumnPair> columns, Boolean isApproval) {
        public static Relation fromEntity(TableRelation r) {
            return new Relation(r.getId(), r.getRelationName(), r.getRelationType().name(), r.getRelationSource().name(),
                    r.getSourceTable().getId(), r.getSourceTable().getTableName(),
                    r.getTargetTable().getId(), r.getTargetTable().getTableName(),
                    r.getColumns().stream().map(c -> new ColumnPair(c.getSourceColumn().getId(), c.getSourceColumn().getColumnName(),
                            c.getTargetColumn().getId(), c.getTargetColumn().getColumnName())).toList(),
                    r.getIsApproval());
        }
    }

    /**
     * 인덱스 검색 키 하나 (M-14 2차) : columnId(컬럼) 또는 expression(SQL 표현식) 중 하나.
     * direction ASC(기본·null) / DESC, nulls null(방향의 기본 : ASC 는 LAST, DESC 는 FIRST) / FIRST / LAST
     */
    public record Member(Long columnId, String columnName, String expression, String direction, String nulls) {
        public static Member of(TableIndexColumn c) {
            String sort = c.getSort();
            boolean desc = sort != null && sort.startsWith("DESC");
            String nulls = "NULLS FIRST".equals(sort) ? "FIRST" : "DESC NULLS LAST".equals(sort) ? "LAST" : null;
            return new Member(c.getColumn() == null ? null : c.getColumn().getId(), c.getColumn() == null ? null : c.getColumn().getColumnName(),
                    c.getExpression(), desc ? "DESC" : "ASC", nulls);
        }

        /** 저장 표기 (AI-2b 와 같다) : 방향·NULLS 가 PostgreSQL 기본이면 null */
        public String sortCode() {
            boolean desc = "DESC".equalsIgnoreCase(direction);
            boolean first = "FIRST".equalsIgnoreCase(nulls);
            boolean last = "LAST".equalsIgnoreCase(nulls);
            if (desc) return last ? "DESC NULLS LAST" : "DESC";
            return first ? "NULLS FIRST" : null;
        }
    }

    /**
     * indexSource : 조회 전용 USER / COLUMN (컬럼의 인덱스 여부에서 만든 인덱스, 등록·수정 요청에서는 무시).
     * 검색 키는 members (M-14 2차, 표현식·정렬 포함). 요청에 members 가 없으면 columns 를 오름차순 컬럼 키로 쓴다 (예전 요청 호환).
     * 응답의 columns 는 키 중 컬럼만 (예전 화면 호환) — 전체 구성은 members·includeColumns·condition 을 본다.
     */
    public record Index(Long id, Long tableId, String indexName, String indexType, Boolean isUnique,
                        List<ColumnRef> columns, Boolean isApproval, String indexSource,
                        List<Member> members, List<ColumnRef> includeColumns, String condition) {
        public Index(Long id, Long tableId, String indexName, String indexType, Boolean isUnique, List<ColumnRef> columns, Boolean isApproval) {
            this(id, tableId, indexName, indexType, isUnique, columns, isApproval, null, null, null, null);
        }

        /** 요청의 검색 키 : members, 없으면 columns (오름차순) */
        public List<Member> keyMembers() {
            if (members != null && !members.isEmpty()) return members;
            return columns == null ? List.of() : columns.stream().map(c -> c == null ? new Member(null, null, null, null, null)
                    : new Member(c.columnId(), c.columnName(), null, null, null)).toList();
        }

        public static Index fromEntity(TableIndex i) {
            List<TableIndexColumn> keys = i.keyMembers();
            return new Index(i.getId(), i.getTableInfo().getId(), i.getIndexName(), i.getIndexType(), i.getIsUnique(),
                    keys.stream().filter(c -> c.getColumn() != null).map(c -> ColumnRef.of(c.getColumn())).toList(), i.getIsApproval(), i.getIndexSource().name(),
                    keys.stream().map(Member::of).toList(), i.includeMembers().stream().map(c -> ColumnRef.of(c.getColumn())).toList(), i.getCondition());
        }
    }

    /**
     * 변경 요청에 보관하는 인덱스 정의 (변경 전·후). M-14 1차에 저장한 요청은 members·includeColumns·condition 이 없다 → columns 가 오름차순 키
     */
    public record IndexDefinition(String indexName, String indexType, Boolean isUnique, List<ColumnRef> columns,
                                  List<Member> members, List<ColumnRef> includeColumns, String condition) {
        public static IndexDefinition of(TableIndex i) {
            Index dto = Index.fromEntity(i);
            return new IndexDefinition(dto.indexName(), dto.indexType(), dto.isUnique(), dto.columns(), dto.members(), dto.includeColumns(), dto.condition());
        }

        /** 수정 요청 형태로 (변경 요청을 승인할 때 등록과 같은 검증을 다시 거친다) */
        public Index toRequest() {
            return new Index(null, null, indexName, indexType, isUnique, columns, null, null, members, includeColumns, condition);
        }
    }

    /** 인덱스 변경 요청. 요청자·일시 = requestedBy·requestedAt, 승인·반려한 사람·일시 = processedBy·processedAt (REQUESTED 면 null) */
    public record ChangeRequest(Long id, Long indexId, String status, IndexDefinition before, IndexDefinition after,
                                String requestedBy, LocalDateTime requestedAt, String processedBy, LocalDateTime processedAt) {
    }

    /** 인덱스 수정 결과 : applied = 바로 반영됨 (미승인 인덱스, 또는 승인 권한자의 요청이 즉시 승인됨) / 아니면 request 가 승인 대기 */
    public record IndexSaveResult(Index index, boolean applied, ChangeRequest request) {
    }

    /** 인덱스 관리 목록 한 행 (Grid : Y/N 문자열. columnNames = 키(표현식은 괄호, 정렬) + INCLUDE, condition = WHERE 조건) */
    public record IndexRow(Long id, Long schemaId, String schemaName, Long tableId, String tableName, String indexName,
                           String indexType, String isUnique, String columnNames, String condition, String indexSource, String isApproval,
                           String pendingRequest, LocalDateTime created, LocalDateTime updated, String createdBy, String updatedBy) {
    }

    /** 프로젝트 DBMS 에서 DDL 로 낼 수 있는 인덱스 유형 (비우면 DBMS 기본) */
    public record IndexTypes(String dbType, String dbLabel, List<String> types) {
    }

    /**
     * 인덱스 상세 : 인덱스 + 소속 + 변경 요청 (최근 것부터).
     * replacedColumnIndexes : 사용자 UNIQUE 단일 컬럼 인덱스와 같은 컬럼의 컬럼 인덱스 이름 — 미승인이면 승인할 때 대신(삭제)될 인덱스,
     * 승인된 UNIQUE 인데 남아 있으면 V3 이전부터 공존하던 정리 대상 (D-19 방안 C)
     */
    public record IndexDetail(Index index, Long schemaId, String schemaName, String tableName,
                              LocalDateTime created, LocalDateTime updated, String createdBy, String updatedBy,
                              List<ChangeRequest> requests, List<String> replacedColumnIndexes) {
    }

    /** constraintType : UNIQUE(columns 필수) / CHECK(checkExpression 필수) */
    public record Constraint(Long id, Long tableId, String constraintName, String constraintType, String checkExpression,
                             List<ColumnRef> columns, Boolean isApproval) {
        public static Constraint fromEntity(TableConstraint c) {
            return new Constraint(c.getId(), c.getTableInfo().getId(), c.getConstraintName(), c.getConstraintType().name(),
                    c.getCheckExpression(), c.getColumns().stream().map(col -> ColumnRef.of(col.getColumn())).toList(), c.getIsApproval());
        }
    }

    /**
     * 테이블 1개의 물리 모델. unresolvedReferences : 참조 테이블명은 있지만 관계로 확정하지 못한 컬럼
     * (참조 테이블 없음·후보 여러 개·PK 없음·복합 PK·자기 PK) - "컬럼명 → 참조 테이블명"
     */
    public record TableModel(Long tableId, String tableName, Long schemaId, String schemaName,
                             List<Relation> outgoingRelations, List<Relation> incomingRelations,
                             List<Index> indexes, List<Constraint> constraints, List<String> unresolvedReferences) {
    }
}
