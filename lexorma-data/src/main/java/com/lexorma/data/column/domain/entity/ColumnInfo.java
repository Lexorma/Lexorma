package com.lexorma.data.column.domain.entity;

import com.lexorma.data.physical.sync.ReferenceSyncListener;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@EntityListeners(ReferenceSyncListener.class) // 저장·수정·삭제 시 참조 관계 동기화 대상으로 표시
@Table(name = "TB_COL")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class ColumnInfo extends BaseEntity {

    @Id
    // 시퀀스(pooled, 50개씩 할당) : IDENTITY 는 JDBC 배치 INSERT 가 꺼지므로 사용하지 않는다 (V6 마이그레이션)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_COL")
    @SequenceGenerator(name = "SQ_COL", sequenceName = "SQ_COL", allocationSize = 50)
    @Column(name = "COL_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN")
    private Long projectId;

    /** 테이블명: ex_table */
    @Column(name = "TBL_NM")
    private String tableName;

    /** 컬럼명: email */
    @Column(name = "COL_NM")
    private String columnName;

    /** 컬럼 설명: 이메일 */
    @Column(name = "COL_EXPLN")
    private String columnDesc;

    /** 원본 COMMENT (M-4e 가져오기, V3). 원본 DB 에서 가져온 설명이며 업무 설명(columnDesc)과 따로 둔다 */
    @Column(name = "SRC_ANTN_CN", columnDefinition = "TEXT")
    private String sourceComment;

    /** 데이터 타입: VARCHAR2 */
    @Column(name = "DATA_TYPE_NM")
    private String dataType;

    /** 최대 길이: nan */
    @Column(name = "MAX_LEN")
    private Integer maxLength;

    /** 정밀도: nan */
    @Column(name = "DATA_PRCSN")
    private BigDecimal precision;

    /** 소수 자릿수: nan */
    @Column(name = "DCPT_LEN")
    private Integer scale;

    /** NULL 허용 여부: N */
    @Column(name = "NULL_ALWNC_YN")
    private Boolean isNullable;

    /** 기본값: nan */
    @Column(name = "BSC_VL")
    private String defaultValue;

    /**
     * 자동 생성 방법 (IDENTITY) : ALWAYS · BY_DEFAULT, null = 없음. 기본값·SERIAL 타입과 함께 쓰지 않는다.
     * DDL : PostgreSQL·Oracle GENERATED … AS IDENTITY, MySQL·MariaDB AUTO_INCREMENT
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "AUTO_CRT_MTHD_CD", length = 20)
    private Identity identity;

    public enum Identity {
        ALWAYS("ALWAYS"), BY_DEFAULT("BY DEFAULT");

        private final String sql;

        Identity(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }

        /** "BY DEFAULT"·"by_default"·"ALWAYS". 비었거나 모르면 null */
        public static Identity parse(String value) {
            if (value == null || value.isBlank()) return null;
            String key = value.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
            for (Identity identity : values()) if (identity.name().equals(key)) return identity;
            return null;
        }
    }

    /** 정렬 순서: 1 */
    @Column(name = "SORT_ORD")
    private Integer sortOrder;

    /** 마스터 데이터 여부: N */
    @Column(name = "MSTR_DATA_YN")
    private Boolean isMasterData;

    /** 참조 테이블명: nan */
    @Column(name = "RFRNC_TBL_NM")
    private String refTableName;

    /** 필수 입력 여부: Y */
    @Column(name = "ESNTL_YN")
    private Boolean isRequired;

    /** 민감정보 여부: Y */
    @Column(name = "SNSTV_INFO_YN")
    private Boolean isSensitive;

    /** PK 여부: Y */
    @Column(name = "PK_YN")
    private Boolean isPk;

    /** 고유값 여부: Y */
    @Column(name = "UNQ_YN")
    private Boolean isUnique;

    /** 인덱스 생성 여부: Y */
    @Column(name = "IDX_YN")
    private Boolean isIndex;

    /** 암호화 필요 여부: N */
    @Column(name = "ENCPT_YN")
    private Boolean isEncrypted;

    /** 데이터 예시: test@test.com */
    @Column(name = "EXMPL_CN")
    private String example;

    /** 승인여부 : N **/
    @Column(name = "APRV_YN")
    private Boolean isApproval;

    @ManyToOne
    @JoinColumn(name = "STD_TRM_SN")
    private StandardTerm standardTerms;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TBL_SN")
    private TableInfo tableInfo;

}
