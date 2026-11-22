package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Summary;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 논리·물리 구조 정합성 (논리 관계 ↔ FK, 보조 식별자 ↔ UNIQUE, 물리 기준 역방향 — H2 / PostgreSQL 같은 판정).
 * 모델은 논리 → 물리 생성으로 만들고(FK : 단일 = 참조 테이블명, 복합·자기 참조 = 관계), 어긋남은 JDBC 로 물리 쪽을 바꿔 만든다. 진단은 읽기 전용이라 데이터가 바뀌지 않는 것도 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelStructureQualityIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ModelMappingQualityService quality;
    @Autowired LogicalModelService logical;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired ProjectRepository projectRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 관계 : 고객 → 주문(단일), 부서(복합 키) → 팀(복합), 직원 → 직원(자기 참조), 고객 → 결제(짝 없음), 상품 → 배송(부모 미생성), 지점 → 배송(대상 바꿀 것) */
    private record Fx(GenerationFixture f, Map<String, Long> e, Map<String, Long> a, Map<String, Long> r) {
        long table(String entity) {
            return f.table(e.get(entity));
        }
    }

    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "msq-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        Map<String, Long> e = new java.util.HashMap<>();
        Map<String, Long> a = new java.util.HashMap<>();
        Map<String, Long> r = new java.util.HashMap<>();
        e.put("고객", f.entity("고객"));
        a.put("CUST_NO", f.attr(e.get("고객"), "고객 번호", true, "CUST_NO"));
        a.put("CUST_NM", f.attr(e.get("고객"), "고객 명", false, "CUST_NM"));
        a.put("CUST_EML", f.attr(e.get("고객"), "고객 메일", false, "CUST_EML"));
        f.pk(e.get("고객"), a.get("CUST_NO"));
        e.put("주문", f.entity("주문"));
        a.put("ORD_NO", f.attr(e.get("주문"), "주문 번호", true, "ORD_NO"));
        a.put("ORD_CUST_NO", f.attr(e.get("주문"), "주문 고객 번호", false, "ORD_CUST_NO"));
        a.put("ORD_PRDT_NO", f.attr(e.get("주문"), "주문 상품 번호", false, "ORD_PRDT_NO"));
        f.pk(e.get("주문"), a.get("ORD_NO"));
        e.put("부서", f.entity("부서"));
        a.put("K1", f.attr(e.get("부서"), "키1", true, "K1"));
        a.put("K2", f.attr(e.get("부서"), "키2", true, "K2"));
        f.pk(e.get("부서"), a.get("K1"), a.get("K2"));
        e.put("팀", f.entity("팀"));
        a.put("TEAM_NO", f.attr(e.get("팀"), "팀 번호", true, "TEAM_NO"));
        a.put("FA1", f.attr(e.get("팀"), "부서 키1", true, "FA1"));
        a.put("FA2", f.attr(e.get("팀"), "부서 키2", true, "FA2"));
        f.pk(e.get("팀"), a.get("TEAM_NO"));
        e.put("직원", f.entity("직원"));
        a.put("EMP_NO", f.attr(e.get("직원"), "직원 번호", true, "EMP_NO"));
        a.put("MGR_NO", f.attr(e.get("직원"), "상위 직원 번호", false, "MGR_NO"));
        f.pk(e.get("직원"), a.get("EMP_NO"));
        e.put("결제", f.entity("결제"));
        a.put("STLM_NO", f.attr(e.get("결제"), "결제 번호", true, "STLM_NO"));
        a.put("STLM_CUST_NO", f.attr(e.get("결제"), "결제 고객 번호", false, "STLM_CUST_NO"));
        f.pk(e.get("결제"), a.get("STLM_NO"));
        e.put("상품", f.entity("상품"));
        a.put("PRDT_NO", f.attr(e.get("상품"), "상품 번호", true, "PRDT_NO"));
        a.put("PRDT_NM", f.attr(e.get("상품"), "상품 명", false, "PRDT_NM"));
        a.put("PRDT_CD", f.attr(e.get("상품"), "상품 코드", false, "PRDT_CD"));
        f.pk(e.get("상품"), a.get("PRDT_NO"));
        e.put("지점", f.entity("지점"));
        a.put("BRNCH_NO", f.attr(e.get("지점"), "지점 번호", true, "BRNCH_NO"));
        f.pk(e.get("지점"), a.get("BRNCH_NO"));
        e.put("배송", f.entity("배송"));
        a.put("DLVR_NO", f.attr(e.get("배송"), "배송 번호", true, "DLVR_NO"));
        a.put("DLVR_BRNCH_NO", f.attr(e.get("배송"), "배송 지점 번호", false, "DLVR_BRNCH_NO"));
        a.put("DLVR_PRDT_NO", f.attr(e.get("배송"), "배송 상품 번호", false, "DLVR_PRDT_NO"));
        f.pk(e.get("배송"), a.get("DLVR_NO"));

        LogicalModelDto.Relationship order = f.rel("주문함", e.get("고객"), e.get("주문"), false, 0);
        f.pairs(order, new long[]{a.get("CUST_NO"), a.get("ORD_CUST_NO")});
        r.put("주문함", order.id());
        LogicalModelDto.Relationship team = f.rel("소속", e.get("부서"), e.get("팀"), false, 0);
        f.pairs(team, new long[]{a.get("K1"), a.get("FA1")}, new long[]{a.get("K2"), a.get("FA2")});
        r.put("소속", team.id());
        LogicalModelDto.Relationship manager = f.rel("상위", e.get("직원"), e.get("직원"), false, 0);
        f.pairs(manager, new long[]{a.get("EMP_NO"), a.get("MGR_NO")});
        r.put("상위", manager.id());
        r.put("결제함", f.rel("결제함", e.get("고객"), e.get("결제"), false, 0).id()); // 짝 없음
        LogicalModelDto.Relationship ship = f.rel("배송함", e.get("상품"), e.get("배송"), false, 0);
        f.pairs(ship, new long[]{a.get("PRDT_NO"), a.get("DLVR_PRDT_NO")});
        r.put("배송함", ship.id());
        LogicalModelDto.Relationship branch = f.rel("담당", e.get("지점"), e.get("배송"), false, 0);
        f.pairs(branch, new long[]{a.get("BRNCH_NO"), a.get("DLVR_BRNCH_NO")});
        r.put("담당", branch.id());

        // 상품은 만들지 않는다 (배송함 : 부모 미매핑)
        List<Long> chosen = List.of(e.get("고객"), e.get("주문"), e.get("부서"), e.get("팀"), e.get("직원"), e.get("결제"), e.get("지점"), e.get("배송"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        return new Fx(f, e, a, r);
    }

    private List<Row> rows(Fx x, CheckGroup group) {
        return quality.selectRows(x.f().user, new Search(x.f().modelId, null, null, null, null, null, group), PageRequest.of(0, 2000)).getContent();
    }

    private static Row relationship(List<Row> rows, long relationshipId) {
        return rows.stream().filter(r -> r.type() == CheckType.RELATIONSHIP && Objects.equals(r.structure().logicalId(), relationshipId)).findFirst().orElseThrow();
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private long relationOf(long table, long parentTable) {
        return jdbc.queryForObject("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK'", Long.class, table, parentTable);
    }

    private String snapshot(Fx x) {
        long p = x.f().user.getProjectId();
        return jdbc.queryForList("select REL_SN || ':' || ORGNL_TBL_SN || ':' || TRGT_TBL_SN from TB_TBL_REL where PRJ_SN = ? order by REL_SN", String.class, p)
                + "|" + jdbc.queryForList("select CNSTRT_SN || ':' || CNSTRT_TYPE_CD from TB_TBL_CNSTRT where PRJ_SN = ? order by CNSTRT_SN", String.class, p)
                + "|" + jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Long.class, p)
                + "|" + jdbc.queryForList("select COL_NM || ':' || coalesce(RFRNC_TBL_NM, '-') || ':' || coalesce(cast(UNQ_YN as varchar(5)), '-') from TB_COL where PRJ_SN = ? order by COL_SN", String.class, p);
    }

    private void unique(Fx x, long table, String name, String... columns) {
        tx.executeWithoutResult(s -> {
            TableConstraint c = TableConstraint.builder().projectId(x.f().user.getProjectId()).tableInfo(entityManager.find(TableInfo.class, table)).constraintName(name)
                    .constraintType(TableConstraint.Type.UNIQUE).isApproval(false).build();
            for (int i = 0; i < columns.length; i++) {
                c.getColumns().add(TableConstraintColumn.builder().tableConstraint(c).column(entityManager.find(ColumnInfo.class, column(table, columns[i]))).columnOrder(i + 1).build());
            }
            entityManager.persist(c);
        });
    }

    // ------------------------------------------------------------------ 논리 관계 ↔ 물리 FK

    @Test
    @DisplayName("관계 ↔ FK : 단일·복합·자기 참조 일치, 짝 없음 비교 불가, 부모 미매핑 UNMAPPED, 순서 바꾸면 FK_ORDER, 대상 바꾸면 FK_TARGET, FK 지우면 FK_MISSING — 진단은 아무것도 바꾸지 않는다")
    void relationships() {
        Fx x = fixture("rel");
        String before = snapshot(x);
        List<Row> rows = rows(x, CheckGroup.RELATIONSHIP);
        assertThat(rows).allSatisfy(r -> assertThat(r.type()).isEqualTo(CheckType.RELATIONSHIP));
        assertThat(rows).hasSize(6);
        Row single = relationship(rows, x.r().get("주문함"));
        assertThat(single.status()).as(single.reason()).isEqualTo(CheckStatus.MATCH);
        assertThat(single.structure().code()).isEqualTo("FK_MATCH");
        assertThat(single.entityId()).isEqualTo(x.e().get("주문"));
        assertThat(single.structure().columns()).singleElement().satisfies(c -> {
            assertThat(c.expectedColumnName()).isEqualTo("ORD_CUST_NO");
            assertThat(c.physicalColumnName()).isEqualTo("ORD_CUST_NO");
            assertThat(c.physicalParentColumnName()).isEqualTo("CUST_NO");
            assertThat(c.parentAttributeName()).isEqualTo("고객 번호");
        });
        Row composite = relationship(rows, x.r().get("소속"));
        assertThat(composite.status()).as(composite.reason()).isEqualTo(CheckStatus.MATCH);
        assertThat(composite.structure().columns()).extracting(c -> c.physicalColumnName() + ">" + c.physicalParentColumnName()).containsExactly("FA1>K1", "FA2>K2");
        assertThat(relationship(rows, x.r().get("상위")).status()).isEqualTo(CheckStatus.MATCH);
        Row noPairs = relationship(rows, x.r().get("결제함"));
        assertThat(noPairs.status()).isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(noPairs.structure().code()).isEqualTo("PAIRS_MISSING");
        Row parentUnmapped = relationship(rows, x.r().get("배송함"));
        assertThat(parentUnmapped.status()).isEqualTo(CheckStatus.UNMAPPED);
        assertThat(parentUnmapped.structure().code()).isEqualTo("ENTITY_UNMAPPED");
        assertThat(relationship(rows, x.r().get("담당")).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(snapshot(x)).as("진단은 읽기 전용").isEqualTo(before);

        // 복합 FK 순서를 바꾼다
        long teamRel = relationOf(x.table("팀"), x.table("부서"));
        jdbc.update("update TB_TBL_REL_COL set COL_ORD = 3 where REL_SN = ? and COL_ORD = 1", teamRel);
        jdbc.update("update TB_TBL_REL_COL set COL_ORD = 1 where REL_SN = ? and COL_ORD = 2", teamRel);
        jdbc.update("update TB_TBL_REL_COL set COL_ORD = 2 where REL_SN = ? and COL_ORD = 3", teamRel);
        Row order = relationship(rows(x, CheckGroup.RELATIONSHIP), x.r().get("소속"));
        assertThat(order.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(order.structure().code()).isEqualTo("FK_ORDER");
        assertThat(order.structure().columns()).extracting(c -> c.expectedColumnName() + "/" + c.physicalColumnName()).containsExactly("FA1/FA2", "FA2/FA1");

        // 담당 FK 의 대상을 고객 테이블로 바꾼다 (같은 자식 컬럼, 다른 부모)
        long branchRel = relationOf(x.table("배송"), x.table("지점"));
        jdbc.update("update TB_TBL_REL set TRGT_TBL_SN = ? where REL_SN = ?", x.table("고객"), branchRel);
        jdbc.update("update TB_TBL_REL_COL set TRGT_COL_SN = ? where REL_SN = ?", column(x.table("고객"), "CUST_NO"), branchRel);
        Row target = relationship(rows(x, CheckGroup.RELATIONSHIP), x.r().get("담당"));
        assertThat(target.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(target.structure().code()).isEqualTo("FK_TARGET");

        // 주문함 FK 를 지운다 (참조 테이블명도)
        long orderRel = relationOf(x.table("주문"), x.table("고객"));
        jdbc.update("update TB_COL set RFRNC_TBL_NM = null where COL_SN = ?", column(x.table("주문"), "ORD_CUST_NO"));
        jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", orderRel);
        jdbc.update("delete from TB_TBL_REL where REL_SN = ?", orderRel);
        Row missing = relationship(rows(x, CheckGroup.RELATIONSHIP), x.r().get("주문함"));
        assertThat(missing.status()).isEqualTo(CheckStatus.UNMAPPED);
        assertThat(missing.structure().code()).isEqualTo("FK_MISSING");

        // 속성 매핑을 지우면 매핑 부족
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", x.a().get("MGR_NO"));
        Row attr = relationship(rows(x, CheckGroup.RELATIONSHIP), x.r().get("상위"));
        assertThat(attr.status()).isEqualTo(CheckStatus.UNMAPPED);
        assertThat(attr.structure().code()).isEqualTo("ATTRIBUTE_UNMAPPED");
        // 엔터티 매핑을 지우면 엔터티 미매핑
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN in (select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ?)", x.e().get("팀"));
        jdbc.update("delete from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", x.e().get("팀"));
        assertThat(relationship(rows(x, CheckGroup.RELATIONSHIP), x.r().get("소속")).structure().code()).isEqualTo("ENTITY_UNMAPPED");
    }

    @Test
    @DisplayName("역방향 FK : 논리 관계가 없는 FK 는 물리 전용(UNMAPPED), 대응한 FK 는 다시 보이지 않는다(중복 없음), 부모 테이블이 모델 밖이면 비교 불가")
    void physicalForeignKeys() {
        Fx x = fixture("phys-fk");
        // 주문 → 결제 (논리 관계 없음) : 참조 테이블명으로 FK 를 만든다
        long orderProduct = column(x.table("주문"), "ORD_PRDT_NO");
        jdbc.update("update TB_COL set RFRNC_TBL_NM = ? where COL_SN = ?", jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.table("결제")), orderProduct);
        tx.executeWithoutResult(s -> physicalModelService.syncReferenceRelations(x.f().user.getProjectId(), List.of(x.table("주문"))));
        long extra = relationOf(x.table("주문"), x.table("결제"));

        List<Row> all = rows(x, CheckGroup.ALL);
        List<Row> physical = rows(x, CheckGroup.PHYSICAL_ONLY);
        assertThat(physical).filteredOn(r -> r.type() == CheckType.PHYSICAL_FK).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(CheckStatus.UNMAPPED);
            assertThat(r.structure().code()).isEqualTo("PHYSICAL_ONLY_FK");
            assertThat(r.structure().physicalId()).isEqualTo(extra);
            assertThat(r.structure().columns()).singleElement().satisfies(c -> {
                assertThat(c.physicalColumnName()).isEqualTo("ORD_PRDT_NO");
                assertThat(c.logicalAttributeName()).isEqualTo("주문 상품 번호");
            });
        });
        // 같은 FK 가 관계 행과 물리 전용 행에 두 번 나오지 않는다
        Map<Long, Long> perFk = new java.util.HashMap<>();
        all.stream().filter(r -> r.structure() != null && r.structure().physicalId() != null && (r.type() == CheckType.RELATIONSHIP || r.type() == CheckType.PHYSICAL_FK))
                .forEach(r -> perFk.merge(r.structure().physicalId(), 1L, Long::sum));
        assertThat(perFk.values()).allSatisfy(n -> assertThat(n).isOne());

        // 부모 테이블이 이 모델 밖 (매핑 해제) : 비교 불가
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN in (select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ?)", x.e().get("결제"));
        jdbc.update("delete from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", x.e().get("결제"));
        assertThat(rows(x, CheckGroup.PHYSICAL_ONLY)).filteredOn(r -> Objects.equals(r.structure().physicalId(), extra)).singleElement()
                .satisfies(r -> assertThat(r.structure().code()).isEqualTo("FK_PARENT_UNMAPPED"));
    }

    // ------------------------------------------------------------------ 보조 식별자 ↔ UNIQUE

    @Test
    @DisplayName("보조 식별자 ↔ UNIQUE : 컬럼 단위 고유값 일치, 복합 일치, 순서 불일치, 컬럼 누락·추가, UNIQUE 없음, 물리 전용 UNIQUE — 승인된 객체도 진단한다")
    void alternateKeys() {
        Fx x = fixture("ak");
        long cust = x.table("고객");
        long order = x.table("주문");
        long dept = x.table("부서");
        logical.saveIdentifier(x.f().user, new LogicalModelDto.Identifier(null, x.e().get("고객"), "고객명 식별자", false, List.of(x.a().get("CUST_NM")), null));
        logical.saveIdentifier(x.f().user, new LogicalModelDto.Identifier(null, x.e().get("고객"), "고객 메일 식별자", false, List.of(x.a().get("CUST_EML"), x.a().get("CUST_NM")), null));
        logical.saveIdentifier(x.f().user, new LogicalModelDto.Identifier(null, x.e().get("주문"), "주문 조합", false, List.of(x.a().get("ORD_CUST_NO"), x.a().get("ORD_PRDT_NO")), null));
        logical.saveIdentifier(x.f().user, new LogicalModelDto.Identifier(null, x.e().get("부서"), "부서 대체", false, List.of(x.a().get("K1")), null));
        logical.saveIdentifier(x.f().user, new LogicalModelDto.Identifier(null, x.e().get("결제"), "결제 고객", false, List.of(x.a().get("STLM_CUST_NO")), null));
        jdbc.update("update TB_COL set UNQ_YN = true where COL_SN = ?", column(cust, "CUST_NM")); // 고객명 식별자 = 컬럼 단위 고유값
        unique(x, cust, "UX_CUST_EML", "CUST_NM", "CUST_EML"); // 순서 반대
        unique(x, order, "UX_ORD_CUST", "ORD_CUST_NO"); // 주문 조합의 일부만
        unique(x, dept, "UX_DEPT_K", "K1", "K2"); // 부서 대체(K1) 에 K2 가 더 있다
        long payment = x.table("결제");
        unique(x, payment, "UX_STLM_NO", "STLM_NO"); // 보조 식별자 없음 → 물리 전용 (결제 고객은 UNIQUE 없음)
        jdbc.update("update TB_TBL set APRV_YN = true where PRJ_SN = ?", x.f().user.getProjectId());
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = true where LGCL_MDL_SN = ?", x.f().modelId);

        List<Row> rows = rows(x, CheckGroup.ALTERNATE_KEY);
        Map<String, Row> byName = new java.util.HashMap<>();
        rows.forEach(r -> byName.put(r.structure().logicalName(), r));
        assertThat(byName.get("고객명 식별자")).satisfies(r -> {
            assertThat(r.status()).isEqualTo(CheckStatus.MATCH);
            assertThat(r.structure().physicalName()).isEqualTo("UK_CUST_CUST_NM");
        });
        assertThat(byName.get("고객 메일 식별자")).satisfies(r -> {
            assertThat(r.status()).isEqualTo(CheckStatus.MISMATCH);
            assertThat(r.structure().code()).isEqualTo("UNIQUE_ORDER");
            assertThat(r.structure().columns()).extracting(c -> c.expectedColumnName() + "/" + c.physicalColumnName()).containsExactly("CUST_EML/CUST_NM", "CUST_NM/CUST_EML");
        });
        assertThat(byName.get("주문 조합").structure().code()).isEqualTo("UNIQUE_COLUMNS_MISSING");
        assertThat(byName.get("부서 대체").structure().code()).isEqualTo("UNIQUE_COLUMNS_EXTRA");
        assertThat(byName.get("결제 고객")).satisfies(r -> {
            assertThat(r.status()).isEqualTo(CheckStatus.UNMAPPED);
            assertThat(r.structure().code()).isEqualTo("UNIQUE_MISSING");
        });
        assertThat(rows(x, CheckGroup.PHYSICAL_ONLY)).filteredOn(r -> r.type() == CheckType.PHYSICAL_UNIQUE).singleElement().satisfies(r -> {
            assertThat(r.structure().physicalName()).isEqualTo("UX_STLM_NO");
            assertThat(r.status()).isEqualTo(CheckStatus.UNMAPPED);
            assertThat(r.structure().code()).isEqualTo("PHYSICAL_ONLY_UNIQUE");
        });

        // 요약 : 구조 진단은 따로 세고, 예전 네 건수는 그대로
        Summary summary = quality.selectSummary(x.f().user, x.f().modelId);
        assertThat(summary.structure().alternateKey().total()).isEqualTo(5);
        assertThat(summary.structure().alternateKey().match()).isOne();
        assertThat(summary.structure().alternateKey().mismatch()).isEqualTo(3);
        assertThat(summary.structure().physicalUniques()).isOne();
        List<Row> legacy = quality.selectRows(x.f().user, new Search(x.f().modelId, null, null, null, null, null), PageRequest.of(0, 100)).getContent();
        assertThat(legacy).noneMatch(r -> r.type().structure());
        assertThat(summary.match() + summary.mismatch() + summary.notComparable() + summary.unmapped())
                .isEqualTo(quality.selectRows(x.f().user, new Search(x.f().modelId, null, null, null, null, null), PageRequest.of(0, 1)).getTotalElements());
    }

    // ------------------------------------------------------------------ 상속 · 격리 · 검색

    @Test
    @DisplayName("상속 B형 : 서브타입 → 슈퍼타입 FK 는 물리 전용으로 보이지 않고 [상속 구조] 진단(INHERITANCE)이 본다. 상속 PK 매핑을 지워도 같은 FK 는 물리 전용이 아니다")
    void inheritance() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "msq-inh");
        long party = f.entity("당사자");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", party);
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long org = f.entity("조직");
        long person = f.entity("사람");
        long corp = f.entity("기업");
        long indv = f.entity("개인");
        for (long id : new long[]{org, person, corp, indv}) jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, party, "당사자 구분", null, true, false, null,
                List.of(new LogicalSubtypeDto.MemberInput(org, null), new LogicalSubtypeDto.MemberInput(person, null)), null));
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, org, "조직 구분", null, true, false, null,
                List.of(new LogicalSubtypeDto.MemberInput(corp, null), new LogicalSubtypeDto.MemberInput(indv, null)), null)); // 3단계
        List<Long> chosen = List.of(party, org, person, corp);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));

        List<Row> all = quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null, CheckGroup.ALL), PageRequest.of(0, 2000)).getContent();
        assertThat(all).filteredOn(r -> r.type() == CheckType.PHYSICAL_FK).as("상속 FK 는 물리 전용이 아니다").isEmpty();
        assertThat(all).filteredOn(r -> r.type() == CheckType.INHERITANCE && r.entityId() == corp).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));
        List<Row> inheritanceGroup = quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null, CheckGroup.INHERITANCE), PageRequest.of(0, 100)).getContent();
        assertThat(inheritanceGroup).isNotEmpty().allSatisfy(r -> assertThat(r.type()).isEqualTo(CheckType.INHERITANCE));

        jdbc.update("delete from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = (select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?)", corp);
        List<Row> after = quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null, CheckGroup.ALL), PageRequest.of(0, 2000)).getContent();
        assertThat(after).filteredOn(r -> r.type() == CheckType.PHYSICAL_FK).isEmpty();
        assertThat(after).filteredOn(r -> r.type() == CheckType.INHERITANCE && r.entityId() == corp && r.attributeId() == null).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(CheckStatus.UNMAPPED));
    }

    @Test
    @DisplayName("프로젝트 격리·검색 : 같은 모양의 다른 프로젝트 모델은 섞이지 않고 다른 프로젝트 모델은 조회할 수 없다. 엔터티·이름 조건은 구조 행에도 적용")
    void isolationAndSearch() {
        Fx a = fixture("iso-a");
        Fx b = fixture("iso-b");
        List<Row> rowsA = rows(a, CheckGroup.ALL);
        assertThat(rowsA).filteredOn(r -> r.type().structure()).allSatisfy(r -> assertThat(r.modelId()).isEqualTo(a.f().modelId));
        assertThat(rowsA).filteredOn(r -> r.type() == CheckType.RELATIONSHIP).extracting(r -> r.structure().logicalId())
                .doesNotContainAnyElementsOf(b.r().values());
        assertThatThrownBy(() -> quality.selectRows(a.f().user, new Search(b.f().modelId, null, null, null, null, null, CheckGroup.ALL), PageRequest.of(0, 10)))
                .isInstanceOf(CustomException.class);
        // 엔터티 조건 · 속성명 · 컬럼명 검색
        List<Row> team = quality.selectRows(a.f().user, new Search(a.f().modelId, a.e().get("팀"), null, null, null, null, CheckGroup.RELATIONSHIP), PageRequest.of(0, 10)).getContent();
        assertThat(team).singleElement().satisfies(r -> assertThat(r.structure().logicalId()).isEqualTo(a.r().get("소속")));
        List<Row> byAttribute = quality.selectRows(a.f().user, new Search(a.f().modelId, null, null, null, "attributeName", "상위 직원", CheckGroup.RELATIONSHIP), PageRequest.of(0, 10)).getContent();
        assertThat(byAttribute).singleElement().satisfies(r -> assertThat(r.structure().logicalId()).isEqualTo(a.r().get("상위")));
        List<Row> byColumn = quality.selectRows(a.f().user, new Search(a.f().modelId, null, null, null, "columnName", "FA2", CheckGroup.RELATIONSHIP), PageRequest.of(0, 10)).getContent();
        assertThat(byColumn).singleElement().satisfies(r -> assertThat(r.structure().logicalId()).isEqualTo(a.r().get("소속")));
        // 유형 조건만으로도 구조 행을 고를 수 있다
        assertThat(quality.selectRows(a.f().user, new Search(a.f().modelId, null, CheckType.RELATIONSHIP, CheckStatus.MATCH, null, null), PageRequest.of(0, 10)).getContent())
                .isNotEmpty().allSatisfy(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));
        // 같은 이름의 다른 객체 : 같은 이름 테이블이 다른 스키마에 있어도 매핑으로만 대응한다 (이름으로 짝 짓지 않음)
        List<String> names = new ArrayList<>(rowsA.stream().filter(r -> r.type() == CheckType.RELATIONSHIP).map(r -> r.structure().code()).toList());
        assertThat(names).contains("FK_MATCH", "PAIRS_MISSING", "ENTITY_UNMAPPED");
    }
}
