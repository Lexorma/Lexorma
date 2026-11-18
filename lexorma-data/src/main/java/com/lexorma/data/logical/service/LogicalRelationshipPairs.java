package com.lexorma.data.logical.service;

import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.LogicalRelationship;
import com.lexorma.data.logical.domain.entity.LogicalRelationshipAttribute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 논리 관계 속성 짝의 상태 판정 (M-7 보강 3차, P1·P4·P5) : 저장 화면·논리 ERD·물리 생성 미리보기가 같은 판정을 쓴다. 값만 보고 아무것도 고치지 않는다.
 * <ul>
 *   <li>status : NONE(짝 없음) · PARENT_KEY_MISSING(부모 주 식별자 없음) · PARENT_KEY_MISMATCH(짝의 부모 속성이 지금 부모 주 식별자에 없음 — 식별자를 바꾼 뒤)
 *       · PARTIAL(부모 주 식별자의 일부만) · COMPLETE(부모 주 식별자 전체). COMPLETE 만 완성된 FK 근거다</li>
 *   <li>warnings : ORDER_DIFFERS(짝 순서 ≠ 부모 주 식별자 순서) · SELF_KEY_REUSED(자기 참조인데 자식 속성이 부모 키 속성 자신)
 *       · IDENTIFYING_CHILD_NOT_KEY(식별 관계인데 자식 속성이 자식 주 식별자에 없음, P4) · REQUIRED_CHILD_NULLABLE(부모가 필수인 관계인데 자식 속성이 필수가 아님, P5 — NULL 여부는 속성 기준)</li>
 * </ul>
 */
public final class LogicalRelationshipPairs {

    private LogicalRelationshipPairs() {
    }

    /** 엔터티의 주 식별자 속성 (식별자 순서, 같으면 식별자 속성 ID). 주 식별자가 없으면 빈 목록 */
    public static List<LogicalAttribute> primaryKey(LogicalEntity entity) {
        return entity.getIdentifiers().stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst()
                .map(i -> i.getAttributes().stream()
                        .sorted(Comparator.comparing(LogicalIdentifierAttribute::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(LogicalIdentifierAttribute::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(LogicalIdentifierAttribute::getAttribute).distinct().toList())
                .orElse(List.of());
    }

    /** 주 식별자 속성 ID (식별자 순서) */
    public static List<Long> primaryKeyIds(LogicalEntity entity) {
        return primaryKey(entity).stream().map(LogicalAttribute::getId).toList();
    }

    public static LogicalModelDto.PairState evaluate(LogicalRelationship relationship, List<LogicalRelationshipAttribute> rows) {
        return evaluate(relationship, rows, LogicalRelationshipPairs::primaryKeyIds);
    }

    /** primaryKeys : 엔터티 → 주 식별자 속성 ID (이미 읽은 식별자로 만든 조회 — 엔터티마다 식별자를 다시 읽지 않는다) */
    public static LogicalModelDto.PairState evaluate(LogicalRelationship relationship, List<LogicalRelationshipAttribute> rows,
                                                     java.util.function.Function<LogicalEntity, List<Long>> primaryKeys) {
        List<LogicalRelationshipAttribute> pairs = rows.stream()
                .sorted(Comparator.comparing(LogicalRelationshipAttribute::getSortOrder).thenComparing(LogicalRelationshipAttribute::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Long> parentKey = primaryKeys.apply(relationship.getSourceEntity());
        List<LogicalModelDto.AttributePair> dto = pairs.stream().map(p -> new LogicalModelDto.AttributePair(p.getParentAttribute().getId(), p.getParentAttribute().getAttributeName(),
                p.getChildAttribute().getId(), p.getChildAttribute().getAttributeName(), p.getSortOrder())).toList();

        String status;
        List<Long> parents = pairs.stream().map(p -> p.getParentAttribute().getId()).toList();
        if (pairs.isEmpty()) status = "NONE";
        else if (parentKey.isEmpty()) status = "PARENT_KEY_MISSING";
        else if (!parentKey.containsAll(parents)) status = "PARENT_KEY_MISMATCH";
        else status = parents.size() == parentKey.size() ? "COMPLETE" : "PARTIAL";

        Set<String> warnings = new LinkedHashSet<>();
        if (!pairs.isEmpty() && parentKey.containsAll(parents)) {
            List<Long> expected = parentKey.stream().filter(parents::contains).toList();
            if (!expected.equals(parents)) warnings.add("ORDER_DIFFERS");
        }
        boolean self = Objects.equals(relationship.getSourceEntity().getId(), relationship.getTargetEntity().getId());
        Set<Long> childKey = new LinkedHashSet<>(primaryKeys.apply(relationship.getTargetEntity()));
        for (LogicalRelationshipAttribute pair : pairs) {
            if (self && pair.getParentAttribute().getId().equals(pair.getChildAttribute().getId())) warnings.add("SELF_KEY_REUSED");
            if (Boolean.TRUE.equals(relationship.getIsIdentifying()) && !childKey.contains(pair.getChildAttribute().getId())) warnings.add("IDENTIFYING_CHILD_NOT_KEY");
            if (relationship.getSourceMin() != null && relationship.getSourceMin() >= 1 && !Boolean.TRUE.equals(pair.getChildAttribute().getIsRequired())) {
                warnings.add("REQUIRED_CHILD_NULLABLE");
            }
        }
        return new LogicalModelDto.PairState(status, new ArrayList<>(warnings), dto, parentKey.size());
    }
}
