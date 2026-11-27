package com.lexorma.data.dictionary.domain.dto;



/** 표준 용어 중복·유사어 진단 (M-5 잔여, 읽기 전용). 조회할 때 계산하고 저장하지 않는다 */
public final class TermQualityDto {

    private TermQualityDto() {
    }

    // ------------------------------------------------------------------ 진단 입력 (프로젝트 단위 프로젝션)

    public record TermRow(Long termId, String termName, String abbreviation, Long domainId, String domainName, Boolean isApproval) {
    }

    /** TB_STD_TRM_WORD : 용어를 이루는 표준 단어와 순서 */
    public record TermWordRow(Long termId, Long wordId, Integer orderIndex) {
    }

    public record WordRow(Long wordId, String wordName, String abbreviation, Boolean isApproval) {
    }

    /** 용어·단어의 이음동의어, 단어의 금칙어 (owner = 용어 또는 단어 ID) */
    public record NameRow(Long ownerId, String name) {
    }

    // ------------------------------------------------------------------ 결과

}
