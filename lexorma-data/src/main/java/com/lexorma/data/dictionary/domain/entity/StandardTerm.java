package com.lexorma.data.dictionary.domain.entity;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "TB_STD_TRM")
public class StandardTerm extends BaseEntity {

    @Id
    // 시퀀스(pooled, 50개씩 할당) : IDENTITY 는 JDBC 배치 INSERT 가 꺼지므로 사용하지 않는다 (V6 마이그레이션)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_STD_TRM")
    @SequenceGenerator(name = "SQ_STD_TRM", sequenceName = "SQ_STD_TRM", allocationSize = 50)
    @Column(name = "STD_TRM_SN")
    private Long id; // 번호

    @Column(name = "PRJ_SN")
    private Long projectId;

    @Column(name = "ENCT_CYCL")
    private int revisionNumber; // 제정차수

    @Column(name = "TRM_NM")
    private String commonStandardTermName; // 표준용어명

    @Column(name = "TRM_EXPLN", length = 1000)
    private String commonStandardTermDescription; // 표준용어설명

    @OneToMany(mappedBy = "standardTerm", cascade = CascadeType.ALL)
    private List<StandardTermWordMapping> termWordMappings;

    @Column(name = "TRM_ENG_ABBR_NM")
    private String commonStandardTermAbbreviation;

    @Column(name = "PBADMS_STD_CD_NM")
    private String administrativeStandardCodeName; // 행정표준코드명

    @Column(name = "JRSDC_INST_NM")
    private String responsibleOrganization; // 소관기관명

    @ElementCollection
    @CollectionTable(name = "TB_STD_TRM_SYNM", joinColumns = @JoinColumn(name = "STD_TRM_SN"))
    @Column(name = "SYNM_NM")
    private List<String> synonymList; // 용어 이음동의어 목록

    @Column(name = "APRV_YN")
    private Boolean isApproval;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "STD_DMN_SN")
    private StandardDomain standardDomain;

    // 이 용어를 사용하는 컬럼 : ColumnInfo.standardTerms(FK) 기준 읽기 전용 (조인 테이블은 V6 에서 제거)
    @OneToMany(mappedBy = "standardTerms")
    private List<ColumnInfo> columnInfos;
}
