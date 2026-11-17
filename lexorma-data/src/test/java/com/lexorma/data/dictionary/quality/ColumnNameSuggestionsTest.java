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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** M-5 잔여 : 비표준 컬럼명 개선 후보 (고정 점수, 추천만) */
class ColumnNameSuggestionsTest {

    private static final List<WordRow> WORDS = List.of(new WordRow(1L, "회원", "MBR", true), new WordRow(2L, "이름", "NM", true),
            new WordRow(3L, "등급", "GRD", false), new WordRow(4L, "코드", "CD", true));

    @Test
    @DisplayName("대상이 아닌 컬럼(용어 연결 + 컬럼명 일치, 도메인만 다름)은 계산하지 않는다")
    void notApplicable() {
        Input input = new Input(column("MBR_NM", "회원 이름", 10L, "MBR_NM"), Set.of(IssueType.DOMAIN_DEFINITION_MISMATCH));
        input.term(10, "회원_이름", "MBR_NM", true, null);
        assertThat(ColumnNameSuggestions.applies(Set.of(IssueType.DOMAIN_DEFINITION_MISMATCH))).isFalse();
        assertThat(input.run()).isEmpty();
    }

    @Test
    @DisplayName("후보 0건 : 약어·논리명·단어 조합 어디에도 맞지 않으면 빈 목록")
    void none() {
        Input input = new Input(column("XYZ_QQ", "알 수 없음", null, null), Set.of(IssueType.TERM_NOT_LINKED));
        input.term(10, "회원_이름", "MBR_NM", true, null);
        assertThat(input.run()).isEmpty();
    }

    @Test
    @DisplayName("미연결 컬럼 : 약어 일치(용어만 연결) > 용어명 일치 > 이음동의어 > 단어 조합 > 같은 논리명 컬럼, 출처가 여럿이면 가장 높은 것")
    void unlinked() {
        Input input = new Input(column("MBR_NM", "회원 성명", null, null), Set.of(IssueType.TERM_NOT_LINKED));
        input.term(10, "회원_이름", "MBR_NM", true, null, 1, 2);    // 약어 일치 + 단어 조합
        input.term(11, "회원_성명", "MBR_FLNM", true, null);       // 용어명 = 논리명
        input.term(12, "회원_실명", "MBR_RNM", true, null);        // 이음동의어
        input.synonyms.add(new NameRow(12L, "회원성명"));
        input.term(13, "고객_이름", "CUST_NM", true, null);        // 같은 논리명의 다른 컬럼 3개가 쓴다
        input.sameDesc.put(13L, 3L);

        List<Candidate> candidates = input.run();
        assertThat(candidates).extracting(Candidate::termId, Candidate::source, Candidate::score, Candidate::action, Candidate::recommendedColumnName)
                .containsExactly(tuple(10L, Source.ABBR_EXACT, 95, Action.LINK_TERM, "MBR_NM"),
                        tuple(11L, Source.NAME_EXACT, 85, Action.LINK_TERM_AND_RENAME, "MBR_FLNM"),
                        tuple(12L, Source.SYNONYM, 75, Action.LINK_TERM_AND_RENAME, "MBR_RNM"),
                        tuple(13L, Source.SAME_DESC_COLUMN, 60, Action.LINK_TERM_AND_RENAME, "CUST_NM"));
        assertThat(candidates.get(0).sources()).containsExactly(Source.ABBR_EXACT, Source.WORD_COMPOSE);
        assertThat(candidates.get(0).words()).extracting(Word::wordName).containsExactly("회원", "이름");
        assertThat(candidates.get(3).sameDescColumns()).isEqualTo(3L);
    }

    @Test
    @DisplayName("컬럼명 불일치 : 연결된 용어의 약어로 이름 변경이 첫 후보, 현재 이름의 약어를 가진 다른 용어는 재연결 후보")
    void mismatch() {
        Input input = new Input(column("MBR_CD", "회원 이름", 10L, "MBR_NM"), Set.of(IssueType.COLUMN_NAME_MISMATCH));
        input.term(10, "회원_이름", "MBR_NM", true, null);
        input.term(14, "회원_코드", "mbr_cd", true, null);

        assertThat(input.run()).extracting(Candidate::termId, Candidate::source, Candidate::action)
                .containsExactly(tuple(10L, Source.LINKED_TERM, Action.RENAME), tuple(14L, Source.ABBR_EXACT, Action.LINK_TERM));
    }

    @Test
    @DisplayName("검증 : 미승인 용어·도메인 불일치·이름 충돌·다른 컬럼의 기대 이름은 감점과 표시, 순서는 같은 데이터면 고정")
    void checks() {
        Input input = new Input(column("MEMBER_NAME", "회원 이름", null, null), Set.of(IssueType.TERM_NOT_LINKED));
        input.term(10, "회원_이름", "MBR_NM", false, new Object[]{"명V10", "VARCHAR", 10});
        input.term(11, "회원 이름", "MBR_NAME", true, new Object[]{"일자", "DATE", null});
        input.tableColumns.add(new TableColumnRow(2L, "mbr_nm", null, null));                 // 같은 이름의 다른 컬럼
        input.tableColumns.add(new TableColumnRow(3L, "MEMB_NAME", 99L, "MBR_NAME"));        // MBR_NAME 을 기대하는 다른 컬럼
        input.tableColumns.add(new TableColumnRow(1L, "MEMBER_NAME", null, null));            // 자기 자신

        List<Candidate> candidates = input.run();
        // 10 : 85 − 미승인 10 − 길이 5 − 충돌 20 = 50, 11 : 85 − 타입 10 − 기대 이름 10 = 65
        assertThat(candidates).extracting(Candidate::termId, Candidate::score, Candidate::termApproved, Candidate::domainFit,
                        Candidate::nameConflict, Candidate::candidateConflict)
                .containsExactly(tuple(11L, 65, true, DomainFit.MISMATCH, false, true), tuple(10L, 50, false, DomainFit.MISMATCH, true, false));
        assertThat(candidates.get(0).attributes()).containsExactly(Attribute.DATA_TYPE);
        assertThat(candidates.get(0).candidateConflictColumns()).containsExactly("MEMB_NAME");
        assertThat(candidates.get(1).attributes()).containsExactly(Attribute.LENGTH);
        assertThat(candidates.get(1).conflictColumns()).containsExactly("mbr_nm");
        assertThat(input.run()).isEqualTo(candidates);
    }

    @Test
    @DisplayName("미등록 조합 : 약어가 모두 표준 단어인데 그 구성의 용어가 없으면 새 용어 후보 (미승인 단어가 있으면 감점), 빈 약어·모르는 약어는 조합하지 않는다")
    void unregistered() {
        Input input = new Input(column("MBR_GRD", "회원 등급", null, null), Set.of(IssueType.TERM_NOT_LINKED));
        input.term(10, "회원_이름", "MBR_NM", true, null, 1, 2);
        Candidate candidate = input.run().get(0);
        assertThat(candidate.source()).isEqualTo(Source.UNREGISTERED);
        assertThat(candidate.action()).isEqualTo(Action.REGISTER_TERM);
        assertThat(candidate.termId()).isNull();
        assertThat(candidate.proposedTermName()).isEqualTo("회원_등급");
        assertThat(candidate.recommendedColumnName()).isEqualTo("MBR_GRD");
        assertThat(candidate.score()).isEqualTo(20);
        assertThat(candidate.words()).extracting(Word::approved).containsExactly(true, false);

        assertThat(new Input(column("MBR__NM", null, null, null), Set.of(IssueType.TERM_NOT_LINKED)).run()).isEmpty();
        assertThat(new Input(column("MBR_XX", null, null, null), Set.of(IssueType.TERM_NOT_LINKED)).run()).isEmpty();
    }

    @Test
    @DisplayName("최대 5건, 나머지는 truncated")
    void limit() {
        Input input = new Input(column("A", "같은 논리명", null, null), Set.of(IssueType.TERM_NOT_LINKED));
        for (long id = 20; id < 27; id++) {
            input.term(id, "용어" + id, "T" + id, true, null);
            input.sameDesc.put(id, id);
        }
        ColumnNameSuggestions.Result result = input.result();
        assertThat(result.candidates()).hasSize(ColumnNameSuggestions.LIMIT).extracting(Candidate::termId).containsExactly(26L, 25L, 24L, 23L, 22L);
        assertThat(result.truncated()).isTrue();
    }

    // ------------------------------------------------------------------ 보조

    private static ColumnRow column(String name, String desc, Long termId, String termAbbreviation) {
        return new ColumnRow(1L, 7L, 1L, "DEFAULT", "TB_MBR", "회원", name, desc, "VARCHAR", 20, null, null, 1, true,
                termId, termId == null ? null : "용어", termAbbreviation, null, null, null, null, null);
    }

    private static final class Input {
        final ColumnRow column;
        final Set<IssueType> issues;
        final List<TermCandidateRow> terms = new ArrayList<>();
        final List<NameRow> synonyms = new ArrayList<>();
        final List<TermWordRow> termWords = new ArrayList<>();
        final Map<Long, Long> sameDesc = new HashMap<>();
        final List<TableColumnRow> tableColumns = new ArrayList<>();

        Input(ColumnRow column, Set<IssueType> issues) {
            this.column = column;
            this.issues = issues;
        }

        /** domain : {이름, 타입, 길이} 또는 null */
        void term(long id, String name, String abbreviation, boolean approved, Object[] domain, long... wordIds) {
            terms.add(new TermCandidateRow(id, name, abbreviation, approved, domain == null ? null : 100 + id,
                    domain == null ? null : (String) domain[0], domain == null ? null : (String) domain[1], domain == null ? null : (Integer) domain[2], null));
            for (int i = 0; i < wordIds.length; i++) {
                termWords.add(new TermWordRow(id, wordIds[i], i + 1));
            }
        }

        ColumnNameSuggestions.Result result() {
            return ColumnNameSuggestions.suggest(new ColumnNameSuggestions.Input(DbType.POSTGRESQL, column, issues, terms, synonyms, WORDS, termWords,
                    sameDesc, tableColumns));
        }

        List<Candidate> run() {
            return result().candidates();
        }
    }
}
