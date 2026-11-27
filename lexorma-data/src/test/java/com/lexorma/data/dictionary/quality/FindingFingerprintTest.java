package com.lexorma.data.dictionary.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fingerprint 공통 인코딩 (AI-6 FINAL 최종 보완, AI6-FC-08·09) — 순수 함수 부분 : FP01~FP07, FC-FP01~FC-FP10·FC-FP16.
 * 저장 결과의 생명주기(FC-FP11~15 : 그룹 구성원 변경·대상 삭제 후 재생성·재발과 예외 유지·충돌 감지·단순 중복)는 InspectionLifecycleTest 와
 * QualityInspectionIntegrationTest 가 실제 검사로 본다.
 */
class FindingFingerprintTest {

    private static final StandardizationRule RULE = StandardizationRule.IDENTIFIER_INVALID_CHARACTER;
    private static final char SEP = '\u001f';

    @Test
    @DisplayName("FP01·FC-FP08 결정적 : 같은 입력은 언제나 같은 값, 대상 ID 순서와 무관, 형식 v4:64자리 16진수")
    void deterministic() {
        String a = FindingFingerprint.of(1, RULE, List.of(3L, 1L, 2L), List.of("COLUMN", "POSTGRESQL", "A-B"));
        assertThat(FindingFingerprint.of(1, RULE, List.of(1L, 2L, 3L), List.of("COLUMN", "POSTGRESQL", "A-B"))).isEqualTo(a);
        assertThat(a).matches("v4:[0-9a-f]{64}");
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("가", "B"))).matches("v4:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("문서의 인코딩 그대로 : 해시 입력 = S(v4)·L(프로젝트)·S(규칙)·S(대상 유형)·S(T|G)·[대상 ID]·L(키 수)·S(키)…, 길이는 UTF-8 바이트 수")
    void documentedEncoding() {
        List<String> keys = Arrays.asList("한글", null, "", "😀" + SEP);
        assertThat(FindingFingerprint.of(7, RULE, List.of(9L, 5L), keys)).isEqualTo(reference(7, RULE, "T", new long[]{5, 9}, keys));
        assertThat(FindingFingerprint.group(7, StandardizationRule.TERM_SAME_WORDS, keys)).isEqualTo(reference(7, StandardizationRule.TERM_SAME_WORDS, "G", null, keys));
    }

    @Test
    @DisplayName("FP02·FC-FP03·FC-FP04 구분자·길이 경계 : [A␟B] ≠ [A, B], [A␟] ≠ [A, \"\"], 길이 접두처럼 보이는 값도 경계가 섞이지 않는다")
    void separatorsAndBoundaries() {
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), List.of("A" + SEP + "B"))).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(5L), List.of("A", "B")));
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), List.of("A" + SEP))).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(5L), List.of("A", "")));
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), List.of("1:" + SEP, "x"))).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(5L), List.of("1:", SEP + "x")));
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("2:AB"))).isNotEqualTo(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("AB")));
    }

    @Test
    @DisplayName("FP03·FC-FP02 null 과 빈 문자열은 다른 값, 순서가 다르면 다른 값")
    void nullVersusEmpty() {
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), Arrays.asList((String) null))).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(5L), List.of("")));
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), Arrays.asList("-", null))).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(5L), Arrays.asList(null, "-")));
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, Arrays.asList((String) null)))
                .isNotEqualTo(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("")));
    }

    @Test
    @DisplayName("FP04·FC-FP05 복합 키·대상 경계 : 키 개수, 키 경계, 대상 ID 경계(12 vs 1·2)가 다르면 다른 값")
    void compositeBoundaries() {
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), List.of("AB", "C"))).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(5L), List.of("A", "BC")));
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), List.of())).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(5L), List.of("")));
        assertThat(FindingFingerprint.of(1, RULE, List.of(12L), List.of())).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(1L, 2L), List.of()));
        assertThat(FindingFingerprint.of(1, RULE, List.of(), List.of())).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(0L), List.of()));
    }

    @Test
    @DisplayName("FC-FP06·FC-FP07 한글·다국어·보조 평면 문자 : 서로 다른 값, 바이트 길이 기준이라 같은 글자 수의 다른 문자열도 섞이지 않는다")
    void unicode() {
        String korean = FindingFingerprint.of(1, RULE, List.of(5L), List.of("회원_이름"));
        String japanese = FindingFingerprint.of(1, RULE, List.of(5L), List.of("会員_名前"));
        String emoji = FindingFingerprint.of(1, RULE, List.of(5L), List.of("😀"));
        String surrogates = FindingFingerprint.of(1, RULE, List.of(5L), List.of("\uD83D", "\uDE00")); // 짝 없는 서러게이트 둘 (잘못된 입력도 결정적)
        assertThat(Set.of(korean, japanese, emoji, surrogates)).hasSize(4);
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), List.of("😀"))).isEqualTo(emoji);
        // 조합형과 완성형은 정규화하지 않는다 (입력 그대로 — 판정 클래스가 정규화한 값을 넘긴다)
        assertThat(FindingFingerprint.of(1, RULE, List.of(5L), List.of("가"))).isNotEqualTo(FindingFingerprint.of(1, RULE, List.of(5L), List.of("가")));
    }

    @Test
    @DisplayName("FC-FP09·FC-FP10·FP05~FP07 대상 식별과 그룹 식별, 규칙·대상·프로젝트가 다르면 다른 값")
    void scope() {
        String base = FindingFingerprint.of(1, StandardizationRule.TERM_SYNONYM, List.of(), List.of("A"));
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_SYNONYM, List.of("A"))).as("대상 ID 가 없어도 대상 식별과 그룹 식별은 다르다").isNotEqualTo(base);
        assertThat(FindingFingerprint.of(1, StandardizationRule.TERM_ABBR_SHARED, List.of(), List.of("A"))).isNotEqualTo(base);
        assertThat(FindingFingerprint.of(1, StandardizationRule.TERM_SYNONYM, List.of(6L), List.of("A"))).isNotEqualTo(base);
        assertThat(FindingFingerprint.of(2, StandardizationRule.TERM_SYNONYM, List.of(), List.of("A"))).isNotEqualTo(base);
        // 규칙 이름이 다른 규칙의 접두가 되어도 섞이지 않는다 (규칙 코드도 길이와 함께)
        assertThat(FindingFingerprint.of(1, StandardizationRule.TERM_ABBR_DUPLICATE, List.of(5L), List.of()))
                .isNotEqualTo(FindingFingerprint.of(1, StandardizationRule.TERM_ABBR_SHARED, List.of(5L), List.of()));
    }

    @Test
    @DisplayName("FC-FP01·FC-FP16 모든 규칙이 공통 인코딩(v4) 하나만 쓰고, 예전 판(v1·v2·v3)·조건부 분기·호환 상수가 남아 있지 않다")
    void singleEncodingNoLegacy() {
        for (StandardizationRule rule : StandardizationRule.values()) {
            assertThat(FindingFingerprint.version(FindingFingerprint.of(1, rule, List.of(1L), List.of("x")))).isEqualTo("v4");
            assertThat(FindingFingerprint.version(FindingFingerprint.group(1, rule, List.of("x")))).isEqualTo("v4");
        }
        Set<String> members = Arrays.stream(FindingFingerprint.class.getDeclaredFields()).filter(f -> Modifier.isStatic(f.getModifiers()))
                .map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        assertThat(members).doesNotContain("GROUP_VERSION", "SAFE_VERSION", "SEPARATOR");
        assertThat(Arrays.stream(FindingFingerprint.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("ambiguous", "safe");
    }

    /** 문서(FindingFingerprint 주석)의 인코딩을 따로 구현한 기준 */
    private static String reference(long projectId, StandardizationRule rule, String identity, long[] ids, List<String> keys) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        text(out, "v4");
        number(out, projectId);
        text(out, rule.name());
        text(out, rule.targetType().name());
        text(out, identity);
        if (ids != null) {
            number(out, ids.length);
            for (long id : ids) number(out, id);
        }
        number(out, keys.size());
        keys.forEach(key -> text(out, key));
        try {
            return "v4:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void text(ByteArrayOutputStream out, String value) {
        if (value == null) {
            out.write(0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(1);
        out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    private static void number(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(8).putLong(value).array());
    }
}
