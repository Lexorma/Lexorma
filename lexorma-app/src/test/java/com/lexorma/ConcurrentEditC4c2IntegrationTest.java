package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.GlobalExceptionHandler;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.schema.service.SchemaService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8-C4c-2 (docs/completed/modeling-concurrency-m8.md 21장) : 승인된 표준 용어 삭제 400 (C4c-D09), 잠금 충돌과 데이터 변경 충돌 구분 (C4c-D10 — 409 그대로, 잠금 실패만 reason LOCKED),
 * 승인 권한자의 승인된 엔터티 수정 (C4c-D08 — 기존 정책 확인), 기준값 없는 내부 오버로드를 운영 코드가 부르지 않음 (4.4)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC4c2IntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired DataDictionaryService dictionary;
    @Autowired StandardDomainRepository domains;
    @Autowired StandardTermRepository terms;
    @Autowired LogicalModelService logical;
    @Autowired SchemaService schemaService;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired AccountRepository accounts;
    @Autowired RoleRepository roles;
    @Autowired ProjectRepository projectRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private ConcurrentEditC4cSupport support() {
        return new ConcurrentEditC4cSupport(tx, jdbc, dataSource, projectRepository);
    }

    private static String code(Runnable call) {
        return ConcurrentEditC4cSupport.outcome(call);
    }

    private GenerationFixture fixture(String name) {
        return GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
    }

    private StandardTermDto termRow(long id) {
        return tx.execute(s -> StandardTermDto.fromEntity(terms.findById(id).orElseThrow()));
    }

    private StandardDomainDto domainRow(long id) {
        return tx.execute(s -> StandardDomainDto.fromEntity(domains.findById(id).orElseThrow()));
    }

    private int exists(String table, String key, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + key + " = ?", Integer.class, id);
    }

    private long unusedDomain(GenerationFixture f, String type, int length) {
        long term = f.term("TMP", type, length);
        long domain = jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, term);
        jdbc.update("delete from TB_STD_TRM where STD_TRM_SN = ?", term);
        jdbc.update("update TB_STD_DMN set APRV_YN = false where STD_DMN_SN = ?", domain);
        return domain;
    }

    // ------------------------------------------------------------------ C4c-D09 승인된 표준 용어 삭제

    @Test
    @DisplayName("C4c-D09 : 승인된 용어 단건 삭제 400 · 일괄 삭제에 섞이면 전체 롤백 (앞의 미승인 용어도 남는다) · 승인 취소 뒤에는 삭제")
    void approvedTermDelete() {
        GenerationFixture f = fixture("c4c2-t1");
        long approved = f.term("C4C2_A", "VARCHAR", 21); // fixture 의 용어는 승인 상태
        long plain = f.term("C4C2_B", "VARCHAR", 22);
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", plain);
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardTerms(List.of(termRow(approved)), f.user))).isEqualTo("APPROVED_DATA_CANNOT_BE_DELETED");
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardTerms(List.of(termRow(plain), termRow(approved)), f.user))).isEqualTo("APPROVED_DATA_CANNOT_BE_DELETED");
        assertThat(exists("TB_STD_TRM", "STD_TRM_SN", plain)).isEqualTo(1);
        assertThat(exists("TB_STD_TRM", "STD_TRM_SN", approved)).isEqualTo(1);

        dictionary.approvalStandardTerms(List.of(termRow(approved)), f.user, false);
        dictionary.deleteDataDictionaryStandardTerms(List.of(termRow(plain), termRow(approved)), f.user);
        assertThat(exists("TB_STD_TRM", "STD_TRM_SN", plain) + exists("TB_STD_TRM", "STD_TRM_SN", approved)).isZero();
    }

    @Test
    @DisplayName("C4c-D09 : 승인 ↔ 삭제 경합 — A 가 승인하는 동안 B 가 예전 행으로 삭제 → B 는 잠금 충돌(LOCKED) 또는 승인된 용어 거절, 용어는 승인된 채 남는다 · 다른 프로젝트 용어는 건너뛴다")
    void approveVsDelete() throws Exception {
        GenerationFixture f = fixture("c4c2-t2");
        long term = f.term("C4C2_R", "VARCHAR", 23);
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", term);
        StandardTermDto seen = termRow(term);
        String[] r = support().both(f.user, "ROLE_ADMIN",
                () -> dictionary.approvalStandardTerms(List.of(termRow(term)), f.user, true),
                () -> dictionary.deleteDataDictionaryStandardTerms(List.of(seen), f.user));
        ConcurrentEditC4cSupport.report("용어 승인 vs 삭제", r);
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isIn("MODEL_CONFLICT:LOCKED", "APPROVED_DATA_CANNOT_BE_DELETED");
        assertThat(exists("TB_STD_TRM", "STD_TRM_SN", term)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select APRV_YN from TB_STD_TRM where STD_TRM_SN = ?", Boolean.class, term)).isTrue();

        GenerationFixture other = fixture("c4c2-t2o");
        long foreign = other.term("C4C2_O", "VARCHAR", 24);
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", foreign);
        dictionary.deleteDataDictionaryStandardTerms(List.of(termRow(foreign)), f.user);
        assertThat(exists("TB_STD_TRM", "STD_TRM_SN", foreign)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ C4c-D10 잠금 충돌 ↔ 데이터 변경 충돌

    @Test
    @DisplayName("C4c-D10 : 기준값만 다르면 409 MODEL_CONFLICT (원인 없음) · 다른 트랜잭션이 행을 쥔 동안은 409 MODEL_CONFLICT + LOCKED · 잠금이 풀린 뒤 같은 (처음) 기준값으로 다시 보내면 데이터 변경 409")
    void lockedVersusChanged() throws Exception {
        GenerationFixture f = fixture("c4c2-l1");
        long domain = unusedDomain(f, "VARCHAR", 25);
        StandardDomainDto seen = domainRow(domain);
        // 잠금 충돌 : A 가 도메인 행을 쥐고 수정하는 동안 B(화면이 본 그대로의 기준값)가 삭제
        StandardDomainDto edit = domainRow(domain);
        edit.setCommonStandardDomainDescription("A 의 수정");
        String[] r = support().both(f.user, "ROLE_ADMIN",
                () -> dictionary.updateStandardDomain(f.user, edit),
                () -> dictionary.deleteDataDictionaryStandardDomains(List.of(seen), f.user));
        ConcurrentEditC4cSupport.report("도메인 수정(행 잠금) vs 삭제", r);
        assertThat(r).containsExactly("OK", "MODEL_CONFLICT:LOCKED");
        // 사용자가 직접 다시 시도 (처음 기준값 그대로) : 이제 잠금은 없고 실제로 바뀌었으므로 데이터 변경 409 (원인 없음)
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardDomains(List.of(seen), f.user))).isEqualTo("MODEL_CONFLICT");
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", domain)).isEqualTo(1);
        // 최신 기준값이면 삭제
        dictionary.deleteDataDictionaryStandardDomains(List.of(domainRow(domain)), f.user);
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", domain)).isZero();
    }

    @Test
    @DisplayName("C4c-D10 : 모델 쪽 NOWAIT(ModelRowLocks) — 다른 트랜잭션이 스키마 행을 쥔 동안 이름 변경은 LOCKED, 잠금이 풀린 뒤 같은 기준값이면 성공 (데이터는 안 바뀌었다)")
    void modelLockIsMarked() throws Exception {
        GenerationFixture f = fixture("c4c2-l2");
        SchemaDto created = schemaService.saveSchema(f.user, new SchemaDto(null, "SC_C4C2", "C4C2", false));
        SchemaInfo saved = schemaRepository.findById(created.id()).orElseThrow();
        SchemaDto rename = new SchemaDto(saved.getId(), "SC_C4C2_R", "C4C2", false, null, null, saved.modifiedAt(), null, null);
        String[] r = support().both(f.user, "ROLE_ADMIN",
                () -> jdbc.queryForList("select SCHM_SN from TB_SCHM where SCHM_SN = ? for update", saved.getId()),
                () -> schemaService.saveSchema(f.user, rename));
        ConcurrentEditC4cSupport.report("스키마 행 잠금 vs 이름 변경", r);
        assertThat(r).containsExactly("OK", "MODEL_CONFLICT:LOCKED");
        assertThat(code(() -> schemaService.saveSchema(f.user, rename))).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select SCHM_NM from TB_SCHM where SCHM_SN = ?", String.class, saved.getId())).isEqualTo("SC_C4C2_R");
    }

    @Test
    @DisplayName("C4c-D10 : 응답 JSON — 잠금 충돌만 reason \"LOCKED\", 그 밖의 오류는 reason 칸이 없다 (status·code·message 그대로)")
    void responseShape() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        var locked = handler.handleCustomException(CustomException.locked(ErrorCode.MODEL_CONFLICT, "다른 사용자가 같은 모델·테이블·컬럼을 바꾸는 중입니다. 잠시 뒤 다시 시도해주세요."));
        assertThat(locked.getStatusCode().value()).isEqualTo(409);
        assertThat(locked.getBody().getCode()).isEqualTo("MODEL_CONFLICT");
        assertThat(locked.getBody().getReason()).isEqualTo("LOCKED");
        var changed = handler.handleCustomException(new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", "X"));
        assertThat(changed.getStatusCode().value()).isEqualTo(409);
        assertThat(changed.getBody().getReason()).isNull();
        var json = new tools.jackson.databind.json.JsonMapper();
        assertThat(json.writeValueAsString(changed.getBody())).doesNotContain("reason");
        assertThat(json.writeValueAsString(locked.getBody())).contains("\"reason\":\"LOCKED\"");
        var denied = handler.handleCustomException(new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED));
        assertThat(denied.getBody().getReason()).isNull();
    }

    // ------------------------------------------------------------------ C4c-D08 승인 권한자의 승인된 엔터티 수정 (기존 정책 확인)

    @Test
    @DisplayName("C4c-D08 : 승인 권한자는 승인된 엔터티의 속성·식별자를 고친다 (승인 유지·변경 이력 기록) · 승인 권한 없는 USER 는 400 · 다른 프로젝트 엔터티는 찾을 수 없음")
    void approverEditsApprovedEntity() {
        GenerationFixture f = fixture("c4c2-e1");
        long entity = f.entity("고객");
        long key = f.attr(entity, "고객번호", true, "CUST_NO");
        f.pk(entity, key);
        LogicalModelDto.Entity detail = logical.selectEntity(f.user, entity);
        if (!Boolean.TRUE.equals(detail.isApproval())) {
            logical.updateEntityApproval(f.user, entity, true, detail.modifiedAt(), detail.composition());
            detail = logical.selectEntity(f.user, entity);
        }
        int historyBefore = jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, f.user.getProjectId());
        LogicalModelDto.Attribute a = detail.attributes().getFirst();
        logical.saveAttribute(f.user, new LogicalModelDto.Attribute(a.id(), entity, a.attributeName(), "승인 권한자가 고친 설명", a.dataType(), a.isRequired(), a.sortOrder(),
                a.termId(), null, null, null, a.modifiedAt()));
        long extra = f.attrNoTerm(entity, "비고");
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity, "보조", false, List.of(extra), null));
        assertThat(jdbc.queryForObject("select APRV_YN from TB_LGCL_ENTT where LGCL_ENTT_SN = ?", Boolean.class, entity)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, f.user.getProjectId())).isGreaterThan(historyBefore);

        // 승인 권한 없는 USER (프로젝트 관리자가 아닌 다른 계정)
        String username = "c4c2-user-" + UUID.randomUUID().toString().substring(0, 8);
        Account plain = tx.execute(s -> accounts.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roles.findByRoleName("ROLE_USER")))).projectMember(new ArrayList<>()).build()));
        var user = SecurityTestSupport.loginAs(projectRepository, f.user.getProjectId(), String.valueOf(plain.getId()), "ROLE_USER");
        LogicalModelDto.Attribute now = logical.selectEntity(user, entity).attributes().getFirst();
        assertThat(code(() -> logical.saveAttribute(user, new LogicalModelDto.Attribute(now.id(), entity, now.attributeName(), "USER 수정", now.dataType(), now.isRequired(),
                now.sortOrder(), now.termId(), null, null, null, now.modifiedAt())))).isEqualTo("APPROVED_DATA_CANNOT_BE_UPDATE");
        assertThat(code(() -> f.attrNoTerm(entity, "USER 속성"))).isEqualTo("APPROVED_DATA_CANNOT_BE_UPDATE");
        assertThat(jdbc.queryForObject("select APRV_YN from TB_LGCL_ENTT where LGCL_ENTT_SN = ?", Boolean.class, entity)).isTrue();

        GenerationFixture other = fixture("c4c2-e1o");
        assertThat(code(() -> logical.selectEntity(other.user, entity))).isNotEqualTo("OK");
    }

    // ------------------------------------------------------------------ M8-FINAL 컬럼 순서 팝업의 현재 순서

    @Autowired com.lexorma.data.column.service.ColumnManageService columnManage;
    @Autowired com.lexorma.data.table.repository.TableInfoRepository tables;
    @Autowired com.lexorma.data.column.repository.ColumnInfoRepository columnRepository;

    @Test
    @DisplayName("M8-FINAL : 컬럼 순서 팝업·충돌 비교가 읽는 selectColumnDetail 은 저장된 정렬 순서대로 (예전 : ID 순 — 팝업이 다르게 보이고 그대로 저장하면 순서가 되돌아갔다)")
    void columnDetailFollowsSortOrder() {
        GenerationFixture f = fixture("final-order");
        var schema = context.getBean(SchemaService.class).resolveSchema(f.user.getProjectId(), null);
        var table = tx.execute(s -> tables.save(com.lexorma.data.table.domain.entity.TableInfo.builder().projectId(f.user.getProjectId()).tableName("TB_FINAL_ORDER")
                .isApproval(false).schema(schema).build()));
        List<Long> ids = new ArrayList<>();
        for (String name : List.of("FIRST_COL", "SECOND_COL", "THIRD_COL")) {
            ids.add(tx.execute(s -> columnRepository.save(com.lexorma.data.column.domain.entity.ColumnInfo.builder().projectId(f.user.getProjectId()).tableName("TB_FINAL_ORDER")
                    .tableInfo(tables.getReferenceById(table.getId())).columnName(name).columnDesc(name).dataType("VARCHAR").isApproval(false).build())).getId());
        }
        // 다른 사용자가 순서를 뒤집었다 (THIRD → SECOND → FIRST)
        for (int i = 0; i < ids.size(); i++) jdbc.update("update TB_COL set SORT_ORD = ? where COL_SN = ?", ids.size() - i, ids.get(i));
        List<String> order = tx.execute(s -> columnManage.selectColumnDetail(f.user, ids.getFirst()).stream().map(c -> c.getColumnName()).toList());
        assertThat(order)
                .containsExactly("THIRD_COL", "SECOND_COL", "FIRST_COL");
    }

    // ------------------------------------------------------------------ 4.4 기준값 없는 내부 오버로드

    /** 기준값 없는(null = 내부 호출) 오버로드를 운영 코드가 부르지 않는다 — HTTP 는 기준값 필수 오버로드만 (M8-C4c-2 4.4) */
    @Test
    @DisplayName("4.4 : 운영 코드는 기준값 없는 해제·승인 오버로드를 부르지 않는다 (컨트롤러는 기준값 필수 오버로드만 부른다)")
    void noProductionCallerOfBaseLessOverloads() throws IOException {
        Pattern[] baseLess = {
                Pattern.compile("\\.unmapTable\\(\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*\\)"),
                Pattern.compile("\\.unmapSchema\\(\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*\\)"),
                Pattern.compile("\\.releaseColumn\\(\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*\\)"),
                Pattern.compile("\\.updateEntityApproval\\(\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*\\)"),
                // M8-FINAL : 원본 매핑 등록·재지정의 기준값 검사 없는 오버로드 (2 인자)
                Pattern.compile("\\.mapTable\\(\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*\\)"),
                Pattern.compile("\\.mapSchema\\(\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*\\)"),
                Pattern.compile("\\.confirmColumn\\(\\s*[\\w.()]+\\s*,\\s*[\\w.()]+\\s*\\)"),
        };
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        List<String> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("lexorma-")).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) continue;
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String source = Files.readString(file);
                        for (Pattern pattern : baseLess) {
                            var m = pattern.matcher(source);
                            while (m.find()) found.add(root.relativize(file) + " : " + m.group());
                        }
                    }
                }
            }
        }
        assertThat(found).as("기준값 없는 오버로드 호출").isEmpty();
        // 검사식이 실제로 맞는지 : 테스트 코드(내부 호출 계약을 쓰는 곳)에서는 찾아야 한다 — 빈 검사로 통과하지 않게
        String tests = Files.readString(root.resolve("lexorma-app/src/test/java/com/lexorma/SourceSyncIntegrationTest.java"))
                + Files.readString(root.resolve("lexorma-app/src/test/java/com/lexorma/LogicalModelIntegrationTest.java"));
        assertThat(Stream.of(baseLess).filter(p -> p.matcher(tests).find()).count()).isGreaterThanOrEqualTo(3);
    }
}
