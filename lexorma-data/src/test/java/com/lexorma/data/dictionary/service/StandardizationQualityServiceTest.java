package com.lexorma.data.dictionary.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Attribute;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ColumnRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Issue;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** M-5 2차 : 표준화 품질 진단 판정 (표준 용어 미연결 / 컬럼명 불일치 / 도메인 정의 불일치) */
class StandardizationQualityServiceTest {

    @Test
    @DisplayName("표준 용어가 없는 컬럼은 미연결 1건만 나온다 (컬럼명·도메인은 비교할 대상이 없다)")
    void termNotLinked() {
        List<Issue> issues = diagnose(DbType.POSTGRESQL, new ColumnRow(1L, 1L, 1L, "DEFAULT", "TB_A", "가", "USER_NM", "사용자명", "VARCHAR", 20, null, null,
                1, true, null, null, null, null, null, null, null, null));

        assertThat(issues).extracting(Issue::type).containsExactly(IssueType.TERM_NOT_LINKED);
        assertThat(issues.get(0).id()).isEqualTo("TERM_NOT_LINKED:1");
    }

    @Test
    @DisplayName("컬럼명은 표준 용어 영문약어와 대소문자만 무시하고 비교한다. 약어가 없는 용어는 위반으로 보지 않는다")
    void columnName() {
        assertThat(diagnose(DbType.POSTGRESQL, named("USER_NM", "USER_NM"))).isEmpty();
        assertThat(diagnose(DbType.POSTGRESQL, named("user_nm", "USER_NM"))).as("대소문자").isEmpty();
        assertThat(diagnose(DbType.POSTGRESQL, named("USER_NM", " "))).as("기대 컬럼명을 알 수 없음").isEmpty();
        assertThat(diagnose(DbType.POSTGRESQL, named("USER_NM", null))).isEmpty();

        List<Issue> mismatch = diagnose(DbType.POSTGRESQL, named("USR_NM", "USER_NM"));
        assertThat(mismatch).extracting(Issue::type, Issue::actualValue, Issue::expectedValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(IssueType.COLUMN_NAME_MISMATCH, "USR_NM", "USER_NM"));
        assertThat(diagnose(DbType.POSTGRESQL, named("USERNM", "USER_NM"))).as("구분자").hasSize(1);
        assertThat(diagnose(DbType.POSTGRESQL, named("USER-NM", "USER_NM"))).hasSize(1);
    }

    @Test
    @DisplayName("도메인 비교는 DDL 타입 매핑을 거친다 : 표기만 다른 타입은 같고, DB 에 따라 같은 타입은 그 DB 에서만 같다")
    void domainType() {
        assertThat(attributes(DbType.POSTGRESQL, typed("VARCHAR2", 100, null, null, "VARCHAR", 100, null))).isEmpty();
        assertThat(attributes(DbType.ORACLE, typed("CHARACTER VARYING", 100, null, null, "VARCHAR2", 100, 0))).isEmpty();
        assertThat(attributes(DbType.POSTGRESQL, typed("VARCHAR(100)", null, null, null, "VARCHAR", 100, null))).as("타입에 적은 길이").isEmpty();
        // Oracle 은 INT 를 NUMBER(10) 으로 만든다
        assertThat(attributes(DbType.ORACLE, typed("INT", null, null, null, "NUMBER", 10, null))).isEmpty();
        assertThat(attributes(DbType.POSTGRESQL, typed("INT", null, null, null, "NUMBER", 10, null))).containsExactly(Attribute.DATA_TYPE);

        // 타입이 다르면 길이는 비교하지 않고, 값은 타입만 보여 준다
        List<Issue> issues = diagnose(DbType.POSTGRESQL, typed("VARCHAR", 8, null, null, "DATE", 10, null));
        assertThat(issues).extracting(Issue::type, Issue::actualValue, Issue::expectedValue, Issue::attributes)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(IssueType.DOMAIN_DEFINITION_MISMATCH, "VARCHAR", "DATE", List.of(Attribute.DATA_TYPE)));
    }

    @Test
    @DisplayName("길이·정밀도·소수 자릿수 : 도메인에 정의된 항목만 비교한다")
    void domainLength() {
        List<Issue> length = diagnose(DbType.POSTGRESQL, typed("VARCHAR", 20, null, null, "VARCHAR", 100, null));
        assertThat(length).extracting(Issue::actualValue, Issue::expectedValue, Issue::attributes)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("VARCHAR(20)", "VARCHAR(100)", List.of(Attribute.LENGTH)));
        assertThat(attributes(DbType.POSTGRESQL, typed("VARCHAR", null, null, null, "VARCHAR", 100, null))).as("컬럼에 길이 없음").containsExactly(Attribute.LENGTH);

        // 숫자 : 정밀도가 없으면 최대 길이를 정밀도로 쓴다 (DDL 과 같다)
        assertThat(attributes(DbType.POSTGRESQL, typed("NUMERIC", null, new BigDecimal(15), 2, "NUMBER", 15, 2))).isEmpty();
        assertThat(attributes(DbType.POSTGRESQL, typed("NUMERIC", 15, null, null, "NUMBER", 15, 0))).isEmpty();
        assertThat(attributes(DbType.POSTGRESQL, typed("NUMERIC", null, new BigDecimal(10), null, "NUMBER", 15, null))).containsExactly(Attribute.PRECISION);
        assertThat(attributes(DbType.POSTGRESQL, typed("NUMERIC", null, new BigDecimal(15), null, "NUMBER", 15, 2))).containsExactly(Attribute.SCALE);
        assertThat(attributes(DbType.POSTGRESQL, typed("NUMERIC", null, new BigDecimal(10), 4, "NUMBER", 15, 2))).containsExactly(Attribute.PRECISION, Attribute.SCALE);

        // 일부만 정의된 도메인 : 타입만 / 길이만 / 소수 자릿수 없음, 길이 0 은 미정의
        assertThat(attributes(DbType.POSTGRESQL, typed("VARCHAR", 20, null, null, "VARCHAR", null, null))).isEmpty();
        assertThat(attributes(DbType.POSTGRESQL, typed("VARCHAR", 20, null, null, "VARCHAR", 0, 0))).isEmpty();
        assertThat(attributes(DbType.ORACLE, typed("VARCHAR", 20, null, null, "VARCHAR", null, null))).as("Oracle 기본 길이로 비교하지 않는다").isEmpty();
        assertThat(attributes(DbType.POSTGRESQL, typed("VARCHAR", 20, null, null, null, 100, null))).containsExactly(Attribute.LENGTH);
        assertThat(attributes(DbType.POSTGRESQL, typed("VARCHAR", 100, null, null, " ", 100, null))).isEmpty();
        assertThat(attributes(DbType.POSTGRESQL, typed("NUMERIC", null, new BigDecimal(15), 2, "NUMBER", 15, null))).isEmpty();
        assertThat(attributes(DbType.POSTGRESQL, typed("NUMERIC", null, new BigDecimal(15), 2, null, null, null))).as("정의가 없는 도메인").isEmpty();
    }

    @Test
    @DisplayName("도메인이 없는 용어는 도메인 불일치가 아니고, 한 컬럼에 여러 문제가 함께 나올 수 있다")
    void combined() {
        assertThat(diagnose(DbType.POSTGRESQL, new ColumnRow(1L, 1L, 1L, "DEFAULT", "TB_A", "가", "USER_NM", null, "CLOB", null, null, null,
                1, true, 10L, "사용자_명", "USER_NM", null, null, null, null, null))).isEmpty();

        List<Issue> issues = diagnose(DbType.POSTGRESQL, new ColumnRow(7L, 1L, 1L, "DEFAULT", "TB_A", "가", "USR_NM", null, "VARCHAR", 20, null, null,
                1, false, 10L, "사용자_명", "USER_NM", 20L, "명V100", "VARCHAR", 100, null));
        assertThat(issues).extracting(Issue::type).containsExactly(IssueType.COLUMN_NAME_MISMATCH, IssueType.DOMAIN_DEFINITION_MISMATCH);
        assertThat(issues).extracting(Issue::columnId).containsOnly(7L);
    }

    // ------------------------------------------------------------------ 보조

    private static List<Issue> diagnose(DbType db, ColumnRow row) {
        return StandardizationQualityService.diagnose(db, List.of(row));
    }

    private static List<Attribute> attributes(DbType db, ColumnRow row) {
        return diagnose(db, row).stream().flatMap(issue -> issue.attributes().stream()).toList();
    }

    /** 용어가 연결되고 도메인은 없는 컬럼 */
    private static ColumnRow named(String columnName, String abbreviation) {
        return new ColumnRow(1L, 1L, 1L, "DEFAULT", "TB_A", "가", columnName, null, "VARCHAR", 20, null, null,
                1, true, 10L, "사용자_명", abbreviation, null, null, null, null, null);
    }

    /** 컬럼명은 맞고 도메인이 연결된 컬럼 */
    private static ColumnRow typed(String dataType, Integer maxLength, BigDecimal precision, Integer scale,
                                   String domainType, Integer domainLength, Integer domainDecimalLength) {
        return new ColumnRow(1L, 1L, 1L, "DEFAULT", "TB_A", "가", "USER_NM", null, dataType, maxLength, precision, scale,
                1, true, 10L, "사용자_명", "USER_NM", 20L, "도메인", domainType, domainLength, domainDecimalLength);
    }
}
