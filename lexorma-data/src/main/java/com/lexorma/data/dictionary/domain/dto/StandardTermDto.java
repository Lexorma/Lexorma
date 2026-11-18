package com.lexorma.data.dictionary.domain.dto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import lombok.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StandardTermDto {
    private Long id; // 번호
    private Long projectId;
    private int revisionNumber; // 제정차수
    private String commonStandardTermName; // 표준용어명
    private String commonStandardTermDescription; // 표준용어설명
    private String commonStandardTermAbbreviation; // 표준용어영문약어명
    private String commonStandardDomainName; // 표준도메인명
    private String allowedValues; // 허용값
    private String storageFormat; // 저장 형식
    private String displayFormat; // 표현 형식
    private String administrativeStandardCodeName; // 행정표준코드명
    private String responsibleOrganization; // 소관기관명
    private String synonyms;
    private String isApprovalYn;

    /** 사용 건수 (목록 조회 전용) : 이 용어를 쓰는 물리 컬럼 수 */
    private Long usageCount;

    private String dataType;

    /**
     * 용어를 이루는 단어들의 단어 약어를 '_' 로 이은 값 (단어 연결이 없으면 ""). 저장된 영문약어를 대신하지 않는다 —
     * 조회·수정 화면은 저장값을 쓴다 (M-5 7차). 논리 → 물리 생성의 컬럼명만 이 값을 먼저 쓴다 (PhysicalGenerationService.columnSpec)
     */
    public static String wordAbbreviation(StandardTerm entity) {
        return entity.getTermWordMappings() == null ? "" : entity.getTermWordMappings().stream()
                .sorted(Comparator.comparingInt(StandardTermWordMapping::getOrderIndex))
                .map(mapping -> mapping.getStandardWord().getCommonStandardWordAbbreviation())
                .collect(Collectors.joining("_"));
    }

    public static StandardTermDto fromEntity(StandardTerm entity) {

        // 영문약어는 저장된 값 그대로 (예전에는 단어 약어 조합을 보여 줘, 저장값이 다른 용어를 화면에서 고치면 약어가 바뀌었다 — M-5 7차)
        String abbreviation = entity.getCommonStandardTermAbbreviation();

        StandardDomain domain = entity.getStandardDomain();

        String data = domain != null ? domain.getDataType() : null;

        // 데이터 타입 입력
        if(data != null && domain.getDataLength() != null && domain.getDataLength() != 0) {
            data += "(" + domain.getDataLength() + ")";
        }

        return StandardTermDto.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .revisionNumber(entity.getRevisionNumber())
                .commonStandardTermName(entity.getCommonStandardTermName())
                .commonStandardTermDescription(entity.getCommonStandardTermDescription())
                .commonStandardTermAbbreviation(abbreviation)
                .commonStandardDomainName(domain != null ? domain.getCommonStandardDomainName() : null)
                .allowedValues(domain != null ? domain.getAllowedValues() : null)
                .storageFormat(domain != null ? domain.getStorageFormat() : null)
                .displayFormat(domain != null ? domain.getDisplayFormat() : null)
                .administrativeStandardCodeName(entity.getAdministrativeStandardCodeName())
                .responsibleOrganization(entity.getResponsibleOrganization())
                .synonyms(convertListToString(entity.getSynonymList()))
                .isApprovalYn(entity.getIsApproval() ? "Y" : "N")
                .dataType(data)
                .build();
    }

    private static String convertListToString(List<String> list) {
        return (list == null || list.isEmpty()) ? "" : list.stream().collect(Collectors.joining(","));
    }
}
