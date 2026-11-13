package com.lexorma.source.tool.postgresql;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.tool.SourceMetadata;
import com.lexorma.source.tool.SourceMetadata.Owned;
import com.lexorma.source.tool.SourceMetadataTool;
import com.lexorma.source.tool.SourceToolContext;
import com.lexorma.source.tool.SourceToolSession;
import com.lexorma.source.tool.SourceTools;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * PostgreSQL 메타데이터 도구 7개 (AI-2b). 시스템 카탈로그(pg_catalog)만 읽는 고정 조회이며, 도구 하나가 대상 스키마 전체를 <b>한 번의 조회</b>로 읽는다
 * (스키마 이름은 배열 값으로 넣는다 : 테이블마다 조회하지 않는다). 입력으로 받은 SQL·식별자를 이어 붙이지 않는다.
 * <ul>
 *   <li>대상 스키마 : 접속한 데이터베이스의 {@code pg_} 로 시작하지 않는 스키마와 information_schema 를 뺀 전부 (pg_catalog, pg_toast, pg_temp_N 등은 PostgreSQL 이 예약한 이름이다)</li>
 *   <li>대상 객체 : 일반 테이블(r)·파티션 부모(p) = TABLE, 뷰(v) = VIEW. 파티션 자식·구체화 뷰·외부 테이블·시퀀스·임시 테이블은 수집하지 않는다</li>
 *   <li>이름은 카탈로그에 저장된 그대로 넘긴다 (따옴표 여부 = null : 접지 않고 그대로 비교한다)</li>
 *   <li>PostgreSQL 12 이상 (attgenerated, relispartition, conparentid)</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class PostgresSourceTools {

    /** 조회할 테이블·뷰 : 파티션 자식은 부모로만 본다 */
    private static final String RELATIONS = "n.nspname = any(?) and c.relkind in ('r', 'p', 'v') and not c.relispartition";

    private static final String SCHEMAS = """
            select n.nspname from pg_catalog.pg_namespace n
            where pg_catalog.left(n.nspname, 3) <> 'pg_' and n.nspname <> 'information_schema'
            order by n.nspname""";

    private static final String TABLES = """
            select n.nspname, c.relname, c.relkind, d.description
            from pg_catalog.pg_class c
            join pg_catalog.pg_namespace n on n.oid = c.relnamespace
            left join pg_catalog.pg_description d on d.objoid = c.oid and d.classoid = 'pg_catalog.pg_class'::pg_catalog.regclass and d.objsubid = 0
            where""" + " " + RELATIONS + """

            order by n.nspname, c.relname""";

    private static final String COLUMNS = """
            select n.nspname, c.relname, a.attname, a.attnum, pg_catalog.format_type(a.atttypid, a.atttypmod),
                   case when a.atttypid in (1042, 1043) and a.atttypmod > 0 then a.atttypmod - 4
                        when a.atttypid in (1560, 1562) and a.atttypmod > 0 then a.atttypmod end,
                   case when a.atttypid = 1700 and a.atttypmod > 0 then ((a.atttypmod - 4) >> 16) & 65535 end,
                   case when a.atttypid = 1700 and a.atttypmod > 0 then (a.atttypmod - 4) & 65535 end,
                   a.attnotnull, pg_catalog.pg_get_expr(ad.adbin, ad.adrelid), a.attidentity, a.attgenerated, d.description
            from pg_catalog.pg_attribute a
            join pg_catalog.pg_class c on c.oid = a.attrelid
            join pg_catalog.pg_namespace n on n.oid = c.relnamespace
            left join pg_catalog.pg_attrdef ad on ad.adrelid = a.attrelid and ad.adnum = a.attnum
            left join pg_catalog.pg_description d on d.objoid = c.oid and d.classoid = 'pg_catalog.pg_class'::pg_catalog.regclass and d.objsubid = a.attnum
            where a.attnum > 0 and not a.attisdropped and""" + " " + RELATIONS + """

            order by n.nspname, c.relname, a.attnum""";

    /** 구성 컬럼 이름 (순서대로) */
    private static final String KEY_NAMES = "array(select a.attname from pg_catalog.unnest(%s) with ordinality k(attnum, ord) "
            + "join pg_catalog.pg_attribute a on a.attrelid = %s and a.attnum = k.attnum order by k.ord)";

    private static final String CONSTRAINTS = """
            select n.nspname, c.relname, con.conname, con.contype, pg_catalog.pg_get_expr(con.conbin, con.conrelid),""" + " "
            + KEY_NAMES.formatted("con.conkey", "con.conrelid") + """

            from pg_catalog.pg_constraint con
            join pg_catalog.pg_class c on c.oid = con.conrelid
            join pg_catalog.pg_namespace n on n.oid = c.relnamespace
            where con.contype in ('p', 'u', 'c') and""" + " " + RELATIONS + """

            order by n.nspname, c.relname, con.conname""";

    /**
     * 구성 요소마다 : 컬럼명(표현식이면 null), 표현식(pg_get_indexdef 의 그 요소), 정렬.
     * indoption 비트 1 = DESC, 2 = NULLS FIRST. 기본(ASC NULLS LAST, DESC NULLS FIRST)과 다를 때만 NULLS 를 적는다. 키가 아닌 포함 컬럼은 INCLUDE
     */
    private static final String INDEXES = """
            select n.nspname, c.relname, ic.relname, am.amname, i.indisunique, pg_catalog.pg_get_expr(i.indpred, i.indrelid),
                   array(select a.attname from pg_catalog.generate_series(1, i.indnatts) k(ord)
                         left join pg_catalog.pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[k.ord - 1] and i.indkey[k.ord - 1] <> 0 order by k.ord),
                   array(select case when i.indkey[k.ord - 1] = 0 then pg_catalog.pg_get_indexdef(i.indexrelid, k.ord, false) end
                         from pg_catalog.generate_series(1, i.indnatts) k(ord) order by k.ord),
                   array(select case when k.ord > i.indnkeyatts then 'INCLUDE'
                                     when i.indoption[k.ord - 1] & 1 = 1 then case when i.indoption[k.ord - 1] & 2 = 2 then 'DESC' else 'DESC NULLS LAST' end
                                     when i.indoption[k.ord - 1] & 2 = 2 then 'NULLS FIRST' end
                         from pg_catalog.generate_series(1, i.indnatts) k(ord) order by k.ord)
            from pg_catalog.pg_index i
            join pg_catalog.pg_class ic on ic.oid = i.indexrelid
            join pg_catalog.pg_am am on am.oid = ic.relam
            join pg_catalog.pg_class c on c.oid = i.indrelid
            join pg_catalog.pg_namespace n on n.oid = c.relnamespace
            where i.indisvalid and""" + " " + RELATIONS + """

            order by n.nspname, c.relname, ic.relname""";

    /** FK : 파티션 테이블을 참조할 때 생기는 하위 제약조건(conparentid <> 0)은 부모 제약조건 하나로 본다 */
    private static final String RELATIONSHIPS = """
            select n.nspname, c.relname, con.conname, rn.nspname, rc.relname, con.confdeltype, con.confupdtype,""" + " "
            + KEY_NAMES.formatted("con.conkey", "con.conrelid") + ", " + KEY_NAMES.formatted("con.confkey", "con.confrelid") + """

            from pg_catalog.pg_constraint con
            join pg_catalog.pg_class c on c.oid = con.conrelid
            join pg_catalog.pg_namespace n on n.oid = c.relnamespace
            join pg_catalog.pg_class rc on rc.oid = con.confrelid
            join pg_catalog.pg_namespace rn on rn.oid = rc.relnamespace
            where con.contype = 'f' and con.conparentid = 0 and""" + " " + RELATIONS + """

            order by n.nspname, c.relname, con.conname""";

    private static final Map<String, String> RULES = Map.of("a", "NO ACTION", "r", "RESTRICT", "c", "CASCADE", "n", "SET NULL", "d", "SET DEFAULT");

    @Bean
    SourceMetadataTool<Void, SourceMetadata.ConnectionInfo> postgresConnectionTest() {
        return tool(SourceTools.CONNECTION_TEST, Void.class, (context, input) -> {
            String[] product = context.product();
            boolean readable;
            try {
                context.query("select count(*) from pg_catalog.pg_namespace", null, row -> row.getLong(1));
                readable = true;
            } catch (CustomException e) {
                if (e.getErrorCode() != ErrorCode.SOURCE_PERMISSION_DENIED) throw e;
                readable = false;
            }
            // 이 접속이 실제로 암호화되었는지는 서버에 묻는다 (설정만 보고 판단하지 않는다)
            Object[] ssl = context.query("select s.ssl, s.version from pg_catalog.pg_stat_ssl s where s.pid = pg_catalog.pg_backend_pid()", null,
                    row -> new Object[]{row.getBoolean(1), row.getString(2)}).stream().findFirst().orElse(new Object[]{false, null});
            boolean encrypted = Boolean.TRUE.equals(ssl[0]);
            String mode = context.options().getOrDefault("sslmode", "prefer");
            boolean verified = encrypted && (mode.equals("verify-ca") || mode.equals("verify-full"));
            return new SourceMetadata.ConnectionInfo(product[0], product[1], readable,
                    new SourceMetadata.Tls(mode, encrypted, encrypted ? (String) ssl[1] : null, verified, verified && mode.equals("verify-full")));
        });
    }

    @Bean
    SourceMetadataTool<Void, List<String>> postgresSchemaDiscovery() {
        return tool(SourceTools.SCHEMA_DISCOVERY, Void.class, (context, input) -> context.query(SCHEMAS, null, row -> row.getString(1)));
    }

    @Bean
    SourceMetadataTool<SourceMetadata.Scope, List<Owned<SourceCatalogSpec.Table>>> postgresTableDiscovery() {
        return tool(SourceTools.TABLE_DISCOVERY, SourceMetadata.Scope.class, (context, scope) -> context.query(TABLES, schemas(scope), row ->
                new Owned<>(row.getString(1), row.getString(2), new SourceCatalogSpec.Table(Name.of(row.getString(2)),
                        "v".equals(row.getString(3)) ? SourceTableType.VIEW : SourceTableType.TABLE, row.getString(4)))));
    }

    @Bean
    SourceMetadataTool<SourceMetadata.Scope, List<Owned<SourceCatalogSpec.Column>>> postgresColumnDiscovery() {
        return tool(SourceTools.COLUMN_DISCOVERY, SourceMetadata.Scope.class, (context, scope) -> context.query(COLUMNS, schemas(scope), row ->
                new Owned<>(row.getString(1), row.getString(2), new SourceCatalogSpec.Column(Name.of(row.getString(3)), row.getInt(4), row.getString(5),
                        integer(row, 6), row.getObject(7) == null ? null : BigDecimal.valueOf(row.getInt(7)), integer(row, 8), !row.getBoolean(9),
                        defaultValue(row.getString(10), row.getString(11), row.getString(12)), row.getString(13)))));
    }

    @Bean
    SourceMetadataTool<SourceMetadata.Scope, List<Owned<SourceCatalogSpec.Constraint>>> postgresConstraintDiscovery() {
        return tool(SourceTools.CONSTRAINT_DISCOVERY, SourceMetadata.Scope.class, (context, scope) -> context.query(CONSTRAINTS, schemas(scope), row -> {
            SourceConstraintType type = switch (row.getString(4)) {
                case "p" -> SourceConstraintType.PRIMARY_KEY;
                case "u" -> SourceConstraintType.UNIQUE;
                default -> SourceConstraintType.CHECK;
            };
            return new Owned<>(row.getString(1), row.getString(2), new SourceCatalogSpec.Constraint(row.getString(3), type,
                    type == SourceConstraintType.CHECK ? row.getString(5) : null, names(row.getArray(6))));
        }));
    }

    @Bean
    SourceMetadataTool<SourceMetadata.Scope, List<Owned<SourceCatalogSpec.Index>>> postgresIndexDiscovery() {
        return tool(SourceTools.INDEX_DISCOVERY, SourceMetadata.Scope.class, (context, scope) -> context.query(INDEXES, schemas(scope), row -> {
            String[] columns = strings(row.getArray(7));
            String[] expressions = strings(row.getArray(8));
            String[] sorts = strings(row.getArray(9));
            List<SourceCatalogSpec.IndexMember> members = new ArrayList<>();
            for (int i = 0; i < columns.length; i++) {
                members.add(new SourceCatalogSpec.IndexMember(columns[i] == null ? null : Name.of(columns[i]), columns[i] == null ? expressions[i] : null, sorts[i]));
            }
            return new Owned<>(row.getString(1), row.getString(2), new SourceCatalogSpec.Index(row.getString(3), row.getString(4), row.getBoolean(5), members,
                    row.getString(6)));
        }));
    }

    @Bean
    SourceMetadataTool<SourceMetadata.Scope, List<Owned<SourceCatalogSpec.Relation>>> postgresRelationshipDiscovery() {
        return tool(SourceTools.RELATIONSHIP_DISCOVERY, SourceMetadata.Scope.class, (context, scope) -> context.query(RELATIONSHIPS, schemas(scope), row -> {
            String[] own = strings(row.getArray(8));
            String[] referenced = strings(row.getArray(9));
            if (own.length != referenced.length) throw new SQLException("FK column count mismatch", "XX000");
            List<SourceCatalogSpec.ColumnPair> pairs = new ArrayList<>();
            for (int i = 0; i < own.length; i++) pairs.add(new SourceCatalogSpec.ColumnPair(Name.of(own[i]), Name.of(referenced[i])));
            return new Owned<>(row.getString(1), row.getString(2), new SourceCatalogSpec.Relation(row.getString(3), null, Name.of(row.getString(4)),
                    Name.of(row.getString(5)), RULES.get(row.getString(6)), RULES.get(row.getString(7)), pairs));
        }));
    }

    /**
     * 기본값 칸 : 기본값 식, 또는 기본값 대신 값을 정하는 절 (PostgreSQL 은 셋 중 하나만 있다). 원본 DDL 의 표기로 남겨 서로 구분된다.
     * 생성 컬럼 = GENERATED ALWAYS AS (식) STORED|VIRTUAL, Identity = GENERATED ALWAYS|BY DEFAULT AS IDENTITY (시퀀스 옵션은 담지 않는다)
     */
    static String defaultValue(String expression, String identity, String generated) {
        if ("s".equals(generated) || "v".equals(generated)) return "GENERATED ALWAYS AS (" + expression + ") " + ("s".equals(generated) ? "STORED" : "VIRTUAL");
        if ("a".equals(identity)) return "GENERATED ALWAYS AS IDENTITY";
        if ("d".equals(identity)) return "GENERATED BY DEFAULT AS IDENTITY";
        return expression;
    }

    private static SourceToolSession.Binder schemas(SourceMetadata.Scope scope) {
        return statement -> statement.setArray(1, statement.getConnection().createArrayOf("text", scope.schemas().toArray()));
    }

    private static Integer integer(ResultSet row, int index) throws SQLException {
        int value = row.getInt(index);
        return row.wasNull() ? null : value;
    }

    private static String[] strings(Array array) throws SQLException {
        if (array == null) return new String[0];
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(value -> value == null ? null : value.toString()).toArray(String[]::new);
    }

    private static List<Name> names(Array array) throws SQLException {
        return Arrays.stream(strings(array)).map(Name::of).toList();
    }

    private static <I, O> SourceMetadataTool<I, O> tool(String name, Class<I> inputType, BiFunction<SourceToolContext, I, O> body) {
        return new PostgresTool<>(name, inputType, body);
    }

    private record PostgresTool<I, O>(String name, Class<I> inputType, BiFunction<SourceToolContext, I, O> body) implements SourceMetadataTool<I, O> {
        @Override
        public boolean supports(DbType dbType) {
            return dbType == DbType.POSTGRESQL;
        }

        @Override
        public O execute(SourceToolContext context, I input) {
            return body.apply(context, input);
        }
    }
}
