package com.lexorma.data.inspection.service;

import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;

/**
 * 저장할 판정 결과 1건 (AI-6 FINAL, 메모리 개선) : 판정 묶음이 끝나면 {@link InspectionItem}(근거 Map 2개·대상 ID 목록·Finding)을 곧바로 이 형태로 바꾸고
 * 원래 객체는 버린다 — 근거는 저장할 JSON 문자열로 한 번만 만든다. 생명주기 판정과 저장이 쓰는 값만 둔다.
 *
 * @param evidence 근거 JSON ({@link EvidenceJson}, 저장 값 그대로)
 */
public record StoredItem(StandardizationRule rule, String fingerprint, InspectionItem.ObjectType objectType, long objectId, Long tableId, String targetName,
                         String evidence) {

    public static StoredItem of(InspectionItem item) {
        return new StoredItem(item.finding().rule(), item.finding().fingerprint(), item.objectType(), item.objectId(), item.tableId(), item.targetName(),
                EvidenceJson.of(item));
    }

    /** 같은 fingerprint 가 다시 나왔을 때만 비교하는 내용 : 규칙·대상 유형·대표 대상·근거 JSON (같은 문제가 두 번 나오면 모두 같다) */
    public boolean sameContent(StoredItem other) {
        return rule == other.rule && objectType == other.objectType && objectId == other.objectId && evidence.equals(other.evidence);
    }
}
