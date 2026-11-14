package com.lexorma.data.physical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 내부 물리 모델의 테이블 관계 (원본 → 대상, 컬럼 쌍은 {@link TableRelationColumn} 에 순서대로).
 * 원본 DB 에서 수집한 실제 FK 는 원본 카탈로그(AI-1·AI-2)에 따로 저장하며 여기에 섞지 않는다.
 * AI 추정 관계도 승인 전에는 여기에 저장하지 않는다 (승인되면 source = AI_APPROVED).
 */
@Entity
@Table(name = "TB_TBL_REL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableRelation extends BaseEntity {

    /** FK : DDL 에 FOREIGN KEY 를 만든다 / LOGICAL : 문서·ERD 용 관계 (DDL 제약 없음) */
    public enum Type { FK, LOGICAL }

    /**
     * 관계 출처
     * - USER : 관계 API 로 사용자가 정의
     * - REF_COLUMN : 컬럼의 참조 테이블명(refTableName, 단일 FK 약식 입력)에서 자동으로 만든 관계. 컬럼 저장 때 다시 맞춘다.
     * - IMPORTED : 원본 카탈로그에서 가져온 관계 (M-4, 미구현)
     * - AI_APPROVED : AI 추정 관계를 사용자가 승인 (AI-12, 미구현)
     */
    public enum Source { USER, REF_COLUMN, IMPORTED, AI_APPROVED }

    /**
     * FK 삭제·수정 규칙 (ON DELETE / ON UPDATE). null = NO_ACTION (DBMS 기본, DDL 에 쓰지 않는다).
     * DBMS 가 지원하지 않는 규칙(Oracle 의 ON UPDATE·RESTRICT·SET DEFAULT, MySQL·MariaDB 의 SET DEFAULT)은 저장은 하고 DDL 에서 빼며 경고한다
     */
    public enum Rule {
        NO_ACTION("NO ACTION"), RESTRICT("RESTRICT"), CASCADE("CASCADE"), SET_NULL("SET NULL"), SET_DEFAULT("SET DEFAULT");

        private final String sql;

        Rule(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }

        /** "SET NULL"·"set_null"·"CASCADE" 등. 비었거나 모르면 null */
        public static Rule parse(String value) {
            if (value == null || value.isBlank()) return null;
            String key = value.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
            for (Rule rule : values()) if (rule.name().equals(key)) return rule;
            return null;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL_REL")
    @SequenceGenerator(name = "SQ_TBL_REL", sequenceName = "SQ_TBL_REL", allocationSize = 50)
    @Column(name = "REL_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ORGNL_TBL_SN", nullable = false)
    private TableInfo sourceTable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TRGT_TBL_SN", nullable = false)
    private TableInfo targetTable;

    @Column(name = "REL_NM")
    private String relationName;

    @Enumerated(EnumType.STRING)
    @Column(name = "REL_TYPE_CD", length = 20, nullable = false)
    private Type relationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "REL_SRC_CD", length = 20, nullable = false)
    private Source relationSource;

    /** REF_COLUMN 관계를 만든 컬럼 ID (컬럼당 관계 1개) */
    @Column(name = "DRVN_COL_SN")
    private Long derivedColumnId;

    @Column(name = "APRV_YN")
    private Boolean isApproval;

    /** ON DELETE 규칙 (null = NO ACTION) */
    @Enumerated(EnumType.STRING)
    @Column(name = "DEL_RULE_CD", length = 20)
    private Rule deleteRule;

    /** ON UPDATE 규칙 (null = NO ACTION) */
    @Enumerated(EnumType.STRING)
    @Column(name = "MDFCN_RULE_CD", length = 20)
    private Rule updateRule;

    @Builder.Default
    @OneToMany(mappedBy = "relation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<TableRelationColumn> columns = new ArrayList<>();
}
