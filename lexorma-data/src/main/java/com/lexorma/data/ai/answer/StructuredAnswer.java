package com.lexorma.data.ai.answer;

import com.lexorma.data.ai.evidence.EvidenceType;

import java.util.List;

/**
 * AI 의 최종 답변 (AI-4 후속 구조화 응답). 형식은 {@code ai/metadata-answer.schema.json} 이고 {@link StructuredAnswerParser} 가 검증한다.
 * 근거의 검증 결과는 여기 없다 : AI 가 정하지 않고 서버가 정한다 (스키마가 다른 필드를 거절한다).
 *
 * @param answer   사용자에게 보일 답변 (Markdown)
 * @param evidence 답변이 기댄 메타데이터 (Tool 결과의 ID)
 */
public record StructuredAnswer(String answer, List<Evidence> evidence) {

    /**
     * @param tableId 컬럼이 속한 테이블 (COLUMN 만, 선택)
     * @param kind    관계 종류 FK / LOGICAL (RELATION 만, 선택)
     */
    public record Evidence(EvidenceType type, Long id, String name, Long tableId, String kind) {
    }
}
