package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Item;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.PlanItem;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Request;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
import com.lexorma.data.mapping.repository.AttributeColumnMappingRepository;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.version.service.ModelVersionService;
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
import org.springframework.test.util.AopTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * M-7 물리명 추천 일괄 적용 2차 실패 주입 (P-05) : 직전 버전을 만든 뒤 테이블·컬럼 이름을 모두 바꾼 다음 단계(참조 관계 동기화)가 실패하거나, 버전 생성이 실패하면
 * 테이블명·컬럼명·참조 테이블명 사본·버전·이력이 모두 되돌아간다.
 * 스파이 빈 구성은 PhysicalGenerationRollbackIntegrationTest 와 같게 둔다 — 같은 테스트 컨텍스트를 나눠 써서 컨텍스트를 하나 더 만들지 않는다 (테스트 JVM 1GB).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameRenameRollbackIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalNameRenameService rename;
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

    /** 고객(고객 번호 PK) · 주문(주문 고객 번호 — 참조 테이블명 O_CUST). 이름 앞에 O_ */
    private record Fx(GenerationFixture f, long cust, long custNo, long ordCustNo) {
    }

    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "rnrb-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        long order = f.entity("주문");
        f.pk(order, f.attr(order, "주문 번호", true, "ORD_NO"));
        f.attr(order, "주문 고객 번호", false, "ORD_CUST_NO");
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer, order)));
        generation.apply(f.user, f.request(List.of(customer, order), plan.planToken()));
        long projectId = f.user.getProjectId();
        jdbc.update("update TB_TBL set TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM, TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        long cust = f.table(customer);
        long ord = f.table(order);
        jdbc.update("update TB_COL set RFRNC_TBL_NM = 'O_CUST' where TBL_SN = ? and COL_NM = 'O_ORD_CUST_NO'", ord);
        return new Fx(f, cust, column(cust, "O_CUST_NO"), column(ord, "O_ORD_CUST_NO"));
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private Request request(Plan plan, Item... items) {
        List<Item> withBase = new ArrayList<>();
        for (Item item : items) {
            PlanItem p = plan.items().stream().filter(i -> i.type() == item.type() && i.id().equals(item.id())).findFirst().orElseThrow();
            withBase.add(new Item(item.type(), item.id(), item.recommendedName(), p.modifiedAt()));
        }
        return new Request(withBase, plan.planToken());
    }

    private String state(Fx x) {
        return jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.cust())
                + "|" + jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, x.custNo())
                + "|" + String.join(",", jdbc.queryForList("select distinct TBL_NM from TB_COL where TBL_SN = ?", String.class, x.cust()))
                + "|" + jdbc.queryForObject("select RFRNC_TBL_NM from TB_COL where COL_SN = ?", String.class, x.ordCustNo())
                + "|" + x.f().versions()
                + "|" + x.f().count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", x.f().user.getProjectId());
    }

    @Test
    @DisplayName("이름을 모두 바꾼 뒤 참조 관계 동기화가 실패하면 테이블·컬럼·참조 사본·직전 버전·이력이 모두 되돌아간다. 실패를 거두면 같은 계획으로 적용된다")
    void failureAfterRenames() {
        Fx x = fixture("sync");
        Item[] items = {new Item(TargetType.TABLE, x.cust(), "CUST", null), new Item(TargetType.COLUMN, x.custNo(), "CUST_NO", null)};
        Plan plan = rename.preview(x.f().user, new Request(List.of(items), null));
        assertThat(plan.applicable()).isTrue();
        String before = state(x);
        assertThat(before).startsWith("O_CUST|O_CUST_NO|O_CUST|O_CUST|");
        PhysicalModelService target = AopTestUtils.getUltimateTargetObject(physicalModelService);
        Mockito.doThrow(new IllegalStateException("동기화 실패")).when(target).syncReferenceRelations(anyLong(), any());
        assertThatThrownBy(() -> rename.apply(x.f().user, request(plan, items))).isInstanceOf(IllegalStateException.class);
        assertThat(state(x)).isEqualTo(before);

        Mockito.reset(target);
        rename.apply(x.f().user, request(plan, items));
        assertThat(state(x)).startsWith("CUST|CUST_NO|CUST|CUST|");
    }

    @Test
    @DisplayName("직전 버전 생성이 실패하면 아무것도 바뀌지 않는다")
    void versionFailure() {
        Fx x = fixture("version");
        Item[] items = {new Item(TargetType.TABLE, x.cust(), "CUST", null)};
        Plan plan = rename.preview(x.f().user, new Request(List.of(items), null));
        String before = state(x);
        ModelVersionService target = AopTestUtils.getUltimateTargetObject(versionService);
        Mockito.doThrow(new IllegalStateException("버전 실패")).when(target).createBeforeModelChange(anyLong(), anyString(), anyString(), any());
        assertThatThrownBy(() -> rename.apply(x.f().user, request(plan, items))).isInstanceOf(IllegalStateException.class);
        assertThat(state(x)).isEqualTo(before);
    }
}
