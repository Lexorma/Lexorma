package com.lexorma.data.dictionary.domain.dto;

import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Attribute;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType;

import java.util.List;

/** 비표준 컬럼명 개선 후보 (M-5 잔여, 읽기 전용 추천). 아무것도 적용하지 않는다 — 사용자가 컬럼·용어 관리 화면에서 직접 고친다 */
public final class ColumnSuggestionDto {

    private ColumnSuggestionDto() {
    }

    /** 후보 출처. 점수는 출처별 고정값 ({@code ColumnNameSuggestions}) */
    public enum Source { LINKED_TERM, ABBR_EXACT, NAME_EXACT, SYNONYM, WORD_COMPOSE, SAME_DESC_COLUMN, UNREGISTERED }

    /** 후보를 따를 때 사용자가 할 일 : 용어만 연결 / 연결 + 컬럼명 변경 / 컬럼명만 변경(이미 연결된 용어) / 새 용어 등록 검토 */
    public enum Action { LINK_TERM, LINK_TERM_AND_RENAME, RENAME, REGISTER_TERM }

    /** 추천 용어의 도메인과 컬럼 정의 : 같음 / 다름(attributes) / 비교할 도메인 없음 */
    public enum DomainFit { MATCH, MISMATCH, NO_DOMAIN }

    // ------------------------------------------------------------------ 입력 (프로젝션)

    /** 후보 용어 (+ 도메인 정의) */
    public record TermCandidateRow(Long termId, String termName, String abbreviation, Boolean isApproval,
                                   Long domainId, String domainName, String domainDataType, Integer domainLength, Integer domainDecimalLength) {
    }

    /** 같은 테이블의 컬럼 (이름 충돌 판정). termAbbreviation 은 연결된 용어의 약어 — 그 컬럼이 기대하는 이름 */
    public record TableColumnRow(Long columnId, String columnName, Long termId, String termAbbreviation) {
    }

    // ------------------------------------------------------------------ 결과

    /** 표준 단어 조합의 단어 하나 (WORD_COMPOSE·UNREGISTERED 근거) */
    public record Word(String abbreviation, String wordName, boolean approved) {
    }

    /**
     * 개선 후보 1건. termId 가 null 이면 등록된 용어가 없는 조합(UNREGISTERED) — proposedTermName·words 가 근거다.
     * recommendedColumnName 은 용어 영문약어 (약어가 없는 용어면 null).
     * nameConflict : 같은 테이블의 다른 컬럼이 그 이름을 쓴다 (대소문자 무시, conflictColumns).
     * candidateConflict : 같은 테이블의 다른 컬럼도 그 이름을 기대한다 (그 컬럼에 연결된 용어의 약어가 같다, candidateConflictColumns)
     */
    public record Candidate(Source source, List<Source> sources, int score, Action action,
                            Long termId, String termName, String termAbbreviation, Boolean termApproved,
                            String recommendedColumnName, String proposedTermName, List<Word> words,
                            String domainName, DomainFit domainFit, List<Attribute> attributes, String actualType, String expectedType,
                            boolean nameConflict, List<String> conflictColumns, boolean candidateConflict, List<String> candidateConflictColumns,
                            Long sameDescColumns, String reason) {
    }

    /**
     * 컬럼 1개의 후보 (최대 {@code ColumnNameSuggestions.LIMIT}건, 점수 순 고정). issueTypes 는 이 컬럼의 진단 유형 (후보 계산 대상 : 용어 미연결·컬럼명 불일치).
     * candidates 가 비면 reason 에 사유가 있다 (대상이 아님 / 후보를 찾지 못함)
     */
    public record Suggestions(Long columnId, String columnName, String columnDesc, String dataType, Long tableId, String tableName, Long schemaId,
                              Long termId, String termName, String termAbbreviation, List<IssueType> issueTypes,
                              List<Candidate> candidates, boolean truncated, String reason) {
    }
}
