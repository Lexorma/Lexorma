package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Item;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalModel;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.entity.AttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import com.lexorma.data.mapping.domain.entity.SubtypeIdentifierColumnMapping;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.domain.entity.ModelVersion;
import com.lexorma.data.version.service.ModelVersionService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 5차 : 스냅샷 9 · 교환 6 의 상속 PK 매핑 보존·하위 호환·참조 재매핑·원자성·격리 (docs/completed/subtype-physical-transformation.md 32장).
 * 모델 버전 복원과 별도 "프로젝트 복사" 서비스는 없다 — 복원은 범위 밖(M-9·M-7 기록), 복사는 지금 프로젝트의 Export 를 다른 프로젝트에 가져오는 교환과 같은 경로(DEC-22 3장)라
 * 복사 검증 = 교환 가져오기 검증이다. 저장된 행을 직접 본다. H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SubtypePreservationIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingService mappings;
    @Autowired ModelVersionService versions;
    @Autowired ModelExchangeService exchange;
    @Autowired com.lexorma.data.exchange.controller.ModelExchangeRestController exchangeController;
    @Autowired EntityTableMappingRepository entityMappings;
    @Autowired SchemaInfoRepository schemas;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 테스트 전용 실패 주입 (운영 코드·설정에 없다 — 꺼져 있으면 아무것도 하지 않는다)

    static final class Injector implements PostInsertEventListener {
        static volatile Class<?> target;
        static final AtomicInteger skip = new AtomicInteger();
        static final AtomicInteger fired = new AtomicInteger();

        @Override
        public void onPostInsert(PostInsertEvent event) {
            Class<?> t = target;
            if (t == null || event.getPersister().getMappedClass() != t) return;
            if (skip.getAndDecrement() > 0) return;
            fired.incrementAndGet();
            throw new IllegalStateException("injected failure after " + t.getSimpleName());
        }

        @Override
        public boolean requiresPostCommitHandling(EntityPersister persister) {
            return false;
        }
    }

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    @BeforeEach
    void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            entityManagerFactory.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(EventListenerRegistry.class)
                    .appendListeners(EventType.POST_INSERT, new Injector());
        }
        Injector.target = null;
    }

    @AfterEach
    void tearDown() {
        Injector.target = null;
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 준비

    private long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private LogicalSubtypeDto.Group group(GenerationFixture f, long supertype, String name, long... members) {
        List<MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new MemberInput(m, null));
        return subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, true, false, null, list, null));
    }

    private void generate(GenerationFixture f, Long schemaId, Long... entities) {
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(entities), schemaId, null));
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(t -> t.errors().stream()).toList())).isTrue();
        generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(entities), schemaId, plan.planToken()));
    }

    private long mappingId(long entity) {
        return entityMappings.findByEntity_Id(entity).orElseThrow().getId();
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    /** 시나리오 : 단일 키 루트(고객) → 기업·개인(자기 속성 없음, 같은 루트 속성을 두 서브타입이 씀), 복합 키 3단계(당사자 → 조직 → 부서·팀, 사람 — 다른 스키마),
     * 직접 속성 매핑과 상속 PK 매핑 혼재, 부서의 상속 PK 하나 해제(부분), 일반 엔터티(상품) */
    private record Scenario(GenerationFixture f, long customer, long customerNo, long company, long person, long party, long partyNo, long orgCd, long org, long human,
                            long dept, long team, long product) {
    }

    private Scenario scenario(String name, boolean partial) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, name);
        SchemaInfo other = tx.execute(s -> schemas.save(SchemaInfo.builder().projectId(f.user.getProjectId()).schemaName("SB").isDefault(false).build()));
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        f.attr(customer, "고객 이름", false, "CUST_NM");
        long company = entity(f, "기업 고객");
        f.attr(company, "사업자 번호", false, "BIZ_NO");
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 구분", company, person);
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        long orgCd = f.attr(party, "기관 코드", true, "ORG_CD");
        f.pk(party, partyNo, orgCd);
        long org = entity(f, "조직");
        f.attr(org, "조직 이름", false, "ORG_NM");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", org, human);
        group(f, org, "조직 구분", dept, team);
        long product = entity(f, "상품");
        f.pk(product, f.attr(product, "상품 번호", true, "PRDT_NO"));
        generate(f, null, customer, company, person, product);
        generate(f, other.getId(), party, org, human, dept, team);
        if (partial) {
            long deptKey = jdbc.queryForObject("select SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ? and LGCL_ATRB_SN = ?", Long.class,
                    mappingId(dept), orgCd);
            mappings.deleteSubtypeKeyMapping(f.user, deptKey, null);
        }
        return new Scenario(f, customer, customerNo, company, person, party, partyNo, orgCd, org, human, dept, team, product);
    }

    /** 프로젝트의 상속 PK 매핑 : 서브타입:원본 속성@원본 엔터티>스키마.테이블.컬럼 (ID 와 무관한 논리 키) */
    private Set<String> keyRows(Long projectId) {
        return new TreeSet<>(jdbc.queryForList("select e.ENTT_NM || ':' || a.ATRB_NM || '@' || ae.ENTT_NM || '>' || sc.SCHM_NM || '.' || ct.TBL_NM || '.' || c.COL_NM"
                + " from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.LGCL_ENTT_SN"
                + " join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = s.LGCL_ATRB_SN join TB_LGCL_ENTT ae on ae.LGCL_ENTT_SN = a.LGCL_ENTT_SN join TB_COL c on c.COL_SN = s.COL_SN"
                + " join TB_TBL ct on ct.TBL_SN = c.TBL_SN join TB_SCHM sc on sc.SCHM_SN = ct.SCHM_SN"
                // 격리 : 매핑·원본 속성·컬럼·컬럼 테이블이 모두 같은 프로젝트이고 컬럼은 그 매핑 테이블의 것
                + " where m.PRJ_SN = ? and ae.PRJ_SN = ? and ct.PRJ_SN = ? and ct.TBL_SN = m.TBL_SN", String.class, projectId, projectId, projectId));
    }

    /** 상속 PK 매핑 행 수 (격리 조건 없이 — 위 논리 키 수와 같아야 다른 프로젝트·다른 테이블을 가리키는 행이 없다) */
    private int keyCount(Long projectId) {
        return jdbc.queryForObject("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.PRJ_SN = ?",
                Integer.class, projectId);
    }

    /** 직접 속성 매핑 : 엔터티.속성>테이블.컬럼 */
    private Set<String> directRows(Long projectId) {
        return new TreeSet<>(jdbc.queryForList("select e.ENTT_NM || '.' || a.ATRB_NM || '>' || t.TBL_NM || '.' || c.COL_NM from TB_ATRB_COL_MPNG x"
                + " join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = x.ENTT_TBL_MPNG_SN join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.LGCL_ENTT_SN"
                + " join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = x.LGCL_ATRB_SN join TB_COL c on c.COL_SN = x.COL_SN join TB_TBL t on t.TBL_SN = c.TBL_SN"
                + " where m.PRJ_SN = ? and t.PRJ_SN = ? and a.LGCL_ENTT_SN = e.LGCL_ENTT_SN", String.class, projectId, projectId));
    }

    /** 상속 구조·식별자·물리 PK·FK 의 논리 표현 (왕복 비교) */
    private Set<String> structure(Long projectId) {
        Set<String> result = new TreeSet<>();
        result.addAll(jdbc.queryForList("select 'G:' || s.ENTT_NM || '>' || e.ENTT_NM || ':' || g.DPCN_PSBLTY_YN || g.TOTL_YN from TB_LGCL_SBTYP x join TB_LGCL_SBTYP_GROUP g on g.LGCL_SBTYP_GROUP_SN = x.LGCL_SBTYP_GROUP_SN"
                + " join TB_LGCL_ENTT s on s.LGCL_ENTT_SN = g.SPRTYP_ENTT_SN join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = x.SBTYP_ENTT_SN where g.PRJ_SN = ?", String.class, projectId));
        result.addAll(jdbc.queryForList("select 'I:' || e.ENTT_NM || '.' || a.ATRB_NM || '#' || ia.SORT_ORD from TB_LGCL_IDNTFR i join TB_LGCL_IDNTFR_ATRB ia on ia.LGCL_IDNTFR_SN = i.LGCL_IDNTFR_SN"
                + " join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = ia.LGCL_ATRB_SN join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = i.LGCL_ENTT_SN where e.PRJ_SN = ? and i.BSC_YN = true", String.class, projectId));
        result.addAll(jdbc.queryForList("select 'PK:' || t.TBL_NM || '.' || c.COL_NM || '#' || c.SORT_ORD from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ? and c.PK_YN = true",
                String.class, projectId));
        result.addAll(jdbc.queryForList("select 'FK:' || st.TBL_NM || '(' || sc.COL_NM || ')>' || tt.TBL_NM || '(' || tc.COL_NM || ')#' || x.COL_ORD from TB_TBL_REL r"
                + " join TB_TBL_REL_COL x on x.REL_SN = r.REL_SN join TB_TBL st on st.TBL_SN = r.ORGNL_TBL_SN join TB_TBL tt on tt.TBL_SN = r.TRGT_TBL_SN"
                + " join TB_COL sc on sc.COL_SN = x.ORGNL_COL_SN join TB_COL tc on tc.COL_SN = x.TRGT_COL_SN where r.PRJ_SN = ? and r.REL_TYPE_CD = 'FK'", String.class, projectId));
        result.addAll(jdbc.queryForList("select 'T:' || a.ATRB_NM || '>' || t.TRM_NM from TB_LGCL_ATRB a join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = a.LGCL_ENTT_SN join TB_STD_TRM t on t.STD_TRM_SN = a.STD_TRM_SN where e.PRJ_SN = ?",
                String.class, projectId).stream().map(s -> s.replaceAll("_\\d+$", "")).toList());
        return result;
    }

    /** 모든 프로젝트의 모델 데이터 행 수 (원자성 : 실패한 요청은 어느 프로젝트에도 아무것도 남기지 않는다) */
    private List<Integer> footprint() {
        List<Integer> result = new ArrayList<>();
        for (String table : List.of("TB_TBL", "TB_COL", "TB_TBL_REL", "TB_TBL_REL_COL", "TB_LGCL_MDL", "TB_LGCL_ENTT", "TB_LGCL_ATRB", "TB_LGCL_IDNTFR", "TB_LGCL_SBTYP_GROUP",
                "TB_LGCL_SBTYP", "TB_ENTT_TBL_MPNG", "TB_ATRB_COL_MPNG", "TB_SBTYP_IDNTFR_COL_MPNG", "TB_MDL_VER", "TB_MDL_CHG_HSTRY", "TB_SCHM")) {
            result.add(jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
        return result;
    }

    private JsonNode snapshotItems(AccountDto user, long versionId, String type) {
        JsonNode snapshot = JSON.readTree(JSON.writeValueAsString(versions.snapshot(user, versionId).objects()));
        return snapshot.path(type);
    }

    private static Set<String> keyTriples(JsonNode items) {
        Set<String> result = new TreeSet<>();
        items.forEach(i -> result.add(i.path("state").path("mappingId").asLong() + ":" + i.path("state").path("attributeId").asLong() + ":" + i.path("state").path("columnId").asLong()));
        return result;
    }

    private Set<String> dbTriples(Long projectId) {
        return new TreeSet<>(jdbc.queryForList("select s.ENTT_TBL_MPNG_SN || ':' || s.LGCL_ATRB_SN || ':' || s.COL_SN from TB_SBTYP_IDNTFR_COL_MPNG s"
                + " join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.PRJ_SN = ?", String.class, projectId));
    }

    private static void error(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).as(e.getMessage()).isEqualTo(code));
    }

    private ModelExchangeDto.Result importAll(AccountDto target, ModelExchangeDto.Document document) {
        ModelExchangeDto.Plan plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), document, null));
        return exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), document, null, plan.planToken(), plan.requestId()));
    }

    private void identifier(GenerationFixture f, long entity, Long... attributes) {
        Long id = jdbc.queryForObject("select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ? and BSC_YN = true", Long.class, entity);
        LocalDateTime at = jdbc.queryForObject("select MDFCN_DT from TB_LGCL_IDNTFR where LGCL_IDNTFR_SN = ?", LocalDateTime.class, id);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(id, entity, "식별자", true, List.of(attributes), null, at));
    }

    // ------------------------------------------------------------------ 스냅샷 9

    @Test
    @DisplayName("스냅샷 9 : 단일·복합·3단계·여러 서브타입이 같은 루트 속성·자기 속성 없는 서브타입·직접/상속 혼재·부분·무효(식별자 변경·상속 해제)·여러 스키마의 상속 PK 매핑을 DB 행 그대로 담는다 (다른 프로젝트 없음, 상속 없는 모델도 형식 9)")
    void snapshotPreservesEveryMapping() {
        Scenario s = scenario("pv-snapshot", true);
        GenerationFixture f = s.f();
        // 무효 매핑 1 : 상속 해제된 별도 계층 (루트 → 갑·을) — 매핑은 남는다
        long root = entity(f, "루트");
        long rootNo = f.attr(root, "루트 번호", true, "ROOT_NO");
        f.pk(root, rootNo);
        long gap = entity(f, "갑");
        long eul = entity(f, "을");
        LogicalSubtypeDto.Group detached = group(f, root, "루트 구분", gap, eul);
        generate(f, null, root, gap, eul);
        subtypes.deleteGroup(f.user, detached.id(), subtypes.selectGroup(f.user, detached.id()).modifiedAt());
        // 다른 프로젝트 (같은 구조) 의 매핑이 섞이지 않는다
        Scenario other = scenario("pv-snapshot-other", false);
        AccountDto user = f.member();
        Set<String> before = dbTriples(user.getProjectId());
        assertThat(before).hasSize(2 /* 기업·개인 */ + 4 /* 조직·사람 */ + 1 /* 부서(부분) */ + 2 /* 팀 */ + 2 /* 갑·을(무효) */);
        long version = versions.create(user, new ModelVersionDto.Create("v-snapshot", null)).id();
        assertThat(jdbc.queryForObject("select FRMT_VER_NO from TB_MDL_VER where MDL_VER_SN = ?", Integer.class, version)).isEqualTo(10);
        JsonNode keys = snapshotItems(user, version, "SUBTYPE_KEY_MAPPING");
        assertThat(keyTriples(keys)).isEqualTo(before).doesNotContainAnyElementsOf(dbTriples(other.f().user.getProjectId()));
        // 같은 루트 속성(고객 번호)을 기업·개인이 각자 다른 매핑·컬럼으로 쓴다
        assertThat(keys.valueStream().filter(k -> k.path("state").path("attributeId").asLong() == s.customerNo()).map(k -> k.path("state").path("mappingId").asLong()).toList())
                .containsExactlyInAnyOrder(mappingId(s.company()), mappingId(s.person()));
        // 직접 속성 매핑과 따로 담긴다 (같은 컬럼이 두 종류에 없다)
        Set<Long> directColumns = new HashSet<>();
        snapshotItems(user, version, "COLUMN_MAPPING").forEach(c -> directColumns.add(c.path("state").path("columnId").asLong()));
        keys.forEach(k -> assertThat(directColumns).doesNotContain(k.path("state").path("columnId").asLong()));
        // 버전을 만든 뒤 매핑을 바꿔도 저장된 버전은 그대로
        String stored = jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, version);
        mappings.deleteSubtypeKeyMapping(f.user, jdbc.queryForObject("select SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ?", Long.class, mappingId(gap)), null);
        assertThat(jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, version)).isEqualTo(stored);
        assertThat(keyTriples(snapshotItems(user, version, "SUBTYPE_KEY_MAPPING"))).isEqualTo(before);

        // 상속이 없는 모델 : 형식 10 (M-7 상속 A·C), 상속 PK 매핑 없음
        GenerationFixture plain = SubtypePhysicalGenerationIntegrationTest.fixture(context, "pv-snapshot-plain");
        long only = entity(plain, "상품");
        plain.pk(only, plain.attr(only, "상품 번호", true, "PRDT_NO"));
        generate(plain, null, only);
        AccountDto plainUser = plain.member();
        long plainVersion = versions.create(plainUser, new ModelVersionDto.Create("v-plain", null)).id();
        assertThat(jdbc.queryForObject("select FRMT_VER_NO from TB_MDL_VER where MDL_VER_SN = ?", Integer.class, plainVersion)).isEqualTo(10);
        assertThat(snapshotItems(plainUser, plainVersion, "SUBTYPE_KEY_MAPPING").size()).isZero();
    }

    @Test
    @DisplayName("스냅샷 8 호환 : 형식 8 버전(상속 PK 매핑 종류 없음)은 읽기·비교에서 고치거나 다시 저장하지 않는다. 8 ↔ 9 비교는 기존 규칙대로 지금 매핑이 '추가', 9 ↔ 9 같은 내용은 변경 0, ERD 비교도 된다")
    void snapshot8Compatibility() {
        Scenario s = scenario("pv-v8", false);
        AccountDto user = s.f().member();
        long v9 = versions.create(user, new ModelVersionDto.Create("v9-a", null)).id();
        // 3차 이전에 만든 형식 8 버전과 같은 모양 : 상속 PK 매핑 종류가 없다 (나머지는 같다)
        ObjectNode content = (ObjectNode) JSON.readTree(jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, v9));
        int keys = content.path("SUBTYPE_KEY_MAPPING").size();
        assertThat(keys).isEqualTo(10);
        content.remove("SUBTYPE_KEY_MAPPING");
        ObjectNode counts = (ObjectNode) JSON.readTree(jdbc.queryForObject("select MDL_CNT_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, v9));
        counts.remove("SUBTYPE_KEY_MAPPING");
        long v8 = GenerationFixture.nextId();
        jdbc.update("insert into TB_MDL_VER(MDL_VER_SN, PRJ_SN, VER_NM, VER_EXPLN, FRMT_VER_NO, MDL_CNT_CN, MDL_CN, RGTR_ID, REG_DT) values (?, ?, 'v8-legacy', null, 8, ?, ?, 'test', current_timestamp)",
                v8, user.getProjectId(), JSON.writeValueAsString(counts), JSON.writeValueAsString(content));
        String stored8 = jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, v8);

        assertThat(versions.snapshot(user, v8).objects().has("SUBTYPE_KEY_MAPPING")).as("없던 종류를 추정해 넣지 않는다").isFalse();
        assertThat(versions.detail(user, v8).formatVersion()).isEqualTo(8);
        ModelVersionDto.Comparison eightToNine = versions.compare(user, v8, v9);
        assertThat(eightToNine.byType().get("SUBTYPE_KEY_MAPPING")).containsEntry("ADDED", keys);
        assertThat(eightToNine.changes()).allSatisfy(c -> assertThat(c.targetType()).isEqualTo("SUBTYPE_KEY_MAPPING"));
        assertThat(versions.compare(user, v8, null).byType().get("SUBTYPE_KEY_MAPPING")).containsEntry("ADDED", keys);
        assertThat(versions.compare(user, v9, v8).byType().get("SUBTYPE_KEY_MAPPING")).containsEntry("REMOVED", keys);
        assertThat(versions.compareErd(user, v8, v9, "PHYSICAL", null, null, null)).isNotNull();
        long v9b = versions.create(user, new ModelVersionDto.Create("v9-b", null)).id();
        assertThat(versions.compare(user, v9, v9b).changes()).as("같은 내용의 9 ↔ 9").isEmpty();
        assertThat(versions.compare(user, v9, null).changes()).isEmpty();
        // 읽고 비교해도 형식 8 버전은 그대로 (자동 재저장 없음)
        assertThat(jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, v8)).isEqualTo(stored8);
        assertThat(jdbc.queryForObject("select FRMT_VER_NO from TB_MDL_VER where MDL_VER_SN = ?", Integer.class, v8)).isEqualTo(8);
    }

    @Test
    @DisplayName("버전 비교 : 상속 PK 매핑 추가·해제·컬럼 변경, 원본 식별자 변경, 직접 매핑과 따로 셈, 복합 PK 일부 변화, 엔터티 매핑 추가. 지우고 같은 값으로 다시 만들면 내부 ID 기준이라 삭제 + 추가 (기존 비교 계약)")
    void versionComparison() {
        Scenario s = scenario("pv-compare", false);
        GenerationFixture f = s.f();
        AccountDto user = f.member();
        long v1 = versions.create(user, new ModelVersionDto.Create("c1", null)).id();
        long deptMapping = mappingId(s.dept());
        long orgCdKey = jdbc.queryForObject("select SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ? and LGCL_ATRB_SN = ?", Long.class, deptMapping, s.orgCd());
        long orgCdColumn = jdbc.queryForObject("select COL_SN from TB_SBTYP_IDNTFR_COL_MPNG where SBTYP_IDNTFR_COL_MPNG_SN = ?", Long.class, orgCdKey);
        // 기업의 직접 속성 매핑을 풀어 그 컬럼(BIZ_NO)을 상속 PK 매핑의 새 컬럼으로 쓴다 (변경)
        long companyTable = f.table(s.company());
        long bizColumn = column(companyTable, "BIZ_NO");
        mappings.deleteAttributeMapping(f.user, jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, bizColumn), null);
        long companyKey = jdbc.queryForObject("select SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ?", Long.class, mappingId(s.company()));
        mappings.saveSubtypeKeyMapping(f.user, new ModelMappingDto.SubtypeKeyMapping(companyKey, null, null, bizColumn));
        mappings.deleteSubtypeKeyMapping(f.user, orgCdKey, null); // 복합 PK 일부 해제
        long v2 = versions.create(user, new ModelVersionDto.Create("c2", null)).id();
        ModelVersionDto.Comparison c = versions.compare(user, v1, v2);
        assertThat(c.byType().get("SUBTYPE_KEY_MAPPING")).containsEntry("MODIFIED", 1).containsEntry("REMOVED", 1).containsEntry("ADDED", 0);
        assertThat(c.changes()).filteredOn(x -> x.targetType().equals("SUBTYPE_KEY_MAPPING") && x.change().equals("MODIFIED")).singleElement()
                .satisfies(x -> assertThat(x.changedFields()).containsExactly("columnId"));
        assertThat(c.byType().get("COLUMN_MAPPING")).as("직접 매핑 해제는 직접 매핑 종류로만").containsEntry("REMOVED", 1);
        // 같은 값으로 다시 등록 : 새 행이라 내부 ID 가 달라 '추가' (지운 것은 v2 → v3 에서 이미 없다)
        mappings.saveSubtypeKeyMapping(f.user, new ModelMappingDto.SubtypeKeyMapping(null, deptMapping, s.orgCd(), orgCdColumn));
        long v3 = versions.create(user, new ModelVersionDto.Create("c3", null)).id();
        ModelVersionDto.Comparison c13 = versions.compare(user, v1, v3);
        assertThat(c13.byType().get("SUBTYPE_KEY_MAPPING")).containsEntry("REMOVED", 1).containsEntry("ADDED", 1).containsEntry("MODIFIED", 1);
        // 원본 식별자 변경 : 상속 PK 매핑 행은 그대로 (식별자 종류의 변경으로 보인다), 엔터티 매핑 추가는 TABLE_MAPPING 으로
        Set<String> beforeIdentifier = dbTriples(user.getProjectId());
        long extra = f.attr(s.customer(), "고객 구분 코드", true, "CUST_SE_CD");
        identifier(f, s.customer(), s.customerNo(), extra);
        long extraEntity = entity(f, "외국");
        f.pk(extraEntity, f.attr(extraEntity, "외국 번호", true, "FRGN_NO"));
        generate(f, null, extraEntity);
        long v4 = versions.create(user, new ModelVersionDto.Create("c4", null)).id();
        ModelVersionDto.Comparison c34 = versions.compare(user, v3, v4);
        assertThat(c34.byType().get("SUBTYPE_KEY_MAPPING").values()).as("식별자 변경은 매핑 행을 바꾸지 않는다").allMatch(n -> n == 0 || c34.byType().get("SUBTYPE_KEY_MAPPING").get("UNCHANGED").equals(n));
        assertThat(c34.byType().get("LOGICAL_IDENTIFIER")).containsEntry("MODIFIED", 1);
        assertThat(c34.byType().get("TABLE_MAPPING")).containsEntry("ADDED", 1);
        assertThat(dbTriples(user.getProjectId())).isEqualTo(beforeIdentifier);
    }

    // ------------------------------------------------------------------ 교환 6 · 복사

    @Test
    @DisplayName("교환 6 왕복(= 다른 프로젝트로 복사) : 상속 그룹·루트 식별자·직접 매핑·상속 PK 매핑(같은 루트 속성의 여러 서브타입 구분)·물리 PK/FK·용어가 논리적으로 같고, 새 ID 는 모두 대상 프로젝트 것, 원본·다른 프로젝트 그대로, 미승인으로, IMPORT 이력·직전 버전, 다시 내보내도 같다. 가져온 뒤 판정·기존 부모 재사용도 같다")
    void exchangeRoundTrip() {
        Scenario s = scenario("pv-roundtrip", true);
        GenerationFixture f = s.f();
        AccountDto source = f.member();
        // 원본 객체를 승인해 둔다 (가져오면 미승인이어야 한다)
        jdbc.update("update TB_TBL set APRV_YN = true where PRJ_SN = ?", source.getProjectId());
        ModelExchangeDto.Document document = exchange.export(source);
        assertThat(document.formatVersion()).isEqualTo(7);
        assertThat(document.model().get("SUBTYPE_KEY_MAPPING")).hasSize(9);
        assertThat(document.model().get("COLUMN_MAPPING")).as("상속 PK 매핑은 직접 매핑 목록에 섞이지 않는다")
                .noneMatch(c -> document.model().get("SUBTYPE_KEY_MAPPING").stream().anyMatch(k -> Objects.equals(k.state().get("columnId"), c.state().get("columnId"))));
        Set<String> sourceKeys = keyRows(source.getProjectId());
        Set<String> sourceDirect = directRows(source.getProjectId());
        Set<String> sourceStructure = structure(source.getProjectId());
        Scenario bystander = scenario("pv-roundtrip-bystander", false);
        Set<String> bystanderKeys = keyRows(bystander.f().user.getProjectId());
        String sourceFile = JSON.writeValueAsString(document.model());

        GenerationFixture t = GenerationFixture.create(context, "pv-roundtrip-dst-" + System.nanoTime(), DbType.POSTGRESQL);
        AccountDto target = t.member();
        copyDictionary(source.getProjectId(), target.getProjectId()); // 교환은 표준 용어를 이름으로만 잇는다 — 대상에 같은 용어가 있어야 연결이 보존된다
        int historyBefore = t.count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", target.getProjectId());
        ModelExchangeDto.Result result = importAll(target, document);
        assertThat(result.created()).containsEntry("SUBTYPE_KEY_MAPPING", 9);
        assertThat(keyRows(target.getProjectId())).isEqualTo(sourceKeys).hasSize(9);
        assertThat(keyCount(target.getProjectId())).as("격리 조건 없이 센 수 = 논리 키 수 (다른 프로젝트·다른 테이블 참조 없음)").isEqualTo(9);
        assertThat(directRows(target.getProjectId())).isEqualTo(sourceDirect);
        assertThat(structure(target.getProjectId())).isEqualTo(sourceStructure);
        assertThat(keyRows(source.getProjectId())).isEqualTo(sourceKeys);
        assertThat(keyRows(bystander.f().user.getProjectId())).isEqualTo(bystanderKeys);
        assertThat(t.count("select count(*) from TB_TBL where PRJ_SN = ? and APRV_YN = true", target.getProjectId())).as("승인 상태를 이어받지 않는다").isZero();
        assertThat(t.count("select count(*) from TB_LGCL_ENTT where PRJ_SN = ? and APRV_YN = true", target.getProjectId())).isZero();
        assertThat(t.count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'SUBTYPE_KEY_MAPPING' and CHG_TYPE_CD = 'CREATE' and CHG_SRC_CD = 'IMPORT'",
                target.getProjectId())).isEqualTo(9);
        assertThat(t.count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", target.getProjectId())).isGreaterThan(historyBefore);
        assertThat(result.modelVersionName()).startsWith("MODEL-IMPORT-");
        // 같은 판정 : 원본과 가져온 쪽의 서브타입별 상태가 같다
        for (String name : List.of("기업 고객", "개인 고객", "조직", "사람", "부서", "팀")) {
            long src = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG m join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.LGCL_ENTT_SN where e.PRJ_SN = ? and e.ENTT_NM = ?",
                    Long.class, source.getProjectId(), name);
            long dst = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG m join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.LGCL_ENTT_SN where e.PRJ_SN = ? and e.ENTT_NM = ?",
                    Long.class, target.getProjectId(), name);
            assertThat(mappings.selectSubtypeKeys(target, dst).status()).as(name).isEqualTo(mappings.selectSubtypeKeys(source, src).status());
        }
        // 다시 내보내도 같은 내용 (번호·내보낸 시각 제외 — 논리 키로 비교)
        ModelExchangeDto.Document again = exchange.export(target);
        assertThat(again.model().get("SUBTYPE_KEY_MAPPING")).hasSize(9);
        assertThat(canonical(again)).isEqualTo(canonical(document));
        // 기존 부모 재사용 : 가져온 쪽에 새 손자(조직 아래)를 만들 때 조직·당사자를 재사용
        long importedOrg = jdbc.queryForObject("select LGCL_ENTT_SN from TB_LGCL_ENTT where PRJ_SN = ? and ENTT_NM = '조직'", Long.class, target.getProjectId());
        long importedModel = jdbc.queryForObject("select LGCL_MDL_SN from TB_LGCL_ENTT where LGCL_ENTT_SN = ?", Long.class, importedOrg);
        long importedTeamGroup = jdbc.queryForObject("select LGCL_SBTYP_GROUP_SN from TB_LGCL_SBTYP_GROUP where PRJ_SN = ? and SPRTYP_ENTT_SN = ?", Long.class, target.getProjectId(), importedOrg);
        assertThat(importedModel).isPositive();
        assertThat(importedTeamGroup).isPositive();
        // 조직 아래 새 그룹(지점·배송 — 미생성)을 만들고 그 하나를 미리보기
        long branch = logical.saveEntity(target, new LogicalModelDto.Entity(null, importedModel, "지점", null, null, null, null)).id();
        long delivery = logical.saveEntity(target, new LogicalModelDto.Entity(null, importedModel, "배송", null, null, null, null)).id();
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN in (?, ?)", branch, delivery);
        subtypes.saveGroup(target, new LogicalSubtypeDto.Save(null, importedModel, importedOrg, "조직 구분 2", null, true, false, null,
                List.of(new MemberInput(branch, null), new MemberInput(delivery, null)), null));
        PhysicalGenerationDto.Plan reuse = generation.preview(target, new PhysicalGenerationDto.Request(List.of(branch), null, null));
        assertThat(reuse.creatable()).isTrue();
        assertThat(reuse.ancestors()).extracting(PhysicalGenerationDto.AncestorUse::state).containsOnly("REUSE");

        assertThat(JSON.writeValueAsString(document.model())).as("내보낸 파일 자체는 바뀌지 않는다").isEqualTo(sourceFile);
    }

    /** 원본 프로젝트의 표준 도메인·용어를 대상 프로젝트에 같은 이름으로 만든다 (교환은 용어를 이름으로 잇는다) */
    private void copyDictionary(Long from, Long to) {
        Map<Long, Long> domains = new HashMap<>();
        for (Map<String, Object> d : jdbc.queryForList("select * from TB_STD_DMN where PRJ_SN = ?", from)) {
            long id = GenerationFixture.nextId();
            domains.put(((Number) d.get("STD_DMN_SN")).longValue(), id);
            jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", id, to, d.get("DMN_GROUP_NM"), d.get("DMN_CLSF_NM"),
                    d.get("DMN_NM"), d.get("DMN_EXPLN"), d.get("DATA_TYPE_NM"), d.get("DATA_LEN"), d.get("DATA_DCPT_LEN"));
        }
        for (Map<String, Object> t : jdbc.queryForList("select * from TB_STD_TRM where PRJ_SN = ?", from)) {
            jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), to, t.get("TRM_NM"), t.get("TRM_EXPLN"),
                    t.get("TRM_ENG_ABBR_NM"), t.get("STD_DMN_SN") == null ? null : domains.get(((Number) t.get("STD_DMN_SN")).longValue()));
        }
    }

    /** 교환 파일의 논리 표현 : 객체 번호를 이름 경로로 바꾼 상속 PK 매핑·직접 매핑·그룹 구성 (번호·시각과 무관) */
    private static Set<String> canonical(ModelExchangeDto.Document d) {
        Map<Long, String> entities = new HashMap<>();
        d.model().get("LOGICAL_ENTITY").forEach(e -> entities.put(e.id(), String.valueOf(e.state().get("entityName"))));
        Map<Long, String> attributes = new HashMap<>();
        d.model().get("LOGICAL_ATTRIBUTE").forEach(a -> attributes.put(a.id(), entities.get(num(a.state().get("entityId"))) + "." + a.state().get("attributeName")));
        Map<Long, String> tables = new HashMap<>();
        d.model().get("PHYSICAL_TABLE").forEach(t -> tables.put(t.id(), String.valueOf(t.state().get("tableName"))));
        Map<Long, String> columns = new HashMap<>();
        d.model().get("PHYSICAL_COLUMN").forEach(c -> columns.put(c.id(), tables.get(num(c.state().get("tableInfoId"))) + "." + c.state().get("columnName") + (Boolean.TRUE.equals(c.state().get("isPk")) ? "*" : "")));
        Map<Long, String> tableMappings = new HashMap<>();
        d.model().get("TABLE_MAPPING").forEach(m -> tableMappings.put(m.id(), entities.get(num(m.state().get("entityId"))) + "=" + tables.get(num(m.state().get("tableId")))));
        Set<String> result = new TreeSet<>();
        d.model().getOrDefault("SUBTYPE_KEY_MAPPING", List.of()).forEach(k -> result.add("K " + tableMappings.get(num(k.state().get("mappingId"))) + " " + attributes.get(num(k.state().get("attributeId")))
                + ">" + columns.get(num(k.state().get("columnId")))));
        d.model().getOrDefault("COLUMN_MAPPING", List.of()).forEach(c -> result.add("C " + tableMappings.get(num(c.state().get("mappingId"))) + " " + attributes.get(num(c.state().get("attributeId")))
                + ">" + columns.get(num(c.state().get("columnId")))));
        tableMappings.values().forEach(v -> result.add("M " + v));
        columns.values().forEach(v -> result.add("COL " + v));
        d.model().getOrDefault("SUBTYPE_GROUP", List.of()).forEach(g -> {
            List<String> members = new ArrayList<>();
            ((List<?>) g.state().get("subtypes")).forEach(m -> members.add(entities.get(num(((Map<?, ?>) m).get("subtypeEntityId")))));
            Collections.sort(members);
            result.add("G " + entities.get(num(g.state().get("supertypeEntityId"))) + " " + g.state().get("groupName") + " " + members);
        });
        return result;
    }

    private static Long num(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    @Test
    @DisplayName("복사 시 유효하지 않은 기존 상속 PK 매핑 : 내보내기에는 그대로 담기지만(조용히 빠지지 않음) 가져오기 검사(3차 계약)가 파일 전체를 거절한다 — 아무것도 만들지 않는다. 부분 매핑은 그대로 복사된다")
    void invalidMappingsBlockCopy() {
        Scenario s = scenario("pv-invalid-copy", true);
        GenerationFixture f = s.f();
        AccountDto source = f.member();
        // 부분 매핑만 있는 파일은 복사된다 (부서 1/2)
        GenerationFixture partialTarget = GenerationFixture.create(context, "pv-partial-dst-" + System.nanoTime(), DbType.POSTGRESQL);
        importAll(partialTarget.member(), exchange.export(source));
        assertThat(keyRows(partialTarget.user.getProjectId())).filteredOn(k -> k.startsWith("부서:")).hasSize(1);
        // 루트 식별자 변경으로 지금 주 식별자가 아닌 기존 매핑이 생긴다 (보존 — 3차 M7-D40)
        long extra = f.attr(s.customer(), "고객 구분 코드", true, "CUST_SE_CD");
        identifier(f, s.customer(), extra);
        ModelExchangeDto.Document document = exchange.export(source);
        assertThat(document.model().get("SUBTYPE_KEY_MAPPING")).as("무효 매핑도 내보내기에 담긴다").hasSize(9);
        GenerationFixture t = GenerationFixture.create(context, "pv-invalid-dst-" + System.nanoTime(), DbType.POSTGRESQL);
        AccountDto target = t.member();
        List<Integer> before = footprint();
        assertThatThrownBy(() -> exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), document, null)))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID);
                    assertThat(e.getMessage()).contains("주 식별자");
                });
        assertThat(footprint()).isEqualTo(before);
        // 상속이 해제된 엔터티의 남은 매핑도 같다 (서브타입이 아님)
        identifier(f, s.customer(), s.customerNo());
        LogicalSubtypeDto.Group customerGroup = subtypes.selectGroupRows(f.user, f.modelId, null, 0, 10, null).getContent().stream()
                .filter(g -> g.groupName().equals("고객 구분")).findFirst().map(g -> subtypes.selectGroup(f.user, g.id())).orElseThrow();
        subtypes.deleteGroup(f.user, customerGroup.id(), customerGroup.modifiedAt());
        ModelExchangeDto.Document detached = exchange.export(source);
        List<Integer> beforeDetached = footprint();
        assertThatThrownBy(() -> exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), detached, null)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getMessage()).contains("서브타입이 아닙니다"));
        assertThat(footprint()).isEqualTo(beforeDetached);
    }

    // ------------------------------------------------------------------ 하위 호환 · 위장 · 잘못된 참조

    /** 형식 n 이 담을 수 있는 것만 남긴 파일 (1 : 기본, 2 : +VIEW·배치, 3 : +관계 속성 짝, 4 : +주제 영역, 5 : +서브타입 그룹, 6 : +상속 PK 매핑) */
    private static ModelExchangeDto.Document downgrade(ModelExchangeDto.Document d, int version) {
        Map<String, List<Item>> model = new LinkedHashMap<>(d.model());
        if (version < 6) model.remove("SUBTYPE_KEY_MAPPING");
        if (version < 5) model.remove("SUBTYPE_GROUP");
        if (version < 4) { model.remove("LOGICAL_SUBJECT_AREA"); model.remove("SUBJECT_AREA_ENTITY"); }
        if (version < 3) {
            model.put("LOGICAL_RELATIONSHIP", model.getOrDefault("LOGICAL_RELATIONSHIP", List.of()).stream().map(r -> {
                Map<String, Object> state = new LinkedHashMap<>(r.state());
                state.remove("attributes");
                return new Item(r.id(), r.name(), state);
            }).toList());
        }
        if (version < 2) model.remove("PHYSICAL_VIEW");
        return new ModelExchangeDto.Document(d.format(), version, d.exportedAt(), d.dbType(), null, model, version < 2 ? null : d.layouts());
    }

    private static ModelExchangeDto.Document relabel(ModelExchangeDto.Document d, int version) {
        return new ModelExchangeDto.Document(d.format(), version, d.exportedAt(), d.dbType(), d.counts(), d.model(), d.layouts());
    }

    @Test
    @DisplayName("형식 1~5 가져오기 : 각 형식이 담을 수 있는 것을 그대로 가져오고 상속 PK 매핑을 추정해 만들지 않는다 (5 는 그룹까지). 위장 : 낮은 번호에 새 종류(상속 PK 매핑·그룹·주제 영역·속성 짝·배치)를 넣은 파일과 번호만 낮춘 파일은 전체 거절")
    void legacyFormatsAndDisguise() {
        Scenario s = scenario("pv-legacy", false);
        AccountDto source = s.f().member();
        // 속성 짝 (형식 3) 이 있는 관계 하나
        LogicalModelDto.Relationship rel = s.f().rel("상품 고객", s.customer(), s.product(), false, 0);
        long productCustomer = s.f().attr(s.product(), "상품 고객 번호", false, "PRDT_CUST_NO");
        s.f().pairs(rel, new long[]{s.customerNo(), productCustomer});
        ModelExchangeDto.Document document = exchange.export(source);
        for (int version = 1; version <= 5; version++) {
            ModelExchangeDto.Document old = downgrade(document, version);
            GenerationFixture t = GenerationFixture.create(context, "pv-legacy-" + version + "-" + System.nanoTime(), DbType.POSTGRESQL);
            AccountDto target = t.member();
            ModelExchangeDto.Result result = importAll(target, old);
            assertThat(keyCount(target.getProjectId())).as("형식 " + version + " : 상속 PK 매핑을 만들지 않는다").isZero();
            assertThat(result.created()).doesNotContainKey("SUBTYPE_KEY_MAPPING");
            assertThat(t.count("select count(*) from TB_LGCL_SBTYP_GROUP where PRJ_SN = ?", target.getProjectId())).as("형식 " + version).isEqualTo(version >= 5 ? 3 : 0);
            assertThat(t.count("select count(*) from TB_LGCL_REL_ATRB x join TB_LGCL_REL r on r.LGCL_REL_SN = x.LGCL_REL_SN where r.PRJ_SN = ?", target.getProjectId()))
                    .as("형식 " + version + " 속성 짝").isEqualTo(version >= 3 ? 1 : 0);
            assertThat(directRows(target.getProjectId())).as("직접 매핑은 모든 형식에서").isEqualTo(directRows(source.getProjectId()));
            // 가져온 뒤 조회·비교가 된다
            long v = versions.create(target, new ModelVersionDto.Create("after-import", null)).id();
            assertThat(versions.compare(target, v, null).changes()).isEmpty();
        }
        GenerationFixture refused = GenerationFixture.create(context, "pv-disguise-" + System.nanoTime(), DbType.POSTGRESQL);
        AccountDto r = refused.member();
        List<Integer> before = footprint();
        Map<String, ModelExchangeDto.Document> bad = new LinkedHashMap<>();
        bad.put("형식 5 + 상속 PK 매핑", relabel(document, 5));
        bad.put("형식 4 + 그룹", relabel(downgrade(document, 5), 4));
        bad.put("형식 3 + 주제 영역", withArea(downgrade(document, 3)));
        bad.put("형식 2 + 속성 짝", relabel(downgrade(document, 3), 2));
        bad.put("형식 1 + 배치", withLayouts(downgrade(document, 1), document));
        bad.put("형식 0", relabel(document, 0));
        bad.put("형식 8", relabel(document, 8));
        bad.put("필수 참조 없음 (mappingId)", withoutField(document, "SUBTYPE_KEY_MAPPING", "mappingId"));
        bad.put("필수 참조 없음 (columnId)", withoutField(document, "SUBTYPE_KEY_MAPPING", "columnId"));
        bad.put("알 수 없는 종류", withType(document, "SUBTYPE_KEY_MAPPING_V7"));
        for (Map.Entry<String, ModelExchangeDto.Document> entry : bad.entrySet()) {
            assertThatThrownBy(() -> exchange.plan(r, new ModelExchangeDto.PlanRequest(r.getProjectId(), entry.getValue(), null))).as(entry.getKey())
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID));
        }
        assertThat(footprint()).isEqualTo(before);
    }

    private static ModelExchangeDto.Document withArea(ModelExchangeDto.Document d) {
        Map<String, List<Item>> model = new LinkedHashMap<>(d.model());
        Long modelId = model.get("LOGICAL_MODEL").getFirst().id();
        model.put("LOGICAL_SUBJECT_AREA", List.of(new Item(777L, "영역", new LinkedHashMap<>(Map.of("modelId", modelId, "areaName", "영역", "sortOrder", 1)))));
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), null, model, d.layouts());
    }

    private static ModelExchangeDto.Document withLayouts(ModelExchangeDto.Document d, ModelExchangeDto.Document from) {
        ModelExchangeDto.Layouts layouts = from.layouts() != null ? from.layouts() : new ModelExchangeDto.Layouts(null, List.of(), List.of());
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), null, d.model(), layouts);
    }

    private static ModelExchangeDto.Document withoutField(ModelExchangeDto.Document d, String type, String field) {
        Map<String, List<Item>> model = new LinkedHashMap<>(d.model());
        List<Item> items = new ArrayList<>(model.get(type));
        Item first = items.getFirst();
        Map<String, Object> state = new LinkedHashMap<>(first.state());
        state.remove(field);
        items.set(0, new Item(first.id(), first.name(), state));
        model.put(type, items);
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), null, model, null);
    }

    private static ModelExchangeDto.Document withType(ModelExchangeDto.Document d, String type) {
        Map<String, List<Item>> model = new LinkedHashMap<>(d.model());
        model.put(type, List.of(new Item(1L, "x", Map.of("a", 1))));
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), null, model, null);
    }

    /** 상속 PK 매핑 하나의 필드 하나를 바꾼 파일 */
    private static ModelExchangeDto.Document withKeyField(ModelExchangeDto.Document d, Function<Item, Boolean> which, String field, Object value) {
        Map<String, List<Item>> model = new LinkedHashMap<>(d.model());
        boolean[] done = {false};
        model.put("SUBTYPE_KEY_MAPPING", model.get("SUBTYPE_KEY_MAPPING").stream().map(item -> {
            if (done[0] || !which.apply(item)) return item;
            done[0] = true;
            Map<String, Object> state = new LinkedHashMap<>(item.state());
            state.put(field, value);
            return new Item(item.id(), item.name(), state);
        }).toList());
        assertThat(done[0]).isTrue();
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), null, model, null);
    }

    @Test
    @DisplayName("잘못된 상속 PK 매핑 참조 14종 : 없는 엔터티 매핑·속성·컬럼, 다른 테이블 컬럼, 다른 논리 모델 속성, 루트 주 식별자가 아닌 속성, 다른 계층의 루트 속성, (매핑, 속성) 중복, 컬럼 중복, 직접 매핑과 교차, 서브타입이 아닌 엔터티의 매핑, "
            + "같은 루트 속성을 다른 서브타입 문맥 컬럼에, 복합 PK 구성원 섞음, 끊어진 상속 계층 — 모두 파일 전체 거절, 어느 프로젝트에도 아무것도 남지 않는다")
    void invalidReferencesRejected() {
        Scenario s = scenario("pv-invalid", false);
        GenerationFixture f = s.f();
        // 같은 프로젝트의 다른 논리 모델 (속성 하나)
        GenerationFixture second = f.otherModel("다른 모델");
        long otherModelEntity = entity(second, "외국");
        long otherModelAttribute = second.attr(otherModelEntity, "외국 번호", true, "FRGN_NO");
        AccountDto source = f.member();
        ModelExchangeDto.Document d = exchange.export(source);
        Map<Long, Item> tableMappings = new HashMap<>();
        d.model().get("TABLE_MAPPING").forEach(m -> tableMappings.put(m.id(), m));
        Function<Long, Long> tableOfMapping = m -> num(tableMappings.get(m).state().get("tableId"));
        Map<Long, Long> mappingOfEntity = new HashMap<>();
        d.model().get("TABLE_MAPPING").forEach(m -> mappingOfEntity.put(num(m.state().get("entityId")), m.id()));
        Item companyKey = d.model().get("SUBTYPE_KEY_MAPPING").stream().filter(k -> num(k.state().get("mappingId")).equals(mappingOfEntity.get(s.company()))).findFirst().orElseThrow();
        Item personKey = d.model().get("SUBTYPE_KEY_MAPPING").stream().filter(k -> num(k.state().get("mappingId")).equals(mappingOfEntity.get(s.person()))).findFirst().orElseThrow();
        Item deptPartyNo = d.model().get("SUBTYPE_KEY_MAPPING").stream().filter(k -> num(k.state().get("mappingId")).equals(mappingOfEntity.get(s.dept()))
                && num(k.state().get("attributeId")).equals(s.partyNo())).findFirst().orElseThrow();
        Long productTableColumn = d.model().get("PHYSICAL_COLUMN").stream().filter(c -> !num(c.state().get("tableInfoId")).equals(tableOfMapping.apply(mappingOfEntity.get(s.company()))))
                .findFirst().orElseThrow().id();
        Long customerName = d.model().get("LOGICAL_ATTRIBUTE").stream().filter(a -> "고객 이름".equals(a.state().get("attributeName"))).findFirst().orElseThrow().id();
        Long companyBizColumn = d.model().get("COLUMN_MAPPING").stream().filter(c -> num(c.state().get("mappingId")).equals(mappingOfEntity.get(s.company()))).findFirst()
                .map(c -> num(c.state().get("columnId"))).orElseThrow();
        Long personColumn = num(personKey.state().get("columnId"));
        Function<Item, Boolean> isCompany = k -> k.id().equals(companyKey.id());

        Map<String, ModelExchangeDto.Document> bad = new LinkedHashMap<>();
        bad.put("없는 엔터티 매핑", withKeyField(d, isCompany, "mappingId", 999_999_991L));
        bad.put("없는 속성", withKeyField(d, isCompany, "attributeId", 999_999_992L));
        bad.put("없는 컬럼", withKeyField(d, isCompany, "columnId", 999_999_993L));
        bad.put("다른 테이블 컬럼", withKeyField(d, isCompany, "columnId", productTableColumn));
        bad.put("다른 논리 모델 속성", withKeyField(d, isCompany, "attributeId", otherModelAttribute));
        bad.put("루트 주 식별자가 아닌 속성", withKeyField(d, isCompany, "attributeId", customerName));
        bad.put("다른 계층의 루트 속성", withKeyField(d, isCompany, "attributeId", s.partyNo()));
        bad.put("(매핑, 속성) 중복", withExtra(d, companyKey, companyKey.state()));
        Map<String, Object> sameColumn = new LinkedHashMap<>(personKey.state());
        sameColumn.put("columnId", num(companyKey.state().get("columnId")));
        bad.put("컬럼 중복 (다른 서브타입 문맥)", withKeyField(d, k -> k.id().equals(personKey.id()), "columnId", num(companyKey.state().get("columnId"))));
        bad.put("직접 매핑과 교차", withKeyField(d, isCompany, "columnId", companyBizColumn));
        bad.put("서브타입이 아닌 엔터티의 매핑", withKeyField(d, isCompany, "mappingId", mappingOfEntity.get(s.customer())));
        bad.put("같은 루트 속성을 다른 서브타입 문맥 컬럼에", withKeyField(d, isCompany, "columnId", personColumn));
        bad.put("복합 PK 구성원 섞음 (부서 매핑에 고객 번호)", withKeyField(d, k -> k.id().equals(deptPartyNo.id()), "attributeId", s.customerNo()));
        bad.put("끊어진 상속 계층 (그룹의 슈퍼타입 없음)", brokenGroup(d));
        GenerationFixture t = GenerationFixture.create(context, "pv-invalid-dst-" + System.nanoTime(), DbType.POSTGRESQL);
        AccountDto target = t.member();
        List<Integer> before = footprint();
        for (Map.Entry<String, ModelExchangeDto.Document> entry : bad.entrySet()) {
            assertThatThrownBy(() -> {
                ModelExchangeDto.Plan plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), entry.getValue(), null));
                exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), entry.getValue(), null, plan.planToken(), plan.requestId()));
            }).as(entry.getKey()).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID));
        }
        assertThat(footprint()).isEqualTo(before);
        assertThat(otherModelEntity).isPositive();
    }

    private static ModelExchangeDto.Document withExtra(ModelExchangeDto.Document d, Item like, Map<String, Object> state) {
        Map<String, List<Item>> model = new LinkedHashMap<>(d.model());
        List<Item> keys = new ArrayList<>(model.get("SUBTYPE_KEY_MAPPING"));
        keys.add(new Item(999_999_990L, like.name(), state));
        model.put("SUBTYPE_KEY_MAPPING", keys);
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), null, model, null);
    }

    private static ModelExchangeDto.Document brokenGroup(ModelExchangeDto.Document d) {
        Map<String, List<Item>> model = new LinkedHashMap<>(d.model());
        List<Item> groups = new ArrayList<>(model.get("SUBTYPE_GROUP"));
        Item g = groups.getFirst();
        Map<String, Object> state = new LinkedHashMap<>(g.state());
        state.put("supertypeEntityId", 999_999_989L);
        groups.set(0, new Item(g.id(), g.name(), state));
        model.put("SUBTYPE_GROUP", groups);
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), null, model, null);
    }

    // ------------------------------------------------------------------ 원자성

    @Test
    @DisplayName("가져오기(= 복사) 실패 원자성 : 직전 버전·논리 모델·속성·테이블·컬럼·엔터티 매핑·직접 매핑·상속 PK 매핑(첫 행 뒤·마지막 행)·커밋 직전 이력에서 실패하면 모든 프로젝트의 행 수가 그대로, 실패 뒤 같은 요청이 그대로 성공")
    void importRollbackAtEveryStage() {
        Scenario s = scenario("pv-rollback", false);
        AccountDto source = s.f().member();
        ModelExchangeDto.Document document = exchange.export(source);
        int keys = document.model().get("SUBTYPE_KEY_MAPPING").size();
        GenerationFixture t = GenerationFixture.create(context, "pv-rollback-dst-" + System.nanoTime(), DbType.POSTGRESQL);
        AccountDto target = t.member();
        ModelExchangeDto.Plan plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), document, null));
        Set<String> sourceKeys = keyRows(source.getProjectId());
        List<Integer> before = footprint();
        Object[][] stages = {
                {ModelVersion.class, 0, "직전 모델 버전"},
                {LogicalModel.class, 0, "논리 모델"},
                {LogicalAttribute.class, 3, "논리 속성"},
                {TableInfo.class, 2, "물리 테이블"},
                {ColumnInfo.class, 5, "물리 컬럼"},
                {EntityTableMapping.class, 2, "엔터티 매핑"},
                {AttributeColumnMapping.class, 1, "직접 속성 매핑"},
                {SubtypeIdentifierColumnMapping.class, 1, "상속 PK 매핑 일부"},
                {SubtypeIdentifierColumnMapping.class, keys - 1, "상속 PK 매핑 마지막"},
                {ModelChangeHistory.class, 0, "커밋 직전 변경 이력"},
        };
        for (Object[] stage : stages) {
            Injector.fired.set(0);
            Injector.skip.set((Integer) stage[1]);
            Injector.target = (Class<?>) stage[0];
            assertThatThrownBy(() -> exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), document, null, plan.planToken(), plan.requestId())))
                    .as((String) stage[2]).isNotNull();
            Injector.target = null;
            assertThat(Injector.fired.get()).as(stage[2] + " : 주입한 실패가 일어났다").isOne();
            assertThat(footprint()).as((String) stage[2]).isEqualTo(before);
        }
        ModelExchangeDto.Result result = exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), document, null, plan.planToken(), plan.requestId()));
        assertThat(result.created()).containsEntry("SUBTYPE_KEY_MAPPING", keys);
        assertThat(keyRows(target.getProjectId())).isEqualTo(sourceKeys);
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    @DisplayName("동시성 : 같은 파일을 같은 대상에 동시에 가져오면 하나만 반영(다른 하나는 409·충돌), 한 벌만 남는다. 상속 PK 매핑 변경이 커밋 전이면 버전은 그 변경 없이 만들어지고 변경은 그 뒤 커밋된다. "
            + "대상 프로젝트의 직접 매핑 변경이 커밋 전이어도 가져오기가 끝나고 둘 다 반영된다 (교착 없음)")
    void concurrency() throws Exception {
        Scenario s = scenario("pv-concurrency", false);
        GenerationFixture f = s.f();
        AccountDto source = f.member();
        ModelExchangeDto.Document document = exchange.export(source);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 1. 같은 파일 · 같은 대상 동시 가져오기 (3회)
            for (int round = 0; round < 3; round++) {
                GenerationFixture t = GenerationFixture.create(context, "pv-conc-dst-" + round + "-" + System.nanoTime(), DbType.POSTGRESQL);
                AccountDto target = t.member();
                ModelExchangeDto.Plan p1 = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), document, null));
                ModelExchangeDto.Plan p2 = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), document, null));
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Object>> results = new ArrayList<>();
                for (ModelExchangeDto.Plan p : List.of(p1, p2)) {
                    results.add(pool.submit(() -> {
                        go.await();
                        SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), target.getProjectId(), target.getId(), "ROLE_ADMIN");
                        try {
                            return exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), document, null, p.planToken(), p.requestId()));
                        } catch (CustomException e) {
                            return e.getErrorCode();
                        } finally {
                            SecurityTestSupport.clear();
                        }
                    }));
                }
                go.countDown();
                List<Object> outcomes = new ArrayList<>();
                for (Future<Object> r : results) outcomes.add(r.get(60, TimeUnit.SECONDS));
                assertThat(outcomes).filteredOn(o -> o instanceof ModelExchangeDto.Result).as(String.valueOf(outcomes)).hasSize(1);
                assertThat(outcomes).filteredOn(o -> o instanceof ErrorCode).hasSize(1);
                assertThat(keyCount(target.getProjectId())).isEqualTo(document.model().get("SUBTYPE_KEY_MAPPING").size());
                assertThat(t.count("select count(*) from TB_LGCL_MDL where PRJ_SN = ?", target.getProjectId())).as("픽스처 모델 + 가져온 모델 하나").isEqualTo(2);
            }

            // 2. 상속 PK 매핑 변경(커밋 전) ↔ 버전 만들기
            long companyMapping = mappingId(s.company());
            long companyKey = jdbc.queryForObject("select SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ?", Long.class, companyMapping);
            long companyColumn = jdbc.queryForObject("select COL_SN from TB_SBTYP_IDNTFR_COL_MPNG where SBTYP_IDNTFR_COL_MPNG_SN = ?", Long.class, companyKey);
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> change = pool.submit(() -> {
                SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), source.getProjectId(), source.getId(), "ROLE_ADMIN");
                try {
                    tx.executeWithoutResult(st -> {
                        mappings.deleteSubtypeKeyMapping(source, companyKey, null);
                        held.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    SecurityTestSupport.clear();
                }
                return null;
            });
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            long version = versions.create(source, new ModelVersionDto.Create("conc-version", null)).id();
            assertThat(keyTriples(snapshotItems(source, version, "SUBTYPE_KEY_MAPPING"))).as("커밋 전 변경은 버전에 없다").contains(companyMapping + ":" + s.customerNo() + ":" + companyColumn);
            release.countDown();
            change.get(60, TimeUnit.SECONDS);
            assertThat(dbTriples(source.getProjectId())).doesNotContain(companyMapping + ":" + s.customerNo() + ":" + companyColumn);
            long after = versions.create(source, new ModelVersionDto.Create("conc-version-after", null)).id();
            assertThat(versions.compare(source, version, after).byType().get("SUBTYPE_KEY_MAPPING")).containsEntry("REMOVED", 1);

            // 3. 대상 프로젝트의 직접 매핑 변경(커밋 전) ↔ 가져오기
            Scenario targetScenario = scenario("pv-conc-target", false);
            GenerationFixture tf = targetScenario.f();
            AccountDto target = tf.member();
            long bizColumn = column(tf.table(targetScenario.company()), "BIZ_NO");
            long direct = jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, bizColumn);
            long bizAttribute = jdbc.queryForObject("select LGCL_ATRB_SN from TB_ATRB_COL_MPNG where ATRB_COL_MPNG_SN = ?", Long.class, direct);
            mappings.deleteAttributeMapping(tf.user, direct, null);
            ModelExchangeDto.Document renamed = renameModel(document, "가져온 모델");
            ModelExchangeDto.Plan plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), renamed, null));
            CountDownLatch heldDirect = new CountDownLatch(1);
            CountDownLatch releaseDirect = new CountDownLatch(1);
            Future<?> directChange = pool.submit(() -> {
                SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), target.getProjectId(), target.getId(), "ROLE_ADMIN");
                try {
                    tx.executeWithoutResult(st -> {
                        mappings.saveAttributeMapping(target, new ModelMappingDto.AttributeMapping(null, mappingId(targetScenario.company()), bizAttribute, bizColumn));
                        heldDirect.countDown();
                        try {
                            releaseDirect.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    SecurityTestSupport.clear();
                }
                return null;
            });
            assertThat(heldDirect.await(30, TimeUnit.SECONDS)).isTrue();
            Future<ModelExchangeDto.Result> imported = pool.submit(() -> {
                SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), target.getProjectId(), target.getId(), "ROLE_ADMIN");
                try {
                    return exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), renamed, null, plan.planToken(), plan.requestId()));
                } finally {
                    SecurityTestSupport.clear();
                }
            });
            Thread.sleep(300);
            releaseDirect.countDown();
            directChange.get(60, TimeUnit.SECONDS);
            ModelExchangeDto.Result result = imported.get(60, TimeUnit.SECONDS);
            assertThat(result.created()).containsKey("SUBTYPE_KEY_MAPPING");
            assertThat(tf.count("select count(*) from TB_ATRB_COL_MPNG where COL_SN = ?", bizColumn)).isOne();
            assertThat(tf.count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG where COL_SN = ?", bizColumn)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    /** 같은 프로젝트에 다시 가져올 수 있게 논리 모델 이름과 TABLE 스키마를 바꾼 파일 (충돌 없이) */
    private static ModelExchangeDto.Document renameModel(ModelExchangeDto.Document d, String name) {
        Map<String, List<Item>> model = new LinkedHashMap<>(d.model());
        model.put("LOGICAL_MODEL", model.get("LOGICAL_MODEL").stream().map(m -> {
            Map<String, Object> state = new LinkedHashMap<>(m.state());
            state.put("modelName", name);
            return new Item(m.id(), name, state);
        }).toList());
        model.put("PHYSICAL_TABLE", model.get("PHYSICAL_TABLE").stream().map(t -> {
            Map<String, Object> state = new LinkedHashMap<>(t.state());
            state.put("tableName", t.state().get("tableName") + "_C");
            return new Item(t.id(), t.name() + "_C", state);
        }).toList());
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), null, model, null);
    }

    // ------------------------------------------------------------------ 파일 크기 (30MB, 바이트 기준 — 컨트롤러가 본문을 읽는 단계)

    private static final int MAX_BYTES = 30 * 1024 * 1024;

    /**
     * 계획 요청 본문을 정확히 totalBytes 바이트로 : 공백(1바이트)은 JSON 끝의 공백으로, 한글(3바이트)은 exportedAt 값 안에 바이트로 끼워 넣는다
     * (큰 문자열·직렬화 버퍼를 만들지 않는다 — 테스트 힙 1GB). 3으로 나눈 나머지는 끝 공백으로 맞춘다
     */
    private static byte[] body(Long target, ModelExchangeDto.Document d, int totalBytes, String fill) {
        ModelExchangeDto.Document empty = new ModelExchangeDto.Document(d.format(), d.formatVersion(), "", d.dbType(), d.counts(), d.model(), d.layouts());
        byte[] base = JSON.writeValueAsBytes(new ModelExchangeDto.PlanRequest(target, empty, null));
        byte[] unit = fill.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = new byte[totalBytes];
        int remaining = totalBytes - base.length;
        if (unit.length == 1) {
            System.arraycopy(base, 0, result, 0, base.length);
            java.util.Arrays.fill(result, base.length, totalBytes, (byte) ' ');
        } else {
            byte[] marker = "\"exportedAt\":\"\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int at = indexOf(base, marker) + marker.length - 1; // 닫는 따옴표 자리
            int units = remaining / unit.length;
            System.arraycopy(base, 0, result, 0, at);
            for (int i = 0; i < units; i++) System.arraycopy(unit, 0, result, at + i * unit.length, unit.length);
            System.arraycopy(base, at, result, at + units * unit.length, base.length - at);
            java.util.Arrays.fill(result, at + units * unit.length + base.length - at, totalBytes, (byte) ' ');
        }
        return result;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        throw new IllegalStateException("marker not found");
    }

    private ModelExchangeDto.Plan planBytes(AccountDto user, byte[] content, Long declaredLength) {
        // 길이 머리글이 실제 본문과 다른 요청 (머리글만 보고 거절하는지)
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/modelExchange/plan") {
            @Override
            public long getContentLengthLong() {
                return declaredLength != null ? declaredLength : super.getContentLengthLong();
            }
        };
        request.setContent(content);
        request.setContentType("application/json");
        return exchangeController.plan(user, request).getBody();
    }

    @Test
    @DisplayName("파일 크기 30MB (바이트 기준) : 정확히 30MB(ASCII·한글 3바이트 채움 모두) 는 읽어 계획하고, 30MB + 1바이트는 거절 — 한글은 글자 수가 30M 보다 훨씬 적어도 바이트로 넘으면 거절, 길이 머리글이 30MB 를 넘으면 읽기 전에 거절. 상속 PK 매핑이 들어간 파일")
    void fileSizeBoundary() {
        // 30MB 본문을 실제로 읽고 풀어 보므로 여유 힙 100MB 정도가 필요하다. H2 전체 실행은 캐시된 모든 테스트 컨텍스트의 메모리 DB 가 같은 1GB 테스트 힙에 있어
        // 모자란다 (Java heap space) — PostgreSQL 전체 실행(DB 가 JVM 밖)에서 돌리고, H2 는 이 클래스만 따로 돌려 확인한다 (LEXORMA_EXCHANGE_SIZE_TEST=true)
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("LEXORMA_TEST_POSTGRES_URL") != null || "true".equals(System.getenv("LEXORMA_EXCHANGE_SIZE_TEST")),
                "30MB 본문 검사는 PostgreSQL 전체 실행 또는 LEXORMA_EXCHANGE_SIZE_TEST=true 에서만");
        Scenario s = scenario("pv-size", false);
        AccountDto user = s.f().member();
        SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), user.getProjectId(), user.getId(), "ROLE_ADMIN");
        ModelExchangeDto.Document document = exchange.export(user);
        assertThat(document.model().get("SUBTYPE_KEY_MAPPING")).isNotEmpty();
        GenerationFixture t = GenerationFixture.create(context, "pv-size-dst-" + System.nanoTime(), DbType.POSTGRESQL);
        AccountDto target = t.member();
        SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), target.getProjectId(), target.getId(), "ROLE_ADMIN");
        List<Integer> before = footprint();
        assertThat(planBytes(target, body(target.getProjectId(), document, MAX_BYTES, " "), null).counts()).containsEntry("SUBTYPE_KEY_MAPPING", 10);
        byte[] korean = body(target.getProjectId(), document, MAX_BYTES, "가");
        assertThat(korean).hasSize(MAX_BYTES);
        assertThat(planBytes(target, korean, null).counts()).containsEntry("SUBTYPE_KEY_MAPPING", 10);
        korean = null;
        for (String fill : List.of(" ", "가")) {
            byte[] over = body(target.getProjectId(), document, MAX_BYTES + 1, fill);
            assertThatThrownBy(() -> planBytes(target, over, null)).as(fill).isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID);
                assertThat(e.getMessage()).contains("30MB");
            });
        }
        byte[] small = JSON.writeValueAsBytes(new ModelExchangeDto.PlanRequest(target.getProjectId(), document, null));
        assertThatThrownBy(() -> planBytes(target, small, (long) MAX_BYTES + 1)).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getMessage()).contains("30MB"));
        assertThat(footprint()).as("계획은 아무것도 저장하지 않는다").isEqualTo(before);
    }
}
