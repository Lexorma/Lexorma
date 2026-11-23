package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-7 3차 동시성 (M7-D39, docs/completed/subtype-physical-transformation.md 30장) : 서로 다른 DB 연결의 두 트랜잭션이 실제로 겹치게 한다 —
 * 먼저 시작한 쪽은 작업을 마치고 커밋 전에 잠금을 쥔 채 멈추고(latch), 그 사이 둘째 요청을 보낸다. 둘째가 기다리는지(잠금 대기) 바로 거절되는지(NOWAIT → 409),
 * 첫째가 커밋한 뒤 둘째의 최종 결과와 DB 상태를 본다. 결과는 성공 또는 업무 오류(CustomException)뿐이다 — 교착·무한 대기·500 이 없다.
 * PostgreSQL 실행이 기준이다 (H2 는 같은 불변식을 확인할 뿐 프로젝트 행 잠금 동작이 다르다 — CLAUDE.md M-4c 메모).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SubtypeGenerationConcurrencyIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingService mappings;
    @Autowired ColumnManageService columns;
    @Autowired EntityTableMappingRepository entityMappings;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private void group(GenerationFixture f, long supertype, String name, long... members) {
        List<MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new MemberInput(m, null));
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, true, false, null, list, null));
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private long mappingId(long entity) {
        return entityMappings.findByEntity_Id(entity).orElseThrow().getId();
    }

    /** 결과 : 둘째 요청이 기다렸는지, 둘째의 결과 (OK 또는 오류 코드), 첫째의 결과 */
    record Outcome(boolean secondWaited, String second, String first) {
    }

    /**
     * 첫째를 바깥 트랜잭션 안에서 실행하고 커밋 전에 멈춘다(잠금을 쥔 채). 그 사이 둘째를 다른 스레드·연결로 실행한다. 500ms 안에 둘째가 끝나지 않으면 "기다림" 이다.
     * 첫째를 커밋한 뒤 둘째의 결과를 받는다 (둘 다 10초 안 — 교착·무한 대기면 실패)
     */
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
        assertThat(firstDone.await(15, TimeUnit.SECONDS)).as("첫째가 작업을 마쳤다").isTrue();
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
        // 첫째가 잠금을 쥔 동안 둘째가 끝나면 기다리지 않은 것(NOWAIT 거절 등), 500ms 안에 끝나지 않으면 잠금을 기다리는 것이다 (H2 잠금 대기 한도 1초 안 — 첫째 커밋 시간 여유)
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

    /** 당사자(루트, 단일 키) → 조직(중간) → 부서·팀. 당사자·조직은 만들어 두고 부서 계획을 돌려준다 */
    private record Tree(GenerationFixture f, long party, long partyNo, long org, long dept, long partyTable, long orgTable, PhysicalGenerationDto.Plan deptPlan) {
    }

    private Tree tree(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, name);
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", org, human);
        group(f, org, "조직 구분", dept, team);
        PhysicalGenerationDto.Plan first = generation.preview(f.user, f.request(List.of(party, org)));
        generation.apply(f.user, f.request(List.of(party, org), first.planToken()));
        PhysicalGenerationDto.Plan deptPlan = generation.preview(f.user, f.request(List.of(dept)));
        assertThat(deptPlan.creatable()).isTrue();
        return new Tree(f, party, partyNo, org, dept, f.table(party), f.table(org), deptPlan);
    }

    private AccountDto login(GenerationFixture f) {
        return SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
    }

    /** 부모 테이블에 PK 컬럼을 더한다 (컬럼 등록 — 테이블 행을 잠그는 경로) */
    private Object addPkColumn(GenerationFixture f, long table, String name) {
        ColumnInfoDto dto = new ColumnInfoDto();
        dto.setTableInfoId(table);
        dto.setTermId(f.term(name, "VARCHAR", 10));
        dto.setColumnName(name);
        dto.setColumnDesc(name);
        dto.setDataType("VARCHAR");
        dto.setMaxLength(10);
        dto.setIsPk("Y");
        dto.setIsNullable("N");
        columns.insertColumn(login(f), dto);
        return null;
    }

    private int created(GenerationFixture f, long entity) {
        return f.count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", entity);
    }

    // ------------------------------------------------------------------ A. 기존 부모 PK 변경 ↔ 생성

    @Test
    @DisplayName("A-1 생성이 먼저 잠금 : 부모 테이블 PK 컬럼 추가는 생성이 끝날 때까지 기다린 뒤 반영된다 — 생성 결과는 계획 그대로, 그 뒤 부모는 불일치로 보인다 (매핑 보존)")
    void parentPkAfterGeneration() throws Exception {
        Tree t = tree("cc-a1");
        Outcome o = ordered(() -> generation.apply(login(t.f()), t.f().request(List.of(t.dept()), t.deptPlan().planToken())), () -> addPkColumn(t.f(), t.orgTable(), "ORG_EXTRA_CD"));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).as("컬럼 등록은 생성이 잠근 부모 테이블 행을 기다린다").isTrue();
        assertThat(o.second()).isEqualTo("OK");
        assertThat(created(t.f(), t.dept())).isOne();
        // 생성이 넣은 상속 FK(참조 테이블명)는 그대로다. 그 뒤 부모 PK 가 복합이 되면 기존 참조 동기화 규칙대로 단일 참조 관계가 풀린다 (생성 뒤의 변경 — §56, 매핑·컬럼은 보존)
        String orgName = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, t.orgTable());
        assertThat(jdbc.queryForObject("select RFRNC_TBL_NM from TB_COL where TBL_SN = ? and COL_NM = 'PTY_NO'", String.class, t.f().table(t.dept()))).isEqualTo(orgName);
        assertThat(t.f().count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.LGCL_ENTT_SN = ?", t.dept())).isOne();
        assertThat(mappings.selectSubtypeKeys(t.f().user, mappingId(t.org())).status()).as("생성 뒤의 변경은 막지 않고 불일치로 보인다").isEqualTo("PARENT_KEY_MISMATCH");
    }

    @Test
    @DisplayName("A-2 부모 변경이 먼저 잠금 : 생성은 기다리지 않고 409, 아무것도 만들지 않는다. 변경 커밋 뒤 미리보기는 PK 불일치")
    void parentPkBeforeGeneration() throws Exception {
        Tree t = tree("cc-a2");
        int versions = t.f().versions();
        Outcome o = ordered(() -> addPkColumn(t.f(), t.orgTable(), "ORG_EXTRA_CD"), () -> generation.apply(login(t.f()), t.f().request(List.of(t.dept()), t.deptPlan().planToken())));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(o.secondWaited()).as("NOWAIT — 기다리지 않는다").isFalse();
        assertThat(created(t.f(), t.dept())).isZero();
        assertThat(t.f().versions()).isEqualTo(versions);
        assertThat(generation.preview(t.f().user, t.f().request(List.of(t.dept()))).tables().getFirst().inheritance().reasons()).containsExactly("PARENT_KEY_MISMATCH");
    }

    // ------------------------------------------------------------------ B. 기존 부모 상속 PK 매핑 변경 ↔ 생성

    @Test
    @DisplayName("B-1 생성이 먼저 : 부모의 상속 PK 매핑 해제는 논리 모델 행을 기다린 뒤 반영 — 생성된 FK 는 그대로, 부모는 매핑 없음으로 보인다")
    void parentMappingAfterGeneration() throws Exception {
        Tree t = tree("cc-b1");
        long link = jdbc.queryForObject("select s.SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.LGCL_ENTT_SN = ?", Long.class, t.org());
        Outcome o = ordered(() -> generation.apply(login(t.f()), t.f().request(List.of(t.dept()), t.deptPlan().planToken())), () -> {
            mappings.deleteSubtypeKeyMapping(login(t.f()), link, null);
            return null;
        });
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).isTrue();
        assertThat(o.second()).isEqualTo("OK");
        assertThat(created(t.f(), t.dept())).isOne();
        assertThat(mappings.selectSubtypeKeys(t.f().user, mappingId(t.org())).status()).isEqualTo("INHERITED_KEY_MAPPING_MISSING");
    }

    @Test
    @DisplayName("B-2 매핑 변경이 먼저 : 생성은 기다리지 않고 409 (논리 모델 행 NOWAIT), 커밋 뒤 미리보기는 상속 PK 매핑 없음")
    void parentMappingBeforeGeneration() throws Exception {
        Tree t = tree("cc-b2");
        long link = jdbc.queryForObject("select s.SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.LGCL_ENTT_SN = ?", Long.class, t.org());
        Outcome o = ordered(() -> {
            mappings.deleteSubtypeKeyMapping(login(t.f()), link, null);
            return null;
        }, () -> generation.apply(login(t.f()), t.f().request(List.of(t.dept()), t.deptPlan().planToken())));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(o.secondWaited()).isFalse();
        assertThat(created(t.f(), t.dept())).isZero();
        assertThat(generation.preview(t.f().user, t.f().request(List.of(t.dept()))).tables().getFirst().inheritance().reasons()).containsExactly("INHERITED_KEY_MAPPING_MISSING");
    }

    // ------------------------------------------------------------------ C. 직접 매핑 ↔ 상속 PK 매핑 교차 등록

    /** 고객(루트) · 기업 고객(서브타입, 기존 테이블 — 컬럼 CORP_CUST_NO 는 아직 어디에도 매핑되지 않음) */
    private record Cross(GenerationFixture f, long customerNo, long company, long ref, long column) {
    }

    private Cross cross(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, name);
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        long company = entity(f, "기업 고객");
        long ref = f.attr(company, "기업 고객 번호", false, "CORP_CUST_NO");
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer, company)));
        generation.apply(f.user, f.request(List.of(customer, company), plan.planToken()));
        group(f, customer, "고객 구분", company, entity(f, "개인 고객"));
        long column = column(f.table(company), "CORP_CUST_NO");
        mappings.deleteAttributeMapping(f.user, jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, column), null);
        return new Cross(f, customerNo, company, ref, column);
    }

    private Object direct(Cross c) {
        return mappings.saveAttributeMapping(login(c.f()), new ModelMappingDto.AttributeMapping(null, mappingId(c.company()), c.ref(), c.column()));
    }

    private Object inherited(Cross c) {
        return mappings.saveSubtypeKeyMapping(login(c.f()), new ModelMappingDto.SubtypeKeyMapping(null, mappingId(c.company()), c.customerNo(), c.column()));
    }

    private int uses(Cross c) {
        return c.f().count("select count(*) from TB_ATRB_COL_MPNG where COL_SN = ?", c.column()) + c.f().count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG where COL_SN = ?", c.column());
    }

    @Test
    @DisplayName("C-1·C-2 교차 등록 : 직접 매핑이 먼저면 상속 PK 매핑이, 상속 PK 매핑이 먼저면 직접 매핑이 기다린 뒤 SAVED_DATA_EXISTS — 컬럼은 한 곳에만")
    void crossOrdered() throws Exception {
        Cross c1 = cross("cc-c1");
        Outcome o1 = ordered(() -> direct(c1), () -> inherited(c1));
        assertThat(o1).isEqualTo(new Outcome(true, "SAVED_DATA_EXISTS", "OK"));
        assertThat(uses(c1)).isOne();
        Cross c2 = cross("cc-c2");
        Outcome o2 = ordered(() -> inherited(c2), () -> direct(c2));
        assertThat(o2).isEqualTo(new Outcome(true, "SAVED_DATA_EXISTS", "OK"));
        assertThat(uses(c2)).isOne();
    }

    @Test
    @DisplayName("C-3 같은 컬럼 실제 동시 등록 (직접 ↔ 상속, 상속 ↔ 상속) : 한쪽만 성공, 다른 쪽은 SAVED_DATA_EXISTS — 둘 다 성공하지 않는다")
    void crossSimultaneous() throws Exception {
        for (int round = 0; round < 3; round++) {
            Cross c = cross("cc-c3-" + round);
            List<String> results = simultaneous(() -> direct(c), () -> inherited(c));
            assertThat(results).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
            assertThat(uses(c)).isOne();
            Cross d = cross("cc-c3b-" + round);
            List<String> twice = simultaneous(() -> inherited(d), () -> inherited(d));
            assertThat(twice).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
            assertThat(uses(d)).isOne();
        }
    }

    private List<String> simultaneous(Supplier<Object> a, Supplier<Object> b) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (Supplier<Object> task : List.of(a, b)) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                SecurityContextHolder.setContext(login);
                try {
                    start.await();
                    task.get();
                    return "OK";
                } catch (CustomException e) {
                    return e.getErrorCode().name();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "INTERRUPTED";
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> f : futures) results.add(f.get(20, TimeUnit.SECONDS));
        return results;
    }

    // ------------------------------------------------------------------ D. 매핑 등록 ↔ 객체 삭제

    @Test
    @DisplayName("D-1 상속 PK 매핑 등록 ↔ 물리 컬럼 삭제 : 등록이 먼저면 삭제가 기다린 뒤 RELATION_EXISTS, 삭제가 먼저면 등록이 기다린 뒤 INVALID_MODEL_MAPPING — 고아 매핑 없음")
    void mappingVersusColumnDelete() throws Exception {
        Cross c1 = cross("cc-d1");
        ColumnInfoDto target = new ColumnInfoDto();
        target.setId(c1.column());
        Outcome o1 = ordered(() -> inherited(c1), () -> {
            columns.deleteColumn(login(c1.f()), List.of(target));
            return null;
        });
        assertThat(o1).isEqualTo(new Outcome(true, "RELATION_EXISTS", "OK"));
        assertThat(uses(c1)).isOne();

        Cross c2 = cross("cc-d1b");
        ColumnInfoDto target2 = new ColumnInfoDto();
        target2.setId(c2.column());
        Outcome o2 = ordered(() -> {
            columns.deleteColumn(login(c2.f()), List.of(target2));
            return null;
        }, () -> inherited(c2));
        assertThat(o2).isEqualTo(new Outcome(true, "INVALID_MODEL_MAPPING", "OK"));
        assertThat(c2.f().count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG where COL_SN = ?", c2.column())).isZero();
        assertThat(c2.f().count("select count(*) from TB_COL where COL_SN = ?", c2.column())).isZero();
    }

    @Test
    @DisplayName("D-2 상속 PK 매핑 등록 ↔ 원본 속성 삭제 : 삭제는 기다린 뒤 거절 (RELATION_EXISTS — 속성이 남는다)")
    void mappingVersusAttributeDelete() throws Exception {
        Cross c = cross("cc-d2");
        Outcome o = ordered(() -> inherited(c), () -> {
            logical.deleteAttribute(login(c.f()), c.customerNo(), null);
            return null;
        });
        assertThat(o).isEqualTo(new Outcome(true, "RELATION_EXISTS", "OK"));
        assertThat(c.f().count("select count(*) from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", c.customerNo())).isOne();
        assertThat(uses(c)).isOne();
    }

    @Test
    @DisplayName("D-3 상속 PK 매핑 등록 ↔ 엔터티 매핑 해제 : 등록이 먼저면 해제가 기다린 뒤 RELATION_EXISTS, 해제가 먼저면 등록이 기다린 뒤 매핑 없음(MODEL_MAPPING_IS_NOT_DEFINED)")
    void mappingVersusEntityMappingDelete() throws Exception {
        Cross c1 = cross("cc-d3");
        long mapping1 = mappingId(c1.company());
        Outcome o1 = ordered(() -> inherited(c1), () -> {
            mappings.deleteEntityMapping(login(c1.f()), mapping1, null);
            return null;
        });
        assertThat(o1).isEqualTo(new Outcome(true, "RELATION_EXISTS", "OK"));
        assertThat(entityMappings.findById(mapping1)).isPresent();

        Cross c2 = cross("cc-d3b");
        long mapping2 = mappingId(c2.company());
        // 직접 속성 매핑이 남아 있으면 해제가 막히므로 모두 푼다
        jdbc.queryForList("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = ?", Long.class, mapping2).forEach(id -> mappings.deleteAttributeMapping(c2.f().user, id, null));
        Outcome o2 = ordered(() -> {
            mappings.deleteEntityMapping(login(c2.f()), mapping2, null);
            return null;
        }, () -> mappings.saveSubtypeKeyMapping(login(c2.f()), new ModelMappingDto.SubtypeKeyMapping(null, mapping2, c2.customerNo(), c2.column())));
        assertThat(o2).isEqualTo(new Outcome(true, "MODEL_MAPPING_IS_NOT_DEFINED", "OK"));
        assertThat(c2.f().count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG where COL_SN = ?", c2.column())).isZero();
    }

    // ------------------------------------------------------------------ E. 같은 계획 동시 실행

    @Test
    @DisplayName("E 같은 planToken 두 요청 : 하나만 성공, 다른 하나는 프로젝트 행을 기다린 뒤 다시 계획해 409 — 테이블·매핑·상속 PK 매핑·버전이 한 벌")
    void samePlanTwice() throws Exception {
        for (int round = 0; round < 3; round++) {
            Tree t = tree("cc-e-" + round);
            int versions = t.f().versions();
            List<String> results = simultaneous(() -> generation.apply(login(t.f()), t.f().request(List.of(t.dept()), t.deptPlan().planToken())),
                    () -> generation.apply(login(t.f()), t.f().request(List.of(t.dept()), t.deptPlan().planToken())));
            assertThat(results).containsExactlyInAnyOrder("OK", "MODEL_CONFLICT");
            assertThat(created(t.f(), t.dept())).isOne();
            assertThat(t.f().count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.LGCL_ENTT_SN = ?", t.dept())).isOne();
            assertThat(t.f().count("select count(*) from TB_TBL where PRJ_SN = ?", t.f().user.getProjectId())).isEqualTo(3);
            assertThat(t.f().versions()).isEqualTo(versions + 1);
        }
    }
}
