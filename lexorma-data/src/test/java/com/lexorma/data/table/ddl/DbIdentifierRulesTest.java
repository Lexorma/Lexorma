package com.lexorma.data.table.ddl;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.table.ddl.DbIdentifierRules.Code;
import com.lexorma.data.table.ddl.DbIdentifierRules.Issue;
import com.lexorma.data.table.ddl.DbIdentifierRules.Kind;
import com.lexorma.data.table.domain.entity.TableInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DBMS 식별자 규칙 (M-7 보강 1차) : 길이(바이트·문자), 비인용 식별자 문자, 권장 형식, DDL 이름 자르기 */
class DbIdentifierRulesTest {

    private static List<Code> codes(DbType db, Kind kind, String name) {
        return DbIdentifierRules.check(db, kind, name).stream().map(Issue::code).toList();
    }

    @Test
    @DisplayName("길이 경계 : PostgreSQL 63바이트, Oracle 30바이트, MySQL·MariaDB 64자 — 넘으면 IDENTIFIER_LENGTH_EXCEEDED (실제·허용 길이 포함)")
    void lengthBoundaries() {
        assertThat(codes(DbType.POSTGRESQL, Kind.TABLE, "A".repeat(63))).isEmpty();
        assertThat(DbIdentifierRules.check(DbType.POSTGRESQL, Kind.TABLE, "A".repeat(64)))
                .singleElement().satisfies(i -> {
                    assertThat(i.code()).isEqualTo(Code.IDENTIFIER_LENGTH_EXCEEDED);
                    assertThat(i.length()).isEqualTo(64);
                    assertThat(i.maxLength()).isEqualTo(63);
                });
        assertThat(codes(DbType.ORACLE, Kind.COLUMN, "A".repeat(30))).isEmpty();
        assertThat(codes(DbType.ORACLE, Kind.COLUMN, "A".repeat(31))).containsExactly(Code.IDENTIFIER_LENGTH_EXCEEDED);
        for (DbType db : List.of(DbType.MYSQL, DbType.MARIADB)) {
            assertThat(codes(db, Kind.SCHEMA, "A".repeat(64))).isEmpty();
            assertThat(codes(db, Kind.SCHEMA, "A".repeat(65))).containsExactly(Code.IDENTIFIER_LENGTH_EXCEEDED);
        }
    }

    @Test
    @DisplayName("멀티바이트 : PostgreSQL·Oracle 은 UTF-8 바이트(한글 3바이트), MySQL·MariaDB 는 문자 수로 센다")
    void multibyteLength() {
        assertThat(DbIdentifierRules.length(DbType.POSTGRESQL, Kind.TABLE, "고객")).isEqualTo(6);
        assertThat(DbIdentifierRules.length(DbType.MYSQL, Kind.TABLE, "고객")).isEqualTo(2);
        // PostgreSQL : 21자 = 63바이트는 통과, 22자 = 66바이트는 초과 (문자 수로는 짧다)
        assertThat(codes(DbType.POSTGRESQL, Kind.TABLE, "가".repeat(21))).doesNotContain(Code.IDENTIFIER_LENGTH_EXCEEDED);
        assertThat(codes(DbType.POSTGRESQL, Kind.TABLE, "가".repeat(22))).contains(Code.IDENTIFIER_LENGTH_EXCEEDED);
        // Oracle : 10자 = 30바이트 통과, 11자 초과
        assertThat(codes(DbType.ORACLE, Kind.TABLE, "가".repeat(10))).doesNotContain(Code.IDENTIFIER_LENGTH_EXCEEDED);
        assertThat(codes(DbType.ORACLE, Kind.TABLE, "가".repeat(11))).contains(Code.IDENTIFIER_LENGTH_EXCEEDED);
        // MySQL : 64자 한글도 길이는 통과 (바이트가 아니라 문자)
        assertThat(codes(DbType.MYSQL, Kind.TABLE, "가".repeat(64))).doesNotContain(Code.IDENTIFIER_LENGTH_EXCEEDED);
    }

    @Test
    @DisplayName("문자 : DBMS 비인용 식별자 위반은 INVALID_CHARACTER, DBMS 는 받지만 권장 형식이 아니면 NOT_RECOMMENDED — 권장 형식과 다르다고 DBMS 오류로 보지 않는다")
    void characters() {
        // 권장 형식 : 모든 DBMS 에서 문제 없음 (Oracle 은 _ 로 시작할 수 없다)
        for (DbType db : DbType.values()) assertThat(codes(db, Kind.COLUMN, "CUST_ID$1")).as(db.name()).isEmpty();
        assertThat(codes(DbType.POSTGRESQL, Kind.COLUMN, "_TMP")).isEmpty();
        assertThat(codes(DbType.ORACLE, Kind.COLUMN, "_TMP")).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER);
        // 한글 : PostgreSQL·Oracle·MySQL 모두 받는다 → 권장 형식 아님(경고)
        for (DbType db : DbType.values()) assertThat(codes(db, Kind.TABLE, "고객")).as(db.name()).containsExactly(Code.IDENTIFIER_NOT_RECOMMENDED);
        // # : Oracle 만 받는다
        assertThat(codes(DbType.ORACLE, Kind.COLUMN, "A#B")).containsExactly(Code.IDENTIFIER_NOT_RECOMMENDED);
        assertThat(codes(DbType.POSTGRESQL, Kind.COLUMN, "A#B")).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER);
        assertThat(codes(DbType.MYSQL, Kind.COLUMN, "A#B")).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER);
        // 숫자로 시작 : MySQL·MariaDB 는 받지만(숫자만은 안 된다) PostgreSQL·Oracle 은 안 된다
        assertThat(codes(DbType.MYSQL, Kind.TABLE, "1ST")).containsExactly(Code.IDENTIFIER_NOT_RECOMMENDED);
        assertThat(codes(DbType.MARIADB, Kind.TABLE, "123")).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER);
        assertThat(codes(DbType.POSTGRESQL, Kind.TABLE, "1ST")).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER);
        assertThat(codes(DbType.ORACLE, Kind.TABLE, "1ST")).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER);
        // 공백·하이픈 : 모두 안 된다
        for (DbType db : DbType.values()) {
            assertThat(codes(db, Kind.COLUMN, "A-B")).as(db.name()).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER);
            assertThat(codes(db, Kind.COLUMN, "A B")).as(db.name()).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER);
        }
        // 비었으면 판정하지 않는다
        assertThat(codes(DbType.POSTGRESQL, Kind.TABLE, " ")).isEmpty();
        assertThat(codes(DbType.POSTGRESQL, Kind.TABLE, null)).isEmpty();
    }

    @Test
    @DisplayName("길이와 문자는 따로 판정한다 (둘 다 위반이면 2건)")
    void lengthAndCharacters() {
        assertThat(codes(DbType.ORACLE, Kind.TABLE, "_" + "A".repeat(30))).containsExactly(Code.IDENTIFIER_INVALID_CHARACTER, Code.IDENTIFIER_LENGTH_EXCEEDED);
    }

    @Test
    @DisplayName("DDL 이름 자르기 : 검사와 DDL 생성기가 같은 값 (Oracle 30·PostgreSQL 63·MySQL·MariaDB 64자, 앞에서부터)")
    void ddlNameMatchesGenerator() {
        String name = "PK_" + "T".repeat(80);
        for (DbType db : DbType.values()) {
            assertThat(DbIdentifierRules.ddlName(db, name)).as(db.name()).isEqualTo(TableDdlGenerator.objectName(db, name));
        }
        assertThat(DbIdentifierRules.ddlName(DbType.ORACLE, name)).hasSize(30);
        assertThat(DbIdentifierRules.ddlName(DbType.POSTGRESQL, name)).hasSize(63);
        assertThat(DbIdentifierRules.ddlName(DbType.MYSQL, name)).hasSize(64);
        assertThat(DbIdentifierRules.ddlName(DbType.MARIADB, "PK_SHORT")).isEqualTo("PK_SHORT");

        // 실제 DDL 의 PK 이름 = 검사가 보는 이름
        String table = "TB_" + "X".repeat(40);
        var ddl = TableDdlGenerator.generate(DbType.ORACLE, TableInfo.builder().tableName(table).build(),
                List.of(ColumnInfo.builder().columnName("ID").dataType("VARCHAR").maxLength(10).isPk(true).sortOrder(1).build()), Map.of());
        assertThat(ddl.ddl()).contains("CONSTRAINT " + DbIdentifierRules.ddlName(DbType.ORACLE, DbIdentifierRules.primaryKeyName(table)) + " PRIMARY KEY");
    }

    @Test
    @DisplayName("자동 제약조건명 잘림 : 길이 기준을 넘으면 CONSTRAINT_NAME_TRUNCATED (최종 이름 포함), 아니면 없음")
    void truncation() {
        Issue issue = DbIdentifierRules.truncation(DbType.ORACLE, Kind.PRIMARY_KEY, "PK_" + "A".repeat(28));
        assertThat(issue).isNotNull();
        assertThat(issue.code()).isEqualTo(Code.CONSTRAINT_NAME_TRUNCATED);
        assertThat(issue.ddlName()).isEqualTo("PK_" + "A".repeat(27));
        assertThat(issue.length()).isEqualTo(31);
        assertThat(issue.maxLength()).isEqualTo(30);
        assertThat(DbIdentifierRules.truncation(DbType.ORACLE, Kind.PRIMARY_KEY, "PK_" + "A".repeat(27))).isNull();
        assertThat(DbIdentifierRules.truncation(DbType.POSTGRESQL, Kind.PRIMARY_KEY, "PK_" + "A".repeat(28))).isNull();
    }
}
