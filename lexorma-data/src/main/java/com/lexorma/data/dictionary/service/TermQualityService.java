package com.lexorma.data.dictionary.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.Group;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.Member;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.Search;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.Summary;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermRow;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.dictionary.quality.TermDuplicateRules;
import com.lexorma.data.dictionary.quality.TermDuplicateRules.TermGroup;
import com.lexorma.data.dictionary.repository.DictionaryUsageRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 표준 용어 중복·유사어 진단 (M-5 잔여, 조회 전용). 판정은 {@link TermDuplicateRules}, 이 서비스는 로그인 프로젝트의 사전을 읽어 문구·사용 컬럼 수를 붙인다.
 * 용어·단어를 병합·삭제·이름 변경하지 않고, 등록·수정 검증도 바꾸지 않는다 (D5). 대시보드는 이 진단을 부르지 않는다 (D2).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TermQualityService {

    private static final Map<String, Comparator<Group>> SORTS = Map.of(
            "type", Comparator.comparing(Group::type),
            "severity", Comparator.comparing(Group::severity),
            "groupKey", Comparator.comparing(Group::groupKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)),
            "memberCount", Comparator.comparingInt(Group::memberCount));

    /** 기본 순서 : 규칙 순 → 묶은 값 → 첫 용어 ID */
    private static final Comparator<Group> DEFAULT_ORDER = Comparator.comparing(Group::type)
            .thenComparing(Group::groupKey, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(group -> group.members().get(0).termId());

    private final DictionaryUsageRepository usageRepository;
    private final StandardTermRepository termRepository;

    public Summary selectSummary(AccountDto accountDto) {
        Diagnosis diagnosis = diagnose(accountDto.getProjectId());
        Map<StandardizationRule, Long> groups = new EnumMap<>(StandardizationRule.class);
        for (StandardizationRule rule : StandardizationRule.values()) {
            if (rule.targetType() == StandardizationRule.TargetType.TERM) {
                groups.put(rule, diagnosis.groups().stream().filter(group -> group.finding().rule() == rule).count());
            }
        }
        return new Summary(diagnosis.terms().size(),
                diagnosis.groups().stream().flatMap(group -> group.termIds().stream()).distinct().count(),
                groups, severity(diagnosis, Severity.ERROR), severity(diagnosis, Severity.WARNING), severity(diagnosis, Severity.INFO));
    }

    public Page<Group> selectGroups(AccountDto accountDto, Search search, Pageable pageable) {
        Long projectId = accountDto.getProjectId();
        Diagnosis diagnosis = diagnose(projectId);
        String keyword = search.searchQuery() == null ? "" : search.searchQuery().trim().toLowerCase(Locale.ROOT);
        boolean byName = "termName".equals(search.searchColumn());
        boolean byAbbreviation = "abbreviation".equals(search.searchColumn());

        List<Group> groups = diagnosis.groups().stream()
                .filter(group -> search.type() == null || group.finding().rule() == search.type())
                .filter(group -> search.severity() == null || group.finding().rule().severity() == search.severity())
                .filter(group -> keyword.isEmpty() || !(byName || byAbbreviation) || group.termIds().stream()
                        .map(diagnosis.terms()::get)
                        .anyMatch(term -> lower(byName ? term.termName() : term.abbreviation()).contains(keyword)))
                .map(group -> toGroup(group, diagnosis.terms(), Map.of(), Map.of()))
                .sorted(order(pageable.getSort()))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), groups.size());
        List<Group> page = groups.subList(from, Math.min(from + pageable.getPageSize(), groups.size()));

        // 사용 컬럼 수 : 이 페이지의 용어만 한 번에 센다 (그룹마다 조회하지 않는다)
        List<Long> pageTermIds = page.stream().flatMap(group -> group.members().stream()).map(Member::termId).distinct().toList();
        Map<Long, Long> counts = usageRepository.countColumnsByTerm(projectId, pageTermIds);
        // 등록·수정 일시 : 같은 페이지 용어만 한 번에 (M-5 5차)
        Map<Long, Object[]> audits = new HashMap<>();
        if (!pageTermIds.isEmpty()) termRepository.findAuditByIds(projectId, pageTermIds).forEach(row -> audits.put((Long) row[0], row));
        Map<String, TermGroup> byId = new HashMap<>();
        diagnosis.groups().forEach(group -> byId.put(group.finding().fingerprint(), group));
        List<Group> content = page.stream().map(group -> toGroup(byId.get(group.id()), diagnosis.terms(), counts, audits)).toList();
        return new PageImpl<>(content, pageable, groups.size());
    }

    // ------------------------------------------------------------------ 진단

    /** SQL 6번 (용어, 용어-단어, 단어, 용어 이음동의어, 단어 이음동의어, 금칙어). terms 는 ID → 용어 */
    private record Diagnosis(Map<Long, TermRow> terms, List<TermGroup> groups) {
    }

    private Diagnosis diagnose(Long projectId) {
        List<TermRow> terms = usageRepository.findQualityTerms(projectId);
        if (terms.isEmpty()) {
            return new Diagnosis(Map.of(), List.of());
        }
        Map<Long, TermRow> byId = new HashMap<>();
        terms.forEach(term -> byId.put(term.termId(), term));
        List<TermGroup> groups = TermDuplicateRules.diagnose(projectId, terms, usageRepository.findQualityTermWords(projectId),
                usageRepository.findTermSynonyms(projectId), usageRepository.findQualityWords(projectId),
                usageRepository.findWordSynonyms(projectId), usageRepository.findRestrictedWords(projectId));
        return new Diagnosis(byId, groups);
    }

    private static long severity(Diagnosis diagnosis, Severity severity) {
        return diagnosis.groups().stream().filter(group -> group.finding().rule().severity() == severity).count();
    }

    private static Comparator<Group> order(Sort sort) {
        Comparator<Group> order = (a, b) -> 0;
        for (Sort.Order requested : sort) {
            Comparator<Group> comparator = SORTS.get(requested.getProperty());
            if (comparator != null) {
                order = order.thenComparing(requested.isAscending() ? comparator : comparator.reversed());
            }
        }
        return order.thenComparing(DEFAULT_ORDER);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 표시

    private static Group toGroup(TermGroup group, Map<Long, TermRow> terms, Map<Long, Long> counts, Map<Long, Object[]> audits) {
        StandardizationRule rule = group.finding().rule();
        Map<String, String> evidence = group.finding().evidence();
        boolean wordSynonym = rule == StandardizationRule.TERM_SYNONYM && "WORD".equals(evidence.get("kind"));
        List<Member> members = group.termIds().stream().map(id -> {
            TermRow term = terms.get(id);
            String note = group.notes().get(id);
            Object[] audit = audits.get(id);
            return new Member(id, term.termName(), term.abbreviation(), term.domainName(), term.isApproval(),
                    counts.isEmpty() ? null : counts.getOrDefault(id, 0L), note == null ? null : note(rule, wordSynonym, note),
                    term.domainId(), audit == null ? null : (java.time.LocalDateTime) audit[1], audit == null ? null : (java.time.LocalDateTime) audit[2]);
        }).toList();
        return new Group(group.finding().fingerprint(), rule, rule.severity(), group.groupKey(), members.size(), members,
                message(rule, wordSynonym, members.size(), evidence), evidence);
    }

    private static String note(StandardizationRule rule, boolean wordSynonym, String raw) {
        return switch (rule) {
            case TERM_SYNONYM -> wordSynonym ? Messages.get("바꾼 단어 : {0}", raw) : Messages.get("이음동의어 : {0}", raw);
            case TERM_RESTRICTED_WORD -> Messages.get("금칙어 → 표준 단어 : {0}", raw);
            default -> raw;
        };
    }

    private static String message(StandardizationRule rule, boolean wordSynonym, int size, Map<String, String> evidence) {
        String count = String.valueOf(size);
        return switch (rule) {
            case TERM_ABBR_DUPLICATE -> Messages.get("영문약어와 도메인이 같은 표준 용어가 {0}개 있습니다.", count);
            case TERM_NAME_DUPLICATE -> Messages.get("용어명이 같은 표준 용어가 {0}개 있습니다 (공백·'_'·대소문자 무시).", count)
                    + (Objects.equals(evidence.get("sameDomain"), "true") ? "" : " " + Messages.get("도메인이 서로 다릅니다."));
            case TERM_ABBR_SHARED -> Messages.get("영문약어가 같고 도메인이 다른 표준 용어가 {0}개 있습니다. 컬럼명 생성·엑셀 업로드에서 용어를 구분할 수 없습니다.", count);
            case TERM_SAME_WORDS -> Messages.get("같은 표준 단어로 이루어진 용어가 {0}개 있습니다.", count)
                    + (Objects.equals(evidence.get("orderDiffers"), "true") ? " " + Messages.get("단어 순서가 다릅니다.") : "");
            case TERM_SYNONYM -> wordSynonym
                    ? Messages.get("표준 단어의 이음동의어를 대표 단어로 바꾸면 단어 구성이 같습니다 : {0}", evidence.get("replaced"))
                    : Messages.get("용어명이 다른 표준 용어의 이음동의어({0})와 같습니다.", evidence.get("synonym"));
            case TERM_RESTRICTED_WORD -> Messages.get("다른 표준 단어의 금칙어가 쓰였습니다 : {0}", evidence.get("matches"));
            default -> "";
        };
    }
}
