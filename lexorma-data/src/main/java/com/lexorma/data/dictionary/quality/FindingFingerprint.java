package com.lexorma.data.dictionary.quality;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 진단 결과의 안정적인 식별자 : {@code v1:} + SHA-256(정규 문자열).
 * <pre>
 * v1 | P{프로젝트 ID} | {규칙 코드} | {대상 유형} | {대상 ID 오름차순, ","} | {핵심 값 …}
 * </pre>
 * 메시지(번역)·화면 순서·현재 값처럼 문제의 의미와 무관한 값은 넣지 않는다. 핵심 값은 판정 클래스가 정규화해서 넘긴다
 * (예: 기대 컬럼명은 대문자, 위반 약어는 정렬). 프로젝트 ID 를 넣어 다른 프로젝트의 같은 ID 와 겹치지 않는다.
 * 정규 문자열의 구성을 바꾸면 접두어(v1)를 올린다.
 */
public final class FindingFingerprint {

    public static final String VERSION = "v1";
    private static final char SEPARATOR = '\u001f';

    private FindingFingerprint() {
    }

    public static String of(long projectId, StandardizationRule rule, Collection<Long> targetIds, List<String> keys) {
        StringBuilder canonical = new StringBuilder(VERSION)
                .append(SEPARATOR).append('P').append(projectId)
                .append(SEPARATOR).append(rule.name())
                .append(SEPARATOR).append(rule.targetType().name())
                .append(SEPARATOR).append(targetIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
        for (String key : keys) {
            canonical.append(SEPARATOR).append(key == null ? "" : key);
        }
        return VERSION + ":" + sha256(canonical.toString());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
