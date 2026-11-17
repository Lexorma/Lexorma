package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.source.catalog.domain.dto.SourceCatalogDto;
import com.lexorma.source.catalog.service.SourceCatalogService;
import com.lexorma.source.catalog.service.SourceViewDefinitionService;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-16 원본 VIEW 정의 수집 (docs/completed/source-view-definition-dec16.md) : 실제 PostgreSQL 원본에서 pg_get_viewdef 로 정의를 읽고,
 * 원문은 TB_SRC_VIEW_DFN 에만 (키가 있으면 암호화), 카탈로그 버전·변경 이력·일반 카탈로그 응답·로그에는 해시·상태까지만 가는지.
 * 정의 변경 재수집 비교, 원본이 정의 조회를 막은 경우(권한 없음 — 지난 정의 유지, 거짓 변경 없음), 권한·프로젝트 격리·확인 이력.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lexorma.secret.keys=t1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "lexorma.secret.active-key-id=t1",
                "lexorma.source.allowed-networks=127.0.0.0/8,::1/128", "lexorma.source.jdbc.query-timeout-seconds=5",
                "lexorma.source.collection.heartbeat-interval=PT0.2S", "lexorma.source.connection-test.per-minute=1000"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SourceViewDefinitionIntegrationTest extends SourceJdbcTestSupport {

    /** 정의에 업무 규칙·리터럴이 들어 있는 VIEW (원문이 새면 이 리터럴이 보인다) */
    private static final String SECRET = "vip-7731@example.com";
    private static final String SECRET_2 = "gold-5519@example.com";
    private static final String[] MODEL = {
            "create schema app",
            "create table app.customer (id bigint primary key, email varchar(100) not null, grade text)",
            "create view app.v_secret as select id, grade from app.customer where email = '" + SECRET + "'",
            "comment on view app.v_secret is '중요 고객'",
            "create view app.v_plain as select id from app.customer"};

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired SourceCatalogService catalog;
    @Autowired SourceViewDefinitionService definitions;

    @Test
    @DisplayName("수집 → 정의 원문은 정의 테이블에만(암호화) · 버전·이력·카탈로그 응답·로그에는 해시·상태만. 관리자만 원문 조회·확인 이력, 다른 프로젝트·관리자 아닌 구성원 거절. 같은 정의 재수집은 변경 없음, 정의 변경은 CHANGED·이력")
    void collectCompareAndProtect(CapturedOutput output) throws Exception {
        SourceDb db = source(MODEL);
        Env e = env(db);
        SourceCollectionDto.Execution first = collect(e, "FULL");
        Long secret = tableId(e, "v_secret");

        SourceCatalogDto.TableDetail detail = catalog.table(e.managerDto(), secret);
        assertThat(detail.viewDefinition()).satisfies(d -> {
            assertThat(d.status()).isEqualTo("COLLECTED");
            assertThat(d.available()).isTrue();
            assertThat(d.change()).isEqualTo(SourceCatalogDto.DefinitionChange.NEW);
            assertThat(d.canRead()).isTrue();
            assertThat(d.changedOrder()).isEqualTo(first.reflectedOrder());
        });
        assertThat(catalog.table(e.managerDto(), tableId(e, "customer")).viewDefinition()).as("TABLE 에는 정의 상태가 없다").isNull();
        // 일반 카탈로그 응답·버전 내용·변경 이력·저장된 칸에 원문이 없다 (저장은 암호문)
        assertThat(JSON.writeValueAsString(detail)).doesNotContain(SECRET);
        assertThat(jdbc.queryForObject("select DFN_CN from TB_SRC_VIEW_DFN where SRC_TBL_SN = ?", String.class, secret)).startsWith("v1:").doesNotContain(SECRET);
        assertThat(jdbc.queryForObject("select ENCPT_YN from TB_SRC_VIEW_DFN where SRC_TBL_SN = ?", Boolean.class, secret)).isTrue();
        String version = jdbc.queryForObject("select TBL_CN from TB_SRC_TBL_VER where SRC_TBL_SN = ? order by RFLT_ORD desc fetch first 1 rows only", String.class, secret);
        assertThat(version).contains("\"definitionHash\":\"d1:").doesNotContain(SECRET).doesNotContain("customer where");
        assertThat(jdbc.queryForObject("select TBL_CN from TB_SRC_TBL_VER where SRC_TBL_SN = ? order by RFLT_ORD desc fetch first 1 rows only", String.class,
                tableId(e, "customer"))).as("TABLE 의 버전 내용은 그대로 (정의 항목 없음)").doesNotContain("definitionHash");

        // 원문 : 관리자만, 확인 이력 (해시만)
        SourceCatalogDto.ViewDefinitionText text = definitions.read(e.managerDto(), secret);
        assertThat(text.definition()).contains(SECRET).contains("app.customer");
        assertThat(text.toString()).doesNotContain(SECRET);
        assertThat(definitions.accesses(e.managerDto(), secret, 20)).singleElement().satisfies(a -> assertThat(a.accountId()).isNotBlank());
        assertThat(jdbc.queryForObject("select HASH_VL from TB_SRC_VIEW_DFN_IDNTY_HSTRY where SRC_TBL_SN = ?", String.class, secret)).startsWith("d1:");

        // 관리자 아닌 구성원 : 상태는 보이지만 원문·확인 이력은 거절
        var member = account("usr", "ROLE_USER");
        projectMemberRepository.save(ProjectMember.builder().project(e.project()).account(member).isActive(true).build());
        AccountDto memberDto = SecurityTestSupport.loginAs(projectRepository, e.project().getId(), String.valueOf(member.getId()), "ROLE_USER");
        assertThat(catalog.table(memberDto, secret).viewDefinition().canRead()).isFalse();
        assertThatThrownBy(() -> definitions.read(memberDto, secret)).isInstanceOfAny(CustomException.class, AccessDeniedException.class);
        assertThatThrownBy(() -> definitions.accesses(memberDto, secret, 20)).isInstanceOfAny(CustomException.class, AccessDeniedException.class);
        // 다른 프로젝트의 관리자 : 없는 객체
        Env other = env(null);
        assertThatThrownBy(() -> definitions.read(other.managerDto(), secret)).isInstanceOfSatisfying(CustomException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_VIEW_DFN_IDNTY_HSTRY where SRC_TBL_SN = ?", Integer.class, secret)).as("거절은 이력을 남기지 않는다").isOne();

        // 같은 정의 다시 : 버전·이력 0, 정의 행 UPDATE 없음, 변경 SAME
        String modified = jdbc.queryForObject("select cast(MDFCN_DT as varchar) from TB_SRC_VIEW_DFN where SRC_TBL_SN = ?", String.class, secret);
        SourceCollectionDto.Execution again = collect(e, "FULL");
        assertThat(again.versionCount()).isZero();
        assertThat(again.changeCount()).isZero();
        assertThat(jdbc.queryForObject("select cast(MDFCN_DT as varchar) from TB_SRC_VIEW_DFN where SRC_TBL_SN = ?", String.class, secret)).isEqualTo(modified);
        assertThat(catalog.table(e.managerDto(), secret).viewDefinition().change()).isEqualTo(SourceCatalogDto.DefinitionChange.SAME);

        // 정의 변경 : VIEW 하나만 새 버전, TABLE 변경 이력의 항목은 definitionHash (전후 해시만), 원문은 새 것
        db.jdbc().execute("create or replace view app.v_secret as select id, grade from app.customer where email = '" + SECRET_2 + "'");
        SourceCollectionDto.Execution changed = collect(e, "FULL");
        assertThat(changed.versionCount()).isOne();
        List<SourceCollectionDto.Change> history = snapshots.changes(e.managerDto(), changed.id(), 0, 50).getContent();
        assertThat(history).singleElement().satisfies(c -> {
            assertThat(c.targetType()).isEqualTo("TABLE");
            assertThat(c.changeType()).isEqualTo("MODIFIED");
            assertThat(c.changedFields()).containsExactly("definitionHash");
            assertThat(JSON.writeValueAsString(c)).doesNotContain(SECRET).doesNotContain(SECRET_2);
        });
        assertThat(catalog.table(e.managerDto(), secret).viewDefinition()).satisfies(d -> {
            assertThat(d.change()).isEqualTo(SourceCatalogDto.DefinitionChange.CHANGED);
            assertThat(d.changedOrder()).isEqualTo(changed.reflectedOrder());
        });
        assertThat(definitions.read(e.managerDto(), secret).definition()).contains(SECRET_2).doesNotContain(SECRET);
        assertThat(catalog.table(e.managerDto(), tableId(e, "v_plain")).viewDefinition().change()).isEqualTo(SourceCatalogDto.DefinitionChange.SAME);

        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain(SECRET_2);
    }

    @Test
    @DisplayName("원본이 정의 조회를 막으면(pg_get_viewdef 실행 권한 회수) 수집은 완료되고 VIEW 는 NO_PRIVILEGE, 지난 정의·해시는 그대로라 거짓 변경 없음. 권한을 돌려주면 다시 COLLECTED")
    void noPrivilege(CapturedOutput output) throws Exception {
        SourceDb db = source(MODEL);
        String role = "ai2b_ro_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        admin().execute("create role " + role + " login password 'ro-pass-1'");
        try {
            db.jdbc().execute("grant usage on schema app to " + role);
            Env e = env(null);
            Long connection = connect(e.managerDto(), db, role, "ro-pass-1", null);
            Env env = new Env(e.project(), e.manager(), e.managerDto(), connection);
            collect(env, "FULL");
            Long secret = tableId(env, "v_secret");
            assertThat(catalog.table(env.managerDto(), secret).viewDefinition().status()).isEqualTo("COLLECTED");
            String hash = jdbc.queryForObject("select HASH_VL from TB_SRC_VIEW_DFN where SRC_TBL_SN = ?", String.class, secret);

            // 이 데이터베이스에서만 정의 조회 함수를 막는다
            db.jdbc().execute("revoke execute on function pg_catalog.pg_get_viewdef(oid, boolean) from public");
            SourceCollectionDto.Execution blocked = collect(env, "FULL");
            assertThat(blocked.status()).isEqualTo("COMPLETED");
            assertThat(blocked.versionCount()).as("정의를 못 읽은 것은 원본 변경이 아니다").isZero();
            assertThat(catalog.table(env.managerDto(), secret).viewDefinition()).satisfies(d -> {
                assertThat(d.status()).isEqualTo("NO_PRIVILEGE");
                assertThat(d.available()).as("지난번에 읽은 원문은 남는다").isTrue();
            });
            assertThat(jdbc.queryForObject("select HASH_VL from TB_SRC_VIEW_DFN where SRC_TBL_SN = ?", String.class, secret)).isEqualTo(hash);
            assertThat(catalog.table(env.managerDto(), tableId(env, "customer")).columns()).as("테이블·컬럼 수집 결과는 그대로").hasSize(3);
            assertThat(output.getAll()).contains("원본 VIEW 정의를 읽지 못했습니다").contains("SOURCE_PERMISSION_DENIED").doesNotContain(SECRET);

            db.jdbc().execute("grant execute on function pg_catalog.pg_get_viewdef(oid, boolean) to public");
            db.jdbc().execute("create or replace view app.v_secret as select id, grade from app.customer where email = '" + SECRET_2 + "'");
            collect(env, "FULL");
            assertThat(catalog.table(env.managerDto(), secret).viewDefinition()).satisfies(d -> {
                assertThat(d.status()).isEqualTo("COLLECTED");
                assertThat(d.change()).isEqualTo(SourceCatalogDto.DefinitionChange.CHANGED);
            });
        } finally {
            db.jdbc().execute("revoke all on schema app from " + role);
            admin().execute("drop role if exists " + role);
        }
    }

    private Long tableId(Env e, String table) {
        return jdbc.queryForObject("select t.SRC_TBL_SN from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = 'app'"
                + " and t.CMPR_NM = ?", Long.class, e.connection(), table);
    }
}
