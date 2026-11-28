package com.lexorma.ai.answer;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 구조화 응답 (AI-4 후속) : AI 메타데이터 질의의 최종 답변을 {@code ai/metadata-answer.schema.json} 으로 검증한다.
 * 형식 안내·정리·엄격 파싱·JSON Schema 검증은 {@link JsonSchemaOutput} 이 한다 (AI-5 데이터 사전 초안도 같은 방식).
 */
@Component
public class StructuredAnswerParser {

    static final String SCHEMA_RESOURCE = "ai/metadata-answer.schema.json";
    /** QUALITY 범위 (AI-7) : 위 스키마 + 근거 유형 QUALITY_FINDING·QUALITY_RUN. METADATA 범위는 품질 근거를 받지 않는다 */
    static final String QUALITY_SCHEMA_RESOURCE = "ai/quality-answer.schema.json";

    /** 검증 결과 : 통과면 value, 아니면 errors (원문은 담지 않는다) */
    public record Result(StructuredAnswer value, List<String> errors) {
        public boolean valid() {
            return value != null;
        }
    }

    private final JsonSchemaOutput<StructuredAnswer> output = new JsonSchemaOutput<>(SCHEMA_RESOURCE, StructuredAnswer.class);
    private final JsonSchemaOutput<StructuredAnswer> quality = new JsonSchemaOutput<>(QUALITY_SCHEMA_RESOURCE, StructuredAnswer.class);

    private JsonSchemaOutput<StructuredAnswer> of(com.lexorma.ai.tool.ToolScope scope) {
        return scope == com.lexorma.ai.tool.ToolScope.QUALITY ? quality : output;
    }

    /** 범위의 형식 지시문 */
    public String format(com.lexorma.ai.tool.ToolScope scope) {
        return of(scope).format();
    }

    /** 범위의 스키마로 검증 */
    public Result parse(String text, com.lexorma.ai.tool.ToolScope scope) {
        JsonSchemaOutput.Result<StructuredAnswer> result = of(scope).parse(text);
        return new Result(result.value(), result.errors());
    }

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
