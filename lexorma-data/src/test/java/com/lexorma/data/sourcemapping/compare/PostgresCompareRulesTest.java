package com.lexorma.data.sourcemapping.compare;

import com.lexorma.data.sourcemapping.compare.PostgresCompareRules.AutoIncrement;
import com.lexorma.data.sourcemapping.compare.PostgresCompareRules.PgType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresCompareRulesTest {

    @Test
    @DisplayName("타입 : 별칭(format_type ↔ 모델 DDL 표기)은 같게, 의미가 다른 타입은 다르게, 모르는 타입은 known = false")
    void types() {
        assertThat(PostgresCompareRules.type("character varying(100)")).isEqualTo(PostgresCompareRules.type("VARCHAR(100)"));
        assertThat(PostgresCompareRules.type("character varying")).isEqualTo(PostgresCompareRules.type("VARCHAR"));
        assertThat(PostgresCompareRules.type("character(1)")).isEqualTo(PostgresCompareRules.type("CHAR"));
        assertThat(PostgresCompareRules.type("numeric(10,0)")).isEqualTo(PostgresCompareRules.type("NUMERIC(10)"));
        assertThat(PostgresCompareRules.type("numeric(12,2)").display()).isEqualTo("NUMERIC(12,2)");
        assertThat(PostgresCompareRules.type("integer").base()).isEqualTo(PostgresCompareRules.type("INT4").base());
        assertThat(PostgresCompareRules.type("double precision").base()).isEqualTo("DOUBLE PRECISION");
        assertThat(PostgresCompareRules.type("real").base()).isNotEqualTo(PostgresCompareRules.type("DOUBLE PRECISION").base());
        assertThat(PostgresCompareRules.type("timestamp without time zone").base()).isEqualTo("TIMESTAMP");
        assertThat(PostgresCompareRules.type("timestamp with time zone").base()).isEqualTo("TIMESTAMPTZ");
        PgType precise = PostgresCompareRules.type("timestamp(3) with time zone");
        assertThat(precise.base()).isEqualTo("TIMESTAMPTZ");
        assertThat(precise.precision()).isEqualTo(3);
        // 정밀도를 생략한 시간 타입은 최대(6)와 같다
        assertThat(PostgresCompareRules.type("timestamp(6) without time zone")).isEqualTo(PostgresCompareRules.type("TIMESTAMP"));
        assertThat(PostgresCompareRules.type("timestamp(3) without time zone")).isNotEqualTo(PostgresCompareRules.type("TIMESTAMP"));
        assertThat(PostgresCompareRules.type("integer[]").arrayDims()).isEqualTo(1);
        assertThat(PostgresCompareRules.type("integer[]").baseDisplay()).isNotEqualTo(PostgresCompareRules.type("integer").baseDisplay());
        // 모델 DDL 의 SQL 표준 표기 (BIGINT ARRAY) 와 원본의 bigint[] 는 같은 타입
        assertThat(PostgresCompareRules.type("BIGINT ARRAY").baseDisplay()).isEqualTo(PostgresCompareRules.type("bigint[]").baseDisplay());
        assertThat(PostgresCompareRules.type("BIGINT ARRAY").known()).isTrue();
        assertThat(PostgresCompareRules.type("varchar(20) array").baseDisplay()).isEqualTo(PostgresCompareRules.type("character varying(20)[]").baseDisplay());
        assertThat(PostgresCompareRules.type("BIGINT ARRAY").baseDisplay()).isNotEqualTo(PostgresCompareRules.type("bigint").baseDisplay());
        assertThat(PostgresCompareRules.type("array")).isNotNull();
        assertThat(PostgresCompareRules.type("BIGSERIAL").serial()).isTrue();
        assertThat(PostgresCompareRules.type("BIGSERIAL").base()).isEqualTo("BIGINT");
        assertThat(PostgresCompareRules.type("public.mood").known()).isFalse();
        assertThat(PostgresCompareRules.type("DATETIME").known()).isFalse();
        assertThat(PostgresCompareRules.type(" ")).isNull();
    }

    @Test
    @DisplayName("식 : 리터럴 형 변환·숫자 괄호·바깥 괄호·대소문자·공백만 정규화하고, 함수·연산 모양이 다른 식은 같게 만들지 않는다")
    void expressions() {
        assertThat(PostgresCompareRules.expression("'NEW'::character varying")).isEqualTo("'NEW'");
        assertThat(PostgresCompareRules.expression("'-1'::integer")).isEqualTo("-1");
        assertThat(PostgresCompareRules.expression("(amount >= (0)::numeric)")).isEqualTo(PostgresCompareRules.expression("AMOUNT >= 0"));
        assertThat(PostgresCompareRules.expression("nextval('orders_id_seq'::regclass)")).isEqualTo("nextval('orders_id_seq')");
        assertThat(PostgresCompareRules.expression("CURRENT_TIMESTAMP")).isEqualTo("current_timestamp");
        assertThat(PostgresCompareRules.expression("'2020-01-01'::date")).isEqualTo("'2020-01-01'");
        // 따옴표 안은 그대로 (대소문자 유지)
        assertThat(PostgresCompareRules.expression("'Abc'")).isEqualTo("'Abc'");
        // 의미가 같을 수 있어도 모양이 다르면 같게 보지 않는다 (부르는 쪽이 UNKNOWN)
        assertThat(PostgresCompareRules.expression("now()")).isNotEqualTo(PostgresCompareRules.expression("CURRENT_TIMESTAMP"));
        assertThat(PostgresCompareRules.expression("(status)::text = ANY (ARRAY['A'::character varying])"))
                .isNotEqualTo(PostgresCompareRules.expression("STATUS IN ('A')"));
        // (a) + (b) 의 괄호는 식 전체를 감싼 것이 아니다
        assertThat(PostgresCompareRules.expression("(a) + (b)")).isEqualTo("(a)+(b)");
        assertThat(PostgresCompareRules.simple(PostgresCompareRules.expression("'x'::text"))).isTrue();
        assertThat(PostgresCompareRules.simple(PostgresCompareRules.expression("lower(name)"))).isFalse();
        assertThat(PostgresCompareRules.expression("  ")).isNull();
    }

    @Test
    @DisplayName("자동 증가 : IDENTITY 절, 시퀀스 기본값, 생성 컬럼")
    void autoIncrement() {
        assertThat(PostgresCompareRules.identity("GENERATED ALWAYS AS IDENTITY")).isEqualTo(AutoIncrement.IDENTITY_ALWAYS);
        assertThat(PostgresCompareRules.identity("generated by default as identity")).isEqualTo(AutoIncrement.IDENTITY_BY_DEFAULT);
        assertThat(PostgresCompareRules.identity("0")).isNull();
        assertThat(PostgresCompareRules.sequenceDefault("nextval('x_seq'::regclass)")).isTrue();
        assertThat(PostgresCompareRules.generated("GENERATED ALWAYS AS ((a * 2)) STORED")).isTrue();
        assertThat(PostgresCompareRules.generated("GENERATED ALWAYS AS IDENTITY")).isFalse();
    }
}
