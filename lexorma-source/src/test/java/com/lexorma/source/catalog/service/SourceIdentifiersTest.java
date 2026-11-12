package com.lexorma.source.catalog.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;
import com.lexorma.source.catalog.service.SourceIdentifiers.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.lexorma.source.connection.domain.enums.IdentifierComparison.CASE_INSENSITIVE;
import static com.lexorma.source.connection.domain.enums.IdentifierComparison.CASE_SENSITIVE;
import static com.lexorma.source.connection.domain.enums.IdentifierComparison.DBMS_DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;

/** 원본 식별자의 비교용 이름 (U-24) */
class SourceIdentifiersTest {

    private static String name(DbType dbType, IdentifierComparison comparison, Kind kind, String name, Boolean quoted) {
        return SourceIdentifiers.compareName(dbType, comparison, kind, name, quoted);
    }

    @Test
    @DisplayName("PostgreSQL : 따옴표 없는 식별자는 소문자로 접고, 따옴표 식별자와 따옴표 여부를 모르는 이름은 그대로 둔다")
    void postgresql() {
        for (Kind kind : Kind.values()) {
            assertThat(name(DbType.POSTGRESQL, DBMS_DEFAULT, kind, "USERS", false)).isEqualTo("users");
            assertThat(name(DbType.POSTGRESQL, DBMS_DEFAULT, kind, "Users", false)).isEqualTo("users");
            assertThat(name(DbType.POSTGRESQL, DBMS_DEFAULT, kind, "Users", true)).isEqualTo("Users");
            // 따옴표 식별자 "users" 는 따옴표 없는 users 와 같은 객체다
            assertThat(name(DbType.POSTGRESQL, DBMS_DEFAULT, kind, "users", true)).isEqualTo(name(DbType.POSTGRESQL, DBMS_DEFAULT, kind, "USERS", false));
            // 카탈로그가 돌려준 이름(따옴표 여부 모름)은 이미 접힌 뒤의 이름이다 : 모양만 보고 접지 않는다
            assertThat(name(DbType.POSTGRESQL, DBMS_DEFAULT, kind, "Users", null)).isEqualTo("Users");
            assertThat(name(DbType.POSTGRESQL, DBMS_DEFAULT, kind, "users", null)).isEqualTo("users");
        }
    }

    @Test
    @DisplayName("Oracle : 따옴표 없는 식별자는 대문자로 접고, 따옴표 식별자는 그대로 둔다")
    void oracle() {
        assertThat(name(DbType.ORACLE, DBMS_DEFAULT, Kind.TABLE, "emp", false)).isEqualTo("EMP");
        assertThat(name(DbType.ORACLE, DBMS_DEFAULT, Kind.TABLE, "EMP", null)).isEqualTo("EMP");
        assertThat(name(DbType.ORACLE, DBMS_DEFAULT, Kind.TABLE, "emp", true)).isEqualTo("emp");
        assertThat(name(DbType.ORACLE, DBMS_DEFAULT, Kind.COLUMN, "Emp_No", false)).isEqualTo("EMP_NO");
    }

    @Test
    @DisplayName("MySQL·MariaDB : 컬럼명은 항상 대소문자 무시, 테이블·데이터베이스명은 연결의 규칙을 따르고 모르면 합치지 않는다")
    void mysql() {
        for (DbType dbType : new DbType[]{DbType.MYSQL, DbType.MARIADB}) {
            assertThat(name(dbType, DBMS_DEFAULT, Kind.COLUMN, "OrderId", null)).isEqualTo("orderid");
            assertThat(name(dbType, CASE_SENSITIVE, Kind.COLUMN, "OrderId", true)).isEqualTo("orderid");
            // 서버 설정을 모른다 : Orders 와 orders 를 임의로 합치지 않는다
            assertThat(name(dbType, DBMS_DEFAULT, Kind.TABLE, "Orders", null)).isEqualTo("Orders");
            assertThat(name(dbType, DBMS_DEFAULT, Kind.TABLE, "Orders", false)).isEqualTo("Orders");
            assertThat(name(dbType, CASE_SENSITIVE, Kind.NAMESPACE, "Shop", null)).isEqualTo("Shop");
            assertThat(name(dbType, CASE_INSENSITIVE, Kind.TABLE, "Orders", null)).isEqualTo("orders");
            assertThat(name(dbType, CASE_INSENSITIVE, Kind.NAMESPACE, "Shop", null)).isEqualTo("shop");
        }
    }

    @Test
    @DisplayName("이름 공간 경로 : 카탈로그·스키마 중 한쪽이 없어도 값이 되고, 서로 다른 조합이 같은 값이 되지 않는다")
    void pathKey() {
        assertThat(SourceIdentifiers.pathKey("app", "public")).isEqualTo("3:app/6:public");
        assertThat(SourceIdentifiers.pathKey(null, "HR")).isEqualTo("0:/2:HR");
        assertThat(SourceIdentifiers.pathKey("shop", null)).isEqualTo("4:shop/0:");
        // 구분 문자가 이름에 들어 있어도 섞이지 않는다
        assertThat(SourceIdentifiers.pathKey("a/1:b", "c")).isNotEqualTo(SourceIdentifiers.pathKey("a", "1:b/c"));
        assertThat(SourceIdentifiers.pathKey("app", null)).isNotEqualTo(SourceIdentifiers.pathKey(null, "app"));
        assertThat(SourceIdentifiers.catalogOf("3:app/6:public")).isEqualTo("app");
        assertThat(SourceIdentifiers.catalogOf("0:/2:HR")).isNull();
        assertThat(SourceIdentifiers.catalogOf(SourceIdentifiers.pathKey("a/1:b", "c"))).isEqualTo("a/1:b");
    }

    @Test
    @DisplayName("비교 규칙을 고를 수 있는 것은 MySQL·MariaDB 뿐이다")
    void supports() {
        assertThat(SourceIdentifiers.supports(DbType.POSTGRESQL, DBMS_DEFAULT)).isTrue();
        assertThat(SourceIdentifiers.supports(DbType.POSTGRESQL, CASE_INSENSITIVE)).isFalse();
        assertThat(SourceIdentifiers.supports(DbType.ORACLE, CASE_SENSITIVE)).isFalse();
        assertThat(SourceIdentifiers.supports(DbType.MYSQL, CASE_INSENSITIVE)).isTrue();
        assertThat(SourceIdentifiers.supports(DbType.MARIADB, CASE_SENSITIVE)).isTrue();
    }
}
