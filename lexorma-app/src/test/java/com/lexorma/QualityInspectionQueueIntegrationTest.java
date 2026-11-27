package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.inspection.domain.InspectionCodes;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.repository.InspectionRepository;
import com.lexorma.data.inspection.service.InspectionHooks;
import com.lexorma.data.inspection.service.InspectionRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장 품질 검사 실행기 한도 (AI-6 2차, AI6-D23) : 서버 전체 실행 스레드 1 + 대기열(여기서는 1). 실행 중 1·대기 1 이면 세 번째 요청은 등록은 되지만
 * 실행기 제출이 거절되어 FAILED(INSPECTION_EXECUTOR_BUSY) 로 닫힌다 (C03 서버 전체 제한, C04 대기열 초과, C19 등록 뒤 제출 실패).
 * 대기열 크기만 다른 별도 컨텍스트다 — 운영 기본값(10)은 바꾸지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "lexorma.inspection.queue-capacity=1")
@ActiveProfiles("test")
@Import(QualityInspectionQueueIntegrationTest.Hold.class)
class QualityInspectionQueueIntegrationTest {

    @TestConfiguration
    static class Hold {
        static volatile CountDownLatch reached = new CountDownLatch(0);
        static volatile CountDownLatch release = new CountDownLatch(0);

        @Bean
        InspectionHooks holdingHooks() {
            return new InspectionHooks() {
                @Override
                public void afterSnapshot(long runId) {
                    reached.countDown();
                    try {
                        if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("release 시간 초과");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            };
        }
    }

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired InspectionRunService runService;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        Hold.release.countDown();
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("C03·C04·C19 : 실행 1 + 대기 1 을 넘는 요청은 등록 뒤 제출이 거절되어 FAILED, 앞의 두 실행은 정상으로 끝나고 그 프로젝트는 다시 요청할 수 있다")
    void executorLimit() {
        Hold.reached = new CountDownLatch(1);
        Hold.release = new CountDownLatch(1);
        AccountDto a = login("ai6-q-a");
        AccountDto b = login("ai6-q-b");
        AccountDto c = login("ai6-q-c");

        long running = runService.request(a);
        await(Hold.reached);
        long queued = runService.request(b);       // C03 : 실행기 스레드가 하나라 대기
        long rejected = runService.request(c);     // C04·C19 : 대기열도 가득 → 제출 거절 → FAILED

        assertThat(inspectionRepository.findRun(a.getProjectId(), running).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(inspectionRepository.findRun(b.getProjectId(), queued).orElseThrow().status()).isEqualTo(RunStatus.PENDING);
        InspectionRepository.RunRow failed = inspectionRepository.findRun(c.getProjectId(), rejected).orElseThrow();
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(InspectionCodes.ERR_EXECUTOR_BUSY);
        assertThat(failed.startedAt()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from TB_INSP_EXCN_RULE where INSP_EXCN_SN = ?", Integer.class, rejected)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", Integer.class, c.getProjectId())).isZero();

        Hold.release.countDown();
        waitDone(a.getProjectId(), running);
        waitDone(b.getProjectId(), queued);
        assertThat(inspectionRepository.findRun(a.getProjectId(), running).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(inspectionRepository.findRun(b.getProjectId(), queued).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);

        // 거절된 프로젝트는 진행 중 실행이 남지 않아 다시 요청할 수 있다
        long retry = runService.request(c);
        waitDone(c.getProjectId(), retry);
        assertThat(inspectionRepository.findRun(c.getProjectId(), retry).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
    }

    private AccountDto login(String name) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 6)).isActive(true)
                .account(admin).tablePrefix("TB_").build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(admin).isActive(true).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private void waitDone(long projectId, long runId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (inspectionRepository.findRun(projectId, runId).orElseThrow().status().active()) {
            if (System.nanoTime() > deadline) throw new AssertionError("실행 " + runId + " 이 끝나지 않았다");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new AssertionError("latch 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
