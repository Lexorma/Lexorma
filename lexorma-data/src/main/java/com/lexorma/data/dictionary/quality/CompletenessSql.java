package com.lexorma.data.dictionary.quality;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * 완전성 '누락' 의 SQL 판정 (AI-6 3차) : HQL {@code completeness_blank(x)} = 1 이면 null · 빈 문자열 · ASCII 공백류(U+0020·탭·LF·VT·FF·CR)만 있는 값.
 * 자바 {@link CompletenessRules#missing} 과 같은 기준이고, 그 기준은 기존 빈 이름 규칙 {@code ColumnNameKey.isBlankName}·DB {@code CK_COL_01}
 * ({@code trim(translate(…))}) 과 같다 — 대시보드의 '설명 없음' 실시간 수치가 저장 검사의 완전성 규칙과 같은 정의를 쓰게 한다.
 * 등록은 {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}.
 */
public final class CompletenessSql implements FunctionContributor {

    public static final String BLANK_FUNCTION = "completeness_blank";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        var integer = contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.INTEGER);
        contributions.getFunctionRegistry().registerPattern(BLANK_FUNCTION,
                "(case when ?1 is null or trim(translate(?1, chr(9) || chr(10) || chr(11) || chr(12) || chr(13), '     ')) = '' then 1 else 0 end)", integer);
    }
}
