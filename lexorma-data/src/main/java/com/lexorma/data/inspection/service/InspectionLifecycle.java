package com.lexorma.data.inspection.service;

import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.InspectionCodes.EndReason;
import com.lexorma.data.inspection.domain.InspectionCodes.FindingStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.RunChange;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 검사 결과 생명주기 판정 (AI-6 2차, 순수 함수 — DB 를 읽지 않는다). 한 번의 정상 실행이 찾은 결과와 저장된 결과를 fingerprint 로 맞춰
 * 무엇을 넣고·고치고·해결할지 정한다.
 * <ul>
 * <li>저장된 결과가 없다 → NEW (OPEN)</li>
 * <li>OPEN 결과를 다시 찾았다 → PERSISTING (이력 없음)</li>
 * <li>RESOLVED 결과를 다시 찾았다 → REOPENED (OPEN, 직전 해결 사유·일시를 이력으로 남긴다)</li>
 * <li>적용 규칙의 OPEN 결과를 찾지 못했다 → RESOLVED : 대상이 지워졌으면 TARGET_DELETED, 아니면 RULE_SATISFIED (AI6-D13)</li>
 * <li>적용하지 않은 규칙(끈 규칙)의 결과, 찾지 못한 RESOLVED 결과는 그대로 둔다 (AI6-D14)</li>
 * </ul>
 * 예외 여부는 판정에 쓰지 않는다 — 예외 처리한 문제가 다시 발견돼도 신규가 아니라 같은 결과의 유지다 (AI6-D24). 건수의 EXCLUDED 만 예외 여부를 본다.
 * 이 판정은 그 규칙을 <b>끝까지 판정한</b> 실행에서만 부른다 : 실패한 실행은 아무것도 해결하지 않는다 (AI6-D32·D33).
 */
public final class InspectionLifecycle {

    private InspectionLifecycle() {
    }

    /** 저장된 결과 1건 (판정에 필요한 값만) */
    public record Existing(long id, String fingerprint, StandardizationRule rule, FindingStatus status, boolean excluded,
                           EndReason endReason, LocalDateTime endedAt) {
    }

    public record Persist(long id, StoredItem item, boolean excluded) {
    }

    public record Reopen(long id, StoredItem item, boolean excluded, EndReason previousReason, LocalDateTime previousEndedAt) {
    }

    public record Resolve(long id, StandardizationRule rule, EndReason reason) {
    }

    /**
     * @param inserts  새 결과
     * @param persists 다시 찾은 OPEN 결과
     * @param reopens  다시 찾은 RESOLVED 결과
     * @param resolves 찾지 못한 OPEN 결과 (적용 규칙만)
     * @param counts   규칙별 변화 건수 (적용 규칙마다, 0 포함)
     * @param duplicates 같은 실행에서 같은 fingerprint 가 두 번 이상 나온 수 (처음 것만 쓴다)
     */
    public record Plan(List<StoredItem> inserts, List<Persist> persists, List<Reopen> reopens, List<Resolve> resolves,
                       Map<StandardizationRule, Map<RunChange, Integer>> counts, int duplicates) {

        public int found(StandardizationRule rule) {
            Map<RunChange, Integer> byChange = counts.get(rule);
            return byChange == null ? 0 : byChange.get(RunChange.NEW) + byChange.get(RunChange.PERSISTING) + byChange.get(RunChange.REOPENED);
        }
    }

    /** 한 실행 안에서 같은 fingerprint 가 <b>다른 내용</b>으로 나왔다 — 서로 다른 문제를 한 결과로 합쳐 하나를 잃지 않도록 그 묶음을 실패시킨다 (AI6-2-05) */
    public static final class FingerprintCollisionException extends IllegalStateException {
        private final StandardizationRule rule;

        public FingerprintCollisionException(StandardizationRule rule) {
            super("같은 fingerprint 의 서로 다른 결과 : " + rule);
            this.rule = rule;
        }

        public StandardizationRule rule() {
            return rule;
        }
    }

    /**
     * 같은 실행에서 같은 fingerprint 가 다시 나오면 : 내용(규칙·대상·근거)이 같으면 단순 중복(처음 것만 쓴다 — {@link #plan} 의 duplicates),
     * 다르면 충돌이다. seen 은 이 실행에서 앞서 확인한 fingerprint → 결과이며, 충돌이 없을 때만 이번 결과를 더한다 (충돌이면 아무것도 더하지 않는다).
     * 내용 비교는 같은 fingerprint 가 다시 나왔을 때만 한다 (FINAL : 결과마다 비교용 문자열을 만들어 들고 있던 것을 없앴다 — 판정은 같다).
     *
     * @return 이번 결과 중 단순 중복 수
     * @throws FingerprintCollisionException 내용이 다른 결과가 같은 fingerprint 를 가졌다
     */
    public static int checkFingerprints(Collection<StoredItem> items, Map<String, StoredItem> seen) {
        Map<String, StoredItem> added = new HashMap<>();
        int duplicates = 0;
        for (StoredItem item : items) {
            String fingerprint = item.fingerprint();
            StoredItem before = added.get(fingerprint);
            if (before == null) before = seen.get(fingerprint);
            if (before == null) {
                added.put(fingerprint, item);
            } else if (before.sameContent(item)) {
                duplicates++;
            } else {
                throw new FingerprintCollisionException(item.rule());
            }
        }
        seen.putAll(added);
        return duplicates;
    }

    /**
     * @param activeRules 이 실행에 적용한 규칙 (실행 시작 때 확정, AI6-D28)
     * @param found       이 실행이 찾은 결과 (적용 규칙의 것만)
     * @param existing    저장된 결과 (프로젝트 전체)
     * @param deleted     찾지 못한 OPEN 결과의 대상이 지워졌는지 (결과 ID → true 면 TARGET_DELETED)
     */
    public static Plan plan(Set<StandardizationRule> activeRules, Collection<StoredItem> found, Collection<Existing> existing,
                            Function<Existing, Boolean> deleted) {
        Map<String, Existing> byFingerprint = HashMap.newHashMap(existing.size());
        existing.forEach(row -> byFingerprint.put(row.fingerprint(), row));
        // 규칙 × 변화 건수 : 더하는 동안은 int 배열 (결과마다 Integer 를 만들지 않는다), 끝에 Map 으로 바꾼다
        Map<StandardizationRule, int[]> tally = new EnumMap<>(StandardizationRule.class);
        for (StandardizationRule rule : activeRules) tally.put(rule, new int[RunChange.values().length]);

        List<StoredItem> inserts = new ArrayList<>();
        List<Persist> persists = new ArrayList<>();
        List<Reopen> reopens = new ArrayList<>();
        Set<String> seen = HashSet.newHashSet(found.size());
        int duplicates = 0;
        for (StoredItem item : found) {
            StandardizationRule rule = item.rule();
            if (!activeRules.contains(rule)) {
                throw new IllegalArgumentException("적용하지 않은 규칙의 결과입니다 : " + rule);
            }
            String fingerprint = item.fingerprint();
            if (!seen.add(fingerprint)) {
                duplicates++;
                continue;
            }
            Existing row = byFingerprint.get(fingerprint);
            RunChange change;
            if (row == null) {
                inserts.add(item);
                change = RunChange.NEW;
            } else if (row.status() == FindingStatus.OPEN) {
                persists.add(new Persist(row.id(), item, row.excluded()));
                change = RunChange.PERSISTING;
            } else {
                reopens.add(new Reopen(row.id(), item, row.excluded(), row.endReason(), row.endedAt()));
                change = RunChange.REOPENED;
            }
            tally.get(rule)[change.ordinal()]++;
            if (row != null && row.excluded()) tally.get(rule)[RunChange.EXCLUDED.ordinal()]++;
        }

        List<Resolve> resolves = new ArrayList<>();
        for (Existing row : existing) {
            if (row.status() != FindingStatus.OPEN || !activeRules.contains(row.rule()) || seen.contains(row.fingerprint())) continue;
            EndReason reason = Boolean.TRUE.equals(deleted.apply(row)) ? EndReason.TARGET_DELETED : EndReason.RULE_SATISFIED;
            resolves.add(new Resolve(row.id(), row.rule(), reason));
            tally.get(row.rule())[(reason == EndReason.TARGET_DELETED ? RunChange.TARGET_DELETED : RunChange.RULE_SATISFIED).ordinal()]++;
        }
        Map<StandardizationRule, Map<RunChange, Integer>> counts = new EnumMap<>(StandardizationRule.class);
        tally.forEach((rule, values) -> {
            Map<RunChange, Integer> byChange = new EnumMap<>(RunChange.class);
            for (RunChange change : RunChange.values()) byChange.put(change, values[change.ordinal()]);
            counts.put(rule, byChange);
        });
        return new Plan(inserts, persists, reopens, resolves, counts, duplicates);
    }
}
