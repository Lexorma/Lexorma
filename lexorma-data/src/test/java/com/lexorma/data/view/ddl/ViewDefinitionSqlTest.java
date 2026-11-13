package com.lexorma.data.view.ddl;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VIEW 정의 SQL 정적 검사 (M-15 D-23·D-27). 정상 SQL 을 거절하지 않고(오탐), 명백한 잘못만 거절한다.
 * 원문은 그대로 두고 DDL 본문은 끝의 ; 와 그 뒤만 뺀다.
 */
class ViewDefinitionSqlTest {

    @ParameterizedTest(name = "허용 : {0}")
    @ValueSource(strings = {
            // 1 일반 SELECT, 2 WITH CTE, 3 중첩 SELECT
            "SELECT\n    user_id,\n    user_name\nFROM tb_user",
            "WITH active_users AS (\n    SELECT user_id\n    FROM tb_user\n    WHERE active = true\n)\nSELECT *\nFROM active_users",
            "SELECT u.user_id FROM (SELECT user_id FROM tb_user WHERE user_id IN (SELECT user_id FROM tb_login)) u",
            // 4 문자열 안 명령, 5 주석 안 명령, 6 인용 식별자
            "SELECT\n    'DELETE FROM tb_user' AS example_text\nFROM tb_user",
            "SELECT\n    user_id\nFROM tb_user\n-- UPDATE statement example",
            "SELECT user_id /* DROP TABLE tb_user; */ FROM tb_user",
            "SELECT \"update\", \"DELETE\" AS \"insert\" FROM tb_user",
            // 7 끝의 ;, 13 본문의 문자열 안 ;
            "SELECT 1;",
            "SELECT 1;;  -- 끝",
            "SELECT 'a;b' AS x, \"c;d\" FROM t",
            // 14 PostgreSQL 달러 인용
            "select $$; DELETE FROM t$$ as x",
            "select $tag$ it's ; $tag$ as x",
            // 예전 오탐 : 명령과 같은 이름의 별칭·컬럼, 점 뒤의 이름, FOR UPDATE, CHARACTER SET, 함수 REPLACE
            "SELECT update_dt, delete_yn, t.update FROM t",
            "SELECT a AS update, b AS delete FROM t",
            "SELECT id FROM t FOR UPDATE",
            "SELECT CAST(nm AS CHAR(10) CHARACTER SET utf8) FROM t",
            "SELECT REPLACE(nm, 'a', 'b') FROM t",
            "(SELECT 1) UNION ALL (SELECT 2)",
            "WITH a AS (SELECT 1 AS n), b AS (SELECT n FROM a) SELECT n, COUNT(*) AS cnt FROM b GROUP BY n"})
    void accepted(String sql) {
        ViewDefinitionSql.Checked checked = ViewDefinitionSql.check(DbType.POSTGRESQL, sql);
        assertThat(sql).startsWith(checked.body()); // 원문의 앞부분 그대로 (끝의 ; 뒤만 뺀다)
    }

    @ParameterizedTest(name = "거절 : {0}")
    @ValueSource(strings = {
            "", "   ",
            // 8 여러 문장, 9 CREATE VIEW 전체, 10 명백한 DML·DDL
            "SELECT * FROM tb_user;\nDELETE FROM tb_user;",
            "SELECT 1; 'x'",
            "CREATE VIEW vw_user AS\nSELECT * FROM tb_user;",
            "DELETE FROM tb_user",
            "UPDATE tb_user SET a = 1",
            "INSERT INTO tb_user VALUES (1)",
            "WITH d AS (DELETE FROM tb_user RETURNING *) SELECT * FROM d",
            "WITH a AS (SELECT 1) INSERT INTO t SELECT * FROM a",
            "SELECT * INTO tb_copy FROM tb_user",
            "SELECT (DROP TABLE t)",
            "VALUES (1)",
            // 11 닫히지 않은 문자열, 12 괄호
            "SELECT 'abc", "SELECT \"abc", "SELECT (1", "SELECT 1)", "/* 닫히지 않음 SELECT 1", "select $$ abc"})
    void rejected(String sql) {
        assertThatThrownBy(() -> ViewDefinitionSql.check(DbType.POSTGRESQL, sql)).isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL));
    }

    @Test
    @DisplayName("15 DBMS 별 차이 : MySQL 은 \\' 이스케이프·# 주석·`식별자`, PostgreSQL 은 겹친 블록 주석·달러 인용")
    void dialects() {
        assertThat(ViewDefinitionSql.check(DbType.MYSQL, "SELECT 'it\\'s' AS `delete` # DROP\nFROM t").body()).isEqualTo("SELECT 'it\\'s' AS `delete` # DROP\nFROM t");
        assertThatThrownBy(() -> ViewDefinitionSql.check(DbType.POSTGRESQL, "SELECT 'it\\'s' AS x")).isInstanceOf(CustomException.class);
        assertThat(ViewDefinitionSql.check(DbType.POSTGRESQL, "SELECT 1 /* a /* b */ c */").body()).isEqualTo("SELECT 1 /* a /* b */ c */");
        // Oracle 은 겹친 주석이 없다 : /* a /* b */ 에서 끝나고 " c */" 는 형식 검사가 알아챌 수 없는 문법 오류다 (DB 가 판단)
        assertThat(ViewDefinitionSql.check(DbType.ORACLE, "SELECT 1 /* a /* b */ c */").body()).isEqualTo("SELECT 1 /* a /* b */ c */");
        assertThatThrownBy(() -> ViewDefinitionSql.check(DbType.ORACLE, "SELECT 1 /* a /* b */ 'c */")).isInstanceOf(CustomException.class);
        assertThat(ViewDefinitionSql.check(DbType.ORACLE, "select $$ as x from dual").body()).isEqualTo("select $$ as x from dual");
    }

    @Test
    @DisplayName("본문 : 끝의 ; 와 그 뒤 공백·주석만 빼고, -- 주석으로 끝나면 알린다 (원문의 대소문자·공백·주석은 그대로)")
    void body() {
        assertThat(ViewDefinitionSql.check(DbType.POSTGRESQL, "select  A\n  from T -- x\n;\n-- 끝\n")).isEqualTo(new ViewDefinitionSql.Checked("select  A\n  from T -- x", true));
        assertThat(ViewDefinitionSql.check(DbType.POSTGRESQL, "select 1 /* ; */  \n\n")).isEqualTo(new ViewDefinitionSql.Checked("select 1 /* ; */", false));
    }
}
