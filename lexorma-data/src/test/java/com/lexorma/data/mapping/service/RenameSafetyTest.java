package com.lexorma.data.mapping.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.service.RenameSafety.Candidate;
import com.lexorma.data.mapping.service.RenameSafety.CheckText;
import com.lexorma.data.mapping.service.RenameSafety.IndexText;
import com.lexorma.data.mapping.service.RenameSafety.ViewText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** M-7 물리명 추천 일괄 적용 1차 : 이름 변경 안전성 판정 (순수 함수) */
class RenameSafetyTest {

    private static final Long T1 = 1L;
    private static final Long T2 = 2L;
    private static final Long C_NM = 11L;
    private static final Long C_AMT = 12L;

    private static ViewText view(long id, String name, String sql, Set<Long> tables, Set<Long> views) {
        return new ViewText(id, name, RenameSafety.tokens(DbType.POSTGRESQL, sql), tables, views);
    }

    private static List<ApplyCode> codes(List<ApplyIssue> issues) {
        return issues.stream().map(ApplyIssue::code).toList();
    }

    @Test
    @DisplayName("추천끼리 충돌 : 같은 이름 공간·같은 새 이름(대소문자·공백 무시)만, 다른 이름 공간은 아니다")
    void duplicates() {
        Map<Long, List<Long>> d = RenameSafety.duplicates(List.of(
                new Candidate(1L, 100L, "MBR_NM"), new Candidate(2L, 100L, " mbr_nm "), new Candidate(3L, 100L, "MBR_NO"),
                new Candidate(4L, 200L, "MBR_NM"), new Candidate(5L, 100L, null)));
        assertThat(d).containsOnlyKeys(1L, 2L);
        assertThat(d.get(1L)).containsExactly(2L);
        assertThat(d.get(2L)).containsExactly(1L);
        // 같은 대상이 두 번 들어와도 충돌이 아니다
        assertThat(RenameSafety.duplicates(List.of(new Candidate(1L, 100L, "A"), new Candidate(1L, 100L, "A")))).isEmpty();
    }

    @Test
    @DisplayName("CHECK : 구성 컬럼·식의 실제 낱말은 막고, 문자열 리터럴·다른 이름은 막지 않는다. 나눌 수 없는 식은 판정 불가")
    void checks() {
        RenameSafety safety = new RenameSafety(List.of(), List.of(
                new CheckText(T1, "CK_AMT", RenameSafety.tokens(DbType.POSTGRESQL, "AMT >= 0"), Set.of()),
                new CheckText(T1, "CK_LIT", RenameSafety.tokens(DbType.POSTGRESQL, "STTS in ('NM', 'X')"), Set.of()),
                new CheckText(T1, "CK_COLS", RenameSafety.tokens(DbType.POSTGRESQL, "1 = 1"), Set.of(C_NM)),
                new CheckText(T1, "CK_QUOTED", RenameSafety.tokens(DbType.POSTGRESQL, "\"Cnt\" > 0"), Set.of())), List.of());
        assertThat(codes(safety.columnRename(T1, "TB_A", C_AMT, "amt"))).containsExactly(ApplyCode.CHECK_REFERENCE);
        assertThat(safety.columnRename(T1, "TB_A", C_AMT, "amt").get(0).related()).containsExactly("CK_AMT");
        assertThat(codes(safety.columnRename(T1, "TB_A", C_NM, "NM"))).containsExactly(ApplyCode.CHECK_REFERENCE); // 리터럴 'NM' 이 아니라 구성 컬럼 때문
        assertThat(safety.columnRename(T1, "TB_A", C_NM, "NM").get(0).related()).containsExactly("CK_COLS");
        assertThat(codes(safety.columnRename(T1, "TB_A", 13L, "CNT"))).containsExactly(ApplyCode.CHECK_REFERENCE); // 인용 식별자
        assertThat(safety.columnRename(T1, "TB_A", 14L, "STTS_NM")).isEmpty();
        assertThat(safety.columnRename(T2, "TB_B", 21L, "AMT")).isEmpty(); // 다른 테이블의 CHECK 는 보지 않는다

        RenameSafety broken = new RenameSafety(List.of(), List.of(new CheckText(T1, "CK_X", RenameSafety.tokens(DbType.POSTGRESQL, "NM = 'a"), Set.of())), List.of());
        assertThat(codes(broken.columnRename(T1, "TB_A", 99L, "ANY"))).containsExactly(ApplyCode.REFERENCE_UNVERIFIABLE);
        assertThat(codes(broken.tableRename(T1, "TB_A"))).containsExactly(ApplyCode.REFERENCE_UNVERIFIABLE);
    }

    @Test
    @DisplayName("인덱스 식·조건의 낱말")
    void indexes() {
        RenameSafety safety = new RenameSafety(List.of(), List.of(),
                List.of(new IndexText(T1, "IX_LOWER", RenameSafety.tokens(DbType.POSTGRESQL, "lower(mbr_nm)")),
                        new IndexText(T1, "IX_PART", RenameSafety.tokens(DbType.POSTGRESQL, "use_yn = 'Y'"))));
        assertThat(codes(safety.columnRename(T1, "TB_A", 1L, "MBR_NM"))).containsExactly(ApplyCode.INDEX_REFERENCE);
        assertThat(codes(safety.columnRename(T1, "TB_A", 1L, "USE_YN"))).containsExactly(ApplyCode.INDEX_REFERENCE);
        assertThat(safety.columnRename(T1, "TB_A", 1L, "Y")).isEmpty();
    }

    @Test
    @DisplayName("VIEW 테이블 참조 : 메타데이터 또는 정의의 낱말 (같은·다른 스키마), 리터럴은 아니다")
    void viewTableReference() {
        RenameSafety safety = new RenameSafety(List.of(
                view(1, "V_META", "select 1", Set.of(T1), Set.of()),
                view(2, "V_TEXT", "select x from other.tb_a", Set.of(), Set.of()),
                view(3, "V_LIT", "select 'TB_A' from dual", Set.of(), Set.of())), List.of(), List.of());
        List<ApplyIssue> issues = safety.tableRename(T1, "TB_A");
        assertThat(codes(issues)).containsExactly(ApplyCode.VIEW_REFERENCE);
        assertThat(issues.get(0).related()).containsExactlyInAnyOrder("V_META", "V_TEXT");
        assertThat(safety.tableRename(T2, "TB_B")).isEmpty();
    }

    @Test
    @DisplayName("VIEW 컬럼 참조 : 그 테이블을 직접·간접(VIEW 를 거쳐) 참조하는 VIEW 의 컬럼명 낱말·별칭·JOIN·서브쿼리, * · NATURAL")
    void viewColumnReference() {
        RenameSafety safety = new RenameSafety(List.of(
                view(1, "V_BASE", "select a.mbr_nm as nm from tb_a a join tb_b b on a.id = b.id", Set.of(), Set.of()),
                view(2, "V_SUB", "select z.amt from (select amt from tb_a) z", Set.of(), Set.of()),
                view(3, "V_STAR", "select * from tb_a", Set.of(), Set.of()),
                view(4, "V_ON_VIEW", "select qty from v_base", Set.of(), Set.of()),
                view(5, "V_META_ONLY", "select qty from somewhere", Set.of(), Set.of(1L)),
                view(6, "V_NATURAL", "select k from tb_b natural join tb_c", Set.of(), Set.of()),
                view(7, "V_OTHER", "select mbr_nm from tb_c", Set.of(), Set.of())), List.of(), List.of());
        List<ApplyIssue> nm = safety.columnRename(T1, "TB_A", 1L, "MBR_NM");
        assertThat(codes(nm)).containsExactly(ApplyCode.VIEW_REFERENCE, ApplyCode.VIEW_REFERENCE);
        assertThat(nm.get(0).related()).containsExactly("V_BASE"); // V_OTHER 는 TB_A 를 참조하지 않는다
        assertThat(nm.get(1).related()).containsExactly("V_STAR");
        assertThat(safety.columnRename(T1, "TB_A", 2L, "AMT").get(0).related()).containsExactly("V_SUB");
        // 간접 : V_BASE 를 참조하는 VIEW (정의의 낱말 / 메타데이터)
        assertThat(safety.columnRename(T1, "TB_A", 3L, "QTY").get(0).related()).containsExactlyInAnyOrder("V_ON_VIEW", "V_META_ONLY");
        // NATURAL : 그 테이블을 참조하는 VIEW 만
        assertThat(safety.columnRename(T2, "TB_B", 4L, "ZZZ").get(0).related()).containsExactlyInAnyOrder("V_NATURAL");
        assertThat(safety.columnRename(T2, "TB_B", 4L, "ZZZ").get(0).related()).doesNotContain("V_BASE");
        assertThat(safety.columnRename(99L, "TB_X", 5L, "MBR_NM")).isEmpty();
    }

    @Test
    @DisplayName("나눌 수 없는 VIEW 정의가 하나라도 있으면 모든 이름 변경이 판정 불가")
    void opaqueView() {
        RenameSafety safety = new RenameSafety(List.of(view(1, "V_U", "select U&\"\\0041\" from t", Set.of(), Set.of())), List.of(), List.of());
        assertThat(codes(safety.tableRename(T1, "TB_A"))).containsExactly(ApplyCode.REFERENCE_UNVERIFIABLE);
        assertThat(codes(safety.columnRename(T1, "TB_A", 1L, "NM"))).containsExactly(ApplyCode.REFERENCE_UNVERIFIABLE);
    }

    @Test
    @DisplayName("사유의 이름은 3개까지, related 에는 모두")
    void shownNames() {
        assertThat(RenameSafety.shown(List.of("A", "B", "C", "D", "A"))).isEqualTo("A, B, C …");
        assertThat(RenameSafety.shown(List.of("A"))).isEqualTo("A");
    }
}
