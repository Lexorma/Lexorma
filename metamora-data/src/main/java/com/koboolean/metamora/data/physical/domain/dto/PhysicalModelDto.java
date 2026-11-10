package com.koboolean.metamora.data.physical.domain.dto;

import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.physical.domain.entity.TableConstraint;
import com.koboolean.metamora.data.physical.domain.entity.TableIndex;
import com.koboolean.metamora.data.physical.domain.entity.TableRelation;

import java.util.List;

/** 물리 모델(관계·인덱스·제약조건) 조회·등록 DTO. 등록 요청은 id·이름(…Name) 없이 보낸다. */
public final class PhysicalModelDto {

    private PhysicalModelDto() {
    }

    public record ColumnRef(Long columnId, String columnName) {
        static ColumnRef of(ColumnInfo column) {
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

    public record Index(Long id, Long tableId, String indexName, String indexType, Boolean isUnique,
                        List<ColumnRef> columns, Boolean isApproval) {
        public static Index fromEntity(TableIndex i) {
            return new Index(i.getId(), i.getTableInfo().getId(), i.getIndexName(), i.getIndexType(), i.getIsUnique(),
                    i.getColumns().stream().map(c -> ColumnRef.of(c.getColumn())).toList(), i.getIsApproval());
        }
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
