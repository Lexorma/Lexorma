package com.lexorma.data.inspection.service;

import com.lexorma.data.dictionary.quality.Finding;
import com.lexorma.data.dictionary.quality.FindingFingerprint;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 근거 JSON 직렬화 (AI-6 FINAL 메모리 개선) : 직접 쓴 JSON 이 FINAL 전 Jackson 직렬화와 같은 JSON 값이고 키 순서도 같다 — 저장 값(JSONB)·화면이 바뀌지 않는다.
 */
class EvidenceJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("이스케이프·유니코드·제어 문자·null·숫자·참거짓·중첩 목록/Map 이 Jackson 과 같은 값, 키 순서 유지")
    void sameAsJackson() {
        StringBuilder control = new StringBuilder();
        for (char c = 0; c < 0x20; c++) control.append(c);
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("quote", "a\"b\\c/d");
        evidence.put("control", control.toString() + "\u007f \u001f");
        evidence.put("korean", "회원 이름 😀");
        evidence.put("empty", "");
        evidence.put("nothing", null);
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("id", 3L);
        member.put("name", "용어\t3");
        member.put("approved", true);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("members", List.of(member, Map.of("id", 4L)));
        details.put("count", 2);
        details.put("flags", Arrays.asList(true, false, null));
        details.put("nested", Map.of("k", List.of()));
        String fingerprint = FindingFingerprint.of(1, StandardizationRule.TERM_NOT_LINKED, List.of(9L, 3L), List.of());
        InspectionItem item = new InspectionItem(new Finding(StandardizationRule.TERM_NOT_LINKED, List.of(3L, 9L), "현재\n값", null, evidence, fingerprint),
                InspectionItem.ObjectType.COLUMN, 3, 7L, "S.T.\"C\"", details);

        String written = EvidenceJson.of(item);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("fingerprintVersion", FindingFingerprint.version(fingerprint));
        legacy.put("targetIds", item.finding().targetIds());
        legacy.put("actualValue", item.finding().actualValue());
        legacy.put("expectedValue", item.finding().expectedValue());
        legacy.put("evidence", new LinkedHashMap<>(evidence));
        legacy.put("details", details);
        JsonNode expected = JSON.readTree(EvidenceJson.jackson(legacy));
        JsonNode actual = JSON.readTree(written);
        assertThat(actual).isEqualTo(expected);
        assertThat(new ArrayList<>(actual.propertyNames())).containsExactly("fingerprintVersion", "targetIds", "actualValue", "expectedValue", "evidence", "details");
        assertThat(new ArrayList<>(actual.path("evidence").propertyNames())).containsExactly("quote", "control", "korean", "empty", "nothing");
        // 버퍼를 다시 써도 앞 결과가 섞이지 않는다
        assertThat(EvidenceJson.of(item)).isEqualTo(written);
    }

    @Test
    @DisplayName("근거·표시 정보가 없으면 빈 객체, 같은 fingerprint 의 내용 비교는 규칙·대상·근거가 모두 같을 때만 같다")
    void emptyAndContent() {
        InspectionItem item = new InspectionItem(new Finding(StandardizationRule.TABLE_COMMENT_MISSING, List.of(5L), null, null, null, "v1:x"),
                InspectionItem.ObjectType.TABLE, 5, 5L, "T", null);
        JsonNode json = JSON.readTree(EvidenceJson.of(item));
        assertThat(json.path("evidence").isObject() && json.path("evidence").isEmpty()).isTrue();
        assertThat(json.path("details").isObject() && json.path("details").isEmpty()).isTrue();
        StoredItem a = StoredItem.of(item);
        assertThat(a.sameContent(StoredItem.of(item))).isTrue();
        assertThat(a.sameContent(StoredItem.of(new InspectionItem(item.finding(), InspectionItem.ObjectType.TABLE, 6, 5L, "T", null)))).isFalse();
        assertThat(a.sameContent(StoredItem.of(new InspectionItem(item.finding(), InspectionItem.ObjectType.TABLE, 5, 5L, "T", Map.of("x", "y"))))).isFalse();
    }
}
