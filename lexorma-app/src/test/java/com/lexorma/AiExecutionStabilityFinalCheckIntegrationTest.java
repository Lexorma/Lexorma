package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.ai.domain.dto.AiMetadataQueryDto;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.ai.evidence.EvidenceVerifier;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.ai.provider.service.AiDefaultProviderService;
import com.lexorma.ai.provider.service.AiProviderService;
import com.lexorma.ai.service.AiMetadataQueryService;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
import com.lexorma.core.support.SecurityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.lexorma.AiExecutionStabilityIntegrationTest.call;
import static com.lexorma.AiExecutionStabilityIntegrationTest.evidence;
import static com.lexorma.AiExecutionStabilityIntegrationTest.user;
import static com.lexorma.MetadataToolIntegrationTest.answer;
import static com.lexorma.MetadataToolIntegrationTest.raw;
import static com.lexorma.MetadataToolIntegrationTest.toolCalls;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * AI 공통 실행 안정성 3차 : 응답 직전 재검증. 근거 검증({@link EvidenceVerifier} — 그 안의 VIEWER 재검증 포함)이 끝난 바로 뒤, 응답을 만들기 전에 멤버십을 끊는다.
 * 운영 코드에 시험용 후크를 두지 않고 Spring 테스트의 spy Bean 으로 근거 검증이 끝난 순간에만 끼어든다. 응답 객체를 만든 뒤 HTTP 로 내보내기까지의 일반적인 경합은 이 시험의 범위가 아니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiExecutionStabilityFinalCheckIntegrationTest {

    private static final String MODEL = AiExecutionStabilityIntegrationTest.MODEL;

    @Autowired ApplicationContext context;
    @Autowired AiMetadataQueryService queries;
    @Autowired AiProviderService providers;
    @Autowired AiDefaultProviderService defaults;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean EvidenceVerifier evidenceVerifier;

    private final List<Long> createdRules = new ArrayList<>();
    private final List<Long> createdProviders = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        Mockito.reset(evidenceVerifier);
        defaults.clear();
        for (Long id : createdProviders) providers.delete(id);
        for (Long id : createdRules) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
        SecurityTestSupport.clear();
    }

    private ScriptedOllama ollama() throws Exception {
        ScriptedOllama ai = new ScriptedOllama();
        servers.add(ai);
        ai.show(AiExecutionStabilityIntegrationTest.THINKING_MODEL, 131_072);
        AiProviderDto.Provider provider = providers.create(new ProviderRequest("AES " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ai.url(), MODEL, true, null));
        createdProviders.add(provider.id());
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ai.port(), true, "AES 테스트")).id());
        defaults.set(new AiProviderDto.DefaultProviderRequest(provider.id()));
        return ai;
    }

    private void revokeAfterEvidence(LogicalD35Fixture x) {
        doAnswer(invocation -> {
            Object verified = invocation.callRealMethod();   // 근거 검증(그 안의 재검증)은 통과
            // 근거 검증의 읽기 전용 트랜잭션 안에서 불리므로 별도 트랜잭션으로 끊고 커밋한다 (PostgreSQL 은 읽기 전용 트랜잭션의 UPDATE 를 거절한다)
            TransactionTemplate separate = new TransactionTemplate(transactionManager);
            separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            separate.executeWithoutResult(s -> jdbc.update("update TB_PRJ_MBR set ACTVTN_YN = false where PRJ_SN = ? and ACNT_SN = ?", x.project(),
                    Long.valueOf(x.f.user.getId())));
            return verified;
        }).when(evidenceVerifier).verify(any(), any());
    }

    @Test
    @DisplayName("응답 직전 권한 상실 : 근거 검증이 끝난 뒤 멤버십 해제 → 정상(COMPLETE 이 될) 답도 돌려주지 않고 PROJECT_ACCESS_DENIED")
    void accessLostAfterEvidenceOnComplete() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-final", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person))).then(raw(MODEL, "개인고객입니다."))
                .then(answer(MODEL, "개인고객입니다.", List.of(evidence(EvidenceType.LOGICAL_ENTITY, x.person, "개인고객", null))));
        revokeAfterEvidence(x);
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(ai.chats).hasSize(3);
    }

    @Test
    @DisplayName("응답 직전 권한 상실 : 부분 답변(PARTIAL 이 될)도 같다 — 권한 오류가 부분 답변보다 앞선다")
    void accessLostAfterEvidenceOnPartial() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-final-partial", false);
        ScriptedOllama ai = ollama();
        for (int page = 0; page < 5; page++) ai.then(toolCalls(MODEL, call("searchLogicalEntities", "modelId", x.f.modelId, "page", page, "limit", 1)));
        ai.then(answer(MODEL, "일부만 확인했습니다."));
        revokeAfterEvidence(x);
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("전부")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(ai.chats).as("Tool 단계 5번 + 부분 구조화 1번 — 답은 만들었지만 돌려주지 않는다").hasSize(6);
    }
}
