package com.lexorma.data.ai.dictionary.service;

import com.lexorma.data.ai.answer.StructuredAnswer;

import java.util.List;

/**
 * AI 데이터 사전 초안의 구조화 응답 ({@code ai/description-draft.schema.json}). 검증 상태·승인 상태 필드는 없다 (스키마가 거절한다) — 서버가 정한다.
 */
public record DescriptionDraft(String targetType, Long targetId, String description, String certainty, String nameInterpretation,
                               Recommendation termRecommendation, Recommendation domainRecommendation, List<Related> related,
                               List<StructuredAnswer.Evidence> evidence, List<String> unknowns, List<String> questions) {

    /** 표준 용어(termId)·도메인(domainId) 추천 */
    public record Recommendation(Long id, String name, String reason) {
    }

    public record Related(String type, Long id, String name, String note) {
    }
}
