package com.lexorma.data.dictionary.quality;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.Action;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.Candidate;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.DomainFit;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.Source;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.TableColumnRow;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.TermCandidateRow;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.Word;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Attribute;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ColumnRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.NameRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermWordRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.WordRow;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.dictionary.service.StandardizationQualityService.DomainComparison;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 비표준 컬럼명 개선 후보 (M-5 잔여). 대상은 표준 용어 미연결·컬럼명 불일치 컬럼이며, 정해진 규칙으로만 후보를 찾고 점수를 매긴다
 * ({@code SimilarColumnService} 와 같은 방식 : 출처별 고정 점수, 여러 출처면 가장 높은 것 + 출처 목록). AI·편집거리·임베딩은 쓰지 않는다.
 * <table>
 *   <tr><td>LINKED_TERM</td><td>100</td><td>컬럼명 불일치 : 연결된 용어의 영문약어로 이름을 바꾼다 (컬럼명 규칙 {@link PhysicalNameRecommendationService#recommendColumnName})</td></tr>
 *   <tr><td>ABBR_EXACT</td><td>95</td><td>용어 영문약어 = 현재 컬럼명 (대소문자·앞뒤 공백 무시) → 용어만 연결</td></tr>
 *   <tr><td>NAME_EXACT</td><td>85</td><td>용어명 = 컬럼 논리명 (공백·'_'·대소문자 무시)</td></tr>
 *   <tr><td>SYNONYM</td><td>75</td><td>용어의 이음동의어 = 컬럼 논리명</td></tr>
 *   <tr><td>WORD_COMPOSE</td><td>70</td><td>컬럼명을 '_' 로 나눈 약어가 모두 표준 단어 약어이고, 그 단어 구성(순서 포함)의 용어가 있다 (약어마다 승인된 단어 우선)</td></tr>
 *   <tr><td>SAME_DESC_COLUMN</td><td>60</td><td>논리명이 같은 다른 컬럼이 쓰는 용어 (쓰는 컬럼 수는 같은 점수 안의 순서)</td></tr>
 *   <tr><td>UNREGISTERED</td><td>30</td><td>컬럼명의 약어가 모두 표준 단어인데 그 구성의 용어도, 그 약어의 용어도 없다 → 새 용어 등록 검토 (용어 ID 없음)</td></tr>
 * </table>
 * 감점 : 미승인 용어 −10, 도메인 타입 불일치 −10 · 그 밖의 도메인 속성 불일치 −5 ({@link StandardizationQualityService#compareDomain}),
 * 같은 테이블의 다른 컬럼이 그 이름을 씀 −20, 같은 테이블의 다른 컬럼도 그 이름을 기대함 −10. 순서 : 점수 → 출처 → 같은 논리명 컬럼 수 → 승인된 용어 → 용어명 → 용어 ID.
 */
public final class ColumnNameSuggestions {

    public static final int LIMIT = 5;

    private static final Map<Source, Integer> SCORES = Map.of(Source.LINKED_TERM, 100, Source.ABBR_EXACT, 95, Source.NAME_EXACT, 85,
            Source.SYNONYM, 75, Source.WORD_COMPOSE, 70, Source.SAME_DESC_COLUMN, 60, Source.UNREGISTERED, 30);

    private ColumnNameSuggestions() {
    }

    /**
     * @param sameDescColumns 용어 ID → 논리명이 같은 다른 컬럼 중 그 용어를 쓰는 수
     * @param tableColumns    같은 테이블의 컬럼 (자기 자신 포함 가능 — 판정에서 뺀다)
     */
    public record Input(DbType db, ColumnRow column, Set<IssueType> issues, List<TermCandidateRow> terms, List<NameRow> termSynonyms,
                        List<WordRow> words, List<TermWordRow> termWords, Map<Long, Long> sameDescColumns, List<TableColumnRow> tableColumns) {
    }

    public record Result(List<Candidate> candidates, boolean truncated) {
    }

    /** 후보 계산 대상인가 : 표준 용어 미연결 또는 컬럼명 불일치 */
    public static boolean applies(Set<IssueType> issues) {
        return issues.contains(IssueType.TERM_NOT_LINKED) || issues.contains(IssueType.COLUMN_NAME_MISMATCH);
    }

    public static Result suggest(Input in) {
        if (!applies(in.issues())) {
            return new Result(List.of(), false);
        }
        ColumnRow column = in.column();
        Long linked = column.termId();
        String name = column.columnName() == null ? "" : column.columnName().trim();
        String upperName = name.toUpperCase(Locale.ROOT);
        String desc = TermDuplicateRules.normalizeName(column.columnDesc());

        Map<Long, TermCandidateRow> terms = new LinkedHashMap<>();
        in.terms().forEach(term -> terms.put(term.termId(), term));
        Map<Long, EnumSet<Source>> hits = new LinkedHashMap<>();

        if (in.issues().contains(IssueType.COLUMN_NAME_MISMATCH) && linked != null && terms.containsKey(linked)) {
            hit(hits, linked, Source.LINKED_TERM);
        }
        for (TermCandidateRow term : terms.values()) {
            if (term.termId().equals(linked)) continue;
            if (!upperName.isEmpty() && term.abbreviation() != null && term.abbreviation().trim().toUpperCase(Locale.ROOT).equals(upperName)) {
                hit(hits, term.termId(), Source.ABBR_EXACT);
            }
            if (!desc.isEmpty() && TermDuplicateRules.normalizeName(term.termName()).equals(desc)) {
                hit(hits, term.termId(), Source.NAME_EXACT);
            }
        }
        if (!desc.isEmpty()) {
            in.termSynonyms().stream()
                    .filter(row -> terms.containsKey(row.ownerId()) && !row.ownerId().equals(linked) && TermDuplicateRules.normalizeName(row.name()).equals(desc))
                    .forEach(row -> hit(hits, row.ownerId(), Source.SYNONYM));
        }

        // 표준 단어 조합 : 약어마다 승인된 단어가 있으면 그것만, 없으면 미승인 단어
        Map<Long, WordRow> wordById = new HashMap<>();
        in.words().forEach(word -> wordById.put(word.wordId(), word));
        List<List<WordRow>> composed = compose(upperName, in.words());
        List<Word> composedWords = composed == null ? List.of()
                : composed.stream().map(options -> options.get(0)).map(word -> new Word(word.abbreviation(), word.wordName(), Boolean.TRUE.equals(word.isApproval()))).toList();
        boolean composedTermFound = false;
        if (composed != null) {
            Map<Long, List<Long>> sequences = new HashMap<>();
            in.termWords().stream().filter(row -> terms.containsKey(row.termId()) && row.wordId() != null)
                    .sorted(Comparator.comparing(TermWordRow::termId).thenComparing(row -> row.orderIndex() == null ? Integer.MAX_VALUE : row.orderIndex()))
                    .forEach(row -> sequences.computeIfAbsent(row.termId(), id -> new ArrayList<>()).add(row.wordId()));
            for (Map.Entry<Long, List<Long>> sequence : sequences.entrySet()) {
                if (matches(sequence.getValue(), composed)) {
                    composedTermFound = true;
                    if (!sequence.getKey().equals(linked)) hit(hits, sequence.getKey(), Source.WORD_COMPOSE);
                }
            }
        }
        in.sameDescColumns().keySet().stream().filter(id -> terms.containsKey(id) && !id.equals(linked))
                .forEach(id -> hit(hits, id, Source.SAME_DESC_COLUMN));

        List<Candidate> candidates = new ArrayList<>();
        hits.forEach((termId, sources) -> candidates.add(candidate(in, terms.get(termId), sources, name)));
        String composedAbbreviation = composedWords.stream().map(word -> word.abbreviation().trim().toUpperCase(Locale.ROOT)).collect(Collectors.joining("_"));
        // 그 약어의 용어가 이미 있으면(단어 매핑이 없는 용어 포함) 새 용어 후보가 아니다
        boolean abbreviationTaken = terms.values().stream()
                .anyMatch(term -> term.abbreviation() != null && term.abbreviation().trim().toUpperCase(Locale.ROOT).equals(composedAbbreviation));
        if (composed != null && !composedTermFound && !abbreviationTaken) {
            String proposed = composedWords.stream().map(Word::wordName).collect(Collectors.joining("_"));
            String abbreviation = composedAbbreviation;
            boolean approved = composedWords.stream().allMatch(Word::approved);
            candidates.add(new Candidate(Source.UNREGISTERED, List.of(Source.UNREGISTERED), SCORES.get(Source.UNREGISTERED) - (approved ? 0 : 10), Action.REGISTER_TERM,
                    null, null, null, null, abbreviation, proposed, composedWords, null, DomainFit.NO_DOMAIN, List.of(), null, null,
                    false, List.of(), false, List.of(), null, null));
        }

        List<Candidate> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::score).reversed()
                        .thenComparing(Candidate::source)
                        .thenComparing(candidate -> candidate.sameDescColumns() == null ? 0L : -candidate.sameDescColumns())
                        .thenComparing(candidate -> Boolean.TRUE.equals(candidate.termApproved()) ? 0 : 1)
                        .thenComparing(Candidate::termName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Candidate::termId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        // 표준 단어 조합 근거는 WORD_COMPOSE 후보에도 붙인다
        List<Candidate> withWords = sorted.stream().map(candidate -> candidate.sources().contains(Source.WORD_COMPOSE) && candidate.words().isEmpty()
                ? copyWithWords(candidate, composedWords) : candidate).toList();
        return new Result(withWords.stream().limit(LIMIT).toList(), withWords.size() > LIMIT);
    }

    private static void hit(Map<Long, EnumSet<Source>> hits, Long termId, Source source) {
        hits.computeIfAbsent(termId, id -> EnumSet.noneOf(Source.class)).add(source);
    }

    /** 컬럼명 약어마다 후보 단어 (승인 우선, ID 순). 빈 약어가 있거나 단어가 없는 약어가 있으면 null */
    private static List<List<WordRow>> compose(String upperName, List<WordRow> words) {
        if (upperName.isEmpty()) return null;
        Map<String, List<WordRow>> byAbbreviation = new HashMap<>();
        words.stream().filter(word -> word.abbreviation() != null && !word.abbreviation().isBlank())
                .sorted(Comparator.comparing(WordRow::wordId))
                .forEach(word -> byAbbreviation.computeIfAbsent(word.abbreviation().trim().toUpperCase(Locale.ROOT), key -> new ArrayList<>()).add(word));
        List<List<WordRow>> composed = new ArrayList<>();
        for (String token : upperName.split("_", -1)) {
            List<WordRow> options = byAbbreviation.get(token);
            if (token.isEmpty() || options == null) return null;
            List<WordRow> approved = options.stream().filter(word -> Boolean.TRUE.equals(word.isApproval())).toList();
            composed.add(approved.isEmpty() ? options : approved);
        }
        return composed;
    }

    private static boolean matches(List<Long> sequence, List<List<WordRow>> composed) {
        if (sequence.size() != composed.size()) return false;
        for (int i = 0; i < sequence.size(); i++) {
            Long wordId = sequence.get(i);
            if (composed.get(i).stream().noneMatch(word -> word.wordId().equals(wordId))) return false;
        }
        return true;
    }

    private static Candidate candidate(Input in, TermCandidateRow term, EnumSet<Source> sources, String currentName) {
        Source source = sources.iterator().next(); // EnumSet 은 선언 순서 = 우선순위
        String recommended = PhysicalNameRecommendationService.recommendColumnName(term.termId(), term.abbreviation()).name();
        Action action = source == Source.LINKED_TERM ? Action.RENAME
                : recommended == null || PhysicalNameRecommendationService.sameName(currentName, recommended) ? Action.LINK_TERM : Action.LINK_TERM_AND_RENAME;
        boolean rename = action != Action.LINK_TERM;

        ColumnRow column = in.column();
        DomainFit fit = DomainFit.NO_DOMAIN;
        List<Attribute> attributes = List.of();
        String actualType = null;
        String expectedType = null;
        if (term.domainId() != null) {
            DomainComparison comparison = StandardizationQualityService.compareDomain(in.db(), column.dataType(), column.maxLength(), column.precision(), column.scale(),
                    term.domainDataType(), term.domainLength(), term.domainDecimalLength());
            attributes = comparison.attributes();
            fit = attributes.isEmpty() ? DomainFit.MATCH : DomainFit.MISMATCH;
            actualType = comparison.actual();
            expectedType = comparison.expected();
        }

        List<TableColumnRow> others = in.tableColumns().stream().filter(other -> !Objects.equals(other.columnId(), column.columnId())).toList();
        List<String> conflicts = !rename || recommended == null ? List.of() : others.stream()
                .filter(other -> PhysicalNameRecommendationService.sameName(other.columnName(), recommended)).map(TableColumnRow::columnName).toList();
        List<String> expecting = recommended == null ? List.of() : others.stream()
                .filter(other -> PhysicalNameRecommendationService.sameName(other.termAbbreviation(), recommended)
                        && !PhysicalNameRecommendationService.sameName(other.columnName(), recommended))
                .map(TableColumnRow::columnName).toList();

        int score = SCORES.get(source);
        if (!Boolean.TRUE.equals(term.isApproval())) score -= 10;
        if (attributes.contains(Attribute.DATA_TYPE)) score -= 10;
        else if (!attributes.isEmpty()) score -= 5;
        if (!conflicts.isEmpty()) score -= 20;
        if (!expecting.isEmpty()) score -= 10;

        return new Candidate(source, List.copyOf(sources), Math.max(0, score), action, term.termId(), term.termName(), term.abbreviation(),
                Boolean.TRUE.equals(term.isApproval()), recommended, null, List.of(), term.domainName(), fit, attributes, actualType, expectedType,
                !conflicts.isEmpty(), conflicts, !expecting.isEmpty(), expecting, in.sameDescColumns().get(term.termId()), null);
    }

    private static Candidate copyWithWords(Candidate c, List<Word> words) {
        return new Candidate(c.source(), c.sources(), c.score(), c.action(), c.termId(), c.termName(), c.termAbbreviation(), c.termApproved(),
                c.recommendedColumnName(), c.proposedTermName(), words, c.domainName(), c.domainFit(), c.attributes(), c.actualType(), c.expectedType(),
                c.nameConflict(), c.conflictColumns(), c.candidateConflict(), c.candidateConflictColumns(), c.sameDescColumns(), c.reason());
    }

    /** 사유 문구를 붙인 사본 (문구는 서비스가 번역해서 만든다) */
    public static Candidate withReason(Candidate c, String reason) {
        return new Candidate(c.source(), c.sources(), c.score(), c.action(), c.termId(), c.termName(), c.termAbbreviation(), c.termApproved(),
                c.recommendedColumnName(), c.proposedTermName(), c.words(), c.domainName(), c.domainFit(), c.attributes(), c.actualType(), c.expectedType(),
                c.nameConflict(), c.conflictColumns(), c.candidateConflict(), c.candidateConflictColumns(), c.sameDescColumns(), reason);
    }
}
