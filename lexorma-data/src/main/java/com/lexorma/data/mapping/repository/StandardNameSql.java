package com.lexorma.data.mapping.repository;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * 표준화 '일치' 판정의 DB 식 (M7-NAME-1, NAME-P01 — docs/completed/physical-name-recommendation-m7.md 12장). 판정 기준은 자바
 * {@code StandardNames.sameColumnName} (앞뒤 {@code String.trim()} + {@code equalsIgnoreCase}) 하나이고, SQL 은 그 결과가 <b>확실히 같은</b> 범위에서만 쓴다.
 * <ul>
 *   <li>{@code standard_name_plain(x)} : 1 = x 가 null 이거나 인쇄 가능한 ASCII(U+0020~U+007E)로만 이뤄졌다. 이 범위에서는 SQL {@code trim}(공백만 제거)과 자바 {@code trim}
 *       (U+0020 이하 모두 제거)이 같고, 아래 ASCII 대문자와 {@code equalsIgnoreCase} 가 같다. 탭·줄바꿈·비 ASCII 글자가 하나라도 있으면 0 — 그 행은 서비스가 자바로 판정한다</li>
 *   <li>{@code standard_name_upper(x)} : ASCII a~z 만 A~Z 로. 그냥 {@code upper} 는 DB ctype 로캘(터키어 i → İ)과
 *       Unicode 확장 대문자(ß → SS)를 따르므로 쓰지 않는다. {@code upper(x collate "C")} ("C" 정렬 규칙의 대소문자 변환은 ASCII 글자만 — {@code translate} 는
 *       같은 결과지만 9,000행 기준 약 5배 느렸다)</li>
 * </ul>
 *   <li>{@code standard_name_trim(x)} : 자바 {@code String.trim()} 과 같은 뜻 — 앞뒤의 U+0020 이하 글자(공백·탭·줄바꿈 등 제어 문자)만 지운다. 안쪽 글자와
 *       U+0020 을 넘는 Unicode 공백(U+00A0·U+3000 …)은 그대로 (M7-NAME-1 최종 보완 NAME-F01 — 표준 용어 약어 후보 조회). SQL {@code TRIM} 은 공백만 지우므로 쓰지 않는다.
 *       정규식 {@code ^[\x01-\x20]+|[\x01-\x20]+$} 를 빈 문자열로 (U+0000 은 PostgreSQL text 에 저장될 수 없다)</li>
 * 컬럼명 중복(DB UNIQUE) 판정은 이 식이 아니라 {@code ColumnNameKeySql}·{@code COL_NM_CMPR_VL} 이다 (NAME-P02 — 섞지 않는다).
 * 등록은 {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}.
 */
public final class StandardNameSql implements FunctionContributor {

    public static final String PLAIN_FUNCTION = "standard_name_plain";
    public static final String UPPER_FUNCTION = "standard_name_upper";
    public static final String TRIM_FUNCTION = "standard_name_trim";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        // PostgreSQL 의 '$' 는 문자열 끝만 맞는다 (줄바꿈 감지 모드가 아니다)
        String plain = "(case when ?1 is null or ?1 ~ '^[ -~]*$' then 1 else 0 end)";
        var integer = contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.INTEGER);
        var string = contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING);
        contributions.getFunctionRegistry().registerPattern(PLAIN_FUNCTION, plain, integer);
        contributions.getFunctionRegistry().registerPattern(UPPER_FUNCTION, "upper((?1) collate \"C\")", string);
        // regexp_replace 는 'g' 가 있어야 모두 바꾼다
        contributions.getFunctionRegistry().registerPattern(TRIM_FUNCTION, "regexp_replace(?1, '^[\\x01-\\x20]+|[\\x01-\\x20]+$', '', 'g')", string);
    }
}
