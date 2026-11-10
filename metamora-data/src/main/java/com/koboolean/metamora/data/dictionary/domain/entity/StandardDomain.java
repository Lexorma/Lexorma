package com.koboolean.metamora.data.dictionary.domain.entity;

import com.koboolean.metamora.data.dictionary.domain.dto.StandardDomainDto;
import com.koboolean.metamora.common.jpa.BaseEntity;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "TB_STD_DMN")
public class StandardDomain extends BaseEntity {

    @Id
    // 시퀀스(pooled, 50개씩 할당) : IDENTITY 는 JDBC 배치 INSERT 가 꺼지므로 사용하지 않는다 (V6 마이그레이션)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_STD_DMN")
    @SequenceGenerator(name = "SQ_STD_DMN", sequenceName = "SQ_STD_DMN", allocationSize = 50)
    @Column(name = "STD_DMN_SN")
    private Long id; // 번호

    @Column(name = "PRJ_SN")
    private Long projectId;

    @Column(name = "ENCT_CYCL")
    private int revisionNumber; // 제정차수

    @Column(name = "DMN_GROUP_NM")
    private String commonStandardDomainGroupName; // 표준도메인그룹명

    @Column(name = "DMN_CLSF_NM")
    private String commonStandardDomainCategory; // 표준도메인분류명

    @Column(name = "DMN_NM")
    private String commonStandardDomainName; // 표준도메인명

    @Column(name = "DMN_EXPLN", length = 1000)
    private String commonStandardDomainDescription; // 표준도메인설명

    @Column(name = "DATA_TYPE_NM")
    private String dataType; // 데이터타입

    @Column(name = "DATA_LEN")
    private Integer dataLength; // 데이터길이

    @Column(name = "DATA_DCPT_LEN")
    private Integer dataDecimalLength; // 데이터소수점길이

    @Column(name = "STRG_FRMT_CN")
    private String storageFormat; // 저장 형식

    @Column(name = "INDCT_FRMT_CN")
    private String displayFormat; // 표현 형식

    @Column(name = "UNIT_NM")
    private String unit; // 단위

    @Column(name = "ALWNC_VL_CN", length = 2000)
    private String allowedValues; // 허용값

    @Column(name = "APRV_YN")
    private Boolean isApproval;

    @OneToMany(mappedBy = "standardDomain")
    private List<StandardTerm> standardTerms;

    public static StandardDomain fromEntity(StandardDomainDto standardDomainDto) {
        return StandardDomain.builder()
                .projectId(standardDomainDto.getProjectId())
                .revisionNumber(standardDomainDto.getRevisionNumber())
                .commonStandardDomainGroupName(standardDomainDto.getCommonStandardDomainGroupName())
                .commonStandardDomainCategory(standardDomainDto.getCommonStandardDomainCategory())
                .commonStandardDomainName(standardDomainDto.getCommonStandardDomainName())
                .commonStandardDomainDescription(standardDomainDto.getCommonStandardDomainDescription())
                .dataType(standardDomainDto.getDataType())
                .dataLength(standardDomainDto.getDataLength())
                .dataDecimalLength(standardDomainDto.getDataDecimalLength())
                .storageFormat(standardDomainDto.getStorageFormat())
                .displayFormat(standardDomainDto.getDisplayFormat())
                .unit(standardDomainDto.getUnit())
                .allowedValues(standardDomainDto.getAllowedValues())
                .isApproval(standardDomainDto.getIsApprovalYn().equals("Y"))
                .build();
    }
}
