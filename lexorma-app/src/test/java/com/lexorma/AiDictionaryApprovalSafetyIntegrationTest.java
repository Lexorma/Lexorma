package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.core.ai.service.AiDefaultProviderService;
import com.lexorma.core.ai.service.AiProviderService;
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
import com.lexorma.data.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal.ReviewStatus;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalHistoryRepository;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.data.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.data.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.data.ai.dictionary.service.AiDictionaryQueryService;
import com.lexorma.data.ai.dictionary.service.ProposalVersions;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.dto.BizDescriptionDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.BizDescriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI-5 승인 안전성 : 기존 업무 설명 교체(명시적 · 생성 때 기준값 비교), 생성 뒤(또는 생성 중) 수정된 업무 설명 보호, 재생성 뒤 승인, 동시 승인·수동 저장과 승인의 경합,
 * 일괄 승인의 항목별 보호, 표준 용어 연결 변경 재검토 사유(TERM_LINK_CHANGED). AI 서버는 가짜.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class AiDictionaryApprovalSafetyIntegrationTest {

    @Autowired BizDescriptionService bizDescriptions;
    @Autowired AiDescriptionDraftService drafts;
    @Autowired AiDescriptionReviewService reviews;
    @Autowired AiDictionaryQueryService queries;
    @Autowired AiDescriptionProposalRepository proposals;
    @Autowired AiDescriptionProposalHistoryRepository histories;
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
    private Account manager;
    private Account member;
    private Project project;
    private TableInfo order;
    private ColumnInfo orderId;
    private ColumnInfo status;
    private ColumnInfo orderedAt;
    private StandardTerm statusTerm;
    private StandardTerm progressTerm;

    @BeforeEach
    void setUp() throws IOException {
        defaults.clear();
        owner = account("ROLE_USER");
        manager = account("ROLE_MANAGER");
        member = account("ROLE_USER");
        project = projectRepository.save(Project.builder().projectName("ai5s-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        for (Account a : List.of(owner, manager, member)) projectMemberRepository.save(ProjectMember.builder().project(project).account(a).isActive(true).build());
        tx.executeWithoutResult(s -> {
            Long p = project.getId();
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            StandardDomain code = domainRepository.save(StandardDomain.builder().projectId(p).commonStandardDomainName("코드S").commonStandardDomainCategory("코드").dataType("VARCHAR").isApproval(true).build());
            statusTerm = termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("주문_상태_코드").commonStandardTermAbbreviation("ORD_STTS_CD")
                    .standardDomain(code).isApproval(true).build());
            progressTerm = termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("주문_진행_상태_코드").commonStandardTermAbbreviation("ORD_PRGRS_STTS_CD")
                    .standardDomain(code).isApproval(true).build());
            order = tableRepository.save(TableInfo.builder().projectId(p).schema(schema).tableName("TB_ORD").tableKorName("주문").tableDescription("주문 테이블")
                    .isApproval(false).isMasterTable(false).build());
            // ORD_ID 만 업무 설명이 있다 (사람이 쓴 설명)
            orderId = column("ORD_ID", "주문 번호", "BIGINT", 1, "주문을 구분하는 번호", null);
            status = column("ORD_STTS_CD", "주문 상태 코드", "VARCHAR", 2, null, statusTerm);
            orderedAt = column("ORD_DT", "주문 일시", "TIMESTAMP", 3, null, null);
        });
        ollama = new ScriptedOllama();
        ollama.show(List.of("completion", "tools"), 32768);
        providerId = providers.create(new ProviderRequest("AI-5S " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), "gemma3:4b", true, null)).id();
        ruleId = rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "AI-5 승인 안전성")).id();
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

    // ------------------------------------------------------------------ Part A : 기존 업무 설명 보호

    @Test
    @DisplayName("상황 A : 업무 설명이 없으면 그냥 승인, 있으면 교체 대상으로 표시(생성 때 기준값 제공)되고 생성 뒤 바뀌지 않았으면 승인으로 교체 — 이력 출처 AI_APPROVAL, 변경 전후 남음")
    void approveAndReplace() {
        AiDescriptionProposal empty = propose(orderedAt);
        assertThat(summary(empty).replacesBizDescription()).isFalse();
        assertThat(queries.detail(user(owner), empty.getId()).baseBizDescription()).isNull();
        assertThat(approve(owner, empty).outcome()).isEqualTo("APPROVED");
        assertThat(column(orderedAt).getBizDescription()).isEqualTo("ORD_DT 설명");

        AiDescriptionProposal replace = propose(orderId);
        assertThat(summary(replace).replacesBizDescription()).isTrue();
        AiDictionaryDto.ProposalDetail detail = queries.detail(user(owner), replace.getId());
        assertThat(detail.baseBizDescription()).isEqualTo("주문을 구분하는 번호");
        assertThat(detail.current().bizDescription()).isEqualTo("주문을 구분하는 번호");
        assertThat(detail.changes()).isEmpty();
        assertThat(approve(owner, replace).outcome()).isEqualTo("APPROVED");
        assertThat(column(orderId).getBizDescription()).isEqualTo("ORD_ID 설명");
        assertThat(jdbc.queryForList("select CHG_SRC_CD from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ? order by MDL_CHG_HSTRY_SN", String.class, orderId.getId()))
                .last().isEqualTo("AI_APPROVAL");
        assertThat(jdbc.queryForObject("select CHG_BFR_CN from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ? order by MDL_CHG_HSTRY_SN desc limit 1",
                String.class, orderId.getId())).contains("주문을 구분하는 번호");
    }

    @Test
    @DisplayName("상황 B : 초안 생성 뒤 다른 사용자가 수정하면 승인 차단(REVIEW_REQUIRED · 기준→지금 값), 최신 설명 유지, 모델 이력에 AI 승인 없음. 다시 눌러도·확인해도 우회 불가")
    void staleProposalBlocked() {
        AiDescriptionProposal p = propose(orderId);
        bizDescriptions.update(login(member), new BizDescriptionDto.Request(order.getId(), orderId.getId(), "A 가 고친 설명", "주문을 구분하는 번호"));
        AiDictionaryDto.ProposalDetail preview = queries.detail(user(owner), p.getId());
        assertThat(preview.baseBizDescription()).isEqualTo("주문을 구분하는 번호");
        assertThat(preview.current().bizDescription()).isEqualTo("A 가 고친 설명");
        assertThat(preview.changes()).extracting(ProposalVersions.Change::kind).containsExactly("BIZ_DESCRIPTION_CHANGED");

        AiDictionaryDto.ReviewResult r = approve(manager, p);
        assertThat(r.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(r.changes()).singleElement().satisfies(c -> assertThat(c.diffs())
                .containsExactly(new ProposalVersions.Diff("bizDescription", "주문을 구분하는 번호", "A 가 고친 설명")));
        assertThat(column(orderId).getBizDescription()).isEqualTo("A 가 고친 설명");
        assertThat(aiApprovals(orderId)).isZero();
        // 다시 승인 → 상태가 막는다, 변경 확인 후 계속 검토 → 업무 설명 변경은 확인으로 풀 수 없다
        AiDescriptionProposal required = proposals.findById(p.getId()).orElseThrow();
        assertThatThrownBy(() -> approve(owner, required)).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROPOSAL_INVALID_STATE);
        AiDictionaryDto.RevalidateResult ack = reviews.revalidate(user(owner), required.getId(), new AiDictionaryDto.RevalidateRequest(required.getVersion(), true, "그래도 승인"));
        assertThat(ack.outcome()).isEqualTo("STILL_REQUIRED");
        assertThat(ack.acknowledgeable()).isFalse();
        assertThat(column(orderId).getBizDescription()).isEqualTo("A 가 고친 설명");
        // 제안 이력 : 생성 → 재검토 필요 (승인 없음)
        assertThat(histories.findAllByProjectIdAndProposalIdOrderByIdAsc(project.getId(), p.getId())).extracting(h -> h.getAction().name())
                .containsExactly("GENERATE", "REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("상황 C·D : 수동 수정 뒤 새 초안은 최신 설명을 기준으로 승인·교체 가능, 재생성 뒤 다시 수정하면 재생성된 초안도 승인 차단. 앞 제안은 대체됨으로 이어진다")
    void regenerateThenApproveOrBlock() {
        AiDescriptionProposal first = propose(orderId);
        bizDescriptions.update(login(member), new BizDescriptionDto.Request(order.getId(), orderId.getId(), "사람이 쓴 설명", "주문을 구분하는 번호"));
        assertThat(approve(owner, first).outcome()).isEqualTo("REVIEW_REQUIRED");
        // C : 최신 설명으로 새 초안 → 기준값은 앞 제안의 것이 아니라 지금 값
        AiDescriptionProposal second = propose(orderId);
        assertThat(proposals.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.SUPERSEDED);
        assertThat(queries.detail(user(owner), second.getId()).baseBizDescription()).isEqualTo("사람이 쓴 설명");
        assertThat(summary(second).replacesBizDescription()).isTrue();
        assertThat(approve(owner, second).outcome()).isEqualTo("APPROVED");
        assertThat(column(orderId).getBizDescription()).isEqualTo("ORD_ID 설명");

        // D : 새 초안 → 다른 사용자가 수정 → 그 초안 승인 차단
        AiDescriptionProposal third = propose(orderId);
        bizDescriptions.update(login(member), new BizDescriptionDto.Request(order.getId(), orderId.getId(), "다시 고친 설명", "ORD_ID 설명"));
        AiDictionaryDto.ReviewResult r = approve(owner, third);
        assertThat(r.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(r.changes()).extracting(ProposalVersions.Change::kind).contains("BIZ_DESCRIPTION_CHANGED");
        assertThat(column(orderId).getBizDescription()).isEqualTo("다시 고친 설명");
        // 앞서 승인된 제안과 그 확정 문구는 그대로
        AiDescriptionProposal approved = proposals.findById(second.getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(approved.getConfirmedDescription()).isEqualTo("ORD_ID 설명");
    }

    @Test
    @DisplayName("생성 중 수정 : AI 응답을 기다리는 동안 업무 설명이 바뀌면 기준값은 AI 를 부르기 전 값 — 제안은 처음부터 재검토 필요, 승인으로 덮어쓰지 못한다")
    void editDuringGeneration() {
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", orderId.getId(), "ORD_ID 설명", "INFERRED",
                List.of(Map.of("type", "COLUMN", "id", orderId.getId(), "name", "ORD_ID")), null, null, List.of()), () -> {
            SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(member.getId()), "ROLE_USER");
            bizDescriptions.update(SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(member.getId()), "ROLE_USER"),
                    new BizDescriptionDto.Request(order.getId(), orderId.getId(), "생성 중 고친 설명", "주문을 구분하는 번호"));
        });
        AiDescriptionProposal p = drafts.generate(user(owner), order.getId(), orderId.getId(), true, null).proposal();
        assertThat(column(orderId).getBizDescription()).isEqualTo("생성 중 고친 설명");
        AiDescriptionProposal saved = proposals.findById(p.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReviewStatus.REVIEW_REQUIRED);
        AiDictionaryDto.ProposalDetail detail = queries.detail(user(owner), p.getId());
        assertThat(detail.baseBizDescription()).isEqualTo("주문을 구분하는 번호");
        assertThat(detail.changes()).extracting(ProposalVersions.Change::kind).contains("BIZ_DESCRIPTION_CHANGED");
        assertThatThrownBy(() -> approve(owner, saved)).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROPOSAL_INVALID_STATE);
        assertThat(column(orderId).getBizDescription()).isEqualTo("생성 중 고친 설명");
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    @DisplayName("동시 승인 : 같은 제안을 두 승인자가 같은 버전으로 동시에 승인하면 하나만 반영(다른 하나는 AI_PROPOSAL_CONFLICT), 승인 이력·모델 이력은 한 번")
    void concurrentApprovals() throws Exception {
        AiDescriptionProposal p = propose(orderId);
        AiDescriptionProposal now = proposals.findById(p.getId()).orElseThrow();
        List<Object> outcomes = race(
                () -> reviews.approve(user(owner), now.getId(), new AiDictionaryDto.ReviewRequest(now.getVersion(), null, null)).outcome(),
                () -> reviews.approve(user(manager), now.getId(), new AiDictionaryDto.ReviewRequest(now.getVersion(), "다른 승인자의 수정 문구", null)).outcome());
        assertThat(outcomes).filteredOn("APPROVED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof CustomException c && c.getErrorCode() == ErrorCode.AI_PROPOSAL_CONFLICT).hasSize(1);
        AiDescriptionProposal done = proposals.findById(p.getId()).orElseThrow();
        assertThat(column(orderId).getBizDescription()).isEqualTo(done.getConfirmedDescription());
        assertThat(histories.findAllByProjectIdAndProposalIdOrderByIdAsc(project.getId(), p.getId())).filteredOn(h -> h.getAction().name().startsWith("APPROVE")).hasSize(1);
        assertThat(aiApprovals(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("수동 저장 ↔ AI 승인 동시 : 어느 쪽이 먼저든 하나만 반영된다 — 수동이 먼저면 승인은 재검토 필요, 승인이 먼저면 수동 저장은 409. 값·제안 상태·이력이 서로 맞다")
    void manualSaveRacesApproval() throws Exception {
        for (int round = 0; round < 3; round++) {
            String base = column(orderId).getBizDescription();
            AiDescriptionProposal p = propose(orderId);
            AiDescriptionProposal now = proposals.findById(p.getId()).orElseThrow();
            long approvalsBefore = aiApprovals(orderId);
            String manual = "수동 저장 " + round;
            List<Object> outcomes = race(
                    () -> bizDescriptions.update(SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(member.getId()), "ROLE_USER"),
                            new BizDescriptionDto.Request(order.getId(), orderId.getId(), manual, base)).bizDescription(),
                    () -> reviews.approve(user(owner), now.getId(), new AiDictionaryDto.ReviewRequest(now.getVersion(), null, null)).outcome());
            String value = column(orderId).getBizDescription();
            ReviewStatus statusAfter = proposals.findById(p.getId()).orElseThrow().getStatus();
            if (manual.equals(outcomes.get(0))) {
                assertThat(outcomes.get(1)).isEqualTo("REVIEW_REQUIRED");
                assertThat(value).isEqualTo(manual);
                assertThat(statusAfter).isEqualTo(ReviewStatus.REVIEW_REQUIRED);
                assertThat(aiApprovals(orderId)).isEqualTo(approvalsBefore);
            } else {
                assertThat(outcomes.get(0)).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT));
                assertThat(outcomes.get(1)).isEqualTo("APPROVED");
                assertThat(value).isEqualTo("ORD_ID 설명");
                assertThat(statusAfter).isEqualTo(ReviewStatus.APPROVED);
                assertThat(aiApprovals(orderId)).isEqualTo(approvalsBefore + 1);
            }
        }
    }

    @Test
    @DisplayName("일괄 승인 : 교체 대상 표시, 항목마다 생성 때 기준값을 다시 비교 — 충돌한 항목만 재검토 필요(최신 설명 유지), 나머지는 승인")
    void bulkApprovalPartialConflict() {
        AiDescriptionProposal replace = propose(orderId);
        AiDescriptionProposal fresh = propose(status);
        AiDescriptionProposal stale = propose(orderedAt);
        bizDescriptions.update(login(member), new BizDescriptionDto.Request(order.getId(), orderedAt.getId(), "그 사이 쓴 설명", ""));
        assertThat(List.of(replace, fresh, stale)).extracting(p -> summary(p).replacesBizDescription()).containsExactly(true, false, false);

        List<AiDictionaryDto.BulkItem> items = List.of(replace, fresh, stale).stream()
                .map(p -> new AiDictionaryDto.BulkItem(p.getId(), proposals.findById(p.getId()).orElseThrow().getVersion())).toList();
        List<AiDictionaryDto.BulkReviewItem> results = reviews.bulk(user(owner), new AiDictionaryDto.BulkReviewRequest("APPROVE", items, null));
        assertThat(results).extracting(AiDictionaryDto.BulkReviewItem::outcome).containsExactly("APPROVED", "APPROVED", "REVIEW_REQUIRED");
        assertThat(column(orderId).getBizDescription()).isEqualTo("ORD_ID 설명");
        assertThat(column(status).getBizDescription()).isEqualTo("ORD_STTS_CD 설명");
        assertThat(column(orderedAt).getBizDescription()).isEqualTo("그 사이 쓴 설명");
        assertThat(aiApprovals(orderedAt)).isZero();
        // 같은 요청을 다시 보내도 (버전이 바뀌어) 아무것도 바뀌지 않는다
        assertThat(reviews.bulk(user(owner), new AiDictionaryDto.BulkReviewRequest("APPROVE", items, null))).extracting(AiDictionaryDto.BulkReviewItem::outcome).containsOnly("FAILED");
        assertThat(column(orderedAt).getBizDescription()).isEqualTo("그 사이 쓴 설명");
    }

    // ------------------------------------------------------------------ Part C : 표준 용어 연결 변경

    @Test
    @DisplayName("표준 용어 연결 변경 : MODIFIED 가 아닌 TERM_LINK_CHANGED 로 변경 전후 용어 이름·시점을 준다. 다른 필드도 바뀌면 둘로 나뉜다. 재검증(확인)으로 검토 중으로 돌릴 수 있다")
    void termLinkChange() {
        AiDescriptionProposal p = propose(status);
        tx.executeWithoutResult(s -> columnRepository.findById(status.getId()).orElseThrow().setStandardTerms(termRepository.getReferenceById(progressTerm.getId())));
        List<ProposalVersions.Change> changes = queries.detail(user(owner), p.getId()).changes();
        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("TERM_LINK_CHANGED");
            assertThat(c.fields()).containsExactlyInAnyOrder("standardTermsId", "standardTermsName");
            assertThat(c.diffs()).contains(new ProposalVersions.Diff("standardTermsName", "주문_상태_코드", "주문_진행_상태_코드"));
            assertThat(c.changedAt()).isNotNull();
        });
        AiDictionaryDto.ReviewResult r = approve(owner, p);
        assertThat(r.outcome()).isEqualTo("REVIEW_REQUIRED");
        AiDescriptionProposal required = proposals.findById(p.getId()).orElseThrow();
        assertThat(required.getReviewReason()).startsWith("TERM_LINK_CHANGED");
        assertThat(column(status).getBizDescription()).isNull();
        // 재검증 : 내용 변경이라 확인으로 풀 수 있다 → 검토 중 (자동 승인 없음)
        AiDictionaryDto.RevalidateResult check = reviews.revalidate(user(owner), required.getId(), new AiDictionaryDto.RevalidateRequest(required.getVersion(), false, null));
        assertThat(check.acknowledgeable()).isTrue();
        assertThat(reviews.revalidate(user(owner), required.getId(), new AiDictionaryDto.RevalidateRequest(required.getVersion(), true, "용어만 바뀌어 설명은 그대로 맞다")).outcome())
                .isEqualTo("REVIEWABLE");
        assertThat(proposals.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.REVIEWED);
        assertThat(column(status).getBizDescription()).isNull();

        // 용어 연결과 타입이 함께 바뀌면 둘로 나뉜다 (타입은 그대로 MODIFIED)
        AiDescriptionProposal both = propose(status);
        tx.executeWithoutResult(s -> {
            ColumnInfo c = columnRepository.findById(status.getId()).orElseThrow();
            c.setStandardTerms(termRepository.getReferenceById(statusTerm.getId()));
            c.setDataType("CHAR");
        });
        assertThat(queries.detail(user(owner), both.getId()).changes()).extracting(ProposalVersions.Change::kind, ProposalVersions.Change::fields)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("MODIFIED", List.of("dataType")),
                        org.assertj.core.groups.Tuple.tuple("TERM_LINK_CHANGED", List.of("standardTermsId", "standardTermsName")));
        // 연결 해제 : 변경 후 없음
        AiDescriptionProposal removed = propose(status);
        tx.executeWithoutResult(s -> columnRepository.findById(status.getId()).orElseThrow().setStandardTerms(null));
        assertThat(queries.detail(user(owner), removed.getId()).changes()).singleElement()
                .satisfies(c -> assertThat(c.diffs()).contains(new ProposalVersions.Diff("standardTermsName", "주문_상태_코드", null)));
    }

    @Test
    @DisplayName("변경 내용을 알 수 없으면 기존 사유 그대로 : 이력 없이 수정 일시만 바뀌면 MODIFIED_UNKNOWN (용어 연결로 꾸며내지 않는다)")
    void unknownChangeKeepsGenericReason() {
        AiDescriptionProposal p = propose(status);
        jdbc.update("update TB_COL set MDFCN_DT = MDFCN_DT + INTERVAL '1' MINUTE where COL_SN = ?", status.getId());
        assertThat(queries.detail(user(owner), p.getId()).changes()).extracting(ProposalVersions.Change::kind).containsExactly("MODIFIED_UNKNOWN");
    }

    // ------------------------------------------------------------------ 준비

    /** 두 작업을 동시에 시작한다. 결과 또는 던진 예외 (순서대로) */
    private List<Object> race(Callable<Object> a, Callable<Object> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (CustomException e) {
                        return e;
                    } finally {
                        SecurityTestSupport.clear();
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) results.add(f.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long aiApprovals(ColumnInfo c) {
        return jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ? and CHG_SRC_CD = 'AI_APPROVAL'", Long.class, c.getId());
    }

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai5s-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private ColumnInfo column(String name, String desc, String type, int sort, String biz, StandardTerm term) {
        return columnRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(order).tableName(order.getTableName()).columnName(name).columnDesc(desc)
                .dataType(type).sortOrder(sort).bizDescription(biz).standardTerms(term).isPk(false).isNullable(true).isApproval(false).build());
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

    private AiDictionaryDto.ProposalSummary summary(AiDescriptionProposal p) {
        return queries.detail(user(owner), p.getId()).proposal();
    }

    private AiDictionaryDto.ReviewResult approve(Account approver, AiDescriptionProposal p) {
        AiDescriptionProposal now = proposals.findById(p.getId()).orElseThrow();
        return reviews.approve(user(approver), now.getId(), new AiDictionaryDto.ReviewRequest(now.getVersion(), null, null));
    }

    private ColumnInfo column(ColumnInfo c) {
        return columnRepository.findById(c.getId()).orElseThrow();
    }
}
