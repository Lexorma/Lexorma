package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.controller.ModelMappingRestController;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Code;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Item;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Plan;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Representation;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Request;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Result;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.ForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-FK-01 기존 물리 테이블 누락 FK 보정 (docs/completed/physical-fk-correction-m7.md). H2 / PostgreSQL 같은 기대값.
 * 모델은 논리 → 물리 생성으로 만들고(단일 FK = 참조 테이블명, 복합·자기 참조 = 사용자 관계), FK 누락은 JDBC 로 기존 FK 를 지워 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ForeignKeyCorrectionIntegrationTest {

    /** 원자성 확인용 : n 번째 FK 생성에서 실패시킨다 (0 = 끔) */
    static final AtomicInteger FAIL_AT = new AtomicInteger();
    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class Hooks {
        @Bean
        static BeanPostProcessor failingForeignKey() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof PhysicalModelService)) return bean;
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), ClassUtils.getAllInterfaces(bean), (proxy, method, args) -> {
                        if (method.getName().equals("createGeneratedForeignKey") && FAIL_AT.get() > 0 && CALLS.incrementAndGet() == FAIL_AT.get()) {
                            throw new IllegalStateException("테스트 : FK 생성 실패");
                        }
                        try {
                            return method.invoke(bean, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
                }
            };
        }
    }

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ForeignKeyCorrectionService correction;
    @Autowired ModelMappingQualityService quality;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired ColumnManageService columns;
    @Autowired TableDesignService tables;
    @Autowired ModelMappingRestController controller;
    @Autowired ProjectRepository projectRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        FAIL_AT.set(0);
        CALLS.set(0);
        SecurityTestSupport.clear();
    }

    /** 관계 : 고객 → 주문(단일), 부서(복합 키) → 팀(복합), 직원 → 직원(자기 참조), 지점(복합 키) → 배송(복합) */
    private record Fx(GenerationFixture f, Map<String, Long> e, Map<String, Long> a, Map<String, Long> r) {
        long table(String entity) {
            return f.table(e.get(entity));
        }
    }

    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "fkc-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        Map<String, Long> e = new HashMap<>();
        Map<String, Long> a = new HashMap<>();
        Map<String, Long> r = new HashMap<>();
        e.put("고객", f.entity("고객"));
        a.put("CUST_NO", f.attr(e.get("고객"), "고객 번호", true, "CUST_NO"));
        f.pk(e.get("고객"), a.get("CUST_NO"));
        e.put("주문", f.entity("주문"));
        a.put("ORD_NO", f.attr(e.get("주문"), "주문 번호", true, "ORD_NO"));
        a.put("ORD_CUST_NO", f.attr(e.get("주문"), "주문 고객 번호", false, "ORD_CUST_NO"));
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
        e.put("지점", f.entity("지점"));
        a.put("B1", f.attr(e.get("지점"), "지점 키1", true, "B1"));
        a.put("B2", f.attr(e.get("지점"), "지점 키2", true, "B2"));
        f.pk(e.get("지점"), a.get("B1"), a.get("B2"));
        e.put("배송", f.entity("배송"));
        a.put("DLVR_NO", f.attr(e.get("배송"), "배송 번호", true, "DLVR_NO"));
        a.put("DB1", f.attr(e.get("배송"), "배송 지점 키1", false, "DB1"));
        a.put("DB2", f.attr(e.get("배송"), "배송 지점 키2", false, "DB2"));
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
        LogicalModelDto.Relationship branch = f.rel("담당", e.get("지점"), e.get("배송"), false, 0);
        f.pairs(branch, new long[]{a.get("B1"), a.get("DB1")}, new long[]{a.get("B2"), a.get("DB2")});
        r.put("담당", branch.id());

        List<Long> chosen = List.of(e.get("고객"), e.get("주문"), e.get("부서"), e.get("팀"), e.get("직원"), e.get("지점"), e.get("배송"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        return new Fx(f, e, a, r);
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private List<Long> relations(long child, long parent) {
        return jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK' order by REL_SN", Long.class, child, parent);
    }

    /** 생성기가 만든 FK 를 지운다 (단일이면 참조 테이블명도) — 기존 테이블의 FK 누락 */
    private void dropForeignKey(Fx x, String child, String parent, String refColumn) {
        if (refColumn != null) jdbc.update("update TB_COL set RFRNC_TBL_NM = null where COL_SN = ?", column(x.table(child), refColumn));
        for (Long rel : relations(x.table(child), x.table(parent))) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", rel);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", rel);
        }
    }

    private Fx missing(String name) {
        Fx x = fixture(name);
        dropForeignKey(x, "주문", "고객", "ORD_CUST_NO");
        dropForeignKey(x, "팀", "부서", null);
        dropForeignKey(x, "직원", "직원", null);
        dropForeignKey(x, "배송", "지점", null);
        return x;
    }

    private Row relationship(Fx x, long id) {
        return quality.selectRows(x.f().user, new Search(x.f().modelId, null, null, null, null, null, CheckGroup.RELATIONSHIP), PageRequest.of(0, 100)).getContent().stream()
                .filter(r -> r.type() == CheckType.RELATIONSHIP && Objects.equals(r.structure().logicalId(), id)).findFirst().orElseThrow();
    }

    private Plan preview(Fx x, Long... ids) {
        return correction.preview(x.f().user, new Request(x.f().modelId, List.of(ids), null));
    }

    private Result apply(Fx x, Plan plan) {
        return correction.apply(x.f().user, new Request(plan.modelId(), plan.items().stream().map(Item::relationshipId).toList(), plan.planToken()));
    }

    private String physicalSnapshot(Fx x) {
        long p = x.f().user.getProjectId();
        return jdbc.queryForList("select TBL_SN || ':' || TBL_NM || ':' || SCHM_SN || ':' || cast(APRV_YN as varchar(5)) || ':' || cast(MDFCN_DT as varchar(40)) from TB_TBL where PRJ_SN = ? order by TBL_SN", String.class, p)
                + "|" + jdbc.queryForList("select COL_SN || ':' || COL_NM || ':' || DATA_TYPE_NM || ':' || coalesce(cast(MAX_LEN as varchar(10)), '-') || ':' || cast(PK_YN as varchar(5)) || ':'"
                + " || coalesce(RFRNC_TBL_NM, '-') || ':' || cast(APRV_YN as varchar(5)) || ':' || cast(MDFCN_DT as varchar(40)) from TB_COL where PRJ_SN = ? order by COL_SN", String.class, p)
                + "|" + jdbc.queryForList("select CNSTRT_SN || ':' || CNSTRT_TYPE_CD from TB_TBL_CNSTRT where PRJ_SN = ? order by CNSTRT_SN", String.class, p)
                + "|" + jdbc.queryForList("select IDX_SN || ':' || IDX_NM from TB_TBL_IDX where PRJ_SN = ? order by IDX_SN", String.class, p)
                + "|" + jdbc.queryForList("select ENTT_TBL_MPNG_SN || ':' || LGCL_ENTT_SN || ':' || TBL_SN from TB_ENTT_TBL_MPNG where TBL_SN in (select TBL_SN from TB_TBL where PRJ_SN = ?) order by 1", String.class, p)
                + "|" + jdbc.queryForList("select LGCL_ATRB_SN || ':' || COL_SN from TB_ATRB_COL_MPNG where COL_SN in (select COL_SN from TB_COL where PRJ_SN = ?) order by 1", String.class, p);
    }

    private List<String> relationSnapshot(Fx x) {
        return jdbc.queryForList("select r.REL_SN || ':' || r.ORGNL_TBL_SN || '>' || r.TRGT_TBL_SN || ':' || coalesce(r.REL_NM, '-') || ':' || r.REL_SRC_CD || ':' || cast(r.APRV_YN as varchar(5))"
                + " from TB_TBL_REL r where r.PRJ_SN = ? order by r.REL_SN", String.class, x.f().user.getProjectId());
    }

    private static ErrorCode code(Runnable work) {
        try {
            work.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        return null;
    }

    // ------------------------------------------------------------------ 지원 유형 : 복합 · 자기 참조

    @Test
    @DisplayName("FK-03·04·19·20 : 누락된 복합 FK·자기 참조 FK 를 미리보기 → 적용으로 만들고, 진단이 실제 MATCH 가 된다. 미승인 USER 관계, 짝 순서 = FK 컬럼 순서, 직전 버전 FKFIX-, 다시 보정하면 NOT_MISSING")
    void compositeAndSelfReference() {
        Fx x = missing("ok");
        long team = x.r().get("소속");
        long self = x.r().get("상위");
        assertThat(relationship(x, team).structure().code()).isEqualTo("FK_MISSING");
        assertThat(relationship(x, self).structure().code()).isEqualTo("FK_MISSING");
        int versions = x.f().versions();
        String physical = physicalSnapshot(x);
        List<String> relationsBefore = relationSnapshot(x);

        Plan plan = preview(x, team, self);
        assertThat(plan.applicable()).as(String.valueOf(plan.items())).isTrue();
        assertThat(plan.total()).isEqualTo(2);
        assertThat(plan.correctable()).isEqualTo(2);
        Item composite = plan.items().get(0);
        assertThat(composite.relationshipId()).isEqualTo(team);
        assertThat(composite.representation()).isEqualTo(Representation.USER_RELATION);
        assertThat(composite.approved()).isFalse();
        assertThat(composite.relationName()).isEqualTo("FK_" + jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.table("팀")) + "_FA1");
        assertThat(composite.pairs()).extracting(p -> p.order() + ":" + p.childColumnName() + ">" + p.parentColumnName()).containsExactly("1:FA1>K1", "2:FA2>K2");
        Item selfItem = plan.items().get(1);
        assertThat(selfItem.selfReference()).isTrue();
        assertThat(selfItem.representation()).isEqualTo(Representation.USER_RELATION);
        // 미리보기는 아무것도 바꾸지 않고 같은 계획이면 같은 토큰
        assertThat(preview(x, team, self).planToken()).isEqualTo(plan.planToken());
        assertThat(preview(x, self, team).planToken()).as("순서와 무관").isEqualTo(plan.planToken());
        assertThat(x.f().versions()).isEqualTo(versions);
        assertThat(relationSnapshot(x)).isEqualTo(relationsBefore);

        Result result = apply(x, plan);
        assertThat(result.selected()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.versionName()).startsWith("FKFIX-");
        assertThat(x.f().versions()).isEqualTo(versions + 1);
        assertThat(relationship(x, team).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(relationship(x, self).status()).isEqualTo(CheckStatus.MATCH);
        // 만든 관계 : 미승인 USER, 구성 순서
        for (Long id : result.relationIds()) {
            assertThat(jdbc.queryForObject("select REL_SRC_CD || ':' || cast(APRV_YN as varchar(5)) from TB_TBL_REL where REL_SN = ?", String.class, id)).isEqualToIgnoringCase("USER:false");
        }
        long teamRel = relations(x.table("팀"), x.table("부서")).get(0);
        assertThat(jdbc.queryForList("select c.COL_NM from TB_TBL_REL_COL rc join TB_COL c on c.COL_SN = rc.ORGNL_COL_SN where rc.REL_SN = ? order by rc.COL_ORD", String.class, teamRel))
                .containsExactly("FA1", "FA2");
        // 기존 테이블·컬럼·제약조건·인덱스·매핑은 그대로, 기존 관계도 그대로 (새 관계만 늘었다)
        assertThat(physicalSnapshot(x)).isEqualTo(physical);
        assertThat(relationSnapshot(x)).containsAll(relationsBefore).hasSize(relationsBefore.size() + 2);
        // 변경 이력 그룹 = 버전명
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ?", Integer.class, x.f().user.getProjectId(), result.versionName())).isPositive();

        // FK-20 : 다시 보정하면 이미 일치 — 중복 생성 없음
        Plan again = preview(x, team);
        assertThat(again.applicable()).isFalse();
        assertThat(again.items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.NOT_MISSING);
        assertThat(code(() -> apply(x, again))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relations(x.table("팀"), x.table("부서"))).hasSize(1);
    }

    @Test
    @DisplayName("D05·D08 승인 : 만든 관계는 미승인으로 남는다 — 관계 동기화·컬럼 등록·테이블 수정 뒤에도. 승인 권한자의 승인 절차로만 승인된다. 생성기의 단일 FK(참조 테이블명) 관계는 처음부터 승인 상태다")
    void approvalPersists() {
        Fx x = fixture("aprv");
        // 22-1 : 생성기가 만든 단일 FK 는 REF_COLUMN 관계이고, 테이블·컬럼이 미승인이어도 관계는 승인 상태로 만들어진다 (보류 근거)
        long orderRel = relations(x.table("주문"), x.table("고객")).get(0);
        assertThat(jdbc.queryForObject("select REL_SRC_CD || ':' || cast(APRV_YN as varchar(5)) from TB_TBL_REL where REL_SN = ?", String.class, orderRel)).isEqualToIgnoringCase("REF_COLUMN:true");
        assertThat(jdbc.queryForObject("select cast(APRV_YN as varchar(5)) from TB_COL where COL_SN = ?", String.class, column(x.table("주문"), "ORD_CUST_NO"))).isEqualToIgnoringCase("false");
        // 22-2·5 : 같은 관계를 다시 동기화해도, 참조 테이블명을 지웠다 다시 적으면 새로 만들어지며 다시 승인 상태
        tx.executeWithoutResult(s -> physicalModelService.syncReferenceRelations(x.f().user.getProjectId(), List.of(x.table("주문"), x.table("고객"))));
        assertThat(jdbc.queryForObject("select cast(APRV_YN as varchar(5)) from TB_TBL_REL where REL_SN = ?", String.class, orderRel)).isEqualToIgnoringCase("true");

        dropForeignKey(x, "팀", "부서", null);
        Plan plan = preview(x, x.r().get("소속"));
        Result result = apply(x, plan);
        long created = result.relationIds().get(0);
        long project = x.f().user.getProjectId();
        // 관계 동기화 (참조 테이블 동기화 대상 테이블)
        tx.executeWithoutResult(s -> physicalModelService.syncReferenceRelations(project, List.of(x.table("팀"), x.table("부서"))));
        // 자식 테이블에 컬럼 등록 (커밋 직전 동기화)
        ColumnInfoDto dto = new ColumnInfoDto();
        dto.setTableInfoId(x.table("팀"));
        dto.setTermId(x.f().term("TEAM_EXTRA", "VARCHAR", 20));
        dto.setColumnName("TEAM_EXTRA");
        dto.setColumnDesc("추가");
        dto.setDataType("VARCHAR");
        dto.setMaxLength(20);
        for (java.util.function.Consumer<String> flag : List.<java.util.function.Consumer<String>>of(dto::setIsPk, dto::setIsMasterData, dto::setIsRequired, dto::setIsSensitive,
                dto::setIsUnique, dto::setIsIndex, dto::setIsEncrypted)) flag.accept("N");
        dto.setIsNullable("Y");
        columns.insertColumn(x.f().user, dto);
        // 자식 테이블 설명 수정
        TableInfoDto table = new TableInfoDto();
        table.setId(x.table("팀"));
        table.setTableName(jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.table("팀")));
        table.setTableDescription("설명 수정");
        table.setIsMasterTable("N");
        table.setBaseModifiedAt(jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", java.sql.Timestamp.class, x.table("팀")).toLocalDateTime());
        tables.updateTable(table, x.f().user);
        assertThat(jdbc.queryForObject("select cast(APRV_YN as varchar(5)) from TB_TBL_REL where REL_SN = ?", String.class, created)).isEqualToIgnoringCase("false");
        // 승인 절차 : 승인 권한자가 승인하면 승인된다 (보정이 승인을 우회하지 않는다)
        physicalModelService.updateApproval(x.f().user, PhysicalModelService.Kind.RELATION, created, true);
        assertThat(jdbc.queryForObject("select cast(APRV_YN as varchar(5)) from TB_TBL_REL where REL_SN = ?", String.class, created)).isEqualToIgnoringCase("true");
    }

    // ------------------------------------------------------------------ 보류·불가 유형

    @Test
    @DisplayName("FK-02 : 자기 참조가 아닌 단일 FK 누락은 정책 제한으로 보류 (SINGLE_FK_HELD) — 진단은 FK_MISSING 그대로, 직접 적용 요청도 서버가 거절하고 아무것도 바꾸지 않는다")
    void singleForeignKeyHeld() {
        Fx x = missing("single");
        long order = x.r().get("주문함");
        Row row = relationship(x, order);
        assertThat(row.structure().code()).isEqualTo("FK_MISSING");
        Plan plan = preview(x, order);
        assertThat(plan.applicable()).isFalse();
        Item item = plan.items().get(0);
        assertThat(item.representation()).isEqualTo(Representation.REF_COLUMN);
        assertThat(item.blockers()).extracting(b -> b.code()).containsExactly(Code.SINGLE_FK_HELD);
        List<String> before = relationSnapshot(x);
        int versions = x.f().versions();
        assertThat(code(() -> apply(x, plan))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relationSnapshot(x)).isEqualTo(before);
        assertThat(x.f().versions()).isEqualTo(versions);
        assertThat(relationship(x, order).structure().code()).isEqualTo("FK_MISSING");
        // 섞어 고르면 전체를 적용하지 않는다 (부분 적용 없음)
        Plan mixed = preview(x, order, x.r().get("소속"));
        assertThat(mixed.applicable()).isFalse();
        assertThat(mixed.correctable()).isEqualTo(1);
        assertThat(code(() -> apply(x, mixed))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relationSnapshot(x)).isEqualTo(before);
    }

    @Test
    @DisplayName("FK-01·05·06·07 : 일치·불일치(순서·대상·구성) 관계는 NOT_MISSING — 기존 FK 를 고치거나 지우지 않는다")
    void existingForeignKeysKept() {
        Fx x = fixture("kept");
        long teamRel = relations(x.table("팀"), x.table("부서")).get(0);
        // 순서 바꾸기 (FK_ORDER)
        jdbc.update("update TB_TBL_REL_COL set COL_ORD = 3 where REL_SN = ? and COL_ORD = 1", teamRel);
        jdbc.update("update TB_TBL_REL_COL set COL_ORD = 1 where REL_SN = ? and COL_ORD = 2", teamRel);
        jdbc.update("update TB_TBL_REL_COL set COL_ORD = 2 where REL_SN = ? and COL_ORD = 3", teamRel);
        // 대상 바꾸기 (FK_TARGET) : 배송 → 지점 FK 를 다른 복합 키 테이블(부서)로
        long branchRel = relations(x.table("배송"), x.table("지점")).get(0);
        jdbc.update("update TB_TBL_REL set TRGT_TBL_SN = ? where REL_SN = ?", x.table("부서"), branchRel);
        jdbc.update("update TB_TBL_REL_COL set TRGT_COL_SN = ? where REL_SN = ? and COL_ORD = 1", column(x.table("부서"), "K1"), branchRel);
        jdbc.update("update TB_TBL_REL_COL set TRGT_COL_SN = ? where REL_SN = ? and COL_ORD = 2", column(x.table("부서"), "K2"), branchRel);
        // 구성 바꾸기 (FK_COLUMNS) : 직원 자기 참조 FK 의 자식 컬럼을 PK 로
        long selfRel = relations(x.table("직원"), x.table("직원")).get(0);
        jdbc.update("update TB_TBL_REL_COL set ORGNL_COL_SN = ? where REL_SN = ?", column(x.table("직원"), "EMP_NO"), selfRel);
        assertThat(relationship(x, x.r().get("주문함")).structure().code()).isEqualTo("FK_MATCH");
        assertThat(relationship(x, x.r().get("소속")).structure().code()).isEqualTo("FK_ORDER");
        assertThat(relationship(x, x.r().get("담당")).structure().code()).isEqualTo("FK_TARGET");
        assertThat(relationship(x, x.r().get("상위")).structure().code()).isEqualTo("FK_COLUMNS");
        List<String> before = relationSnapshot(x);
        List<String> columnsBefore = jdbc.queryForList("select REL_SN || ':' || COL_ORD || ':' || ORGNL_COL_SN || '>' || TRGT_COL_SN from TB_TBL_REL_COL where REL_SN in (select REL_SN from TB_TBL_REL where PRJ_SN = ?) order by 1", String.class, x.f().user.getProjectId());
        Plan plan = preview(x, x.r().get("주문함"), x.r().get("소속"), x.r().get("담당"), x.r().get("상위"));
        assertThat(plan.correctable()).isZero();
        assertThat(plan.items()).allSatisfy(i -> assertThat(i.blockers()).extracting(b -> b.code()).containsExactly(Code.NOT_MISSING));
        assertThat(code(() -> apply(x, plan))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relationSnapshot(x)).isEqualTo(before);
        assertThat(jdbc.queryForList("select REL_SN || ':' || COL_ORD || ':' || ORGNL_COL_SN || '>' || TRGT_COL_SN from TB_TBL_REL_COL where REL_SN in (select REL_SN from TB_TBL_REL where PRJ_SN = ?) order by 1", String.class, x.f().user.getProjectId()))
                .isEqualTo(columnsBefore);
    }

    @Test
    @DisplayName("FK-08·09·13·14·15·16·17·18 : 비교 불가·미매핑·참조 키 불충족은 NOT_MISSING, 타입 비호환·참조 테이블명 충돌·같은 구성 기대(공유)는 각 사유로 막는다")
    void blockedKinds() {
        Fx x = missing("blk");
        long team = x.r().get("소속");
        // FK-17 타입 비호환 : 자식 컬럼 타입을 숫자로
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'BIGINT', MAX_LEN = null where COL_SN = ?", column(x.table("팀"), "FA2"));
        assertThat(preview(x, team).items().get(0).blockers()).extracting(b -> b.code()).contains(Code.TYPE_INCOMPATIBLE);
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'VARCHAR', MAX_LEN = 20 where COL_SN = ?", column(x.table("팀"), "FA2"));
        // FK-18 기존 참조 정보 충돌 : 자식 컬럼에 다른 참조 테이블명 (그 값의 관계는 다른 대상이라 진단은 FK_MISSING 그대로)
        jdbc.update("update TB_COL set RFRNC_TBL_NM = 'NO_SUCH_TABLE' where COL_SN = ?", column(x.table("팀"), "FA1"));
        assertThat(relationship(x, team).structure().code()).isEqualTo("FK_MISSING");
        assertThat(preview(x, team).items().get(0).blockers()).extracting(b -> b.code()).contains(Code.REF_COLUMN_CONFLICT);
        jdbc.update("update TB_COL set RFRNC_TBL_NM = null where COL_SN = ?", column(x.table("팀"), "FA1"));
        assertThat(preview(x, team).applicable()).isTrue();
        // 같은 구성을 기대하는 다른 논리 관계 (만들면 FK_SHARED) : 두 관계 모두 막는다
        LogicalModelDto.Relationship twin = x.f().rel("소속2", x.e().get("부서"), x.e().get("팀"), false, 0);
        x.f().pairs(twin, new long[]{x.a().get("K1"), x.a().get("FA1")}, new long[]{x.a().get("K2"), x.a().get("FA2")});
        Plan shared = preview(x, team, twin.id());
        assertThat(shared.items()).allSatisfy(i -> assertThat(i.blockers()).extracting(b -> b.code()).contains(Code.SHARED_STRUCTURE));
        assertThat(preview(x, team).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.SHARED_STRUCTURE);
        jdbc.update("delete from TB_LGCL_REL_ATRB where LGCL_REL_SN = ?", twin.id());
        // FK-16 참조 키 불충족 : 부모 PK 를 바꾸면 진단이 FK_NOT_EXPECTED (보정 대상 아님)
        jdbc.update("update TB_COL set PK_YN = false where COL_SN = ?", column(x.table("부서"), "K2"));
        assertThat(relationship(x, team).structure().code()).isEqualTo("FK_NOT_EXPECTED");
        assertThat(preview(x, team).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.NOT_MISSING);
        jdbc.update("update TB_COL set PK_YN = true where COL_SN = ?", column(x.table("부서"), "K2"));
        // FK-14·15 속성 미매핑 (관계 구성 컬럼 누락)
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", x.a().get("FA2"));
        assertThat(relationship(x, team).structure().code()).isEqualTo("ATTRIBUTE_UNMAPPED");
        assertThat(preview(x, team).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.NOT_MISSING);
        // FK-13 엔터티 미매핑
        long branch = x.r().get("담당");
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN in (select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ?)", x.e().get("지점"));
        jdbc.update("delete from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", x.e().get("지점"));
        assertThat(relationship(x, branch).structure().code()).isEqualTo("ENTITY_UNMAPPED");
        assertThat(preview(x, branch).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.NOT_MISSING);
    }

    @Test
    @DisplayName("FK-08·09 : 부모 테이블로의 FK 가 여럿(FK_AMBIGUOUS)·같은 FK 를 다른 관계가 이미 씀(FK_SHARED) 은 비교 불가 — 보정하지 않는다")
    void ambiguousAndShared() {
        Fx x = fixture("amb");
        long team = x.r().get("소속");
        // FK_SHARED : 같은 구성의 두 번째 논리 관계 (첫 관계가 FK 를 가져간다)
        LogicalModelDto.Relationship twin = x.f().rel("소속2", x.e().get("부서"), x.e().get("팀"), false, 0);
        x.f().pairs(twin, new long[]{x.a().get("K1"), x.a().get("FA1")}, new long[]{x.a().get("K2"), x.a().get("FA2")});
        assertThat(relationship(x, twin.id()).structure().code()).isEqualTo("FK_SHARED");
        assertThat(preview(x, twin.id()).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.NOT_MISSING);
        jdbc.update("delete from TB_LGCL_REL_ATRB where LGCL_REL_SN = ?", twin.id());
        // FK_AMBIGUOUS : 부서로의 FK 를 두 개로 (구성이 짝과 다른 것 둘)
        long rel = relations(x.table("팀"), x.table("부서")).get(0);
        jdbc.update("update TB_TBL_REL_COL set ORGNL_COL_SN = ? where REL_SN = ? and COL_ORD = 1", column(x.table("팀"), "TEAM_NO"), rel);
        long second = GenerationFixture.nextId();
        jdbc.update("insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, APRV_YN) values (?, ?, ?, ?, 'FK_TEAM_X2', 'FK', 'USER', false)",
                second, x.f().user.getProjectId(), x.table("팀"), x.table("부서"));
        jdbc.update("insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values (?, ?, ?, ?, 1)", GenerationFixture.nextId(), second, column(x.table("팀"), "TEAM_NO"), column(x.table("부서"), "K1"));
        jdbc.update("insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values (?, ?, ?, ?, 2)", GenerationFixture.nextId(), second, column(x.table("팀"), "FA2"), column(x.table("부서"), "K2"));
        assertThat(relationship(x, team).structure().code()).isEqualTo("FK_AMBIGUOUS");
        assertThat(preview(x, team).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.NOT_MISSING);
    }

    @Test
    @DisplayName("FK-21 승인 : 승인된 자식 테이블·자식 컬럼이면 막는다. 승인된 부모 테이블·부모 컬럼은 참조만 하므로 막지 않는다. 관리자 요청도 우회하지 않는다")
    void approvedChildBlocked() {
        Fx x = missing("apc");
        long team = x.r().get("소속");
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.table("부서"));
        jdbc.update("update TB_COL set APRV_YN = true where TBL_SN = ?", x.table("부서"));
        assertThat(preview(x, team).applicable()).as("승인된 부모").isTrue();
        jdbc.update("update TB_COL set APRV_YN = true where COL_SN = ?", column(x.table("팀"), "FA2"));
        assertThat(preview(x, team).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.CHILD_COLUMN_APPROVED);
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.table("팀"));
        Plan plan = preview(x, team);
        assertThat(plan.items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.CHILD_TABLE_APPROVED, Code.CHILD_COLUMN_APPROVED);
        // 이 사용자는 ADMIN (승인 권한) — 그래도 거절
        assertThat(code(() -> apply(x, plan))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relations(x.table("팀"), x.table("부서"))).isEmpty();
    }

    @Test
    @DisplayName("FK-11·12 상속 (D-INH-11 계약 변경) : 슈퍼타입 → 서브타입 일반 관계(상속 PK 가 아닌 자식 컬럼)는 상속 FK 를 후보로 잡지 않아 상속 FK 가 있든 없든 FK_MISSING 이고, 일반 단일 PK FK 라 보류(SINGLE_FK_HELD — M-8)다."
            + " 예전 기대 FK_COLUMNS / INHERITANCE_LINK 는 직계 부모 테이블로 가는 모든 FK 를 상속 FK 로 보던 판정이었다. 상속 PK 를 참조하는 일반 관계는 상속 PK 매핑 컬럼으로 판정한다 (단일이면 보류)")
    void inheritance() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "fkc-inh");
        long party = f.entity("당사자");
        long org = f.entity("조직");
        long person = f.entity("사람");
        long order = f.entity("주문");
        for (long id : new long[]{party, org, person, order}) jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long orgUpper = f.attr(org, "상위 당사자 번호", false, "UP_PTY_NO");
        long ordNo = f.attr(order, "주문 번호", true, "ORD_NO");
        long ordOrg = f.attr(order, "주문 조직 번호", false, "ORD_ORG_NO");
        f.pk(order, ordNo);
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, party, "당사자 구분", null, true, false, null,
                List.of(new LogicalSubtypeDto.MemberInput(org, null), new LogicalSubtypeDto.MemberInput(person, null)), null));
        List<Long> chosen = List.of(party, org, person, order);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        // 당사자(슈퍼) → 조직(서브) 관계 : 상속 FK(조직.PTY_NO → 당사자)는 이 관계의 후보가 아니다 — 기대 FK(조직.UP_PTY_NO → 당사자)가 없어 FK 누락 (예전 FK_COLUMNS)
        LogicalModelDto.Relationship up = f.rel("상위", party, org, false, 0);
        f.pairs(up, new long[]{partyNo, orgUpper});
        // 조직(서브) → 주문 : 부모 키 = 상속 PK (조직.PTY_NO)
        LogicalModelDto.Relationship byOrg = f.rel("주문 조직", org, order, false, 0);
        f.pairs(byOrg, new long[]{partyNo, ordOrg});
        Fx x = new Fx(f, Map.of("당사자", party, "조직", org, "주문", order), Map.of(), Map.of());
        assertThat(relationship(x, up.id()).structure().code()).isEqualTo("FK_MISSING");
        assertThat(preview(x, up.id()).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.SINGLE_FK_HELD);
        Row inherited = relationship(x, byOrg.id());
        assertThat(inherited.structure().code()).isEqualTo("FK_MISSING");
        Item item = preview(x, byOrg.id()).items().get(0);
        assertThat(item.pairs()).singleElement().satisfies(p -> {
            assertThat(p.parentColumnName()).isEqualTo("PTY_NO");
            assertThat(p.parentColumnId()).isEqualTo(column(f.table(org), "PTY_NO"));
        });
        assertThat(item.blockers()).extracting(b -> b.code()).containsExactly(Code.SINGLE_FK_HELD);
        // 상속 FK 를 지워도 상위 관계의 진단은 같다 (상속 FK 는 상속 구조 진단·상속 FK 보정이 다룬다). 상속 관계라는 이유로 막지 않고, 일반 단일 PK FK 라 보류만 한다 (예전 INHERITANCE_LINK)
        for (Long rel : relations(f.table(org), f.table(party))) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", rel);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", rel);
        }
        jdbc.update("update TB_COL set RFRNC_TBL_NM = null where TBL_SN = ?", f.table(org));
        assertThat(relationship(x, up.id()).structure().code()).isEqualTo("FK_MISSING");
        assertThat(preview(x, up.id()).items().get(0).blockers()).extracting(b -> b.code()).containsExactly(Code.SINGLE_FK_HELD);
    }

    // ------------------------------------------------------------------ 계획 토큰 · 최신 상태 · 원자성

    @Test
    @DisplayName("계획 토큰 : 잘못된 토큰·미리보기 뒤 FK 생성·승인·매핑·컬럼 이름·PK 변경은 409 (아무것도 만들지 않는다). 토큰은 권한 확인을 대신하지 않는다")
    void staleToken() {
        Fx x = missing("tok");
        long team = x.r().get("소속");
        Plan plan = preview(x, team);
        int versions = x.f().versions();
        assertThat(code(() -> correction.apply(x.f().user, new Request(x.f().modelId, List.of(team), "bad")))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(code(() -> correction.apply(x.f().user, new Request(x.f().modelId, List.of(team), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        // 선택을 바꾸면 기존 토큰으로 적용할 수 없다
        assertThat(code(() -> correction.apply(x.f().user, new Request(x.f().modelId, List.of(team, x.r().get("상위")), plan.planToken())))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        String[][] changes = {
                {"update TB_COL set APRV_YN = true where COL_SN = ?", "update TB_COL set APRV_YN = false where COL_SN = ?"},
                {"update TB_COL set COL_NM = 'FA1_X' where COL_SN = ?", "update TB_COL set COL_NM = 'FA1' where COL_SN = ?"},
        };
        long fa1 = column(x.table("팀"), "FA1");
        for (String[] change : changes) {
            jdbc.update(change[0], fa1);
            assertThat(code(() -> apply(x, plan))).as(change[0]).isEqualTo(ErrorCode.MODEL_CONFLICT);
            jdbc.update(change[1], fa1);
        }
        long k2 = column(x.table("부서"), "K2");
        jdbc.update("update TB_COL set PK_YN = false where COL_SN = ?", k2);
        assertThat(code(() -> apply(x, plan))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        jdbc.update("update TB_COL set PK_YN = true where COL_SN = ?", k2);
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.table("팀"));
        assertThat(code(() -> apply(x, plan))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        jdbc.update("update TB_TBL set APRV_YN = false where TBL_SN = ?", x.table("팀"));
        Map<String, Object> mapping = jdbc.queryForMap("select ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", x.a().get("FA2"));
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", x.a().get("FA2"));
        assertThat(code(() -> apply(x, plan))).as("매핑 해제").isEqualTo(ErrorCode.MODEL_CONFLICT);
        jdbc.update("insert into TB_ATRB_COL_MPNG(ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values (?, ?, ?, ?, ?, ?, ?, ?)",
                mapping.get("ATRB_COL_MPNG_SN"), mapping.get("ENTT_TBL_MPNG_SN"), mapping.get("LGCL_ATRB_SN"), mapping.get("COL_SN"), mapping.get("REG_DT"), mapping.get("MDFCN_DT"),
                mapping.get("RGTR_ID"), mapping.get("MDFR_ID"));
        // 원래대로 돌리면 같은 토큰 (결정적)
        assertThat(preview(x, team).planToken()).isEqualTo(plan.planToken());
        // 다른 사용자가 그 사이 같은 FK 를 만들었다
        Plan fresh = preview(x, team);
        apply(x, preview(x, team));
        assertThat(code(() -> apply(x, fresh))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(relations(x.table("팀"), x.table("부서"))).hasSize(1);
        assertThat(x.f().versions()).isEqualTo(versions + 1);
    }

    @Test
    @DisplayName("계획 토큰 (추가) : 미리보기 뒤 관계 속성 짝·짝 순서·엔터티 매핑·FK 이름 충돌·참조 컬럼 삭제는 409, 관계 삭제는 400 — 아무것도 만들지 않는다. 부모 승인·설명 변경은 계획을 바꾸지 않아 같은 토큰")
    void staleTokenMore() {
        Fx x = missing("tok2");
        long team = x.r().get("소속");
        Plan plan = preview(x, team);
        int versions = x.f().versions();
        long p = x.f().user.getProjectId();
        List<Map<String, Object>> pairs = jdbc.queryForList("select LGCL_REL_ATRB_SN, TRGT_ATRB_SN, SORT_ORD from TB_LGCL_REL_ATRB where LGCL_REL_SN = ? order by SORT_ORD", team);
        assertThat(pairs).hasSize(2);
        Object first = pairs.get(0).get("LGCL_REL_ATRB_SN"), second = pairs.get(1).get("LGCL_REL_ATRB_SN");
        Object ord1 = pairs.get(0).get("SORT_ORD"), ord2 = pairs.get(1).get("SORT_ORD");

        // 짝 순서
        jdbc.update("update TB_LGCL_REL_ATRB set SORT_ORD = 99 where LGCL_REL_ATRB_SN = ?", first);
        jdbc.update("update TB_LGCL_REL_ATRB set SORT_ORD = ? where LGCL_REL_ATRB_SN = ?", ord1, second);
        jdbc.update("update TB_LGCL_REL_ATRB set SORT_ORD = ? where LGCL_REL_ATRB_SN = ?", ord2, first);
        assertThat(code(() -> apply(x, plan))).as("짝 순서").isEqualTo(ErrorCode.MODEL_CONFLICT);
        jdbc.update("update TB_LGCL_REL_ATRB set SORT_ORD = 99 where LGCL_REL_ATRB_SN = ?", first);
        jdbc.update("update TB_LGCL_REL_ATRB set SORT_ORD = ? where LGCL_REL_ATRB_SN = ?", ord2, second);
        jdbc.update("update TB_LGCL_REL_ATRB set SORT_ORD = ? where LGCL_REL_ATRB_SN = ?", ord1, first);
        // 짝의 자식 속성
        jdbc.update("update TB_LGCL_REL_ATRB set TRGT_ATRB_SN = ? where LGCL_REL_ATRB_SN = ?", x.a().get("TEAM_NO"), first);
        assertThat(code(() -> apply(x, plan))).as("짝 구성").isEqualTo(ErrorCode.MODEL_CONFLICT);
        jdbc.update("update TB_LGCL_REL_ATRB set TRGT_ATRB_SN = ? where LGCL_REL_ATRB_SN = ?", pairs.get(0).get("TRGT_ATRB_SN"), first);
        // 엔터티 매핑 (자식 엔터티를 다른 테이블로)
        long empty = GenerationFixture.nextId();
        long schema = jdbc.queryForObject("select SCHM_SN from TB_TBL where TBL_SN = ?", Long.class, x.table("팀"));
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values (?, ?, ?, 'TB_TOK2_EMPTY', false)", empty, p, schema);
        long teamTable = x.table("팀");
        jdbc.update("update TB_ENTT_TBL_MPNG set TBL_SN = ? where LGCL_ENTT_SN = ?", empty, x.e().get("팀"));
        assertThat(code(() -> apply(x, plan))).as("엔터티 매핑").isEqualTo(ErrorCode.MODEL_CONFLICT);
        jdbc.update("update TB_ENTT_TBL_MPNG set TBL_SN = ? where LGCL_ENTT_SN = ?", teamTable, x.e().get("팀"));
        // FK 이름 충돌 (같은 스키마의 다른 FK 가 그 이름을 쓴다)
        Item item = plan.items().get(0);
        long rel = GenerationFixture.nextId();
        jdbc.update("insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, APRV_YN) values (?, ?, ?, ?, ?, 'FK', 'USER', false)",
                rel, p, x.table("배송"), x.table("지점"), item.relationName());
        jdbc.update("insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values (?, ?, ?, ?, 1)", GenerationFixture.nextId(), rel,
                column(x.table("배송"), "DB1"), column(x.table("지점"), "B1"));
        assertThat(code(() -> apply(x, plan))).as("FK 이름 충돌").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(preview(x, team).items().get(0).blockers()).extracting(i -> i.code()).contains(Code.NAME_CONFLICT);
        jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", rel);
        jdbc.update("delete from TB_TBL_REL where REL_SN = ?", rel);

        // 계획과 무관한 값 : 부모 테이블·부모 컬럼 승인, 자식 테이블 설명 — 같은 토큰 (보정 가능 여부·계획이 그대로)
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.table("부서"));
        jdbc.update("update TB_COL set APRV_YN = true where COL_SN = ?", column(x.table("부서"), "K1"));
        jdbc.update("update TB_TBL set TBL_EXPLN = '설명 바꿈' where TBL_SN = ?", teamTable);
        assertThat(preview(x, team).planToken()).isEqualTo(plan.planToken());
        assertThat(relations(teamTable, x.table("부서"))).isEmpty();
        assertThat(x.f().versions()).isEqualTo(versions);

        // 관계 삭제 : 이 모델의 관계가 아니다 → 400 (저장 없음)
        long manager = x.r().get("상위");
        Plan managerPlan = preview(x, manager);
        jdbc.update("delete from TB_LGCL_REL_ATRB where LGCL_REL_SN = ?", manager);
        jdbc.update("delete from TB_LGCL_REL where LGCL_REL_SN = ?", manager);
        assertThat(code(() -> apply(x, managerPlan))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relations(x.table("직원"), x.table("직원"))).isEmpty();

        // 참조 대상(부모 PK) 컬럼 삭제
        long k2 = column(x.table("부서"), "K2");
        jdbc.update("delete from TB_ATRB_COL_MPNG where COL_SN = ?", k2);
        jdbc.update("delete from TB_COL where COL_SN = ?", k2);
        assertThat(code(() -> apply(x, plan))).as("참조 컬럼 삭제").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(relations(teamTable, x.table("부서"))).isEmpty();
        assertThat(x.f().versions()).as("409·400 뒤 모델 버전 없음").isEqualTo(versions);
    }

    @Test
    @DisplayName("전체 원자성 : 두 번째 FK 생성이 실패하면 첫 FK·모델 버전·이력까지 모두 롤백")
    void atomic() {
        Fx x = missing("atom");
        Plan plan = preview(x, x.r().get("소속"), x.r().get("상위"), x.r().get("담당"));
        assertThat(plan.applicable()).isTrue();
        List<String> before = relationSnapshot(x);
        int versions = x.f().versions();
        long history = jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Long.class, x.f().user.getProjectId());
        FAIL_AT.set(2);
        assertThatThrownBy(() -> apply(x, plan)).hasMessageContaining("FK 생성 실패");
        assertThat(relationSnapshot(x)).isEqualTo(before);
        assertThat(x.f().versions()).isEqualTo(versions);
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Long.class, x.f().user.getProjectId())).isEqualTo(history);
        FAIL_AT.set(0);
        assertThat(apply(x, plan).created()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 요청 검사 · 권한 · 격리

    @Test
    @DisplayName("요청 검사·격리·권한 : 빈 선택·중복·101개 400, 다른 모델·다른 프로젝트의 관계/모델 거절, VIEWER 는 조회(상태)만 — 미리보기·적용은 403")
    void validationAndAuthorization() {
        Fx x = missing("auth");
        long team = x.r().get("소속");
        assertThat(code(() -> correction.preview(x.f().user, new Request(x.f().modelId, List.of(), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> correction.preview(x.f().user, new Request(x.f().modelId, List.of(team, team), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        List<Long> many = new java.util.ArrayList<>();
        for (long i = 1; i <= 101; i++) many.add(i);
        assertThat(code(() -> correction.preview(x.f().user, new Request(x.f().modelId, many, null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> correction.preview(x.f().user, new Request(x.f().modelId, List.of(Long.MAX_VALUE), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        // 같은 프로젝트 다른 모델의 관계
        GenerationFixture other = x.f().otherModel("fkc-other-" + System.nanoTime());
        assertThat(code(() -> correction.preview(x.f().user, new Request(other.modelId, List.of(team), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        // 다른 프로젝트 : 그 로그인으로는 이 모델을 찾을 수 없다
        Fx stranger = fixture("auth2");
        assertThat(code(() -> correction.preview(stranger.f().user, new Request(x.f().modelId, List.of(team), null)))).isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        Plan plan = preview(x, team);
        assertThat(code(() -> correction.apply(stranger.f().user, new Request(x.f().modelId, List.of(team), plan.planToken())))).isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        // 권한 (컨트롤러의 @PreAuthorize)
        com.lexorma.core.security.domain.dto.AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, x.f().user.getProjectId(), x.f().user.getId(), "ROLE_VIEWER");
        assertThat(controller.foreignKeyStatus(viewer, x.f().modelId, List.of(team)).getBody()).singleElement().satisfies(i -> assertThat(i.correctable()).isTrue());
        assertThatThrownBy(() -> controller.foreignKeyPreview(new Request(x.f().modelId, List.of(team), null), viewer)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.foreignKeyApply(new Request(x.f().modelId, List.of(team), plan.planToken()), viewer)).isInstanceOf(AccessDeniedException.class);
        assertThat(relations(x.table("팀"), x.table("부서"))).isEmpty();
        com.lexorma.core.security.domain.dto.AccountDto user = SecurityTestSupport.loginAs(projectRepository, x.f().user.getProjectId(), x.f().user.getId(), "ROLE_USER");
        assertThat(controller.foreignKeyApply(new Request(x.f().modelId, List.of(team), plan.planToken()), user).getBody().created()).isEqualTo(1);
    }
}
