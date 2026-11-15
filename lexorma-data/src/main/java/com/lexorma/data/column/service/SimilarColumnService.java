package com.lexorma.data.column.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 유사 컬럼 찾기 (AI-4 {@code findSimilarColumns}). 로그인 프로젝트의 물리 컬럼 중 기준과 이름·설명(논리명)·표준 용어·표준 도메인이 같거나 비슷한 것을
 * 정해진 규칙으로 점수를 매겨 돌려준다. 임베딩·벡터 검색·AI 판단을 쓰지 않는다 — 점수는 아래 규칙으로만 계산한다.
 * <p>기준 : 컬럼 ID(그 컬럼의 이름·설명·표준 용어·도메인. 그 컬럼은 결과에서 뺀다) 또는 검색어(물리명 MBR_SN · 한글 논리명/표준 용어명 회원일련번호).
 * 검색어가 표준 용어명·약어와 정확히 같으면 그 용어의 약어·도메인도 기준이 된다.
 * <p>점수 (기준과 비교, 가장 높은 하나 + 도메인 가점, 최대 100) :
 * <table>
 *   <tr><td>NAME_EXACT</td><td>100</td><td>물리명이 같다 (대소문자 무시)</td></tr>
 *   <tr><td>SAME_TERM</td><td>90</td><td>같은 표준 용어를 쓴다</td></tr>
 *   <tr><td>LOGICAL_NAME_EXACT</td><td>85</td><td>설명(논리명)이 같다 (공백·_ 를 빼고 비교 — 띄어쓰기 차이 무시)</td></tr>
 *   <tr><td>NAME_PARTIAL</td><td>60</td><td>한 물리명이 다른 물리명을 _ 단위로 포함한다 (UPPR_MBR_SN ⊃ MBR_SN)</td></tr>
 *   <tr><td>NAME_TOKENS</td><td>40 + 40×겹침률</td><td>물리명의 _ 단위 단어가 절반 이상 겹친다 (겹침률 = 공통 단어 수 / 합친 단어 수)</td></tr>
 *   <tr><td>LOGICAL_NAME_PARTIAL</td><td>45</td><td>한 설명이 다른 설명을 포함한다 (공백·_ 를 빼고 2자 이상)</td></tr>
 *   <tr><td>SAME_DOMAIN</td><td>+10 (단독이면 20)</td><td>표준 용어의 도메인이 같다</td></tr>
 * </table>
 * 후보는 프로젝트 조건이 붙은 조회로만 모은다 (이름·설명 부분 일치, 같은 용어 {@value #CANDIDATES}건, 같은 도메인 {@value #DOMAIN_CANDIDATES}건까지).
 * 결과는 점수 순, 같으면 테이블명·컬럼명 순.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SimilarColumnService {

    static final int CANDIDATES = 300;
    static final int DOMAIN_CANDIDATES = 100;
    static final int MAX_LIMIT = 20;
    private static final Pattern PHYSICAL = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    public enum Reason { NAME_EXACT, SAME_TERM, LOGICAL_NAME_EXACT, NAME_PARTIAL, NAME_TOKENS, LOGICAL_NAME_PARTIAL, SAME_DOMAIN }

    /** 비교 기준 (화면·AI 에 보이는 값) */
    public record Reference(Long columnId, String columnName, String logicalName, List<String> standardTerms, List<String> standardDomains) {
    }

    public record Match(ColumnInfo column, int score, List<Reason> reasons) {
    }

    public record Result(Reference reference, List<Match> matches, boolean truncated) {
    }

    private record Basis(Long columnId, String name, String logical, Set<Long> termIds, Set<Long> domainIds, Reference reference) {
    }

    private final ColumnInfoRepository columns;
    private final StandardTermRepository terms;

    public Result find(AccountDto account, Long columnId, String keyword, int limit) {
        Long projectId = account.getProjectId();
        Basis basis = columnId != null ? byColumn(projectId, columnId) : byKeyword(projectId, keyword);
        int max = Math.max(1, Math.min(MAX_LIMIT, limit));

        Map<Long, ColumnInfo> candidates = new LinkedHashMap<>();
        columns.findAll(candidates(projectId, basis), PageRequest.of(0, CANDIDATES, Sort.by("id"))).forEach(c -> candidates.putIfAbsent(c.getId(), c));
        if (!basis.domainIds().isEmpty()) {
            Specification<ColumnInfo> sameDomain = (root, query, cb) -> cb.and(cb.equal(root.get("projectId"), projectId),
                    root.get("standardTerms").get("standardDomain").get("id").in(basis.domainIds()));
            columns.findAll(sameDomain, PageRequest.of(0, DOMAIN_CANDIDATES, Sort.by("id"))).forEach(c -> candidates.putIfAbsent(c.getId(), c));
        }
        List<Match> matches = candidates.values().stream()
                .filter(c -> !Objects.equals(c.getId(), basis.columnId()) && Objects.equals(c.getProjectId(), projectId))
                .map(c -> score(basis, c))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(Match::score).reversed()
                        .thenComparing(m -> String.valueOf(m.column().getTableName()))
                        .thenComparing(m -> String.valueOf(m.column().getColumnName())))
                .toList();
        return new Result(basis.reference(), matches.stream().limit(max).toList(), matches.size() > max);
    }

    private Basis byColumn(Long projectId, Long columnId) {
        ColumnInfo column = columns.findAllByProjectIdAndId(projectId, columnId).stream().findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED, "현재 프로젝트에 그 컬럼이 없습니다."));
        StandardTerm term = column.getStandardTerms();
        Set<Long> termIds = term == null ? Set.of() : Set.of(term.getId());
        Set<Long> domainIds = term == null || term.getStandardDomain() == null ? Set.of() : Set.of(term.getStandardDomain().getId());
        return new Basis(column.getId(), upper(column.getColumnName()), blankToNull(column.getColumnDesc()), termIds, domainIds,
                new Reference(column.getId(), column.getColumnName(), blankToNull(column.getColumnDesc()), names(term == null ? List.of() : List.of(term)), domains(term == null ? List.of() : List.of(term))));
    }

    private Basis byKeyword(Long projectId, String keyword) {
        String value = keyword == null ? "" : keyword.strip();
        if (value.isEmpty()) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "컬럼 ID 나 검색어가 필요합니다.");
        boolean physical = PHYSICAL.matcher(value).matches();
        // 검색어가 표준 용어명·약어와 정확히 같으면 그 용어 (용어명은 단어를 _ 로 잇는다 : "회원 일련번호" → "회원_일련번호")
        Set<StandardTerm> found = new LinkedHashSet<>();
        found.addAll(terms.findAllByProjectIdAndCommonStandardTermNameIn(projectId, List.of(value, value.replaceAll("\\s+", "_"))));
        if (physical) found.addAll(terms.findAllByProjectIdAndCommonStandardTermAbbreviationIn(projectId, List.of(upper(value))));
        List<StandardTerm> matched = found.stream().limit(5).toList();
        String name = physical ? upper(value) : matched.stream().map(StandardTerm::getCommonStandardTermAbbreviation).filter(Objects::nonNull).map(SimilarColumnService::upper)
                .findFirst().orElse(null);
        String logical = physical ? matched.stream().map(StandardTerm::getCommonStandardTermName).findFirst().orElse(null) : value;
        Set<Long> termIds = matched.stream().map(StandardTerm::getId).collect(Collectors.toSet());
        Set<Long> domainIds = matched.stream().map(StandardTerm::getStandardDomain).filter(Objects::nonNull).map(StandardDomain::getId).collect(Collectors.toSet());
        return new Basis(null, name, logical, termIds, domainIds, new Reference(null, name, logical, names(matched), domains(matched)));
    }

    /** 이름·설명·용어 후보 (프로젝트 조건 필수) */
    private static Specification<ColumnInfo> candidates(Long projectId, Basis basis) {
        return (root, query, cb) -> {
            List<Predicate> any = new ArrayList<>();
            if (basis.name() != null) {
                any.add(cb.like(cb.upper(root.get("columnName")), "%" + like(basis.name()) + "%", '\\'));
                for (String token : tokens(basis.name())) {
                    if (token.length() >= 2) any.add(cb.like(cb.upper(root.get("columnName")), "%" + like(token) + "%", '\\'));
                }
            }
            if (basis.logical() != null && compact(basis.logical()).length() >= 2) {
                // 설명은 띄어쓰기가 제각각이다 : 공백·_ 를 뺀 값끼리 부분 일치 ("회원일련번호" ~ "회원 일련번호")
                var desc = cb.function("REPLACE", String.class, cb.function("REPLACE", String.class, root.get("columnDesc"), cb.literal(" "), cb.literal("")),
                        cb.literal("_"), cb.literal(""));
                any.add(cb.like(cb.upper(desc), "%" + like(compact(basis.logical())) + "%", '\\'));
            }
            if (!basis.termIds().isEmpty()) any.add(root.get("standardTerms").get("id").in(basis.termIds()));
            Predicate project = cb.equal(root.get("projectId"), projectId);
            return any.isEmpty() ? cb.disjunction() : cb.and(project, cb.or(any.toArray(Predicate[]::new)));
        };
    }

    /** 규칙대로 점수를 매긴다. 어느 규칙에도 맞지 않으면 null */
    private static Match score(Basis basis, ColumnInfo column) {
        List<Reason> reasons = new ArrayList<>();
        int best = 0;
        String name = upper(column.getColumnName());
        StandardTerm term = column.getStandardTerms();
        if (basis.name() != null && name != null) {
            if (name.equals(basis.name())) {
                best = Math.max(best, 100);
                reasons.add(Reason.NAME_EXACT);
            } else if (containsTokens(name, basis.name()) || containsTokens(basis.name(), name)) {
                best = Math.max(best, 60);
                reasons.add(Reason.NAME_PARTIAL);
            } else {
                double overlap = overlap(tokens(name), tokens(basis.name()));
                if (overlap >= 0.5) {
                    best = Math.max(best, 40 + (int) Math.round(40 * overlap));
                    reasons.add(Reason.NAME_TOKENS);
                }
            }
        }
        if (term != null && basis.termIds().contains(term.getId())) {
            best = Math.max(best, 90);
            reasons.add(Reason.SAME_TERM);
        }
        String logical = blankToNull(column.getColumnDesc());
        if (basis.logical() != null && logical != null) {
            String a = compact(logical);
            String b = compact(basis.logical());
            if (a.equals(b)) {
                best = Math.max(best, 85);
                reasons.add(Reason.LOGICAL_NAME_EXACT);
            } else if (b.length() >= 2 && a.length() >= 2 && (a.contains(b) || b.contains(a))) {
                best = Math.max(best, 45);
                reasons.add(Reason.LOGICAL_NAME_PARTIAL);
            }
        }
        boolean sameDomain = term != null && term.getStandardDomain() != null && basis.domainIds().contains(term.getStandardDomain().getId());
        if (sameDomain) reasons.add(Reason.SAME_DOMAIN);
        if (best == 0 && !sameDomain) return null;
        int score = best == 0 ? 20 : Math.min(100, best + (sameDomain ? 10 : 0));
        return new Match(column, score, List.copyOf(reasons));
    }

    /** 한 이름이 다른 이름을 _ 단위로 포함 (UPPR_MBR_SN 은 MBR_SN 을 포함, MBR_SNO 는 아니다) */
    private static boolean containsTokens(String whole, String part) {
        if (part.length() < 3 || whole.length() <= part.length()) return false;
        return ("_" + whole + "_").contains("_" + part + "_");
    }

    private static double overlap(List<String> a, List<String> b) {
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) return 0;
        long common = a.stream().distinct().filter(b::contains).count();
        return (double) common / union.size();
    }

    private static List<String> tokens(String name) {
        return name == null ? List.of() : Arrays.stream(name.split("_+")).filter(t -> !t.isBlank()).toList();
    }

    /** 설명 비교 : 공백·_ 를 빼고, 대소문자 무시 (띄어쓰기 차이를 같게 본다) */
    private static String compact(String value) {
        return value.replaceAll("[\\s_]+", "").toUpperCase(Locale.ROOT);
    }

    private static String like(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> names(List<StandardTerm> list) {
        return list.stream().map(StandardTerm::getCommonStandardTermName).filter(Objects::nonNull).distinct().toList();
    }

    private static List<String> domains(List<StandardTerm> list) {
        return list.stream().map(StandardTerm::getStandardDomain).filter(Objects::nonNull).map(StandardDomain::getCommonStandardDomainName)
                .filter(Objects::nonNull).distinct().toList();
    }
}
