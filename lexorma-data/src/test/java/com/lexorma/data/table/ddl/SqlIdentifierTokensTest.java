package com.lexorma.data.table.ddl;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.table.ddl.SqlIdentifierTokens.Tokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** M-7 물리명 추천 일괄 적용 1차 : 이름 참조 판정용 낱말 분리 (보수적 — 나오면 참조, 나눌 수 없으면 opaque) */
class SqlIdentifierTokensTest {

    private static Tokens pg(String sql) {
        return SqlIdentifierTokens.scan(DbType.POSTGRESQL, sql);
    }

    @Test
    @DisplayName("낱말 : 컬럼·한정 이름·함수명을 대문자로 모은다 (대소문자 무시)")
    void words() {
        Tokens t = pg("amt >= 0 and length(Mbr_Nm) > 0 and s.tb_order.qty < 10");
        assertThat(t.opaque()).isFalse();
        assertThat(t.mentions("AMT")).isTrue();
        assertThat(t.mentions("mbr_nm")).isTrue();
        assertThat(t.mentions("LENGTH")).isTrue(); // 함수명도 낱말 (보수적)
        assertThat(t.mentions("TB_ORDER")).isTrue();
        assertThat(t.mentions(" QTY ")).isTrue();
        assertThat(t.mentions("MBR")).isFalse(); // 낱말 일부는 아니다
        assertThat(t.mentions(null)).isFalse();
    }

    @Test
    @DisplayName("문자열 리터럴·주석 안의 이름은 참조가 아니다")
    void literalsAndComments() {
        Tokens t = pg("STTS_CD in ('MBR_NM', 'it''s') -- MBR_ID\n /* RMRK /* 겹친 주석 */ ETC */ and X = $$NM2$$ and Y = $t$ZZ$t$ and E'a\\'MBR_GRD' = 'b'");
        assertThat(t.opaque()).isFalse();
        assertThat(t.identifiers()).contains("STTS_CD", "IN", "AND", "X", "Y");
        assertThat(t.identifiers()).doesNotContain("MBR_NM", "MBR_ID", "RMRK", "ETC", "NM2", "ZZ", "MBR_GRD", "S");
    }

    @Test
    @DisplayName("따옴표 식별자 : 내용을 낱말로 (대소문자 구분 식별자도 같은 이름으로 — 보수적), MySQL 백틱·# 주석·\\ 이스케이프")
    void quotedIdentifiers() {
        assertThat(pg("\"Mbr_Nm\" is not null and \"a\"\"b\" > 0").identifiers()).contains("MBR_NM", "A\"B");
        Tokens mysql = SqlIdentifierTokens.scan(DbType.MYSQL, "`mbr_nm` > 0 # RMRK\n and nm = 'x\\'MBR_ID'");
        assertThat(mysql.opaque()).isFalse();
        assertThat(mysql.identifiers()).contains("MBR_NM", "NM").doesNotContain("RMRK", "MBR_ID");
        // MySQL 의 "…" 는 문자열이지만 식별자로 본다 (보수적)
        assertThat(SqlIdentifierTokens.scan(DbType.MARIADB, "\"MBR_NM\" = 1").mentions("MBR_NM")).isTrue();
    }

    @Test
    @DisplayName("Oracle q 문자열은 건너뛴다")
    void oracleQuote() {
        Tokens t = SqlIdentifierTokens.scan(DbType.ORACLE, "NM <> q'[MBR_ID ' ]' and NO# > 0");
        assertThat(t.opaque()).isFalse();
        assertThat(t.identifiers()).contains("NM", "NO#").doesNotContain("MBR_ID");
    }

    @Test
    @DisplayName("SELECT * · NATURAL JOIN · 별칭·JOIN·서브쿼리")
    void starAndNatural() {
        Tokens t = pg("select a.*, b.mbr_nm as nm from tb_mbr a natural join (select * from tb_ord) b");
        assertThat(t.star()).isTrue();
        assertThat(t.natural()).isTrue();
        assertThat(t.identifiers()).contains("TB_MBR", "TB_ORD", "MBR_NM", "NM", "A", "B");
        assertThat(pg("select 'a*b', x from t -- *").star()).isFalse();
    }

    @Test
    @DisplayName("나눌 수 없음 : 닫히지 않은 따옴표·주석, 유니코드 이스케이프 식별자")
    void opaque() {
        assertThat(pg("NM = 'abc").opaque()).isTrue();
        assertThat(pg("NM /* abc").opaque()).isTrue();
        assertThat(pg("\"NM = 1").opaque()).isTrue();
        assertThat(pg("U&\"\\0041BC\" > 0").opaque()).isTrue();
        assertThat(pg("$x$abc").opaque()).isTrue();
        assertThat(pg(null)).isEqualTo(Tokens.EMPTY);
        assertThat(pg("  ").opaque()).isFalse();
        // 합치면 하나라도 opaque 이면 opaque
        assertThat(pg("a").merge(pg("'b")).opaque()).isTrue();
    }
}
