package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.repository.AttributeColumnMappingRepository;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.version.service.ModelVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 논리 → 물리 생성 반영 중 실패 (M-7 보강 2차 P11) : 버전 저장 뒤 매핑 저장이 실패하거나 버전 저장이 실패하면, 테이블·컬럼·엔터티/속성 매핑·생성 전 버전 행이 하나도 남지 않는다.
 * 실패는 스파이 빈으로 일으킨다 (그래서 다른 테스트와 컨텍스트를 나눈다). 시퀀스 값은 되돌리지 않으므로 행 수로 확인한다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalGenerationRollbackIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(872_000_000L);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LogicalModelService logical;
    @Autowired PhysicalGenerationService generation;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AttributeColumnMappingRepository attributeMappings;
    @MockitoSpyBean ModelVersionService versionService;
    @MockitoSpyBean PhysicalModelService physicalModelService;

    @AfterEach
    void tearDown() {
        Mockito.reset(attributeMappings);
        Mockito.reset((ModelVersionService) AopTestUtils.getUltimateTargetObject(versionService));
        Mockito.reset((PhysicalModelService) AopTestUtils.getUltimateTargetObject(physicalModelService));
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("버전을 남긴 뒤 속성 매핑 저장이 실패 → 전체 롤백 (버전 포함). 실패를 거두면 같은 계획으로 정상 생성")
    void failureAfterVersion() {
        Setup s = setup("gen-rollback-mapping");
        PhysicalGenerationDto.Plan plan = generation.preview(s.user, s.request(null));
        Map<String, Long> before = counts(s.user);
        Mockito.doThrow(new IllegalStateException("강제 실패")).when(attributeMappings).save(any());
        assertThatThrownBy(() -> generation.apply(s.user, s.request(plan.planToken()))).isInstanceOf(IllegalStateException.class);
        Mockito.verify((ModelVersionService) AopTestUtils.getUltimateTargetObject(versionService)).createBeforeModelChange(anyLong(), anyString(), anyString(), any());
        assertThat(counts(s.user)).isEqualTo(before);

        Mockito.reset(attributeMappings);
        PhysicalGenerationDto.Result result = generation.apply(s.user, s.request(plan.planToken()));
        assertThat(result.tables()).isEqualTo(1);
        assertThat(counts(s.user)).containsEntry("TB_MDL_VER", before.get("TB_MDL_VER") + 1).containsEntry("TB_TBL", before.get("TB_TBL") + 1);
    }

    @Test
    @DisplayName("버전 저장이 실패 → 테이블·컬럼·매핑을 만들지 않는다")
    void versionFailure() {
        Setup s = setup("gen-rollback-version");
        PhysicalGenerationDto.Plan plan = generation.preview(s.user, s.request(null));
        Map<String, Long> before = counts(s.user);
        // 스파이는 트랜잭션 프록시 안쪽 객체다 : 프록시(MANDATORY 검사)를 거치지 않고 스텁한다
        ModelVersionService target = AopTestUtils.getUltimateTargetObject(versionService);
        Mockito.doThrow(new IllegalStateException("버전 실패")).when(target).createBeforeModelChange(anyLong(), anyString(), anyString(), any());
        assertThatThrownBy(() -> generation.apply(s.user, s.request(plan.planToken()))).isInstanceOf(IllegalStateException.class);
        assertThat(counts(s.user)).isEqualTo(before);
    }

    @Test
    @DisplayName("FK 관계 저장 실패 (M-7 보강 4차) : 테이블·컬럼·매핑·생성 전 버전·FK 관계 모두 남지 않는다")
    void foreignKeyFailure() {
        Setup s = setup("gen-rollback-fk");
        // 자기 참조 FK (TableRelation 경로) : 고객 아이디 → 상위 고객 아이디
        long upper = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " select ?, PRJ_SN, 1, '상위_고객_아이디', '상위', 'UPR_CUST_ID', STD_DMN_SN, true, current_timestamp, current_timestamp, 'test', 'test' from TB_STD_TRM where TRM_ENG_ABBR_NM = 'CUST_ID' and PRJ_SN = ?",
                upper, s.user.getProjectId());
        LogicalModelDto.Attribute up = logical.saveAttribute(s.user, new LogicalModelDto.Attribute(null, s.entityId, "상위 고객 아이디", null, null, false, null, upper, null, null, null));
        Long key = logical.selectAttributes(s.user, s.entityId).stream().filter(a -> "고객 아이디".equals(a.attributeName())).findFirst().orElseThrow().id();
        Long modelId = jdbc.queryForObject("select LGCL_MDL_SN from TB_LGCL_ENTT where LGCL_ENTT_SN = ?", Long.class, s.entityId);
        LogicalModelDto.Relationship rel = logical.saveRelationship(s.user, new LogicalModelDto.Relationship(null, modelId, "상위", null, s.entityId, null, s.entityId, null,
                false, 0, 1, 0, null, null, null));
        jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", rel.id());
        logical.saveRelationshipPairs(s.user, rel.id(), new LogicalModelDto.PairSave(logical.selectRelationshipPairs(s.user, rel.id()).modifiedAt(),
                List.of(new LogicalModelDto.PairInput(key, up.id()))));
        PhysicalGenerationDto.Plan plan = generation.preview(s.user, s.request(null));
        assertThat(plan.relations()).singleElement().satisfies(r -> assertThat(r.fkType()).isEqualTo("SELF_SINGLE"));
        Map<String, Long> before = counts(s.user);
        PhysicalModelService target = AopTestUtils.getUltimateTargetObject(physicalModelService);
        Mockito.doThrow(new IllegalStateException("FK 실패")).when(target).createGeneratedForeignKey(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> generation.apply(s.user, s.request(plan.planToken()))).isInstanceOf(IllegalStateException.class);
        assertThat(counts(s.user)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where PRJ_SN = ?", Long.class, s.user.getProjectId())).isZero();

        Mockito.reset(target);
        assertThat(generation.apply(s.user, s.request(plan.planToken())).foreignKeys()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where PRJ_SN = ?", Long.class, s.user.getProjectId())).isOne();
    }

    private Map<String, Long> counts(AccountDto user) {
        long p = user.getProjectId();
        return Map.of(
                "TB_TBL", jdbc.queryForObject("select count(*) from TB_TBL where PRJ_SN = ?", Long.class, p),
                "TB_COL", jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ?", Long.class, p),
                "TB_ENTT_TBL_MPNG", jdbc.queryForObject("select count(*) from TB_ENTT_TBL_MPNG where PRJ_SN = ?", Long.class, p),
                "TB_ATRB_COL_MPNG", jdbc.queryForObject("select count(*) from TB_ATRB_COL_MPNG m join TB_ENTT_TBL_MPNG e on e.ENTT_TBL_MPNG_SN = m.ENTT_TBL_MPNG_SN where e.PRJ_SN = ?", Long.class, p),
                "TB_MDL_VER", jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Long.class, p),
                "TB_TBL_REL", jdbc.queryForObject("select count(*) from TB_TBL_REL where PRJ_SN = ?", Long.class, p),
                "TB_TBL_REL_COL", jdbc.queryForObject("select count(*) from TB_TBL_REL_COL x join TB_TBL_REL r on r.REL_SN = x.REL_SN where r.PRJ_SN = ?", Long.class, p),
                "TB_MDL_CHG_HSTRY", jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Long.class, p));
    }

    private record Setup(AccountDto user, Long entityId) {
        PhysicalGenerationDto.Request request(String token) {
            return new PhysicalGenerationDto.Request(List.of(entityId), null, token);
        }
    }

    private Setup setup(String name) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name).isActive(true).account(admin).dbType(DbType.POSTGRESQL).build());
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        long p = project.getId();
        for (String[] w : new String[][]{{"고객", "CUST"}, {"아이디", "ID"}}) {
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", IDS.incrementAndGet(), p, w[0], w[1], w[1], w[0]);
        }
        long domain = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '테스트', '테스트', 'V20', 'V20', 'VARCHAR', 20, null, true, current_timestamp, current_timestamp, 'test', 'test')", domain, p);
        long term = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '고객_아이디', '고객_아이디', 'CUST_ID', ?, true, current_timestamp, current_timestamp, 'test', 'test')", term, p, domain);
        LogicalModelDto.Model model = logical.saveModel(user, new LogicalModelDto.Model(null, name, null));
        LogicalModelDto.Entity entity = logical.saveEntity(user, new LogicalModelDto.Entity(null, model.id(), "고객", null, null, null, null));
        LogicalModelDto.Attribute key = logical.saveAttribute(user, new LogicalModelDto.Attribute(null, entity.id(), "고객 아이디", null, null, true, null, term, null, null, null));
        logical.saveIdentifier(user, new LogicalModelDto.Identifier(null, entity.id(), "식별자", true, List.of(key.id()), null));
        return new Setup(user, entity.id());
    }
}
