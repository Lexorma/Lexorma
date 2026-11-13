package com.lexorma.source.catalog.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.service.SourceIdentifiers.Kind;
import com.lexorma.source.connection.domain.entity.SourceConnection;

import java.math.BigDecimal;

/**
 * 원본 카탈로그에 저장하는 값의 규칙 : 한 건씩 쓰는 {@link SourceCatalogWriter} 와 수집 결과를 한 번에 적재하는 경로(AI-1c)가 같이 쓴다.
 * 값을 다듬거나 잘라서 저장하지 않는다 : 맞지 않으면 받지 않는다.
 */
public final class SourceCatalogRules {

    public static final int NAME_MAX = 255;
    public static final int RULE_MAX = 20;

    private SourceCatalogRules() {
    }

    /** 이름 : 앞뒤 공백도 원본의 일부일 수 있어 다듬지 않는다. 비었거나 255자를 넘으면 받지 않는다 (잘라서 저장하지 않는다) */
    public static String optional(Name name) {
        if (name == null || name.value() == null) return null;
        if (name.value().isEmpty() || name.value().length() > NAME_MAX) throw invalid("이름이 없거나 너무 깁니다.");
        return name.value();
    }

    public static String required(Name name) {
        String value = optional(name);
        if (value == null) throw invalid("이름이 없거나 너무 깁니다.");
        return value;
    }

    /** 제약조건·인덱스·관계의 이름 : 빈 값은 이름이 없는 것(null)으로 저장한다 */
    public static String limited(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() > NAME_MAX) throw invalid("이름이 없거나 너무 깁니다.");
        return value;
    }

    /** 삭제·수정 규칙 : 비었으면 null, 20자를 넘으면 받지 않는다 */
    public static String rule(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > RULE_MAX) throw invalid("삭제·수정 규칙이 올바르지 않습니다.");
        return value;
    }

    /** 인덱스 구성 요소의 정렬 : 비었으면 기본(null), 20자를 넘으면 받지 않는다 */
    public static String sort(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > RULE_MAX) throw invalid("인덱스 정렬 방식이 올바르지 않습니다.");
        return value;
    }

    /** 타입 표현·인덱스 방식처럼 255자 칸에 원본 그대로 넣는 값 */
    public static String text255(String value) {
        if (value != null && value.length() > NAME_MAX) throw invalid("값이 너무 깁니다.");
        return value;
    }

    /** 정밀도 : 저장 칸(NUMERIC(38,2))에 그대로 들어가는 값만 받는다 (반올림해 저장하면 다음 수집에서 바뀐 것으로 보인다) */
    public static BigDecimal precision(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 2 || stripped.precision() - stripped.scale() > 36) throw invalid("정밀도가 올바르지 않습니다.");
        return value;
    }

    public static String compare(SourceConnection connection, Kind kind, Name name) {
        String value = optional(name);
        return value == null ? null : SourceIdentifiers.compareName(connection.getDbType(), connection.getIdentifierComparison(), kind, value, name.quoted());
    }

    public static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_SOURCE_CATALOG, message, args);
    }
}
