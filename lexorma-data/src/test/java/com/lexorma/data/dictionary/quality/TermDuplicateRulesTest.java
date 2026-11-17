package com.lexorma.data.dictionary.quality;

import com.lexorma.data.dictionary.domain.dto.TermQualityDto.NameRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermWordRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.WordRow;
import com.lexorma.data.dictionary.quality.TermDuplicateRules.TermGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** M-5 잔여 : 표준 용어 중복·유사어 (정규화 키 그룹, D6·D7) */
class TermDuplicateRulesTest {

    // 단어 : 1 회원, 2 이름, 3 등록, 4 일자, 5 고객(이음동의어 없음), 6 성명
    private static final List<WordRow> WORDS = List.of(word(1, "회원", "MBR"), word(2, "이름", "NM"), word(3, "등록", "REG"),
            word(4, "일자", "DT"), word(5, "고객", "CUST"), word(6, "성명", "FLNM"));

    @Test
    @DisplayName("정상 : 이름·약어·구성이 모두 다른 용어는 결과가 없다. 같은 용어 자신은 중복으로 세지 않는다")
    void clean() {
        Input input = new Input();
        input.term(1, "회원_이름", "MBR_NM", 10L, 1, 2);
        input.term(2, "등록_일자", "REG_DT", 11L, 3, 4);
        assertThat(input.run()).isEmpty();
    }

    @Test
    @DisplayName("약어 : 같은 약어 + 같은 도메인은 오류(대소문자·앞뒤 공백 무시), 같은 약어 + 다른 도메인은 주의 (그 약어의 용어 전체)")
    void abbreviation() {
        Input input = new Input();
        input.term(1, "회원_이름", "MBR_NM", 10L);
        input.term(2, "회원명", " mbr_nm ", 10L);
        input.term(3, "회원_성명", "MBR_NM", 20L);
        input.term(4, "비고", "", 10L);
        input.term(5, "비고2", null, 10L);

        assertThat(input.run()).extracting(g -> g.finding().rule(), TermGroup::termIds, TermGroup::groupKey)
                .containsExactly(tuple(StandardizationRule.TERM_ABBR_DUPLICATE, List.of(1L, 2L), "MBR_NM"),
                        tuple(StandardizationRule.TERM_ABBR_SHARED, List.of(1L, 2L, 3L), "MBR_NM"));
        assertThat(StandardizationRule.TERM_ABBR_SHARED.severity()).isEqualTo(StandardizationRule.Severity.WARNING);
    }

    @Test
    @DisplayName("용어명 : 공백·'_'·대소문자를 무시하고 같으면 오류, 도메인이 같은지 근거에 남긴다")
    void name() {
        Input input = new Input();
        input.term(1, "회원_이름", "MBR_NM", 10L);
        input.term(2, "회원 이름", "MBR_NAME", 20L);
        input.term(3, "Member_ID", "MBR_ID", 10L);
        input.term(4, "member id", "MEMBER_ID", 10L);

        List<TermGroup> groups = input.run();
        assertThat(groups).extracting(g -> g.finding().rule(), TermGroup::termIds, g -> g.finding().evidence().get("sameDomain"))
                .containsExactly(tuple(StandardizationRule.TERM_NAME_DUPLICATE, List.of(3L, 4L), "true"),
                        tuple(StandardizationRule.TERM_NAME_DUPLICATE, List.of(1L, 2L), "false"));
    }

    @Test
    @DisplayName("단어 구성 : 순서를 무시하고 같으면 참고 (순서가 다르면 근거에 표시), 순서·이름까지 같으면 용어명 중복에 맡긴다")
    void sameWords() {
        Input input = new Input();
        input.term(1, "회원_등록_일자", "MBR_REG_DT", 10L, 1, 3, 4);
        input.term(2, "등록_회원_일자", "REG_MBR_DT", 10L, 3, 1, 4);
        input.term(3, "회원_이름", "MBR_NM", 10L, 1, 2);
        input.term(4, "회원_이름", "MBR_NM2", 20L, 1, 2);

        List<TermGroup> groups = input.run();
        assertThat(groups).filteredOn(g -> g.finding().rule() == StandardizationRule.TERM_SAME_WORDS)
                .extracting(TermGroup::termIds, TermGroup::groupKey, g -> g.finding().evidence().get("orderDiffers"))
                .containsExactly(tuple(List.of(1L, 2L), "회원 + 등록 + 일자", "true"));
        assertThat(groups).extracting(g -> g.finding().rule()).containsExactly(StandardizationRule.TERM_NAME_DUPLICATE, StandardizationRule.TERM_SAME_WORDS);
    }

    @Test
    @DisplayName("이음동의어 : 용어명 = 다른 용어의 이음동의어 (두 용어씩), 단어 이음동의어를 대표 단어로 바꾸면 같은 구성 (한 단계, 순서 포함)")
    void synonyms() {
        Input input = new Input();
        input.term(1, "회원_이름", "MBR_NM", 10L, 1, 2);
        input.synonym(1, "회원명");
        input.synonym(1, " ");
        input.term(2, "회원명", "MBRNM", 10L);
        // 단어 '이름' 의 이음동의어 '성명' (단어 6) → 회원_성명 = 회원_이름
        input.wordSynonym(2, "성명");
        input.term(3, "회원_성명", "MBR_FLNM", 10L, 1, 6);
        input.term(4, "성명_회원", "FLNM_MBR", 10L, 6, 1);

        List<TermGroup> groups = input.run().stream().filter(g -> g.finding().rule() == StandardizationRule.TERM_SYNONYM).toList();
        assertThat(groups).extracting(TermGroup::termIds, g -> g.finding().evidence().get("kind"))
                .containsExactly(tuple(List.of(1L, 2L), "TERM"), tuple(List.of(1L, 3L), "WORD"));
        assertThat(groups.get(0).notes()).containsExactly(java.util.Map.entry(1L, "회원명"));
        assertThat(groups.get(1).notes()).containsExactly(java.util.Map.entry(3L, "성명 → 이름"));
        assertThat(groups.get(1).groupKey()).isEqualTo("회원_이름");
    }

    @Test
    @DisplayName("금칙어 : 단어 경계(단어 매핑, 없으면 '_'·공백)에서 정확히 같을 때만 주의. 부분 문자열·자기 이름은 보지 않는다")
    void restricted() {
        Input input = new Input();
        input.restricted(1, "멤버");      // 회원의 금칙어
        input.restricted(2, "이름");      // 자기 이름 (데이터 오류) — 보지 않는다
        input.restricted(5, "회원");      // 고객의 금칙어로 회원이 올라 있다
        input.words.add(word(7, "멤버", "MEMB"));
        input.term(1, "멤버_이름", "MEMB_NM", 10L, 7, 2);
        input.term(2, "멤버십등급", "MSHP_GRD", 10L);   // '멤버' 는 부분 문자열일 뿐
        input.term(3, "멤버 등급", "MEMB_GRD", 10L);    // 매핑 없음 → 공백 경계
        input.term(4, "회원_이름", "MBR_NM", 10L, 1, 2);

        List<TermGroup> groups = input.run().stream().filter(g -> g.finding().rule() == StandardizationRule.TERM_RESTRICTED_WORD).toList();
        assertThat(groups).extracting(TermGroup::termIds, g -> g.finding().actualValue(), g -> g.finding().expectedValue(), g -> g.finding().evidence().get("boundary"))
                .containsExactly(tuple(List.of(1L), "멤버", "회원", "TERM_WORD"), tuple(List.of(3L), "멤버", "회원", "TERM_NAME"),
                        tuple(List.of(4L), "회원", "고객", "TERM_WORD"));
    }

    @Test
    @DisplayName("fingerprint : 같은 입력이면 같은 값과 순서, 그룹 구성원이 바뀌면 다른 값")
    void stable() {
        Input input = new Input();
        input.term(2, "회원명", "MBR_NM", 10L);
        input.term(1, "회원_명", "MBR_NM", 10L);
        List<String> first = input.run().stream().map(g -> g.finding().fingerprint()).toList();
        assertThat(input.run().stream().map(g -> g.finding().fingerprint()).toList()).isEqualTo(first);
        input.term(3, "회원 명", "MBR_NM", 10L);
        assertThat(input.run().stream().map(g -> g.finding().fingerprint()).toList()).doesNotContainAnyElementsOf(first);
    }

    // ------------------------------------------------------------------ 보조

    private static WordRow word(long id, String name, String abbreviation) {
        return new WordRow(id, name, abbreviation, true);
    }

    private static final class Input {
        final List<TermRow> terms = new ArrayList<>();
        final List<TermWordRow> termWords = new ArrayList<>();
        final List<NameRow> termSynonyms = new ArrayList<>();
        final List<WordRow> words = new ArrayList<>(WORDS);
        final List<NameRow> wordSynonyms = new ArrayList<>();
        final List<NameRow> restricted = new ArrayList<>();

        void term(long id, String name, String abbreviation, Long domainId, long... wordIds) {
            terms.add(new TermRow(id, name, abbreviation, domainId, domainId == null ? null : "D" + domainId, true));
            for (int i = 0; i < wordIds.length; i++) {
                termWords.add(new TermWordRow(id, wordIds[i], i + 1));
            }
        }

        void synonym(long termId, String name) {
            termSynonyms.add(new NameRow(termId, name));
        }

        void wordSynonym(long wordId, String name) {
            wordSynonyms.add(new NameRow(wordId, name));
        }

        void restricted(long wordId, String name) {
            restricted.add(new NameRow(wordId, name));
        }

        List<TermGroup> run() {
            return TermDuplicateRules.diagnose(1L, terms, termWords, termSynonyms, words, wordSynonyms, restricted);
        }
    }
}
