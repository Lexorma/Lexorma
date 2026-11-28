package com.lexorma.data.inspection.service;

import com.lexorma.data.dictionary.quality.Finding;
import com.lexorma.data.dictionary.quality.FindingFingerprint;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.InspectionCodes.EndReason;
import com.lexorma.data.inspection.domain.InspectionCodes.FindingStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.RunChange;
import com.lexorma.data.inspection.service.InspectionLifecycle.Existing;
import com.lexorma.data.inspection.service.InspectionLifecycle.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 생명주기 판정(순수 함수)과 근거 JSON 모양 — DB 경로는 lexorma-app 의 QualityInspectionIntegrationTest */
class InspectionLifecycleTest {

    private static final StandardizationRule COLUMN = StandardizationRule.TERM_NOT_LINKED;
    private static final StandardizationRule TABLE = StandardizationRule.TABLE_PREFIX_MISSING;
    private static final Set<StandardizationRule> BOTH = EnumSet.of(COLUMN, TABLE);
    private static final LocalDateTime ENDED = LocalDateTime.of(2026, 11, 20, 10, 0);

    @Test
    @DisplayName("신규 · 유지 · 재발 · 해결(개선/대상 삭제) · 그대로 두는 것(끈 규칙, 이미 해결)")
    void plan() {
        List<StoredItem> found = List.of(item(COLUMN, 1, "a"), item(COLUMN, 2, "b"), item(TABLE, 3, "c"));
        List<Existing> existing = List.of(
                existing(10, "b", COLUMN, FindingStatus.OPEN, true),         // 유지 (예외)
                resolved(11, "c", TABLE),                                      // 재발
                existing(12, "d", COLUMN, FindingStatus.OPEN, false),        // 해결 : 대상 있음
                existing(13, "e", TABLE, FindingStatus.OPEN, true),          // 해결 : 대상 삭제
                resolved(14, "f", COLUMN),                                     // 찾지 못한 해결 결과 : 그대로
                existing(15, "g", StandardizationRule.TERM_SYNONYM, FindingStatus.OPEN, false)); // 적용하지 않은 규칙 : 그대로
        Plan plan = InspectionLifecycle.plan(BOTH, found, existing, row -> row.id() == 13);

        assertThat(plan.inserts()).extracting(StoredItem::fingerprint).containsExactly("a");
        assertThat(plan.persists()).extracting(InspectionLifecycle.Persist::id).containsExactly(10L);
        assertThat(plan.reopens()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(11);
            assertThat(r.previousReason()).isEqualTo(EndReason.RULE_SATISFIED);
            assertThat(r.previousEndedAt()).isEqualTo(ENDED);
        });
        assertThat(plan.resolves()).extracting(InspectionLifecycle.Resolve::id, InspectionLifecycle.Resolve::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(12L, EndReason.RULE_SATISFIED), org.assertj.core.groups.Tuple.tuple(13L, EndReason.TARGET_DELETED));
        assertThat(plan.counts().get(COLUMN)).containsEntry(RunChange.NEW, 1).containsEntry(RunChange.PERSISTING, 1).containsEntry(RunChange.EXCLUDED, 1)
                .containsEntry(RunChange.RULE_SATISFIED, 1).containsEntry(RunChange.REOPENED, 0);
        assertThat(plan.counts().get(TABLE)).containsEntry(RunChange.REOPENED, 1).containsEntry(RunChange.TARGET_DELETED, 1);
        assertThat(plan.counts()).doesNotContainKey(StandardizationRule.TERM_SYNONYM);
        assertThat(plan.found(COLUMN)).isEqualTo(2);
        assertThat(plan.found(TABLE)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 실행의 같은 fingerprint 는 한 번만, 적용하지 않은 규칙의 결과를 넘기면 거절")
    void duplicatesAndForeignRules() {
        Plan plan = InspectionLifecycle.plan(BOTH, List.of(item(COLUMN, 1, "a"), item(COLUMN, 1, "a")), List.of(), row -> false);
        assertThat(plan.inserts()).hasSize(1);
        assertThat(plan.duplicates()).isEqualTo(1);
        assertThatThrownBy(() -> InspectionLifecycle.plan(EnumSet.of(TABLE), List.of(item(COLUMN, 1, "a")), List.of(), row -> false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("규칙 집합이 비면(모든 묶음 실패) 아무것도 해결하지 않는다")
    void nothingJudgedResolvesNothing() {
        Plan plan = InspectionLifecycle.plan(EnumSet.noneOf(StandardizationRule.class), List.of(),
                List.of(existing(1, "a", COLUMN, FindingStatus.OPEN, false)), row -> true);
        assertThat(plan.resolves()).isEmpty();
        assertThat(plan.counts()).isEmpty();
    }

    @Test
    @DisplayName("그룹 식별 fingerprint : 그룹 키만 쓰고(구성원 무관) 프로젝트·규칙이 다르면 다른 값, 대상 식별과 섞이지 않는다 (공통 인코딩 v4)")
    void groupFingerprint() {
        String group = FindingFingerprint.group(1, StandardizationRule.TERM_ABBR_DUPLICATE, List.of("MBR_NM", "10"));
        assertThat(group).startsWith("v4:").hasSize(3 + 64);
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_ABBR_DUPLICATE, List.of("MBR_NM", "10"))).isEqualTo(group);
        assertThat(FindingFingerprint.group(2, StandardizationRule.TERM_ABBR_DUPLICATE, List.of("MBR_NM", "10"))).isNotEqualTo(group);
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_ABBR_SHARED, List.of("MBR_NM", "10"))).isNotEqualTo(group);
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_ABBR_DUPLICATE, List.of("MBR_NM", "11"))).isNotEqualTo(group);
        assertThat(FindingFingerprint.version(group)).isEqualTo("v4");
        assertThat(FindingFingerprint.version(FindingFingerprint.of(1, COLUMN, List.of(5L), List.of()))).isEqualTo("v4");
        assertThat(FindingFingerprint.version(null)).isNull();
        // 구분자·정규화 충돌 : 키 안의 구분자, null 과 빈 문자열, 키 개수가 달라도 같은 문자열이 되지 않는다 (키마다 길이 접두)
        String joined = FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("A\u001fB"));
        assertThat(joined).isNotEqualTo(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("A", "B")));
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, java.util.Arrays.asList((String) null)))
                .isNotEqualTo(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("")));
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("AB", "")))
                .isNotEqualTo(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("A", "B")));
        // 재현성 : 같은 입력은 언제나 같은 값
        assertThat(FindingFingerprint.group(1, StandardizationRule.TERM_SAME_WORDS, List.of("A\u001fB"))).isEqualTo(joined);
    }

    @Test
    @DisplayName("fingerprint 충돌 검사 : 내용까지 같으면 단순 중복(수만 센다), 내용이 다르면 충돌 예외 — 충돌한 묶음의 결과는 하나도 더하지 않는다")
    void fingerprintCollision() {
        java.util.Map<String, StoredItem> seen = new java.util.HashMap<>();
        StoredItem a = item(COLUMN, 1, "x");
        assertThat(InspectionLifecycle.checkFingerprints(List.of(a, item(COLUMN, 1, "x")), seen)).isEqualTo(1);
        assertThat(seen).containsOnlyKeys("x");
        // 다른 대상인데 같은 fingerprint (다른 묶음에서 와도 같다)
        assertThatThrownBy(() -> InspectionLifecycle.checkFingerprints(List.of(item(COLUMN, 2, "y"), item(COLUMN, 3, "x")), seen))
                .isInstanceOfSatisfying(InspectionLifecycle.FingerprintCollisionException.class, e -> assertThat(e.rule()).isEqualTo(COLUMN));
        assertThat(seen).as("충돌한 묶음의 결과는 더하지 않는다").containsOnlyKeys("x");
        // 같은 묶음 안의 충돌
        assertThatThrownBy(() -> InspectionLifecycle.checkFingerprints(List.of(item(TABLE, 4, "z"), item(TABLE, 5, "z")), new java.util.HashMap<>())).isInstanceOf(InspectionLifecycle.FingerprintCollisionException.class);
    }

    @Test
    @DisplayName("예외 여부는 생명주기와 독립 : OPEN 유지·해결, RESOLVED 재발·그대로 어느 경우에도 계획이 예외 값을 바꾸지 않는다 (AI6-2-03)")
    void exclusionIndependentOfLifecycle() {
        List<Existing> existing = List.of(existing(1, "keep", COLUMN, FindingStatus.OPEN, true), existing(2, "fixed", COLUMN, FindingStatus.OPEN, true),
                new Existing(3, "again", COLUMN, FindingStatus.RESOLVED, true, EndReason.RULE_SATISFIED, ENDED),
                new Existing(4, "quiet", COLUMN, FindingStatus.RESOLVED, true, EndReason.RULE_SATISFIED, ENDED));
        Plan plan = InspectionLifecycle.plan(EnumSet.of(COLUMN), List.of(item(COLUMN, 1, "keep"), item(COLUMN, 3, "again")), existing, row -> false);
        assertThat(plan.inserts()).as("예외 결과를 신규로 다시 만들지 않는다").isEmpty();
        assertThat(plan.persists()).singleElement().satisfies(p -> assertThat(p.excluded()).isTrue());
        assertThat(plan.reopens()).singleElement().satisfies(r -> assertThat(r.excluded()).isTrue());
        assertThat(plan.resolves()).extracting(InspectionLifecycle.Resolve::id).containsExactly(2L);
        assertThat(plan.counts().get(COLUMN)).containsEntry(RunChange.EXCLUDED, 2).containsEntry(RunChange.NEW, 0);
    }

    @Test
    @DisplayName("근거 JSON : 바깥 모양(판·대상 ID·현재/기대 값·규칙 근거·표시 정보)은 규칙과 무관하게 같다")
    void evidenceShape() {
        InspectionItem item = new InspectionItem(new Finding(COLUMN, List.of(7L), "A", null, Map.of("termName", "회원"), "v1:x"),
                InspectionItem.ObjectType.COLUMN, 7, 3L, "S.T.A", Map.of("columnName", "A"));
        JsonNode json = JsonMapper.builder().build().readTree(InspectionRunService.evidence(item));
        assertThat(json.propertyNames()).containsExactly("fingerprintVersion", "targetIds", "actualValue", "expectedValue", "evidence", "details");
        assertThat(json.path("targetIds").get(0).asLong()).isEqualTo(7);
        assertThat(json.path("expectedValue").isNull()).isTrue();
        assertThat(json.path("evidence").path("termName").asString()).isEqualTo("회원");
    }

    private static StoredItem item(StandardizationRule rule, long target, String fingerprint) {
        return StoredItem.of(new InspectionItem(new Finding(rule, List.of(target), null, null, Map.of(), fingerprint), InspectionItem.ObjectType.COLUMN, target, null,
                "T." + target, Map.of()));
    }

    private static Existing existing(long id, String fingerprint, StandardizationRule rule, FindingStatus status, boolean excluded) {
        return new Existing(id, fingerprint, rule, status, excluded, null, null);
    }

    private static Existing resolved(long id, String fingerprint, StandardizationRule rule) {
        return new Existing(id, fingerprint, rule, FindingStatus.RESOLVED, false, EndReason.RULE_SATISFIED, ENDED);
    }
}
