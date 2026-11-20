package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.logical.controller.LogicalModelRestController;
import com.lexorma.data.logical.controller.LogicalSubjectAreaRestController;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Assignment;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.AssignmentItem;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Save;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-6 후속 4차 : 논리 모델 주제 영역 — 계층(3단계·이동·순환·이름 중복), 엔터티 분류(승인 정책 분리·동시성·원자성), 삭제·무결성, 목록·ERD 범위,
 * 주제 영역별 독립 배치, 변경 이력·모델 버전·교환 형식 4, 권한·격리, 실제 독립 트랜잭션 동시성. H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalSubjectAreaIntegrationTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired LogicalSubjectAreaService areas;
    @Autowired LogicalSubjectAreaRestController areaController;
    @Autowired LogicalModelRestController logicalController;
    @Autowired ModelVersionService versions;
    @Autowired ModelExchangeService exchange;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 준비

    private GenerationFixture fixture(String name) {
        return GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
    }

    private LogicalSubjectAreaDto.Area area(GenerationFixture f, Long parentId, String name) {
        return areas.save(f.user, new Save(null, f.modelId, parentId, name, null, null, null));
    }

    private LogicalSubjectAreaDto.Area find(GenerationFixture f, Long id) {
        return areas.selectTree(f.user, f.modelId).areas().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private LogicalSubjectAreaDto.Area update(GenerationFixture f, LogicalSubjectAreaDto.Area area, Long parentId, String name) {
        LogicalSubjectAreaDto.Area now = find(f, area.id());
        return areas.save(f.user, new Save(now.id(), f.modelId, parentId, name, now.areaDescription(), null, now.modifiedAt()));
    }

    /** 엔터티를 영역에 (지금 소속 = expected) */
    private LogicalSubjectAreaDto.AssignmentResult assign(GenerationFixture f, Long target, Map<Long, Long> expected) {
        List<AssignmentItem> items = new ArrayList<>();
        expected.forEach((entity, area) -> items.add(new AssignmentItem(entity, node(area))));
        return areas.assign(f.user, f.modelId, new Assignment(node(target), items));
    }

    private static JsonNode node(Long id) {
        return id == null ? JSON.nullNode() : JSON.numberNode(id);
    }

    private static Map<Long, Long> one(long entity, Long expected) {
        Map<Long, Long> map = new LinkedHashMap<>();
        map.put(entity, expected);
        return map;
    }

    private Long areaOf(long entity) {
        List<Long> found = jdbc.queryForList("select LGCL_SBJ_AREA_SN from TB_LGCL_SBJ_AREA_ENTT where LGCL_ENTT_SN = ?", Long.class, entity);
        return found.isEmpty() ? null : found.getFirst();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void error(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static List<Long> ids(org.springframework.data.domain.Page<LogicalModelDto.EntityRow> page) {
        return page.getContent().stream().map(LogicalModelDto.EntityRow::id).toList();
    }

    // ------------------------------------------------------------------ 계층

    @Test
    @DisplayName("계층 : 1~3단계 등록, 4단계 거절, 같은 상위(최상위 포함) 이름 중복은 대소문자 무시로 거절, 다른 상위의 같은 이름은 허용, 경로·깊이·설명·순서")
    void hierarchyAndNames() {
        GenerationFixture f = fixture("sa-tree");
        var customer = area(f, null, "고객 관리");
        var member = area(f, customer.id(), "회원 관리");
        var basic = area(f, member.id(), "기본 정보");
        var sales = area(f, null, "판매 관리");
        var salesBasic = area(f, sales.id(), "기본 정보");
        assertThat(basic.depth()).isEqualTo(3);
        assertThat(basic.path()).containsExactly("고객 관리", "회원 관리", "기본 정보");
        assertThat(salesBasic.path()).containsExactly("판매 관리", "기본 정보");
        error(() -> area(f, basic.id(), "4단계"), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> area(f, null, "고객 관리"), ErrorCode.SAVED_DATA_EXISTS);
        error(() -> area(f, null, "  고객 관리  "), ErrorCode.SAVED_DATA_EXISTS);
        error(() -> area(f, member.id(), "기본 정보"), ErrorCode.SAVED_DATA_EXISTS);
        error(() -> area(f, sales.id(), "기본 정보"), ErrorCode.SAVED_DATA_EXISTS);
        error(() -> area(f, sales.id(), "기본 정보".toUpperCase()), ErrorCode.SAVED_DATA_EXISTS);
        var english = area(f, null, "Orders");
        error(() -> area(f, null, "ORDERS"), ErrorCode.SAVED_DATA_EXISTS);
        error(() -> area(f, null, " "), ErrorCode.INVALID_LOGICAL_MODEL);

        // 설명·순서 : 순서를 주지 않으면 같은 상위의 맨 뒤
        var described = areas.save(f.user, new Save(null, f.modelId, null, "설명 영역", "업무 설명", 0, null));
        assertThat(described.areaDescription()).isEqualTo("업무 설명");
        assertThat(described.sortOrder()).isZero();
        assertThat(english.sortOrder()).isEqualTo(3);
        var tree = areas.selectTree(f.user, f.modelId);
        assertThat(tree.areas()).extracting(LogicalSubjectAreaDto.Area::areaName).first().isEqualTo("설명 영역"); // 순서 0 이 맨 앞
        assertThat(tree.areas()).extracting(a -> String.join(" / ", a.path()))
                .containsSubsequence("고객 관리", "고객 관리 / 회원 관리", "고객 관리 / 회원 관리 / 기본 정보", "판매 관리", "판매 관리 / 기본 정보");
        assertThat(find(f, customer.id()).childCount()).isEqualTo(1);
        // 이름 수정 : 같은 이름 그대로 저장 가능, 형제와 겹치면 거절
        update(f, member, customer.id(), "회원 관리");
        var consult = area(f, customer.id(), "고객 상담");
        error(() -> update(f, consult, customer.id(), "회원 관리"), ErrorCode.SAVED_DATA_EXISTS);
        assertThat(update(f, consult, customer.id(), "고객 상담 센터").areaName()).isEqualTo("고객 상담 센터");
    }

    @Test
    @DisplayName("이동 : 다른 상위·최상위로 이동, 자기·하위를 상위로 거절, 하위 트리까지 3단계 초과 거절, 대상 상위 아래 이름 중복 거절, 다른 모델·프로젝트 상위 거절, 엔터티 소속 그대로")
    void moveRules() {
        GenerationFixture f = fixture("sa-move");
        var a = area(f, null, "A");
        var a1 = area(f, a.id(), "A1");
        var a2 = area(f, a1.id(), "A2");
        var b = area(f, null, "B");
        var b1 = area(f, b.id(), "B1");
        long entity = f.entity("주문");
        assign(f, a2.id(), one(entity, null));

        error(() -> update(f, a, a.id(), "A"), ErrorCode.INVALID_LOGICAL_MODEL); // 자기 자신
        error(() -> update(f, a, a2.id(), "A"), ErrorCode.INVALID_LOGICAL_MODEL); // 자기 하위
        error(() -> update(f, a1, b1.id(), "A1"), ErrorCode.INVALID_LOGICAL_MODEL); // A1(하위 1단계) 을 2단계 아래로 → 4단계
        assertThat(update(f, a1, b.id(), "A1").depth()).as("B 아래 2단계, 그 하위 A2 는 3단계").isEqualTo(2);
        assertThat(find(f, a2.id()).path()).containsExactly("B", "A1", "A2");
        assertThat(areaOf(entity)).as("영역을 옮겨도 엔터티의 소속 영역 ID 는 그대로").isEqualTo(a2.id());
        assertThat(find(f, b.id()).totalEntityCount()).as("상위 집계는 새 계층을 따른다").isOne();
        assertThat(find(f, a.id()).totalEntityCount()).isZero();
        assertThat(update(f, a1, null, "A1").depth()).as("최상위로").isEqualTo(1);
        var dup = area(f, b.id(), "A1");
        error(() -> update(f, dup, null, "A1"), ErrorCode.SAVED_DATA_EXISTS); // 최상위에 이미 A1
        // 다른 모델·다른 프로젝트의 상위 : 찾을 수 없음 (존재 여부를 알려 주지 않는다)
        GenerationFixture otherModel = f.otherModel("다른 모델-" + System.nanoTime());
        var foreign = areas.save(f.user, new Save(null, otherModel.modelId, null, "X", null, null, null));
        error(() -> update(f, b, foreign.id(), "B"), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        GenerationFixture otherProject = fixture("sa-move-other");
        var stranger = area(otherProject, null, "남의 영역");
        SecurityContextHolder.getContext().setAuthentication(null);
        SecurityTestSupport.loginAs(f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
        error(() -> update(f, b, stranger.id(), "B"), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> areas.save(f.user, new Save(stranger.id(), f.modelId, null, "탈취", null, null, LocalDateTime.now())), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        // 오래된 화면 : 기준값이 다르면 409
        var stale = find(f, b.id());
        update(f, b, null, "B 이름");
        error(() -> areas.save(f.user, new Save(b.id(), f.modelId, null, "B 덮어쓰기", null, null, stale.modifiedAt())), ErrorCode.MODEL_CONFLICT);
        assertThat(find(f, b.id()).areaName()).isEqualTo("B 이름");
    }

    // ------------------------------------------------------------------ 분류

    @Test
    @DisplayName("분류 : 지정·이동·해제, 상위 영역 직접 소속, 승인된 엔터티 분류(엔터티 수정 일시·승인 상태 그대로), 같은 영역 재지정은 변경 없음, 다른 모델·프로젝트 엔터티 거절")
    void classify() throws Exception {
        GenerationFixture f = fixture("sa-classify");
        var top = area(f, null, "판매");
        var child = area(f, top.id(), "주문");
        long order = f.entity("주문");
        long payment = f.entity("결제");
        long product = f.entity("상품");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = true where LGCL_ENTT_SN = ?", payment);
        LocalDateTime paymentModified = logical.selectEntity(f.user, payment).modifiedAt();

        Map<Long, Long> expected = new LinkedHashMap<>();
        expected.put(order, null);
        expected.put(payment, null);
        assertThat(assign(f, child.id(), expected)).isEqualTo(new LogicalSubjectAreaDto.AssignmentResult(2, 0));
        assign(f, top.id(), one(product, null)); // 상위 영역에 직접
        LogicalModelDto.Entity paymentNow = logical.selectEntity(f.user, payment);
        assertThat(paymentNow.isApproval()).as("승인 상태 그대로").isTrue();
        assertThat(paymentNow.modifiedAt()).as("엔터티 수정 일시 그대로 (엔터티 화면의 기준값이 바뀌지 않는다)").isEqualTo(paymentModified);
        assertThat(paymentNow.subjectAreaId()).isEqualTo(child.id());
        // 승인된 엔터티의 정의 수정은 여전히 거절
        error(() -> logical.saveEntity(f.user, new LogicalModelDto.Entity(payment, f.modelId, "결제 변경", null, null, null, null, paymentModified)),
                ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);

        var tree = areas.selectTree(f.user, f.modelId);
        assertThat(tree.entityCount()).isEqualTo(3);
        assertThat(tree.unclassifiedCount()).isZero();
        assertThat(find(f, top.id())).extracting(LogicalSubjectAreaDto.Area::directEntityCount, LogicalSubjectAreaDto.Area::totalEntityCount).containsExactly(1L, 3L);

        // 같은 영역 재지정 : 변경 없음 (소속 수정 일시·이력 그대로)
        int history = count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", f.user.getProjectId());
        LocalDateTime memberModified = jdbc.queryForObject("select MDFCN_DT from TB_LGCL_SBJ_AREA_ENTT where LGCL_ENTT_SN = ?", LocalDateTime.class, order);
        assertThat(assign(f, child.id(), one(order, child.id()))).isEqualTo(new LogicalSubjectAreaDto.AssignmentResult(0, 1));
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", f.user.getProjectId())).isEqualTo(history);
        assertThat(jdbc.queryForObject("select MDFCN_DT from TB_LGCL_SBJ_AREA_ENTT where LGCL_ENTT_SN = ?", LocalDateTime.class, order)).isEqualTo(memberModified);
        // 재지정이어도 지금 소속 검사는 한다
        error(() -> assign(f, child.id(), one(order, null)), ErrorCode.MODEL_CONFLICT);

        // 이동 → 해제
        assign(f, top.id(), one(order, child.id()));
        assertThat(areaOf(order)).isEqualTo(top.id());
        assign(f, null, one(order, top.id()));
        assertThat(areaOf(order)).isNull();
        assertThat(areas.selectTree(f.user, f.modelId).unclassifiedCount()).isOne();

        // 잘못된 요청 : 대상·지금 소속 누락(필드 없음 ≠ null), 같은 엔터티 두 번, 다른 모델·프로젝트 엔터티, 다른 모델 영역
        error(() -> areas.assign(f.user, f.modelId, new Assignment(null, List.of(new AssignmentItem(order, JSON.nullNode())))), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> areas.assign(f.user, f.modelId, new Assignment(node(top.id()), List.of(new AssignmentItem(order, null)))), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> areas.assign(f.user, f.modelId, new Assignment(node(top.id()), List.of(new AssignmentItem(order, JSON.stringNode("1"))))), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> areas.assign(f.user, f.modelId, new Assignment(node(top.id()), List.of(new AssignmentItem(order, JSON.nullNode()), new AssignmentItem(order, JSON.nullNode())))),
                ErrorCode.INVALID_LOGICAL_MODEL);
        GenerationFixture other = f.otherModel("다른 모델-" + System.nanoTime());
        long foreignEntity = other.entity("남의 엔터티");
        error(() -> assign(f, top.id(), one(foreignEntity, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        var foreignArea = areas.save(f.user, new Save(null, other.modelId, null, "남의 영역", null, null, null));
        error(() -> assign(f, foreignArea.id(), one(order, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        GenerationFixture otherProject = fixture("sa-classify-other");
        long strangerEntity = otherProject.entity("남의 프로젝트 엔터티");
        SecurityTestSupport.loginAs(f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
        error(() -> assign(f, top.id(), one(strangerEntity, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(areaOf(strangerEntity)).isNull();

        // HTTP 본문 : 필드 없음은 Java null, 명시적 null 은 NullNode — 서로 다르게 다룬다
        Assignment missing = objectMapper.readValue("{\"entities\":[{\"entityId\":1}]}", Assignment.class);
        Assignment explicit = objectMapper.readValue("{\"subjectAreaId\":null,\"entities\":[{\"entityId\":1,\"expectedSubjectAreaId\":null}]}", Assignment.class);
        assertThat(missing.subjectAreaId()).isNull();
        assertThat(missing.entities().getFirst().expectedSubjectAreaId()).isNull();
        assertThat(explicit.subjectAreaId() != null && explicit.subjectAreaId().isNull()).isTrue();
        assertThat(explicit.entities().getFirst().expectedSubjectAreaId() != null && explicit.entities().getFirst().expectedSubjectAreaId().isNull()).isTrue();
    }

    @Test
    @DisplayName("원자성 : 일괄 분류 중 하나가 오래된 소속(409)·없는 엔터티면 아무것도 바뀌지 않는다. 새 엔터티 등록과 처음 소속은 한 트랜잭션 (영역이 잘못되면 엔터티도 없다)")
    void atomicity() {
        GenerationFixture f = fixture("sa-atomic");
        var area = area(f, null, "영역");
        var other = area(f, null, "다른 영역");
        long a = f.entity("가");
        long b = f.entity("나");
        long c = f.entity("다");
        assign(f, other.id(), one(b, null));
        Map<Long, Long> batch = new LinkedHashMap<>();
        batch.put(a, null);
        batch.put(b, null); // 실제는 other
        batch.put(c, null);
        int history = count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", f.user.getProjectId());
        assertThatThrownBy(() -> assign(f, area.id(), batch)).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_CONFLICT);
            assertThat(e.getCustomMessage()).contains("나");
        });
        assertThat(areaOf(a)).isNull();
        assertThat(areaOf(b)).isEqualTo(other.id());
        assertThat(areaOf(c)).isNull();
        Map<Long, Long> withMissing = new LinkedHashMap<>();
        withMissing.put(a, null);
        withMissing.put(987_654_321L, null);
        error(() -> assign(f, area.id(), withMissing), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(areaOf(a)).isNull();
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", f.user.getProjectId())).as("롤백된 요청은 이력이 없다").isEqualTo(history);

        // 등록과 처음 소속
        LogicalModelDto.Entity created = logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, "라", null, null, null, null, null, area.id()));
        assertThat(created.subjectAreaId()).isEqualTo(area.id());
        assertThat(areaOf(created.id())).isEqualTo(area.id());
        GenerationFixture otherModel = f.otherModel("다른 모델-" + System.nanoTime());
        var foreign = areas.save(f.user, new Save(null, otherModel.modelId, null, "남의 영역", null, null, null));
        int entities = count("select count(*) from TB_LGCL_ENTT where LGCL_MDL_SN = ?", f.modelId);
        error(() -> logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, "마", null, null, null, null, null, foreign.id())), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(count("select count(*) from TB_LGCL_ENTT where LGCL_MDL_SN = ?", f.modelId)).as("영역 지정이 실패하면 엔터티 등록도 없다").isEqualTo(entities);
        // 수정 요청의 subjectAreaId 는 보지 않는다 (관리자가 등록해 승인된 엔터티 : 정의를 고치려면 승인 취소가 먼저다)
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", created.id());
        LogicalModelDto.Entity renamed = logical.saveEntity(f.user, new LogicalModelDto.Entity(created.id(), f.modelId, "라 수정", null, null, null, null, created.modifiedAt(), other.id()));
        assertThat(renamed.subjectAreaId()).isEqualTo(area.id());
    }

    // ------------------------------------------------------------------ 삭제·무결성

    @Test
    @DisplayName("삭제 : 빈 영역만 (소속 엔터티·하위 영역이 있으면 거절, 기준값 다르면 409), 영역 배치만 정리. 엔터티 삭제는 기존 제약 그대로이고 삭제되면 소속도 지운다. 모델 삭제는 영역·배치를 함께")
    void deletion() {
        GenerationFixture f = fixture("sa-delete");
        var parent = area(f, null, "상위");
        var child = area(f, parent.id(), "하위");
        long entity = f.entity("엔터티");
        long other = f.entity("다른 엔터티");
        assign(f, child.id(), one(entity, null));
        assign(f, child.id(), one(other, null));
        f.rel("관계", entity, other, false, 0);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_MDL_SN = ?", f.modelId); // 관리자가 등록해 승인된 엔터티는 삭제 전에 승인 취소
        areas.saveLayout(f.user, f.modelId, child.id(), new LogicalModelDto.Layout(null, Map.of(entity, new LogicalModelDto.Point(1, 2)), new LogicalModelDto.Viewport(0, 0, 1)));
        logical.saveLayout(f.user, f.modelId, new LogicalModelDto.Layout(null, Map.of(entity, new LogicalModelDto.Point(5, 5)), new LogicalModelDto.Viewport(0, 0, 1)));

        error(() -> areas.delete(f.user, parent.id(), find(f, parent.id()).modifiedAt()), ErrorCode.RELATION_EXISTS);
        error(() -> areas.delete(f.user, child.id(), find(f, child.id()).modifiedAt()), ErrorCode.RELATION_EXISTS);
        // 관계가 있는 엔터티는 지울 수 없다 (기존 제약) : 소속도 그대로
        error(() -> logical.deleteEntity(f.user, entity), ErrorCode.RELATION_EXISTS);
        assertThat(areaOf(entity)).isEqualTo(child.id());
        // 지워지는 엔터티는 소속도 함께
        logical.deleteRelationship(f.user, logical.selectEntityRelationships(f.user, entity).getFirst().id());
        logical.deleteEntity(f.user, entity);
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA_ENTT where LGCL_ENTT_SN = ?", entity)).isZero();
        assign(f, null, one(other, child.id()));
        LocalDateTime stale = find(f, child.id()).modifiedAt().minusSeconds(1);
        error(() -> areas.delete(f.user, child.id(), stale), ErrorCode.MODEL_CONFLICT);
        areas.delete(f.user, child.id(), find(f, child.id()).modifiedAt());
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA_LYT where LGCL_SBJ_AREA_SN = ?", child.id())).as("영역 배치만 지운다").isZero();
        assertThat(count("select count(*) from TB_LGCL_ERD_LYT where LGCL_MDL_SN = ?", f.modelId)).as("모델 전체 배치는 그대로").isOne();
        assertThat(count("select count(*) from TB_LGCL_ENTT where LGCL_ENTT_SN = ?", other)).as("엔터티는 지우지 않는다").isOne();

        // 모델 삭제 : 엔터티가 있으면 그대로 거절, 없으면 영역(계층)·영역 배치까지
        var deep = area(f, parent.id(), "하위 2");
        area(f, deep.id(), "하위 3");
        areas.saveLayout(f.user, f.modelId, deep.id(), new LogicalModelDto.Layout(null, Map.of(), new LogicalModelDto.Viewport(0, 0, 1)));
        error(() -> logical.deleteModel(f.user, f.modelId), ErrorCode.RELATION_EXISTS);
        logical.deleteEntity(f.user, other);
        logical.deleteModel(f.user, f.modelId);
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA where LGCL_MDL_SN = ?", f.modelId)).isZero();
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA_LYT where LGCL_MDL_SN = ?", f.modelId)).isZero();
    }

    // ------------------------------------------------------------------ 목록·ERD 범위

    @Test
    @DisplayName("범위 : 엔터티 목록(하위 포함 기본·직접만·미분류·검색·정렬·페이징·소속 영역), 관계 목록(한쪽 이상), ERD 범위·엔터티 찾기, 이웃 탐색은 모델 전체, 잘못된 조건 거절")
    void scopes() {
        GenerationFixture f = fixture("sa-scope");
        var member = area(f, null, "회원");
        var order = area(f, null, "주문");
        var detail = area(f, order.id(), "주문 상세");
        long customer = f.entity("회원");
        long orderEntity = f.entity("주문");
        long item = f.entity("주문 상세");
        long loose = f.entity("미분류 엔터티");
        assign(f, member.id(), one(customer, null));
        assign(f, order.id(), one(orderEntity, null));
        assign(f, detail.id(), one(item, null));
        var r1 = f.rel("회원 주문", customer, orderEntity, false, 0);
        var r2 = f.rel("주문 상세", orderEntity, item, false, 0);
        f.rel("회원 자기", customer, customer, false, 0);

        assertThat(ids(logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null))).hasSize(4); // 예전 호출 그대로
        assertThat(ids(logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null, order.id(), null, null))).containsExactly(orderEntity, item);
        assertThat(ids(logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null, order.id(), null, false))).containsExactly(orderEntity);
        assertThat(ids(logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null, null, true, null))).containsExactly(loose);
        assertThat(ids(logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null, null, false, null))).as("unclassified=false 는 조건 없음").hasSize(4);
        assertThat(ids(logical.selectEntityRows(f.user, f.modelId, "상세", 0, 100, null, order.id(), null, null))).containsExactly(item);
        assertThat(ids(logical.selectEntityRows(f.user, f.modelId, null, 1, 1, "entityName,desc", order.id(), null, null))).containsExactly(orderEntity);
        assertThat(logical.selectEntityRows(f.user, f.modelId, null, 0, 1, null, order.id(), null, null).getTotalElements()).isEqualTo(2);
        assertThat(logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null).getContent()).extracting(LogicalModelDto.EntityRow::id, LogicalModelDto.EntityRow::subjectAreaId)
                .contains(tuple(item, detail.id()), tuple(loose, null));
        error(() -> logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null, order.id(), true, null), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null, 987_654_321L, null, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> logical.selectEntityRows(f.user, f.modelId, null, 0, 101, null, order.id(), null, null), ErrorCode.INVALID_PAGE_REQUEST);
        error(() -> logical.selectEntityRows(f.user, f.modelId, null, 0, 10, "entityName;drop", order.id(), null, null), ErrorCode.INVALID_PAGE_REQUEST);
        GenerationFixture otherModel = f.otherModel("다른 모델-" + System.nanoTime());
        var foreign = areas.save(f.user, new Save(null, otherModel.modelId, null, "남의 영역", null, null, null));
        error(() -> logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null, foreign.id(), null, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);

        // 관계 : 양 끝 중 하나라도 범위 안 (자기 참조 한 번)
        var relations = logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 100, null, order.id(), null, false);
        assertThat(relations.getContent()).extracting(LogicalModelDto.Relationship::id).containsExactlyInAnyOrder(r1.id(), r2.id());
        assertThat(relations.getContent()).filteredOn(r -> r.id().equals(r1.id())).extracting(LogicalModelDto.Relationship::sourceSubjectAreaId,
                LogicalModelDto.Relationship::targetSubjectAreaId).containsExactly(tuple(member.id(), order.id()));
        assertThat(logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 100, null, member.id(), null, null).getTotalElements()).isEqualTo(2);
        assertThat(logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 100, null, null, true, null).getTotalElements()).isZero();
        assertThat(logical.selectRelationshipRows(f.user, f.modelId, "회원", null, 0, 100, null, order.id(), null, null).getTotalElements()).isOne();
        assertThat(logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 100, null).getTotalElements()).as("예전 호출 그대로").isEqualTo(3);

        // ERD 범위 : 범위 안 엔터티만, 범위 밖 관계는 수로
        LogicalModelDto.GraphPart part = logical.selectGraphPart(f.user, f.modelId, null, 0, 100, order.id(), null, null);
        assertThat(part.entities()).extracting(LogicalModelDto.GraphEntity::id).containsExactly(orderEntity, item);
        assertThat(part.relationships()).extracting(LogicalModelDto.Relationship::id).containsExactly(r2.id());
        assertThat(part.outsideRelationships()).containsEntry(orderEntity, 1);
        assertThat(part.totalCount()).isEqualTo(2);
        assertThat(part.modelEntityCount()).isEqualTo(4);
        assertThat(logical.selectGraphPart(f.user, f.modelId, null, 0, 1, order.id(), null, null).truncated()).isTrue();
        error(() -> logical.selectGraphPart(f.user, f.modelId, null, 0, 301, order.id(), null, null), ErrorCode.INVALID_LOGICAL_MODEL);
        assertThat(logical.selectGraphPart(f.user, f.modelId, null, 0, 100, null, true, null).entities()).extracting(LogicalModelDto.GraphEntity::id).containsExactly(loose);
        // 엔터티 찾기 : 범위 안, 최대 50 유지
        assertThat(logical.searchEntities(f.user, f.modelId, "주문", 20, member.id(), null, null).totalCount()).isZero();
        assertThat(logical.searchEntities(f.user, f.modelId, "주문", 20, order.id(), null, null).items()).extracting(LogicalModelDto.EntityHit::id).containsExactly(orderEntity, item);
        error(() -> logical.searchEntities(f.user, f.modelId, null, 51, order.id(), null, null), ErrorCode.INVALID_LOGICAL_MODEL);
        // 이웃 탐색 : 영역과 관계없이 모델 전체
        assertThat(logical.selectNeighbors(f.user, f.modelId, item, 2).entities()).extracting(LogicalModelDto.GraphEntity::id).contains(customer, orderEntity, item);
    }

    // ------------------------------------------------------------------ 독립 배치

    @Test
    @DisplayName("독립 배치 : 모델 전체 저장 → 영역 A 최초 조회(모델 좌표 참고, 저장 없음) → A 저장 → 모델 전체 DB 값 그대로 → B 저장 → A 그대로. 영역별·사용자별 충돌 범위 분리, 미표시 좌표 보존")
    void independentLayouts() {
        GenerationFixture f = fixture("sa-layout");
        var a = area(f, null, "A");
        var a1 = area(f, a.id(), "A1");
        var b = area(f, null, "B");
        long e1 = f.entity("가");
        long e2 = f.entity("나");
        long e3 = f.entity("다");
        assign(f, a.id(), one(e1, null));
        assign(f, a1.id(), one(e2, null));
        assign(f, b.id(), one(e3, null));
        var view = new LogicalModelDto.Viewport(10, 20, 1);

        // 1) 모델 전체
        logical.saveLayout(f.user, f.modelId, new LogicalModelDto.Layout(null, Map.of(e1, new LogicalModelDto.Point(100, 100), e2, new LogicalModelDto.Point(200, 200),
                e3, new LogicalModelDto.Point(300, 300)), view));
        String modelJson = jdbc.queryForObject("select NODE_PSTN_CN from TB_LGCL_ERD_LYT where LGCL_MDL_SN = ?", String.class, f.modelId);
        // 2~3) 영역 A 최초 : 모델 좌표를 참고로, 아무것도 저장하지 않는다
        var first = areas.selectLayout(f.user, f.modelId, a.id());
        assertThat(first.source()).isEqualTo("MODEL");
        assertThat(first.version()).isNull();
        assertThat(first.nodes()).containsKeys(e1, e2);
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA_LYT where LGCL_MDL_SN = ?", f.modelId)).isZero();
        // 4~5) 자동 배치한 좌표를 A 에만 저장
        var savedA = areas.saveLayout(f.user, f.modelId, a.id(), new LogicalModelDto.Layout(null, Map.of(e1, new LogicalModelDto.Point(1, 1), e2, new LogicalModelDto.Point(2, 2)), view));
        assertThat(savedA.source()).isEqualTo("SUBJECT_AREA");
        // 6~7) 모델 전체 배치 DB 값 그대로
        assertThat(jdbc.queryForObject("select NODE_PSTN_CN from TB_LGCL_ERD_LYT where LGCL_MDL_SN = ?", String.class, f.modelId)).isEqualTo(modelJson);
        assertThat(logical.selectLayout(f.user, f.modelId).nodes().get(e1)).isEqualTo(new LogicalModelDto.Point(100, 100));
        // 8~10) B 저장, A 그대로 (다른 영역 저장은 A 의 버전을 바꾸지 않는다 → A 저장에 충돌 없음)
        areas.saveLayout(f.user, f.modelId, b.id(), new LogicalModelDto.Layout(null, Map.of(e3, new LogicalModelDto.Point(9, 9)), view));
        var againA = areas.selectLayout(f.user, f.modelId, a.id());
        assertThat(againA.nodes()).containsEntry(e1, new LogicalModelDto.Point(1, 1)).containsEntry(e2, new LogicalModelDto.Point(2, 2)).doesNotContainKey(e3);
        assertThat(againA.version()).isEqualTo(savedA.version());

        // 상위(A)·하위(A1) 독립 : A1 저장은 A 를 바꾸지 않는다
        areas.saveLayout(f.user, f.modelId, a1.id(), new LogicalModelDto.Layout(null, Map.of(e2, new LogicalModelDto.Point(50, 50)), view));
        assertThat(areas.selectLayout(f.user, f.modelId, a.id()).nodes()).containsEntry(e2, new LogicalModelDto.Point(2, 2));
        // 미표시 좌표 보존 : 직접 소속만 보며 e1 만 저장해도 e2 좌표는 남는다
        var merged = areas.saveLayout(f.user, f.modelId, a.id(), new LogicalModelDto.Layout(againA.version(), Map.of(e1, new LogicalModelDto.Point(7, 7)), view));
        assertThat(merged.nodes()).containsEntry(e1, new LogicalModelDto.Point(7, 7)).containsEntry(e2, new LogicalModelDto.Point(2, 2));
        // 같은 영역 오래된 버전 → 409
        error(() -> areas.saveLayout(f.user, f.modelId, a.id(), new LogicalModelDto.Layout(againA.version(), Map.of(e1, new LogicalModelDto.Point(8, 8)), view)), ErrorCode.ERD_LAYOUT_CONFLICT);
        // 다른 모델의 엔터티 좌표 거절, 다른 모델의 영역 거절
        GenerationFixture otherModel = f.otherModel("다른 모델-" + System.nanoTime());
        long foreign = otherModel.entity("남의 엔터티");
        error(() -> areas.saveLayout(f.user, f.modelId, b.id(), new LogicalModelDto.Layout(areas.selectLayout(f.user, f.modelId, b.id()).version(),
                Map.of(foreign, new LogicalModelDto.Point(1, 1)), view)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> areas.selectLayout(f.user, otherModel.modelId, a.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        // 사용자별 : 다른 사용자는 자기 배치만 (A 는 처음 상태)
        AccountDto viewer = SecurityTestSupport.loginAs(f.user.getProjectId(), "987654", "ROLE_VIEWER");
        var mine = areaController.selectLayout(viewer, f.modelId, a.id()).getBody();
        assertThat(mine.source()).isEqualTo("NONE");
        areaController.saveLayout(viewer, f.modelId, a.id(), new LogicalModelDto.Layout(null, Map.of(e1, new LogicalModelDto.Point(-1, -1)), view));
        SecurityTestSupport.loginAs(f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
        assertThat(areas.selectLayout(f.user, f.modelId, a.id()).nodes()).containsEntry(e1, new LogicalModelDto.Point(7, 7));
        // 분류 변경은 배치를 바꾸지 않는다 (전체·영역 모두)
        assign(f, b.id(), one(e1, a.id()));
        assertThat(jdbc.queryForObject("select NODE_PSTN_CN from TB_LGCL_ERD_LYT where LGCL_MDL_SN = ?", String.class, f.modelId)).isEqualTo(modelJson);
        assertThat(areas.selectLayout(f.user, f.modelId, a.id()).nodes()).containsKey(e1);
    }

    // ------------------------------------------------------------------ 이력·버전

    @Test
    @DisplayName("변경 이력 : 영역 등록·수정·이동·삭제, 소속 지정 CREATE·이동 UPDATE·해제 DELETE 가 엔터티 이력과 따로 남는다 (분류 변경에 LOGICAL_ENTITY 이력 없음). 모델 버전에 영역·소속이 담기고 비교된다")
    void historyAndVersions() {
        GenerationFixture f = fixture("sa-history");
        AccountDto member = f.member();
        var a = area(f, null, "A");
        var b = area(f, null, "B");
        long entity = f.entity("엔터티");
        ModelVersionDto.Summary before = versions.create(member, new ModelVersionDto.Create("sa-before-" + System.nanoTime(), null));
        Long project = f.user.getProjectId();
        int entityHistory = count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'LOGICAL_ENTITY'", project);

        assign(f, a.id(), one(entity, null));
        assign(f, b.id(), one(entity, a.id()));
        assign(f, null, one(entity, b.id()));
        assign(f, a.id(), one(entity, null));
        assertThat(jdbc.queryForList("select CHG_TYPE_CD from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'SUBJECT_AREA_ENTITY' order by MDL_CHG_HSTRY_SN", String.class, project))
                .containsExactly("CREATE", "UPDATE", "DELETE", "CREATE");
        assertThat(jdbc.queryForList("select TRGT_NM from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'SUBJECT_AREA_ENTITY' and CHG_TYPE_CD = 'UPDATE'", String.class, project))
                .containsExactly("엔터티 ∈ B");
        String update = jdbc.queryForObject("select CHG_BFR_CN || ' → ' || CHG_AFTR_CN from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'SUBJECT_AREA_ENTITY' and CHG_TYPE_CD = 'UPDATE'",
                String.class, project);
        assertThat(update).contains("\"subjectAreaName\":\"A\"", "\"subjectAreaName\":\"B\"");
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'LOGICAL_ENTITY'", project)).as("분류는 엔터티 이력이 아니다").isEqualTo(entityHistory);

        update(f, b, a.id(), "B 이름");
        areas.delete(f.user, b.id(), find(f, b.id()).modifiedAt());
        assertThat(jdbc.queryForList("select CHG_TYPE_CD from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'LOGICAL_SUBJECT_AREA' and TRGT_SN = ? order by MDL_CHG_HSTRY_SN",
                String.class, project, b.id())).containsExactly("CREATE", "UPDATE", "DELETE");
        String move = jdbc.queryForObject("select CHG_AFTR_CN from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'LOGICAL_SUBJECT_AREA' and TRGT_SN = ? and CHG_TYPE_CD = 'UPDATE'",
                String.class, project, b.id());
        assertThat(move).contains("\"parentId\":" + a.id(), "\"parentName\":\"A\"", "\"areaName\":\"B 이름\"");

        // 버전 : 형식 7, 영역·소속 종류, 비교
        ModelVersionDto.Summary after = versions.create(member, new ModelVersionDto.Create("sa-after-" + System.nanoTime(), null));
        assertThat(after.formatVersion()).isEqualTo(8); // 형식 8 (M-6 후속 5차 서브타입) 에도 영역·소속은 그대로
        assertThat(after.counts().get("LOGICAL_SUBJECT_AREA").asInt()).isOne();
        assertThat(after.counts().get("SUBJECT_AREA_ENTITY").asInt()).isOne();
        var comparison = versions.compare(member, before.id(), after.id());
        assertThat(comparison.changes()).extracting(ModelVersionDto.Change::targetType, ModelVersionDto.Change::change)
                .contains(tuple("SUBJECT_AREA_ENTITY", "ADDED"), tuple("LOGICAL_SUBJECT_AREA", "REMOVED"));
        update(f, a, null, "A 새 이름");
        assertThat(versions.compare(member, after.id(), null).changes()).extracting(ModelVersionDto.Change::targetType, ModelVersionDto.Change::change)
                .containsExactly(tuple("LOGICAL_SUBJECT_AREA", "MODIFIED"));
    }

    // ------------------------------------------------------------------ 교환 형식 4

    @Test
    @DisplayName("교환 형식 4 : 계층(같은 이름의 다른 계층 포함)·소속·영역 배치를 새 ID 로 옮기고 왕복해도 같다. 형식 3 파일은 영역 없이 가져오고, 잘못된 계층·소속은 파일 전체 거절")
    void exchangeFormat4() {
        GenerationFixture f = fixture("sa-export");
        var customer = area(f, null, "고객");
        var basic1 = area(f, customer.id(), "기본 정보");
        var deep = area(f, basic1.id(), "세부");
        var sales = area(f, null, "판매");
        var basic2 = area(f, sales.id(), "기본 정보");
        long e1 = f.entity("회원");
        long e2 = f.entity("주문");
        f.entity("미분류");
        assign(f, deep.id(), one(e1, null));
        assign(f, basic2.id(), one(e2, null));
        AccountDto source = f.member();
        areas.saveLayout(source, f.modelId, basic2.id(), new LogicalModelDto.Layout(null, Map.of(e2, new LogicalModelDto.Point(11, 22)), new LogicalModelDto.Viewport(1, 2, 1)));
        String modelName = jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, f.modelId);

        ModelExchangeDto.Document document = exchange.export(source);
        assertThat(document.formatVersion()).isEqualTo(5); // 형식 5 (M-6 후속 5차) 에도 같다
        assertThat(document.model().get("LOGICAL_SUBJECT_AREA")).hasSize(5);
        assertThat(document.model().get("SUBJECT_AREA_ENTITY")).hasSize(2);
        assertThat(document.layouts().subjectAreas()).hasSize(1);

        GenerationFixture targetFixture = fixture("sa-import");
        AccountDto target = targetFixture.member();
        Long targetProject = target.getProjectId();
        ModelExchangeDto.Selection selection = new ModelExchangeDto.Selection(List.of(), List.of(modelName), List.of());
        var plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(targetProject, document, selection));
        assertThat(plan.counts()).containsEntry("LOGICAL_SUBJECT_AREA", 5);
        var result = exchange.apply(target, new ModelExchangeDto.ApplyRequest(targetProject, document, selection, plan.planToken(), plan.requestId()));
        assertThat(result.created()).containsEntry("LOGICAL_SUBJECT_AREA", 5).containsEntry("SUBJECT_AREA_ENTITY", 2);
        Long imported = jdbc.queryForObject("select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = ? and MDL_NM = ?", Long.class, targetProject, modelName);
        assertThat(paths(target, imported)).isEqualTo(paths(source, f.modelId));
        assertThat(jdbc.queryForList("select e.ENTT_NM || ':' || a.SBJ_AREA_NM from TB_LGCL_SBJ_AREA_ENTT m join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.LGCL_ENTT_SN"
                + " join TB_LGCL_SBJ_AREA a on a.LGCL_SBJ_AREA_SN = m.LGCL_SBJ_AREA_SN where m.PRJ_SN = ? and a.PRJ_SN = ? and e.PRJ_SN = ? order by e.ENTT_NM", String.class,
                targetProject, targetProject, targetProject)).containsExactly("주문:기본 정보", "회원:세부");
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA_LYT where PRJ_SN = ? and ACNT_SN = ?", targetProject, Long.valueOf(target.getId()))).isOne();
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA where PRJ_SN = ? and LGCL_SBJ_AREA_SN in (?, ?, ?, ?, ?)", targetProject,
                customer.id(), basic1.id(), deep.id(), sales.id(), basic2.id())).as("원본 ID 를 쓰지 않는다").isZero();
        // 왕복 : 다시 내보내면 계층·소속이 같다 (ID·일시 제외)
        ModelExchangeDto.Document again = exchange.export(target);
        assertThat(logicalShape(again, modelName)).isEqualTo(logicalShape(document, modelName));

        // 형식 3 파일 : 영역 없이 가져온다 (기본 영역을 만들지 않는다)
        GenerationFixture oldFixture = fixture("sa-import-old");
        AccountDto oldTarget = oldFixture.member();
        ModelExchangeDto.Document format3 = new ModelExchangeDto.Document(document.format(), 3, document.exportedAt(), document.dbType(), document.counts(),
                withoutAreas(document), null);
        var oldPlan = exchange.plan(oldTarget, new ModelExchangeDto.PlanRequest(oldTarget.getProjectId(), format3, selection));
        exchange.apply(oldTarget, new ModelExchangeDto.ApplyRequest(oldTarget.getProjectId(), format3, selection, oldPlan.planToken(), oldPlan.requestId()));
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA where PRJ_SN = ?", oldTarget.getProjectId())).isZero();
        assertThat(count("select count(*) from TB_LGCL_ENTT where PRJ_SN = ?", oldTarget.getProjectId())).isEqualTo(3);

        // 거절 : 형식 3 에 영역, 순환, 4단계, 같은 상위 이름 중복, 없는 상위, 중복 소속, 다른 모델 소속
        AccountDto clean = fixture("sa-import-bad").member();
        Long cleanProject = clean.getProjectId();
        Consumer<ModelExchangeDto.Document> rejected = bad -> assertThatThrownBy(() -> exchange.plan(clean, new ModelExchangeDto.PlanRequest(cleanProject, bad, selection)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID));
        rejected.accept(new ModelExchangeDto.Document(document.format(), 3, document.exportedAt(), document.dbType(), document.counts(), document.model(), null));
        rejected.accept(withArea(document, basic1.id(), "parentId", deep.id())); // 순환
        rejected.accept(withArea(document, basic1.id(), "parentId", basic1.id())); // 자기 자신
        rejected.accept(withArea(document, sales.id(), "parentId", deep.id())); // 판매(+하위) 를 3단계 아래로 → 4·5단계
        rejected.accept(withArea(document, sales.id(), "areaName", "고객")); // 최상위 이름 중복
        rejected.accept(withArea(document, basic2.id(), "parentId", 999_999L)); // 없는 상위
        rejected.accept(withMembers(document, List.of(member(1L, deep.id(), e1), member(2L, basic2.id(), e1)))); // 중복 소속
        rejected.accept(withMembers(document, List.of(member(1L, 999_999L, e1)))); // 없는 영역
        assertThat(count("select count(*) from TB_LGCL_MDL where PRJ_SN = ?", cleanProject)).isOne(); // fixture 모델만
    }

    /** 모델의 영역 경로 집합 */
    private List<String> paths(AccountDto user, Long modelId) {
        SecurityTestSupport.loginAs(user.getProjectId(), user.getId(), "ROLE_ADMIN");
        return areas.selectTree(user, modelId).areas().stream().map(a -> String.join(" / ", a.path()) + "#" + a.directEntityCount()).sorted().toList();
    }

    /** 파일의 논리 구조 (ID·일시 없이) : 영역 경로, 엔터티 → 영역 경로 */
    private static List<String> logicalShape(ModelExchangeDto.Document document, String modelName) {
        Map<Long, ModelExchangeDto.Item> byId = document.model().get("LOGICAL_SUBJECT_AREA").stream().collect(Collectors.toMap(ModelExchangeDto.Item::id, i -> i));
        java.util.function.Function<Long, String> path = id -> {
            List<String> names = new ArrayList<>();
            for (ModelExchangeDto.Item item = byId.get(id); item != null; item = byId.get(item.state().get("parentId") == null ? null : ((Number) item.state().get("parentId")).longValue())) {
                names.addFirst((String) item.state().get("areaName"));
            }
            return String.join("/", names);
        };
        List<String> shape = new ArrayList<>();
        byId.keySet().forEach(id -> shape.add("AREA " + path.apply(id)));
        for (ModelExchangeDto.Item m : document.model().get("SUBJECT_AREA_ENTITY")) {
            shape.add("MEMBER " + m.state().get("entityName") + " → " + path.apply(((Number) m.state().get("subjectAreaId")).longValue()));
        }
        shape.add("MODEL " + modelName);
        return shape.stream().sorted().toList();
    }

    private static Map<String, List<ModelExchangeDto.Item>> withoutAreas(ModelExchangeDto.Document document) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        model.remove("LOGICAL_SUBJECT_AREA");
        model.remove("SUBJECT_AREA_ENTITY");
        return model;
    }

    private static ModelExchangeDto.Document withArea(ModelExchangeDto.Document document, Long areaId, String field, Object value) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        model.put("LOGICAL_SUBJECT_AREA", model.get("LOGICAL_SUBJECT_AREA").stream().map(item -> {
            if (!item.id().equals(areaId)) return item;
            Map<String, Object> state = new LinkedHashMap<>(item.state());
            state.put(field, value);
            return new ModelExchangeDto.Item(item.id(), item.name(), state);
        }).toList());
        return new ModelExchangeDto.Document(document.format(), document.formatVersion(), document.exportedAt(), document.dbType(), document.counts(), model, null);
    }

    private static ModelExchangeDto.Document withMembers(ModelExchangeDto.Document document, List<ModelExchangeDto.Item> members) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        model.put("SUBJECT_AREA_ENTITY", members);
        return new ModelExchangeDto.Document(document.format(), document.formatVersion(), document.exportedAt(), document.dbType(), document.counts(), model, null);
    }

    private static ModelExchangeDto.Item member(Long id, Long areaId, Long entityId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("subjectAreaId", areaId);
        state.put("entityId", entityId);
        return new ModelExchangeDto.Item(id, null, state);
    }

    // ------------------------------------------------------------------ 동시성 (실제 독립 트랜잭션)

    /** 첫 트랜잭션이 모델 행을 잠근 채(작업을 마치고 커밋 전) 둘째 요청을 시작한다 : 둘째는 잠금을 기다렸다가 첫째가 커밋한 상태에서 검사한다 */
    private String raced(Runnable first, Runnable second) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(login);
            try {
                tx.executeWithoutResult(status -> {
                    first.run();
                    firstDone.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.setContext(login);
            try {
                second.run();
                return "OK";
            } catch (CustomException e) {
                return e.getErrorCode().name();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        Thread.sleep(300);
        assertThat(waiter).as("둘째 요청은 모델 행 잠금을 기다린다").isNotDone();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        return waiter.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("동시성 : 같은 이름 동시 등록(최상위·같은 상위), 같은 엔터티 동시 분류, 영역 삭제 ↔ 분류, 상위 이동 ↔ 하위 등록(깊이), 서로를 상위로 동시 이동(순환) — 모두 하나만 성공")
    void concurrency() throws Exception {
        GenerationFixture f = fixture("sa-race");
        var parent = area(f, null, "상위");
        assertThat(raced(() -> area(f, null, "동시"), () -> area(f, null, "동시"))).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(raced(() -> area(f, parent.id(), "하위"), () -> area(f, parent.id(), "하위".toUpperCase()))).isEqualTo("SAVED_DATA_EXISTS");
        // 스레드 여럿이 동시에 같은 이름 : 하나만
        List<String> names = LogicalModelIntegrationTest.concurrently(4, i -> area(f, null, "여럿").id().toString());
        assertThat(names).filteredOn("SAVED_DATA_EXISTS"::equals).hasSize(3);
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA where LGCL_MDL_SN = ? and SBJ_AREA_NM = '여럿'", f.modelId)).isOne();

        // 같은 엔터티를 같은 화면 상태(미분류)에서 동시에 두 영역으로 : 하나만, 다른 하나는 409
        var x = area(f, null, "X");
        var y = area(f, null, "Y");
        long entity = f.entity("엔터티");
        assertThat(raced(() -> assign(f, x.id(), one(entity, null)), () -> assign(f, y.id(), one(entity, null)))).isEqualTo("MODEL_CONFLICT");
        assertThat(areaOf(entity)).isEqualTo(x.id());
        // 영역 삭제 ↔ 그 영역으로 분류 : 삭제가 먼저면 분류는 찾을 수 없음
        var doomed = area(f, null, "삭제될 영역");
        long other = f.entity("다른 엔터티");
        LocalDateTime base = find(f, doomed.id()).modifiedAt();
        assertThat(raced(() -> areas.delete(f.user, doomed.id(), base), () -> assign(f, doomed.id(), one(other, null)))).isEqualTo("LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(areaOf(other)).isNull();
        // 분류가 먼저면 삭제는 거절
        var kept = area(f, null, "남을 영역");
        LocalDateTime keptBase = find(f, kept.id()).modifiedAt();
        assertThat(raced(() -> assign(f, kept.id(), one(other, null)), () -> areas.delete(f.user, kept.id(), keptBase))).isEqualTo("RELATION_EXISTS");
        // 상위 이동 ↔ 하위 등록 : A(1단계) 아래 A1 을 만드는 동안 A 를 B1(2단계) 아래로 옮기면 A1 은 4단계 → 둘째가 거절
        var a = area(f, null, "A");
        var b = area(f, null, "B");
        var b1 = area(f, b.id(), "B1");
        var a1 = new LogicalSubjectAreaDto.Area[1];
        assertThat(raced(() -> a1[0] = area(f, a.id(), "A1"), () -> update(f, a, b1.id(), "A"))).isEqualTo("INVALID_LOGICAL_MODEL");
        assertThat(find(f, a.id()).parentId()).isNull();
        var a2 = area(f, null, "A2");
        assertThat(raced(() -> update(f, a2, b1.id(), "A2"), () -> area(f, a2.id(), "A3"))).isEqualTo("INVALID_LOGICAL_MODEL");
        // 서로를 상위로 동시에 : 순환이 생기지 않는다
        var p = area(f, null, "P");
        var q = area(f, null, "Q");
        LogicalSubjectAreaDto.Area pNow = find(f, p.id());
        LogicalSubjectAreaDto.Area qNow = find(f, q.id());
        assertThat(raced(() -> areas.save(f.user, new Save(p.id(), f.modelId, q.id(), "P", null, null, pNow.modifiedAt())),
                () -> areas.save(f.user, new Save(q.id(), f.modelId, p.id(), "Q", null, null, qNow.modifiedAt())))).isIn("INVALID_LOGICAL_MODEL", "MODEL_CONFLICT");
        assertThat(find(f, q.id()).parentId()).isNull();
        // 같은 영역 동시 수정 : 같은 기준값으로 둘 → 둘째 409
        var r = area(f, null, "R");
        LocalDateTime rBase = find(f, r.id()).modifiedAt();
        assertThat(raced(() -> areas.save(f.user, new Save(r.id(), f.modelId, null, "R1", null, null, rBase)),
                () -> areas.save(f.user, new Save(r.id(), f.modelId, null, "R2", null, null, rBase)))).isEqualTo("MODEL_CONFLICT");
        assertThat(find(f, r.id()).areaName()).isEqualTo("R1");
    }

    // ------------------------------------------------------------------ 권한·격리

    @Test
    @DisplayName("권한 : VIEWER 는 조회·내 배치만, USER·DBA·MANAGER·ADMIN 은 영역 관리·분류 (@PreAuthorize). 다른 프로젝트의 모델·영역은 찾을 수 없음")
    void permissions() {
        GenerationFixture f = fixture("sa-roles");
        var area = area(f, null, "영역");
        long entity = f.entity("엔터티");
        Long project = f.user.getProjectId();
        for (String role : List.of("ROLE_VIEWER", "ROLE_USER", "ROLE_DBA", "ROLE_MANAGER", "ROLE_ADMIN")) {
            AccountDto user = SecurityTestSupport.loginAs(project, f.user.getId(), role);
            boolean editor = !role.equals("ROLE_VIEWER");
            assertThat(areaController.selectTree(user, f.modelId).getBody().areas()).isNotEmpty();
            assertThat(logicalController.selectEntityRows(user, f.modelId, 0, 10, null, null, area.id(), null, null).getBody()).containsKey("data");
            areaController.saveLayout(user, f.modelId, area.id(), new LogicalModelDto.Layout(areaController.selectLayout(user, f.modelId, area.id()).getBody().version(),
                    Map.of(entity, new LogicalModelDto.Point(1, 1)), new LogicalModelDto.Viewport(0, 0, 1)));
            Runnable create = () -> areaController.save(user, new Save(null, f.modelId, null, "영역-" + role, null, null, null));
            Runnable classify = () -> areaController.assign(user, f.modelId, new Assignment(node(area.id()), List.of(new AssignmentItem(entity, node(areaOf(entity))))));
            Runnable remove = () -> areaController.delete(user, 987_654_321L, LocalDateTime.now());
            if (editor) {
                create.run();
                classify.run();
                error(remove, ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
            } else {
                assertThatThrownBy(create::run).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(classify::run).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(remove::run).isInstanceOf(AccessDeniedException.class);
            }
        }
        // 기준값 없는 수정·삭제는 400
        AccountDto admin = SecurityTestSupport.loginAs(project, f.user.getId(), "ROLE_ADMIN");
        error(() -> areaController.save(admin, new Save(area.id(), f.modelId, null, "영역", null, null, null)), ErrorCode.MODEL_BASE_REQUIRED);
        error(() -> areaController.delete(admin, area.id(), null), ErrorCode.MODEL_BASE_REQUIRED);
        // 다른 프로젝트 : 모델·영역 모두 찾을 수 없음 (조회·배치·분류)
        GenerationFixture stranger = fixture("sa-roles-other");
        error(() -> areas.selectTree(stranger.user, f.modelId), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> areas.selectLayout(stranger.user, stranger.modelId, area.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> areas.assign(stranger.user, f.modelId, new Assignment(node(area.id()), List.of(new AssignmentItem(entity, JSON.nullNode())))), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> logical.selectEntityRows(stranger.user, stranger.modelId, null, 0, 10, null, area.id(), null, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> areas.delete(stranger.user, area.id(), LocalDateTime.now()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(count("select count(*) from TB_LGCL_SBJ_AREA where LGCL_SBJ_AREA_SN = ?", area.id())).isOne();
    }
}
