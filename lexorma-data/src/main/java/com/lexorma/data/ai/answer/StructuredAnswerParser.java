package com.lexorma.data.ai.answer;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 구조화 응답 (AI-4 후속) : AI 메타데이터 질의의 최종 답변을 {@code ai/metadata-answer.schema.json} 으로 검증한다.
 * 형식 안내·정리·엄격 파싱·JSON Schema 검증은 {@link JsonSchemaOutput} 이 한다 (AI-5 데이터 사전 초안도 같은 방식).
 */
@Component
public class StructuredAnswerParser {

    static final String SCHEMA_RESOURCE = "ai/metadata-answer.schema.json";

    /** 검증 결과 : 통과면 value, 아니면 errors (원문은 담지 않는다) */
    public record Result(StructuredAnswer value, List<String> errors) {
        public boolean valid() {
            return value != null;
        }
    }

    private final JsonSchemaOutput<StructuredAnswer> output = new JsonSchemaOutput<>(SCHEMA_RESOURCE, StructuredAnswer.class);

    /** 모델에게 보낼 형식 지시문 (Spring AI 형식 + 이 스키마) */
    public String format() {
        return output.format();
    }

    public String schema() {
        return output.schema();
    }

    public Result parse(String text) {
        JsonSchemaOutput.Result<StructuredAnswer> result = output.parse(text);
        return new Result(result.value(), result.errors());
    }
}
