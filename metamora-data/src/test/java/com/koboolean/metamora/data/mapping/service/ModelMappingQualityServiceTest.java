package com.koboolean.metamora.data.mapping.service;

import com.koboolean.metamora.core.project.domain.enums.DbType;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 정합성 판정 규칙 (DB 불필요). 조회·집계·권한은 ModelMappingQualityIntegrationTest */
class ModelMappingQualityServiceTest {

    @Test
    @DisplayName("데이터 타입 : DBMS 별 표기만 다른 타입은 일치, 프로젝트 DB 에서 다른 타입이 되면 불일치")
    void typeEquivalenceByDbms() {
        // 도메인 VARCHAR(100) ↔ 컬럼 VARCHAR2(100)
        Source varchar = mapped("VARCHAR", 100, null, "VARCHAR2", 100, null, null);
        assertThat(status(DbType.ORACLE, varchar, CheckType.DATA_TYPE)).isEqualTo(CheckStatus.MATCH);
        assertThat(status(DbType.ORACLE, varchar, CheckType.LENGTH)).isEqualTo(CheckStatus.MATCH);
        assertThat(status(DbType.POSTGRESQL, varchar, CheckType.DATA_TYPE)).isEqualTo(CheckStatus.MATCH);

        // 도메인 INT ↔ 컬럼 NUMBER(10) : Oracle 에서는 같은 타입, PostgreSQL 에서는 INTEGER ↔ NUMERIC
        Source number = mapped("INT", null, null, "NUMBER", null, BigDecimal.TEN, null);
        assertThat(status(DbType.ORACLE, number, CheckType.DATA_TYPE)).isEqualTo(CheckStatus.MATCH);
        Row mismatch = row(DbType.POSTGRESQL, number, CheckType.DATA_TYPE);
        assertThat(mismatch).extracting(Row::status, Row::logicalValue, Row::physicalValue).containsExactly(CheckStatus.MISMATCH, "INTEGER", "NUMERIC");
        // 타입이 다르면 길이는 불일치가 아니라 비교 불가다
        assertThat(status(DbType.POSTGRESQL, number, CheckType.LENGTH)).isEqualTo(CheckStatus.NOT_COMPARABLE);
    }

    @Test
    @DisplayName("길이·정밀도 : 도메인에 정의된 항목만 비교하고, 길이 0 은 미정의다")
    void lengthOnlyWhenDefined() {
        assertThat(row(DbType.POSTGRESQL, mapped("VARCHAR", 100, null, "VARCHAR", 50, null, null), CheckType.LENGTH))
                .extracting(Row::status, Row::logicalValue, Row::physicalValue).containsExactly(CheckStatus.MISMATCH, "VARCHAR(100)", "VARCHAR(50)");
        assertThat(status(DbType.POSTGRESQL, mapped("VARCHAR", 0, null, "VARCHAR", 50, null, null), CheckType.LENGTH)).isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(status(DbType.POSTGRESQL, mapped("VARCHAR", null, null, "VARCHAR", 50, null, null), CheckType.DATA_TYPE)).isEqualTo(CheckStatus.MATCH);
        // 소수 자릿수
        assertThat(status(DbType.POSTGRESQL, mapped("NUMERIC", 15, 2, "NUMERIC", null, BigDecimal.valueOf(15), 2), CheckType.LENGTH)).isEqualTo(CheckStatus.MATCH);
        assertThat(status(DbType.POSTGRESQL, mapped("NUMERIC", 15, 2, "NUMERIC", null, BigDecimal.valueOf(15), 0), CheckType.LENGTH)).isEqualTo(CheckStatus.MISMATCH);
        // 타입에 길이를 함께 적은 컬럼 (VARCHAR(100))
        assertThat(status(DbType.POSTGRESQL, mapped("VARCHAR", 100, null, "varchar(100)", null, null, null), CheckType.LENGTH)).isEqualTo(CheckStatus.MATCH);
    }

    @Test
    @DisplayName("매핑하지 않은 엔터티·속성은 UNMAPPED 만 나오고 다른 진단을 하지 않는다. 엔터티 행은 엔터티마다 한 번")
    void unmapped() {
        Source first = source(1L, null, 10L, null, null);
        Source second = source(1L, null, 11L, null, null);
        Source empty = source(2L, null, null, null, null);
        Source attributeOnly = source(3L, 30L, 12L, null, null);

        List<Row> rows = ModelMappingQualityService.diagnose(DbType.POSTGRESQL, List.of(first, second, empty, attributeOnly), Map.of(), true);
        assertThat(rows).allMatch(row -> row.type() == CheckType.MAPPING && row.status() == CheckStatus.UNMAPPED);
        assertThat(rows).extracting(Row::entityId, Row::attributeId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, null), org.assertj.core.groups.Tuple.tuple(1L, 10L), org.assertj.core.groups.Tuple.tuple(1L, 11L),
                        org.assertj.core.groups.Tuple.tuple(2L, null), org.assertj.core.groups.Tuple.tuple(3L, 12L));
        assertThat(rows).extracting(Row::id).doesNotHaveDuplicates();
        // 속성명·컬럼명으로 찾을 때는 엔터티 단위 행을 내지 않는다
        assertThat(ModelMappingQualityService.diagnose(DbType.POSTGRESQL, List.of(first, second, empty, attributeOnly), Map.of(), false))
                .extracting(Row::attributeId).containsExactly(10L, 11L, 12L);
    }

    @Test
    @DisplayName("필수·식별자 : 주 식별자 속성은 필수로 보고, PK·필수 입력·NULL 비허용 컬럼은 NOT NULL 로 본다")
    void requiredAndIdentifier() {
        Source source = new Source(1L, "모델", 1L, "엔터티", 100L, 1L, "DEFAULT", 1L, "TB", 10L, "속성", false,
                null, null, null, null, null, null, null, null, 1000L, 20L, "COL", "VARCHAR", 10, null, null, null, null, true, null, null);
        // 주 식별자 속성 ↔ PK 컬럼
        List<Row> rows = ModelMappingQualityService.diagnose(DbType.POSTGRESQL, List.of(source), Map.of(1L, Set.of(10L)), true);
        assertThat(find(rows, CheckType.REQUIRED)).extracting(Row::status, Row::physicalValue).containsExactly(CheckStatus.MATCH, "NOT NULL");
        assertThat(find(rows, CheckType.IDENTIFIER).status()).isEqualTo(CheckStatus.MATCH);
        // 주 식별자가 다른 속성이면 PK 와 구성이 다르다
        rows = ModelMappingQualityService.diagnose(DbType.POSTGRESQL, List.of(source), Map.of(1L, Set.of(99L)), true);
        assertThat(find(rows, CheckType.REQUIRED).status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(find(rows, CheckType.IDENTIFIER).status()).isEqualTo(CheckStatus.MISMATCH);
        // 주 식별자가 없는 엔터티는 식별자를 비교하지 않는다
        rows = ModelMappingQualityService.diagnose(DbType.POSTGRESQL, List.of(source), Map.of(), true);
        assertThat(find(rows, CheckType.IDENTIFIER).status()).isEqualTo(CheckStatus.NOT_COMPARABLE);
    }

    private static CheckStatus status(DbType db, Source source, CheckType type) {
        return row(db, source, type).status();
    }

    private static Row row(DbType db, Source source, CheckType type) {
        return find(ModelMappingQualityService.diagnose(db, List.of(source), Map.of(), true), type);
    }

    private static Row find(List<Row> rows, CheckType type) {
        return rows.stream().filter(row -> row.type() == type).findFirst().orElseThrow();
    }

    /** 매핑된 속성 1개 : 논리 속성의 도메인(타입·길이·소수 자릿수) ↔ 컬럼(타입·길이·정밀도·소수 자릿수) */
    private static Source mapped(String domainType, Integer domainLength, Integer domainDecimal, String columnType, Integer maxLength, BigDecimal precision, Integer scale) {
        return new Source(1L, "모델", 1L, "엔터티", 100L, 1L, "DEFAULT", 1L, "TB", 10L, "속성", false,
                5L, "용어", "COL", 7L, "도메인", domainType, domainLength, domainDecimal,
                1000L, 20L, "COL", columnType, maxLength, precision, scale, true, false, false, 5L, "용어");
    }

    private static Source source(Long entityId, Long mappingId, Long attributeId, Long attributeMappingId, Long columnId) {
        return new Source(1L, "모델", entityId, "엔터티" + entityId, mappingId, null, null, null, null, attributeId, attributeId == null ? null : "속성" + attributeId, false,
                null, null, null, null, null, null, null, null, attributeMappingId, columnId, null, null, null, null, null, null, null, null, null, null);
    }
}
