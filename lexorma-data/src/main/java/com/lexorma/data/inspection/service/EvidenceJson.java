package com.lexorma.data.inspection.service;

import com.lexorma.data.dictionary.quality.FindingFingerprint;
import com.lexorma.data.dictionary.quality.InspectionItem;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.Map;

/**
 * 검사 결과 근거(JSONB)의 직렬화 (AI-6 FINAL, 메모리 개선). 바깥 모양은 2차 그대로다 :
 * {@code {"fingerprintVersion", "targetIds", "actualValue", "expectedValue", "evidence", "details"}}.
 * 결과 30만 건을 결과마다 Jackson 으로 두 번(충돌 비교·저장) 직렬화하던 것이 실행기 할당의 약 3분의 1이었다 — 근거의 값은 문자열·숫자·참거짓·목록·Map 뿐이라
 * 한 번만, 재사용하는 버퍼에 직접 쓴다. 다른 값(예상하지 못한 형)은 Jackson 에 맡긴다. 문자열 이스케이프는 JSON 규칙(따옴표·역슬래시·제어 문자) 그대로다.
 * 실행기 스레드 하나만 쓰지만 버퍼는 스레드마다 둔다.
 */
final class EvidenceJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(() -> new StringBuilder(512));
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private EvidenceJson() {
    }

    static String of(InspectionItem item) {
        StringBuilder out = BUFFER.get();
        out.setLength(0);
        out.append("{\"fingerprintVersion\":");
        value(out, FindingFingerprint.version(item.finding().fingerprint()));
        out.append(",\"targetIds\":");
        value(out, item.finding().targetIds());
        out.append(",\"actualValue\":");
        value(out, item.finding().actualValue());
        out.append(",\"expectedValue\":");
        value(out, item.finding().expectedValue());
        out.append(",\"evidence\":");
        value(out, item.finding().evidence() == null ? Map.of() : item.finding().evidence());
        out.append(",\"details\":");
        value(out, item.details() == null ? Map.of() : item.details());
        out.append('}');
        String json = out.toString();
        if (out.capacity() > 64 * 1024) BUFFER.remove(); // 큰 근거 하나 때문에 버퍼가 계속 커져 있지 않게
        return json;
    }

    static void value(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> string(out, text);
            case Long number -> out.append(number.longValue());
            case Integer number -> out.append(number.intValue());
            case Short number -> out.append(number.shortValue());
            case Byte number -> out.append(number.byteValue());
            case Boolean bool -> out.append(bool.booleanValue());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    string(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    value(out, entry.getValue());
                }
                out.append('}');
            }
            case Collection<?> list -> {
                out.append('[');
                boolean first = true;
                for (Object element : list) {
                    if (!first) out.append(',');
                    first = false;
                    value(out, element);
                }
                out.append(']');
            }
            default -> out.append(JSON.writeValueAsString(value)); // 근거에 쓰지 않는 형 (안전한 기본값)
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** 테스트 : Jackson 결과와 같은 JSON 값인지 비교할 때 쓴다 */
    static String jackson(Object value) {
        return JSON.writeValueAsString(value);
    }
}
