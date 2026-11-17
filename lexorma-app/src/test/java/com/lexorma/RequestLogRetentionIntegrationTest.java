package com.lexorma;

import com.lexorma.core.logs.service.RequestLogRetention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 요청 로그 보관 기간 (DEC-14) : 기본은 끔, 켜면 기간보다 오래된 요청 로그만 지운다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class RequestLogRetentionIntegrationTest {

    @Autowired RequestLogRetention retention;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("기간보다 오래된 요청 로그만 지우고 최근 것은 남긴다. 0 이하(기본)는 아무것도 지우지 않는다")
    void purge() {
        String marker = "/api/retention-test/" + System.nanoTime();
        insert(marker + "/old", LocalDateTime.now().minusDays(40));
        insert(marker + "/old2", LocalDateTime.now().minusDays(31));
        insert(marker + "/new", LocalDateTime.now().minusDays(1));

        assertThat(retention.purge(0)).as("끔").isZero();
        assertThat(count(marker)).isEqualTo(3);

        assertThat(retention.purge(30)).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForList("select DMND_URL_ADDR from TB_LOG where DMND_URL_ADDR like ?", String.class, marker + "%"))
                .containsExactly(marker + "/new");
    }

    private void insert(String url, LocalDateTime at) {
        jdbc.update("insert into TB_LOG (DMND_URL_ADDR, DMND_MTHD_NM, CNTN_IP_ADDR, LOG_DT) values (?, 'GET', '127.0.0.1', ?)", url, Timestamp.valueOf(at));
    }

    private int count(String marker) {
        return jdbc.queryForObject("select count(*) from TB_LOG where DMND_URL_ADDR like ?", Integer.class, marker + "%");
    }
}
