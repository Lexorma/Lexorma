package com.lexorma.data.dictionary.service;

import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.quality.StandardNames;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 영문약어 → 표준 용어 후보 (M7-NAME-1, NAME-P01·P09 — docs/completed/physical-name-recommendation-m7.md 12장).
 * 컬럼 엑셀 업로드·컬럼 일괄 편집 붙여넣기가 같은 규칙으로 용어를 찾는다 : 같은 프로젝트에서 영문약어가 입력과 {@link StandardNames#sameColumnName} 으로 같은 용어
 * (앞뒤 공백 제거 + 대소문자 무시, 내부 공백·'_'·'-'·숫자는 그대로). 저장된 약어가 {@code user_id}·{@code USER_ID}·{@code User_Id} 어느 것이든 찾는다.
 * <p>
 * 후보를 고르지 않는다 — 여러 개면 모두 돌려주고(ID 순), 승인 여부·DB 반환 순서로 하나를 정하지 않는다. 0개·1개·여러 개의 처리는 부르는 쪽 정책이다
 * (엑셀 : 없음 {@code TERM_IS_NOT_DEFINED}, 여러 개 {@code AMBIGUOUS_STANDARD_TERM} 으로 업로드 전체 취소 / 붙여넣기 : 사용자가 고른다).
 * DB 조회는 후보를 좁히기만 하고({@link StandardTermRepository#findAbbreviationCandidates}), 일치는 여기서 자바로 다시 판정한다.
 */
@Component
@RequiredArgsConstructor
public class StandardTermAbbreviationLookup {

    private final StandardTermRepository repository;

    /** 입력마다 영문약어가 같은 용어 (ID 순, 없으면 빈 목록). 입력 순서·중복 입력을 그대로 키로 쓴다 (빈 입력은 빈 목록) */
    public Map<String, List<StandardTerm>> find(Long projectId, Collection<String> inputs) {
        Map<String, List<StandardTerm>> result = new LinkedHashMap<>();
        Set<String> lower = new LinkedHashSet<>();
        Set<String> upper = new LinkedHashSet<>();
        for (String input : inputs) {
            if (input == null) continue;
            result.put(input, new ArrayList<>());
            String name = StandardNames.expectedColumnName(input);
            if (name == null) continue;
            lower.add(name.toLowerCase(Locale.ROOT));
            upper.add(name.toUpperCase(Locale.ROOT));
        }
        if (lower.isEmpty()) return result;
        List<StandardTerm> candidates = repository.findAbbreviationCandidates(projectId, lower, upper);
        for (Map.Entry<String, List<StandardTerm>> entry : result.entrySet()) {
            String name = StandardNames.expectedColumnName(entry.getKey());
            if (name == null) continue;
            candidates.stream().filter(term -> StandardNames.sameColumnName(term.getCommonStandardTermAbbreviation(), name))
                    .sorted(Comparator.comparing(StandardTerm::getId)).forEach(entry.getValue()::add);
        }
        return result;
    }
}
