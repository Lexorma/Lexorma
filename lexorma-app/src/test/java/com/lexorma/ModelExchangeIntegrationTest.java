package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Item;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Reason;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Status;
import com.lexorma.data.exchange.service.ModelExchangeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-22 모델 Export·Import·복사 (docs/completed/model-exchange-dec22.md, H2 / PostgreSQL) : 예제 프로젝트(LEXORMA) 를 내보내 새 프로젝트로 가져오는 왕복,
 * 참조(FK·컬럼 쌍·인덱스·제약조건·논리 관계·식별자·매핑)를 새 ID 로 다시 잇는지, 승인 상태를 물려받지 않는지, 같은 이름은 덮어쓰지 않는지(CONFLICT),
 * 계획 뒤 대상이 바뀌면 409, 대상 프로젝트 관리자만, 반영 중 실패 → 전체 롤백, 안전하지 않은 SQL 식 제외, 형식 버전 검사.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelExchangeIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ModelExchangeService exchange;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired SchemaService schemaService;

    @Test
    @DisplayName("예제 모델 Export → 새 프로젝트 Import : 객체 수·참조 재매핑·미승인·직전 버전·IMPORT 이력. 같은 파일을 다시 가져오면 모두 CONFLICT (덮어쓰지 않음)")
    void roundTrip() throws Exception {
        AccountDto admin = admin(0L);
        ModelExchangeDto.Document document = exchange.export(admin);
        String json = JSON.writeValueAsString(document);
        assertThat(document.format()).isEqualTo("lexorma-model");
        assertThat(document.formatVersion()).isEqualTo(6); // 형식 6 : 상속 PK 매핑 (M-7 3차)
        assertThat(document.model().get("PHYSICAL_VIEW")).as("예제에는 모델 VIEW 가 없다").isEmpty();
        assertThat(json).doesNotContain("\"isApproval\"");
        int tables = count("select count(*) from TB_TBL where PRJ_SN = 0");
        int columns = count("select count(*) from TB_COL where PRJ_SN = 0");
        assertThat(document.model().get("PHYSICAL_TABLE")).hasSize(tables);
        assertThat(document.model().get("PHYSICAL_COLUMN")).hasSize(columns);

        // 예제(TB_ 테이블)를 새 프로젝트로 : 다시 읽은 파일(JSON 왕복)로 가져온다
        ModelExchangeDto.Document file = JSON.readValue(json, ModelExchangeDto.Document.class);
        Long target = project("exchange-target");
        ModelExchangeDto.Plan plan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(target, file, null));
        assertThat(plan.applicable()).isTrue();
        assertThat(plan.items()).filteredOn(i -> i.kind().equals("TABLE")).allSatisfy(i -> assertThat(i.status()).isEqualTo(Status.NEW)).hasSize(tables);
        assertThat(plan.warnings()).anyMatch(w -> w.startsWith("TERM_NOT_FOUND:"));
        if (document.dbType() != null) assertThat(plan.warnings()).contains("DB_TYPE_DIFFERS");
        ModelExchangeDto.Result result = exchange.apply(admin, new ModelExchangeDto.ApplyRequest(target, file, null, plan.planToken(), plan.requestId()));

        assertThat(result.created()).containsEntry("PHYSICAL_TABLE", tables).containsEntry("PHYSICAL_COLUMN", columns).containsEntry("LOGICAL_MODEL", 1);
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", target)).isEqualTo(tables);
        assertThat(count("select count(*) from TB_COL where PRJ_SN = ?", target)).isEqualTo(columns);
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ? and APRV_YN = true", target) + count("select count(*) from TB_COL where PRJ_SN = ? and APRV_YN = true", target)
                + count("select count(*) from TB_TBL_REL where PRJ_SN = ? and APRV_YN = true and REL_SRC_CD <> 'REF_COLUMN'", target)
                + count("select count(*) from TB_TBL_IDX where PRJ_SN = ? and APRV_YN = true and IDX_SRC_CD = 'USER'", target) + count("select count(*) from TB_LGCL_ENTT where PRJ_SN = ? and APRV_YN = true", target))
                .as("승인 상태를 물려받지 않는다 (컬럼에서 파생되는 참조 관계·컬럼 인덱스는 동기화 규칙대로)").isZero();
        // 관계 : 예제의 FK 와 같은 수, 모두 새 프로젝트의 TABLE·컬럼을 가리킨다
        assertThat(count("select count(*) from TB_TBL_REL where PRJ_SN = ? and REL_SRC_CD <> 'REF_COLUMN'", target))
                .isEqualTo(count("select count(*) from TB_TBL_REL where PRJ_SN = 0 and REL_SRC_CD <> 'REF_COLUMN'"));
        assertThat(count("select count(*) from TB_TBL_REL r join TB_TBL s on s.TBL_SN = r.ORGNL_TBL_SN join TB_TBL t on t.TBL_SN = r.TRGT_TBL_SN where r.PRJ_SN = ? and (s.PRJ_SN <> ? or t.PRJ_SN <> ?)",
                target, target, target)).isZero();
        assertThat(count("select count(*) from TB_TBL_REL_COL x join TB_TBL_REL r on r.REL_SN = x.REL_SN join TB_COL c on c.COL_SN = x.ORGNL_COL_SN where r.PRJ_SN = ? and c.PRJ_SN <> ?",
                target, target)).isZero();
        assertThat(count("select count(*) from TB_TBL_REL_COL x join TB_TBL_REL r on r.REL_SN = x.REL_SN where r.PRJ_SN = ?", target))
                .isEqualTo(count("select count(*) from TB_TBL_REL_COL x join TB_TBL_REL r on r.REL_SN = x.REL_SN where r.PRJ_SN = 0 and r.REL_SRC_CD <> 'REF_COLUMN'")
                        + count("select count(*) from TB_TBL_REL_COL x join TB_TBL_REL r on r.REL_SN = x.REL_SN where r.PRJ_SN = ? and r.REL_SRC_CD = 'REF_COLUMN'", target));
        // 인덱스·제약조건·논리·매핑
        assertThat(count("select count(*) from TB_TBL_IDX where PRJ_SN = ?", target)).isEqualTo(count("select count(*) from TB_TBL_IDX where PRJ_SN = 0"));
        assertThat(count("select count(*) from TB_TBL_IDX_COL x join TB_TBL_IDX i on i.IDX_SN = x.IDX_SN where i.PRJ_SN = ?", target))
                .isEqualTo(count("select count(*) from TB_TBL_IDX_COL x join TB_TBL_IDX i on i.IDX_SN = x.IDX_SN where i.PRJ_SN = 0"));
        assertThat(count("select count(*) from TB_TBL_CNSTRT where PRJ_SN = ?", target)).isEqualTo(count("select count(*) from TB_TBL_CNSTRT where PRJ_SN = 0"));
        assertThat(count("select count(*) from TB_LGCL_ENTT where PRJ_SN = ?", target)).isEqualTo(count("select count(*) from TB_LGCL_ENTT where PRJ_SN = 0"));
        assertThat(count("select count(*) from TB_LGCL_REL where PRJ_SN = ?", target)).isEqualTo(count("select count(*) from TB_LGCL_REL where PRJ_SN = 0"));
        assertThat(count("select count(*) from TB_LGCL_IDNTFR_ATRB x join TB_LGCL_IDNTFR i on i.LGCL_IDNTFR_SN = x.LGCL_IDNTFR_SN join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = i.LGCL_ENTT_SN where e.PRJ_SN = ?", target))
                .isEqualTo(count("select count(*) from TB_LGCL_IDNTFR_ATRB x join TB_LGCL_IDNTFR i on i.LGCL_IDNTFR_SN = x.LGCL_IDNTFR_SN join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = i.LGCL_ENTT_SN where e.PRJ_SN = 0"));
        assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where PRJ_SN = ?", target)).isEqualTo(count("select count(*) from TB_ENTT_TBL_MPNG where PRJ_SN = 0"));
        assertThat(count("select count(*) from TB_ATRB_COL_MPNG x join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = x.ENTT_TBL_MPNG_SN join TB_COL c on c.COL_SN = x.COL_SN where m.PRJ_SN = ? and c.PRJ_SN = ?", target, target))
                .isEqualTo(count("select count(*) from TB_ATRB_COL_MPNG x join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = x.ENTT_TBL_MPNG_SN where m.PRJ_SN = 0"));
        // 표준 용어 : 대상 프로젝트에 용어가 없으니 연결 없이
        assertThat(count("select count(*) from TB_COL where PRJ_SN = ? and STD_TRM_SN is not null", target)).isZero();
        // 직전 모델 버전·이력 (출처 IMPORT, 그룹 = 버전 이름)
        assertThat(count("select count(*) from TB_MDL_VER where PRJ_SN = ? and VER_NM = ?", target, result.modelVersionName())).isOne();
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ?", target, result.changeGroupId())).isPositive();
        // 원본 프로젝트는 그대로
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = 0")).isEqualTo(tables);
        // 기본 스키마가 아직 없던 프로젝트 : 파일의 DEFAULT 는 기본 스키마로 만든다 (일반 스키마로 만들면 그 뒤 기본 스키마 생성이 UNIQUE 위반 — 6차 E2E 에서 물리 ERD 500)
        assertThat(count("select count(*) from TB_SCHM where PRJ_SN = ? and SCHM_NM = 'DEFAULT' and BSC_YN = true", target)).isOne();
        Long resolved = tx.execute(status -> schemaService.resolveSchema(target, null).getId());
        assertThat(count("select count(*) from TB_SCHM where SCHM_SN = ? and SCHM_NM = 'DEFAULT'", resolved)).isOne();

        // 같은 파일을 다시 : 모두 CONFLICT, 가져올 것이 없다
        ModelExchangeDto.Plan again = exchange.plan(admin, new ModelExchangeDto.PlanRequest(target, file, null));
        assertThat(again.applicable()).isFalse();
        assertThat(again.items()).filteredOn(i -> i.kind().equals("TABLE") || i.kind().equals("LOGICAL_MODEL"))
                .allSatisfy(i -> assertThat(i).extracting(ModelExchangeDto.PlanItem::status, ModelExchangeDto.PlanItem::reason).containsExactly(Status.CONFLICT, Reason.NAME_EXISTS));
        assertThatThrownBy(() -> exchange.apply(admin, new ModelExchangeDto.ApplyRequest(target, file, null, again.planToken(), again.requestId())))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID));
    }

    @Test
    @DisplayName("선택 가져오기 · 계획 뒤 대상 변경 409 · 대상 관리자만 · 반영 중 실패 → 전체 롤백 · 안전하지 않은 SQL 식 제외 · 형식 버전 검사")
    void selectionStaleAuthorityRollbackAndUnsafe() throws Exception {
        AccountDto admin = admin(0L);
        ModelExchangeDto.Document document = exchange.export(admin);
        String schema = (String) document.model().get("SCHEMA").getFirst().state().get("schemaName");

        // 선택 : TABLE 하나만, 논리 모델 없음
        Long selective = project("exchange-select");
        ModelExchangeDto.Selection only = new ModelExchangeDto.Selection(List.of(schema + ".TB_TBL"), List.of(), List.of());
        ModelExchangeDto.Plan plan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(selective, document, only));
        assertThat(plan.items()).filteredOn(i -> i.status() == Status.NEW && i.kind().equals("TABLE")).extracting(ModelExchangeDto.PlanItem::name).containsExactly("TB_TBL");
        assertThat(plan.items()).filteredOn(i -> i.kind().equals("LOGICAL_MODEL")).allSatisfy(i -> assertThat(i.reason()).isEqualTo(Reason.NOT_SELECTED));
        assertThat(plan.items()).filteredOn(i -> i.kind().equals("RELATION") && i.key().startsWith(schema + ".TB_TBL->")).allSatisfy(i -> assertThat(i.status()).isEqualTo(Status.EXCLUDED));
        exchange.apply(admin, new ModelExchangeDto.ApplyRequest(selective, document, only, plan.planToken(), plan.requestId()));
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", selective)).isOne();
        assertThat(count("select count(*) from TB_LGCL_MDL where PRJ_SN = ?", selective)).isZero();

        // 계획 뒤 대상에 같은 이름 TABLE 이 생기면 409, 아무것도 만들지 않는다
        Long stale = project("exchange-stale");
        ModelExchangeDto.Plan stalePlan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(stale, document, only));
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, ?, true)", 990_000_000L + stale, stale, schema);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, MSTR_TBL_YN, APRV_YN) values (?, ?, ?, 'TB_TBL', false, false)", 990_000_000L + stale, stale, 990_000_000L + stale);
        assertThatThrownBy(() -> exchange.apply(admin, new ModelExchangeDto.ApplyRequest(stale, document, only, stalePlan.planToken(), stalePlan.requestId())))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_STALE));
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", stale)).isOne();
        assertThat(count("select count(*) from TB_MDL_VER where PRJ_SN = ?", stale)).isZero();

        // 대상 프로젝트의 관리자가 아니면 계획도 거절 (구성원 USER)
        Long guarded = project("exchange-guard");
        Account member = tx.execute(s -> accountRepository.save(Account.builder().username("mx-" + UUID.randomUUID().toString().substring(0, 8)).name("member").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName("ROLE_USER")))).projectMember(new ArrayList<>()).build()));
        tx.executeWithoutResult(s -> projectMemberRepository.save(ProjectMember.builder().project(projectRepository.findById(guarded).orElseThrow()).account(member).isActive(true).build()));
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, guarded, String.valueOf(member.getId()), "ROLE_USER");
        assertThatThrownBy(() -> exchange.plan(user, new ModelExchangeDto.PlanRequest(guarded, document, only)))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_FORBIDDEN));
        assertThat(exchange.targets(user)).extracting(ModelExchangeDto.TargetProject::id).doesNotContain(guarded);

        // 반영 중 실패 (계획 단계에서 보지 않는 숫자 칸이 글자) → 전체 롤백 : TABLE·버전·이력 없음
        Long broken = project("exchange-rollback");
        ModelExchangeDto.Document bad = mutate(document, model -> {
            Item column = model.get("PHYSICAL_COLUMN").getLast();
            column.state().put("maxLength", "abc");
        });
        ModelExchangeDto.Plan badPlan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(broken, bad, null));
        assertThatThrownBy(() -> exchange.apply(admin, new ModelExchangeDto.ApplyRequest(broken, bad, null, badPlan.planToken(), badPlan.requestId())))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_INVALID));
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", broken)).isZero();
        assertThat(count("select count(*) from TB_SCHM where PRJ_SN = ?", broken)).isZero();
        assertThat(count("select count(*) from TB_MDL_VER where PRJ_SN = ?", broken)).isZero();
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", broken)).isZero();

        // 안전하지 않은 SQL 식 (문장 구분·주석) : 그 객체만 빼고 가져온다
        Long unsafe = project("exchange-unsafe");
        ModelExchangeDto.Document injected = mutate(document, model -> {
            Item constraint = model.get("PHYSICAL_CONSTRAINT").stream().filter(i -> "CHECK".equals(i.state().get("constraintType"))).findFirst().orElseThrow();
            constraint.state().put("checkExpression", "1 = 1); drop table TB_ACNT; --");
        });
        ModelExchangeDto.Plan unsafePlan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(unsafe, injected, null));
        assertThat(unsafePlan.items()).anyMatch(i -> i.counts() != null && Integer.valueOf(1).equals(i.counts().get("unsafe")));
        exchange.apply(admin, new ModelExchangeDto.ApplyRequest(unsafe, injected, null, unsafePlan.planToken(), unsafePlan.requestId()));
        assertThat(count("select count(*) from TB_TBL_CNSTRT where PRJ_SN = ? and INSP_EXPR_CN like '%drop table%'", unsafe)).isZero();
        assertThat(count("select count(*) from TB_TBL_CNSTRT where PRJ_SN = ?", unsafe)).isEqualTo(count("select count(*) from TB_TBL_CNSTRT where PRJ_SN = 0") - 1);

        // 형식 : 다른 버전·모르는 종류·겹치는 번호
        ModelExchangeDto.Document v7 = new ModelExchangeDto.Document(document.format(), 7, document.exportedAt(), document.dbType(), document.counts(), document.model(), null);
        assertThatThrownBy(() -> exchange.plan(admin, new ModelExchangeDto.PlanRequest(unsafe, v7, null))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_INVALID));
        Map<String, List<Item>> unknown = new LinkedHashMap<>(document.model());
        unknown.put("PHYSICAL_SEQUENCE", List.of());
        assertThatThrownBy(() -> exchange.plan(admin, new ModelExchangeDto.PlanRequest(unsafe, new ModelExchangeDto.Document(document.format(), 2, null, null, null, unknown, null), null)))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_INVALID));
        // 형식 1 파일에는 VIEW 가 들어갈 수 없다 (형식 1 은 VIEW 를 내보낸 적이 없다)
        assertThatThrownBy(() -> exchange.plan(admin, new ModelExchangeDto.PlanRequest(unsafe, new ModelExchangeDto.Document(document.format(), 1, null, null, null, document.model(), null), null)))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_INVALID));
        ModelExchangeDto.Document nested = mutate(document, model -> model.get("PHYSICAL_TABLE").getFirst().state().put("tableName", Map.of("a", Map.of("b", 1))));
        assertThatThrownBy(() -> exchange.plan(admin, new ModelExchangeDto.PlanRequest(unsafe, nested, null))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_INVALID));
    }

    @Test
    @DisplayName("형식 2 : 모델 VIEW(정의·출력 컬럼·참조 재매핑)와 저장된 ERD 배치(물리·논리)를 새 ID 로 옮긴다. 다시 가져오면 CONFLICT·기존 배치 그대로, 이미 배치가 있으면 오른쪽에 더한다, "
            + "형식 1 파일 호환, 잘못된 좌표 거절, 안전하지 않은 정의·순환 제외, 실패 시 VIEW·배치까지 전체 롤백, 다른 프로젝트 객체를 가리키지 않는다")
    void viewsAndLayouts() throws Exception {
        AccountDto admin = admin(0L);
        long adminId = Long.parseLong(admin.getId());
        // 원본 프로젝트 : 예제를 복사한 뒤 VIEW 3개와 배치를 둔다
        Long source = project("exchange-view-source");
        ModelExchangeDto.Document example = exchange.export(admin);
        ModelExchangeDto.Plan copy = exchange.plan(admin, new ModelExchangeDto.PlanRequest(source, example, null));
        exchange.apply(admin, new ModelExchangeDto.ApplyRequest(source, example, null, copy.planToken(), copy.requestId()));
        long schema = jdbc.queryForObject("select SCHM_SN from TB_SCHM where PRJ_SN = ? and BSC_YN = true", Long.class, source);
        long account = id("select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_NM = 'TB_ACNT'", source);
        long project = id("select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_NM = 'TB_PRJ'", source);
        long model = id("select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = ?", source);
        List<Long> entities = jdbc.queryForList("select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = ? order by LGCL_ENTT_SN", Long.class, model);
        long base = 980_000_000L + source;
        view(base, source, schema, "VW_ACCOUNT", "SELECT USER_ID FROM TB_ACNT");
        jdbc.update("insert into TB_VIEW_COL(VIEW_COL_SN, VIEW_SN, COL_NM, COL_KORN_NM, COL_ORD) values (?, ?, 'USER_ID', '사용자 아이디', 1)", base, base);
        jdbc.update("insert into TB_VIEW_RFRNC(VIEW_RFRNC_SN, VIEW_SN, RFRNC_TYPE_CD, RFRNC_TBL_SN) values (?, ?, 'TABLE', ?)", base, base, account);
        jdbc.update("insert into TB_VIEW_RFRNC(VIEW_RFRNC_SN, VIEW_SN, RFRNC_TYPE_CD, RFRNC_SCHM_NM, RFRNC_TRGT_NM) values (?, ?, 'TABLE', 'ext', 'OUTSIDE_TABLE')", base + 1, base);
        view(base + 1, source, schema, "VW_ACCOUNT_PROJECT", "WITH a AS (SELECT * FROM VW_ACCOUNT) SELECT * FROM a, TB_PRJ");
        jdbc.update("insert into TB_VIEW_RFRNC(VIEW_RFRNC_SN, VIEW_SN, RFRNC_TYPE_CD, RFRNC_VIEW_SN) values (?, ?, 'VIEW', ?)", base + 2, base + 1, base);
        jdbc.update("insert into TB_VIEW_RFRNC(VIEW_RFRNC_SN, VIEW_SN, RFRNC_TYPE_CD, RFRNC_TBL_SN) values (?, ?, 'TABLE', ?)", base + 3, base + 1, project);
        view(base + 2, source, schema, "VW_ONLY_NAME", "SELECT 1 AS ONE");
        jdbc.update("insert into TB_ERD_LYT(ERD_LYT_SN, PRJ_SN, ACNT_SN, NODE_PSTN_CN, SCRN_X_CRDNT, SCRN_Y_CRDNT, SCRN_MGNF, VER_NO) values (?, ?, ?, ?, 10, 20, 0.75, 3)",
                base, source, adminId, "{\"" + account + "\":{\"x\":120.5,\"y\":-40},\"" + project + "\":{\"x\":900,\"y\":300},\"999999999\":{\"x\":1,\"y\":1}}");
        jdbc.update("insert into TB_LGCL_ERD_LYT(LGCL_ERD_LYT_SN, PRJ_SN, LGCL_MDL_SN, ACNT_SN, NODE_PSTN_CN, SCRN_X_CRDNT, SCRN_Y_CRDNT, SCRN_MGNF, VER_NO) values (?, ?, ?, ?, ?, 0, 0, 1, 0)",
                base, source, model, adminId, "{\"" + entities.get(0) + "\":{\"x\":-300,\"y\":50},\"" + entities.get(1) + "\":{\"x\":400,\"y\":75.25}}");

        ModelExchangeDto.Document document = exchange.export(admin(source));
        String json = JSON.writeValueAsString(document);
        assertThat(document.formatVersion()).isEqualTo(6); // 형식 6 : 상속 PK 매핑 (M-7 3차)
        assertThat(document.model().get("PHYSICAL_VIEW")).hasSize(3);
        assertThat(json).contains("WITH a AS (SELECT * FROM VW_ACCOUNT)").doesNotContain("\"isApproval\"");
        assertThat(document.layouts().physical().nodes()).as("지워진 객체의 좌표는 내보내지 않는다").containsOnlyKeys(account, project);
        assertThat(document.layouts().physical().viewport().zoom()).isEqualTo(0.75);
        assertThat(document.layouts().logical()).singleElement().satisfies(l -> assertThat(l.nodes()).hasSize(2));
        ModelExchangeDto.Document file = JSON.readValue(json, ModelExchangeDto.Document.class);

        // 새 프로젝트로 : VIEW 3개(미승인), 참조는 새 ID, 모델 밖 참조는 이름 그대로, 배치는 새 ID 로 같은 좌표
        Long target = project("exchange-view-target");
        ModelExchangeDto.Plan plan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(target, file, null));
        assertThat(plan.items()).filteredOn(i -> i.kind().equals("VIEW")).extracting(ModelExchangeDto.PlanItem::status).containsOnly(Status.NEW).hasSize(3);
        assertThat(plan.items()).filteredOn(i -> i.kind().equals("ERD_LAYOUT")).extracting(ModelExchangeDto.PlanItem::key, ModelExchangeDto.PlanItem::status)
                .contains(org.assertj.core.groups.Tuple.tuple("PHYSICAL", Status.NEW));
        assertThat(plan.warnings()).noneMatch(w -> w.startsWith("LAYOUT_IGNORED"));
        ModelExchangeDto.Result result = exchange.apply(admin, new ModelExchangeDto.ApplyRequest(target, file, null, plan.planToken(), plan.requestId()));
        assertThat(result.created()).containsEntry("PHYSICAL_VIEW", 3).containsEntry("ERD_LAYOUT_NODE", 4);
        assertThat(count("select count(*) from TB_VIEW where PRJ_SN = ? and APRV_YN = false", target)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select VIEW_DFN_CN from TB_VIEW where PRJ_SN = ? and VIEW_NM = 'VW_ACCOUNT_PROJECT'", String.class, target))
                .isEqualTo("WITH a AS (SELECT * FROM VW_ACCOUNT) SELECT * FROM a, TB_PRJ");
        assertThat(jdbc.queryForObject("select COL_KORN_NM from TB_VIEW_COL c join TB_VIEW v on v.VIEW_SN = c.VIEW_SN where v.PRJ_SN = ?", String.class, target)).isEqualTo("사용자 아이디");
        long newAccount = id("select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_NM = 'TB_ACNT'", target);
        long newProject = id("select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_NM = 'TB_PRJ'", target);
        long newView = id("select VIEW_SN from TB_VIEW where PRJ_SN = ? and VIEW_NM = 'VW_ACCOUNT'", target);
        assertThat(jdbc.queryForList("select coalesce(cast(r.RFRNC_TBL_SN as varchar(30)), '') || '/' || coalesce(cast(r.RFRNC_VIEW_SN as varchar(30)), '') || '/' || coalesce(r.RFRNC_TRGT_NM, '')"
                + " from TB_VIEW_RFRNC r join TB_VIEW v on v.VIEW_SN = r.VIEW_SN where v.PRJ_SN = ? order by v.VIEW_NM, r.VIEW_RFRNC_SN", String.class, target))
                .containsExactlyInAnyOrder(newAccount + "//", "//OUTSIDE_TABLE", "/" + newView + "/", newProject + "//");
        assertThat(count("select count(*) from TB_VIEW_RFRNC r join TB_VIEW v on v.VIEW_SN = r.VIEW_SN left join TB_TBL t on t.TBL_SN = r.RFRNC_TBL_SN left join TB_VIEW w on w.VIEW_SN = r.RFRNC_VIEW_SN"
                + " where v.PRJ_SN = ? and (t.PRJ_SN <> ? or w.PRJ_SN <> ?)", target, target, target)).as("다른 프로젝트 객체를 가리키지 않는다").isZero();
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_VIEW' and CHG_GROUP_ID = ?", target, result.changeGroupId())).isEqualTo(3);
        Map<String, Object> physical = JSON.readValue(jdbc.queryForObject("select NODE_PSTN_CN from TB_ERD_LYT where PRJ_SN = ? and ACNT_SN = ?", String.class, target, adminId), Map.class);
        assertThat(physical).containsOnlyKeys(String.valueOf(newAccount), String.valueOf(newProject));
        assertThat(physical.get(String.valueOf(newAccount))).isEqualTo(Map.of("x", 120.5, "y", -40.0));
        assertThat(jdbc.queryForObject("select SCRN_MGNF from TB_ERD_LYT where PRJ_SN = ? and ACNT_SN = ?", Double.class, target, adminId)).isEqualTo(0.75);
        long newModel = id("select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = ?", target);
        Map<String, Object> logical = JSON.readValue(jdbc.queryForObject("select NODE_PSTN_CN from TB_LGCL_ERD_LYT where LGCL_MDL_SN = ? and ACNT_SN = ?", String.class, newModel, adminId), Map.class);
        List<Long> newEntities = jdbc.queryForList("select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = ? order by LGCL_ENTT_SN", Long.class, newModel);
        assertThat(logical).containsOnlyKeys(String.valueOf(newEntities.get(0)), String.valueOf(newEntities.get(1)));
        assertThat(logical.get(String.valueOf(newEntities.get(1)))).isEqualTo(Map.of("x", 400.0, "y", 75.25));
        assertThat(count("select count(*) from TB_ERD_LYT where PRJ_SN = ?", target)).as("다른 사용자의 배치는 만들지 않는다").isOne();

        // 다시 가져오기 : VIEW 는 CONFLICT, 배치는 옮길 것이 없다 (기존 배치 그대로)
        String before = jdbc.queryForObject("select NODE_PSTN_CN || VER_NO from TB_ERD_LYT where PRJ_SN = ?", String.class, target);
        ModelExchangeDto.Plan again = exchange.plan(admin, new ModelExchangeDto.PlanRequest(target, file, null));
        assertThat(again.items()).filteredOn(i -> i.kind().equals("VIEW")).extracting(ModelExchangeDto.PlanItem::status).containsOnly(Status.CONFLICT);
        assertThat(again.items()).filteredOn(i -> i.kind().equals("ERD_LAYOUT")).extracting(ModelExchangeDto.PlanItem::status).containsOnly(Status.EXCLUDED);
        assertThat(again.applicable()).isFalse();
        assertThat(jdbc.queryForObject("select NODE_PSTN_CN || VER_NO from TB_ERD_LYT where PRJ_SN = ?", String.class, target)).isEqualTo(before);

        // 이미 배치가 있는 프로젝트 : TB_ACNT 만 먼저 → 나머지를 가져오면 기존 좌표 그대로, 새 TABLE 은 기존 노드 오른쪽
        Long merged = project("exchange-layout-merge");
        String key = (String) document.model().get("SCHEMA").getFirst().state().get("schemaName") + ".TB_ACNT";
        ModelExchangeDto.Selection first = new ModelExchangeDto.Selection(List.of(key), List.of(), List.of());
        ModelExchangeDto.Plan firstPlan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(merged, file, first));
        exchange.apply(admin, new ModelExchangeDto.ApplyRequest(merged, file, first, firstPlan.planToken(), firstPlan.requestId()));
        long mergedAccount = id("select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_NM = 'TB_ACNT'", merged);
        ModelExchangeDto.Plan rest = exchange.plan(admin, new ModelExchangeDto.PlanRequest(merged, file, null));
        // 계획 뒤 같은 사용자가 배치를 저장하면 다시 계획해야 한다
        jdbc.update("update TB_ERD_LYT set VER_NO = VER_NO + 1 where PRJ_SN = ?", merged);
        assertThatThrownBy(() -> exchange.apply(admin, new ModelExchangeDto.ApplyRequest(merged, file, null, rest.planToken(), rest.requestId())))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_STALE));
        ModelExchangeDto.Plan restAgain = exchange.plan(admin, new ModelExchangeDto.PlanRequest(merged, file, null));
        assertThat(restAgain.items()).filteredOn(i -> i.kind().equals("VIEW") && i.name().equals("VW_ACCOUNT"))
                .singleElement().satisfies(i -> assertThat(i.counts()).containsEntry("linked", 1).containsEntry("unresolved", 1));
        exchange.apply(admin, new ModelExchangeDto.ApplyRequest(merged, file, null, restAgain.planToken(), restAgain.requestId()));
        Map<String, Map<String, Double>> mergedNodes = JSON.readValue(jdbc.queryForObject("select NODE_PSTN_CN from TB_ERD_LYT where PRJ_SN = ?", String.class, merged), Map.class);
        assertThat(mergedNodes.get(String.valueOf(mergedAccount))).as("기존 좌표 그대로").isEqualTo(Map.of("x", 120.5, "y", -40.0));
        long mergedProject = id("select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_NM = 'TB_PRJ'", merged);
        assertThat(mergedNodes.get(String.valueOf(mergedProject)).get("x")).as("기존 노드 오른쪽").isGreaterThanOrEqualTo(120.5 + 600);
        // VW_ACCOUNT 는 기존 TB_ACNT 에 잇는다 (이번에 만들지 않았지만 같은 이름 객체가 있다)
        assertThat(jdbc.queryForList("select r.RFRNC_TBL_SN from TB_VIEW_RFRNC r join TB_VIEW v on v.VIEW_SN = r.VIEW_SN where v.PRJ_SN = ? and v.VIEW_NM = 'VW_ACCOUNT' and r.RFRNC_TBL_SN is not null",
                Long.class, merged)).containsExactly(mergedAccount);

        // 형식 1 파일 (VIEW·배치 없음) : 그대로 가져온다, 배치는 만들지 않는다 (화면의 자동 배치)
        Map<String, List<Item>> withoutViews = new LinkedHashMap<>(file.model());
        withoutViews.remove("PHYSICAL_VIEW");
        // 형식 1·2 파일에는 관계 속성 짝(형식 3)도 없다 (예제 관계에는 짝이 있다)
        withoutViews.put("LOGICAL_RELATIONSHIP", withoutViews.get("LOGICAL_RELATIONSHIP").stream().map(item -> {
            Map<String, Object> state = new LinkedHashMap<>(item.state());
            state.remove("attributes");
            return new Item(item.id(), item.name(), state);
        }).toList());
        ModelExchangeDto.Document v1 = new ModelExchangeDto.Document(file.format(), 1, file.exportedAt(), file.dbType(), file.counts(), withoutViews, null);
        Long legacy = project("exchange-v1");
        ModelExchangeDto.Plan legacyPlan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(legacy, JSON.readValue(JSON.writeValueAsString(v1), ModelExchangeDto.Document.class), null));
        assertThat(legacyPlan.items()).noneMatch(i -> i.kind().equals("VIEW") || i.kind().equals("ERD_LAYOUT"));
        exchange.apply(admin, new ModelExchangeDto.ApplyRequest(legacy, v1, null, legacyPlan.planToken(), legacyPlan.requestId()));
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", legacy)).isEqualTo(count("select count(*) from TB_TBL where PRJ_SN = ?", source));
        assertThat(count("select count(*) from TB_ERD_LYT where PRJ_SN = ?", legacy) + count("select count(*) from TB_VIEW where PRJ_SN = ?", legacy)).isZero();

        // 잘못된 좌표 : 너무 큼 · 확대 비율 · 좌표 없음 → 파일 전체 400. 파일에 없는 객체의 좌표는 무시(경고)
        Long checks = project("exchange-layout-check");
        for (String broken : List.of("\"x\":1.0E12,\"y\":0", "\"x\":1,\"y\":null")) {
            String bad = json.replaceFirst("\"x\":120\\.5,\"y\":-40\\.0", broken);
            assertThat(bad).isNotEqualTo(json);
            assertThatThrownBy(() -> exchange.plan(admin, new ModelExchangeDto.PlanRequest(checks, JSON.readValue(bad, ModelExchangeDto.Document.class), null)))
                    .as(broken).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_INVALID));
        }
        String zoom = json.replace("\"zoom\":0.75", "\"zoom\":10.0");
        assertThat(zoom).isNotEqualTo(json);
        assertThatThrownBy(() -> exchange.plan(admin, new ModelExchangeDto.PlanRequest(checks, JSON.readValue(zoom, ModelExchangeDto.Document.class), null)))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_INVALID));
        assertThatThrownBy(() -> JSON.readValue(json.replace("120.5", "NaN"), ModelExchangeDto.Document.class)).as("JSON 이 아닌 숫자").isInstanceOf(Exception.class);
        ModelExchangeDto.Document unknownNode = JSON.readValue(json.replace("\"physical\":{\"nodes\":{", "\"physical\":{\"nodes\":{\"123456789012\":{\"x\":1,\"y\":2},"),
                ModelExchangeDto.Document.class);
        assertThat(exchange.plan(admin, new ModelExchangeDto.PlanRequest(checks, unknownNode, null)).warnings()).contains("LAYOUT_IGNORED:1");

        // 안전하지 않은 정의 (문장 둘) · 순환 참조 · 자기 참조 : 그 VIEW 만 가져오지 않는다
        ModelExchangeDto.Document unsafe = mutate(file, m -> {
            for (Item v : m.get("PHYSICAL_VIEW")) {
                if (v.state().get("viewName").equals("VW_ONLY_NAME")) v.state().put("definition", "SELECT 1; DROP TABLE TB_ACNT");
                if (v.state().get("viewName").equals("VW_ACCOUNT")) {
                    Map<String, Object> cycle = new LinkedHashMap<>(Map.of("referenceType", "VIEW", "referencedViewId", base + 1));
                    List<Map<String, Object>> references = new ArrayList<>((List<Map<String, Object>>) v.state().get("references"));
                    references.add(cycle);
                    v.state().put("references", references);
                }
            }
        });
        ModelExchangeDto.Plan unsafePlan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(checks, unsafe, null));
        assertThat(unsafePlan.items()).filteredOn(i -> i.kind().equals("VIEW")).extracting(ModelExchangeDto.PlanItem::name, ModelExchangeDto.PlanItem::reason)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("VW_ACCOUNT", Reason.INVALID), org.assertj.core.groups.Tuple.tuple("VW_ACCOUNT_PROJECT", Reason.INVALID),
                        org.assertj.core.groups.Tuple.tuple("VW_ONLY_NAME", Reason.UNSAFE_TEXT));
        exchange.apply(admin, new ModelExchangeDto.ApplyRequest(checks, unsafe, null, unsafePlan.planToken(), unsafePlan.requestId()));
        assertThat(count("select count(*) from TB_VIEW where PRJ_SN = ?", checks)).isZero();

        // 반영 중 실패 : VIEW·배치까지 아무것도 남지 않는다
        Long broken = project("exchange-view-rollback");
        ModelExchangeDto.Document bad = mutate(file, m -> m.get("PHYSICAL_COLUMN").getLast().state().put("maxLength", "abc"));
        ModelExchangeDto.Plan badPlan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(broken, bad, null));
        assertThatThrownBy(() -> exchange.apply(admin, new ModelExchangeDto.ApplyRequest(broken, bad, null, badPlan.planToken(), badPlan.requestId())))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.MODEL_EXCHANGE_INVALID));
        assertThat(count("select count(*) from TB_VIEW where PRJ_SN = ?", broken) + count("select count(*) from TB_ERD_LYT where PRJ_SN = ?", broken)
                + count("select count(*) from TB_LGCL_ERD_LYT where PRJ_SN = ?", broken) + count("select count(*) from TB_TBL where PRJ_SN = ?", broken)).isZero();
        // 원본 프로젝트의 VIEW·배치는 그대로
        assertThat(count("select count(*) from TB_VIEW where PRJ_SN = ?", source)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select VER_NO from TB_ERD_LYT where PRJ_SN = ?", Long.class, source)).isEqualTo(3L);
    }

    @Test
    @DisplayName("M7-U01 : 파일 안 한 TABLE 의 컬럼명이 대소문자·앞뒤 공백만 다르면 그 TABLE 은 EXCLUDED·INVALID (가져오지 않는다) — 다른 TABLE 의 같은 이름은 그대로")
    void columnNameKeyInFile() throws Exception {
        AccountDto admin = admin(0L);
        ModelExchangeDto.Document document = exchange.export(admin);
        Item table = document.model().get("PHYSICAL_TABLE").stream().filter(t -> "TB_COL".equals(t.state().get("tableName"))).findFirst().orElseThrow();
        ModelExchangeDto.Document dup = mutate(document, model -> {
            Item column = model.get("PHYSICAL_COLUMN").stream().filter(c -> table.id().equals(((Number) c.state().get("tableInfoId")).longValue())
                    && "COL_NM".equals(c.state().get("columnName"))).findFirst().orElseThrow();
            Map<String, Object> state = new LinkedHashMap<>(column.state());
            state.put("columnName", " col_nm ");
            long id = model.values().stream().flatMap(List::stream).mapToLong(Item::id).max().orElse(0) + 1;
            model.get("PHYSICAL_COLUMN").add(new Item(id, " col_nm ", state));
        });
        Long target = project("exchange-colkey");
        ModelExchangeDto.Plan plan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(target, dup, null));
        assertThat(plan.items()).filteredOn(i -> i.kind().equals("TABLE") && "TB_COL".equals(i.name())).singleElement()
                .extracting(ModelExchangeDto.PlanItem::status, ModelExchangeDto.PlanItem::reason).containsExactly(Status.EXCLUDED, Reason.INVALID);
        assertThat(plan.items()).filteredOn(i -> i.kind().equals("TABLE") && "TB_TBL".equals(i.name())).singleElement()
                .extracting(ModelExchangeDto.PlanItem::status).isEqualTo(Status.NEW);

        // M7-U06 : 확장 대문자로만 같은 두 컬럼(STRAßE_X · strasse_x)이 한 TABLE 에 있으면 그 TABLE 은 INVALID, 다른 TABLE 에 나뉘면 그대로 (모델 복사 = 같은 가져오기)
        Item tbl = document.model().get("PHYSICAL_TABLE").stream().filter(t -> "TB_TBL".equals(t.state().get("tableName"))).findFirst().orElseThrow();
        ModelExchangeDto.Document unicode = mutate(document, model -> {
            Item colNm = model.get("PHYSICAL_COLUMN").stream().filter(c -> table.id().equals(((Number) c.state().get("tableInfoId")).longValue())
                    && "COL_NM".equals(c.state().get("columnName"))).findFirst().orElseThrow();
            long id = model.values().stream().flatMap(List::stream).mapToLong(Item::id).max().orElse(0) + 1;
            for (Object[] add : new Object[][]{{table.id(), "STRAßE_X"}, {table.id(), "strasse_x"}, {tbl.id(), "STRAßE_X"}}) {
                Map<String, Object> state = new LinkedHashMap<>(colNm.state());
                state.put("columnName", add[1]);
                state.put("tableInfoId", add[0]);
                model.get("PHYSICAL_COLUMN").add(new Item(id++, (String) add[1], state));
            }
        });
        ModelExchangeDto.Plan unicodePlan = exchange.plan(admin, new ModelExchangeDto.PlanRequest(project("exchange-colkey-u"), unicode, null));
        assertThat(unicodePlan.items()).filteredOn(i -> i.kind().equals("TABLE") && "TB_COL".equals(i.name())).singleElement()
                .extracting(ModelExchangeDto.PlanItem::status, ModelExchangeDto.PlanItem::reason).containsExactly(Status.EXCLUDED, Reason.INVALID);
        assertThat(unicodePlan.items()).filteredOn(i -> i.kind().equals("TABLE") && "TB_TBL".equals(i.name())).singleElement()
                .extracting(ModelExchangeDto.PlanItem::status).isEqualTo(Status.NEW);
    }

    private void view(long id, long projectId, long schemaId, String name, String definition) {
        jdbc.update("insert into TB_VIEW(VIEW_SN, PRJ_SN, SCHM_SN, VIEW_NM, VIEW_DFN_CN, APRV_YN) values (?, ?, ?, ?, ?, true)", id, projectId, schemaId, name, definition);
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ------------------------------------------------------------------

    private AccountDto admin(Long projectId) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        return SecurityTestSupport.loginAs(projectRepository, projectId, String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private Long project(String name) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(admin)
                .dbType(DbType.ORACLE).build());
        // 관리자라도 멤버가 아닌 프로젝트에는 가져올 수 없다 (ProjectAuthority.canManage)
        projectMemberRepository.save(ProjectMember.builder().project(project).account(admin).isActive(true).build());
        return project.getId();
    }

    /** 파일을 JSON 으로 복제해 고친다 (원본 문서는 그대로) */
    private static ModelExchangeDto.Document mutate(ModelExchangeDto.Document document, Consumer<Map<String, List<Item>>> change) {
        ModelExchangeDto.Document copy = JSON.readValue(JSON.writeValueAsString(document), ModelExchangeDto.Document.class);
        change.accept(copy.model());
        return copy;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static Consumer<CustomException> code(ErrorCode expected) {
        return e -> assertThat(e.getErrorCode()).isEqualTo(expected);
    }
}
