package com.lexorma.data.dictionary.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "TB_STD_WORD")
public class StandardWord extends BaseEntity {

    @Id
    // 시퀀스(pooled, 50개씩 할당) : IDENTITY 는 JDBC 배치 INSERT 가 꺼지므로 사용하지 않는다 (V6 마이그레이션)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_STD_WORD")
    @SequenceGenerator(name = "SQ_STD_WORD", sequenceName = "SQ_STD_WORD", allocationSize = 50)
    @Column(name = "STD_WORD_SN")
    private Long id; // 번호

    @Column(name = "PRJ_SN")
    private Long projectId;

    @Column(name = "ENCT_CYCL")
    private int revisionNumber; // 제정차수

    @Column(name = "WORD_NM")
    private String commonStandardWordName; // 표준단어명

    @Column(name = "WORD_ENG_ABBR_NM")
    private String commonStandardWordAbbreviation; // 표준단어영문약어명

    @Column(name = "WORD_ENG_NM")
    private String commonStandardWordEnglishName; // 표준단어 영문명

    @Column(name = "WORD_EXPLN", length = 1000)
    private String commonStandardWordDescription; // 표준단어 설명

    @Column(name = "FRMT_WORD_YN")
    private Boolean isFormatWord; // 형식단어 여부 (true/false)

    @Column(name = "DMN_CLSF_NM")
    private String commonStandardDomainCategory; // 표준도메인분류명

    @ElementCollection
    @CollectionTable(name = "TB_STD_WORD_SYNM", joinColumns = @JoinColumn(name = "STD_WORD_SN"))
    @Column(name = "SYNM_NM")
    private List<String> synonymList; // 이음동의어 목록

    @ElementCollection
    @CollectionTable(name = "TB_STD_WORD_PRHBT", joinColumns = @JoinColumn(name = "STD_WORD_SN"))
    @Column(name = "PRHBT_WORD_NM")
    private List<String> restrictedWords; // 금칙어 목록

    @Column(name = "ABBR_USE_YN")
    private Boolean useAbbreviation;

    @Column(name = "APRV_YN")
    private Boolean isApproval;

    @OneToMany(mappedBy = "standardWord", cascade = CascadeType.ALL)
    private List<StandardTermWordMapping> termWordMappings;

    public static StandardWord fromEntity(StandardWordDto standardWordDto) {

        List<String> synonyms = new ArrayList<>();

        if(!standardWordDto.getSynonyms().isEmpty()){
            synonyms.addAll(Arrays.asList(standardWordDto.getSynonyms().split(",")));
        }

        List<String> restrictedWords = new ArrayList<>();
        if(!standardWordDto.getRestrictedWords().isEmpty()){
            restrictedWords.addAll(Arrays.asList(standardWordDto.getRestrictedWords().split(",")));
        }


        return StandardWord.builder()
                .projectId(standardWordDto.getProjectId())
                .revisionNumber(standardWordDto.getRevisionNumber())
                .commonStandardWordName(standardWordDto.getCommonStandardWordName())
                .commonStandardWordAbbreviation(standardWordDto.getCommonStandardWordAbbreviation())
                .commonStandardWordEnglishName(standardWordDto.getCommonStandardWordEnglishName())
                .commonStandardWordDescription(standardWordDto.getCommonStandardWordDescription())
                .isFormatWord(standardWordDto.getIsFormatWord().equals("Y"))
                .commonStandardDomainCategory(standardWordDto.getCommonStandardDomainCategory())
                .synonymList(synonyms)
                .restrictedWords(restrictedWords)
                .isApproval(standardWordDto.getIsApprovalYn().equals("Y"))
                .useAbbreviation(standardWordDto.getUseAbbreviation().equals("Y"))
                .build();
    }
}
