package com.lexorma.data.dictionary.quality;

import com.lexorma.data.dictionary.domain.dto.TermQualityDto.NameRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermWordRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.WordRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 표준 용어 중복·유사어 (M-5 잔여, D6·D7). 모든 규칙은 정규화한 키로 묶는 해시 그룹이다 — 용어 쌍을 모두 비교하지 않는다 (O(N), 이음동의어 수에 비례).
 * 문자열 편집거리·n-gram·임베딩·AI 판단은 쓰지 않는다 (D7). 묶기만 하고 같은 뜻이라고 확정하거나 병합하지 않는다.
 * <table>
 *   <tr><td>{@link StandardizationRule#TERM_ABBR_DUPLICATE}</td><td>영문약어(trim, 대문자) + 도메인 ID 가 같다 — 등록의 중복 기준(약어 + 도메인)을 대소문자 무시로 본다</td></tr>
 *   <tr><td>{@link StandardizationRule#TERM_NAME_DUPLICATE}</td><td>용어명에서 공백·'_' 를 빼고 소문자로 바꾼 값이 같다 (엑셀 업로드의 용어명 비교 {@code resolveUploadedTermName} 과 같은 {@link StandardNames#compact} + 대소문자)</td></tr>
 *   <tr><td>{@link StandardizationRule#TERM_ABBR_SHARED}</td><td>영문약어는 같고 도메인이 둘 이상 (그 약어의 용어 전체)</td></tr>
 *   <tr><td>{@link StandardizationRule#TERM_SAME_WORDS}</td><td>TB_STD_TRM_WORD 의 단어 구성(순서 무시, 같은 단어 반복은 센다)이 같다. 구성 순서와 정규화 용어명이 모두 같으면 용어명 중복과 같은 내용이라 뺀다</td></tr>
 *   <tr><td>{@link StandardizationRule#TERM_SYNONYM}</td><td>① 용어명 = 다른 용어의 이음동의어 ② 단어의 이음동의어와 이름이 같은 다른 단어를 그 대표 단어로 바꾸면 단어 구성(순서 포함)이 같아진다 (한 단계만 바꾼다)</td></tr>
 *   <tr><td>{@link StandardizationRule#TERM_RESTRICTED_WORD}</td><td>용어를 이루는 단어(단어 매핑, 없으면 용어명을 '_'·공백으로 나눈 단어)의 이름이 다른 표준 단어의 금칙어와 같다.
 *       부분 문자열은 보지 않는다 — 단어 경계에서 정확히 같을 때만</td></tr>
 * </table>
 * 이름 비교의 정규화는 모두 {@link StandardNames#termNameKey} (공백·'_' 제거, 소문자), 약어는 {@link StandardNames#physicalNameKey}, 빈 값(이음동의어 "" 등)은 무시한다.
 */
public final class TermDuplicateRules {

    private TermDuplicateRules() {
    }

    /**
     * 판정 결과 1건. termIds 는 그룹의 용어 ID 오름차순, groupKey 는 묶은 값의 표시,
     * notes 는 용어별 근거 원문 (가진 이음동의어, 바뀐 단어 "고객 → 회원", 쓰인 금칙어 "회원명 → 회원") — 문구는 서비스가 붙인다
     */
    public record TermGroup(Finding finding, String groupKey, List<Long> termIds, Map<Long, String> notes) {
    }

    /** 결과 순서 : 규칙 순 → 묶은 키 순 (같은 입력이면 같은 순서) */
    public static List<TermGroup> diagnose(long projectId, List<TermRow> terms, List<TermWordRow> termWords, List<NameRow> termSynonyms,
                                           List<WordRow> words, List<NameRow> wordSynonyms, List<NameRow> restrictedWords) {
        Map<Long, TermRow> termById = new TreeMap<>();
        terms.forEach(term -> termById.put(term.termId(), term));
        Map<Long, WordRow> wordById = new HashMap<>();
        words.forEach(word -> wordById.put(word.wordId(), word));
        Map<Long, List<Long>> sequences = sequences(termById.keySet(), termWords);

        List<TermGroup> groups = new ArrayList<>();
        abbreviations(projectId, termById, groups);
        names(projectId, termById, groups);
        sameWords(projectId, termById, sequences, wordById, groups);
        termSynonyms(projectId, termById, termSynonyms, groups);
        wordSynonyms(projectId, termById, sequences, words, wordById, wordSynonyms, groups);
        restricted(projectId, termById, sequences, wordById, restrictedWords, groups);
        return groups;
    }

    /** 용어 ID → 단어 ID (순서대로). 매핑이 없는 용어는 빠진다 */
    private static Map<Long, List<Long>> sequences(Set<Long> termIds, List<TermWordRow> termWords) {
        Map<Long, List<Long>> sequences = new HashMap<>();
        termWords.stream()
                .filter(row -> termIds.contains(row.termId()) && row.wordId() != null)
                .sorted(Comparator.comparing(TermWordRow::termId)
                        .thenComparing(row -> row.orderIndex() == null ? Integer.MAX_VALUE : row.orderIndex())
                        .thenComparing(TermWordRow::wordId))
                .forEach(row -> sequences.computeIfAbsent(row.termId(), id -> new ArrayList<>()).add(row.wordId()));
        return sequences;
    }

    // ------------------------------------------------------------------ 약어

    private static void abbreviations(long projectId, Map<Long, TermRow> termById, List<TermGroup> groups) {
        Map<String, List<TermRow>> byAbbreviation = new TreeMap<>();
        termById.values().stream().filter(term -> !StandardNames.physicalNameKey(term.abbreviation()).isEmpty())
                .forEach(term -> byAbbreviation.computeIfAbsent(StandardNames.physicalNameKey(term.abbreviation()), key -> new ArrayList<>()).add(term));

        List<TermGroup> shared = new ArrayList<>();
        byAbbreviation.forEach((abbreviation, members) -> {
            Map<String, List<TermRow>> byDomain = new TreeMap<>();
            members.forEach(term -> byDomain.computeIfAbsent(domainKey(term), key -> new ArrayList<>()).add(term));
            byDomain.forEach((domain, same) -> {
                if (same.size() > 1) {
                    Map<String, String> evidence = new LinkedHashMap<>();
                    evidence.put("abbreviation", abbreviation);
                    evidence.put("domainName", Objects.toString(same.get(0).domainName(), ""));
                    groups.add(group(projectId, StandardizationRule.TERM_ABBR_DUPLICATE, ids(same), List.of(abbreviation, domain),
                            abbreviation, null, evidence, Map.of()));
                }
            });
            if (byDomain.size() > 1) {
                Map<String, String> evidence = new LinkedHashMap<>();
                evidence.put("abbreviation", abbreviation);
                evidence.put("domains", String.valueOf(byDomain.size()));
                shared.add(group(projectId, StandardizationRule.TERM_ABBR_SHARED, ids(members), List.of(abbreviation),
                        abbreviation, null, evidence, Map.of()));
            }
        });
        groups.addAll(shared);
    }

    private static String domainKey(TermRow term) {
        return term.domainId() == null ? "-" : String.valueOf(term.domainId());
    }

    // ------------------------------------------------------------------ 용어명

    private static void names(long projectId, Map<Long, TermRow> termById, List<TermGroup> groups) {
        Map<String, List<TermRow>> byName = new TreeMap<>();
        termById.values().stream().filter(term -> !StandardNames.termNameKey(term.termName()).isEmpty())
                .forEach(term -> byName.computeIfAbsent(StandardNames.termNameKey(term.termName()), key -> new ArrayList<>()).add(term));
        byName.forEach((name, members) -> {
            if (members.size() > 1) {
                Map<String, String> evidence = new LinkedHashMap<>();
                evidence.put("normalizedName", name);
                evidence.put("sameDomain", String.valueOf(members.stream().map(TermDuplicateRules::domainKey).distinct().count() == 1));
                groups.add(group(projectId, StandardizationRule.TERM_NAME_DUPLICATE, ids(members), List.of(name), name, null, evidence, Map.of()));
            }
        });
    }

    // ------------------------------------------------------------------ 단어 구성

    private static void sameWords(long projectId, Map<Long, TermRow> termById, Map<Long, List<Long>> sequences, Map<Long, WordRow> wordById,
                                  List<TermGroup> groups) {
        Map<String, List<Long>> bySet = new TreeMap<>();
        termById.keySet().stream().filter(sequences::containsKey)
                .forEach(termId -> bySet.computeIfAbsent(join(sequences.get(termId).stream().sorted().toList()), key -> new ArrayList<>()).add(termId));
        bySet.forEach((key, members) -> {
            if (members.size() < 2) {
                return;
            }
            boolean orderDiffers = members.stream().map(sequences::get).distinct().count() > 1;
            boolean sameName = members.stream().map(id -> StandardNames.termNameKey(termById.get(id).termName())).distinct().count() == 1;
            if (!orderDiffers && sameName) {
                return; // 용어명 중복과 같은 내용
            }
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("wordIds", key);
            evidence.put("orderDiffers", String.valueOf(orderDiffers));
            String display = Arrays.stream(key.split(",")).map(id -> wordName(wordById, Long.valueOf(id))).collect(Collectors.joining(" + "));
            groups.add(group(projectId, StandardizationRule.TERM_SAME_WORDS, members, List.of(key), display, null, evidence, Map.of()));
        });
    }

    // ------------------------------------------------------------------ 이음동의어

    /** ① 용어명 = 다른 용어의 이음동의어 : 두 용어씩 묶는다 (이음동의어를 가진 용어의 note 에 그 이음동의어) */
    private static void termSynonyms(long projectId, Map<Long, TermRow> termById, List<NameRow> termSynonyms, List<TermGroup> groups) {
        Map<String, Map<Long, String>> owners = new HashMap<>();
        termSynonyms.stream().filter(row -> termById.containsKey(row.ownerId()) && !StandardNames.termNameKey(row.name()).isEmpty())
                .forEach(row -> owners.computeIfAbsent(StandardNames.termNameKey(row.name()), key -> new TreeMap<>()).putIfAbsent(row.ownerId(), row.name().trim()));

        Map<String, TermGroup> pairs = new TreeMap<>();
        for (TermRow term : termById.values()) {
            String name = StandardNames.termNameKey(term.termName());
            for (Map.Entry<Long, String> owner : owners.getOrDefault(name, Map.of()).entrySet()) {
                if (owner.getKey().equals(term.termId())) {
                    continue;
                }
                List<Long> pair = new ArrayList<>(List.of(owner.getKey(), term.termId()));
                pair.sort(null);
                Map<String, String> evidence = new LinkedHashMap<>();
                evidence.put("kind", "TERM");
                evidence.put("synonym", owner.getValue());
                evidence.put("synonymOwnerTermId", String.valueOf(owner.getKey()));
                String pairKey = join(pair);
                pairs.putIfAbsent(pairKey, group(projectId, StandardizationRule.TERM_SYNONYM, pair, List.of("TERM", name),
                        owner.getValue(), null, evidence, Map.of(owner.getKey(), owner.getValue())));
            }
        }
        groups.addAll(pairs.values());
    }

    /** ② 단어 이음동의어 치환 : 대표 단어(그 이음동의어를 가진 단어, 여럿이면 ID 가 작은 것)로 바꾼 단어 구성이 같은 용어 */
    private static void wordSynonyms(long projectId, Map<Long, TermRow> termById, Map<Long, List<Long>> sequences, List<WordRow> words,
                                     Map<Long, WordRow> wordById, List<NameRow> wordSynonyms, List<TermGroup> groups) {
        Map<String, List<Long>> wordsByName = new HashMap<>();
        words.forEach(word -> wordsByName.computeIfAbsent(StandardNames.termNameKey(word.wordName()), key -> new ArrayList<>()).add(word.wordId()));
        Map<Long, Long> canonical = new HashMap<>();
        for (NameRow synonym : wordSynonyms) {
            String name = StandardNames.termNameKey(synonym.name());
            if (name.isEmpty() || !wordById.containsKey(synonym.ownerId())) {
                continue;
            }
            for (Long wordId : wordsByName.getOrDefault(name, List.of())) {
                if (!wordId.equals(synonym.ownerId())) {
                    canonical.merge(wordId, synonym.ownerId(), Math::min);
                }
            }
        }
        if (canonical.isEmpty()) {
            return;
        }

        Map<String, List<Long>> byCanonical = new TreeMap<>();
        termById.keySet().stream().filter(sequences::containsKey).forEach(termId -> byCanonical.computeIfAbsent(
                join(sequences.get(termId).stream().map(id -> canonical.getOrDefault(id, id)).toList()), key -> new ArrayList<>()).add(termId));
        byCanonical.forEach((key, members) -> {
            if (members.size() < 2 || members.stream().map(sequences::get).distinct().count() < 2) {
                return; // 바꾸기 전에도 같은 구성이면 이음동의어 때문이 아니다
            }
            Map<Long, String> notes = new TreeMap<>();
            for (Long termId : members) {
                String replaced = sequences.get(termId).stream().filter(canonical::containsKey)
                        .map(id -> wordName(wordById, id) + " → " + wordName(wordById, canonical.get(id))).distinct().collect(Collectors.joining(", "));
                if (!replaced.isEmpty()) {
                    notes.put(termId, replaced);
                }
            }
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("kind", "WORD");
            evidence.put("canonicalWordIds", key);
            evidence.put("replaced", String.join(" / ", new LinkedHashSet<>(notes.values())));
            String display = Arrays.stream(key.split(",")).map(id -> wordName(wordById, Long.valueOf(id))).collect(Collectors.joining("_"));
            groups.add(group(projectId, StandardizationRule.TERM_SYNONYM, members, List.of("WORD", key), display, null, evidence, notes));
        });
    }

    // ------------------------------------------------------------------ 금칙어

    private static void restricted(long projectId, Map<Long, TermRow> termById, Map<Long, List<Long>> sequences, Map<Long, WordRow> wordById,
                                   List<NameRow> restrictedWords, List<TermGroup> groups) {
        Map<String, Set<Long>> owners = new HashMap<>();
        restrictedWords.stream().filter(row -> wordById.containsKey(row.ownerId()) && !StandardNames.termNameKey(row.name()).isEmpty())
                .forEach(row -> owners.computeIfAbsent(StandardNames.termNameKey(row.name()), key -> new TreeSet<>()).add(row.ownerId()));
        if (owners.isEmpty()) {
            return;
        }

        for (TermRow term : termById.values()) {
            // 단어 경계 : 단어 매핑이 있으면 그 단어들, 없으면 용어명을 '_'·공백으로 나눈 조각
            List<Long> sequence = sequences.get(term.termId());
            List<String[]> segments = new ArrayList<>(); // [이름, 단어 ID 또는 null]
            if (sequence != null) {
                sequence.forEach(id -> segments.add(new String[]{wordName(wordById, id), String.valueOf(id)}));
            } else {
                StandardNames.splitWords(term.termName()).forEach(segment -> segments.add(new String[]{segment, null}));
            }

            Set<String> keys = new TreeSet<>();
            List<String> matches = new ArrayList<>();
            List<String> standards = new ArrayList<>();
            for (String[] segment : segments) {
                String name = StandardNames.termNameKey(segment[0]);
                for (Long ownerId : owners.getOrDefault(name, Set.of())) {
                    WordRow owner = wordById.get(ownerId);
                    // 자기 이름을 금칙어로 둔 단어(데이터 오류)는 보지 않는다
                    if (String.valueOf(ownerId).equals(segment[1]) || StandardNames.termNameKey(owner.wordName()).equals(name)) {
                        continue;
                    }
                    if (keys.add(name + ">" + ownerId)) {
                        matches.add(segment[0] + " → " + owner.wordName());
                        standards.add(owner.wordName());
                    }
                }
            }
            if (!keys.isEmpty()) {
                Map<String, String> evidence = new LinkedHashMap<>();
                evidence.put("boundary", sequence != null ? "TERM_WORD" : "TERM_NAME");
                evidence.put("matches", String.join(", ", matches));
                List<Long> target = List.of(term.termId());
                groups.add(new TermGroup(new Finding(StandardizationRule.TERM_RESTRICTED_WORD, target,
                        String.join(", ", matches.stream().map(match -> match.substring(0, match.indexOf(" → "))).distinct().toList()),
                        String.join(", ", standards.stream().distinct().toList()), evidence,
                        FindingFingerprint.of(projectId, StandardizationRule.TERM_RESTRICTED_WORD, target, List.copyOf(keys))),
                        term.termName(), target, Map.of(term.termId(), String.join(", ", matches))));
            }
        }
    }

    // ------------------------------------------------------------------ 보조

    private static TermGroup group(long projectId, StandardizationRule rule, List<Long> termIds, List<String> keys, String groupKey,
                                   String expected, Map<String, String> evidence, Map<Long, String> notes) {
        List<Long> sorted = termIds.stream().sorted().toList();
        String actual = rule == StandardizationRule.TERM_ABBR_DUPLICATE || rule == StandardizationRule.TERM_ABBR_SHARED
                || rule == StandardizationRule.TERM_NAME_DUPLICATE ? groupKey : null;
        return new TermGroup(new Finding(rule, sorted, actual, expected, evidence, fingerprint(projectId, rule, sorted, keys)),
                groupKey, sorted, notes);
    }

    /**
     * 그룹을 키로 정하는 규칙(약어·도메인 중복, 같은 약어, 용어명 중복, 같은 단어 구성, 단어 이음동의어 치환)은 그룹 식별(FindingFingerprint.group) — 구성원 ID 를 넣지 않는다 (AI6-D11).
     * 용어명 = 다른 용어의 이음동의어(TERM_SYNONYM 의 TERM 종류)는 그룹이 아니라 <b>두 용어의 짝</b>이라 짝 자체가 식별자다 :
     * 키(용어명)만 쓰면 서로 다른 짝이 같은 값이 되므로 대상 식별(FindingFingerprint.of, 대상 ID 포함)을 쓴다. 인코딩은 둘 다 같다 (v4).
     */
    static String fingerprint(long projectId, StandardizationRule rule, List<Long> sortedIds, List<String> keys) {
        boolean pair = rule == StandardizationRule.TERM_SYNONYM && !keys.isEmpty() && "TERM".equals(keys.get(0));
        return pair ? FindingFingerprint.of(projectId, rule, sortedIds, keys) : FindingFingerprint.group(projectId, rule, keys);
    }

    private static List<Long> ids(List<TermRow> terms) {
        return terms.stream().map(TermRow::termId).toList();
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String wordName(Map<Long, WordRow> wordById, Long id) {
        WordRow word = wordById.get(id);
        return word == null || word.wordName() == null ? "#" + id : word.wordName();
    }
}
