package com.lexorma.source.catalog.domain.dto;

import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 원본 카탈로그에 저장할 내용 (SourceCatalogWriter 의 입력). 수집 도구(DBMS 별)의 결과를 이 모양으로 바꿔 넘긴다 — DBMS 마다 다른 조회 결과와 저장을 떼어 놓는다.
 * 이름은 원본이 준 그대로 넘기고, quoted 는 따옴표 식별자인지 아는 경우에만 채운다 (모르면 null).
 */
public final class SourceCatalogSpec {

    private SourceCatalogSpec() {
    }

    public record Name(String value, Boolean quoted) {
        public static Name of(String value) {
            return new Name(value, null);
        }
    }

    public record Table(Name name, SourceTableType type, String comment) {
    }

    public record Column(Name name, Integer ordinal, String dataType, Integer maxLength, BigDecimal precision, Integer scale, Boolean nullable,
                         String defaultValue, String comment) {
    }

    /** @param columns 구성 컬럼 (순서대로). CHECK 는 없어도 된다 */
    public record Constraint(String name, SourceConstraintType type, String checkExpression, List<Name> columns) {
    }

    public record Index(String name, String indexType, boolean unique, List<Name> columns) {
    }

    public record ColumnPair(Name column, Name referencedColumn) {
    }

    /**
     * @param referencedCatalog·referencedSchema 참조되는 테이블의 이름 공간. 둘 다 없으면 참조하는 테이블과 같은 스키마
     */
    public record Relation(String name, Name referencedCatalog, Name referencedSchema, Name referencedTable, String deleteRule, String updateRule, List<ColumnPair> columns) {
    }
}
