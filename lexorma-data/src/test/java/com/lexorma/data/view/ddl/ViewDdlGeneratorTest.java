package com.lexorma.data.view.ddl;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CREATE VIEW DDL (M-15) : 네 DBMS 의 생성 결과 고정 (실제 DB 실행은 PostgreSQL 만 — ViewPostgresDdlTest) */
class ViewDdlGeneratorTest {

    private static final SchemaInfo DEFAULT = SchemaInfo.builder().id(1L).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build();
    private static final SchemaInfo SALES = SchemaInfo.builder().id(2L).schemaName("SALES").isDefault(false).build();

    @Test
    @DisplayName("DBMS 별 : 이름·스키마(따옴표 없음), 원문 보존, 끝의 ; 하나, 논리명 COMMENT (MySQL·MariaDB 는 없음 + 경고)")
    void perDbms() {
        PhysicalView view = view(1L, SALES, "VW_A", "에이 'VIEW'", "select A, 'x;y' -- c\n  from T;\n");
        assertThat(ViewDdlGenerator.generate(DbType.POSTGRESQL, view, null).ddl())
                .isEqualTo("CREATE VIEW SALES.VW_A AS\nselect A, 'x;y' -- c\n  from T;\nCOMMENT ON VIEW SALES.VW_A IS '에이 ''VIEW''';\n");
        assertThat(ViewDdlGenerator.generate(DbType.ORACLE, view, null).ddl())
                .isEqualTo("CREATE VIEW SALES.VW_A AS\nselect A, 'x;y' -- c\n  from T;\nCOMMENT ON TABLE SALES.VW_A IS '에이 ''VIEW''';\n");
        for (DbType mysql : List.of(DbType.MYSQL, DbType.MARIADB)) {
            TableDdlGenerator.Result result = ViewDdlGenerator.generate(mysql, view, null);
            assertThat(result.ddl()).isEqualTo("CREATE VIEW SALES.VW_A AS\nselect A, 'x;y' -- c\n  from T;\n");
            assertThat(result.warnings()).singleElement().asString().contains("COMMENT");
        }
        // 기본 스키마 : 접두어 없음, [스키마명 포함] 이면 그 이름
        PhysicalView plain = view(2L, DEFAULT, "VW_B", null, "SELECT 1");
        assertThat(ViewDdlGenerator.generate(DbType.POSTGRESQL, plain, null).ddl()).isEqualTo("CREATE VIEW VW_B AS\nSELECT 1;\n");
        assertThat(ViewDdlGenerator.generate(DbType.POSTGRESQL, plain, "app").ddl()).isEqualTo("CREATE VIEW app.VW_B AS\nSELECT 1;\n");
    }

    @Test
    @DisplayName("내보내기 순서 : 참조되는 VIEW 먼저(같은 단계는 이름순), 미확인 참조·스크립트 밖 VIEW 는 경고")
    void order() {
        PhysicalView base = view(10L, DEFAULT, "VW_Z", null, "SELECT 1");
        PhysicalView middle = view(11L, DEFAULT, "VW_M", null, "SELECT * FROM VW_Z");
        PhysicalView top = view(12L, DEFAULT, "VW_A", null, "SELECT * FROM VW_M");
        PhysicalView outside = view(13L, DEFAULT, "VW_OUT", null, "SELECT 1");
        middle.getReferences().add(ref(middle, base));
        top.getReferences().add(ref(top, middle));
        top.getReferences().add(ref(top, outside));
        top.getReferences().add(PhysicalViewReference.builder().view(top).referenceType(PhysicalViewReference.Type.TABLE).schemaName("LEGACY").objectName("TB_OLD").build());
        TableDdlGenerator.Result all = ViewDdlGenerator.generateAll(DbType.POSTGRESQL, List.of(top, middle, base), null);
        assertThat(all.ddl().indexOf("VW_Z AS")).isLessThan(all.ddl().indexOf("VW_M AS"));
        assertThat(all.ddl().indexOf("VW_M AS")).isLessThan(all.ddl().indexOf("VW_A AS"));
        assertThat(all.warnings()).anyMatch(w -> w.contains("VW_OUT")).anyMatch(w -> w.contains("LEGACY.TB_OLD"));
    }

    @Test
    @DisplayName("통합 Export(M-11) : 순환에 걸린 VIEW 이름, 스크립트 밖 테이블 참조 경고 (테이블 범위를 줄 때만)")
    void cyclesAndOutsideTables() {
        PhysicalView a = view(20L, DEFAULT, "VW_A", null, "SELECT * FROM VW_B");
        PhysicalView b = view(21L, DEFAULT, "VW_B", null, "SELECT * FROM VW_A");
        PhysicalView c = view(22L, DEFAULT, "VW_C", null, "SELECT * FROM VW_A");
        PhysicalView free = view(23L, DEFAULT, "VW_FREE", null, "SELECT 1");
        a.getReferences().add(ref(a, b));
        b.getReferences().add(ref(b, a));
        c.getReferences().add(ref(c, a));
        assertThat(ViewDdlGenerator.cyclicViews(List.of(free, c, b, a))).as("순환에 걸린 VIEW 와 그 뒤에 기다리는 VIEW").containsExactly("VW_A", "VW_B", "VW_C");
        assertThat(ViewDdlGenerator.cyclicViews(List.of(free))).isEmpty();
        TableDdlGenerator.Result withCycle = ViewDdlGenerator.generateAll(DbType.POSTGRESQL, List.of(free, c, b, a), null);
        assertThat(withCycle.ddl()).startsWith("CREATE VIEW VW_FREE AS");
        assertThat(withCycle.warnings()).contains("순환 참조가 있어 생성 순서를 정할 수 없는 VIEW 가 있습니다 : VW_A, VW_B, VW_C");

        TableInfo table = TableInfo.builder().id(5L).tableName("TB_USER").schema(DEFAULT).build();
        free.getReferences().add(PhysicalViewReference.builder().view(free).referenceType(PhysicalViewReference.Type.TABLE).referencedTable(table).build());
        assertThat(ViewDdlGenerator.generateAll(DbType.POSTGRESQL, List.of(free), null).warnings()).as("VIEW 만 내보내기 : 테이블 참조는 보지 않는다 (M-15 그대로)").isEmpty();
        assertThat(ViewDdlGenerator.generateAll(DbType.POSTGRESQL, List.of(free), null, Set.of(5L)).warnings()).isEmpty();
        assertThat(ViewDdlGenerator.generateAll(DbType.POSTGRESQL, List.of(free), null, Set.of()).warnings())
                .containsExactly("이 스크립트 밖의 테이블을 참조합니다 (대상 DB 에 먼저 있어야 합니다) : VW_FREE → TB_USER");
    }

    private static PhysicalViewReference ref(PhysicalView from, PhysicalView to) {
        return PhysicalViewReference.builder().view(from).referenceType(PhysicalViewReference.Type.VIEW).referencedView(to).build();
    }

    private static PhysicalView view(Long id, SchemaInfo schema, String name, String korName, String sql) {
        return PhysicalView.builder().id(id).projectId(1L).schema(schema).viewName(name).viewKorName(korName).definition(sql).isApproval(true).build();
    }
}
