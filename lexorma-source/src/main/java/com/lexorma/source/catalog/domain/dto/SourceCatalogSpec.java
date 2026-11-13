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

    /**
     * @param members   구성 요소 (순서대로). 컬럼 또는 표현식 하나씩
     * @param predicate 부분 인덱스 조건 (원본 표현). 없으면 null
     */
    public record Index(String name, String indexType, boolean unique, List<IndexMember> members, String predicate) {
        /** 컬럼만으로 된 오름차순 인덱스 */
        public Index(String name, String indexType, boolean unique, List<Name> columns) {
            this(name, indexType, unique, columns == null ? null : columns.stream().map(IndexMember::column).toList(), null);
        }
    }

    /**
     * 인덱스 구성 요소 : 컬럼(name) 또는 표현식(expression) 중 하나.
     *
     * @param sort 기본(오름차순, PostgreSQL 은 NULLS LAST)이면 null. 그 밖에는 원본 표기 (DESC, NULLS FIRST, DESC NULLS LAST) 또는 INCLUDE (키가 아닌 포함 컬럼)
     */
    public record IndexMember(Name name, String expression, String sort) {
        public static IndexMember column(Name name) {
            return new IndexMember(name, null, null);
        }
    }

    public record ColumnPair(Name column, Name referencedColumn) {
    }

    /**
     * @param referencedCatalog·referencedSchema 참조되는 테이블의 이름 공간. 둘 다 없으면 참조하는 테이블과 같은 스키마
     */
    public record Relation(String name, Name referencedCatalog, Name referencedSchema, Name referencedTable, String deleteRule, String updateRule, List<ColumnPair> columns) {
    }
}
