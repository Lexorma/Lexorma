package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.view.ddl.ViewDdlGenerator;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M-15 : 생성한 CREATE VIEW DDL 을 격리된 PostgreSQL 스키마에서 실제로 실행한다 (LEXORMA_TEST_POSTGRES_URL 이 있을 때만, UUID 스키마를 만들고 지운다).
 * 정의 SQL 원문(주석·문자열 안의 ; 와 --·달러 인용·끝의 ;·-- 로 끝나는 원문)이 그대로 실행되고, 내보내기 스크립트의 순서(참조되는 VIEW 먼저)로 한 번에 만들어지는지 본다.
 */
class ViewPostgresDdlTest {

    @Test
    @DisplayName("PostgreSQL 에서 생성 DDL 이 실행된다 : 원문 보존·세미콜론 처리·스키마·COMMENT ON VIEW·참조 순서")
    void generatedViewDdlRunsOnPostgresql() {
        String url = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(url != null, "PostgreSQL 검증 DB 미지정");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "lexorma", "lexorma"));
        String base = "view_ddl_" + UUID.randomUUID().toString().replace("-", "");
        String sales = base + "_sales";
        jdbc.execute("create schema " + base);
        jdbc.execute("create schema " + sales);
        try {
            jdbc.execute("create table " + base + ".TB_USER (USER_ID varchar(20), USER_NM varchar(100), MEMO text, ACTV_YN boolean)");
            SchemaInfo defaultSchema = SchemaInfo.builder().id(1L).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build();
            SchemaInfo salesSchema = SchemaInfo.builder().id(2L).schemaName(sales).isDefault(false).build();

            PhysicalView active = view(10L, defaultSchema, "VW_ACTIVE_USER", "활성 사용자",
                    "-- 활성 사용자\nselect u.USER_ID,\n       u.USER_NM /* 이름 */\n  from " + base + ".TB_USER u\n where u.MEMO <> 'a;b -- c' and u.ACTV_YN\n;\n");
            PhysicalView commented = view(11L, defaultSchema, "VW_COMMENTED", null, "select 1 as ONE -- 끝");
            PhysicalView dollar = view(12L, defaultSchema, "VW_DOLLAR", "달러 인용", "select $$; drop$$ as X;;  -- 끝");
            PhysicalView cte = view(13L, salesSchema, "VW_SALES", "판매 사용자", "WITH x AS (SELECT USER_ID FROM " + base + ".TB_USER) SELECT USER_ID FROM x");
            // 이름순으로는 먼저지만 VW_ACTIVE_USER 를 참조하므로 뒤에 만들어야 한다
            PhysicalView dependent = view(14L, defaultSchema, "VW_0_DEPENDENT", null, "SELECT USER_ID FROM " + base + ".VW_ACTIVE_USER");
            dependent.getReferences().add(PhysicalViewReference.builder().view(dependent).referenceType(PhysicalViewReference.Type.VIEW).referencedView(active).build());

            TableDdlGenerator.Result single = ViewDdlGenerator.generate(DbType.POSTGRESQL, active, base);
            assertThat(single.ddl()).startsWith("CREATE VIEW " + base + ".VW_ACTIVE_USER AS\n-- 활성 사용자\n").contains("'a;b -- c' and u.ACTV_YN;\nCOMMENT ON VIEW");

            TableDdlGenerator.Result all = ViewDdlGenerator.generateAll(DbType.POSTGRESQL, List.of(dependent, cte, dollar, commented, active), base);
            assertThat(all.warnings()).isEmpty();
            assertThat(all.ddl().indexOf("VW_ACTIVE_USER AS")).isLessThan(all.ddl().indexOf("VW_0_DEPENDENT AS"));
            jdbc.execute(all.ddl()); // 스크립트 한 번에 (문장 구분·문자열·주석·달러 인용을 DB 가 그대로 해석한다)

            assertThat(jdbc.queryForList("select table_schema || '.' || table_name from information_schema.views where table_schema in (?, ?) order by 1", String.class, base, sales))
                    .containsExactlyInAnyOrder(base + ".vw_0_dependent", base + ".vw_active_user", base + ".vw_commented", base + ".vw_dollar", sales + ".vw_sales");
            assertThat(jdbc.queryForObject("select obj_description((? || '.vw_active_user')::regclass, 'pg_class')", String.class, base)).isEqualTo("활성 사용자");
            assertThat(jdbc.queryForObject("select x from " + base + ".VW_DOLLAR", String.class)).isEqualTo("; drop");
            assertThat(jdbc.queryForObject("select count(*) from " + base + ".VW_0_DEPENDENT", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select ONE from " + base + ".VW_COMMENTED", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema " + sales + " cascade");
            jdbc.execute("drop schema " + base + " cascade");
        }
    }

    private static PhysicalView view(Long id, SchemaInfo schema, String name, String korName, String definition) {
        return PhysicalView.builder().id(id).projectId(1L).schema(schema).viewName(name).viewKorName(korName).definition(definition).isApproval(true).build();
    }
}
