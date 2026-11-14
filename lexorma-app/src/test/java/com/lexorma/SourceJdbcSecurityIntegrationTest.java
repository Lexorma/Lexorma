package com.lexorma;

import com.lexorma.core.network.domain.dto.ExternalConnectionDto;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Result;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-1 × 원본 DB (실제 PostgreSQL, LEXORMA_TEST_SOURCE_POSTGRES_URL 이 있을 때만) : 관리자 JDBC 허용 규칙으로만 연결 테스트·수집·재최신화가 되고,
 * 규칙을 끄면 바로 다음 연결부터 막히며(정책 변경 즉시 반영), 다시 켜면 열린다. 연결 시도는 결과(연결됨·차단)와 함께 남고 비밀번호는 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lexorma.secret.keys=t1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "lexorma.secret.active-key-id=t1",
                "lexorma.source.jdbc.query-timeout-seconds=5", "lexorma.source.connection-test.per-minute=1000"})
@ActiveProfiles("test")
class SourceJdbcSecurityIntegrationTest extends SourceJdbcTestSupport {

    @Test
    @DisplayName("JDBC 정책 : 허용 규칙으로 수집·재최신화가 되고, 규칙을 끄면 연결 테스트·수집이 사유와 함께 막히며 다시 켜면 열린다. 감사에는 연결됨·차단이 남는다")
    void ruleControlsRealConnections() throws Exception {
        SourceDb db = source("create table app_item (id int primary key, name varchar(20))");
        Env e = env(db);
        Long ruleId = allowSource();
        URI uri = URI.create(ADMIN_URL.substring("jdbc:".length()));
        try {
            // 허용 : 수집과 재최신화
            SourceCollectionDto.Execution first = collect(e, "FULL");
            assertThat(first.status()).isEqualTo("COMPLETED");
            assertThat(collect(e, "FULL").status()).isEqualTo("COMPLETED");
            assertThat(connectionApi.test(e.managerDto(), e.connection()).getBody().connected()).isTrue();

            // 규칙을 끄면 다음 연결부터 막힌다 (연결 테스트·수집 모두)
            var rule = connectionRules.list("JDBC", null).stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
            connectionRules.update(ruleId, new ExternalConnectionDto.RuleRequest("JDBC", rule.host(), rule.cidr(), rule.port(), false, rule.description()));
            SourceConnectionDto.TestResult blocked = connectionApi.test(e.managerDto(), e.connection()).getBody();
            assertThat(blocked).extracting(SourceConnectionDto.TestResult::connected, SourceConnectionDto.TestResult::errorCode).containsExactly(false, "SOURCE_HOST_NOT_ALLOWED");
            assertThat(blocked.message()).contains("관리자가 허용한 규칙이 없습니다");
            SourceCollectionDto.Execution denied = await(e, refresh(e, e.connection(), "FULL").id());
            assertThat(denied).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode).containsExactly("FAILED", "SOURCE_HOST_NOT_ALLOWED");

            // 다시 켜면 열린다
            connectionRules.update(ruleId, new ExternalConnectionDto.RuleRequest("JDBC", rule.host(), rule.cidr(), rule.port(), true, rule.description()));
            assertThat(collect(e, "FULL").status()).isEqualTo("COMPLETED");

            List<ExternalConnectionDto.Attempt> attempts = connectionRules.attempts("JDBC", null, uri.getHost(), null, null, null, 0, 100).rows();
            assertThat(attempts).anySatisfy(a -> {
                assertThat(a.result()).isEqualTo(Result.CONNECTED);
                assertThat(a.ruleId()).isEqualTo(ruleId);
                assertThat(a.port()).isEqualTo(uri.getPort());
                assertThat(a.addresses()).isNotBlank();
                assertThat(a.executionType()).isIn("USER", "ASYNC");
            });
            assertThat(attempts).anySatisfy(a -> {
                assertThat(a.result()).isEqualTo(Result.DENIED);
                assertThat(a.reason()).isEqualTo(Reason.NO_RULE);
            });
            String dump = String.join("|", jdbc.queryForList("select coalesce(HOST_NM, '') || coalesce(CNTN_ADDR_CN, '') || coalesce(RSN_CD, '') || coalesce(DMND_ID, '')"
                    + " from TB_EXTRL_CNTN_HSTRY", String.class));
            assertThat(dump).doesNotContain(PASSWORD.length() > 3 ? PASSWORD : "\u0000");
        } finally {
            var rule = connectionRules.list("JDBC", null).stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
            if (!rule.enabled()) connectionRules.update(ruleId, new ExternalConnectionDto.RuleRequest("JDBC", rule.host(), rule.cidr(), rule.port(), true, rule.description()));
        }
    }
}
