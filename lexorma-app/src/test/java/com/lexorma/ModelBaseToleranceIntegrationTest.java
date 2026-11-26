package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.jpa.ModelBase;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-08·13 보완 : 낙관적 충돌 비교의 1µs 허용 오차(PostgreSQL timestamp(6) 반올림)가 실제 변경을 놓치지 않는지. 같은 행을 바로 이어서 여러 번 저장해도 DB 에 남은
 * 수정 일시(MDFCN_DT)는 저장마다 1µs 이상 달라지고, 바로 앞 저장의 값을 기준값으로 들고 온 요청은 409 MODEL_CONFLICT 다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelBaseToleranceIntegrationTest {

    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName("연속 저장 50번 : DB 의 수정 일시는 매번 1µs 이상 달라지고, 바로 앞 기준값은 409, 지금 기준값(반올림 포함)은 통과")
    void rapidUpdatesAreDetected() {
        var admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Long projectId = projectRepository.save(Project.builder().projectName("tolerance-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(admin).build()).getId();
        Long id = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(projectId).schemaName("S0").isDefault(true).build()).getId());
        LocalDateTime previous = stored(id);
        for (int i = 1; i <= 50; i++) {
            int n = i;
            tx.executeWithoutResult(s -> schemaRepository.findById(id).orElseThrow().setSchemaDescription("change " + n));
            LocalDateTime current = stored(id);
            assertThat(Math.abs(ChronoUnit.NANOS.between(previous, current))).as("저장 %d 의 수정 일시 차이", n).isGreaterThanOrEqualTo(1_000);
            LocalDateTime stale = previous;
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> ModelBase.verify(schemaRepository.findById(id).orElseThrow(), stale, "S0")))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_CONFLICT));
            // 화면이 받은 값(같은 행을 다시 읽은 값)은 통과
            tx.executeWithoutResult(s -> ModelBase.verify(schemaRepository.findById(id).orElseThrow(), current, "S0"));
            previous = current;
        }
    }

    /** 영속성 컨텍스트가 아니라 DB 에 남은 값 (PostgreSQL 은 µs 로 반올림된다) */
    private LocalDateTime stored(Long id) {
        return tx.execute(s -> schemaRepository.findById(id).orElseThrow().modifiedAt());
    }
}
