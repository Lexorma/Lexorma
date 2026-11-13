package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.service.SourceRefreshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI-2b : 수집 실행 제한 — 전체 제한 시간, 작업 대기열이 가득 찬 경우, 서버 종료. 작업 스레드 1개·대기열 1개·전체 제한 시간 1초로 줄인 설정이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lexorma.secret.keys=t1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "lexorma.secret.active-key-id=t1",
                "lexorma.source.allowed-networks=127.0.0.0/8,::1/128", "lexorma.source.jdbc.query-timeout-seconds=20",
                "lexorma.source.collection.timeout=PT1S", "lexorma.source.collection.workers=1", "lexorma.source.collection.queue-capacity=1"})
@ActiveProfiles("test")
class SourceJdbcCollectionLimitIntegrationTest extends SourceJdbcTestSupport {

    @Autowired SourceRefreshService refreshService;

    @Test
    @DisplayName("전체 제한 시간 : 원본 조회가 끝나지 않아도 제한 시간에 조회를 취소하고 연결을 끊는다 (FAILED, 반영 없음). 대기열이 가득 차면 503 이고 그 실행은 RUNNING 으로 남지 않는다")
    void timeoutAndRejection() throws Exception {
        SourceDb db = source("create table public.t (id int)");
        Env e = env(db);
        Long second = connect(e.managerDto(), db, USER, PASSWORD, null);
        Long third = connect(e.managerDto(), db, USER, PASSWORD, null);
        Connection lock = lockCatalog(db, "pg_description");

        Long running = refresh(e, e.connection(), "FULL").id();
        awaitCollectorWaiting(db);
        Long queued = refresh(e, second, "FULL").id();
        assertThatThrownBy(() -> refresh(e, third, "FULL")).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SOURCE_COLLECTION_REJECTED));
        assertThat(jdbc.queryForList("select EXCN_STTS_CD || ':' || ERR_CD from TB_SRC_CLCT_EXCN where SRC_CNTN_SN = ?", String.class, third))
                .containsExactly("FAILED:SOURCE_COLLECTION_REJECTED");

        SourceCollectionDto.Execution timedOut = await(e, running);
        assertThat(timedOut).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode, SourceCollectionDto.Execution::reflectedOrder)
                .containsExactly("FAILED", "SOURCE_COLLECTION_TIMEOUT", null);
        assertThat(java.time.Duration.between(timedOut.startedAt(), timedOut.endedAt()).toSeconds()).isLessThan(10);
        // 대기하던 작업도 같은 잠금에 걸려 제한 시간에 끝난다. 잠금을 풀면 같은 연결로 다시 수집할 수 있다
        assertThat(await(e, queued).errorCode()).isEqualTo("SOURCE_COLLECTION_TIMEOUT");
        awaitCollectorGone(db);
        lock.rollback();
        assertThat(catalogRows(e.connection())).isZero();
        assertThat(collect(e, "FULL").status()).isEqualTo("COMPLETED");
    }

    @Test
    @DirtiesContext
    @DisplayName("서버 종료 : 돌고 있는 수집의 원본 조회를 멈추고 FAILED(SOURCE_COLLECTION_ABANDONED)로 남긴다. 종료 뒤의 요청은 받지 않는다 (RUNNING 으로 남지 않는다)")
    void shutdown() throws Exception {
        SourceDb db = source("create table public.t (id int)");
        Env e = env(db);
        lockCatalog(db, "pg_description");
        Long running = refresh(e, e.connection(), "FULL").id();
        awaitCollectorWaiting(db);
        ReflectionTestUtils.invokeMethod(refreshService, "shutdown");
        assertThat(await(e, running)).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode)
                .containsExactly("FAILED", "SOURCE_COLLECTION_ABANDONED");
        awaitCollectorGone(db);
        assertThatThrownBy(() -> refresh(e, e.connection(), "FULL")).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SOURCE_COLLECTION_REJECTED));
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CLCT_EXCN where SRC_CNTN_SN = ? and EXCN_STTS_CD = 'RUNNING'", Integer.class, e.connection())).isZero();
    }
}
