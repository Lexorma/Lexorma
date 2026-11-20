package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.logical.controller.LogicalModelRestController;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.PairInput;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.PairSave;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 논리 관계 속성 짝 (M-7 보강 3차, P1) : 저장·조회·교체·해제, 검사(부모 주 식별자·자식 엔터티·중복·다른 프로젝트), 판정(NONE·PARTIAL·COMPLETE·PARENT_KEY_MISMATCH, 경고),
 * 자기 참조·같은 부모의 여러 관계, 관계 삭제·속성 삭제 보호·식별자 변경, 충돌(409)·승인 잠금·권한, 변경 이력·모델 버전(예전 형식과 비교), 모델 교환(형식 3, 번호 재연결, 잘못된 참조 거절),
 * 물리 생성 미리보기의 관계 상태(FK 는 여전히 만들지 않음)·planToken. AI Provider 를 등록하지 않은 상태로 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalRelationshipPairsIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(873_000_000L);

    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired AccountRepository accountRepository;
    @Autowired LogicalModelService logical;
    @Autowired LogicalModelRestController controller;
    @Autowired ModelVersionService versions;
    @Autowired ModelExchangeService exchange;
    @Autowired PhysicalGenerationService generation;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("복합 짝 저장·조회 : 순서 = 목록 순서, 부모 주 식별자 전체 = COMPLETE, 일부 = PARTIAL, 순서가 식별자와 다르면 ORDER_DIFFERS, 빈 목록이면 해제 (NONE). 관계 목록·ERD 에도 같은 판정")
    void saveCompositePairs() {
        Fixture f = fixture("pairs-composite");
        LogicalModelDto.RelationshipPairs view = logical.selectRelationshipPairs(f.user, f.orderToHistory.id());
        assertThat(view.state().status()).isEqualTo("NONE");
        assertThat(view.parentCandidates()).extracting(LogicalModelDto.PairCandidate::attributeName, LogicalModelDto.PairCandidate::keyOrder)
                .containsExactly(tuple("주문번호", 1), tuple("주문상세번호", 2));
        assertThat(view.childCandidates()).extracting(LogicalModelDto.PairCandidate::attributeName).contains("원본주문번호", "원본주문상세번호");

        LogicalModelDto.RelationshipPairs saved = save(f, f.orderToHistory, List.of(new PairInput(f.orderNo, f.srcOrderNo), new PairInput(f.detailNo, f.srcDetailNo)));
        assertThat(saved.state().status()).isEqualTo("COMPLETE");
        assertThat(saved.state().warnings()).isEmpty();
        assertThat(saved.state().pairs()).extracting(LogicalModelDto.AttributePair::parentAttributeName, LogicalModelDto.AttributePair::childAttributeName, LogicalModelDto.AttributePair::sortOrder)
                .containsExactly(tuple("주문번호", "원본주문번호", 1), tuple("주문상세번호", "원본주문상세번호", 2));
        assertThat(saved.modifiedAt()).as("짝만 바뀌어도 관계의 기준값이 바뀐다").isAfter(f.orderToHistory.modifiedAt());

        // 다시 조회해도 같다, 목록·ERD 에도 판정이 실린다
        assertThat(logical.selectRelationshipPairs(f.user, f.orderToHistory.id()).state()).isEqualTo(saved.state());
        assertThat(logical.selectRelationships(f.user, f.modelId)).filteredOn(r -> r.id().equals(f.orderToHistory.id())).singleElement()
                .satisfies(r -> assertThat(r.attributePairs().status()).isEqualTo("COMPLETE"));
        assertThat(logical.selectGraph(f.user, f.modelId).relationships()).filteredOn(r -> r.id().equals(f.orderToHistory.id())).singleElement()
                .satisfies(r -> assertThat(r.attributePairs().pairs()).hasSize(2));

        // 순서 바꾸기 → ORDER_DIFFERS, 일부만 → PARTIAL, 빈 목록 → NONE
        LogicalModelDto.RelationshipPairs reordered = save(f, saved, List.of(new PairInput(f.detailNo, f.srcDetailNo), new PairInput(f.orderNo, f.srcOrderNo)));
        assertThat(reordered.state().status()).isEqualTo("COMPLETE");
        assertThat(reordered.state().warnings()).containsExactly("ORDER_DIFFERS");
        LogicalModelDto.RelationshipPairs partial = save(f, reordered, List.of(new PairInput(f.orderNo, f.srcOrderNo)));
        assertThat(partial.state().status()).isEqualTo("PARTIAL");
        LogicalModelDto.RelationshipPairs cleared = save(f, partial, List.of());
        assertThat(cleared.state().status()).isEqualTo("NONE");
        assertThat(count("select count(*) from TB_LGCL_REL_ATRB where LGCL_REL_SN = ?", f.orderToHistory.id())).isZero();
        // 같은 구성으로 다시 저장하면 바뀌는 것이 없다 (기준값 그대로)
        assertThat(save(f, cleared, List.of()).modifiedAt()).isEqualTo(cleared.modifiedAt());
    }

    @Test
    @DisplayName("검사 (400, 아무것도 바꾸지 않음) : 부모 주 식별자가 아닌 부모 속성, 다른 엔터티·다른 프로젝트·없는 자식 속성, 부모·자식 중복, 빈 ID, 부모 주 식별자 없음")
    void rejectsInvalidPairs() {
        Fixture f = fixture("pairs-invalid");
        Fixture other = fixture("pairs-invalid-other");
        save(f, f.orderToHistory, List.of(new PairInput(f.orderNo, f.srcOrderNo)));
        LogicalModelDto.RelationshipPairs before = logical.selectRelationshipPairs(f.user, f.orderToHistory.id());
        for (List<PairInput> wrong : List.of(
                List.of(new PairInput(f.orderName, f.srcOrderNo)),                 // 부모 주 식별자가 아닌 속성
                List.of(new PairInput(f.orderNo, f.orderName)),                    // 자식이 아닌 부모 엔터티 속성
                List.of(new PairInput(f.orderNo, other.srcOrderNo)),               // 다른 프로젝트 속성
                List.of(new PairInput(f.orderNo, -1L)),                            // 없는 속성
                List.of(new PairInput(f.orderNo, f.srcOrderNo), new PairInput(f.orderNo, f.srcDetailNo)),   // 부모 중복
                List.of(new PairInput(f.orderNo, f.srcOrderNo), new PairInput(f.detailNo, f.srcOrderNo)),   // 자식 중복
                List.of(new PairInput(null, f.srcOrderNo)))) {
            assertThatThrownBy(() -> logical.saveRelationshipPairs(f.user, f.orderToHistory.id(), new PairSave(before.modifiedAt(), wrong)))
                    .as(wrong.toString()).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_LOGICAL_MODEL);
        }
        assertThat(logical.selectRelationshipPairs(f.user, f.orderToHistory.id()).state()).isEqualTo(before.state());
        // 부모 주 식별자가 없는 관계 (이력 → 주문 : 이력에 주 식별자 없음)
        LogicalModelDto.Relationship reverse = relationship(f, "역방향", f.history, f.order);
        assertThatThrownBy(() -> logical.saveRelationshipPairs(f.user, reverse.id(), new PairSave(reverse.modifiedAt(), List.of(new PairInput(f.srcOrderNo, f.orderNo)))))
                .isInstanceOf(CustomException.class).hasMessageContaining("주 식별자가 없어");
        // 다른 프로젝트의 관계는 없는 것과 같다
        assertThatThrownBy(() -> logical.selectRelationshipPairs(other.user, f.orderToHistory.id())).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("자기 참조(별도 속성은 정상, PK 자신은 SELF_KEY_REUSED 경고)·같은 부모의 관계 둘(같은 부모 속성을 관계마다 다른 자식 속성으로)·P4/P5 경고")
    void selfAndMultipleRelationships() {
        Fixture f = fixture("pairs-self");
        long deptKey = attribute(f, f.order, "상위주문번호", false, null).id(); // 주문 엔터티의 별도 속성
        LogicalModelDto.Relationship self = relationship(f, "상위 주문", f.order, f.order);
        LogicalModelDto.RelationshipPairs proper = save(f, self, List.of(new PairInput(f.orderNo, deptKey)));
        assertThat(proper.state().status()).isEqualTo("PARTIAL"); // 주문 주 식별자가 복합이라 둘 다 이어야 완성
        assertThat(proper.state().warnings()).doesNotContain("SELF_KEY_REUSED");
        LogicalModelDto.RelationshipPairs reused = save(f, proper, List.of(new PairInput(f.orderNo, f.orderNo), new PairInput(f.detailNo, f.detailNo)));
        assertThat(reused.state().status()).isEqualTo("COMPLETE");
        assertThat(reused.state().warnings()).contains("SELF_KEY_REUSED");

        // 같은 부모·자식 사이의 두 관계 : 각자의 자식 속성
        LogicalModelDto.Relationship second = relationship(f, "두 번째", f.order, f.history);
        save(f, f.orderToHistory, List.of(new PairInput(f.orderNo, f.srcOrderNo), new PairInput(f.detailNo, f.srcDetailNo)));
        LogicalModelDto.RelationshipPairs secondPairs = save(f, second, List.of(new PairInput(f.orderNo, f.otherOrderNo), new PairInput(f.detailNo, f.otherDetailNo)));
        assertThat(secondPairs.state().status()).isEqualTo("COMPLETE");
        assertThat(logical.selectRelationshipPairs(f.user, f.orderToHistory.id()).state().pairs()).extracting(LogicalModelDto.AttributePair::childAttributeName)
                .containsExactly("원본주문번호", "원본주문상세번호");

        // P4 (식별 관계인데 자식 속성이 자식 주 식별자에 없음) · P5 (필수 관계인데 자식 속성이 선택) 경고
        LogicalModelDto.Relationship identifying = logical.saveRelationship(f.user, new LogicalModelDto.Relationship(null, f.modelId, "식별", null, f.order, null, f.history, null,
                true, 1, 1, 0, null, null, null));
        jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", identifying.id());
        LogicalModelDto.RelationshipPairs warned = save(f, identifying, List.of(new PairInput(f.orderNo, f.optionalOrderNo), new PairInput(f.detailNo, f.srcDetailNo)));
        assertThat(warned.state().warnings()).contains("IDENTIFYING_CHILD_NOT_KEY", "REQUIRED_CHILD_NULLABLE");
    }

    @Test
    @DisplayName("관계 삭제는 짝도 지운다. 짝에서 쓰는 속성은 지울 수 없다(짝 해제 뒤 가능). 부모 식별자를 바꾸면 짝은 그대로 두고 PARENT_KEY_MISMATCH 로 보인다")
    void deletionAndIdentifierChange() {
        Fixture f = fixture("pairs-delete");
        LogicalModelDto.RelationshipPairs saved = save(f, f.orderToHistory, List.of(new PairInput(f.orderNo, f.srcOrderNo), new PairInput(f.detailNo, f.srcDetailNo)));
        assertThatThrownBy(() -> logical.deleteAttribute(f.user, f.srcOrderNo)).isInstanceOf(CustomException.class)
                .hasMessageContaining("속성 짝에서 사용 중인 속성");
        assertThat(count("select count(*) from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", f.srcOrderNo)).isOne();

        // 부모 식별자를 바꾼다 (주문명 하나로) : 짝은 그대로, 판정만 바뀐다
        LogicalModelDto.Identifier identifier = logical.selectIdentifiers(f.user, f.order).stream().filter(i -> Boolean.TRUE.equals(i.isPrimary())).findFirst().orElseThrow();
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", f.order);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(identifier.id(), f.order, identifier.identifierName(), true, List.of(f.orderName), null, identifier.modifiedAt()));
        LogicalModelDto.RelationshipPairs mismatch = logical.selectRelationshipPairs(f.user, f.orderToHistory.id());
        assertThat(mismatch.state().status()).isEqualTo("PARENT_KEY_MISMATCH");
        assertThat(mismatch.state().pairs()).hasSize(2);

        // 짝을 비우면 속성을 지울 수 있다
        LogicalModelDto.RelationshipPairs cleared = save(f, mismatch, List.of());
        logical.deleteAttribute(f.user, f.srcOrderNo);
        // 관계 삭제 → 짝도 없다
        save(f, cleared, List.of(new PairInput(f.orderName, f.srcDetailNo)));
        assertThat(count("select count(*) from TB_LGCL_REL_ATRB where LGCL_REL_SN = ?", f.orderToHistory.id())).isOne();
        logical.deleteRelationship(f.user, f.orderToHistory.id());
        assertThat(count("select count(*) from TB_LGCL_REL_ATRB where LGCL_REL_SN = ?", f.orderToHistory.id())).isZero();
        assertThat(saved.relationshipId()).isEqualTo(f.orderToHistory.id());
    }

    @Test
    @DisplayName("충돌·잠금·권한 : 예전 기준값 409, 기준값 없는 HTTP 요청 400, 승인된 관계는 바꿀 수 없음, VIEWER 조회 가능·저장 거절, USER 저장 가능")
    void conflictsAndRoles() {
        Fixture f = fixture("pairs-conflict");
        save(f, f.orderToHistory, List.of(new PairInput(f.orderNo, f.srcOrderNo)));
        assertThatThrownBy(() -> logical.saveRelationshipPairs(f.user, f.orderToHistory.id(), new PairSave(f.orderToHistory.modifiedAt(), List.of())))
                .as("이미 다른 저장이 기준값을 바꿨다").isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThatThrownBy(() -> controller.saveRelationshipPairs(f.user, f.orderToHistory.id(), new PairSave(null, List.of())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);

        LogicalModelDto.RelationshipPairs current = logical.selectRelationshipPairs(f.user, f.orderToHistory.id());
        jdbc.update("update TB_LGCL_REL set APRV_YN = true where LGCL_REL_SN = ?", f.orderToHistory.id());
        assertThatThrownBy(() -> logical.saveRelationshipPairs(f.user, f.orderToHistory.id(), new PairSave(current.modifiedAt(), List.of())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", f.orderToHistory.id());

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, f.user.getProjectId(), "900", "ROLE_VIEWER");
        assertThat(controller.selectRelationshipPairs(viewer, f.orderToHistory.id()).getBody().state().status()).isEqualTo("PARTIAL");
        assertThatThrownBy(() -> controller.saveRelationshipPairs(viewer, f.orderToHistory.id(), new PairSave(current.modifiedAt(), List.of())))
                .isInstanceOf(AccessDeniedException.class);
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, f.user.getProjectId(), "901", "ROLE_USER");
        assertThat(controller.saveRelationshipPairs(user, f.orderToHistory.id(), new PairSave(current.modifiedAt(), List.of())).getBody().state().status()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("변경 이력 (관계 이력의 attributes) · 모델 버전 스냅샷 · 비교 : 짝 변경은 MODIFIED(attributes), 예전 형식(attributes 없음) 버전과 짝 없는 현재는 UNCHANGED")
    void historyAndVersions() {
        Fixture f = fixture("pairs-version");
        ModelVersionDto.Summary empty = versions.create(f.user, new ModelVersionDto.Create("pairs-empty", null));
        // 예전 형식 흉내 : 그 버전의 관계 state 에서 attributes 를 뺀다 (형식 5 이하 버전)
        String snapshot = jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, empty.id());
        assertThat(snapshot).contains("\"attributes\":[]");
        jdbc.update("update TB_MDL_VER set MDL_CN = ? where MDL_VER_SN = ?", snapshot.replace(",\"attributes\":[]", "").replace("\"attributes\":[],", ""), empty.id());
        assertThat(versions.compare(f.user, empty.id(), null).changes()).filteredOn(c -> "LOGICAL_RELATIONSHIP".equals(c.targetType())).isEmpty();

        save(f, f.orderToHistory, List.of(new PairInput(f.orderNo, f.srcOrderNo), new PairInput(f.detailNo, f.srcDetailNo)));
        String after = jdbc.queryForObject("select CHG_AFTR_CN from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'LOGICAL_RELATIONSHIP' and TRGT_SN = ? order by MDL_CHG_HSTRY_SN desc fetch first 1 rows only",
                String.class, f.user.getProjectId(), f.orderToHistory.id());
        assertThat(after).contains("\"attributes\"").contains("원본주문번호").contains("원본주문상세번호");
        var comparison = versions.compare(f.user, empty.id(), null);
        assertThat(comparison.changes()).filteredOn(c -> "LOGICAL_RELATIONSHIP".equals(c.targetType())).singleElement().satisfies(c -> {
            assertThat(c.change()).isEqualTo("MODIFIED");
            assertThat(c.changedFields()).contains("attributes");
        });
        ModelVersionDto.Summary withPairs = versions.create(f.user, new ModelVersionDto.Create("pairs-full", null));
        assertThat(versions.snapshot(f.user, withPairs.id()).objects().toString()).contains("parentAttributeId").contains("childAttributeId");
    }

    @Test
    @DisplayName("모델 교환 (형식 3) : 내보낸 짝을 새 프로젝트의 새 속성 번호로 다시 잇는다. 형식 2 파일의 짝·잘못된 참조는 400 이고 아무것도 만들지 않는다")
    void exchangeRoundTrip() {
        Fixture f = fixture("pairs-export");
        save(f, f.orderToHistory, List.of(new PairInput(f.orderNo, f.srcOrderNo), new PairInput(f.detailNo, f.srcDetailNo)));
        ModelExchangeDto.Document document = exchange.export(f.user);
        assertThat(document.formatVersion()).isEqualTo(4); // 형식 4 (주제 영역)에도 짝은 그대로

        AccountDto target = member(fixture("pairs-import").user);
        long targetProject = target.getProjectId();
        ModelExchangeDto.Selection selection = new ModelExchangeDto.Selection(List.of(), List.of("pairs-export"), List.of());
        var plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(targetProject, document, selection));
        assertThat(plan.applicable()).as(plan.toString()).isTrue();
        var result = exchange.apply(target, new ModelExchangeDto.ApplyRequest(targetProject, document, selection, plan.planToken(), plan.requestId()));
        assertThat(result.created()).containsEntry("LOGICAL_RELATIONSHIP_ATTRIBUTE", 2);
        List<Map<String, Object>> imported = jdbc.queryForList("select pa.ATRB_NM P, ca.ATRB_NM C, x.SORT_ORD O, pe.PRJ_SN PP, ce.PRJ_SN CP from TB_LGCL_REL_ATRB x"
                + " join TB_LGCL_REL r on r.LGCL_REL_SN = x.LGCL_REL_SN join TB_LGCL_ATRB pa on pa.LGCL_ATRB_SN = x.ORGNL_ATRB_SN join TB_LGCL_ENTT pe on pe.LGCL_ENTT_SN = pa.LGCL_ENTT_SN"
                + " join TB_LGCL_ATRB ca on ca.LGCL_ATRB_SN = x.TRGT_ATRB_SN join TB_LGCL_ENTT ce on ce.LGCL_ENTT_SN = ca.LGCL_ENTT_SN where r.PRJ_SN = ? order by x.SORT_ORD", targetProject);
        assertThat(imported).extracting(m -> m.get("P"), m -> m.get("C"), m -> ((Number) m.get("PP")).longValue(), m -> ((Number) m.get("CP")).longValue())
                .containsExactly(tuple("주문번호", "원본주문번호", targetProject, targetProject), tuple("주문상세번호", "원본주문상세번호", targetProject, targetProject));
        assertThat(count("select count(*) from TB_LGCL_REL_ATRB where ORGNL_ATRB_SN = ?", f.orderNo)).as("원래 프로젝트의 속성 번호를 쓰지 않는다").isOne();

        // 형식 2 에 짝 → 400, 잘못된 참조(자식 속성이 부모 엔터티 속성) → 400, 둘 다 아무것도 만들지 않는다
        AccountDto clean = member(fixture("pairs-import-bad").user);
        long cleanProject = clean.getProjectId();
        int relationshipsBefore = count("select count(*) from TB_LGCL_REL where PRJ_SN = ?", cleanProject);
        ModelExchangeDto.Document old = new ModelExchangeDto.Document(document.format(), 2, document.exportedAt(), document.dbType(), document.counts(), document.model(), null);
        assertThatThrownBy(() -> exchange.plan(clean, new ModelExchangeDto.PlanRequest(cleanProject, old, selection))).isInstanceOf(CustomException.class)
                .hasMessageContaining("형식 버전 2");
        ModelExchangeDto.Document broken = withBrokenPair(document, f.orderName);
        assertThatThrownBy(() -> exchange.plan(clean, new ModelExchangeDto.PlanRequest(cleanProject, broken, selection))).isInstanceOf(CustomException.class)
                .hasMessageContaining("속성 짝이 올바르지 않습니다");
        assertThatThrownBy(() -> exchange.apply(clean, new ModelExchangeDto.ApplyRequest(cleanProject, broken, selection, plan.planToken(), plan.requestId())))
                .isInstanceOf(CustomException.class);
        assertThat(count("select count(*) from TB_LGCL_REL where PRJ_SN = ?", cleanProject)).isEqualTo(relationshipsBefore);
        // 형식 2 파일(짝 없음)은 그대로 가져온다
        ModelExchangeDto.Document withoutPairs = new ModelExchangeDto.Document(document.format(), 2, document.exportedAt(), document.dbType(), document.counts(),
                stripPairs(document), null);
        var oldPlan = exchange.plan(clean, new ModelExchangeDto.PlanRequest(cleanProject, withoutPairs, selection));
        exchange.apply(clean, new ModelExchangeDto.ApplyRequest(cleanProject, withoutPairs, selection, oldPlan.planToken(), oldPlan.requestId()));
        assertThat(count("select count(*) from TB_LGCL_REL where PRJ_SN = ?", cleanProject)).isEqualTo(relationshipsBefore + 1);
    }

    @Test
    @DisplayName("물리 생성 미리보기 : 짝 없음·일부·완성을 사유로 구분한다 (부모 테이블이 없으면 완성이어도 FK 없음). 짝 상태가 바뀌면 409, 관계 이름만 바꾸면 토큰 그대로")
    void generationPreview() {
        Fixture f = fixture("pairs-generate");
        // 용어 없는 속성은 빼고 만든다 (P7)
        List<Long> excluded = List.of(f.otherOrderNo, f.otherDetailNo, f.optionalOrderNo);
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.history), null, null, excluded);
        PhysicalGenerationDto.Plan none = generation.preview(f.user, request);
        assertThat(none.relations()).singleElement().satisfies(r -> {
            assertThat(r.reasons()).first().isEqualTo("ATTRIBUTE_PAIRS_MISSING");
            assertThat(r.pairStatus()).isEqualTo("NONE");
        });
        LogicalModelDto.RelationshipPairs partial = save(f, f.orderToHistory, List.of(new PairInput(f.orderNo, f.srcOrderNo)));
        PhysicalGenerationDto.Plan partialPlan = generation.preview(f.user, request);
        assertThat(partialPlan.relations().get(0).reasons()).first().isEqualTo("ATTRIBUTE_PAIRS_INCOMPLETE");
        assertThat(partialPlan.planToken()).as("짝 상태가 바뀌면 계획이 다르다").isNotEqualTo(none.planToken());
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.history), null, none.planToken(), excluded)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);

        LogicalModelDto.RelationshipPairs complete = save(f, partial, List.of(new PairInput(f.orderNo, f.srcOrderNo), new PairInput(f.detailNo, f.srcDetailNo)));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        // 짝은 완성이지만 부모(주문)를 이번에 만들지 않고 매핑도 없어 FK 를 만들 수 없다 (4차 : 완성된 짝 + 부모 테이블이면 READY)
        assertThat(plan.relations()).singleElement().satisfies(r -> {
            assertThat(r.fkCreated()).isFalse();
            assertThat(r.fkStatus()).isEqualTo("PARENT_TABLE_UNAVAILABLE");
            assertThat(r.reasons()).first().isEqualTo("PARENT_TABLE_MISSING");
            assertThat(r.pairStatus()).isEqualTo("COMPLETE");
            assertThat(r.pairs()).hasSize(2);
            assertThat(r.message()).contains("부모 테이블이 없어");
        });
        // 관계 이름(표시 정보)만 바꾸면 토큰 그대로
        logical.saveRelationship(f.user, new LogicalModelDto.Relationship(f.orderToHistory.id(), f.modelId, "이름만 바꿈", null, f.order, null, f.history, null,
                false, 1, 1, 0, null, null, null, complete.modifiedAt()));
        assertThat(generation.preview(f.user, request).planToken()).isEqualTo(plan.planToken());
        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.history), null, plan.planToken(), excluded));
        assertThat(count("select count(*) from TB_TBL_REL where PRJ_SN = ?", f.user.getProjectId())).as("부모 테이블이 없어 FK 없음").isZero();
        assertThat(count("select count(*) from TB_COL where TBL_SN = ? and RFRNC_TBL_NM is not null", result.tableIds().get(0))).isZero();
    }

    // ------------------------------------------------------------------ 보조

    /** 주문(주 식별자 = 주문번호 + 주문상세번호, 주문명) → 주문이력(원본주문번호·원본주문상세번호·다른주문번호·다른주문상세번호·선택주문번호, 주 식별자 없음) */
    private record Fixture(AccountDto user, Long modelId, Long order, Long history, Long orderNo, Long detailNo, Long orderName,
                           Long srcOrderNo, Long srcDetailNo, Long otherOrderNo, Long otherDetailNo, Long optionalOrderNo, LogicalModelDto.Relationship orderToHistory) {
    }

    private Fixture fixture(String name) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name).isActive(true).account(admin).dbType(DbType.POSTGRESQL).build());
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        long p = project.getId();
        for (String[] w : new String[][]{{"주문", "ORD"}, {"이력", "HSTRY"}, {"번호", "NO"}, {"상세", "DTL"}, {"명", "NM"}, {"원본", "ORGNL"}}) word(p, w[0], w[1]);
        long text = domain(p);
        LogicalModelDto.Model model = logical.saveModel(user, new LogicalModelDto.Model(null, name, null));
        long order = logical.saveEntity(user, new LogicalModelDto.Entity(null, model.id(), "주문", null, null, null, null)).id();
        long history = logical.saveEntity(user, new LogicalModelDto.Entity(null, model.id(), "주문 이력", null, null, null, null)).id();
        Fixture partial = new Fixture(user, model.id(), order, history, null, null, null, null, null, null, null, null, null);
        long orderName = attribute(partial, order, "주문명", false, term(p, "주문_명", "ORD_NM", text)).id();
        long orderNo = attribute(partial, order, "주문번호", true, term(p, "주문_번호", "ORD_NO", text)).id();
        long detailNo = attribute(partial, order, "주문상세번호", true, term(p, "상세_번호", "DTL_NO", text)).id();
        logical.saveIdentifier(user, new LogicalModelDto.Identifier(null, order, "주문 식별자", true, List.of(orderNo, detailNo), null));
        long srcOrderNo = attribute(partial, history, "원본주문번호", true, term(p, "원본_주문_번호", "ORGNL_ORD_NO", text)).id();
        long srcDetailNo = attribute(partial, history, "원본주문상세번호", true, term(p, "원본_상세_번호", "ORGNL_DTL_NO", text)).id();
        long otherOrderNo = attribute(partial, history, "다른주문번호", true, null).id();
        long otherDetailNo = attribute(partial, history, "다른주문상세번호", true, null).id();
        long optionalOrderNo = attribute(partial, history, "선택주문번호", false, null).id();
        Fixture f = new Fixture(user, model.id(), order, history, orderNo, detailNo, orderName, srcOrderNo, srcDetailNo, otherOrderNo, otherDetailNo, optionalOrderNo, null);
        LogicalModelDto.Relationship relationship = relationship(f, "주문 이력", order, history);
        return new Fixture(user, model.id(), order, history, orderNo, detailNo, orderName, srcOrderNo, srcDetailNo, otherOrderNo, otherDetailNo, optionalOrderNo, relationship);
    }

    /** 승인 전 관계 (관리자가 만들면 승인되므로 풀어 둔다 — 승인된 관계는 짝을 바꿀 수 없다) */
    private LogicalModelDto.Relationship relationship(Fixture f, String name, Long parent, Long child) {
        LogicalModelDto.Relationship saved = logical.saveRelationship(f.user, new LogicalModelDto.Relationship(null, f.modelId, name, null, parent, null, child, null,
                false, 1, 1, 0, null, null, null));
        jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", saved.id());
        return saved;
    }

    private LogicalModelDto.RelationshipPairs save(Fixture f, LogicalModelDto.Relationship relationship, List<PairInput> pairs) {
        return logical.saveRelationshipPairs(f.user, relationship.id(), new PairSave(logical.selectRelationshipPairs(f.user, relationship.id()).modifiedAt(), pairs));
    }

    private LogicalModelDto.RelationshipPairs save(Fixture f, LogicalModelDto.RelationshipPairs current, List<PairInput> pairs) {
        return logical.saveRelationshipPairs(f.user, current.relationshipId(), new PairSave(current.modifiedAt(), pairs));
    }

    private LogicalModelDto.Attribute attribute(Fixture f, Long entityId, String name, boolean required, Long termId) {
        return logical.saveAttribute(f.user, new LogicalModelDto.Attribute(null, entityId, name, null, termId == null ? "문자" : null, required, null, termId, null, null, null));
    }

    private AccountDto member(AccountDto user) {
        projectMembers.save(ProjectMember.builder().project(projectRepository.findById(user.getProjectId()).orElseThrow())
                .account(accountRepository.findById(Long.valueOf(user.getId())).orElseThrow()).isActive(true).build());
        return SecurityTestSupport.loginAs(projectRepository, user.getProjectId(), user.getId(), "ROLE_ADMIN");
    }

    /** 첫 짝의 자식 속성을 부모 엔터티 속성 번호로 바꾼 파일 (잘못된 참조) */
    private static ModelExchangeDto.Document withBrokenPair(ModelExchangeDto.Document document, Long parentEntityAttribute) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        List<ModelExchangeDto.Item> relationships = new ArrayList<>();
        for (ModelExchangeDto.Item item : model.get("LOGICAL_RELATIONSHIP")) {
            Map<String, Object> state = new HashMap<>(item.state());
            @SuppressWarnings("unchecked") List<Map<String, Object>> pairs = (List<Map<String, Object>>) state.get("attributes");
            if (pairs != null && !pairs.isEmpty()) {
                List<Map<String, Object>> changed = new ArrayList<>();
                for (Map<String, Object> pair : pairs) changed.add(new HashMap<>(pair));
                changed.get(0).put("childAttributeId", parentEntityAttribute);
                state.put("attributes", changed);
            }
            relationships.add(new ModelExchangeDto.Item(item.id(), item.name(), state));
        }
        model.put("LOGICAL_RELATIONSHIP", relationships);
        return new ModelExchangeDto.Document(document.format(), document.formatVersion(), document.exportedAt(), document.dbType(), document.counts(), model, document.layouts());
    }

    private static Map<String, List<ModelExchangeDto.Item>> stripPairs(ModelExchangeDto.Document document) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        model.put("LOGICAL_RELATIONSHIP", model.get("LOGICAL_RELATIONSHIP").stream().map(item -> {
            Map<String, Object> state = new HashMap<>(item.state());
            state.remove("attributes");
            return new ModelExchangeDto.Item(item.id(), item.name(), state);
        }).toList());
        return model;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void word(long projectId, String name, String abbreviation) {
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", IDS.incrementAndGet(), projectId, name, abbreviation, abbreviation, name);
    }

    private long domain(long projectId) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '테스트', '테스트', 'V20', 'V20', 'VARCHAR', 20, null, true, current_timestamp, current_timestamp, 'test', 'test')", id, projectId);
        return id;
    }

    private long term(long projectId, String name, String abbreviation, long domainId) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", id, projectId, name, name, abbreviation, domainId);
        return id;
    }
}
