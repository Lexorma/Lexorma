package com.lexorma.data.ai.answer;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.CompositeResponseTextCleaner;
import org.springframework.ai.converter.MarkdownCodeBlockCleaner;
import org.springframework.ai.converter.ResponseTextCleaner;
import org.springframework.ai.converter.ThinkingTagCleaner;
import org.springframework.ai.converter.WhitespaceCleaner;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 기능별 구조화 응답 (AI-4 의 방식을 AI-5 등이 함께 쓴다) : 클래스패스의 JSON Schema 하나로 모델에게 형식을 알리고, 받은 답을 그 스키마로 검증해 객체로 바꾼다.
 * <ul>
 *   <li>형식 안내 : Spring AI {@link BeanOutputConverter} 의 형식 지시문({@link #format()})에 이 스키마를 넣는다</li>
 *   <li>정리 : Spring AI 의 응답 정리기(앞뒤 공백·생각 태그·```json 코드 블록)만 쓴다</li>
 *   <li>검증 : 엄격한 JSON 파싱(뒤에 남는 글자·중복 키 거절. JSON 에 없는 이스케이프 {@code \_} 는 그 글자로 읽는다) 뒤 JSON Schema(draft 2020-12, networknt).
 *       파싱만 된 것은 통과가 아니다. 통과한 것만 객체로 바꾼다</li>
 * </ul>
 * 의미 검증(대상·근거 ID 가 맞는지 등)은 부르는 쪽이 한다.
 */
public final class JsonSchemaOutput<T> {

    /** 오류 사유는 몇 개까지만 (재요청 프롬프트·로그용) */
    static final int MAX_ERRORS = 5;

    /** 검증 결과 : 통과면 value, 아니면 errors (원문은 담지 않는다) */
    public record Result<T>(T value, List<String> errors) {
        public boolean valid() {
            return value != null;
        }
    }

    private final String schemaJson;
    private final ResponseTextCleaner cleaner = CompositeResponseTextCleaner.builder()
            .addCleaner(new WhitespaceCleaner())
            .addCleaner(new ThinkingTagCleaner())
            .addCleaner(new MarkdownCodeBlockCleaner())
            .addCleaner(new WhitespaceCleaner())
            .build();
    // 문법은 엄격하게 (뒤에 남는 글자·중복 키 거절). 단 "\\_" 처럼 JSON 에 없는 이스케이프는 그 글자로 읽는다 :
    // 모델이 Markdown 답변 안의 _ 를 \\_ 로 적는 일이 실제로 있었다 (gemma4). 구조·필드·타입은 스키마가 그대로 검증한다
    private final JsonMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();
    private final Schema schema;
    private final BeanOutputConverter<T> converter;

    public JsonSchemaOutput(String schemaResource, Class<T> type) {
        this.schemaJson = read(schemaResource);
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaJson);
        String json = schemaJson;
        this.converter = new BeanOutputConverter<>(type, strict, cleaner) {
            @Override
            protected String generateSchema() {
                return json;
            }
        };
    }

    /** 모델에게 보낼 형식 지시문 (Spring AI 형식 + 이 스키마) */
    public String format() {
        return converter.getFormat();
    }

    public String schema() {
        return schemaJson;
    }

    public Result<T> parse(String text) {
        String cleaned = text == null ? null : cleaner.clean(text);
        if (cleaned == null || cleaned.isBlank()) return invalid(List.of("The reply is empty; a JSON object is required."));
        JsonNode node;
        try {
            node = strict.readTree(cleaned);
        } catch (JacksonException e) {
            return invalid(List.of("The reply is not valid JSON (" + e.getOriginalMessage() + ")."));
        }
        // 오류 문구는 영어로 (재요청 프롬프트에 넣는다)
        List<String> errors = schema.validate(node, context -> context.executionConfig(config -> config.locale(Locale.ENGLISH))).stream()
                .map(error -> error.getInstanceLocation() + " " + error.getMessage()).limit(MAX_ERRORS).toList();
        if (!errors.isEmpty()) return invalid(errors);
        try {
            return new Result<>(converter.convert(cleaned), List.of());
        } catch (RuntimeException e) {
            return invalid(List.of("The reply does not match the answer format."));
        }
    }

    private Result<T> invalid(List<String> errors) {
        return new Result<>(null, errors);
    }

    private static String read(String resource) {
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("AI 응답 스키마를 읽지 못했습니다 : " + resource, e);
        }
    }
}
