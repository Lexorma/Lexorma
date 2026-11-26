package com.lexorma.data.column.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB 기준 컬럼명 충돌 조회 (M7-U10, docs/completed/column-name-uniqueness-m7.md 15장) : 후보 이름의 비교 값을 그 DB 의 식({@link ColumnNameKeySql})으로 계산하고
 * 저장된 {@code COL_NM_CMPR_VL} 과 비교한다 (UX_COL_01 인덱스). 물리명 추천 목록의 충돌 사유·추천끼리 겹침과 일괄 변경 미리보기·적용이 같은 기준을 쓴다.
 * 후보 수만큼 바인딩 변수를 쓰는 한 문장이다 (행마다 조회하지 않는다).
 */
@Repository
public class ColumnNameDbKeys {

    /** 같은 비교 값을 이미 쓰는 컬럼 : 후보 이름 · 테이블 · 컬럼 · 이름 · 매핑된 속성명 */
    public record Taken(String candidate, Long tableId, Long columnId, String columnName, String mappedAttributeName) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    private static String values(int size) {
        StringBuilder sql = new StringBuilder("(values ");
        for (int i = 0; i < size; i++) sql.append(i == 0 ? "" : ", ").append("(cast(?").append(i + 1).append(" as varchar(1000)))");
        return sql.append(") v(NM)").toString();
    }

    /** 후보 이름 → 그 DB 의 비교 값 */
    public Map<String, String> keys(Collection<String> names) {
        List<String> list = names.stream().filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, String> result = new LinkedHashMap<>();
        if (list.isEmpty()) return result;
        Query query = entityManager.createNativeQuery("select v.NM, " + ColumnNameKeySql.key("v.NM") + " from " + values(list.size()));
        for (int i = 0; i < list.size(); i++) query.setParameter(i + 1, list.get(i));
        for (Object row : query.getResultList()) {
            Object[] r = (Object[]) row;
            result.put((String) r[0], (String) r[1]);
        }
        return result;
    }

    /** tableIds 의 컬럼 중 비교 값이 후보 이름의 비교 값과 같은 것 (자기 자신 제외는 호출하는 쪽이 컬럼 ID 로) */
    public List<Taken> taken(Collection<Long> tableIds, Collection<String> names) {
        List<String> list = names.stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> tables = tableIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (list.isEmpty() || tables.isEmpty()) return List.of();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) in.append(i == 0 ? "" : ", ").append('?').append(list.size() + i + 1);
        Query query = entityManager.createNativeQuery("select v.NM, x.TBL_SN, x.COL_SN, x.COL_NM, a.ATRB_NM from " + values(list.size())
                + " join TB_COL x on x.COL_NM_CMPR_VL = " + ColumnNameKeySql.key("v.NM") + " and x.TBL_SN in (" + in + ")"
                + " left join TB_ATRB_COL_MPNG m on m.COL_SN = x.COL_SN left join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = m.LGCL_ATRB_SN");
        for (int i = 0; i < list.size(); i++) query.setParameter(i + 1, list.get(i));
        for (int i = 0; i < tables.size(); i++) query.setParameter(list.size() + i + 1, tables.get(i));
        List<Taken> result = new ArrayList<>();
        for (Object row : query.getResultList()) {
            Object[] r = (Object[]) row;
            result.add(new Taken((String) r[0], ((Number) r[1]).longValue(), ((Number) r[2]).longValue(), (String) r[3], (String) r[4]));
        }
        return result;
    }
}
