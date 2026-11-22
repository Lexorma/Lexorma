package com.lexorma.data.mapping.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.table.ddl.SqlIdentifierTokens;
import com.lexorma.data.table.ddl.SqlIdentifierTokens.Tokens;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 물리명 추천 일괄 적용의 이름 변경 안전성 판정 (M-7 물리명 추천 일괄 적용 1차, 순수 함수 — 조회 결과만 받아 판정하고 아무것도 바꾸지 않는다).
 * <ul>
 *   <li>이름 문자열 참조 : 이름 변경 서비스가 따라 고치지 않는 SQL 문자열(VIEW 정의·처리 전 VIEW 변경 요청의 정의, CHECK 식, 인덱스 식·조건)에
 *       옛 이름이 낱말로 나오면 적용 불가 ({@link SqlIdentifierTokens} — 낱말 분리라 보수적이다 : 나오면 참조로 본다).
 *       낱말을 확실히 나눌 수 없는 SQL(opaque)은 참조 여부를 판정할 수 없어 적용 불가 ({@link ApplyCode#REFERENCE_UNVERIFIABLE}).</li>
 *   <li>테이블 이름 변경 : 그 테이블을 직접 참조하는 VIEW (참조 객체 메타데이터 또는 정의에 테이블명 낱말), 그 테이블의 CHECK·인덱스 식에 테이블명 낱말.</li>
 *   <li>컬럼 이름 변경 : 그 컬럼을 쓰는 CHECK (구성 컬럼 또는 식의 낱말), 그 테이블의 인덱스 식·조건의 낱말,
 *       그 테이블을 직접·간접(VIEW 를 거쳐) 참조하는 VIEW 의 정의에 컬럼명 낱말·{@code *}·NATURAL 이 있으면 적용 불가.</li>
 *   <li>추천끼리 충돌 : 같은 이름 공간(컬럼 = 같은 테이블, 테이블 = 같은 스키마)의 서로 다른 대상이 같은 새 이름을 받는다 ({@link #duplicates}).
 *       1차는 전체 추천 후보(현재 이름과 추천명이 다른 매핑된 객체 전체)를 넣는다. 2차의 선택 집합으로 다시 부를 수 있게 후보 목록만 받는다.</li>
 * </ul>
 * 이름 맞바꾸기·연쇄 변경(A → B, B → A)은 대상 이름을 지금 다른 객체가 쓰므로 기존 충돌(CONFLICT)로 막힌다 — 지원하지 않는다.
 */
public final class RenameSafety {

    /** 관련 이름을 사유에 몇 개까지 적는가 (나머지는 related 에 모두 있다) */
    private static final int NAMES_IN_REASON = 3;

    /** 모델 VIEW 1개 : 지금 정의와 처리 전(REQUESTED) 변경 요청의 정의를 합친 낱말, 참조 객체 메타데이터의 테이블·VIEW ID */
    public record ViewText(Long viewId, String viewName, Tokens tokens, Set<Long> tableIds, Set<Long> viewIds) {
    }

    /** CHECK 제약 1개 (식과 구성 컬럼 ID) */
    public record CheckText(Long tableId, String constraintName, Tokens tokens, Set<Long> columnIds) {
    }

    /** 인덱스 1개의 식 컬럼·조건(부분 인덱스)을 합친 낱말 */
    public record IndexText(Long tableId, String indexName, Tokens tokens) {
    }

    /** 추천끼리 충돌 판정의 후보 : key = 대상 ID, namespace = 이름 공간 (컬럼 = 테이블 ID, 테이블 = 스키마 ID), name = 새 이름 */
    public record Candidate(Long key, Long namespace, String name) {
    }

    private final List<ViewText> views;
    private final Map<Long, List<CheckText>> checksByTable;
    private final Map<Long, List<IndexText>> indexesByTable;
    private final List<String> opaqueViews;

    public RenameSafety(Collection<ViewText> views, Collection<CheckText> checks, Collection<IndexText> indexes) {
        this.views = List.copyOf(views);
        this.checksByTable = checks.stream().collect(Collectors.groupingBy(CheckText::tableId));
        this.indexesByTable = indexes.stream().collect(Collectors.groupingBy(IndexText::tableId));
        this.opaqueViews = this.views.stream().filter(v -> v.tokens().opaque()).map(ViewText::viewName).toList();
    }

    public static Tokens tokens(DbType db, String sql) {
        return SqlIdentifierTokens.scan(db, sql);
    }

    // ------------------------------------------------------------------ 추천끼리 충돌

    /** 같은 이름 공간에서 같은 새 이름(앞뒤 공백 제거·대소문자 무시)을 받는 서로 다른 대상 → 그 대상 키 → 같은 이름을 받는 다른 대상 키 */
    public static Map<Long, List<Long>> duplicates(Collection<Candidate> candidates) {
        Map<List<Object>, Set<Long>> groups = new HashMap<>();
        for (Candidate c : candidates) {
            if (c.name() == null || c.name().isBlank()) continue;
            groups.computeIfAbsent(List.of(Objects.requireNonNullElse(c.namespace(), -1L), c.name().trim().toUpperCase(Locale.ROOT)), k -> new LinkedHashSet<>()).add(c.key());
        }
        Map<Long, List<Long>> result = new HashMap<>();
        for (Set<Long> keys : groups.values()) {
            if (keys.size() < 2) continue;
            for (Long key : keys) result.put(key, keys.stream().filter(k -> !k.equals(key)).toList());
        }
        return result;
    }

    // ------------------------------------------------------------------ 이름 문자열 참조

    /** 테이블 이름 변경의 참조 판정 (빈 목록 = 막는 참조 없음) */
    public List<ApplyIssue> tableRename(Long tableId, String currentName) {
        List<ApplyIssue> issues = new ArrayList<>();
        if (!opaqueViews.isEmpty()) issues.add(unverifiableViews());
        List<String> direct = views.stream().filter(v -> !v.tokens().opaque() && references(v, tableId, currentName)).map(ViewText::viewName).toList();
        if (!direct.isEmpty()) {
            issues.add(issue(ApplyCode.VIEW_REFERENCE, Messages.get("테이블을 참조하는 VIEW 가 있어 이름을 바꿀 수 없습니다 : {0}. VIEW 정의는 자동으로 고치지 않습니다.", shown(direct)), direct));
        }
        textIssues(issues, tableId, null, currentName);
        return issues;
    }

    /** 컬럼 이름 변경의 참조 판정 (빈 목록 = 막는 참조 없음) */
    public List<ApplyIssue> columnRename(Long tableId, String tableName, Long columnId, String currentName) {
        List<ApplyIssue> issues = new ArrayList<>();
        if (!opaqueViews.isEmpty()) issues.add(unverifiableViews());
        List<String> named = new ArrayList<>();
        List<String> wide = new ArrayList<>();
        for (ViewText view : reachingViews(tableId, tableName)) {
            if (view.tokens().opaque()) continue;
            if (view.tokens().mentions(currentName)) named.add(view.viewName());
            else if (view.tokens().star() || view.tokens().natural()) wide.add(view.viewName());
        }
        if (!named.isEmpty()) {
            issues.add(issue(ApplyCode.VIEW_REFERENCE, Messages.get("테이블을 참조하는 VIEW 의 정의에 컬럼명이 있어 이름을 바꿀 수 없습니다 : {0}. VIEW 정의는 자동으로 고치지 않습니다.", shown(named)), named));
        }
        if (!wide.isEmpty()) {
            issues.add(issue(ApplyCode.VIEW_REFERENCE, Messages.get("테이블을 참조하는 VIEW 의 정의에 * 또는 NATURAL 이 있어 컬럼 이름 변경의 영향을 판정할 수 없습니다 : {0}", shown(wide)), wide));
        }
        textIssues(issues, tableId, columnId, currentName);
        return issues;
    }

    /** 그 테이블을 직접 참조하는 VIEW + 그 VIEW 를 (거듭) 참조하는 VIEW */
    private List<ViewText> reachingViews(Long tableId, String tableName) {
        Set<Long> reached = new HashSet<>();
        Deque<ViewText> queue = new ArrayDeque<>();
        for (ViewText view : views) {
            if (references(view, tableId, tableName) && reached.add(view.viewId())) queue.add(view);
        }
        List<ViewText> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            ViewText target = queue.poll();
            result.add(target);
            for (ViewText view : views) {
                if (reached.contains(view.viewId())) continue;
                if (view.viewIds().contains(target.viewId()) || view.tokens().mentions(target.viewName())) {
                    reached.add(view.viewId());
                    queue.add(view);
                }
            }
        }
        return result;
    }

    private static boolean references(ViewText view, Long tableId, String tableName) {
        return view.tableIds().contains(tableId) || view.tokens().mentions(tableName);
    }

    /** 그 테이블의 CHECK·인덱스 식 (columnId 가 있으면 컬럼 이름 변경 — CHECK 구성 컬럼도 본다) */
    private void textIssues(List<ApplyIssue> issues, Long tableId, Long columnId, String currentName) {
        List<String> checks = new ArrayList<>();
        List<String> unverifiable = new ArrayList<>();
        for (CheckText check : checksByTable.getOrDefault(tableId, List.of())) {
            if (columnId != null && check.columnIds().contains(columnId) || !check.tokens().opaque() && check.tokens().mentions(currentName)) checks.add(check.constraintName());
            else if (check.tokens().opaque()) unverifiable.add(check.constraintName());
        }
        List<String> indexes = new ArrayList<>();
        for (IndexText index : indexesByTable.getOrDefault(tableId, List.of())) {
            if (index.tokens().opaque()) unverifiable.add(index.indexName());
            else if (index.tokens().mentions(currentName)) indexes.add(index.indexName());
        }
        if (!checks.isEmpty()) {
            issues.add(issue(ApplyCode.CHECK_REFERENCE, Messages.get("CHECK 제약조건이 이 이름을 쓰고 있어 바꿀 수 없습니다 : {0}. CHECK 식은 자동으로 고치지 않습니다.", shown(checks)), checks));
        }
        if (!indexes.isEmpty()) {
            issues.add(issue(ApplyCode.INDEX_REFERENCE, Messages.get("인덱스의 식 또는 조건이 이 이름을 쓰고 있어 바꿀 수 없습니다 : {0}. 인덱스 식은 자동으로 고치지 않습니다.", shown(indexes)), indexes));
        }
        if (!unverifiable.isEmpty()) {
            issues.add(issue(ApplyCode.REFERENCE_UNVERIFIABLE, Messages.get("CHECK 식 또는 인덱스 식을 낱말로 나눌 수 없어 참조 여부를 판정할 수 없습니다 : {0}", shown(unverifiable)), unverifiable));
        }
    }

    private ApplyIssue unverifiableViews() {
        return issue(ApplyCode.REFERENCE_UNVERIFIABLE, Messages.get("정의를 낱말로 나눌 수 없는 VIEW 가 있어 이름 참조 여부를 판정할 수 없습니다 : {0}", shown(opaqueViews)), opaqueViews);
    }

    static ApplyIssue issue(ApplyCode code, String reason, List<String> related) {
        return new ApplyIssue(code, reason, related.stream().distinct().toList());
    }

    /** 사유에 적는 이름 (앞에서 몇 개, 나머지는 …) */
    static String shown(List<String> names) {
        List<String> distinct = names.stream().distinct().toList();
        return distinct.stream().limit(NAMES_IN_REASON).collect(Collectors.joining(", ")) + (distinct.size() > NAMES_IN_REASON ? " …" : "");
    }
}
