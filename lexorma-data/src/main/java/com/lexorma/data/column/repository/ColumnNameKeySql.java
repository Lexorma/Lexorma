package com.lexorma.data.column.repository;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * 컬럼명 비교 값의 DB 식 (M7-U06 B3 · M7-U10, docs/completed/column-name-uniqueness-m7.md 15장) — 생성 컬럼 {@code TB_COL.COL_NM_CMPR_VL} 과 같은 식을 한 곳에 둔다 :
 * {@code upper(trim(x) collate "und-x-icu") collate "C"} (생성 컬럼과 같은 ICU 대문자, 비교는 컬럼과 같은 "C" — UX_COL_01 인덱스를 쓴다).
 * Java 키({@code ColumnNameKey.of})와 뜻은 같지만 Unicode 판이 달라 일부 글자에서 결과가 다를 수 있다 (문서 14.6) — DB 에 있는 이름과의 충돌은 이 식으로 그 DB 의 UNIQUE 와 같게 판단한다.
 * <p>
 * HQL 함수 {@code column_name_taken(tableId, name, excludeColumnId)} (1 = 그 테이블에 다른 컬럼이 같은 비교 값을 쓴다, 0 = 아니다) 를 등록한다 — 물리명 추천 목록의 상태·필터·요약이 쓴다.
 * 등록은 {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}.
 */
public final class ColumnNameKeySql implements FunctionContributor {

    public static final String TAKEN_FUNCTION = "column_name_taken";

    /** 이름 식 {@code arg} 의 비교 값 SQL */
    public static String key(String arg) {
        return "(upper(trim(" + arg + ") collate \"und-x-icu\") collate \"C\")";
    }

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        String pattern = "(case when exists (select 1 from TB_COL x_nm where x_nm.TBL_SN = ?1 and x_nm.COL_NM_CMPR_VL = " + key("?2")
                + " and (?3 is null or x_nm.COL_SN <> ?3)) then 1 else 0 end)";
        contributions.getFunctionRegistry().registerPattern(TAKEN_FUNCTION, pattern,
                contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.INTEGER));
    }
}
