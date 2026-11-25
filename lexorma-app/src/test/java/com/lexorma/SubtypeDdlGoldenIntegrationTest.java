package com.lexorma;

import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.ddl.service.DdlExportService;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-7 3차 DDL 골든 (M7-D41) : 상속 생성 결과를 통합 DDL Export(4개 DBMS)로 내보낸 글자 그대로를 고정한다 (ddl-golden-subtype/*.sql).
 * 단일 상속 PK·FK(자기 속성 없는 서브타입 포함), 복합 상속 PK·FK 3단계, 일반 FK 혼재(서브타입 부모 · 자기 참조 복합), 기존 부모 재사용 + 다른 스키마 참조.
 * 생성은 트리거·CHECK·구분 컬럼을 만들지 않는다 (아래에서도 확인). 기존 골든(ddl-golden/)은 바꾸지 않는다.
 * 골든을 다시 만들 때만 LEXORMA_GOLDEN_WRITE=<저장소의 lexorma-app/src/test/resources 절대 경로> 로 실행한다 (내용을 검토한 뒤 반영).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SubtypeDdlGoldenIntegrationTest {

    private static final List<String> DBMS = List.of("POSTGRESQL", "ORACLE", "MYSQL", "MARIADB");

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired DdlExportService ddlExport;
    @Autowired SchemaInfoRepository schemas;
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

    private void generate(GenerationFixture f, Long schemaId, Long... entities) {
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(entities), schemaId, null));
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(t -> t.errors().stream()).toList())).isTrue();
        generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(entities), schemaId, plan.planToken()));
    }

    private void compare(GenerationFixture f, String scenario) throws IOException {
        String write = System.getenv("LEXORMA_GOLDEN_WRITE");
        for (String db : DBMS) {
            String ddl = ddlExport.export(f.user, null, db, null, false).ddl();
            assertThat(ddl).as(db).doesNotContainIgnoringCase("CREATE TRIGGER").doesNotContain(" CHECK (");
            String name = "ddl-golden-subtype/" + scenario + "_" + db + ".sql";
            if (write != null && !write.isBlank()) {
                Path path = Path.of(write, name);
                Files.createDirectories(path.getParent());
                Files.writeString(path, ddl, StandardCharsets.UTF_8);
                continue;
            }
            try (InputStream in = getClass().getResourceAsStream("/" + name)) {
                assertThat(in).as(name).isNotNull();
                assertThat(ddl).as(name).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    @DisplayName("한 번에 생성 : 단일(고객 → 기업·개인, 개인은 자기 속성 없음)·복합 3단계(당사자 → 조직 → 부서·팀, 사람) 상속 PK·FK + 일반 FK (기업 고객 → 계약, 부서 자기 참조 복합)")
    void oneStep() throws IOException {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "golden-one");
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
        long upperNo = f.attr(dept, "상위 당사자 번호", false, "UPR_PTY_NO");
        long upperCd = f.attr(dept, "상위 기관 코드", false, "UPR_ORG_CD");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", org, human);
        group(f, org, "조직 구분", dept, team);
        long contract = entity(f, "계약");
        f.pk(contract, f.attr(contract, "계약 번호", true, "CNTRT_NO"));
        long owner = f.attr(contract, "계약 기업 고객 번호", true, "CNTRT_CUST_NO");
        f.pairs(f.rel("기업 계약", company, contract, false, 1), new long[]{customerNo, owner});
        f.pairs(f.rel("상위 부서", dept, dept, false, 0), new long[]{partyNo, upperNo}, new long[]{orgCd, upperCd});
        generate(f, null, customer, company, person, party, org, human, dept, team, contract);
        compare(f, "one-step");

        // PostgreSQL 실제 실행 (격리 스키마 — 지정된 검증 DB 에서만) : PK 이면서 FK 인 상속 PK·복합 FK 가 그대로 만들어진다
        JdbcTemplate pg = new JdbcTemplate(TestDatabaseGuard.directDataSource());
        String target = "sub_ddl_" + UUID.randomUUID().toString().replace("-", "");
        pg.execute("create schema " + target);
        try {
            pg.execute(ddlExport.export(f.user, null, "POSTGRESQL", target, false).ddl());
            List<String> fks = pg.queryForList("""
                    select r.relname || '.' || c.conname || ':' || (select string_agg(a.attname, ',' order by k.n) from unnest(c.conkey) with ordinality k(attnum, n)
                                                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum)
                         || '>' || t.relname || '(' || (select string_agg(a.attname, ',' order by k.n) from unnest(c.confkey) with ordinality k(attnum, n)
                                                  join pg_attribute a on a.attrelid = c.confrelid and a.attnum = k.attnum) || ')'
                      from pg_constraint c join pg_namespace s on s.oid = c.connamespace join pg_class r on r.oid = c.conrelid join pg_class t on t.oid = c.confrelid
                     where s.nspname = ? and c.contype = 'f' order by 1""", String.class, target);
            assertThat(fks).containsExactly("cntrt.fk_cntrt_cntrt_cust_no:cntrt_cust_no>corp_cust(cust_no)", "corp_cust.fk_corp_cust_cust_no:cust_no>cust(cust_no)",
                    "dept.fk_dept_pty_no:pty_no,org_cd>org(pty_no,org_cd)", "dept.fk_dept_upr_pty_no:upr_pty_no,upr_org_cd>dept(pty_no,org_cd)",
                    "indv_cust.fk_indv_cust_cust_no:cust_no>cust(cust_no)", "org.fk_org_pty_no:pty_no,org_cd>pty(pty_no,org_cd)",
                    "prsn.fk_prsn_pty_no:pty_no,org_cd>pty(pty_no,org_cd)", "team.fk_team_pty_no:pty_no,org_cd>org(pty_no,org_cd)");
            assertThat(pg.queryForObject("select count(*) from pg_trigger g join pg_class r on r.oid = g.tgrelid join pg_namespace s on s.oid = r.relnamespace where s.nspname = ? and not g.tgisinternal",
                    Integer.class, target)).isZero();
            assertThat(pg.queryForObject("select count(*) from pg_constraint c join pg_namespace s on s.oid = c.connamespace where s.nspname = ? and c.contype = 'c'", Integer.class, target)).isZero();
        } finally {
            pg.execute("drop schema " + target + " cascade");
        }
    }

    @Test
    @DisplayName("기존 부모 재사용 + 다른 스키마 : SA 의 기존 루트(단일·복합)를 SB 에서 만든 서브타입이 스키마명.테이블명으로 참조, 중간 엔터티도 두 번째 요청에서 기존 부모로 재사용")
    void reuseAcrossSchemas() throws IOException {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "golden-reuse");
        SchemaInfo sa = tx.execute(s -> schemas.save(SchemaInfo.builder().projectId(f.user.getProjectId()).schemaName("SA").isDefault(false).build()));
        SchemaInfo sb = tx.execute(s -> schemas.save(SchemaInfo.builder().projectId(f.user.getProjectId()).schemaName("SB").isDefault(false).build()));
        long customer = entity(f, "고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 구분", company, person);
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        long orgCd = f.attr(party, "기관 코드", true, "ORG_CD");
        f.pk(party, partyNo, orgCd);
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", org, human);
        group(f, org, "조직 구분", dept, team);
        generate(f, sa.getId(), customer, party);
        generate(f, sb.getId(), company, person, org, human);
        generate(f, sb.getId(), dept, team);
        compare(f, "reuse-schemas");
    }
}
