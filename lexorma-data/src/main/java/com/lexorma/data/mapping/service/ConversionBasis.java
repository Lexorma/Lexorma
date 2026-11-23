package com.lexorma.data.mapping.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 변환 당시 그룹 상태와 지금 그룹 상태의 비교 (AC-P03, docs/completed/inheritance-strategy-ac-m7.md 8장). 순수 판정 : 조회·저장을 하지 않는다.
 * <p>
 * 변환 기록(TB_SBTYP_GROUP_CNVR)에는 구성원·분류 값이 없다. 대신 모델 변경 이력(append-only, 모든 모델 쓰기가 남긴다)에서
 * 변환 기록 CREATE 이력과 같은 작업이거나 그보다 앞선 그룹 이력의 마지막 변경 후 상태를 변환 당시 상태로 본다. 그런 이력이 없으면 비교하지 않는다 ({@link Result#unknown()}) —
 * 저장되지 않은 과거 상태를 추측하지 않는다. A→B→A 처럼 되돌린 변경은 차이가 없다 (지금 상태와 변환 당시 상태만 본다).
 * <p>
 * 비교 항목 : 구성원(서브타입 엔터티 ID), 배타·완전 여부, A형만 분류 속성 ID 와 구성원별 분류 값 (C형은 분류 컬럼이 없어 분류 속성·값이 물리 구조와 무관하다).
 */
public final class ConversionBasis {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ConversionBasis() {
    }

    /** 이력 1행 (그룹 또는 변환 기록) */
    public record History(Long id, Long targetId, String changeGroupId, LocalDateTime changedAt, String afterData) {
    }

    /** 지금 그룹 상태 */
    public record GroupState(Long groupId, String groupName, Boolean overlapping, Boolean total, Long classificationAttributeId, String classificationAttributeName,
                             Map<Long, String> values, Map<Long, String> names) {
    }

    /** 그룹 하나의 비교 결과 : unknown = 비교 기준 없음, changes = 바뀐 내용 (비어 있으면 변환 당시와 같다) */
    public record Result(boolean unknown, List<String> changes) {
        static Result notKnown() {
            return new Result(true, List.of());
        }
    }

    /** 변환 당시 그룹 상태 이력 : 변환 기록 CREATE 이력과 같은 작업이거나 그보다 앞선 그룹 이력 중 마지막 (시각, ID 순 — ID 만으로 순서를 보지 않는다) */
    static History basis(History conversion, List<History> groupHistory) {
        History best = null;
        for (History h : groupHistory) {
            boolean before = h.changeGroupId().equals(conversion.changeGroupId()) || h.changedAt().isBefore(conversion.changedAt())
                    || (h.changedAt().isEqual(conversion.changedAt()) && h.id() < conversion.id());
            if (!before || h.afterData() == null) continue;
            if (best == null || h.changedAt().isAfter(best.changedAt()) || (h.changedAt().isEqual(best.changedAt()) && h.id() > best.id())) best = h;
        }
        return best;
    }

    /** 그룹 하나 : 변환 기록 CREATE 이력(conversion), 그 그룹의 이력 전체(groupHistory), 지금 상태(current) */
    public static Result compare(SubtypeGroupConversion.Strategy strategy, History conversion, List<History> groupHistory, GroupState current) {
        if (conversion == null || current == null) return Result.notKnown();
        History basis = basis(conversion, groupHistory);
        if (basis == null) return Result.notKnown();
        JsonNode then;
        try {
            then = JSON.readTree(basis.afterData());
        } catch (RuntimeException e) {
            return Result.notKnown();
        }
        if (then == null || !then.has("subtypes")) return Result.notKnown();
        List<String> changes = new ArrayList<>();
        String group = current.groupName();
        Map<Long, String> thenValues = new LinkedHashMap<>();
        Map<Long, String> thenNames = new HashMap<>();
        for (JsonNode m : then.get("subtypes")) {
            if (m.get("subtypeEntityId") == null || m.get("subtypeEntityId").isNull()) return Result.notKnown();
            Long id = m.get("subtypeEntityId").asLong();
            thenValues.put(id, text(m.get("classificationValue")));
            thenNames.put(id, text(m.get("subtypeEntityName")));
        }
        for (Long id : current.values().keySet()) {
            if (!thenValues.containsKey(id)) changes.add(Messages.get("그룹 {0} 에 변환 뒤 구성원 {1} 이(가) 추가되었습니다.", group, current.names().get(id)));
        }
        for (Long id : thenValues.keySet()) {
            if (!current.values().containsKey(id)) {
                changes.add(Messages.get("그룹 {0} 에서 변환 뒤 구성원 {1} 이(가) 빠졌습니다.", group, Objects.requireNonNullElse(thenNames.get(id), String.valueOf(id))));
            }
        }
        if (!Objects.equals(bool(then.get("overlapping")), current.overlapping()) || !Objects.equals(bool(then.get("total")), current.total())) {
            changes.add(Messages.get("그룹 {0} 의 배타·완전 여부가 변환 뒤 바뀌었습니다.", group));
        }
        if (strategy == SubtypeGroupConversion.Strategy.SINGLE_TABLE) {
            Long thenAttribute = then.get("classificationAttributeId") == null || then.get("classificationAttributeId").isNull() ? null : then.get("classificationAttributeId").asLong();
            if (!Objects.equals(thenAttribute, current.classificationAttributeId())) {
                changes.add(Messages.get("그룹 {0} 의 분류 속성이 변환 뒤 {1} 에서 {2} (으)로 바뀌었습니다.", group,
                        Objects.requireNonNullElse(text(then.get("classificationAttributeName")), "-"), Objects.requireNonNullElse(current.classificationAttributeName(), "-")));
            }
            for (Map.Entry<Long, String> e : current.values().entrySet()) {
                if (!thenValues.containsKey(e.getKey())) continue;
                String before = thenValues.get(e.getKey());
                if (!Objects.equals(before, e.getValue())) {
                    changes.add(Messages.get("그룹 {0} 의 구성원 {1} 의 분류 값이 변환 뒤 {2} 에서 {3} (으)로 바뀌었습니다.", group, current.names().get(e.getKey()),
                            Objects.requireNonNullElse(before, "-"), Objects.requireNonNullElse(e.getValue(), "-")));
                }
            }
        }
        return new Result(false, changes);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Boolean bool(JsonNode node) {
        return node == null || node.isNull() ? null : node.asBoolean();
    }
}
