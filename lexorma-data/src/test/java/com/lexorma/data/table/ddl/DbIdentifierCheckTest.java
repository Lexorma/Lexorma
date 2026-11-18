package com.lexorma.data.table.ddl;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.table.ddl.DbIdentifierCheck.Column;
import com.lexorma.data.table.ddl.DbIdentifierCheck.Finding;
import com.lexorma.data.table.ddl.DbIdentifierCheck.Model;
import com.lexorma.data.table.ddl.DbIdentifierCheck.Schema;
import com.lexorma.data.table.ddl.DbIdentifierCheck.StoredName;
import com.lexorma.data.table.ddl.DbIdentifierCheck.Table;
import com.lexorma.data.table.ddl.DbIdentifierRules.Code;
import com.lexorma.data.table.ddl.DbIdentifierRules.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 모델 단위 식별자 판정 (M-7 보강 1차) : 자동 제약조건명 잘림, 최종 이름 충돌과 DBMS별 이름 공간, 대상 */
class DbIdentifierCheckTest {

    private static final Schema DEFAULT = new Schema(1L, "DEFAULT", true);
    private static final Schema SALES = new Schema(2L, "SALES", false);

    /** 28자이고 앞 27자가 같은 두 테이블 (이름 자체는 Oracle 30자 안) : PK_ 를 붙이면 31자라 Oracle 30자에서 같은 이름으로 잘린다 */
    private static final String LONG_A = "TB_" + "X".repeat(24) + "A";
    private static final String LONG_B = "TB_" + "X".repeat(24) + "B";

    private static Column pk(long id, long table) {
        return new Column(id, table, "ID", true, false, false);
    }

    private static List<Code> codes(List<Finding> findings) {
        return findings.stream().map(f -> f.issue().code()).toList();
    }

    @Test
    @DisplayName("Oracle : 자동 PK 이름이 30자에서 잘리면 TRUNCATED, 잘린 이름이 같은 스키마에서 같으면 두 PK 모두 COLLISION (서로를 related 로)")
    void oracleTruncatedPkCollision() {
        Model model = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, LONG_A), new Table(11L, 1L, LONG_B)), List.of(pk(100L, 10L), pk(101L, 11L)), List.of());
        List<Finding> findings = DbIdentifierCheck.check(DbType.ORACLE, model);
        assertThat(codes(findings)).containsExactly(Code.CONSTRAINT_NAME_TRUNCATED, Code.CONSTRAINT_NAME_COLLISION, Code.CONSTRAINT_NAME_TRUNCATED, Code.CONSTRAINT_NAME_COLLISION);
        Finding collision = findings.get(1);
        assertThat(collision.tableId()).isEqualTo(10L);
        assertThat(collision.issue().kind()).isEqualTo(Kind.PRIMARY_KEY);
        assertThat(collision.issue().ddlName()).hasSize(30);
        assertThat(collision.others()).singleElement().satisfies(o -> assertThat(o.tableId()).isEqualTo(11L));

        // 다른 스키마면 이름 공간이 다르다 (잘림만 남는다)
        Model split = new Model(List.of(DEFAULT, SALES), List.of(new Table(10L, 1L, LONG_A), new Table(11L, 2L, LONG_B)), List.of(pk(100L, 10L), pk(101L, 11L)), List.of());
        assertThat(codes(DbIdentifierCheck.check(DbType.ORACLE, split))).containsExactly(Code.CONSTRAINT_NAME_TRUNCATED, Code.CONSTRAINT_NAME_TRUNCATED);
    }

    @Test
    @DisplayName("같은 모델이 PostgreSQL(63)은 문제없고, MySQL·MariaDB 는 PK 이름을 쓰지 않아 잘림·충돌이 없다")
    void sameModelOtherDbms() {
        Model model = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, LONG_A), new Table(11L, 1L, LONG_B)), List.of(pk(100L, 10L), pk(101L, 11L)), List.of());
        assertThat(DbIdentifierCheck.check(DbType.POSTGRESQL, model)).isEmpty();
        String longer = "TB_" + "Y".repeat(70);
        Model mysql = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, longer.substring(0, 64))), List.of(pk(100L, 10L)), List.of());
        assertThat(DbIdentifierCheck.check(DbType.MYSQL, mysql)).isEmpty();
        assertThat(DbIdentifierCheck.check(DbType.MARIADB, mysql)).isEmpty();
    }

    @Test
    @DisplayName("FK 자동 이름 : PostgreSQL 은 테이블마다(다른 테이블 FK 와 같아도 됨), MySQL 은 스키마 전체에서 하나")
    void foreignKeyNamespace() {
        // FK_<테이블>_<컬럼> 이 잘려 같아지는 두 테이블의 FK (63·64자 경계를 넘도록 긴 컬럼명)
        String column = "C".repeat(60);
        Model model = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, "TA"), new Table(11L, 1L, "TB")),
                List.of(new Column(100L, 10L, column + "1", false, false, true), new Column(101L, 11L, column + "2", false, false, true)), List.of());
        // 잘린 이름 : FK_TA_CCC… / FK_TB_CCC… — 테이블명이 달라 겹치지 않는다. 같은 테이블의 두 FK 를 본다
        Model sameTable = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, "TA")),
                List.of(new Column(100L, 10L, column + "1", false, false, true), new Column(101L, 10L, column + "2", false, false, true)), List.of());
        for (DbType db : DbType.values()) {
            assertThat(codes(DbIdentifierCheck.check(db, model))).as(db.name()).doesNotContain(Code.CONSTRAINT_NAME_COLLISION);
            assertThat(codes(DbIdentifierCheck.check(db, sameTable))).as(db.name()).contains(Code.CONSTRAINT_NAME_COLLISION);
        }

        // 다른 테이블에 같은 이름의 사용자 FK 관계가 있는 경우 : PostgreSQL 은 충돌 아님, MySQL·MariaDB·Oracle 은 스키마 단위라 충돌
        Model crossTable = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, "TA"), new Table(11L, 1L, "TB")),
                List.of(new Column(100L, 10L, "REF_ID", false, false, true)), List.of(new StoredName(Kind.FOREIGN_KEY, 900L, 11L, "FK_TA_REF_ID")));
        assertThat(codes(DbIdentifierCheck.check(DbType.POSTGRESQL, crossTable))).isEmpty();
        assertThat(codes(DbIdentifierCheck.check(DbType.MYSQL, crossTable))).containsExactly(Code.CONSTRAINT_NAME_COLLISION);
        assertThat(codes(DbIdentifierCheck.check(DbType.MARIADB, crossTable))).containsExactly(Code.CONSTRAINT_NAME_COLLISION);
        assertThat(codes(DbIdentifierCheck.check(DbType.ORACLE, crossTable))).containsExactly(Code.CONSTRAINT_NAME_COLLISION);
    }

    @Test
    @DisplayName("PostgreSQL : 자동 PK 이름과 같은 스키마의 인덱스 이름이 같으면 충돌 (인덱스 이름 공간), MySQL 은 PK 제외라 아님")
    void primaryKeyVersusIndex() {
        Model model = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, "CUST"), new Table(11L, 1L, "ORD")), List.of(pk(100L, 10L)),
                List.of(new StoredName(Kind.INDEX, 700L, 11L, "PK_CUST")));
        List<Finding> findings = DbIdentifierCheck.check(DbType.POSTGRESQL, model);
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.issue().code()).isEqualTo(Code.CONSTRAINT_NAME_COLLISION);
            assertThat(f.others()).singleElement().satisfies(o -> {
                assertThat(o.kind()).isEqualTo(Kind.INDEX);
                assertThat(o.generated()).isFalse();
            });
        });
        assertThat(DbIdentifierCheck.check(DbType.MYSQL, model)).isEmpty();
    }

    @Test
    @DisplayName("저장된 이름끼리의 그대로 중복은 알리지 않는다 (각 저장 화면이 다룬다). 저장된 이름이 잘려서 겹치면 알린다")
    void storedNames() {
        Model same = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, "TA"), new Table(11L, 1L, "TB")), List.of(),
                List.of(new StoredName(Kind.INDEX, 1L, 10L, "IX_SAME"), new StoredName(Kind.INDEX, 2L, 11L, "IX_SAME")));
        assertThat(DbIdentifierCheck.check(DbType.POSTGRESQL, same)).isEmpty();

        String prefix = "IX_" + "Z".repeat(40);
        Model truncated = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, "TA")), List.of(),
                List.of(new StoredName(Kind.INDEX, 1L, 10L, prefix + "_ONE"), new StoredName(Kind.INDEX, 2L, 10L, prefix + "_TWO")));
        assertThat(codes(DbIdentifierCheck.check(DbType.ORACLE, truncated))).containsExactly(Code.CONSTRAINT_NAME_COLLISION, Code.CONSTRAINT_NAME_COLLISION);
        assertThat(DbIdentifierCheck.check(DbType.POSTGRESQL, truncated)).isEmpty();
    }

    @Test
    @DisplayName("스키마·테이블·컬럼 이름 판정과 대상 (기본 스키마는 DDL 에 이름이 나가지 않아 보지 않는다)")
    void names() {
        Model model = new Model(List.of(new Schema(1L, "기본", true), new Schema(2L, "영업", false)), List.of(new Table(10L, 2L, "고객")),
                List.of(new Column(100L, 10L, "1ST", false, false, false)), List.of());
        List<Finding> findings = DbIdentifierCheck.check(DbType.POSTGRESQL, model);
        assertThat(findings).extracting(f -> f.issue().kind(), f -> f.issue().code(), Finding::schemaId, Finding::tableId, Finding::columnId).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Kind.SCHEMA, Code.IDENTIFIER_NOT_RECOMMENDED, 2L, null, null),
                org.assertj.core.groups.Tuple.tuple(Kind.TABLE, Code.IDENTIFIER_NOT_RECOMMENDED, 2L, 10L, null),
                org.assertj.core.groups.Tuple.tuple(Kind.COLUMN, Code.IDENTIFIER_INVALID_CHARACTER, 2L, 10L, 100L));
    }

    @Test
    @DisplayName("UNIQUE 자동 이름 : PK 가 아닌 고유값 컬럼만 (PK 컬럼의 고유값 표시는 DDL 이 따로 만들지 않는다)")
    void uniqueNames() {
        Model model = new Model(List.of(DEFAULT), List.of(new Table(10L, 1L, "T")),
                List.of(new Column(100L, 10L, "ID", true, true, false), new Column(101L, 10L, "EMAIL", false, true, false)), List.of());
        assertThat(DbIdentifierCheck.ddlNames(DbType.POSTGRESQL, model)).extracting(DbIdentifierCheck.DdlName::original).containsExactly("PK_T", "UK_T_EMAIL");
    }
}
