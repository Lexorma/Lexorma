package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 논리 → 물리 선택 생성 (DEC-10) : 미리보기(이름·타입·PK·FK·문제), planToken 확인 뒤 미승인 테이블·컬럼·매핑 생성, 계획이 바뀌면 409, 막는 문제가 있으면 아무것도 만들지 않음,
 * 이미 매핑된 엔터티는 건너뜀(덮어쓰지 않음), 같은 이름의 테이블이 있으면 오류, 다른 프로젝트 엔터티 거부.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalGenerationIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(870_000_000L);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LogicalModelService logical;
    @Autowired PhysicalGenerationService generation;
    @Autowired TableInfoRepository tables;
    @Autowired EntityTableMappingRepository mappings;
    @Autowired SchemaService schemaService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("미리보기 → 반영 : 표준 단어 테이블명, 용어 약어 컬럼명, 도메인 타입(문자 길이·숫자 정밀도), 주 식별자 PK, 논리 관계 FK(참조 테이블명), 모두 미승인 + 매핑. 다시 하면 건너뜀")
    void generate() {
        Fixture f = fixture("gen-ok");
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, null);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        assertThat(plan.creatable()).as(plan.tables().toString()).isTrue();
        assertThat(plan.tables()).extracting(PhysicalGenerationDto.TablePlan::tableName).containsExactly("CUST", "ORD");
        PhysicalGenerationDto.TablePlan order = plan.tables().get(1);
        assertThat(order.columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).containsExactly("ORD_ID", "CUST_ID", "ORD_AMT");
        assertThat(order.columns().get(0).pk()).isTrue();
        assertThat(order.columns().get(1).refTableName()).isEqualTo("CUST");
        assertThat(order.columns().get(2)).satisfies(c -> {
            assertThat(c.dataType()).isEqualTo("NUMERIC");
            assertThat(c.precision()).isEqualTo(15);
            assertThat(c.scale()).isEqualTo(2);
        });
        assertThat(plan.tables().get(0).columns().get(0)).satisfies(c -> {
            assertThat(c.dataType()).isEqualTo("VARCHAR");
            assertThat(c.maxLength()).isEqualTo(20);
        });

        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, plan.planToken()));
        assertThat(result.tables()).isEqualTo(2);
        assertThat(result.columns()).isEqualTo(4);
        tx.executeWithoutResult(s -> {
            TableInfo ord = tables.findById(result.tableIds().get(1)).orElseThrow();
            assertThat(ord.getIsApproval()).isFalse();
            assertThat(ord.getColumns()).allSatisfy(c -> {
                assertThat(c.getIsApproval()).isFalse();
                assertThat(c.getStandardTerms()).isNotNull();
            });
            assertThat(ord.getColumns().stream().filter(c -> "CUST_ID".equals(c.getColumnName())).map(ColumnInfo::getRefTableName)).containsExactly("CUST");
        });
        assertThat(mappings.findByEntity_Id(f.order)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from TB_ATRB_COL_MPNG m join TB_ENTT_TBL_MPNG e on e.ENTT_TBL_MPNG_SN = m.ENTT_TBL_MPNG_SN where e.PRJ_SN = ?",
                Integer.class, f.user.getProjectId())).isEqualTo(4);

        // 다시 : 이미 매핑된 엔터티는 건너뛴다 — 만들 것이 없다
        PhysicalGenerationDto.Plan again = generation.preview(f.user, request);
        assertThat(again.tables()).extracting(PhysicalGenerationDto.TablePlan::status).containsOnly("SKIP");
        assertThat(again.creatable()).isFalse();
    }

    @Test
    @DisplayName("계획이 바뀌면 409 (속성 수정 뒤 예전 planToken), 막는 문제(용어 없는 속성·같은 이름의 테이블)가 있으면 아무것도 만들지 않는다, 다른 프로젝트 엔터티 거부")
    void staleAndBlocked() {
        Fixture f = fixture("gen-stale");
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, null));
        logical.saveAttribute(f.user, new LogicalModelDto.Attribute(f.customerId, f.customer, "고객 아이디", "바뀐 설명", null, true, 1, f.custIdTerm, null, null, null));
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, plan.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(mappings.findByEntity_Id(f.customer)).isEmpty();

        // 용어 없는 속성 → 오류, 같은 이름의 테이블이 이미 있음 → 오류. 둘 다 고른 요청은 아무것도 만들지 않는다
        logical.saveAttribute(f.user, new LogicalModelDto.Attribute(null, f.order, "비고", null, "VARCHAR(100)", false, null, null, null, null, null));
        tx.executeWithoutResult(s -> tables.save(TableInfo.builder().projectId(f.user.getProjectId()).schema(schemaService.resolveSchema(f.user.getProjectId(), null))
                .tableName("CUST").tableDescription("이미 있음").isApproval(false).build()));
        PhysicalGenerationDto.Plan blocked = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, null));
        assertThat(blocked.creatable()).isFalse();
        assertThat(blocked.tables()).extracting(PhysicalGenerationDto.TablePlan::status).containsExactly("ERROR", "ERROR");
        long before = tables.count();
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, blocked.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(tables.count()).isEqualTo(before);

        Fixture other = fixture("gen-other");
        assertThatThrownBy(() -> generation.preview(other.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, null)))
                .isInstanceOf(CustomException.class);
    }

    private record Fixture(AccountDto user, Long customer, Long order, Long customerId, Long custIdTerm) {
    }

    private Fixture fixture(String name) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name).isActive(true).account(admin).build());
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        long p = project.getId();
        for (String[] w : new String[][]{{"고객", "CUST"}, {"주문", "ORD"}, {"아이디", "ID"}, {"금액", "AMT"}}) {
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", IDS.incrementAndGet(), p, w[0], w[1], w[1], w[0]);
        }
        long text = domain(p, "아이디V20", "VARCHAR", 20, null);
        long money = domain(p, "금액N15", "NUMERIC", 15, 2);
        long custId = term(p, "고객_아이디", "CUST_ID", text);
        long ordId = term(p, "주문_아이디", "ORD_ID", text);
        long ordAmt = term(p, "주문_금액", "ORD_AMT", money);
        LogicalModelDto.Model model = logical.saveModel(user, new LogicalModelDto.Model(null, name, null));
        LogicalModelDto.Entity customer = logical.saveEntity(user, new LogicalModelDto.Entity(null, model.id(), "고객", "고객 정보", null, null, null));
        LogicalModelDto.Attribute customerId = logical.saveAttribute(user, new LogicalModelDto.Attribute(null, customer.id(), "고객 아이디", null, null, true, null, custId, null, null, null));
        logical.saveIdentifier(user, new LogicalModelDto.Identifier(null, customer.id(), "고객 식별자", true, List.of(customerId.id()), null));
        LogicalModelDto.Entity order = logical.saveEntity(user, new LogicalModelDto.Entity(null, model.id(), "주문", "주문 정보", null, null, null));
        LogicalModelDto.Attribute orderId = logical.saveAttribute(user, new LogicalModelDto.Attribute(null, order.id(), "주문 아이디", null, null, true, null, ordId, null, null, null));
        logical.saveAttribute(user, new LogicalModelDto.Attribute(null, order.id(), "고객 아이디", null, null, true, null, custId, null, null, null));
        logical.saveAttribute(user, new LogicalModelDto.Attribute(null, order.id(), "주문 금액", null, null, false, null, ordAmt, null, null, null));
        logical.saveIdentifier(user, new LogicalModelDto.Identifier(null, order.id(), "주문 식별자", true, List.of(orderId.id()), null));
        logical.saveRelationship(user, new LogicalModelDto.Relationship(null, model.id(), "고객 주문", null, customer.id(), null, order.id(), null,
                false, 1, 1, 0, null, null, null));
        return new Fixture(user, customer.id(), order.id(), customerId.id(), custId);
    }

    private long domain(long projectId, String name, String type, Integer length, Integer scale) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '테스트', '테스트', ?, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", id, projectId, name, name, type, length, scale);
        return id;
    }

    private long term(long projectId, String name, String abbreviation, long domainId) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", id, projectId, name, name, abbreviation, domainId);
        return id;
    }
}
