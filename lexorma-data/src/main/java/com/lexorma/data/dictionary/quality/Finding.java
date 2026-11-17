package com.lexorma.data.dictionary.quality;

import java.util.List;
import java.util.Map;

/**
 * 규칙 판정 결과 1건 (화면 문구와 무관한 값만). AI-6 이 결과를 저장·비교할 때 쓰는 형태다 — 지금은 계산만 하고 저장하지 않는다.
 *
 * @param targetIds   대상 ID (테이블·컬럼은 1개, 용어 그룹은 구성 용어 ID 오름차순)
 * @param actualValue 현재 값 (없으면 null)
 * @param expectedValue 권장 값 (알 수 없으면 null)
 * @param evidence    판정 근거 (키 순서 고정)
 * @param fingerprint {@link FindingFingerprint} — 같은 문제면 같은 값
 */
public record Finding(StandardizationRule rule, List<Long> targetIds, String actualValue, String expectedValue,
                      Map<String, String> evidence, String fingerprint) {
}
