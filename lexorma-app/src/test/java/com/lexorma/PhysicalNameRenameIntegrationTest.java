package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnTermDto;
import com.lexorma.data.column.service.ColumnTermService;
import com.lexorma.data.mapping.controller.ModelMappingRestController;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Item;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.PlanItem;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Request;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Result;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.sourcemapping.repository.SourceTableMappingRepository;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 물리명 추천 일괄 적용 2차 (P-01~P-15) : 미리보기·적용 API 의 서비스 계약 (H2 / PostgreSQL — 같은 테스트를 두 DB 에서 돌린다).
 * 요청 검사(0·500·501·중복·빈 값·없는 ID·다른 프로젝트), 선택 집합 기준 추천끼리 충돌, 지금 이름 충돌(같은/다른 스키마·VIEW·맞바꾸기), 승인(테이블·컬럼·소속 테이블)·용어(없음·불일치),
 * CHECK·VIEW 참조, DDL 제약조건명(잘림·충돌), 원본 매핑 경고, 적용(이름·사본·승인된 참조 컬럼·버전·이력·매핑 보존·DDL), 오래된 계획·기준값·위조 토큰,
 * 권한(VIEWER), 동시성(기존 updateTable·용어 재연결·같은 적용 두 번 — 서로 다른 연결의 실제 겹침).
 * 실패 주입 롤백은 PhysicalNameRenameRollbackIntegrationTest (스파이 빈 컨텍스트를 물리 생성 롤백 테스트와 나눠 쓴다).
 * 모델은 논리 → 물리 생성으로 만든 뒤 테이블·컬럼 이름을 JDBC 로 바꿔(앞에 O_) 추천명과 다르게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameRenameIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalNameRenameService rename;
    @Autowired ModelMappingRestController controller;
    @Autowired TableDesignService tableDesignService;
    @Autowired ColumnTermService columnTermService;
    @Autowired SourceTableMappingRepository sourceTableMappings;
    @Autowired ProjectRepository projectRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired com.lexorma.data.ddl.service.DdlExportService ddlExport;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 준비

    /** 고객(고객 번호 PK · 고객 명 UNIQUE) · 주문(주문 번호 PK · 고객 번호 — 참조 테이블명 O_CUST, 승인) · 상품. 테이블·컬럼 이름 앞에 O_ */
    private record Fx(GenerationFixture f, long customer, long order, long product, long cust, long ord, long prdt,
                      long custNo, long custNm, long ordNo, long ordCustNo, long prdtNo) {
        AccountDto user() {
            return f.user;
        }
    }

    private Fx fixture(String name, DbType db) {
        GenerationFixture f = GenerationFixture.create(context, "rn-" + name + "-" + System.nanoTime(), db);
        long customer = f.entity("고객");
        long custNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.attr(customer, "고객 명", false, "CUST_NM");
        f.pk(customer, custNo);
        long order = f.entity("주문");
        long ordNo = f.attr(order, "주문 번호", true, "ORD_NO");
        f.attr(order, "주문 고객 번호", false, "ORD_CUST_NO");
        f.pk(order, ordNo);
        long product = f.entity("상품");
        f.pk(product, f.attr(product, "상품 번호", true, "PRDT_NO"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer, order, product)));
        generation.apply(f.user, f.request(List.of(customer, order, product), plan.planToken()));
        long projectId = f.user.getProjectId();
        jdbc.update("update TB_TBL set TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM, TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        long cust = f.table(customer), ord = f.table(order), prdt = f.table(product);
        jdbc.update("update TB_COL set UNQ_YN = true where TBL_SN = ? and COL_NM = 'O_CUST_NM'", cust);
        // 다른 테이블이 이름으로 고객 테이블을 가리킨다 (승인된 컬럼 — P-11 : 참조 이름 사본은 동기화한다)
        jdbc.update("update TB_COL set RFRNC_TBL_NM = 'O_CUST', APRV_YN = true where TBL_SN = ? and COL_NM = 'O_ORD_CUST_NO'", ord);
        return new Fx(f, customer, order, product, cust, ord, prdt,
                colId(cust, "O_CUST_NO"), colId(cust, "O_CUST_NM"), colId(ord, "O_ORD_NO"), colId(ord, "O_ORD_CUST_NO"), colId(prdt, "O_PRDT_NO"));
    }

    private long colId(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private String tableName(long id) {
        return jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, id);
    }

    private String columnName(long id) {
        return jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, id);
    }

    private static Item table(long id, String name) {
        return new Item(TargetType.TABLE, id, name, null);
    }

    private static Item column(long id, String name) {
        return new Item(TargetType.COLUMN, id, name, null);
    }

    private Plan preview(Fx x, Item... items) {
        return rename.preview(x.user(), new Request(List.of(items), null));
    }

    /** 미리보기가 준 기준값으로 적용 */
    private Result apply(Fx x, Plan plan, Item... items) {
        return rename.apply(x.user(), request(plan, items));
    }

    private static Request request(Plan plan, Item... items) {
        List<Item> withBase = new ArrayList<>();
        for (Item item : items) {
            PlanItem p = plan.items().stream().filter(i -> i.type() == item.type() && i.id().equals(item.id())).findFirst().orElseThrow();
            withBase.add(new Item(item.type(), item.id(), item.recommendedName(), p.modifiedAt()));
        }
        return new Request(withBase, plan.planToken());
    }

    private static PlanItem item(Plan plan, TargetType type, long id) {
        return plan.items().stream().filter(i -> i.type() == type && i.id() == id).findFirst().orElseThrow();
    }

    private static List<ApplyCode> blockers(PlanItem item) {
        return item.applicability().blockers().stream().map(ApplyIssue::code).toList();
    }

    private static String code(Runnable r) {
        try {
            r.run();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        }
    }

    private int versions(Fx x) {
        return x.f.versions();
    }

    // ------------------------------------------------------------------ 요청 검사

    @Test
    @DisplayName("요청 검사 : 0건·501건·중복·빈 종류·빈 ID·빈 추천명 → 400, 없는 ID·다른 프로젝트 ID → 대상 없음, 적용은 토큰·기준값 필수")
    void requestValidation() {
        Fx x = fixture("req", DbType.POSTGRESQL);
        Fx other = fixture("req-other", DbType.POSTGRESQL);
        AccountDto user = x.user();
        assertThat(code(() -> rename.preview(user, new Request(List.of(), null)))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(code(() -> rename.preview(user, null))).isEqualTo("INVALID_PHYSICAL_MODEL");
        List<Item> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) many.add(column(-1_000_000L - i, "X"));
        assertThat(code(() -> rename.preview(user, new Request(many, null)))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(code(() -> preview(x, table(x.cust(), "CUST"), table(x.cust(), "CUST")))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(code(() -> preview(x, new Item(null, x.cust(), "CUST", null)))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(code(() -> preview(x, new Item(TargetType.TABLE, null, "CUST", null)))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(code(() -> preview(x, table(x.cust(), " ")))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(code(() -> preview(x, table(987_654_321L, "CUST")))).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(code(() -> preview(x, column(987_654_321L, "CUST_NO")))).isEqualTo("COLUMN_IS_NOT_DEFINED");
        // 다른 프로젝트의 ID : 없는 ID 와 같은 답 (프로젝트 격리)
        assertThat(code(() -> preview(x, table(other.cust(), "CUST")))).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(code(() -> preview(x, column(other.custNo(), "CUST_NO")))).isEqualTo("COLUMN_IS_NOT_DEFINED");
        // 같은 ID 라도 종류가 다르면 다른 대상이다
        assertThat(code(() -> preview(x, table(x.cust(), "CUST"), column(x.custNo(), "CUST_NO")))).isEqualTo("OK");

        Plan plan = preview(x, table(x.cust(), "CUST"));
        assertThat(code(() -> rename.apply(user, new Request(List.of(new Item(TargetType.TABLE, x.cust(), "CUST", plan.items().get(0).modifiedAt())), null))))
                .isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(code(() -> rename.apply(user, new Request(List.of(table(x.cust(), "CUST")), plan.planToken())))).isEqualTo("MODEL_BASE_REQUIRED");
        assertThat(tableName(x.cust())).isEqualTo("O_CUST");
        assertThat(tableName(other.cust())).isEqualTo("O_CUST");
    }

    // ------------------------------------------------------------------ 적용

    @Test
    @DisplayName("적용 : 테이블·컬럼 혼합 — 물리명만 바뀌고 소속 컬럼 테이블명 사본·승인된 참조 컬럼의 참조 테이블명이 따라 바뀐다. 직전 RENAME 버전·이력·매핑·한글명·승인 상태 보존")
    void applyMixed() {
        Fx x = fixture("apply", DbType.POSTGRESQL);
        long projectId = x.user().getProjectId();
        String korName = jdbc.queryForObject("select TBL_KORN_NM from TB_TBL where TBL_SN = ?", String.class, x.cust());
        Long mappingTable = jdbc.queryForObject("select TBL_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, x.customer());
        Long attributeOfCustNo = jdbc.queryForObject("select LGCL_ATRB_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, x.custNo());
        int versions = versions(x);
        Item[] items = {table(x.cust(), "CUST"), column(x.custNo(), "CUST_NO"), column(x.custNm(), "cust_nm"), table(x.ord(), "ORD"), column(x.ordNo(), "ORD_NO")};

        Plan plan = preview(x, items);
        assertThat(plan.total()).isEqualTo(5);
        assertThat(plan.tables()).isEqualTo(2);
        assertThat(plan.columns()).isEqualTo(3);
        assertThat(plan.applicable()).as(plan.items().toString()).isTrue();
        assertThat(plan.selectable()).isEqualTo(5);
        assertThat(plan.blocked()).isZero();
        assertThat(plan.planToken()).hasSize(64);
        assertThat(item(plan, TargetType.TABLE, x.cust())).satisfies(i -> {
            assertThat(i.currentName()).isEqualTo("O_CUST");
            assertThat(i.recommendedName()).isEqualTo("CUST");
            assertThat(i.logicalName()).isEqualTo("고객");
            assertThat(i.modifiedAt()).isNotNull();
            assertThat(i.noChange()).isFalse();
        });
        // 미리보기는 아무것도 바꾸지 않는다
        assertThat(tableName(x.cust())).isEqualTo("O_CUST");
        assertThat(versions(x)).isEqualTo(versions);

        Result result = apply(x, plan, items);
        assertThat(result.tables()).isEqualTo(2);
        assertThat(result.columns()).isEqualTo(3);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.versionName()).startsWith("RENAME-");
        assertThat(result.versionId()).isNotNull();
        assertThat(result.warnings()).isEmpty();

        assertThat(tableName(x.cust())).isEqualTo("CUST");
        assertThat(tableName(x.ord())).isEqualTo("ORD");
        assertThat(tableName(x.prdt())).isEqualTo("O_PRDT");
        assertThat(columnName(x.custNo())).isEqualTo("CUST_NO");
        assertThat(columnName(x.custNm())).isEqualTo("CUST_NM");
        assertThat(columnName(x.ordNo())).isEqualTo("ORD_NO");
        assertThat(columnName(x.ordCustNo())).isEqualTo("O_ORD_CUST_NO");
        // 소속 컬럼의 테이블명 사본 (고르지 않은 컬럼도)
        assertThat(jdbc.queryForList("select distinct TBL_NM from TB_COL where TBL_SN = ?", String.class, x.cust())).containsExactly("CUST");
        // 승인된 참조 컬럼 : 참조 테이블명만 바뀌고 승인·이름은 그대로 (P-11)
        assertThat(jdbc.queryForMap("select RFRNC_TBL_NM, APRV_YN, COL_NM from TB_COL where COL_SN = ?", x.ordCustNo()))
                .containsEntry(key("RFRNC_TBL_NM"), "CUST").containsEntry(key("COL_NM"), "O_ORD_CUST_NO");
        assertThat(jdbc.queryForObject("select APRV_YN from TB_COL where COL_SN = ?", Boolean.class, x.ordCustNo())).isTrue();
        // 한글명·매핑·승인 상태 보존 (P-02)
        assertThat(jdbc.queryForObject("select TBL_KORN_NM from TB_TBL where TBL_SN = ?", String.class, x.cust())).isEqualTo(korName);
        assertThat(jdbc.queryForObject("select TBL_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, x.customer())).isEqualTo(mappingTable);
        assertThat(jdbc.queryForObject("select LGCL_ATRB_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, x.custNo())).isEqualTo(attributeOfCustNo);
        assertThat(jdbc.queryForObject("select APRV_YN from TB_TBL where TBL_SN = ?", Boolean.class, x.cust())).isFalse();

        // 직전 버전 : 하나, 바뀌기 전 이름을 담는다 (P-06)
        assertThat(versions(x)).isEqualTo(versions + 1);
        String snapshot = jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, result.versionId());
        assertThat(snapshot).contains("\"O_CUST\"").contains("\"O_CUST_NO\"").doesNotContain("\"CUST\"");
        assertThat(jdbc.queryForObject("select VER_NM from TB_MDL_VER where MDL_VER_SN = ?", String.class, result.versionId())).isEqualTo(result.versionName());

        // 이력 : 변경 그룹 = 버전명, 출처 USER, 바뀌기 전·후 이름
        List<Map<String, Object>> history = jdbc.queryForList("select TRGT_TYPE_CD, TRGT_SN, CHG_SRC_CD, CHG_BFR_CN, CHG_AFTR_CN, USER_ID from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ?",
                projectId, result.versionName());
        assertThat(history).isNotEmpty();
        Map<String, Object> custHistory = history.stream().filter(h -> "PHYSICAL_TABLE".equals(h.get(key("TRGT_TYPE_CD"))) && ((Number) h.get(key("TRGT_SN"))).longValue() == x.cust())
                .findFirst().orElseThrow();
        assertThat(custHistory.get(key("CHG_SRC_CD"))).isEqualTo("USER");
        assertThat((String) custHistory.get(key("CHG_BFR_CN"))).contains("O_CUST");
        assertThat((String) custHistory.get(key("CHG_AFTR_CN"))).contains("\"CUST\"");
        assertThat(custHistory.get(key("USER_ID"))).isEqualTo(x.user().getUsername());
        assertThat(history).anySatisfy(h -> {
            assertThat(h.get(key("TRGT_TYPE_CD"))).isEqualTo("PHYSICAL_COLUMN");
            assertThat(((Number) h.get(key("TRGT_SN"))).longValue()).isEqualTo(x.ordCustNo()); // 참조 테이블명 사본 동기화도 같은 그룹
        });

        // DDL 회귀 : 바뀐 이름으로 PK_·UK_·FK_ 를 만들고, 참조 컬럼의 FK 는 바뀐 테이블을 가리킨다 (PostgreSQL 이면 임시 스키마에 실제로 실행해 본다)
        String ddl = ddlExport.export(x.user(), null, "POSTGRESQL", null, false).ddl();
        assertThat(ddl).contains("CREATE TABLE CUST (", "CUST_NO", "CUST_NM", "CONSTRAINT PK_CUST PRIMARY KEY", "UK_CUST_CUST_NM", "CREATE TABLE ORD (", "REFERENCES CUST")
                .doesNotContain("O_CUST ", "O_CUST(", "O_CUST_NO");
        if (postgres()) {
            String schema = "rn_ddl_" + System.nanoTime();
            jdbc.execute((java.sql.Connection c) -> {
                try (java.sql.Statement st = c.createStatement()) {
                    st.execute("create schema " + schema + "; set search_path to " + schema);
                    try {
                        st.execute(ddl);
                        java.util.function.ToIntFunction<String> count = sql -> {
                            try (java.sql.ResultSet rs = st.executeQuery(sql)) {
                                rs.next();
                                return rs.getInt(1);
                            } catch (java.sql.SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        };
                        int tables = count.applyAsInt("select count(*) from information_schema.tables where table_schema = '" + schema + "' and table_name in ('cust', 'ord', 'o_prdt')");
                        int columns = count.applyAsInt("select count(*) from information_schema.columns where table_schema = '" + schema
                                + "' and ((table_name = 'cust' and column_name in ('cust_no', 'cust_nm')) or (table_name = 'ord' and column_name = 'ord_no'))");
                        int keys = count.applyAsInt("select count(*) from information_schema.table_constraints where table_schema = '" + schema
                                + "' and constraint_name in ('pk_cust', 'uk_cust_cust_nm', 'pk_ord')");
                        int foreignKeys = count.applyAsInt("select count(*) from information_schema.referential_constraints r join information_schema.table_constraints t"
                                + " on t.constraint_schema = r.constraint_schema and t.constraint_name = r.unique_constraint_name where r.constraint_schema = '" + schema
                                + "' and t.table_name = 'cust'");
                        assertThat(tables).isEqualTo(3);
                        assertThat(columns).isEqualTo(3);
                        assertThat(keys).isEqualTo(3);
                        assertThat(foreignKeys).as("ORD → CUST FK").isOne();
                        // 실행 확인용 로그 (테스트 결과 XML 의 system-out 에 남는다 — 이 분기가 실제로 돌았는지 확인)
                        System.out.println("PG-DDL-EXECUTED schema=" + schema + " tables=" + tables + " columns=" + columns + " pkUnique=" + keys + " fk=" + foreignKeys);
                    } finally {
                        st.execute("set search_path to public; drop schema " + schema + " cascade");
                    }
                }
                return null;
            });
        }

        // 다시 미리보기 : 바꿀 것 없음
        Plan again = preview(x, table(x.cust(), "CUST"), column(x.custNo(), "CUST_NO"));
        assertThat(again.items()).allSatisfy(i -> {
            assertThat(i.noChange()).isTrue();
            assertThat(blockers(i)).containsExactly(ApplyCode.NO_CHANGE);
        });
    }

    /** H2 는 대문자, PostgreSQL 은 소문자 열 이름 */
    private String key(String column) {
        return postgres() ? column.toLowerCase() : column;
    }

    private boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    @Test
    @DisplayName("500건 : 테이블 1 + 컬럼 499 를 한 번에 적용한다 (P-07, 합산)")
    void apply500() {
        GenerationFixture f = GenerationFixture.create(context, "rn-500-" + System.nanoTime(), DbType.POSTGRESQL);
        long entity = f.entity("고객");
        long first = f.attr(entity, "키", true, "C000");
        f.pk(entity, first);
        for (int i = 1; i < 499; i++) f.attr(entity, "속성" + i, false, String.format("C%03d", i));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(entity)));
        generation.apply(f.user, f.request(List.of(entity), plan.planToken()));
        long table = f.table(entity);
        jdbc.update("update TB_TBL set TBL_NM = 'O_' || TBL_NM where TBL_SN = ?", table);
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM, TBL_NM = 'O_' || TBL_NM where TBL_SN = ?", table);
        List<Item> items = new ArrayList<>();
        items.add(table(table, "CUST"));
        jdbc.queryForList("select COL_SN, COL_NM from TB_COL where TBL_SN = ? order by COL_SN", table)
                .forEach(r -> items.add(column(((Number) r.get(key("COL_SN"))).longValue(), ((String) r.get(key("COL_NM"))).substring(2))));
        assertThat(items).hasSize(500);
        Plan preview = rename.preview(f.user, new Request(items, null));
        assertThat(preview.applicable()).as(preview.items().stream().filter(i -> !i.applicability().selectable()).limit(3).toList().toString()).isTrue();
        Result result = rename.apply(f.user, request(preview, items.toArray(Item[]::new)));
        assertThat(result.total()).isEqualTo(500);
        assertThat(result.columns()).isEqualTo(499);
        assertThat(tableName(table)).isEqualTo("CUST");
        assertThat(f.count("select count(*) from TB_COL where TBL_SN = ? and COL_NM like 'O_%'", table)).isZero();
    }

    // ------------------------------------------------------------------ 충돌

    @Test
    @DisplayName("추천끼리 충돌은 선택 집합 기준 (P-12) : 하나만 고르면 적용 가능, 둘 다 고르면 둘 다 막힌다 — 테이블(같은 스키마)·컬럼(같은 테이블)")
    void selectionDuplicates() {
        Fx x = fixture("dup", DbType.POSTGRESQL);
        // 다른 모델의 같은 이름 엔터티 → 같은 스키마의 다른 테이블이 같은 추천명 CUST 를 받는다
        GenerationFixture second = x.f().otherModel("두 번째 " + System.nanoTime());
        long customer2 = second.entity("고객");
        second.pk(customer2, second.attr(customer2, "고객 키", true, "CUST_KEY"));
        generation.apply(second.user, second.request(List.of(customer2), generation.preview(second.user, second.request(List.of(customer2))).planToken()));
        long cust2 = second.table(customer2);
        jdbc.update("update TB_TBL set TBL_NM = 'P_CUST' where TBL_SN = ?", cust2);
        assertThat(blockers(item(preview(x, table(x.cust(), "CUST")), TargetType.TABLE, x.cust()))).isEmpty();
        Plan both = preview(x, table(x.cust(), "CUST"), table(cust2, "CUST"));
        // 둘 다 CUST 가 되면 DDL 의 PK_CUST 도 겹친다 (P-13) — 추천끼리 충돌과 함께 막힌다
        assertThat(blockers(item(both, TargetType.TABLE, x.cust()))).containsExactly(ApplyCode.RECOMMENDATION_DUPLICATE, ApplyCode.CONSTRAINT_NAME_CONFLICT);
        assertThat(item(both, TargetType.TABLE, x.cust()).applicability().blockers().get(0).related()).containsExactly("P_CUST");
        assertThat(blockers(item(both, TargetType.TABLE, cust2))).containsExactly(ApplyCode.RECOMMENDATION_DUPLICATE, ApplyCode.CONSTRAINT_NAME_CONFLICT);
        assertThat(both.applicable()).isFalse();
        assertThat(both.blocked()).isEqualTo(2);

        // 컬럼 : 고객 명의 용어 약어를 CUST_NO 로 바꾸면 같은 테이블의 두 컬럼이 같은 추천명을 받는다 (지금 CUST_NO 를 쓰는 컬럼은 없다 — 둘 다 O_)
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = 'CUST_NO' where STD_TRM_SN = (select STD_TRM_SN from TB_COL where COL_SN = ?)", x.custNm());
        assertThat(item(preview(x, column(x.custNm(), "CUST_NO")), TargetType.COLUMN, x.custNm()).applicability().selectable()).isTrue();
        Plan columns = preview(x, column(x.custNo(), "CUST_NO"), column(x.custNm(), "CUST_NO"));
        assertThat(blockers(item(columns, TargetType.COLUMN, x.custNo()))).containsExactly(ApplyCode.RECOMMENDATION_DUPLICATE);
        assertThat(blockers(item(columns, TargetType.COLUMN, x.custNm()))).containsExactly(ApplyCode.RECOMMENDATION_DUPLICATE);
    }

    @Test
    @DisplayName("지금 이름 충돌 : 같은 스키마의 테이블·VIEW 는 충돌, 다른 스키마는 아니다. 이름 맞바꾸기(테이블·컬럼)는 둘 다 막힌다")
    void currentConflicts() {
        Fx x = fixture("conflict", DbType.POSTGRESQL);
        long projectId = x.user().getProjectId();
        // 다른 스키마의 CUST 는 충돌이 아니다
        tx.executeWithoutResult(s -> {
            SchemaInfo other = SchemaInfo.builder().projectId(projectId).schemaName("S2").schemaDescription("다른 스키마").isDefault(false).build();
            entityManager.persist(other);
            entityManager.persist(TableInfo.builder().projectId(projectId).schema(other).tableName("CUST").isApproval(false).isMasterTable(false).build());
        });
        assertThat(blockers(item(preview(x, table(x.cust(), "CUST")), TargetType.TABLE, x.cust()))).isEmpty();
        // 같은 스키마의 VIEW CUST
        long viewId = view(x, "CUST", "select 1 as one", null);
        PlanItem withView = item(preview(x, table(x.cust(), "CUST")), TargetType.TABLE, x.cust());
        assertThat(blockers(withView)).containsExactly(ApplyCode.NAME_CONFLICT);
        assertThat(withView.applicability().blockers().get(0).related()).containsExactly("CUST");
        jdbc.update("delete from TB_VIEW where VIEW_SN = ?", viewId);

        // 맞바꾸기 : 주문 테이블이 지금 CUST, 고객 테이블이 지금 ORD
        jdbc.update("update TB_TBL set TBL_NM = 'ORD' where TBL_SN = ?", x.cust());
        jdbc.update("update TB_TBL set TBL_NM = 'CUST' where TBL_SN = ?", x.ord());
        Plan swap = preview(x, table(x.cust(), "CUST"), table(x.ord(), "ORD"));
        assertThat(blockers(item(swap, TargetType.TABLE, x.cust()))).containsExactly(ApplyCode.NAME_CONFLICT);
        assertThat(blockers(item(swap, TargetType.TABLE, x.ord()))).containsExactly(ApplyCode.NAME_CONFLICT);
        jdbc.update("update TB_TBL set TBL_NM = 'O_CUST' where TBL_SN = ?", x.cust());
        jdbc.update("update TB_TBL set TBL_NM = 'O_ORD' where TBL_SN = ?", x.ord());

        // 컬럼 맞바꾸기 (대소문자만 다른 이름도 같은 이름)
        jdbc.update("update TB_COL set COL_NM = 'cust_nm' where COL_SN = ?", x.custNo());
        jdbc.update("update TB_COL set COL_NM = 'CUST_NO' where COL_SN = ?", x.custNm());
        Plan columnSwap = preview(x, column(x.custNo(), "CUST_NO"), column(x.custNm(), "CUST_NM"));
        assertThat(blockers(item(columnSwap, TargetType.COLUMN, x.custNo()))).containsExactly(ApplyCode.NAME_CONFLICT);
        assertThat(blockers(item(columnSwap, TargetType.COLUMN, x.custNm()))).containsExactly(ApplyCode.NAME_CONFLICT);
        // 적용 요청도 거절, 아무것도 바뀌지 않는다
        int versions = versions(x);
        assertThat(code(() -> rename.apply(x.user(), request(columnSwap, column(x.custNo(), "CUST_NO"), column(x.custNm(), "CUST_NM"))))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(columnName(x.custNo())).isEqualTo("cust_nm");
        assertThat(versions(x)).isEqualTo(versions);
    }

    private long view(Fx x, String name, String definition, Long referencedTable) {
        long projectId = x.user().getProjectId();
        return tx.execute(s -> {
            SchemaInfo schema = entityManager.find(TableInfo.class, x.cust()).getSchema();
            PhysicalView v = PhysicalView.builder().projectId(projectId).schema(schema).viewName(name).definition(definition).isApproval(false).build();
            entityManager.persist(v);
            if (referencedTable != null) {
                entityManager.persist(PhysicalViewReference.builder().view(v).referenceType(PhysicalViewReference.Type.TABLE)
                        .referencedTable(entityManager.find(TableInfo.class, referencedTable)).build());
            }
            return v.getId();
        });
    }

    private void check(Fx x, long table, String name, String expression) {
        tx.executeWithoutResult(s -> entityManager.persist(TableConstraint.builder().projectId(x.user().getProjectId()).tableInfo(entityManager.find(TableInfo.class, table))
                .constraintName(name).constraintType(TableConstraint.Type.CHECK).checkExpression(expression).isApproval(false).build()));
    }

    // ------------------------------------------------------------------ 승인 · 용어 · 참조

    @Test
    @DisplayName("정책 : 승인된 테이블·컬럼·소속 테이블(P-04·P-10), 용어 없음(P-09)·불일치(P-03), CHECK·VIEW 참조(P-08), 매핑 없음 — 막힌 대상이 있으면 적용 전체 거절")
    void policies() {
        Fx x = fixture("policy", DbType.POSTGRESQL);
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.prdt());
        assertThat(blockers(item(preview(x, table(x.prdt(), "PRDT")), TargetType.TABLE, x.prdt()))).containsExactly(ApplyCode.TABLE_APPROVED);
        // 승인된 테이블의 (미승인) 컬럼 : 소속 테이블 승인으로 막힌다
        assertThat(blockers(item(preview(x, column(x.prdtNo(), "PRDT_NO")), TargetType.COLUMN, x.prdtNo()))).containsExactly(ApplyCode.TABLE_APPROVED);
        jdbc.update("update TB_COL set APRV_YN = true where COL_SN = ?", x.ordNo());
        assertThat(blockers(item(preview(x, column(x.ordNo(), "ORD_NO")), TargetType.COLUMN, x.ordNo()))).containsExactly(ApplyCode.COLUMN_APPROVED);
        jdbc.update("update TB_COL set APRV_YN = false where COL_SN = ?", x.ordNo());
        // 용어 없음 · 불일치
        Long custNmTerm = jdbc.queryForObject("select STD_TRM_SN from TB_COL where COL_SN = ?", Long.class, x.custNm());
        jdbc.update("update TB_COL set STD_TRM_SN = null where COL_SN = ?", x.custNm());
        assertThat(blockers(item(preview(x, column(x.custNm(), "CUST_NM")), TargetType.COLUMN, x.custNm()))).containsExactly(ApplyCode.COLUMN_TERM_MISSING);
        long otherTerm = x.f().term("OTHER_NM", "VARCHAR", 20);
        jdbc.update("update TB_COL set STD_TRM_SN = ? where COL_SN = ?", otherTerm, x.custNm());
        assertThat(blockers(item(preview(x, column(x.custNm(), "CUST_NM")), TargetType.COLUMN, x.custNm()))).containsExactly(ApplyCode.TERM_MISMATCH);
        jdbc.update("update TB_COL set STD_TRM_SN = ? where COL_SN = ?", custNmTerm, x.custNm());
        // 매핑 없음
        jdbc.update("delete from TB_ATRB_COL_MPNG where COL_SN = ?", x.custNm());
        assertThat(blockers(item(preview(x, column(x.custNm(), "CUST_NM")), TargetType.COLUMN, x.custNm()))).containsExactly(ApplyCode.NOT_MAPPED);

        // CHECK : 식이 옛 컬럼명을 쓴다 (리터럴은 참조가 아니다)
        check(x, x.cust(), "CK_CUST_NO", "length(O_CUST_NO) > 0");
        check(x, x.ord(), "CK_ORD_LIT", "'O_ORD_NO' <> ''");
        assertThat(blockers(item(preview(x, column(x.custNo(), "CUST_NO")), TargetType.COLUMN, x.custNo()))).containsExactly(ApplyCode.CHECK_REFERENCE);
        assertThat(blockers(item(preview(x, column(x.ordNo(), "ORD_NO")), TargetType.COLUMN, x.ordNo()))).isEmpty();
        // VIEW : 테이블을 참조 (정의의 낱말), 컬럼은 SELECT *
        view(x, "V_ORD", "select * from O_ORD", x.ord());
        Plan withView = preview(x, table(x.ord(), "ORD"), column(x.ordNo(), "ORD_NO"));
        assertThat(blockers(item(withView, TargetType.TABLE, x.ord()))).containsExactly(ApplyCode.VIEW_REFERENCE);
        assertThat(blockers(item(withView, TargetType.COLUMN, x.ordNo()))).containsExactly(ApplyCode.VIEW_REFERENCE);
        // 판정할 수 없는 VIEW (닫히지 않은 따옴표) : 모든 이름 변경이 막힌다
        long opaque = view(x, "V_BAD", "select 'unclosed from O_PRDT", null);
        jdbc.update("update TB_TBL set APRV_YN = false where TBL_SN = ?", x.prdt());
        assertThat(blockers(item(preview(x, table(x.prdt(), "PRDT")), TargetType.TABLE, x.prdt()))).contains(ApplyCode.REFERENCE_UNVERIFIABLE);
        jdbc.update("delete from TB_VIEW where VIEW_SN = ?", opaque);

        // 막힌 대상이 하나라도 있으면 적용은 거절 (부분 적용 없음)
        int versions = versions(x);
        Plan mixed = preview(x, table(x.prdt(), "PRDT"), table(x.ord(), "ORD"));
        assertThat(item(mixed, TargetType.TABLE, x.prdt()).applicability().selectable()).isTrue();
        assertThat(code(() -> rename.apply(x.user(), request(mixed, table(x.prdt(), "PRDT"), table(x.ord(), "ORD"))))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(tableName(x.prdt())).isEqualTo("O_PRDT");
        assertThat(versions(x)).isEqualTo(versions);
    }

    @Test
    @DisplayName("DDL 제약조건명 (P-13) : 바뀐 이름으로 만든 PK_ 가 같은 테이블의 저장된 제약조건명과 겹치면 충돌, Oracle 30바이트를 넘으면 잘림 — 막는다")
    void constraintNames() {
        Fx x = fixture("ddl", DbType.POSTGRESQL);
        check(x, x.cust(), "PK_CUST", "1 = 1");
        PlanItem conflict = item(preview(x, table(x.cust(), "CUST")), TargetType.TABLE, x.cust());
        assertThat(blockers(conflict)).containsExactly(ApplyCode.CONSTRAINT_NAME_CONFLICT);
        assertThat(conflict.applicability().blockers().get(0).related()).contains("PK_CUST");
        // 저장된 이름은 고치지 않는다 : 바꾸지 않으면 판정도 없다
        assertThat(blockers(item(preview(x, column(x.ordNo(), "ORD_NO")), TargetType.COLUMN, x.ordNo()))).isEmpty();
        // 컬럼명 변경 : UNIQUE 컬럼의 UK_테이블_컬럼 이 저장된 이름과 겹친다
        check(x, x.cust(), "UK_O_CUST_CUST_NM", "1 = 1");
        assertThat(blockers(item(preview(x, column(x.custNm(), "CUST_NM")), TargetType.COLUMN, x.custNm()))).containsExactly(ApplyCode.CONSTRAINT_NAME_CONFLICT);

        GenerationFixture f = GenerationFixture.create(context, "rn-ora-" + System.nanoTime(), DbType.ORACLE);
        long entity = f.entity("고객 주문 직원 부서 팀 이력"); // CUST_ORD_EMP_DEPT_TEAM_HSTRY (28바이트) → PK_ 31바이트
        f.pk(entity, f.attr(entity, "키", true, "K1"));
        generation.apply(f.user, f.request(List.of(entity), generation.preview(f.user, f.request(List.of(entity))).planToken()));
        long table = f.table(entity);
        jdbc.update("update TB_TBL set TBL_NM = 'O_T' where TBL_SN = ?", table);
        PlanItem truncated = rename.preview(f.user, new Request(List.of(table(table, "CUST_ORD_EMP_DEPT_TEAM_HSTRY")), null)).items().get(0);
        assertThat(blockers(truncated)).containsExactly(ApplyCode.CONSTRAINT_NAME_INVALID);
        assertThat(truncated.applicability().blockers().get(0).reason()).contains("30");
    }

    @Test
    @DisplayName("원본 매핑 (P-15) : 경고만 — 적용할 수 있고 결과에 경고가 남는다. 원본 매핑은 그대로")
    void sourceMappedWarning() {
        Fx x = fixture("source", DbType.POSTGRESQL);
        long projectId = x.user().getProjectId();
        SourceTableMapping saved = tx.execute(s -> sourceTableMappings.save(SourceTableMapping.builder().projectId(projectId).connectionId(7_100_001L).sourceSchemaId(1L)
                .sourceTableId(7_100_002L).pathKey("public").compareName("o_cust").creationCycle(1).reflectedOrder(1).tableId(x.cust()).excluded(false).build()));
        Item[] items = {table(x.cust(), "CUST"), column(x.custNo(), "CUST_NO"), table(x.ord(), "ORD")};
        Plan plan = preview(x, items);
        assertThat(plan.applicable()).isTrue();
        assertThat(plan.warned()).isEqualTo(2);
        assertThat(item(plan, TargetType.TABLE, x.cust()).applicability().warnings()).extracting(ApplyIssue::code).containsExactly(ApplyCode.SOURCE_MAPPED);
        assertThat(item(plan, TargetType.COLUMN, x.custNo()).applicability().warnings()).extracting(ApplyIssue::code).containsExactly(ApplyCode.SOURCE_MAPPED);
        assertThat(item(plan, TargetType.TABLE, x.ord()).applicability().warnings()).isEmpty();
        Result result = apply(x, plan, items);
        assertThat(result.warnings()).hasSize(2);
        assertThat(tableName(x.cust())).isEqualTo("CUST");
        assertThat(jdbc.queryForObject("select TBL_SN from TB_SRC_TBL_MPNG where SRC_TBL_MPNG_SN = ?", Long.class, saved.getId())).isEqualTo(x.cust());
        assertThat(jdbc.queryForObject("select CMPR_NM from TB_SRC_TBL_MPNG where SRC_TBL_MPNG_SN = ?", String.class, saved.getId())).isEqualTo("o_cust");
    }

    // ------------------------------------------------------------------ 오래된 계획 · 롤백 · 권한

    @Test
    @DisplayName("오래된 계획 : 미리보기 뒤 승인·이름·VIEW·용어 약어가 바뀌면 409, 위조 토큰 409, 기준값이 다르면 409 — 아무것도 바뀌지 않는다")
    void stalePlans() {
        Fx x = fixture("stale", DbType.POSTGRESQL);
        int versions = versions(x);
        Item[] items = {table(x.cust(), "CUST"), column(x.custNo(), "CUST_NO")};

        Plan plan = preview(x, items);
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.cust());
        assertThat(code(() -> apply(x, plan, items))).isEqualTo("MODEL_CONFLICT");
        jdbc.update("update TB_TBL set APRV_YN = false where TBL_SN = ?", x.cust());

        Plan plan2 = preview(x, items);
        jdbc.update("update TB_COL set COL_NM = 'O2_CUST_NO' where COL_SN = ?", x.custNo()); // 다른 사용자의 이름 변경
        assertThat(code(() -> apply(x, plan2, items))).isEqualTo("MODEL_CONFLICT");
        jdbc.update("update TB_COL set COL_NM = 'O_CUST_NO' where COL_SN = ?", x.custNo());

        Plan plan3 = preview(x, items);
        long viewId = view(x, "V_CUST", "select O_CUST_NO from O_CUST", x.cust());
        assertThat(code(() -> apply(x, plan3, items))).isEqualTo("MODEL_CONFLICT");
        jdbc.update("delete from TB_VIEW_RFRNC where VIEW_SN = ?", viewId);
        jdbc.update("delete from TB_VIEW where VIEW_SN = ?", viewId);

        Plan plan4 = preview(x, items);
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = 'CUST_NUM' where STD_TRM_SN = (select STD_TRM_SN from TB_COL where COL_SN = ?)", x.custNo());
        assertThat(code(() -> apply(x, plan4, items))).isEqualTo("MODEL_CONFLICT");
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = 'CUST_NO' where STD_TRM_SN = (select STD_TRM_SN from TB_COL where COL_SN = ?)", x.custNo());

        Plan plan5 = preview(x, items);
        Request forged = new Request(request(plan5, items).items(), "0".repeat(64));
        assertThat(code(() -> rename.apply(x.user(), forged))).isEqualTo("MODEL_CONFLICT");
        // 요청 추천명을 바꿔 보내도 서버 추천명만 쓴다 — 미리보기에서 막히고, 그 토큰으로도 적용되지 않는다
        Plan tampered = preview(x, table(x.cust(), "HACKED"));
        assertThat(blockers(tampered.items().get(0))).containsExactly(ApplyCode.RECOMMENDATION_CHANGED);
        assertThat(code(() -> rename.apply(x.user(), request(tampered, table(x.cust(), "HACKED"))))).isEqualTo("INVALID_PHYSICAL_MODEL");
        // 기준값 (DEC-08)
        Request oldBase = new Request(List.of(new Item(TargetType.TABLE, x.cust(), "CUST", plan5.items().get(0).modifiedAt().minusSeconds(5)),
                new Item(TargetType.COLUMN, x.custNo(), "CUST_NO", plan5.items().get(1).modifiedAt())), plan5.planToken());
        assertThat(code(() -> rename.apply(x.user(), oldBase))).isEqualTo("MODEL_CONFLICT");

        assertThat(tableName(x.cust())).isEqualTo("O_CUST");
        assertThat(columnName(x.custNo())).isEqualTo("O_CUST_NO");
        assertThat(versions(x)).isEqualTo(versions);
        assertThat(code(() -> apply(x, plan5, items))).isEqualTo("OK");
    }

    @Test
    @DisplayName("권한 : VIEWER 는 미리보기·적용 모두 거절 (USER 이상 — 기존 물리 모델 편집 권한)")
    void viewerDenied() {
        Fx x = fixture("role", DbType.POSTGRESQL);
        Plan plan = preview(x, table(x.cust(), "CUST"));
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, x.user().getProjectId(), x.user().getId(), "ROLE_VIEWER");
        assertThatThrownBy(() -> controller.renamePreview(new Request(List.of(table(x.cust(), "CUST")), null), viewer))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> controller.rename(request(plan, table(x.cust(), "CUST")), viewer))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(tableName(x.cust())).isEqualTo("O_CUST");
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, x.user().getProjectId(), x.user().getId(), "ROLE_USER");
        assertThat(controller.renamePreview(new Request(List.of(table(x.cust(), "CUST")), null), user).getBody().applicable()).isTrue();
    }

    // ------------------------------------------------------------------ 동시성 (서로 다른 연결의 실제 겹침)

    private record Outcome(boolean secondWaited, String second, String first) {
    }

    /** 첫째를 트랜잭션 안에서 실행하고 커밋 전에 잠금을 쥔 채 멈춘다. 그 사이 둘째를 다른 스레드·연결로 실행한다 (500ms 안에 끝나지 않으면 기다린 것) — SubtypeGenerationConcurrencyIntegrationTest 와 같은 방식 */
    private Outcome ordered(Supplier<Object> first, Supplier<Object> second) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.setContext(login);
            try {
                return tx.execute(status -> {
                    String result;
                    try {
                        first.get();
                        result = "OK";
                    } catch (CustomException e) {
                        status.setRollbackOnly();
                        result = e.getErrorCode().name();
                    }
                    firstDone.countDown();
                    try {
                        release.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return result;
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        assertThat(firstDone.await(15, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.setContext(login);
            try {
                second.get();
                return "OK";
            } catch (CustomException e) {
                return e.getErrorCode().name();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        boolean waited;
        try {
            waiter.get(500, TimeUnit.MILLISECONDS);
            waited = false;
        } catch (java.util.concurrent.TimeoutException e) {
            waited = true;
        }
        release.countDown();
        String firstResult = holder.get(15, TimeUnit.SECONDS);
        return new Outcome(waited, waiter.get(15, TimeUnit.SECONDS), firstResult);
    }

    private Object updateTable(Fx x, long table, String name) {
        TableInfoDto dto = new TableInfoDto();
        dto.setId(table);
        dto.setTableName(name);
        dto.setIsMasterTable("N");
        dto.setSortOrder(1);
        dto.setTableDescription("설명");
        dto.setBaseModifiedAt(jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", LocalDateTime.class, table));
        tableDesignService.updateTable(dto, x.user());
        return null;
    }

    @Test
    @DisplayName("동시성 1 : 기존 테이블 수정(updateTable)이 먼저 테이블 행을 쥐면 적용은 기다리지 않고 409, 커밋 뒤 다시 미리보기하면 바뀐 이름 기준")
    void updateTableFirst() throws Exception {
        Fx x = fixture("cc1", DbType.POSTGRESQL);
        int versions = versions(x);
        Item[] items = {table(x.cust(), "CUST"), column(x.custNo(), "CUST_NO")};
        Plan plan = preview(x, items);
        Outcome o = ordered(() -> updateTable(x, x.cust(), "O_CUST2"), () -> apply(x, plan, items));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(tableName(x.cust())).isEqualTo("O_CUST2");
        assertThat(columnName(x.custNo())).isEqualTo("O_CUST_NO");
        assertThat(versions(x)).isEqualTo(versions);
        assertThat(item(preview(x, items), TargetType.TABLE, x.cust()).currentName()).isEqualTo("O_CUST2");
    }

    @Test
    @DisplayName("동시성 2 : 적용이 먼저면 같은 테이블의 updateTable 은 기다린 뒤 기준값이 달라 409 — 적용 결과는 그대로")
    void applyFirstThenUpdateTable() throws Exception {
        Fx x = fixture("cc2", DbType.POSTGRESQL);
        Item[] items = {table(x.cust(), "CUST")};
        Plan plan = preview(x, items);
        LocalDateTime base = jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", LocalDateTime.class, x.cust());
        Outcome o = ordered(() -> apply(x, plan, items), () -> {
            TableInfoDto dto = new TableInfoDto();
            dto.setId(x.cust());
            dto.setTableName("O_CUST3");
            dto.setIsMasterTable("N");
            dto.setSortOrder(1);
            dto.setTableDescription("설명");
            dto.setBaseModifiedAt(base);
            tableDesignService.updateTable(dto, x.user());
            return null;
        });
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).isTrue();
        assertThat(o.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(tableName(x.cust())).isEqualTo("CUST");
    }

    @Test
    @DisplayName("동시성 3 : 같은 계획을 두 번 동시에 적용하면 둘째는 프로젝트 행을 기다린 뒤 계획이 달라져 409 — 한 번만 반영, 버전 하나")
    void sameApplyTwice() throws Exception {
        Fx x = fixture("cc3", DbType.POSTGRESQL);
        int versions = versions(x);
        Item[] items = {table(x.cust(), "CUST"), column(x.custNo(), "CUST_NO")};
        Plan plan = preview(x, items);
        Outcome o = ordered(() -> apply(x, plan, items), () -> apply(x, plan, items));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).isTrue();
        assertThat(o.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(versions(x)).isEqualTo(versions + 1);
        assertThat(tableName(x.cust())).isEqualTo("CUST");
    }

    @Test
    @DisplayName("동시성 4 : 컬럼 용어 재연결(ColumnTermService)이 먼저 컬럼 행을 쥐면 적용은 409, 커밋 뒤 미리보기는 용어 불일치")
    void termRelinkFirst() throws Exception {
        Fx x = fixture("cc4", DbType.POSTGRESQL);
        Item[] items = {column(x.custNm(), "CUST_NM")};
        Plan plan = preview(x, items);
        long otherTerm = x.f().term("OTHER_NM", "VARCHAR", 20);
        Long current = jdbc.queryForObject("select STD_TRM_SN from TB_COL where COL_SN = ?", Long.class, x.custNm());
        Outcome o = ordered(() -> columnTermService.relink(x.user(), x.custNm(), new ColumnTermDto.Request(otherTerm, current)), () -> apply(x, plan, items));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(o.secondWaited()).isFalse();
        assertThat(columnName(x.custNm())).isEqualTo("O_CUST_NM");
        assertThat(blockers(item(preview(x, items), TargetType.COLUMN, x.custNm()))).containsExactly(ApplyCode.TERM_MISMATCH);
    }

    @Test
    @DisplayName("동시성 5 : 서로 다른 두 적용이 같은 테이블의 두 컬럼을 같은 이름으로 바꾸면 하나만 반영 — 둘째는 기다린 뒤 이름 충돌로 계획이 달라져 409")
    void twoAppliesSameName() throws Exception {
        Fx x = fixture("cc5", DbType.POSTGRESQL);
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = 'CUST_NO' where STD_TRM_SN = (select STD_TRM_SN from TB_COL where COL_SN = ?)", x.custNm());
        Item[] a = {column(x.custNo(), "CUST_NO")};
        Item[] b = {column(x.custNm(), "CUST_NO")};
        Plan pa = preview(x, a);
        Plan pb = preview(x, b);
        assertThat(pa.applicable()).isTrue();
        assertThat(pb.applicable()).isTrue();
        Outcome o = ordered(() -> apply(x, pa, a), () -> apply(x, pb, b));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).isTrue();
        assertThat(o.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(x.f().count("select count(*) from TB_COL where TBL_SN = ? and upper(COL_NM) = 'CUST_NO'", x.cust())).isOne();
    }
}
