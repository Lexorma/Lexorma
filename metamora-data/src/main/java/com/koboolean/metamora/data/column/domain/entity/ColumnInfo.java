package com.koboolean.metamora.data.column.domain.entity;

import com.koboolean.metamora.data.dictionary.domain.entity.StandardTerm;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
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
