package com.lexorma.data.dictionary.domain.dto;

import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;

import java.util.List;
import java.util.Map;

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

    /**
     * 그룹에 속한 용어. note 는 이 용어가 그룹에 들어온 근거 (예: 가진 이음동의어, 쓰인 금칙어), 없으면 null.
     * columnCount 는 그 용어를 쓰는 물리 컬럼 수 (목록의 한 페이지 분량만 한 번에 센다)
     */
    public record Member(Long termId, String termName, String abbreviation, String domainName, Boolean isApproval, Long columnCount, String note) {
    }

    /**
     * 진단 결과 1건 = 규칙 1개의 용어 그룹 (금칙어는 용어 1개). id 는 fingerprint (그리드 행 키 겸 AI-6 식별자).
     * groupKey 는 그룹을 묶은 값의 표시 (약어, 정규화한 용어명, 단어 구성 등), evidence 는 판정 근거(키 순서 고정)
     */
    public record Group(String id, StandardizationRule type, Severity severity, String groupKey, int memberCount, List<Member> members,
                        String message, Map<String, String> evidence) {
    }

    /** groups 는 규칙별 그룹 수, issueTerms 는 한 그룹 이상에 속한 용어 수 (한 용어가 여러 그룹에 들 수 있다) */
    public record Summary(long totalTerms, long issueTerms, Map<StandardizationRule, Long> groups, long errors, long warnings, long infos) {
    }

    /** 목록 조건 (모두 선택). searchColumn : termName / abbreviation (그룹의 용어 중 하나라도 맞으면) */
    public record Search(StandardizationRule type, Severity severity, String searchColumn, String searchQuery) {
    }
}
