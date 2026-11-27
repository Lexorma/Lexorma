package com.lexorma.data.dictionary.quality;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * 진단 결과의 안정적인 식별자 (AI-6 FINAL 최종 보완, AI6-FC-08·09) : {@code v4:} + SHA-256(구조적 인코딩). 모든 규칙이 같은 인코딩을 쓴다 —
 * 예전 v1(구분자 연결)·v2(그룹 키 길이 접두)·조건부 v3 는 없앴다 (실제 사용자 0명, 호환 분기·변환 없음).
 *
 * <p><b>논리적 식별 키</b> (규칙마다 판정 클래스가 정한다 — 이번 변경으로 바뀌지 않았다)
 * <ul>
 * <li>대상 식별 ({@link #of}) : 프로젝트 · 규칙 · 대상 유형 · 대상 ID 집합(오름차순) · 핵심 값 목록 — 이름·메시지·현재 값은 넣지 않는다
 *     (이름을 바꿔도 같은 결과, 지우고 새 ID 로 만들면 새 결과)</li>
 * <li>그룹 식별 ({@link #group}) : 프로젝트 · 규칙 · 대상 유형 · 그룹 키 목록 — 구성원 ID 는 넣지 않는다 (구성원이 바뀌어도 같은 결과, 키가 바뀌면 새 결과, AI6-D11)</li>
 * </ul>
 * 프로젝트 ID 는 예전부터 식별 키에 있었다 (DB 의 UNIQUE(PRJ_SN, HASH_VL) 와 함께 프로젝트를 나눈다).
 *
 * <p><b>인코딩</b> (해시 입력 바이트, 순서대로) :
 * <pre>
 * 문자열 S(x)  = x 가 null 이면 0x00, 아니면 0x01 + UTF-8 바이트 길이(4바이트 big-endian) + UTF-8 바이트
 * 정수 L(n)    = 8바이트 big-endian
 * 입력         = S("v4") · L(프로젝트 ID) · S(규칙 코드) · S(대상 유형) · S("T" | "G")
 *                · [T 이면] L(대상 ID 수) · L(대상 ID)… (오름차순)
 *                · L(핵심 값 수) · S(핵심 값)…
 * </pre>
 * 모든 값이 길이·종류 표시와 함께 들어가므로 구분자를 담은 값, null 과 빈 문자열, 경계가 다른 목록, 대상 식별과 그룹 식별이 같은 입력이 될 수 없다
 * (구조적 충돌 없음 — SHA-256 자체의 이론적 충돌은 별개). 문자열은 플랫폼 기본 인코딩이 아니라 항상 UTF-8 이고 길이는 실제 바이트 수다 (보조 평면 문자 포함).
 * 해시 입력은 문자열로 만들지 않고 다이제스트에 바로 쓴다.
 */
public final class FindingFingerprint {

    /** 인코딩 판 — 입력 구조를 바꾸면 올린다 */
    public static final String VERSION = "v4";
    private static final String TARGETS = "T";
    private static final String GROUP = "G";

    private FindingFingerprint() {
    }

    /** 대상 식별 : 대상 ID 집합(순서 무관) + 핵심 값 (판정 클래스가 정규화해서 넘긴다 — 예 : 기대 컬럼명은 대문자, 위반 약어는 정렬) */
    public static String of(long projectId, StandardizationRule rule, Collection<Long> targetIds, List<String> keys) {
        Encoder encoder = new Encoder(projectId, rule, TARGETS);
        long[] ids = targetIds.stream().mapToLong(Long::longValue).sorted().toArray();
        encoder.number(ids.length);
        for (long id : ids) encoder.number(id);
        return encoder.keys(keys);
    }

    /** 그룹 식별 : 그룹을 정하는 키(정규화한 약어 + 도메인, 정규화 용어명, 단어 구성, 이음동의어 치환 구성)만 — 구성원 ID 는 근거(Finding.targetIds)에 따로 둔다 */
    public static String group(long projectId, StandardizationRule rule, List<String> keys) {
        return new Encoder(projectId, rule, GROUP).keys(keys);
    }

    /** 식별자의 판 — 저장할 때 근거에 함께 둔다 */
    public static String version(String fingerprint) {
        int colon = fingerprint == null ? -1 : fingerprint.indexOf(':');
        return colon < 0 ? null : fingerprint.substring(0, colon);
    }

    /** 다이제스트에 바로 쓰는 구조적 인코더 (해시 입력 문자열을 만들지 않는다) */
    private static final class Encoder {
        private final MessageDigest digest;
        private final byte[] buffer = new byte[8];

        Encoder(long projectId, StandardizationRule rule, String identity) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            text(VERSION);
            number(projectId);
            text(rule.name());
            text(rule.targetType().name());
            text(identity);
        }

        void text(String value) {
            if (value == null) {
                digest.update((byte) 0);
                return;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) 1);
            int length = bytes.length;
            buffer[0] = (byte) (length >>> 24);
            buffer[1] = (byte) (length >>> 16);
            buffer[2] = (byte) (length >>> 8);
            buffer[3] = (byte) length;
            digest.update(buffer, 0, 4);
            digest.update(bytes);
        }

        void number(long value) {
            for (int i = 7; i >= 0; i--) {
                buffer[i] = (byte) value;
                value >>>= 8;
            }
            digest.update(buffer, 0, 8);
        }

        String keys(List<String> keys) {
            number(keys.size());
            for (String key : keys) text(key);
            return VERSION + ":" + HexFormat.of().formatHex(digest.digest());
        }
    }
}
