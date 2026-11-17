package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.ai.provider.service.AiDefaultProviderService;
import com.lexorma.ai.provider.service.AiProviderService;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposal.ReviewStatus;
import com.lexorma.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.ai.dictionary.service.AiDictionaryQueryService;
import com.lexorma.ai.dictionary.service.ProposalVersions;
import com.lexorma.data.column.controller.ColumnManageRestController;
import com.lexorma.data.column.domain.dto.ColumnTermDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnTermService;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기존 컬럼의 표준 용어 재연결 (COL-TERM) : 연결만 바뀌고 컬럼 속성·DDL 은 그대로, 같은 용어면 아무것도 하지 않음, 화면이 읽은 연결과 다르면 409,
 * 승인된 컬럼·다른 프로젝트·없는 대상·VIEWER 거부, 동시 변경은 하나만. 실제 재연결 뒤 그 컬럼을 대상·근거로 만든 AI 설명 제안은 TERM_LINK_CHANGED 로 재검토된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class ColumnTermRelinkIntegrationTest {

    @Autowired ColumnTermService termService;
    @Autowired ColumnManageRestController columnApi;
    @Autowired TableDesignService tableDesignService;
    @Autowired AiDescriptionDraftService drafts;
    @Autowired AiDescriptionReviewService reviews;
    @Autowired AiDictionaryQueryService queries;
    @Autowired AiDescriptionProposalRepository proposals;
    @Autowired AiProviderService providers;
    @Autowired AiDefaultProviderService defaults;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private ScriptedOllama ollama;
    private Long providerId;
    private Long ruleId;
    private Account owner;
    private Account member;
    private Account viewer;
    private Account outsider;
    private Project project;
    private Project other;
    private TableInfo order;
    private ColumnInfo status;
    private ColumnInfo amount;
    private ColumnInfo approved;
    private ColumnInfo noTerm;
    private StandardTerm statusTerm;
    private StandardTerm progressTerm;
    private StandardTerm foreignTerm;

    @BeforeEach
    void setUp() throws IOException {
        defaults.clear();
        owner = account("ROLE_USER");
        member = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        outsider = account("ROLE_USER");
        project = project(owner, List.of(owner, member, viewer));
        other = project(outsider, List.of(outsider));
        tx.executeWithoutResult(s -> {
            Long p = project.getId();
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            StandardDomain code2 = domainRepository.save(StandardDomain.builder().projectId(p).commonStandardDomainName("코드C2").commonStandardDomainCategory("코드").dataType("VARCHAR").dataLength(2).isApproval(true).build());
            StandardDomain code3 = domainRepository.save(StandardDomain.builder().projectId(p).commonStandardDomainName("코드C3").commonStandardDomainCategory("코드").dataType("VARCHAR").dataLength(3).isApproval(true).build());
            statusTerm = termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("주문_상태_코드").commonStandardTermAbbreviation("ORD_STTS_CD").standardDomain(code2).isApproval(true).build());
            progressTerm = termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("주문_처리_상태_코드").commonStandardTermAbbreviation("ORD_PROC_STTS_CD").standardDomain(code3).isApproval(false).build());
            order = tableRepository.save(TableInfo.builder().projectId(p).schema(schema).tableName("TB_ORD").tableKorName("주문").tableDescription("주문").isApproval(true).isMasterTable(false).build());
            column("ORD_ID", "주문 번호", "BIGINT", null, 1, null, true, null);
            status = columnRepository.save(ColumnInfo.builder().projectId(p).tableInfo(order).tableName("TB_ORD").columnName("ORD_STTS_CD").columnDesc("주문 상태 코드").dataType("VARCHAR")
                    .maxLength(2).precision(new BigDecimal("0")).scale(0).isNullable(false).defaultValue("'01'").sortOrder(2).isPk(false).isUnique(true).isIndex(true)
                    .isRequired(true).isSensitive(false).isMasterData(false).isEncrypted(false).refTableName(null).example("01").bizDescription("주문 진행 단계를 나타낸다")
                    .standardTerms(statusTerm).isApproval(false).build());
            amount = column("ORD_AMT", "주문 금액", "NUMERIC", null, 3, null, false, statusTerm);
            approved = column("ORD_DT", "주문 일시", "TIMESTAMP", null, 4, null, false, statusTerm);
            approved.setIsApproval(true);
            noTerm = column("ORD_MEMO", "메모", "VARCHAR", 200, 5, null, false, null);
            SchemaInfo foreignSchema = schemaRepository.save(SchemaInfo.builder().projectId(other.getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            foreignTerm = termRepository.save(StandardTerm.builder().projectId(other.getId()).commonStandardTermName("다른_용어").commonStandardTermAbbreviation("OTH_TERM").isApproval(true).build());
            TableInfo foreignTable = tableRepository.save(TableInfo.builder().projectId(other.getId()).schema(foreignSchema).tableName("TB_OTH").tableKorName("다른").isApproval(false).isMasterTable(false).build());
            columnRepository.save(ColumnInfo.builder().projectId(other.getId()).tableInfo(foreignTable).tableName("TB_OTH").columnName("OTH_TERM").dataType("VARCHAR").sortOrder(1)
                    .standardTerms(foreignTerm).isApproval(false).build());
        });
        ollama = new ScriptedOllama();
        ollama.show(List.of("completion", "tools"), 32768);
        providerId = providers.create(new ProviderRequest("COL-TERM " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), "gemma3:4b", true, null)).id();
        ruleId = rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "COL-TERM")).id();
        defaults.set(new AiProviderDto.DefaultProviderRequest(providerId));
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
        defaults.clear();
        providers.delete(providerId);
        rules.delete(ruleId);
        ollama.close();
    }

    @Test
    @DisplayName("재연결 : 연결만 바뀌고 컬럼명·타입·길이·정밀도·NULL·기본값·PK/고유/인덱스·참조·설명·업무 설명·예시·순서·승인 여부는 그대로, UPDATE 는 연결(과 감사) 칸만, DDL 은 같다, 이력 USER 에 용어 이름 전후")
    void relinkKeepsColumn() {
        String ddlBefore = ddl();
        ColumnInfo before = column(status);
        SqlCapture.start();
        ColumnTermDto.Result result = termService.relink(login(owner), status.getId(), new ColumnTermDto.Request(progressTerm.getId(), statusTerm.getId()));
        List<String> updates = SqlCapture.stop("TB_COL");
        assertThat(result.changed()).isTrue();
        assertThat(result.before().getCommonStandardTermName()).isEqualTo("주문_상태_코드");
        assertThat(result.after().getCommonStandardTermName()).isEqualTo("주문_처리_상태_코드");

        ColumnInfo after = column(status);
        assertThat(termId(after)).isEqualTo(progressTerm.getId());
        assertThat(after).usingRecursiveComparison()
                .ignoringFields("standardTerms", "tableInfo", "modifiedDate", "modifiedBy", "audit")
                .comparingOnlyFields("columnName", "tableName", "columnDesc", "dataType", "maxLength", "precision", "scale", "isNullable", "defaultValue", "sortOrder", "isPk",
                        "isUnique", "isIndex", "isRequired", "isSensitive", "isMasterData", "isEncrypted", "refTableName", "example", "bizDescription", "sourceComment", "identity", "isApproval")
                .isEqualTo(before);
        assertThat(updates).singleElement().satisfies(sql -> assertThat(sql.toUpperCase()).contains("STD_TRM_SN")
                .doesNotContain("COL_NM", "DATA_TYPE", "TASK_EXPLN", "COL_EXPLN", "APRV_YN", "DFLT_VL", "MAX_LEN"));
        assertThat(ddl()).as("DDL 은 표준 용어를 쓰지 않는다").isEqualTo(ddlBefore);
        // 모델 변경 이력 : 출처 USER, 용어 이름 전후
        Map<String, Object> history = jdbc.queryForMap("select CHG_SRC_CD, CHG_BFR_CN, CHG_AFTR_CN from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ? "
                + "order by MDL_CHG_HSTRY_SN desc limit 1", status.getId());
        assertThat(history.get("CHG_SRC_CD")).isEqualTo("USER");
        assertThat(String.valueOf(history.get("CHG_BFR_CN"))).contains("주문_상태_코드");
        assertThat(String.valueOf(history.get("CHG_AFTR_CN"))).contains("주문_처리_상태_코드");
    }

    @Test
    @DisplayName("같은 용어 : 바꾸지 않고(changed=false) 이력·수정 일시도 그대로 / 화면이 읽은 연결과 다르면 409, 지금 연결 유지")
    void sameTermAndConflict() {
        long historyBefore = histories(status);
        Object modifiedBefore = jdbc.queryForObject("select MDFCN_DT from TB_COL where COL_SN = ?", Object.class, status.getId());
        ColumnTermDto.Result same = termService.relink(login(owner), status.getId(), new ColumnTermDto.Request(statusTerm.getId(), statusTerm.getId()));
        assertThat(same.changed()).isFalse();
        assertThat(histories(status)).isEqualTo(historyBefore);
        assertThat(jdbc.queryForObject("select MDFCN_DT from TB_COL where COL_SN = ?", Object.class, status.getId())).isEqualTo(modifiedBefore);

        termService.relink(login(member), status.getId(), new ColumnTermDto.Request(progressTerm.getId(), statusTerm.getId()));
        // 다른 사용자는 예전 연결(statusTerm)을 보고 있다
        assertThatThrownBy(() -> termService.relink(login(owner), status.getId(), new ColumnTermDto.Request(statusTerm.getId(), statusTerm.getId())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.COLUMN_TERM_CONFLICT);
        assertThat(termId(column(status))).isEqualTo(progressTerm.getId());
    }

    @Test
    @DisplayName("거부 : 승인된 컬럼, 용어 없는 컬럼을 용어가 있다고 본 요청(기준값 충돌 — DEC-26 부터 처음 연결은 지원), 다른 프로젝트의 컬럼·용어, 없는 컬럼·용어, 기준값 불일치, VIEWER(API 권한)")
    void rejects() {
        AccountDto user = login(owner);
        assertThatThrownBy(() -> termService.relink(user, approved.getId(), new ColumnTermDto.Request(progressTerm.getId(), statusTerm.getId())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        assertThatThrownBy(() -> termService.relink(user, noTerm.getId(), new ColumnTermDto.Request(progressTerm.getId(), statusTerm.getId())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.COLUMN_TERM_CONFLICT);
        Long foreignColumn = columnRepository.findAll().stream().filter(c -> other.getId().equals(c.getProjectId())).findFirst().orElseThrow().getId();
        assertThatThrownBy(() -> termService.relink(user, foreignColumn, new ColumnTermDto.Request(progressTerm.getId(), foreignTerm.getId())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.COLUMN_IS_NOT_DEFINED);
        assertThatThrownBy(() -> termService.relink(user, status.getId(), new ColumnTermDto.Request(foreignTerm.getId(), statusTerm.getId())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_TERM_DATA);
        assertThatThrownBy(() -> termService.relink(user, 999_999_999L, new ColumnTermDto.Request(progressTerm.getId(), statusTerm.getId())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.COLUMN_IS_NOT_DEFINED);
        assertThatThrownBy(() -> termService.relink(user, status.getId(), new ColumnTermDto.Request(999_999_999L, statusTerm.getId())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_TERM_DATA);
        assertThatThrownBy(() -> termService.relink(user, status.getId(), new ColumnTermDto.Request(progressTerm.getId(), null))).isInstanceOf(CustomException.class);
        AccountDto view = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        assertThatThrownBy(() -> columnApi.relinkTerm(status.getId(), new ColumnTermDto.Request(progressTerm.getId(), statusTerm.getId()), view))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(termId(column(status))).as("어느 거부도 연결을 바꾸지 않았다").isEqualTo(statusTerm.getId());
        assertThat(termId(column(approved))).isEqualTo(statusTerm.getId());
    }

    @Test
    @DisplayName("DEC-26 : 용어 없는 미승인 컬럼에 처음 연결, 연결 해제(기준값 확인), 이력에 연결 변경이 남고 다른 속성은 그대로")
    void firstLinkAndUnlink() {
        AccountDto user = login(owner);
        long historyBefore = histories(noTerm);
        ColumnTermDto.Result linked = termService.relink(user, noTerm.getId(), new ColumnTermDto.Request(statusTerm.getId(), null));
        assertThat(linked.changed()).isTrue();
        assertThat(linked.before()).isNull();
        assertThat(termId(column(noTerm))).isEqualTo(statusTerm.getId());
        assertThat(histories(noTerm)).isEqualTo(historyBefore + 1);
        // 그 사이 다른 사용자가 연결했는데 '연결 없음' 을 본 화면이 다시 연결하려 하면 충돌
        assertThatThrownBy(() -> termService.relink(user, noTerm.getId(), new ColumnTermDto.Request(progressTerm.getId(), null)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.COLUMN_TERM_CONFLICT);
        // 해제 : 화면이 읽은 연결과 같을 때만, 용어 칸을 함께 보내면 거부
        assertThatThrownBy(() -> termService.relink(user, noTerm.getId(), new ColumnTermDto.Request(statusTerm.getId(), statusTerm.getId(), true)))
                .isInstanceOf(CustomException.class);
        ColumnTermDto.Result unlinked = termService.relink(user, noTerm.getId(), new ColumnTermDto.Request(null, statusTerm.getId(), true));
        assertThat(unlinked.changed()).isTrue();
        assertThat(unlinked.after()).isNull();
        assertThat(termId(column(noTerm))).isNull();
        assertThat(histories(noTerm)).isEqualTo(historyBefore + 2);
        assertThatThrownBy(() -> termService.relink(user, approved.getId(), new ColumnTermDto.Request(null, statusTerm.getId(), true)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
    }

    @Test
    @DisplayName("DEC-26 일괄 : 여러 컬럼의 연결·변경·해제를 한 번에 — 하나라도 거부되면(승인 컬럼·기준값 충돌) 아무것도 바뀌지 않는다")
    void bulkLink() {
        AccountDto user = login(owner);
        // 승인된 컬럼이 섞이면 전체 거부
        assertThatThrownBy(() -> termService.bulk(user, new ColumnTermDto.BulkRequest(List.of(
                new ColumnTermDto.BulkItem(noTerm.getId(), statusTerm.getId(), null, null),
                new ColumnTermDto.BulkItem(approved.getId(), progressTerm.getId(), statusTerm.getId(), null)))))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        assertThat(termId(column(noTerm))).as("되돌림").isNull();
        // 같은 컬럼 두 번 거부
        assertThatThrownBy(() -> termService.bulk(user, new ColumnTermDto.BulkRequest(List.of(
                new ColumnTermDto.BulkItem(noTerm.getId(), statusTerm.getId(), null, null),
                new ColumnTermDto.BulkItem(noTerm.getId(), statusTerm.getId(), null, null))))).isInstanceOf(CustomException.class);
        // 정상 : 처음 연결 + 변경
        List<ColumnTermDto.Result> results = termService.bulk(user, new ColumnTermDto.BulkRequest(List.of(
                new ColumnTermDto.BulkItem(status.getId(), progressTerm.getId(), statusTerm.getId(), null),
                new ColumnTermDto.BulkItem(noTerm.getId(), statusTerm.getId(), null, null))));
        assertThat(results).allSatisfy(r -> assertThat(r.changed()).isTrue());
        assertThat(termId(column(noTerm))).isEqualTo(statusTerm.getId());
        assertThat(termId(column(status))).isEqualTo(progressTerm.getId());
    }

    @Test
    @DisplayName("동시 변경 : 같은 연결을 보고 두 사용자가 동시에 바꾸면 하나만 반영(다른 하나는 409), 이력은 한 번")
    void concurrentRelink() throws Exception {
        long historyBefore = histories(amount);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Account a : List.of(owner, member)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return termService.relink(login(a), amount.getId(), new ColumnTermDto.Request(progressTerm.getId(), statusTerm.getId())).changed();
                    } catch (CustomException e) {
                        return e.getErrorCode();
                    } finally {
                        SecurityTestSupport.clear();
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) results.add(f.get(30, TimeUnit.SECONDS));
            // 둘 다 같은 새 용어로 바꾸려 했다 : 먼저 잠근 쪽이 바꾸고, 뒤쪽은 지금 연결이 base 와 달라 409
            assertThat(results).containsExactlyInAnyOrder(true, ErrorCode.COLUMN_TERM_CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(termId(column(amount))).isEqualTo(progressTerm.getId());
        assertThat(histories(amount)).isEqualTo(historyBefore + 1);
    }

    @Test
    @DisplayName("AI 연계 : 실제 재연결 뒤 그 컬럼의 제안은 TERM_LINK_CHANGED(용어 이름 전후)로 미리 보기·승인 시 재검토, 업무 설명 유지. 같은 용어 재선택·다른 컬럼의 제안은 영향 없음")
    void aiReviewAfterRealRelink() {
        AiDescriptionProposal target = propose(status);
        AiDescriptionProposal untouched = propose(amount);
        // 같은 용어로 "바꾸기" 는 아무것도 하지 않으므로 재검토도 없다
        termService.relink(login(owner), status.getId(), new ColumnTermDto.Request(statusTerm.getId(), statusTerm.getId()));
        assertThat(queries.detail(user(owner), target.getId()).changes()).isEmpty();

        termService.relink(login(owner), status.getId(), new ColumnTermDto.Request(progressTerm.getId(), statusTerm.getId()));
        List<ProposalVersions.Change> changes = queries.detail(user(owner), target.getId()).changes();
        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("TERM_LINK_CHANGED");
            assertThat(c.diffs()).contains(new ProposalVersions.Diff("standardTermsName", "주문_상태_코드", "주문_처리_상태_코드"));
        });
        AiDictionaryDto.ReviewResult r = approve(target);
        assertThat(r.outcome()).isEqualTo("REVIEW_REQUIRED");
        AiDescriptionProposal required = proposals.findById(target.getId()).orElseThrow();
        assertThat(required.getStatus()).isEqualTo(ReviewStatus.REVIEW_REQUIRED);
        assertThat(required.getReviewReason()).startsWith("TERM_LINK_CHANGED COLUMN:");
        assertThat(column(status).getBizDescription()).as("재검토로 업무 설명은 바뀌지 않는다").isEqualTo("주문 진행 단계를 나타낸다");
        assertThatThrownBy(() -> approve(required)).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROPOSAL_INVALID_STATE);
        // 다른 컬럼(ORD_AMT)의 제안은 근거가 바뀌지 않았다
        assertThat(queries.detail(user(owner), untouched.getId()).changes()).isEmpty();
        assertThat(approve(untouched).outcome()).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ 준비

    private String ddl() {
        return tableDesignService.generateTableDdl(order.getId(), login(owner), null).ddl();
    }

    private long histories(ColumnInfo c) {
        return jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ?", Long.class, c.getId());
    }

    private Long termId(ColumnInfo c) {
        return jdbc.queryForObject("select STD_TRM_SN from TB_COL where COL_SN = ?", Long.class, c.getId());
    }

    private ColumnInfo column(String name, String desc, String type, Integer length, int sort, String biz, boolean pk, StandardTerm term) {
        return columnRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(order).tableName("TB_ORD").columnName(name).columnDesc(desc).dataType(type)
                .maxLength(length).sortOrder(sort).bizDescription(biz).isPk(pk).isNullable(!pk).standardTerms(term).isApproval(false).build());
    }

    private ColumnInfo column(ColumnInfo c) {
        return tx.execute(s -> {
            ColumnInfo found = columnRepository.findById(c.getId()).orElseThrow();
            if (found.getStandardTerms() != null) found.getStandardTerms().getId();
            return found;
        });
    }

    private Project project(Account manager, List<Account> members) {
        Project p = projectRepository.save(Project.builder().projectName("colterm-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(manager)
                .dbType(DbType.POSTGRESQL).build());
        for (Account a : members) projectMemberRepository.save(ProjectMember.builder().project(p).account(a).isActive(true).build());
        return p;
    }

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("colterm-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private AccountDto login(Account account) {
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(account.getId()), "ROLE_USER");
    }

    private ExecutionContext user(Account account) {
        return ExecutionContext.user(AccountDto.builder().id(String.valueOf(account.getId())).projectId(project.getId()).build());
    }

    private AiDescriptionProposal propose(ColumnInfo c) {
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", c.getId(), c.getColumnName() + " 설명", "INFERRED",
                List.of(Map.of("type", "COLUMN", "id", c.getId(), "name", c.getColumnName())), null, null, List.of()));
        return drafts.generate(user(owner), order.getId(), c.getId(), true, null).proposal();
    }

    private AiDictionaryDto.ReviewResult approve(AiDescriptionProposal p) {
        AiDescriptionProposal now = proposals.findById(p.getId()).orElseThrow();
        return reviews.approve(user(owner), now.getId(), new AiDictionaryDto.ReviewRequest(now.getVersion(), null, null));
    }
}
