package com.lexorma.source.collection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.catalog.service.SourceCatalogRules;
import com.lexorma.source.catalog.service.SourceIdentifiers;
import com.lexorma.source.catalog.service.SourceIdentifiers.Kind;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.domain.enums.CollectionType;
import com.lexorma.source.collection.domain.enums.TargetResult;
import com.lexorma.source.collection.service.SourceCatalogCanonical.ColumnPair;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.lexorma.source.catalog.service.SourceCatalogRules.invalid;
import static com.lexorma.source.catalog.service.SourceCatalogRules.limited;
import static com.lexorma.source.catalog.service.SourceCatalogRules.optional;
import static com.lexorma.source.catalog.service.SourceCatalogRules.required;
import static com.lexorma.source.catalog.service.SourceCatalogRules.rule;

/**
 * 수집 결과를 검증하고 정규화한다 (AI-1c, 트랜잭션 밖 : Lexorma DB 를 잠그지 않는다). 저장 규칙은 {@link SourceCatalogRules} 그대로다.
 * <ul>
 *   <li>범위 : PARTIAL 은 요청한 스키마와 결과의 스키마가 같아야 한다 (없는 스키마는 NOT_FOUND, 요청하지 않은 스키마는 받지 않는다). FULL 은 스키마가 하나 이상</li>
 *   <li>완결성 : 모든 대상이 COMPLETED 여야 반영한다. 아니면 대상별 결과만 남기고 실행을 실패로 둔다 (부분 반영 없음)</li>
 *   <li>구조 : 스키마 경로·테이블·컬럼 비교 명이 겹치지 않고, 컬럼 순서가 겹치지 않고, 제약조건·인덱스·관계의 구성 컬럼이 그 테이블에 있어야 한다</li>
 *   <li>크기 : 한 번에 받는 테이블·컬럼 수 상한 ({@code lexorma.source.collection.max-tables}, {@code max-columns})</li>
 * </ul>
 */
@Component
public class SourceCollectionPreparer {

    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,99}$");

    private final int maxTables;
    private final int maxColumns;

    public SourceCollectionPreparer(@Value("${lexorma.source.collection.max-tables:20000}") int maxTables,
                                    @Value("${lexorma.source.collection.max-columns:1000000}") int maxColumns) {
        this.maxTables = maxTables;
        this.maxColumns = maxColumns;
    }

    /**
     * @param requested PARTIAL 이 등록할 때 요청한 대상 (경로 → 대상). FULL 은 비어 있다
     */
    PreparedCollection prepare(SourceConnection connection, CollectionType type, Map<String, PreparedCollection.Target> requested, SourceCollectionSpec.Result result) {
        if (result == null || result.schemas() == null) throw invalid("수집 결과가 없습니다.");
        DbType dbType = connection.getDbType();
        Map<String, SourceCollectionSpec.Schema> byPath = new LinkedHashMap<>();
        Map<String, String[]> names = new HashMap<>();
        for (SourceCollectionSpec.Schema schema : result.schemas()) {
            if (schema == null) throw invalid("수집 결과가 올바르지 않습니다.");
            String catalogName = optional(schema.catalog());
            String schemaName = optional(schema.schema());
            if (catalogName == null && schemaName == null) throw invalid("카탈로그와 스키마 중 하나는 있어야 합니다.");
            String path = SourceIdentifiers.pathKey(SourceCatalogRules.compare(connection, Kind.NAMESPACE, schema.catalog()),
                    SourceCatalogRules.compare(connection, Kind.NAMESPACE, schema.schema()));
            if (byPath.put(path, schema) != null) throw invalid("같은 스키마가 두 번 있습니다 : {0}", display(catalogName, schemaName));
            names.put(path, new String[]{catalogName, schemaName});
            if (schema.status() == null) throw invalid("스키마의 수집 결과가 없습니다 : {0}", display(catalogName, schemaName));
        }

        List<PreparedCollection.Target> targets = new ArrayList<>();
        String incomplete = null;
        if (type == CollectionType.PARTIAL) {
            for (String path : byPath.keySet()) {
                if (!requested.containsKey(path)) throw invalid("요청하지 않은 스키마가 결과에 있습니다 : {0}", display(names.get(path)[0], names.get(path)[1]));
            }
            for (PreparedCollection.Target target : requested.values()) {
                if (!byPath.containsKey(target.pathKey())) {
                    targets.add(new PreparedCollection.Target(target.pathKey(), target.catalogName(), target.schemaName(), TargetResult.NOT_FOUND, null, 0, 0));
                    incomplete = "요청한 스키마가 원본에 없습니다.";
                }
            }
        } else if (byPath.isEmpty()) {
            incomplete = "전체 수집 결과에 스키마가 없습니다.";
        }

        List<PreparedCollection.Schema> schemas = new ArrayList<>();
        int tableCount = 0;
        int columnCount = 0;
        for (Map.Entry<String, SourceCollectionSpec.Schema> entry : byPath.entrySet()) {
            SourceCollectionSpec.Schema schema = entry.getValue();
            String[] name = names.get(entry.getKey());
            if (schema.status() != TargetResult.COMPLETED) {
                targets.add(new PreparedCollection.Target(entry.getKey(), name[0], name[1], schema.status(), errorCode(schema.errorCode()), 0, 0));
                if (incomplete == null) incomplete = "완전하게 수집하지 못한 스키마가 있습니다.";
                continue;
            }
            List<SourceCollectionSpec.Table> specTables = schema.tables() == null ? List.of() : schema.tables();
            tableCount += specTables.size();
            if (tableCount > maxTables) throw invalid("한 번에 반영할 수 있는 테이블 수({0})를 넘었습니다.", maxTables);
            List<PreparedCollection.Table> tables = new ArrayList<>();
            Set<String> tableNames = new HashSet<>();
            int schemaColumns = 0;
            for (SourceCollectionSpec.Table table : specTables) {
                PreparedCollection.Table prepared = table(connection, dbType, entry.getKey(), name[0], name[1], table);
                if (!tableNames.add(prepared.compareName())) throw invalid("같은 이름의 테이블이 두 번 있습니다 : {0}", prepared.canonical().name());
                schemaColumns += prepared.canonical().columns().size();
                tables.add(prepared);
            }
            columnCount += schemaColumns;
            if (columnCount > maxColumns) throw invalid("한 번에 반영할 수 있는 컬럼 수({0})를 넘었습니다.", maxColumns);
            tables.sort(Comparator.comparing(PreparedCollection.Table::compareName));
            SourceCatalogCanonical.Schema canonical = new SourceCatalogCanonical.Schema(entry.getKey(), name[0], name[1]);
            String json = SourceCatalogCanonical.json(canonical.json());
            schemas.add(new PreparedCollection.Schema(canonical, json, SourceCatalogCanonical.hash(json), tables));
            targets.add(new PreparedCollection.Target(entry.getKey(), name[0], name[1], TargetResult.COMPLETED, null, tables.size(), schemaColumns));
        }
        boolean complete = incomplete == null;
        return new PreparedCollection(connection.getId(), dbType, connection.getIdentifierComparison(), targets, complete ? schemas : List.of(), complete, incomplete,
                tableCount, columnCount);
    }

    private PreparedCollection.Table table(SourceConnection connection, DbType dbType, String schemaPath, String ownCatalogName, String ownSchemaName,
                                           SourceCollectionSpec.Table spec) {
        if (spec == null || spec.table() == null) throw invalid("수집 결과가 올바르지 않습니다.");
        SourceCatalogSpec.Table table = spec.table();
        String name = required(table.name());
        String compareName = SourceCatalogRules.compare(connection, Kind.TABLE, table.name());
        SourceTableType type = table.type() == null ? SourceTableType.TABLE : table.type();

        // 컬럼 : 비교 명·순서가 겹치지 않는다
        List<SourceCatalogCanonical.Column> columns = new ArrayList<>();
        Map<String, String> columnHashes = new LinkedHashMap<>();
        Set<Integer> ordinals = new HashSet<>();
        for (SourceCatalogSpec.Column column : spec.columns() == null ? List.<SourceCatalogSpec.Column>of() : spec.columns()) {
            if (column == null) throw invalid("수집 결과가 올바르지 않습니다.");
            String columnName = required(column.name());
            if (column.ordinal() == null || column.ordinal() < 1) throw invalid("컬럼 순서가 올바르지 않습니다.");
            if (!ordinals.add(column.ordinal())) throw invalid("컬럼 순서가 겹칩니다 : {0}", name + "." + columnName);
            String columnCompare = SourceCatalogRules.compare(connection, Kind.COLUMN, column.name());
            SourceCatalogCanonical.Column canonical = new SourceCatalogCanonical.Column(columnName, columnCompare, column.name().quoted(), column.ordinal(),
                    SourceCatalogRules.text255(column.dataType()), column.maxLength(), SourceCatalogCanonical.precision(SourceCatalogRules.precision(column.precision())),
                    column.scale(), column.nullable(), column.defaultValue(), column.comment());
            if (columnHashes.put(columnCompare, SourceCatalogCanonical.hash(SourceCatalogCanonical.json(canonical.json()))) != null) {
                throw invalid("같은 이름의 컬럼이 두 번 있습니다 : {0}", name + "." + columnName);
            }
            columns.add(canonical);
        }

        // 제약조건 (PK·UNIQUE·CHECK)
        List<SourceCatalogCanonical.Constraint> constraints = new ArrayList<>();
        for (SourceCatalogSpec.Constraint constraint : spec.constraints() == null ? List.<SourceCatalogSpec.Constraint>of() : spec.constraints()) {
            if (constraint == null || constraint.type() == null) throw invalid("제약조건 유형이 없습니다.");
            List<String> members = members(connection, columnHashes, constraint.columns());
            if (constraint.type() != SourceConstraintType.CHECK && members.isEmpty()) throw invalid("구성 컬럼이 없습니다.");
            String constraintName = limited(constraint.name());
            String check = constraint.type() == SourceConstraintType.CHECK ? constraint.checkExpression() : null;
            constraints.add(new SourceCatalogCanonical.Constraint(SourceCatalogCanonical.constraintKey(dbType, constraint.type().name(), constraintName, members, check),
                    constraintName, constraint.type().name(), check, members));
        }
        constraints = SourceCatalogCanonical.unique(constraints);

        List<SourceCatalogCanonical.Index> indexes = new ArrayList<>();
        for (SourceCatalogSpec.Index index : spec.indexes() == null ? List.<SourceCatalogSpec.Index>of() : spec.indexes()) {
            if (index == null) throw invalid("수집 결과가 올바르지 않습니다.");
            String indexName = limited(index.name());
            if (indexName == null) throw invalid("이름이 없거나 너무 깁니다.");
            if (index.members() == null || index.members().isEmpty()) throw invalid("구성 컬럼이 없습니다.");
            // 구성 요소 : 이 테이블의 컬럼 또는 표현식 (PostgreSQL 의 표현식 인덱스). 순서대로 둔다
            List<String> members = new ArrayList<>();
            List<String> expressions = new ArrayList<>();
            List<String> sorts = new ArrayList<>();
            for (SourceCatalogSpec.IndexMember member : index.members()) {
                if (member == null) throw invalid("구성 컬럼이 없습니다.");
                boolean column = member.name() != null && member.name().value() != null;
                boolean expression = member.expression() != null && !member.expression().isEmpty();
                if (column == expression) throw invalid("인덱스 구성 요소는 컬럼과 표현식 중 하나여야 합니다.");
                members.add(column ? member(connection, columnHashes, member.name()) : null);
                expressions.add(expression ? member.expression() : null);
                sorts.add(SourceCatalogRules.sort(member.sort()));
            }
            String predicate = index.predicate() == null || index.predicate().isEmpty() ? null : index.predicate();
            indexes.add(new SourceCatalogCanonical.Index(SourceCatalogCanonical.indexKey(dbType, indexName, index.unique(), members, expressions), indexName,
                    SourceCatalogRules.text255(index.indexType()), index.unique(), members, expressions, sorts, predicate));
        }
        indexes = SourceCatalogCanonical.unique(indexes);

        // 관계 (FK) : 참조 대상은 원본이 준 이름 그대로 남기고, 내부 참조를 이을 비교용 경로·이름을 따로 만든다
        List<SourceCatalogCanonical.Relation> relations = new ArrayList<>();
        java.util.IdentityHashMap<SourceCatalogCanonical.Relation, PreparedCollection.RelationTarget> targets = new java.util.IdentityHashMap<>();
        String ownCatalog = SourceIdentifiers.catalogOf(schemaPath);
        for (SourceCatalogSpec.Relation relation : spec.relations() == null ? List.<SourceCatalogSpec.Relation>of() : spec.relations()) {
            if (relation == null) throw invalid("수집 결과가 올바르지 않습니다.");
            if (relation.columns() == null || relation.columns().isEmpty()) throw invalid("구성 컬럼이 없습니다.");
            String referencedTable = required(relation.referencedTable());
            String referencedCatalog = optional(relation.referencedCatalog());
            String referencedSchema = optional(relation.referencedSchema());
            String referencedPath;
            if (referencedCatalog == null && referencedSchema == null) {
                // 지정하지 않으면 참조하는 테이블과 같은 스키마 (저장 규칙 : 그 스키마의 이름을 적는다)
                referencedPath = schemaPath;
            } else {
                String catalogCompare = referencedCatalog == null ? ownCatalog : SourceCatalogRules.compare(connection, Kind.NAMESPACE, relation.referencedCatalog());
                referencedPath = SourceIdentifiers.pathKey(catalogCompare, SourceCatalogRules.compare(connection, Kind.NAMESPACE, relation.referencedSchema()));
            }
            List<ColumnPair> pairs = new ArrayList<>();
            List<String> referencedColumns = new ArrayList<>();
            for (SourceCatalogSpec.ColumnPair pair : relation.columns()) {
                if (pair == null) throw invalid("구성 컬럼이 없습니다.");
                String own = member(connection, columnHashes, pair.column());
                pairs.add(new ColumnPair(own, required(pair.referencedColumn())));
                referencedColumns.add(SourceCatalogRules.compare(connection, Kind.COLUMN, pair.referencedColumn()));
            }
            String relationName = limited(relation.name());
            // 저장하는 값과 같게 : 카탈로그를 생략하면 같은 카탈로그, 둘 다 생략하면 같은 스키마의 이름을 적는다 (SourceCatalogWriter 와 같은 규칙)
            String storedCatalog = referencedCatalog == null ? ownCatalogName : referencedCatalog;
            String storedSchema = referencedCatalog == null && referencedSchema == null ? ownSchemaName : referencedSchema;
            String key = SourceCatalogCanonical.relationKey(dbType, relationName, storedCatalog, storedSchema, referencedTable, pairs);
            SourceCatalogCanonical.Relation canonical = new SourceCatalogCanonical.Relation(key, relationName, storedCatalog, storedSchema, referencedTable,
                    rule(relation.deleteRule()), rule(relation.updateRule()), pairs);
            relations.add(canonical);
            targets.put(canonical, new PreparedCollection.RelationTarget(referencedPath, SourceCatalogRules.compare(connection, Kind.TABLE, relation.referencedTable()), referencedColumns));
        }
        // 이름 없는 같은 구성의 관계에 #2 … 를 붙이고, 참조 대상도 같은 키로 찾게 둔다
        Map<String, PreparedCollection.RelationTarget> relationTargets = new LinkedHashMap<>();
        List<SourceCatalogCanonical.Relation> keyed = new ArrayList<>();
        for (Map.Entry<SourceCatalogCanonical.Relation, SourceCatalogCanonical.Relation> entry : SourceCatalogCanonical.rekey(relations)) {
            keyed.add(entry.getValue());
            relationTargets.put(entry.getValue().key(), targets.get(entry.getKey()));
        }

        SourceCatalogCanonical.Table canonical = SourceCatalogCanonical.Table.of(schemaPath, name, compareName, table.name().quoted(), type.name(), table.comment(),
                columns, constraints, indexes, keyed);
        String json = SourceCatalogCanonical.json(canonical.json());
        return new PreparedCollection.Table(canonical, json, SourceCatalogCanonical.hash(json), columnHashes, relationTargets);
    }

    /** 구성 컬럼 : 같은 테이블의 컬럼만 (비교 명) */
    private static List<String> members(SourceConnection connection, Map<String, String> columns, List<Name> names) {
        List<String> members = new ArrayList<>();
        if (names == null) return members;
        for (Name name : names) members.add(member(connection, columns, name));
        return members;
    }

    private static String member(SourceConnection connection, Map<String, String> columns, Name name) {
        String value = required(name);
        String compare = SourceCatalogRules.compare(connection, Kind.COLUMN, name);
        if (!columns.containsKey(compare)) throw invalid("구성 컬럼을 찾을 수 없습니다 : {0}", value);
        return compare;
    }

    /** 도구가 알려 준 오류 코드 : 정해진 모양(대문자·숫자·밑줄)만 남긴다 (메시지·접속 정보가 섞여 들어오지 않게) */
    static String errorCode(String code) {
        if (code == null) return null;
        return ERROR_CODE.matcher(code).matches() ? code : "UNKNOWN";
    }

    private static String display(String catalog, String schema) {
        return catalog == null ? schema : schema == null ? catalog : catalog + "." + schema;
    }

    static CustomException incomplete(String reason) {
        return new CustomException(com.lexorma.common.exception.domain.ErrorCode.SOURCE_COLLECTION_INCOMPLETE, reason);
    }
}
