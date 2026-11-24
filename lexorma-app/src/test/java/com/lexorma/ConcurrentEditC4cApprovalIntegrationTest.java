package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.controller.LogicalModelRestController;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.repository.LogicalEntityRepository;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M8-C4c-1 C4c-D01 (docs/ongoing/modeling-concurrency-m8.md 20장) : 엔터티 승인은 직접 정의한 속성·식별자를 함께 승인하므로, 상세 조회의 구성 기준값(composition)이
 * 잠근 뒤의 구성과 같을 때만 승인한다. 승인 취소는 엔터티 기준값만, 이미 같은 상태면 아무것도 하지 않는다. 관계·주제 영역 분류는 승인 구성에 넣지 않는다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC4cApprovalIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired LogicalModelRestController logicalApi;
    @Autowired LogicalSubjectAreaService subjectAreas;
    @Autowired LogicalEntityRepository entities;
    @Autowired ProjectRepository projectRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired com.lexorma.core.security.repository.AccountRepository accounts;
    @Autowired com.lexorma.core.security.repository.RoleRepository roles;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private ConcurrentEditC4cSupport support() {
        return new ConcurrentEditC4cSupport(tx, jdbc, dataSource, projectRepository);
    }

    private record Target(GenerationFixture f, long entity, long attribute) {
    }

    private Target target(String name) {
        GenerationFixture f = GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long entity = f.entity("고객");
        long attribute = f.attr(entity, "고객번호", true, "CUST_NO");
        f.pk(entity, attribute);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", entity);
        return new Target(f, entity, attribute);
    }

    private LogicalModelDto.Entity detail(Target t) {
        return logical.selectEntity(t.f().user, t.entity());
    }

    private boolean approved(long entity) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select APRV_YN from TB_LGCL_ENTT where LGCL_ENTT_SN = ?", Boolean.class, entity));
    }

    private static String code(Runnable call) {
        return ConcurrentEditC4cSupport.outcome(call);
    }

    @Test
    @DisplayName("상세를 본 뒤 속성이 더해지면 그 기준값의 승인은 409 · 승인 안 됨 → 다시 읽은 구성이면 승인")
    void attributeAdded() {
        Target t = target("c4c-a1");
        LogicalModelDto.Entity seen = detail(t);
        assertThat(seen.composition()).startsWith("v1:");
        t.f().attrNoTerm(t.entity(), "본 적 없는 속성");
        assertThat(code(() -> logical.updateEntityApproval(t.f().user, t.entity(), true, seen.modifiedAt(), seen.composition()))).isEqualTo("MODEL_CONFLICT");
        assertThat(approved(t.entity())).isFalse();
        LogicalModelDto.Entity fresh = detail(t);
        assertThat(fresh.composition()).isNotEqualTo(seen.composition());
        logical.updateEntityApproval(t.f().user, t.entity(), true, fresh.modifiedAt(), fresh.composition());
        assertThat(approved(t.entity())).isTrue();
    }

    @Test
    @DisplayName("상세를 본 뒤 속성 내용 수정·속성 삭제·식별자 구성 변경 → 각각 승인 409, 엔터티는 미승인 그대로")
    void attributeEditedDeletedIdentifierChanged() {
        Target t = target("c4c-a2");
        long extra = t.f().attrNoTerm(t.entity(), "비고");
        // 속성 내용 수정
        LogicalModelDto.Entity seen = detail(t);
        LogicalModelDto.Attribute a = seen.attributes().stream().filter(x -> x.id() == extra).findFirst().orElseThrow();
        logical.saveAttribute(t.f().user, new LogicalModelDto.Attribute(a.id(), t.entity(), a.attributeName(), "고친 설명", a.dataType(), a.isRequired(), a.sortOrder(),
                a.termId(), null, null, null, a.modifiedAt()));
        assertThat(code(() -> logical.updateEntityApproval(t.f().user, t.entity(), true, seen.modifiedAt(), seen.composition()))).isEqualTo("MODEL_CONFLICT");
        // 식별자 구성 변경 (보조 식별자 추가)
        LogicalModelDto.Entity seen2 = detail(t);
        logical.saveIdentifier(t.f().user, new LogicalModelDto.Identifier(null, t.entity(), "보조", false, List.of(extra), null));
        assertThat(code(() -> logical.updateEntityApproval(t.f().user, t.entity(), true, seen2.modifiedAt(), seen2.composition()))).isEqualTo("MODEL_CONFLICT");
        // 속성 삭제 (식별자를 먼저 지운다)
        LogicalModelDto.Entity before = detail(t);
        LogicalModelDto.Identifier alternate = before.identifiers().stream().filter(i -> !Boolean.TRUE.equals(i.isPrimary())).findFirst().orElseThrow();
        logical.deleteIdentifier(t.f().user, alternate.id(), alternate.modifiedAt());
        LogicalModelDto.Entity seen3 = detail(t);
        LogicalModelDto.Attribute doomed = seen3.attributes().stream().filter(x -> x.id() == extra).findFirst().orElseThrow();
        logical.deleteAttribute(t.f().user, extra, doomed.modifiedAt());
        assertThat(code(() -> logical.updateEntityApproval(t.f().user, t.entity(), true, seen3.modifiedAt(), seen3.composition()))).isEqualTo("MODEL_CONFLICT");
        assertThat(approved(t.entity())).isFalse();
    }

    @Test
    @DisplayName("승인 구성에 넣지 않는 변경(관계 추가·주제 영역 분류)은 승인을 막지 않는다 · 엔터티 자신의 수정은 엔터티 기준값으로 409")
    void unrelatedChangesDoNotConflict() {
        Target t = target("c4c-a3");
        long other = t.f().entity("주문");
        LogicalModelDto.Entity seen = detail(t);
        t.f().rel("고객 주문", t.entity(), other, false, 0);
        LogicalSubjectAreaDto.Area area = subjectAreas.save(t.f().user, new LogicalSubjectAreaDto.Save(null, t.f().modelId, null, "영업", null, null, null));
        subjectAreas.assign(t.f().user, t.f().modelId, new LogicalSubjectAreaDto.Assignment(JsonNodeFactory.instance.numberNode(area.id()),
                List.of(new LogicalSubjectAreaDto.AssignmentItem(t.entity(), JsonNodeFactory.instance.nullNode()))));
        logical.updateEntityApproval(t.f().user, t.entity(), true, seen.modifiedAt(), seen.composition());
        assertThat(approved(t.entity())).isTrue();

        Target s = target("c4c-a3b");
        LogicalModelDto.Entity seenS = detail(s);
        logical.saveEntity(s.f().user, new LogicalModelDto.Entity(s.entity(), s.f().modelId, "고객", "고친 설명", null, null, null, seenS.modifiedAt(), null));
        assertThat(code(() -> logical.updateEntityApproval(s.f().user, s.entity(), true, seenS.modifiedAt(), seenS.composition()))).isEqualTo("MODEL_CONFLICT");
    }

    @Test
    @DisplayName("HTTP : 승인은 composition 필수(400 MODEL_BASE_REQUIRED) · 승인 취소는 modifiedAt 만 · 이미 같은 상태면 예전 구성이어도 아무것도 하지 않는다")
    void httpContract() {
        Target t = target("c4c-a4");
        LogicalModelDto.Entity seen = detail(t);
        assertThatThrownBy(() -> logicalApi.updateEntityApproval(t.f().user, t.entity(), true, seen.modifiedAt(), null))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        assertThat(approved(t.entity())).isFalse();
        logicalApi.updateEntityApproval(t.f().user, t.entity(), true, seen.modifiedAt(), seen.composition());
        assertThat(approved(t.entity())).isTrue();
        // 같은 상태 재요청 : 예전 구성 기준값이어도 그대로 (409 아님)
        assertThat(code(() -> logicalApi.updateEntityApproval(t.f().user, t.entity(), true, seen.modifiedAt(), "v1:stale"))).isEqualTo("OK");
        // 승인 취소 : composition 없이 엔터티 기준값만
        LogicalModelDto.Entity approvedNow = detail(t);
        logicalApi.updateEntityApproval(t.f().user, t.entity(), false, approvedNow.modifiedAt(), null);
        assertThat(approved(t.entity())).isFalse();
    }

    @Test
    @DisplayName("동시 : A 가 모델 행을 잡고 속성을 더하는 동안 B 가 예전 구성으로 승인 → B 는 기다렸다가 409, 승인 안 됨 (A 의 속성은 저장)")
    void approveWhileAttributeAdded() throws Exception {
        Target t = target("c4c-a5");
        LogicalModelDto.Entity seen = detail(t);
        String[] r = support().both(t.f().user, "ROLE_ADMIN",
                () -> t.f().attrNoTerm(t.entity(), "동시 속성"),
                () -> logical.updateEntityApproval(t.f().user, t.entity(), true, seen.modifiedAt(), seen.composition()));
        ConcurrentEditC4cSupport.report("엔터티 승인 vs 속성 추가", r);
        assertThat(r).containsExactly("OK", "MODEL_CONFLICT");
        assertThat(approved(t.entity())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_ATRB where LGCL_ENTT_SN = ?", Integer.class, t.entity())).isEqualTo(2);
    }

    @Test
    @DisplayName("동시 : A 가 승인하는 동안 B(승인 권한 없는 USER)가 속성을 더함 → B 는 기다렸다가 승인된 엔터티라 거절 (승인된 구성은 승인자가 본 그대로). 승인 권한자는 기존 정책대로 승인된 엔터티도 고친다")
    void attributeAddedWhileApproving() throws Exception {
        Target t = target("c4c-a6");
        LogicalModelDto.Entity seen = detail(t);
        // 같은 계정은 프로젝트 관리자(= 승인 권한자)라 ROLE_USER 여도 고칠 수 있다 : 관리자가 아닌 USER 계정을 따로 만든다
        String username = "c4c-user-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        com.lexorma.core.security.domain.entity.Account plain = tx.execute(s -> accounts.save(com.lexorma.core.security.domain.entity.Account.builder()
                .username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new java.util.HashSet<>(java.util.Set.of(roles.findByRoleName("ROLE_USER")))).projectMember(new java.util.ArrayList<>()).build()));
        String[] r = support().both(t.f().user, String.valueOf(plain.getId()), "ROLE_USER",
                () -> logical.updateEntityApproval(t.f().user, t.entity(), true, seen.modifiedAt(), seen.composition()),
                () -> t.f().attrNoTerm(t.entity(), "늦은 속성"));
        ConcurrentEditC4cSupport.report("속성 추가 vs 엔터티 승인", r);
        assertThat(r).containsExactly("OK", "APPROVED_DATA_CANNOT_BE_UPDATE");
        assertThat(approved(t.entity())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_ATRB where LGCL_ENTT_SN = ?", Integer.class, t.entity())).isEqualTo(1);
        assertThat(logical.selectEntity(t.f().user, t.entity()).composition()).isEqualTo(seen.composition());
    }
}
