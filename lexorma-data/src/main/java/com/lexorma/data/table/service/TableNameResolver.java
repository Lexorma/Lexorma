package com.lexorma.data.table.service;

import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.domain.entity.TableInfo;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 이름으로 적은 테이블(컬럼의 참조 테이블명, 컬럼 엑셀의 테이블명)을 실제 테이블로 정한다. 테이블명은 스키마 안에서만 하나라서 (UX_TBL_01)
 * 이름만으로는 여러 테이블일 수 있다. 모든 이름 기반 조회가 같은 규칙을 쓴다.
 * <ol>
 *   <li>{@code 스키마명.테이블명} : 그 스키마의 테이블 (스키마명은 대소문자 무시)</li>
 *   <li>{@code 테이블명} : 기준 스키마(참조하는 테이블의 스키마)에 있으면 그것</li>
 *   <li>없으면 프로젝트에서 그 이름이 하나뿐일 때 그것 (예전처럼 다른 스키마의 테이블을 이름만으로 참조한 것과 호환)</li>
 *   <li>여러 스키마에 있으면 모호하다 : 이미 확정된 관계의 대상(hint)이 후보 안에 있으면 그것을 유지하고, 아니면 정하지 않는다 (스키마명을 적게 한다)</li>
 * </ol>
 * 확정된 관계를 이름으로 다시 추론해 다른 테이블로 바꾸지 않기 위해 hint 를 쓴다.
 */
public final class TableNameResolver {

    private TableNameResolver() {
    }

    /** @param schemaName {@code 스키마명.} 으로 적었을 때만 (대문자) @param tableName 대문자 */
    public record Name(String schemaName, String tableName) {
        public static Name parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String value = raw.trim().toUpperCase(Locale.ROOT);
            int dot = value.indexOf('.');
            if (dot <= 0 || dot == value.length() - 1) return new Name(null, value);
            return new Name(value.substring(0, dot).trim(), value.substring(dot + 1).trim());
        }
    }

    public enum Status { FOUND, NOT_FOUND, AMBIGUOUS }

    public record Result(Status status, TableInfo table, List<TableInfo> candidates) {
        public static final Result NONE = new Result(Status.NOT_FOUND, null, List.of());
    }

    /**
     * @param raw        적은 이름 (테이블명 또는 스키마명.테이블명)
     * @param baseSchema 기준 스키마 (없으면 null)
     * @param tables     같은 프로젝트에서 그 테이블명을 쓰는 테이블 (다른 이름이 섞여 있어도 된다)
     * @param hintTableId 이미 확정된 대상 (없으면 null)
     */
    public static Result resolve(String raw, SchemaInfo baseSchema, Collection<TableInfo> tables, Long hintTableId) {
        Pick<TableInfo> pick = pick(raw, baseSchema != null, baseSchema == null ? null : baseSchema.getId(), tables, TableNameResolver::view, hintTableId);
        return pick.status() == Status.NOT_FOUND && pick.candidates().isEmpty() ? Result.NONE : new Result(pick.status(), pick.table(), pick.candidates());
    }

    /** 이름 판단에 쓰는 테이블 값 : 엔티티든 조회 행이든 같은 규칙을 쓰려고 나눴다 (물리 모델 구조 진단의 일괄 조회, M-DIAG-PERF). hasSchema = 스키마가 있는지 */
    public record View(Long id, boolean hasSchema, Long schemaId, String schemaName, String tableName) {
    }

    public record Pick<T>(Status status, T table, List<T> candidates) {
    }

    private static View view(TableInfo t) {
        SchemaInfo schema = t.getSchema();
        return new View(t.getId(), schema != null, schema == null ? null : schema.getId(), schema == null ? null : schema.getSchemaName(), t.getTableName());
    }

    /**
     * {@link #resolve} 와 같은 규칙을 임의의 테이블 값에 적용한다.
     * @param hasBaseSchema 기준 스키마가 있는지 (있으면 baseSchemaId 와 같은 스키마의 테이블을 먼저 본다)
     */
    public static <T> Pick<T> pick(String raw, boolean hasBaseSchema, Long baseSchemaId, Collection<T> tables, java.util.function.Function<T, View> view, Long hintTableId) {
        Name name = Name.parse(raw);
        if (name == null) return new Pick<>(Status.NOT_FOUND, null, List.of());
        List<T> named = tables.stream().filter(t -> name.tableName().equalsIgnoreCase(view.apply(t).tableName())).toList();
        if (name.schemaName() != null) {
            return one(named.stream().filter(t -> view.apply(t).hasSchema() && name.schemaName().equalsIgnoreCase(view.apply(t).schemaName())).toList(), view, hintTableId);
        }
        if (hasBaseSchema) {
            List<T> same = named.stream().filter(t -> view.apply(t).hasSchema() && Objects.equals(view.apply(t).schemaId(), baseSchemaId)).toList();
            if (!same.isEmpty()) return one(same, view, hintTableId);
        }
        return one(named, view, hintTableId);
    }

    private static <T> Pick<T> one(List<T> candidates, java.util.function.Function<T, View> view, Long hintTableId) {
        if (candidates.isEmpty()) return new Pick<>(Status.NOT_FOUND, null, List.of());
        if (candidates.size() == 1) return new Pick<>(Status.FOUND, candidates.get(0), candidates);
        T hinted = hintTableId == null ? null : candidates.stream().filter(t -> hintTableId.equals(view.apply(t).id())).findFirst().orElse(null);
        return hinted != null ? new Pick<>(Status.FOUND, hinted, candidates) : new Pick<>(Status.AMBIGUOUS, null, candidates);
    }

    /** 이 테이블을 가리킬 수 있는 이름 : 테이블명, 스키마명.테이블명 (대문자). 관계 동기화의 후보 컬럼 조회에 쓴다 */
    public static List<String> namesOf(TableInfo table) {
        String name = table.getTableName().toUpperCase(Locale.ROOT);
        return table.getSchema() == null ? List.of(name) : List.of(name, table.getSchema().getSchemaName().toUpperCase(Locale.ROOT) + "." + name);
    }

    /** 후보 조회용 테이블명 (스키마 접두어 제외) */
    public static String tableNameOf(String raw) {
        Name name = Name.parse(raw);
        return name == null ? null : name.tableName();
    }
}
