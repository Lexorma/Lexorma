package com.lexorma.data.logical.service;

import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifier;
import com.lexorma.data.logical.domain.entity.LogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.LogicalRelationship;
import com.lexorma.data.logical.domain.entity.LogicalRelationshipAttribute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 논리 관계 속성 짝의 상태 판정 (M-7 보강 3차, P1·P4·P5 · M7-AK-D09) : 저장 화면·논리 ERD·물리 생성 미리보기가 같은 판정을 쓴다. 값만 보고 아무것도 고치지 않는다.
 * <ul>
 *   <li>status : NONE(짝 없음) · PARENT_KEY_MISSING(부모 주 식별자 없음) · PARENT_KEY_MISMATCH(짝의 부모 속성이 지금 부모의 어느 식별자와도 맞지 않음 — 식별자를 바꾼 뒤,
 *       보조 식별자의 일부·여러 식별자의 혼합) · PARTIAL(부모 주 식별자의 일부만) · COMPLETE(부모 주 식별자 전체 또는 부모 자체의 보조 식별자 전체). COMPLETE 만 완성된 FK 근거다.
 *       참조 키 해석은 {@link ReferenceKeys} (keyType PRIMARY · ALTERNATE, 같은 구성의 보조 식별자는 식별자 ID 가 가장 작은 것)</li>
 *   <li>warnings : ORDER_DIFFERS(짝 순서 ≠ 참조 식별자 순서) · SELF_KEY_REUSED(자기 참조인데 자식 속성이 부모 키 속성 자신)
 *       · IDENTIFYING_CHILD_NOT_KEY(식별 관계인데 자식 속성이 자식 주 식별자에 없음, P4) · REQUIRED_CHILD_NULLABLE(부모가 필수인 관계인데 자식 속성이 필수가 아님, P5 — NULL 여부는 속성 기준)</li>
 * </ul>
 */
public final class LogicalRelationshipPairs {

    private LogicalRelationshipPairs() {
    }

    /** 엔터티 → 유효 주 식별자(속성 ID, 식별자 순서) · 참조할 수 있는 보조 식별자 (상속 참여 부모면 빈 목록 — M7-AK-D16) */
    public interface KeyLookup {
        List<Long> primary(LogicalEntity entity);

        List<ReferenceKeys.Key> alternates(LogicalEntity entity);

        default Long primaryIdentifierId(LogicalEntity entity) {
            return null;
        }

        default String primaryIdentifierName(LogicalEntity entity) {
            return null;
        }
    }

    /** 엔터티의 주 식별자 속성 (식별자 순서, 같으면 식별자 속성 ID). 주 식별자가 없으면 빈 목록 */
    public static List<LogicalAttribute> primaryKey(LogicalEntity entity) {
        return entity.getIdentifiers().stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst()
                .map(LogicalRelationshipPairs::orderedAttributes)
                .orElse(List.of());
    }

    /** 주 식별자 속성 ID (식별자 순서) */
    public static List<Long> primaryKeyIds(LogicalEntity entity) {
        return primaryKey(entity).stream().map(LogicalAttribute::getId).toList();
    }

    /** 식별자 구성 속성 (식별자 순서, 같으면 식별자 속성 ID) */
    public static List<LogicalAttribute> orderedAttributes(LogicalIdentifier identifier) {
        return identifier.getAttributes().stream()
                .sorted(Comparator.comparing(LogicalIdentifierAttribute::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LogicalIdentifierAttribute::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(LogicalIdentifierAttribute::getAttribute).distinct().toList();
    }

    /** 엔터티 자체의 보조 식별자 (식별자 ID 순) */
    public static List<ReferenceKeys.Key> alternateKeys(LogicalEntity entity) {
        return entity.getIdentifiers().stream().filter(i -> !Boolean.TRUE.equals(i.getIsPrimary()))
                .sorted(Comparator.comparing(LogicalIdentifier::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(i -> new ReferenceKeys.Key(i.getId(), i.getIdentifierName(), false, orderedAttributes(i).stream().map(LogicalAttribute::getId).toList()))
                .toList();
    }

    /** 이미 읽은 엔터티만으로 판정 (상속 계층을 모르는 곳 : 보조 식별자 후보 = 엔터티 자체의 것) */
    public static LogicalModelDto.PairState evaluate(LogicalRelationship relationship, List<LogicalRelationshipAttribute> rows) {
        return evaluate(relationship, rows, LogicalRelationshipPairs::primaryKeyIds);
    }

    /** primaryKeys : 엔터티 → 주 식별자 속성 ID (이미 읽은 식별자로 만든 조회 — 엔터티마다 식별자를 다시 읽지 않는다). 보조 식별자 후보 = 엔터티 자체의 것 */
    public static LogicalModelDto.PairState evaluate(LogicalRelationship relationship, List<LogicalRelationshipAttribute> rows, Function<LogicalEntity, List<Long>> primaryKeys) {
        return evaluate(relationship, rows, new KeyLookup() {
            @Override
            public List<Long> primary(LogicalEntity entity) {
                return primaryKeys.apply(entity);
            }

            @Override
            public List<ReferenceKeys.Key> alternates(LogicalEntity entity) {
                return alternateKeys(entity);
            }
        });
    }

    public static LogicalModelDto.PairState evaluate(LogicalRelationship relationship, List<LogicalRelationshipAttribute> rows, KeyLookup keys) {
        List<LogicalRelationshipAttribute> pairs = rows.stream()
                .sorted(Comparator.comparing(LogicalRelationshipAttribute::getSortOrder).thenComparing(LogicalRelationshipAttribute::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Long> parentKey = keys.primary(relationship.getSourceEntity());
        List<LogicalModelDto.AttributePair> dto = pairs.stream().map(p -> new LogicalModelDto.AttributePair(p.getParentAttribute().getId(), p.getParentAttribute().getAttributeName(),
                p.getChildAttribute().getId(), p.getChildAttribute().getAttributeName(), p.getSortOrder())).toList();

        List<Long> parents = pairs.stream().map(p -> p.getParentAttribute().getId()).toList();
        ReferenceKeys.Resolution resolution = ReferenceKeys.resolve(parents, keys.primaryIdentifierId(relationship.getSourceEntity()),
                keys.primaryIdentifierName(relationship.getSourceEntity()), parentKey, keys.alternates(relationship.getSourceEntity()));

        Set<String> warnings = new LinkedHashSet<>();
        if (resolution.key() != null) {
            List<Long> keyOrder = resolution.key().attributeIds();
            List<Long> expected = keyOrder.stream().filter(parents::contains).toList();
            if (!expected.equals(parents)) warnings.add("ORDER_DIFFERS");
        }
        boolean self = Objects.equals(relationship.getSourceEntity().getId(), relationship.getTargetEntity().getId());
        Set<Long> childKey = new LinkedHashSet<>(keys.primary(relationship.getTargetEntity()));
        for (LogicalRelationshipAttribute pair : pairs) {
            if (self && pair.getParentAttribute().getId().equals(pair.getChildAttribute().getId())) warnings.add("SELF_KEY_REUSED");
            if (Boolean.TRUE.equals(relationship.getIsIdentifying()) && !childKey.contains(pair.getChildAttribute().getId())) warnings.add("IDENTIFYING_CHILD_NOT_KEY");
            if (relationship.getSourceMin() != null && relationship.getSourceMin() >= 1 && !Boolean.TRUE.equals(pair.getChildAttribute().getIsRequired())) {
                warnings.add("REQUIRED_CHILD_NULLABLE");
            }
        }
        ReferenceKeys.Key key = resolution.key();
        return new LogicalModelDto.PairState(resolution.status(), new ArrayList<>(warnings), dto, resolution.keySize(parentKey),
                resolution.type() == null ? null : resolution.type().name(), key == null ? null : key.identifierId(), key == null ? null : key.identifierName());
    }
}
