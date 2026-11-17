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
import com.lexorma.ai.dictionary.repository.AiDescriptionProposalHistoryRepository;
import com.lexorma.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.ai.dictionary.service.AiDescriptionJobService;
import com.lexorma.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.ai.dictionary.service.AiDictionaryQueryService;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.controller.BizDescriptionRestController;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 업무 설명 관리와 AI-5 제안 워크플로 보완 : 업무 설명 직접 작성·수정·삭제(AI 없이, 충돌·이력 출처), 생성 대상 판정(테이블·하위 컬럼·고른 컬럼·업무 설명 있는 것 제외·
 * 검토 대기 건너뜀)과 대상 상세, 재검토 필요 해결(근거 재검증·변경 확인 후 계속 검토·종결·재생성 — 이력 연결). AI 서버는 가짜.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class AiDictionaryWorkflowIntegrationTest {

    @Autowired BizDescriptionService bizDescriptions;
    @Autowired BizDescriptionRestController bizDescriptionApi;
    @Autowired AiDescriptionDraftService drafts;
    @Autowired AiDescriptionReviewService reviews;
    @Autowired AiDescriptionJobService jobs;
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
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private ScriptedOllama ollama;
    private Long providerId;
    private Long ruleId;
    private Account owner;
    private Account member;
    private Account viewer;
    private Project project;
    private TableInfo order;
    private TableInfo payment;
    private ColumnInfo orderId;
    private ColumnInfo status;
    private ColumnInfo orderedAt;

    @BeforeEach
    void setUp() throws IOException {
        defaults.clear();
        owner = account("ROLE_USER");
        member = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        project = projectRepository.save(Project.builder().projectName("ai5w-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        for (Account a : List.of(owner, member, viewer)) projectMemberRepository.save(ProjectMember.builder().project(project).account(a).isActive(true).build());
        tx.executeWithoutResult(s -> {
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            // 주문 : 테이블 업무 설명 있음, ORD_ID 만 업무 설명 있음 · 결제 : 아무 설명 없음
            order = tableRepository.save(TableInfo.builder().projectId(project.getId()).schema(schema).tableName("TB_ORD").tableKorName("주문").tableDescription("주문 테이블")
                    .bizDescription("고객이 접수한 주문").isApproval(true).isMasterTable(false).build());
            orderId = column(order, "ORD_ID", "주문 번호", "BIGINT", 1, "주문을 구분하는 번호", true);
            status = column(order, "ORD_STTS_CD", "주문 상태 코드", "VARCHAR", 2, null, false);
            orderedAt = column(order, "ORD_DT", "주문 일시", "TIMESTAMP", 3, null, false);
            payment = tableRepository.save(TableInfo.builder().projectId(project.getId()).schema(schema).tableName("TB_PAY").tableKorName("결제").tableDescription("결제")
                    .isApproval(false).isMasterTable(false).build());
            column(payment, "PAY_AMT", "결제 금액", "INTEGER", 1, null, false);
        });
        ollama = new ScriptedOllama();
        ollama.show(List.of("completion", "tools"), 32768);
        providerId = providers.create(new ProviderRequest("AI-5W " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), "gemma3:4b", true, null)).id();
        ruleId = rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "AI-5 워크플로")).id();
        defaults.set(new AiProviderDto.DefaultProviderRequest(providerId));
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityTestSupport.clear();
        defaults.clear();
        providers.delete(providerId);
        rules.delete(ruleId);
        ollama.close();
    }

    // ------------------------------------------------------------------ Part A : 업무 설명 직접 관리

    @Test
    @DisplayName("직접 관리 : AI 없이 테이블·컬럼 업무 설명 작성·수정·삭제(승인된 객체도), 읽은 뒤 바뀌었으면 409, VIEWER 는 403. 이력 출처는 직접 수정 USER · AI 승인 AI_APPROVAL")
    void directEdit() {
        defaults.clear(); // AI Provider 없이
        AccountDto user = login(owner);
        // 테이블 (승인된 객체) : 수정 → 지우기
        assertThat(bizDescriptions.update(user, new BizDescriptionDto.Request(order.getId(), null, "고객 주문과 처리 상태", "고객이 접수한 주문")).bizDescription()).isEqualTo("고객 주문과 처리 상태");
        assertThat(bizDescriptions.update(user, new BizDescriptionDto.Request(order.getId(), null, "", "고객 주문과 처리 상태")).bizDescription()).isNull();
        assertThat(table(order).getIsApproval()).as("객체 승인 여부 그대로").isTrue();
        // 컬럼 : 작성 → 수정
        bizDescriptions.update(user, new BizDescriptionDto.Request(order.getId(), status.getId(), "주문 진행 단계", ""));
        assertThat(column(status).getBizDescription()).isEqualTo("주문 진행 단계");
        // 동시 수정 : 같은 값(주문 진행 단계)을 보고 연 두 사용자 — 앞 사람 저장 뒤 뒷사람은 409, 값은 앞 사람 것
        bizDescriptions.update(login(member), new BizDescriptionDto.Request(order.getId(), status.getId(), "A 의 설명", "주문 진행 단계"));
        assertThatThrownBy(() -> bizDescriptions.update(login(owner), new BizDescriptionDto.Request(order.getId(), status.getId(), "B 의 설명", "주문 진행 단계")))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        assertThat(column(status).getBizDescription()).isEqualTo("A 의 설명");
        // 다른 테이블의 컬럼 ID · 기준 값 없음 → 거부
        assertThatThrownBy(() -> bizDescriptions.update(user, new BizDescriptionDto.Request(payment.getId(), status.getId(), "x", ""))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> bizDescriptions.update(user, new BizDescriptionDto.Request(order.getId(), status.getId(), "x", null))).isInstanceOf(CustomException.class);
        // VIEWER : API 권한
        AccountDto view = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        assertThatThrownBy(() -> bizDescriptionApi.update(view, new BizDescriptionDto.Request(order.getId(), status.getId(), "x", "A 의 설명"))).isInstanceOf(AccessDeniedException.class);

        // AI 승인 → 출처 AI_APPROVAL, 직접 수정 → USER. 승인 뒤 화면을 연 때의 값으로 고치면 409
        useProvider();
        AiDescriptionProposal p = propose(orderedAt);
        approve(p);
        String approved = column(orderedAt).getBizDescription();
        assertThatThrownBy(() -> bizDescriptions.update(login(owner), new BizDescriptionDto.Request(order.getId(), orderedAt.getId(), "사람이 쓴 설명", "")))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        bizDescriptions.update(login(owner), new BizDescriptionDto.Request(order.getId(), orderedAt.getId(), "사람이 쓴 설명", approved));
        assertThat(jdbc.queryForList("select CHG_SRC_CD from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ? order by MDL_CHG_HSTRY_SN", String.class, orderedAt.getId()))
                .containsSubsequence("AI_APPROVAL", "USER");
        // 직접 고친 뒤 오래된 제안 승인 → 덮어쓰지 않고 재검토 필요
        AiDescriptionProposal stale = propose(status);
        bizDescriptions.update(login(owner), new BizDescriptionDto.Request(order.getId(), status.getId(), "다시 사람이 쓴 설명", column(status).getBizDescription()));
        AiDictionaryDto.ReviewResult r = approve(stale);
        assertThat(r.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(r.changes()).extracting(c -> c.kind()).contains("BIZ_DESCRIPTION_CHANGED");
        assertThat(column(status).getBizDescription()).isEqualTo("다시 사람이 쓴 설명");
    }

    // ------------------------------------------------------------------ Part B·C : 생성 대상 판정과 상세

    @Test
    @DisplayName("대상 판정 : 테이블 + 설명 없는 하위 컬럼, 테이블 설명이 있으면 컬럼만, 컬럼만 생성, 고른 컬럼, 중복 제거, 업무 설명 있는 것 제외(다시 만들기 켜면 포함), 검토 대기 제안은 건너뜀. 실행도 같은 판정")
    void targetResolution() throws Exception {
        // 주문 테이블 선택 + 하위 컬럼 : 테이블은 업무 설명이 있어 제외, ORD_ID 제외, ORD_STTS_CD·ORD_DT 생성
        AiDictionaryDto.JobPreview both = jobs.preview(user(owner), request(List.of(new AiDictionaryDto.Target(order.getId(), null)), true, true, true));
        assertThat(both.items()).extracting(AiDictionaryDto.PreviewItem::name, AiDictionaryDto.PreviewItem::outcome).containsExactly(
                tuple("TB_ORD", "DESCRIBED"), tuple("TB_ORD.ORD_ID", "DESCRIBED"), tuple("TB_ORD.ORD_STTS_CD", "CREATE"), tuple("TB_ORD.ORD_DT", "CREATE"));
        assertThat(both).extracting(AiDictionaryDto.JobPreview::create, AiDictionaryDto.JobPreview::described, AiDictionaryDto.JobPreview::targets).containsExactly(2, 2, 2);
        // 결제 테이블 : 테이블 설명만 / 컬럼만
        assertThat(jobs.preview(user(owner), request(List.of(new AiDictionaryDto.Target(payment.getId(), null)), true, false, true)).items())
                .extracting(AiDictionaryDto.PreviewItem::name).containsExactly("TB_PAY");
        assertThat(jobs.preview(user(owner), request(List.of(new AiDictionaryDto.Target(payment.getId(), null)), false, true, true)).items())
                .extracting(AiDictionaryDto.PreviewItem::name).containsExactly("TB_PAY.PAY_AMT");
        // 고른 컬럼 + 같은 컬럼을 포함한 테이블 확장 → 한 번만, 업무 설명 있는 컬럼은 다시 만들기를 켜야 포함
        List<AiDictionaryDto.Target> mixed = List.of(new AiDictionaryDto.Target(order.getId(), status.getId()), new AiDictionaryDto.Target(order.getId(), null),
                new AiDictionaryDto.Target(order.getId(), orderId.getId()));
        assertThat(jobs.preview(user(owner), request(mixed, false, true, true)).items()).extracting(AiDictionaryDto.PreviewItem::name)
                .containsExactly("TB_ORD.ORD_STTS_CD", "TB_ORD.ORD_ID", "TB_ORD.ORD_DT");
        assertThat(jobs.preview(user(owner), request(mixed, false, true, false)).items()).extracting(AiDictionaryDto.PreviewItem::outcome).containsOnly("CREATE");
        // 검토 대기 제안이 있으면 건너뜀으로 보이고, 실행 대상에는 남는다 (실행 때 SKIPPED)
        propose(status);
        assertThat(jobs.preview(user(owner), request(List.of(new AiDictionaryDto.Target(order.getId(), null)), true, true, true)).items())
                .extracting(AiDictionaryDto.PreviewItem::name, AiDictionaryDto.PreviewItem::outcome).contains(tuple("TB_ORD.ORD_STTS_CD", "OPEN_PROPOSAL"), tuple("TB_ORD.ORD_DT", "CREATE"));
        // 실행 : 같은 판정 — 2개(ORD_STTS_CD 건너뜀, ORD_DT 생성)
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", orderedAt.getId(), "주문 일시로 보입니다", "INFERRED",
                List.of(Map.of("type", "COLUMN", "id", orderedAt.getId(), "name", "ORD_DT")), null, null, List.of()));
        AiDictionaryDto.JobSummary job = jobs.start(user(owner), request(List.of(new AiDictionaryDto.Target(order.getId(), null)), true, true, true));
        assertThat(job.targetCount()).isEqualTo(2);
        AiDictionaryDto.JobSummary done = awaitJob(job.id());
        assertThat(done).extracting(AiDictionaryDto.JobSummary::completedCount, AiDictionaryDto.JobSummary::skippedCount).containsExactly(1, 1);
        assertThat(column(orderId).getBizDescription()).as("업무 설명 있는 대상은 건드리지 않는다").isEqualTo("주문을 구분하는 번호");
        // 아무것도 없으면 거부
        assertThatThrownBy(() -> jobs.start(user(owner), request(List.of(new AiDictionaryDto.Target(order.getId(), orderId.getId())), true, false, true)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_JOB_INVALID);
    }

    @Test
    @DisplayName("대상 상세 : 테이블 목록은 컬럼 수·업무 설명 없는 컬럼 수만, 펼친 테이블은 스키마·논리명·물리 설명·원본 COMMENT·업무 설명·타입·PK 를 '없음'(null)과 구분해 준다")
    void targetDetails() {
        List<AiDictionaryDto.TargetRow> list = queries.targets(user(viewer), null, false, null);
        AiDictionaryDto.TargetRow ord = list.stream().filter(r -> r.name().equals("TB_ORD")).findFirst().orElseThrow();
        assertThat(ord.detail().columnCount()).isEqualTo(3);
        assertThat(ord.detail().missingColumnCount()).isEqualTo(2);
        assertThat(ord.detail().modelDescription()).as("목록에는 설명 본문을 싣지 않는다").isNull();
        // 업무 설명이 빠진 테이블만 : 테이블 설명이 있어도 설명 없는 컬럼이 있으면 남는다
        assertThat(queries.targets(user(viewer), null, true, null)).extracting(AiDictionaryDto.TargetRow::name).contains("TB_ORD", "TB_PAY");

        List<AiDictionaryDto.TargetRow> rows = queries.targets(user(viewer), order.getId(), false, null);
        AiDictionaryDto.TargetDetail t = rows.getFirst().detail();
        assertThat(t).extracting(AiDictionaryDto.TargetDetail::schemaName, AiDictionaryDto.TargetDetail::logicalName, AiDictionaryDto.TargetDetail::modelDescription,
                AiDictionaryDto.TargetDetail::sourceComment, AiDictionaryDto.TargetDetail::bizDescription).containsExactly(SchemaInfo.DEFAULT_NAME, "주문", "주문 테이블", null, "고객이 접수한 주문");
        AiDictionaryDto.TargetDetail id = rows.stream().filter(r -> "TB_ORD.ORD_ID".equals(r.name())).findFirst().orElseThrow().detail();
        assertThat(id).extracting(AiDictionaryDto.TargetDetail::physicalName, AiDictionaryDto.TargetDetail::dataType, AiDictionaryDto.TargetDetail::pk,
                AiDictionaryDto.TargetDetail::modelDescription, AiDictionaryDto.TargetDetail::bizDescription).containsExactly("ORD_ID", "BIGINT", true, "주문 번호", "주문을 구분하는 번호");
    }

    // ------------------------------------------------------------------ Part D : 재검토 필요 해결

    @Test
    @DisplayName("재검토 : 근거 재검증 — 내용 변경뿐이면 사유와 함께 확인해 기준을 옮기고 검토 중으로(그 뒤 승인 가능), 업무 설명이 바뀌었으면 계속 막힘. 승인 권한만. AI 없이도 된다")
    void revalidate() {
        AiDescriptionProposal p = propose(status);
        tx.executeWithoutResult(s -> columnRepository.findById(status.getId()).orElseThrow().setColumnDesc("주문 진행 상태 코드"));
        assertThat(approve(p).outcome()).isEqualTo("REVIEW_REQUIRED");
        AiDescriptionProposal required = proposals.findById(p.getId()).orElseThrow();
        defaults.clear(); // AI Provider 없이 재검증·종결
        // 승인 권한이 없는 사용자
        assertThatThrownBy(() -> reviews.revalidate(user(member), required.getId(), new AiDictionaryDto.RevalidateRequest(required.getVersion(), false, null)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.APPROVAL_PERMISSION_DENIED);
        // 확인 없이 : 그대로 재검토 필요, 확인 가능, 변경 전후
        AiDictionaryDto.RevalidateResult check = reviews.revalidate(user(owner), required.getId(), new AiDictionaryDto.RevalidateRequest(required.getVersion(), false, null));
        assertThat(check.outcome()).isEqualTo("STILL_REQUIRED");
        assertThat(check.acknowledgeable()).isTrue();
        assertThat(check.changes()).singleElement().satisfies(c -> assertThat(c.fields()).containsExactly("columnDesc"));
        assertThat(proposals.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.REVIEW_REQUIRED);
        // 사유 없이 확인 → 거부, 사유와 함께 → 검토 중 (기준을 옮김) → 승인 가능 (자동 승인은 없다)
        assertThatThrownBy(() -> reviews.revalidate(user(owner), required.getId(), new AiDictionaryDto.RevalidateRequest(required.getVersion(), true, " ")))
                .isInstanceOf(CustomException.class);
        AiDictionaryDto.RevalidateResult ok = reviews.revalidate(user(owner), required.getId(), new AiDictionaryDto.RevalidateRequest(required.getVersion(), true, "코드 설명만 바뀌어 제안 문구는 그대로 맞다"));
        assertThat(ok.outcome()).isEqualTo("REVIEWABLE");
        AiDescriptionProposal reviewable = proposals.findById(p.getId()).orElseThrow();
        assertThat(reviewable.getStatus()).isEqualTo(ReviewStatus.REVIEWED);
        assertThat(column(status).getBizDescription()).as("재검증은 업무 설명을 바꾸지 않는다").isNull();
        assertThat(histories.findAllByProjectId(project.getId())).filteredOn(h -> h.getProposalId().equals(p.getId()))
                .extracting(h -> h.getAction().name()).contains("REVALIDATE");
        useProvider();
        assertThat(approve(reviewable).outcome()).isEqualTo("APPROVED");

        // 업무 설명을 누가 고쳤으면 확인으로도 풀 수 없다 → 종결
        AiDescriptionProposal other = propose(orderedAt);
        bizDescriptions.update(login(owner), new BizDescriptionDto.Request(order.getId(), orderedAt.getId(), "사람이 쓴 설명", ""));
        assertThat(approve(other).outcome()).isEqualTo("REVIEW_REQUIRED");
        AiDescriptionProposal blocked = proposals.findById(other.getId()).orElseThrow();
        AiDictionaryDto.RevalidateResult no = reviews.revalidate(user(owner), blocked.getId(), new AiDictionaryDto.RevalidateRequest(blocked.getVersion(), true, "그래도 계속"));
        assertThat(no.outcome()).isEqualTo("STILL_REQUIRED");
        assertThat(no.acknowledgeable()).isFalse();
        // 기준 변경이 없는(재검토 필요가 된 원인이 없어진) 제안 : 바로 검토 중으로
        AiDescriptionProposal clean = propose(payment, columnRepository.findAllByTableInfo_IdIn(List.of(payment.getId())).getFirst());
        jdbc.update("update TB_AI_EXPLN_SGSTN set RVW_STTS_CD = 'REVIEW_REQUIRED' where AI_EXPLN_SGSTN_SN = ?", clean.getId());
        AiDescriptionProposal cleanNow = proposals.findById(clean.getId()).orElseThrow();
        assertThat(reviews.revalidate(user(owner), cleanNow.getId(), new AiDictionaryDto.RevalidateRequest(cleanNow.getVersion(), false, null)).outcome()).isEqualTo("REVIEWABLE");
    }

    @Test
    @DisplayName("종결·재생성 : 종결은 사유 필수·승인 권한, 업무 설명 불변, 품질 지표에서 반려와 구분. 재생성은 앞 제안을 대체됨으로 남기고 서로 이력으로 잇는다, 실패하면 앞 제안 그대로")
    void closeAndRegenerate() {
        AiDescriptionProposal p = propose(status);
        assertThatThrownBy(() -> reviews.close(user(owner), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> reviews.close(user(member), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, "필요 없음")))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.APPROVAL_PERMISSION_DENIED);
        assertThat(reviews.close(user(owner), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, "업무 설명을 직접 쓰기로 함")).outcome()).isEqualTo("CLOSED");
        AiDescriptionProposal closed = proposals.findById(p.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(ReviewStatus.CLOSED);
        assertThat(closed.getReviewReason()).isEqualTo("업무 설명을 직접 쓰기로 함");
        assertThat(column(status).getBizDescription()).isNull();
        assertThatThrownBy(() -> reviews.approve(user(owner), closed.getId(), new AiDictionaryDto.ReviewRequest(closed.getVersion(), null, null)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROPOSAL_INVALID_STATE);
        AiDictionaryDto.ModelMetrics m = queries.metrics(user(owner)).models().getFirst();
        assertThat(m.closed()).isEqualTo(1);
        assertThat(m.rejected()).isZero();
        assertThat(m.approvalRate()).as("종결은 승인율·반려율의 분모에 넣지 않는다").isNull();

        // 재검토 필요 → 새 초안 : 앞 제안 SUPERSEDED, 서로 이력으로 연결
        AiDescriptionProposal old = propose(orderedAt);
        tx.executeWithoutResult(s -> columnRepository.findById(orderedAt.getId()).orElseThrow().setDataType("DATE"));
        assertThat(approve(old).outcome()).isEqualTo("REVIEW_REQUIRED");
        AiDescriptionProposal renewed = propose(orderedAt);
        assertThat(proposals.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.SUPERSEDED);
        assertThat(histories.findAllByProjectId(project.getId())).filteredOn(h -> h.getProposalId().equals(old.getId()) && h.getAction().name().equals("SUPERSEDE"))
                .singleElement().satisfies(h -> assertThat(h.getReason()).contains("#" + renewed.getId()));
        assertThat(histories.findAllByProjectId(project.getId())).filteredOn(h -> h.getProposalId().equals(renewed.getId()) && h.getAction().name().equals("GENERATE"))
                .singleElement().satisfies(h -> assertThat(h.getReason()).contains("#" + old.getId()));
        // 재생성 실패 (AI 응답 형식 오류 두 번) → 앞 제안(지금 검토 대기)·업무 설명 그대로
        ollama.then("not json").then("still not json");
        assertThatThrownBy(() -> drafts.generate(user(owner), order.getId(), orderedAt.getId(), true, null)).isInstanceOf(CustomException.class);
        assertThat(proposals.findById(renewed.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(column(orderedAt).getBizDescription()).isNull();
    }

    // ------------------------------------------------------------------ 준비

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai5w-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private ColumnInfo column(TableInfo t, String name, String desc, String type, int order, String biz, boolean pk) {
        return columnRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(t).tableName(t.getTableName()).columnName(name).columnDesc(desc)
                .dataType(type).sortOrder(order).bizDescription(biz).isPk(pk).isNullable(!pk).isApproval(false).build());
    }

    private void useProvider() {
        defaults.set(new AiProviderDto.DefaultProviderRequest(providerId));
    }

    private AccountDto login(Account account) {
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(account.getId()), "ROLE_USER");
    }

    private ExecutionContext user(Account account) {
        return ExecutionContext.user(AccountDto.builder().id(String.valueOf(account.getId())).projectId(project.getId()).build());
    }

    private AiDescriptionProposal propose(ColumnInfo c) {
        return propose(order, c);
    }

    private AiDescriptionProposal propose(TableInfo t, ColumnInfo c) {
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", c.getId(), c.getColumnName() + " 설명", "INFERRED",
                List.of(Map.of("type", "COLUMN", "id", c.getId(), "name", c.getColumnName())), null, null, List.of()));
        return drafts.generate(user(owner), t.getId(), c.getId(), true, null).proposal();
    }

    private AiDictionaryDto.ReviewResult approve(AiDescriptionProposal p) {
        AiDescriptionProposal now = proposals.findById(p.getId()).orElseThrow();
        return reviews.approve(user(owner), now.getId(), new AiDictionaryDto.ReviewRequest(now.getVersion(), null, null));
    }

    private static AiDictionaryDto.JobRequest request(List<AiDictionaryDto.Target> targets, boolean tables, boolean columns, boolean onlyMissing) {
        return new AiDictionaryDto.JobRequest("SELECTED", null, onlyMissing, targets, tables, columns);
    }

    private AiDictionaryDto.JobSummary awaitJob(Long id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            AiDictionaryDto.JobSummary s = jobs.get(user(owner), id);
            if (s.status() != com.lexorma.ai.dictionary.domain.entity.AiDescriptionJob.Status.PENDING
                    && s.status() != com.lexorma.ai.dictionary.domain.entity.AiDescriptionJob.Status.RUNNING) return s;
            Thread.sleep(50);
        }
        throw new AssertionError("실행이 끝나지 않았습니다");
    }

    private TableInfo table(TableInfo t) {
        return tableRepository.findById(t.getId()).orElseThrow();
    }

    private ColumnInfo column(ColumnInfo c) {
        return columnRepository.findById(c.getId()).orElseThrow();
    }
}
