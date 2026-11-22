package com.lexorma.data.mapping.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.AttributeSuggestion;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.Level;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.OverlayStatus;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.TableSuggestion;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Source;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository.CandidateColumn;
import com.lexorma.data.mapping.repository.MappingSuggestionQueryRepository.CandidateTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 자동 매핑 후보·ERD 표시의 순수 판정 (M-7) : 근거·수준·충돌·우선순위·표시 상태 */
class MappingSuggestionServiceTest {

    private static Source attribute(long id, String name, Long termId, String abbreviation, Long mappingId) {
        return new Source(1L, "모델", 10L, "고객", 100L, 1L, "S", 1000L, "TB_CUST", id, name, false, termId, termId == null ? null : "용어" + termId, abbreviation,
                null, null, null, null, null, mappingId, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CandidateColumn column(long id, String name, String desc, Long termId, boolean approved) {
        return new CandidateColumn(id, name, desc, "VARCHAR", 20, null, null, true, false, false, termId, termId == null ? null : "용어" + termId, termId == null ? null : approved);
    }

    @Test
    @DisplayName("근거 우선순위 : 같은 승인 용어 → 미승인 용어 → 컬럼명(용어 약어) → 논리명. 이름만 같으면 REFERENCE")
    void reasonsAndLevel() {
        Source a = attribute(1, "고객 번호", 7L, "CUST_NO", null);
        List<AttributeSuggestion> result = MappingSuggestionService.suggest(DbType.POSTGRESQL, List.of(a),
                List.of(column(11, "OTHER", "고객_번호", null, false), column(12, "cust_no", null, 99L, true), column(13, "X", null, 7L, true)),
                Map.of(7L, true), Set.of());
        AttributeSuggestion s = result.get(0);
        assertThat(s.candidates()).extracting(c -> c.columnId()).containsExactly(13L, 12L, 11L);
        assertThat(s.candidates().get(0).reasons()).containsExactly("STANDARD_TERM");
        assertThat(s.candidates().get(0).level()).isEqualTo(Level.STANDARD);
        assertThat(s.candidates().get(1).reasons()).containsExactly("COLUMN_NAME");
        assertThat(s.candidates().get(2).reasons()).as("공백·_ 무시 논리명 비교").containsExactly("LOGICAL_NAME");
        assertThat(s.candidates().subList(1, 3)).allSatisfy(c -> assertThat(c.level()).isEqualTo(Level.REFERENCE));
        assertThat(s.conflicts()).as("근거마다 다른 컬럼").containsExactly("REASONS_DIFFER");

        List<AttributeSuggestion> unapproved = MappingSuggestionService.suggest(DbType.POSTGRESQL, List.of(a), List.of(column(13, "X", null, 7L, false)), Map.of(7L, false), Set.of());
        assertThat(unapproved.get(0).candidates().get(0).reasons()).containsExactly("STANDARD_TERM_UNAPPROVED");
        assertThat(unapproved.get(0).candidates().get(0).level()).isEqualTo(Level.REFERENCE);
        assertThat(unapproved.get(0).notes()).containsExactly("TERM_NOT_APPROVED");
    }

    @Test
    @DisplayName("매핑된 속성은 후보 없음, 용어 없는 속성은 NO_TERM, 근거 없는 컬럼은 후보가 아니다, 최우선 후보를 두 속성이 나누면 SHARED")
    void mappedNoTermShared() {
        Source mapped = attribute(1, "고객 번호", 7L, "CUST_NO", 500L);
        Source noTerm = attribute(2, "비고", null, null, null);
        Source a = attribute(3, "고객 명", 8L, "CUST_NM", null);
        Source b = attribute(4, "고객 이름", 8L, "CUST_NM", null);
        List<AttributeSuggestion> result = MappingSuggestionService.suggest(DbType.POSTGRESQL, List.of(mapped, noTerm, a, b),
                List.of(column(21, "CUST_NM", null, 8L, true), column(22, "ETC", null, null, false)), Map.of(7L, true, 8L, true), Set.of());
        assertThat(result.get(0).mapped()).isTrue();
        assertThat(result.get(0).unavailableReason()).isEqualTo("ALREADY_MAPPED");
        assertThat(result.get(0).candidates()).isEmpty();
        assertThat(result.get(1).notes()).containsExactly("NO_TERM");
        assertThat(result.get(1).unavailableReason()).isEqualTo("NO_CANDIDATE");
        assertThat(result.get(2).conflicts()).contains("SHARED");
        assertThat(result.get(2).candidates().get(0).sharedWith()).containsExactly("고객 이름");
        assertThat(result.get(3).conflicts()).contains("SHARED");
    }

    @Test
    @DisplayName("주 식별자 용어 = PK 용어 : 순서 무관, 개수까지 같고 용어 없는 PK 컬럼이 없을 때만")
    void keyTerms() {
        assertThat(MappingSuggestionService.sameKeyTerms(List.of(1L, 2L), List.of(2L, 1L))).isTrue();
        assertThat(MappingSuggestionService.sameKeyTerms(List.of(1L), List.of(1L, 2L))).isFalse();
        assertThat(MappingSuggestionService.sameKeyTerms(List.of(1L, 2L), new ArrayList<>(Arrays.asList(1L, null)))).isFalse();
    }

    @Test
    @DisplayName("테이블 후보 순서 : 가장 앞선 근거 → 근거 수 → 이름. KEY_TERMS 만 STANDARD")
    void tableOrder() {
        TableSuggestion comment = MappingSuggestionService.tableSuggestion(new CandidateTable(1L, 1L, "S", "A", "고객", false), Set.of("TABLE_COMMENT"), 0, 3);
        TableSuggestion nameAndComment = MappingSuggestionService.tableSuggestion(new CandidateTable(2L, 1L, "S", "B", "고객", false), Set.of("TABLE_COMMENT", "TABLE_NAME"), 0, 3);
        TableSuggestion key = MappingSuggestionService.tableSuggestion(new CandidateTable(3L, 1L, "S", "C", null, true), Set.of("KEY_TERMS"), 2, 3);
        List<TableSuggestion> sorted = new ArrayList<>(List.of(comment, nameAndComment, key));
        sorted.sort(MappingSuggestionService.TABLE_ORDER);
        assertThat(sorted).extracting(TableSuggestion::tableId).containsExactly(3L, 2L, 1L);
        assertThat(key.level()).isEqualTo(Level.STANDARD);
        assertThat(nameAndComment.reasons()).containsExactly("TABLE_NAME", "TABLE_COMMENT");
        assertThat(nameAndComment.level()).isEqualTo(Level.REFERENCE);
    }

    private static Row row(CheckStatus status) {
        return new Row("r", 1L, "모델", 1L, 10L, "고객", 1L, "a", 1L, "S", 1L, "T", 1L, "C", CheckType.TERM, status, null, null, null, false, null);
    }

    @Test
    @DisplayName("ERD 표시 상태 : 불일치 > 비교 불가 > 매핑됨, 미매핑 행은 매핑된 객체의 상태를 바꾸지 않는다")
    void overlayStatus() {
        assertThat(MappingSuggestionService.worst(List.of())).isEqualTo(OverlayStatus.MAPPED);
        assertThat(MappingSuggestionService.worst(List.of(row(CheckStatus.MATCH), row(CheckStatus.UNMAPPED)))).isEqualTo(OverlayStatus.MAPPED);
        assertThat(MappingSuggestionService.worst(List.of(row(CheckStatus.NOT_COMPARABLE), row(CheckStatus.MATCH)))).isEqualTo(OverlayStatus.NOT_COMPARABLE);
        assertThat(MappingSuggestionService.worst(List.of(row(CheckStatus.NOT_COMPARABLE), row(CheckStatus.MISMATCH)))).isEqualTo(OverlayStatus.MISMATCH);
    }

    @Test
    @DisplayName("M7-F02 : 있는 매핑은 정합성과 무관하게 MAPPED, 정합성은 판정 그대로 모은다 (비교 불가를 일치로 바꾸지 않는다), 사유는 판정의 것만")
    void mappingAndQualitySeparated() {
        var notComparable = MappingSuggestionService.link(1L, "a", 2L, "C", com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.LinkKind.DIRECT,
                List.of(row(CheckStatus.MATCH), row(CheckStatus.NOT_COMPARABLE)));
        assertThat(notComparable.mappingStatus()).isEqualTo(com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.MappingStatus.MAPPED);
        assertThat(notComparable.qualityStatus()).isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(notComparable.issues()).singleElement().satisfies(i -> assertThat(i.status()).isEqualTo(CheckStatus.NOT_COMPARABLE));
        assertThat(MappingSuggestionService.quality(List.of(row(CheckStatus.MATCH), row(CheckStatus.UNMAPPED)))).as("미매핑 행은 매핑된 객체의 판정이 아니다").isEqualTo(CheckStatus.MATCH);
        assertThat(MappingSuggestionService.quality(List.of(row(CheckStatus.NOT_COMPARABLE), row(CheckStatus.MISMATCH)))).isEqualTo(CheckStatus.MISMATCH);
        assertThat(MappingSuggestionService.quality(List.of())).isEqualTo(CheckStatus.MATCH);
    }
}
