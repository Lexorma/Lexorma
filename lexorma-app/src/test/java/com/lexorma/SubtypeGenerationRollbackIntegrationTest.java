package com.lexorma;

import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.entity.AttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import com.lexorma.data.mapping.domain.entity.SubtypeIdentifierColumnMapping;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 3차 롤백 (M7-D39·30장) : 상속 생성 반영의 각 단계 뒤(테이블·컬럼·엔터티 매핑·직접 속성 매핑·상속 PK 매핑·상속 FK·일반 FK·커밋 직전 변경 이력)에서 예외가 나면
 * 모델 버전까지 전체가 되돌아가 사용자 데이터의 행 수·값이 그대로다. 예외는 이 테스트만 등록하는 Hibernate 저장 후 리스너가 낸다 (운영 설정·공개 API 에 없다 — 꺼져 있으면 아무것도 하지 않는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SubtypeGenerationRollbackIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired JdbcTemplate jdbc;

    /** 지정한 종류의 행을 skip 개 저장한 다음 저장에서 예외 (target null 이면 꺼짐) */
    static final class Injector implements PostInsertEventListener {
        static volatile Class<?> target;
        static final AtomicInteger skip = new AtomicInteger();
        static final AtomicInteger fired = new AtomicInteger();

        @Override
        public void onPostInsert(PostInsertEvent event) {
            Class<?> t = target;
            if (t == null || event.getPersister().getMappedClass() != t) return;
            if (skip.getAndDecrement() > 0) return;
            fired.incrementAndGet();
            throw new IllegalStateException("injected failure after " + t.getSimpleName());
        }

        @Override
        public boolean requiresPostCommitHandling(EntityPersister persister) {
            return false;
        }
    }

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    @BeforeEach
    void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            entityManagerFactory.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(EventListenerRegistry.class)
                    .appendListeners(EventType.POST_INSERT, new Injector());
        }
        Injector.target = null;
    }

    @AfterEach
    void tearDown() {
        Injector.target = null;
        SecurityTestSupport.clear();
    }

    private long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private void group(GenerationFixture f, long supertype, String name, long... members) {
        List<MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new MemberInput(m, null));
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, true, false, null, list, null));
    }

    /** 생성 반영이 남기는 모든 행 : 테이블·컬럼·엔터티 매핑·속성 매핑·상속 PK 매핑·관계·관계 컬럼·버전·변경 이력 + 기존 테이블·컬럼 값 */
    private List<Object> footprint(GenerationFixture f) {
        Long p = f.user.getProjectId();
        return List.of(f.count("select count(*) from TB_TBL where PRJ_SN = ?", p),
                f.count("select count(*) from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ?", p),
                f.count("select count(*) from TB_ENTT_TBL_MPNG where PRJ_SN = ?", p),
                f.count("select count(*) from TB_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where m.PRJ_SN = ?", p),
                f.count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.PRJ_SN = ?", p),
                f.count("select count(*) from TB_TBL_REL where PRJ_SN = ?", p),
                f.count("select count(*) from TB_TBL_REL_COL x join TB_TBL_REL r on r.REL_SN = x.REL_SN where r.PRJ_SN = ?", p),
                f.versions(),
                f.count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", p),
                jdbc.queryForList("select t.TBL_NM, t.MDFCN_DT, c.COL_NM, c.PK_YN, c.MDFCN_DT from TB_TBL t join TB_COL c on c.TBL_SN = t.TBL_SN where t.PRJ_SN = ? order by c.COL_SN", p).toString());
    }

    @Test
    @DisplayName("롤백 : 테이블·컬럼·엔터티 매핑·속성 매핑·상속 PK 매핑·상속 FK·일반 FK 저장 뒤와 변경 이력 저장 중 실패 — 모두 전체 롤백, 행 수·기존 값·버전·이력 그대로. 주입을 끄면 같은 계획이 그대로 생성된다")
    void rollbackAtEveryStage() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "sg-rollback");
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        long orgCd = f.attr(party, "기관 코드", true, "ORG_CD");
        f.pk(party, partyNo, orgCd);
        long org = entity(f, "조직");
        f.attr(org, "조직 이름", false, "ORG_NM");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long upperNo = f.attr(dept, "상위 당사자 번호", false, "UPR_PTY_NO");
        long upperCd = f.attr(dept, "상위 기관 코드", false, "UPR_ORG_CD");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", org, human);
        group(f, org, "조직 구분", dept, team);
        // 일반 관계 : 부서 자기 참조 (복합 — TableRelation 으로 만든다)
        LogicalModelDto.Relationship self = f.rel("상위 부서", dept, dept, false, 0);
        f.pairs(self, new long[]{partyNo, upperNo}, new long[]{orgCd, upperCd});
        // 기존 데이터 : 다른 엔터티 하나를 먼저 만들어 둔다 (값이 그대로인지 본다)
        long keep = entity(f, "상품");
        f.pk(keep, f.attr(keep, "상품 번호", true, "PRDT_NO"));
        PhysicalGenerationDto.Plan done = generation.preview(f.user, f.request(List.of(keep)));
        generation.apply(f.user, f.request(List.of(keep), done.planToken()));

        List<Long> chosen = List.of(party, org, human, dept, team);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        assertThat(plan.creatable()).isTrue();
        assertThat(plan.relations()).singleElement().satisfies(r -> assertThat(r.fkType()).isEqualTo("SELF_COMPOSITE"));
        List<Object> before = footprint(f);
        Object[][] stages = {
                {TableInfo.class, 1, "테이블 1개 뒤"},
                {ColumnInfo.class, 3, "컬럼 3개 뒤"},
                {EntityTableMapping.class, 1, "엔터티 매핑 1개 뒤"},
                {AttributeColumnMapping.class, 1, "속성 매핑 1개 뒤"},
                {SubtypeIdentifierColumnMapping.class, 1, "상속 PK 매핑 1개 뒤"},
                {TableRelation.class, 1, "상속 FK 1개 뒤"},
                {TableRelation.class, 4, "상속 FK 4개 = 일반 FK 저장"},
                {ModelChangeHistory.class, 0, "커밋 직전 변경 이력 저장"},
        };
        for (Object[] stage : stages) {
            Injector.fired.set(0);
            Injector.skip.set((Integer) stage[1]);
            Injector.target = (Class<?>) stage[0];
            assertThatThrownBy(() -> generation.apply(f.user, f.request(chosen, plan.planToken()))).as((String) stage[2]).isNotNull();
            Injector.target = null;
            assertThat(Injector.fired.get()).as((String) stage[2] + " : 주입한 실패가 일어났다").isOne();
            assertThat(footprint(f)).as((String) stage[2]).isEqualTo(before);
        }
        // 주입을 끄면 같은 토큰으로 그대로 만든다 (실패가 아무것도 남기지 않았다 — 계획이 같다)
        PhysicalGenerationDto.Result result = generation.apply(f.user, f.request(chosen, plan.planToken()));
        assertThat(result.tables()).isEqualTo(5);
        assertThat(result.inheritanceForeignKeys()).isEqualTo(4);
        assertThat(result.foreignKeys()).isOne();
        assertThat(result.inheritedKeyMappings()).isEqualTo(8);
        assertThat(f.versions()).isEqualTo((Integer) before.get(7) + 1);
    }
}
