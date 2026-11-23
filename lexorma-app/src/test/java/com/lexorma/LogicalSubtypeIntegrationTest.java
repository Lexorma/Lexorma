package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.logical.controller.LogicalSubtypeRestController;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.Save;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-6 후속 5차 : 슈퍼타입·서브타입 — 그룹 구조(단일 상속·3단계·복수 그룹·최소 2개·순환), 가상 속성·식별자 상속, 이름 충돌, 분류 속성, 관계 상속·상속 주 식별자 속성 짝,
 * 승인 보호, 삭제·참조 무결성, 논리 ERD 범위·이웃, 물리 생성 제한·기존 매핑 보호, 변경 이력·모델 버전 형식 8·교환 형식 5, 실제 독립 트랜잭션 동시성, 권한·격리.
 * H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalSubtypeIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalSubtypeRestController subtypeController;
    @Autowired LogicalSubjectAreaService areas;
    @Autowired ModelVersionService versions;
    @Autowired ModelExchangeService exchange;
    @Autowired PhysicalGenerationService generation;
    @Autowired ModelMappingQualityService quality;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 준비

    private GenerationFixture fixture(String name) {
        return GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
    }

    /** 미승인 엔터티 (관리자가 등록하면 바로 승인되므로 승인을 되돌린다 — 상속 변경은 승인된 엔터티에서 거절된다) */
    private long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private long attr(GenerationFixture f, long entity, String name) {
        return logical.saveAttribute(f.user, new LogicalModelDto.Attribute(null, entity, name, null, null, false, null, null, null, null, null)).id();
    }

    private void pk(GenerationFixture f, long entity, long... attributes) {
        List<Long> ids = new ArrayList<>();
        for (long a : attributes) ids.add(a);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity, "주 식별자", true, ids, null));
    }

    private static List<MemberInput> members(Object... entityAndValue) {
        List<MemberInput> result = new ArrayList<>();
        for (int i = 0; i < entityAndValue.length; i += 2) result.add(new MemberInput(((Number) entityAndValue[i]).longValue(), (String) entityAndValue[i + 1]));
        return result;
    }

    private static List<MemberInput> of(long... entities) {
        List<MemberInput> result = new ArrayList<>();
        for (long e : entities) result.add(new MemberInput(e, null));
        return result;
    }

    private LogicalSubtypeDto.Group group(GenerationFixture f, long supertype, String name, long... members) {
        return subtypes.saveGroup(f.user, new Save(null, f.modelId, supertype, name, null, false, true, null, of(members), null));
    }

    private LogicalSubtypeDto.Group save(GenerationFixture f, Long id, long supertype, String name, Boolean overlapping, Boolean total, Long classification,
                                         List<MemberInput> members, LocalDateTime base) {
        return subtypes.saveGroup(f.user, new Save(id, f.modelId, supertype, name, null, overlapping, total, classification, members, base));
    }

    /** 그룹의 지금 상태로 구성원만 바꿔 저장 */
    private LogicalSubtypeDto.Group members(GenerationFixture f, LogicalSubtypeDto.Group group, List<MemberInput> members) {
        LogicalSubtypeDto.Group now = subtypes.selectGroup(f.user, group.id());
        return save(f, now.id(), now.supertypeEntityId(), now.groupName(), now.overlapping(), now.total(), now.classificationAttributeId(), members, now.modifiedAt());
    }

    private void deleteGroup(GenerationFixture f, LogicalSubtypeDto.Group group) {
        subtypes.deleteGroup(f.user, group.id(), subtypes.selectGroup(f.user, group.id()).modifiedAt());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void error(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static void error(Runnable call, ErrorCode code, String messagePart) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getCustomMessage()).contains(messagePart);
        });
    }

    private int groupRows(GenerationFixture f) {
        return count("select count(*) from TB_LGCL_SBTYP_GROUP where LGCL_MDL_SN = ?", f.modelId);
    }

    // ------------------------------------------------------------------ 구조

    @Test
    @DisplayName("구조 : 그룹 등록(구성원 2개 이상·원자적)·복수 그룹·네 가지 분류 조합, 빈·1개·중복·자기 자신·단일 상속·순환·4단계(하위 트리 포함)·다른 모델·다른 프로젝트 거절, 기본값 없음")
    void structure() {
        GenerationFixture f = fixture("st-structure");
        long customer = entity(f, "고객");
        long person = entity(f, "개인 고객");
        long company = entity(f, "기업 고객");
        long vip = entity(f, "우수 고객");
        long overseas = entity(f, "해외 고객");
        var type = group(f, customer, "고객 유형", person, company);
        assertThat(type.subtypes()).extracting(LogicalSubtypeDto.Member::entityId, LogicalSubtypeDto.Member::sortOrder).containsExactly(tuple(person, 1), tuple(company, 2));
        // 복수 그룹 : 같은 슈퍼타입의 다른 기준 — 네 가지 조합 모두 저장된다
        var trait = save(f, null, customer, "고객 특성", true, false, null, of(vip, overseas), null);
        assertThat(trait.overlapping()).isTrue();
        assertThat(trait.total()).isFalse();
        for (boolean overlapping : new boolean[]{true, false}) {
            for (boolean total : new boolean[]{true, false}) {
                long a = entity(f, "조합 A " + overlapping + total);
                long b = entity(f, "조합 B " + overlapping + total);
                long s = entity(f, "조합 슈퍼 " + overlapping + total);
                var saved = save(f, null, s, "조합", overlapping, total, null, of(a, b), null);
                assertThat(saved.overlapping()).isEqualTo(overlapping);
                assertThat(saved.total()).isEqualTo(total);
            }
        }
        int before = groupRows(f);
        long x = entity(f, "X");
        long y = entity(f, "Y");
        // 배타성·완전성은 반드시 고른다 (기본값 없음)
        error(() -> save(f, null, x, "기본값", null, true, null, of(y, person), null), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> save(f, null, x, "기본값", false, null, null, of(y, entity(f, "Y2")), null), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> save(f, null, x, "빈 그룹", false, true, null, List.of(), null), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> save(f, null, x, "하나", false, true, null, of(y), null), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> save(f, null, x, "중복", false, true, null, of(y, y), null), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> save(f, null, x, "자기", false, true, null, of(x, y), null), ErrorCode.INVALID_LOGICAL_MODEL);
        // 단일 상속 : 이미 다른 그룹의 서브타입 (같은 슈퍼타입의 다른 그룹도)
        error(() -> save(f, null, x, "단일", false, true, null, of(person, y), null), ErrorCode.INVALID_LOGICAL_MODEL, "이미 그룹");
        error(() -> save(f, null, customer, "다른 기준", false, true, null, of(person, y), null), ErrorCode.INVALID_LOGICAL_MODEL);
        // 그룹명 : 같은 슈퍼타입 안에서 대소문자 무시
        error(() -> save(f, null, customer, " 고객 유형 ", false, true, null, of(x, y), null), ErrorCode.SAVED_DATA_EXISTS);
        // 순환 : 고객 ← 개인 고객 인데 개인 고객의 서브타입으로 고객
        error(() -> save(f, null, person, "순환", false, true, null, of(customer, y), null), ErrorCode.INVALID_LOGICAL_MODEL, "순환");
        assertThat(groupRows(f)).as("실패한 저장은 아무것도 남기지 않는다").isEqualTo(before);

        // 3단계 허용 (고객 1 → 기업 고객 2 → 해외 기업 고객 3), 4단계 거절
        long overseasCompany = entity(f, "해외 기업 고객");
        long localCompany = entity(f, "국내 기업 고객");
        var companies = group(f, company, "기업 구분", overseasCompany, localCompany);
        long publicAgency = entity(f, "해외 공공기관 고객");
        long privateFirm = entity(f, "해외 민간 고객");
        error(() -> group(f, overseasCompany, "4단계", publicAgency, privateFirm), ErrorCode.INVALID_LOGICAL_MODEL, "3단계");
        // 하위 트리를 옮겨 4단계가 되는 경우 : 2단계 하위가 있는 루트 R(R → R1 → R11)을 해외 고객(2단계) 아래로 → R1 이 4단계
        long r = entity(f, "R");
        long r1 = entity(f, "R1");
        long r2 = entity(f, "R2");
        long r11 = entity(f, "R11");
        long r12 = entity(f, "R12");
        group(f, r, "R 그룹", r1, r2);
        group(f, r1, "R1 그룹", r11, r12);
        long another = entity(f, "다른 고객");
        error(() -> group(f, overseas, "옮김", r, another), ErrorCode.INVALID_LOGICAL_MODEL, "3단계");
        assertThat(subtypes.selectInheritance(f.user, r).level()).isOne();
        assertThat(subtypes.selectInheritance(f.user, overseasCompany).level()).isEqualTo(3);
        assertThat(subtypes.selectInheritance(f.user, overseasCompany).ancestors()).extracting(LogicalSubtypeDto.Ancestor::entityId).containsExactly(customer, company);

        // 같은 프로젝트의 다른 모델 엔터티, 다른 프로젝트 엔터티
        GenerationFixture otherModel = f.otherModel("다른 모델 " + System.nanoTime());
        long foreign = entity(otherModel, "다른 모델 엔터티");
        error(() -> save(f, null, x, "다른 모델", false, true, null, of(y, foreign), null), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> save(f, null, foreign, "다른 모델 슈퍼", false, true, null, of(x, y), null), ErrorCode.INVALID_LOGICAL_MODEL);
        GenerationFixture stranger = fixture("st-structure-other");
        long strangerEntity = entity(stranger, "남의 엔터티");
        SecurityTestSupport.loginAs(f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
        error(() -> save(f, null, x, "다른 프로젝트", false, true, null, of(y, strangerEntity), null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> subtypes.selectGroup(stranger.user, type.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);

        // 구성원 변경 : 1개만 남기면 거절 (그룹은 자동 삭제되지 않는다), 수정 기준값이 다르면 409
        error(() -> members(f, companies, of(overseasCompany)), ErrorCode.INVALID_LOGICAL_MODEL);
        assertThat(subtypes.selectGroup(f.user, companies.id()).subtypes()).hasSize(2);
        error(() -> save(f, companies.id(), company, "기업 구분", false, true, null, of(overseasCompany, localCompany), companies.modifiedAt().minusSeconds(5)), ErrorCode.MODEL_CONFLICT);
        // 구성원 추가·순서 변경은 그룹 기준값을 바꾼다 (DEC-08)
        long newCompany = entity(f, "신규 기업 고객");
        var changed = members(f, companies, of(localCompany, overseasCompany, newCompany));
        assertThat(changed.subtypes()).extracting(LogicalSubtypeDto.Member::entityId).containsExactly(localCompany, overseasCompany, newCompany);
        assertThat(changed.modifiedAt()).isNotEqualTo(companies.modifiedAt());
        // 그룹 삭제 : 엔터티는 남고 상속만 풀린다
        deleteGroup(f, trait);
        assertThat(subtypes.selectInheritance(f.user, vip).level()).isOne();
        assertThat(count("select count(*) from TB_LGCL_ENTT where LGCL_ENTT_SN in (?, ?)", vip, overseas)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 속성·식별자

    /** 미승인 엔터티 n 개를 JDBC 로 한 번에 넣는다 (구성원 수 시험용 — 엔터티 등록 API 를 수백 번 부르지 않는다). 이름 = prefix + 순번 */
    private long[] bulkEntities(GenerationFixture f, String prefix, int n) {
        long base = BULK_IDS.getAndAdd(n);
        List<Object[]> rows = new ArrayList<>();
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = base + i;
            rows.add(new Object[]{ids[i], f.user.getProjectId(), f.modelId, prefix + String.format("%04d", i)});
        }
        jdbc.batchUpdate("insert into TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN) values (?, ?, ?, ?, false)", rows);
        return ids;
    }

    private static final java.util.concurrent.atomic.AtomicLong BULK_IDS = new java.util.concurrent.atomic.AtomicLong(7_000_000_000_000L + (System.currentTimeMillis() % 100_000L) * 100_000L);

    private int memberRows(long groupId) {
        return count("select count(*) from TB_LGCL_SBTYP where LGCL_SBTYP_GROUP_SN = ?", groupId);
    }

    @Test
    @DisplayName("D-51 구성원 수 : 업무상 최대 없음 — 1개 거절, 2·200·201·500개 등록·수정, 중복·단일 상속·승인 위반은 전체 롤백, 형식 5 왕복, 큰 그룹의 부분 ERD·범위 밖 상속 수·이웃 300 한도")
    void memberCountUnlimited() {
        GenerationFixture f = fixture("st-members");
        long[] supers = bulkEntities(f, "슈퍼 ", 5);
        long[] pool = bulkEntities(f, "구성원 ", 920);
        java.util.function.BiFunction<Integer, Integer, List<MemberInput>> range = (from, to) -> {
            List<MemberInput> result = new ArrayList<>();
            for (int i = from; i < to; i++) result.add(new MemberInput(pool[i], "V" + i));
            return result;
        };
        error(() -> save(f, null, supers[0], "한 개", false, true, null, range.apply(0, 1), null), ErrorCode.INVALID_LOGICAL_MODEL, "2개 이상");
        assertThat(groupRows(f)).isZero();

        var two = save(f, null, supers[0], "두 개", false, true, null, range.apply(0, 2), null);
        var g200 = save(f, null, supers[1], "이백", false, true, null, range.apply(2, 202), null);
        var g201 = save(f, null, supers[2], "이백일", true, false, null, range.apply(202, 403), null);
        var g500 = save(f, null, supers[3], "오백", false, false, null, range.apply(403, 903), null);
        assertThat(List.of(two, g200, g201, g500)).extracting(g -> g.subtypes().size()).containsExactly(2, 200, 201, 500);
        assertThat(List.of(two, g200, g201, g500)).extracting(g -> memberRows(g.id())).containsExactly(2, 200, 201, 500);
        assertThat(g500.subtypes()).extracting(LogicalSubtypeDto.Member::sortOrder).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 500).boxed().toList());

        // 수정 : 501개로 늘리기 (구성원 전체 교체, 기준값 필요)
        List<MemberInput> grown = range.apply(403, 904);
        var g501 = save(f, g500.id(), supers[3], "오백", false, false, null, grown, g500.modifiedAt());
        assertThat(memberRows(g501.id())).isEqualTo(501);

        // 실패는 전체 롤백 : 그룹 행·구성원·수정 일시·이력이 그대로
        int history = count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", f.user.getProjectId());
        List<MemberInput> duplicated = new ArrayList<>(range.apply(403, 904));
        duplicated.add(new MemberInput(pool[500], null));
        error(() -> save(f, g501.id(), supers[3], "오백", false, false, null, duplicated, g501.modifiedAt()), ErrorCode.INVALID_LOGICAL_MODEL, "두 번");
        List<MemberInput> stolen = new ArrayList<>(range.apply(903, 920));
        stolen.add(new MemberInput(pool[10], null)); // 이백 그룹의 구성원
        error(() -> save(f, null, supers[4], "빼앗기", true, false, null, stolen, null), ErrorCode.INVALID_LOGICAL_MODEL, "하나의 슈퍼타입만");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = true where LGCL_ENTT_SN = ?", pool[919]);
        error(() -> save(f, null, supers[4], "승인 섞임", true, false, null, range.apply(904, 920), null), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", pool[919]);
        assertThat(groupRows(f)).isEqualTo(4);
        assertThat(memberRows(g501.id())).isEqualTo(501);
        assertThat(memberRows(g200.id())).isEqualTo(200);
        assertThat(count("select count(*) from TB_LGCL_SBTYP where LGCL_MDL_SN = ?", f.modelId)).isEqualTo(2 + 200 + 201 + 501);
        assertThat(subtypes.selectGroup(f.user, g501.id()).modifiedAt()).isEqualTo(g501.modifiedAt());
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", f.user.getProjectId())).isEqualTo(history);

        // 부분 ERD : 슈퍼타입만 범위면 그룹 원 없이 범위 밖 상속 연결 501, 이웃 탐색은 300 한도 그대로
        var superOnly = logical.selectGraphPart(f.user, f.modelId, "슈퍼 0003", 0, 100);
        assertThat(superOnly.entities()).hasSize(1);
        assertThat(superOnly.subtypeGroups()).isEmpty();
        assertThat(superOnly.outsideInheritances()).containsEntry(supers[3], 501);
        var neighbors = logical.selectNeighbors(f.user, f.modelId, supers[3], 1);
        assertThat(neighbors.entities()).hasSize(LogicalModelService.ERD_MAX_ENTITIES);
        assertThat(neighbors.truncated()).isTrue();
        assertThat(neighbors.subtypeGroups()).singleElement().satisfies(g -> {
            assertThat(g.subtypeCount()).isEqualTo(501);
            assertThat(g.subtypes()).hasSize(LogicalModelService.ERD_MAX_ENTITIES - 1); // 범위 안 구성원만 (분류 값과 함께)
            assertThat(g.subtypes()).allSatisfy(m -> assertThat(m.classificationValue()).startsWith("V"));
        });
        // 서브타입 하나의 상속 정보 : 부모 그룹 정의와 자기 분류 값
        assertThat(subtypes.selectInheritance(f.user, pool[700]).parent()).satisfies(p -> {
            assertThat(p.groupName()).isEqualTo("오백");
            assertThat(p.classificationValue()).isEqualTo("V700");
        });

        // 형식 5 왕복 : 구성원 501개 그룹 그대로 (순서·분류 값)
        ModelExchangeDto.Document document = exchange.export(f.member());
        assertThat(document.model().get("SUBTYPE_GROUP")).hasSize(4);
        GenerationFixture targetFixture = fixture("st-members-import");
        AccountDto target = targetFixture.member();
        String modelName = jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, f.modelId);
        ModelExchangeDto.Selection selection = new ModelExchangeDto.Selection(List.of(), List.of(modelName), List.of());
        var plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), document, selection));
        var result = exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), document, selection, plan.planToken(), plan.requestId()));
        assertThat(result.created()).containsEntry("SUBTYPE_GROUP", 4).containsEntry("LOGICAL_SUBTYPE", 2 + 200 + 201 + 501);
        assertThat(jdbc.queryForList("select e.ENTT_NM || '=' || m.CLSF_VL from TB_LGCL_SBTYP m join TB_LGCL_SBTYP_GROUP g on g.LGCL_SBTYP_GROUP_SN = m.LGCL_SBTYP_GROUP_SN"
                + " join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.SBTYP_ENTT_SN where m.PRJ_SN = ? and g.SBTYP_GROUP_NM = '오백' order by m.SORT_ORD", String.class, target.getProjectId()))
                .as("구성원 순서(SORT_ORD)·분류 값 보존").hasSize(501).startsWith("구성원 0403=V403", "구성원 0404=V404").endsWith("구성원 0903=V903");
    }

    /** 프로젝트의 논리 모델 내용을 이름으로 (ID 와 무관하게 비교) — 행마다 문자열, 정렬해서 돌려준다 */
    private List<String> modelText(long projectId, String sql) {
        List<String> rows = new ArrayList<>(jdbc.query(sql, (rs, n) -> {
            StringBuilder b = new StringBuilder();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) b.append(c > 1 ? "|" : "").append(rs.getString(c));
            return b.toString();
        }, projectId));
        rows.sort(null);
        return rows;
    }

    private static final Map<String, String> MODEL_SQL = new LinkedHashMap<>();
    static {
        MODEL_SQL.put("엔터티", "select e.ENTT_NM, e.ENTT_EXPLN from TB_LGCL_ENTT e where e.PRJ_SN = ?");
        MODEL_SQL.put("속성", "select e.ENTT_NM, a.ATRB_NM, a.SORT_ORD from TB_LGCL_ATRB a join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = a.LGCL_ENTT_SN where e.PRJ_SN = ?");
        MODEL_SQL.put("식별자", "select e.ENTT_NM, i.IDNTFR_NM, case when i.BSC_YN then 'PK' else 'AK' end, a.ATRB_NM, ia.SORT_ORD from TB_LGCL_IDNTFR i join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = i.LGCL_ENTT_SN"
                + " join TB_LGCL_IDNTFR_ATRB ia on ia.LGCL_IDNTFR_SN = i.LGCL_IDNTFR_SN join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = ia.LGCL_ATRB_SN where e.PRJ_SN = ?");
        MODEL_SQL.put("관계·속성 짝", "select r.REL_NM, s.ENTT_NM, t.ENTT_NM, pa.ATRB_NM, ca.ATRB_NM, p.SORT_ORD from TB_LGCL_REL r join TB_LGCL_ENTT s on s.LGCL_ENTT_SN = r.ORGNL_ENTT_SN"
                + " join TB_LGCL_ENTT t on t.LGCL_ENTT_SN = r.TRGT_ENTT_SN left join TB_LGCL_REL_ATRB p on p.LGCL_REL_SN = r.LGCL_REL_SN left join TB_LGCL_ATRB pa on pa.LGCL_ATRB_SN = p.ORGNL_ATRB_SN"
                + " left join TB_LGCL_ATRB ca on ca.LGCL_ATRB_SN = p.TRGT_ATRB_SN where r.PRJ_SN = ?");
        MODEL_SQL.put("서브타입 그룹", "select s.ENTT_NM, g.SBTYP_GROUP_NM, case when g.DPCN_PSBLTY_YN then 'O' else 'D' end, case when g.TOTL_YN then 'T' else 'P' end,"
                + " a.ATRB_NM, ae.ENTT_NM, g.SORT_ORD from TB_LGCL_SBTYP_GROUP g join TB_LGCL_ENTT s on s.LGCL_ENTT_SN = g.SPRTYP_ENTT_SN"
                + " left join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = g.CLSF_ATRB_SN left join TB_LGCL_ENTT ae on ae.LGCL_ENTT_SN = a.LGCL_ENTT_SN where g.PRJ_SN = ?");
        MODEL_SQL.put("서브타입 구성원", "select g.SBTYP_GROUP_NM, m.SORT_ORD, e.ENTT_NM, m.CLSF_VL from TB_LGCL_SBTYP m join TB_LGCL_SBTYP_GROUP g on g.LGCL_SBTYP_GROUP_SN = m.LGCL_SBTYP_GROUP_SN"
                + " join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.SBTYP_ENTT_SN where m.PRJ_SN = ?");
        MODEL_SQL.put("주제 영역 소속", "select ar.SBJ_AREA_NM, e.ENTT_NM from TB_LGCL_SBJ_AREA_ENTT x join TB_LGCL_SBJ_AREA ar on ar.LGCL_SBJ_AREA_SN = x.LGCL_SBJ_AREA_SN"
                + " join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = x.LGCL_ENTT_SN where x.PRJ_SN = ?");
    }

    @Test
    @DisplayName("D-53 교환 : 구성원 2·500·999·1,000·1,001·2,000개 그룹을 실제 파일로 내보내 다른 프로젝트에 가져오면 이름·순서·분류·상속·식별자·속성 짝·주제 영역이 같고 ID 는 새로, 모두 미승인. 1개 그룹·자식 목록 1,000행 초과 다른 목록 파일은 거절, 아무것도 남지 않음")
    void exchangeLargeGroups() {
        GenerationFixture f = fixture("st-x-large");
        int[] sizes = {2, 500, 999, 1000, 1001, 2000};
        long[] supers = bulkEntities(f, "큰 슈퍼 ", sizes.length);
        long[] pool = bulkEntities(f, "큰 구성원 ", java.util.Arrays.stream(sizes).sum());
        // 2,000개 그룹의 슈퍼타입 : 복합 주 식별자 + 분류 속성(직접), 그 구성원 하나는 3단계 그룹의 슈퍼타입(분류 속성 = 상속)
        long top = supers[5];
        long no = attr(f, top, "대표 번호");
        long branch = attr(f, top, "대표 지점");
        pk(f, top, branch, no);
        long typeCode = attr(f, top, "대표 유형 코드");
        int next = 0;
        for (int g = 0; g < sizes.length; g++) {
            List<MemberInput> members = new ArrayList<>();
            for (int m = 0; m < sizes[g]; m++, next++) members.add(new MemberInput(pool[next], g == 5 ? "V" + m : null));
            save(f, null, supers[g], "그룹 " + sizes[g], g != 5, g == 5, g == 5 ? typeCode : null, members, null);
        }
        long middle = pool[next - 1]; // 2,000개 그룹의 마지막 구성원
        long[] leaves = bulkEntities(f, "큰 손자 ", 2);
        save(f, null, middle, "손자 그룹", false, false, typeCode, members(leaves[0], "L0", leaves[1], "L1"), null);
        long middleOwn = attr(f, middle, "중간 고유 속성");
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, middle, "중간 보조 식별자", false, List.of(middleOwn), null));
        // 상속 주 식별자(복합)를 쓰는 속성 짝, 주제 영역 소속, 승인된 일반 엔터티
        long child = entity(f, "큰 자식");
        long childNo = attr(f, child, "자식 대표 번호");
        long childBranch = attr(f, child, "자식 대표 지점");
        var rel = f.rel("손자에서", leaves[0], child, false, 1);
        f.pairs(rel, new long[]{branch, childBranch}, new long[]{no, childNo});
        var area = areas.save(f.user, new com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Save(null, f.modelId, null, "큰 영역", null, null, null));
        jdbc.update("insert into TB_LGCL_SBJ_AREA_ENTT(LGCL_SBJ_AREA_ENTT_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBJ_AREA_SN, LGCL_ENTT_SN) values (?, ?, ?, ?, ?)",
                BULK_IDS.getAndIncrement(), f.user.getProjectId(), f.modelId, area.id(), middle);
        long approved = entity(f, "큰 승인 엔터티");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = true where LGCL_ENTT_SN = ?", approved);
        long source = f.user.getProjectId();
        assertThat(count("select count(*) from TB_LGCL_SBTYP where PRJ_SN = ?", source)).isEqualTo(5502 + 2);

        // 실제 파일 : JSON 으로 쓰고 다시 읽는다 (화면이 내려받고 올리는 것과 같은 바이트)
        tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        byte[] bytes = json.writeValueAsBytes(exchange.export(f.member()));
        assertThat(bytes.length).as("기존 요청 한도 30MB 안").isLessThan(30 * 1024 * 1024);
        ModelExchangeDto.Document document = json.readValue(bytes, ModelExchangeDto.Document.class);
        assertThat(document.formatVersion()).isEqualTo(7);
        assertThat(document.model().get("SUBTYPE_GROUP")).extracting(i -> ((List<?>) i.state().get("subtypes")).size()).containsExactlyInAnyOrder(2, 500, 999, 1000, 1001, 2000, 2);

        GenerationFixture targetFixture = fixture("st-x-large-import");
        AccountDto target = targetFixture.member();
        long targetProject = target.getProjectId();
        String modelName = jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, f.modelId);
        ModelExchangeDto.Selection selection = new ModelExchangeDto.Selection(List.of(), List.of(modelName), List.of());
        var plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(targetProject, document, selection));
        var result = exchange.apply(target, new ModelExchangeDto.ApplyRequest(targetProject, document, selection, plan.planToken(), plan.requestId()));
        assertThat(result.created()).containsEntry("SUBTYPE_GROUP", 7).containsEntry("LOGICAL_SUBTYPE", 5504);
        for (Map.Entry<String, String> query : MODEL_SQL.entrySet()) {
            assertThat(modelText(targetProject, query.getValue())).as(query.getKey()).isNotEmpty().isEqualTo(modelText(source, query.getValue()));
        }
        // ID 는 새로 (원본 행을 가리키지 않는다), 모두 미승인 (가져오기 정책), 구성원은 같은 프로젝트의 엔터티
        assertThat(count("select count(*) from TB_LGCL_SBTYP m join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.SBTYP_ENTT_SN where m.PRJ_SN = ? and e.PRJ_SN <> ?", targetProject, targetProject)).isZero();
        assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP t join TB_LGCL_SBTYP_GROUP s on s.LGCL_SBTYP_GROUP_SN = t.LGCL_SBTYP_GROUP_SN where t.PRJ_SN = ? and s.PRJ_SN = ?", targetProject, source)).isZero();
        assertThat(count("select count(*) from TB_LGCL_ENTT where PRJ_SN = ? and APRV_YN = true", targetProject)).isZero();
        // 가져온 모델에서 상속이 그대로 동작 : 손자의 상속 정보·분류 값·상속 주 식별자
        long importedLeaf = jdbc.queryForObject("select LGCL_ENTT_SN from TB_LGCL_ENTT where PRJ_SN = ? and ENTT_NM = '큰 손자 0000'", Long.class, targetProject);
        var leafInfo = subtypes.selectInheritance(target, importedLeaf);
        assertThat(leafInfo.level()).isEqualTo(3);
        assertThat(leafInfo.parent().classificationValue()).isEqualTo("L0");
        assertThat(leafInfo.identifiers()).filteredOn(LogicalSubtypeDto.EffectiveIdentifier::isPrimary).singleElement()
                .satisfies(i -> assertThat(i.attributeNames()).containsExactly("대표 지점", "대표 번호"));

        // 잘못된 파일은 계획 단계에서 전체 거절 — 대상 프로젝트에 아무것도 남지 않는다
        GenerationFixture emptyFixture = fixture("st-x-large-reject");
        AccountDto empty = emptyFixture.member();
        List<String> rejectTables = List.of("TB_LGCL_MDL", "TB_LGCL_ENTT", "TB_LGCL_SBTYP_GROUP", "TB_LGCL_SBTYP", "TB_LGCL_SBJ_AREA", "TB_MDL_CHG_HSTRY", "TB_MDL_VER");
        Map<String, Integer> beforeReject = new LinkedHashMap<>();
        for (String table : rejectTables) beforeReject.put(table, count("select count(*) from " + table + " where PRJ_SN = ?", empty.getProjectId()));
        java.util.function.Consumer<ModelExchangeDto.Document> refused = doc -> {
            assertThatThrownBy(() -> exchange.plan(empty, new ModelExchangeDto.PlanRequest(empty.getProjectId(), doc, selection)))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID));
        };
        Long group2 = groupId(document, "그룹 2");
        refused.accept(withGroup(document, group2, "subtypes", List.of(((List<?>) groupItem(document, group2).state().get("subtypes")).getFirst())));
        // 다른 자식 목록(식별자 구성)은 1,000행 그대로
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) tooMany.add(Map.of("attributeId", (long) i, "sortOrder", i + 1));
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        ModelExchangeDto.Item identifier = model.get("LOGICAL_IDENTIFIER").getFirst();
        Map<String, Object> identifierState = new LinkedHashMap<>(identifier.state());
        identifierState.put("attributes", tooMany);
        List<ModelExchangeDto.Item> identifiers = new ArrayList<>(model.get("LOGICAL_IDENTIFIER"));
        identifiers.set(0, new ModelExchangeDto.Item(identifier.id(), identifier.name(), identifierState));
        model.put("LOGICAL_IDENTIFIER", identifiers);
        refused.accept(new ModelExchangeDto.Document(document.format(), document.formatVersion(), document.exportedAt(), document.dbType(), document.counts(), model, document.layouts()));
        // D-17 : 서브타입 보조 식별자가 상속 속성을 가리키는 파일
        Map<String, List<ModelExchangeDto.Item>> model2 = new LinkedHashMap<>(document.model());
        List<ModelExchangeDto.Item> identifiers2 = new ArrayList<>(model2.get("LOGICAL_IDENTIFIER"));
        long topAttributeRef = model2.get("LOGICAL_ATTRIBUTE").stream().filter(a -> "대표 유형 코드".equals(a.state().get("attributeName"))).findFirst().orElseThrow().id();
        for (int i = 0; i < identifiers2.size(); i++) {
            ModelExchangeDto.Item item = identifiers2.get(i);
            if (!"중간 보조 식별자".equals(item.state().get("identifierName"))) continue;
            Map<String, Object> state = new LinkedHashMap<>(item.state());
            state.put("attributes", List.of(Map.of("attributeId", topAttributeRef, "sortOrder", 1)));
            identifiers2.set(i, new ModelExchangeDto.Item(item.id(), item.name(), state));
        }
        model2.put("LOGICAL_IDENTIFIER", identifiers2);
        refused.accept(new ModelExchangeDto.Document(document.format(), document.formatVersion(), document.exportedAt(), document.dbType(), document.counts(), model2, document.layouts()));
        for (String table : rejectTables) {
            assertThat(count("select count(*) from " + table + " where PRJ_SN = ?", empty.getProjectId())).as(table).isEqualTo(beforeReject.get(table));
        }
    }

    @Test
    @DisplayName("속성·식별자 : 가상 상속(원본 ID·정의 엔터티·깊이·편집 불가)·복합 주 식별자 순서 상속, 상속 속성 복제 없음, 서브타입 문맥의 수정 거절, 이름 충돌 거절(형제는 허용), 자체 주 식별자 금지")
    void attributesAndIdentifiers() {
        GenerationFixture f = fixture("st-attr");
        long customer = entity(f, "고객");
        long no = attr(f, customer, "고객 번호");
        long branch = attr(f, customer, "지점 코드");
        long name = attr(f, customer, "고객명");
        pk(f, customer, branch, no); // 복합 : 지점 코드 → 고객 번호 순서
        long email = attr(f, customer, "이메일");
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, customer, "이메일 식별자", false, List.of(email), null));
        long company = entity(f, "기업 고객");
        long bizNo = attr(f, company, "사업자등록번호");
        long person = entity(f, "개인 고객");
        attr(f, person, "생년월일");
        int attributeRows = count("select count(*) from TB_LGCL_ATRB");
        group(f, customer, "고객 유형", company, person);
        long overseas = entity(f, "해외 기업 고객");
        long local = entity(f, "국내 기업 고객");
        long region = attr(f, overseas, "국가 코드");
        group(f, company, "기업 구분", overseas, local);
        assertThat(count("select count(*) from TB_LGCL_ATRB")).as("상속 속성을 복제하지 않는다").isEqualTo(attributeRows + 1);

        var inheritance = subtypes.selectInheritance(f.user, overseas);
        assertThat(inheritance.level()).isEqualTo(3);
        assertThat(inheritance.rootEntityId()).isEqualTo(customer);
        assertThat(inheritance.parent().supertypeEntityId()).isEqualTo(company);
        assertThat(inheritance.attributes()).extracting(LogicalSubtypeDto.EffectiveAttribute::attributeId, LogicalSubtypeDto.EffectiveAttribute::definedEntityId,
                        LogicalSubtypeDto.EffectiveAttribute::depth, LogicalSubtypeDto.EffectiveAttribute::inherited, LogicalSubtypeDto.EffectiveAttribute::editable,
                        LogicalSubtypeDto.EffectiveAttribute::contextEntityId)
                .containsExactly(tuple(no, customer, 2, true, false, overseas), tuple(branch, customer, 2, true, false, overseas), tuple(name, customer, 2, true, false, overseas),
                        tuple(email, customer, 2, true, false, overseas), tuple(bizNo, company, 1, true, false, overseas), tuple(region, overseas, 0, false, true, overseas));
        assertThat(inheritance.attributes()).filteredOn(LogicalSubtypeDto.EffectiveAttribute::primary)
                .extracting(LogicalSubtypeDto.EffectiveAttribute::attributeId, LogicalSubtypeDto.EffectiveAttribute::keyOrder).containsExactlyInAnyOrder(tuple(branch, 1), tuple(no, 2));
        // 식별자 : 상속 주 식별자(복합 순서 그대로) → 상속 보조 식별자
        assertThat(inheritance.identifiers()).extracting(LogicalSubtypeDto.EffectiveIdentifier::isPrimary, LogicalSubtypeDto.EffectiveIdentifier::inherited,
                LogicalSubtypeDto.EffectiveIdentifier::definedEntityId, LogicalSubtypeDto.EffectiveIdentifier::attributeIds)
                .containsExactly(tuple(true, true, customer, List.of(branch, no)), tuple(false, true, customer, List.of(email)));
        assertThat(subtypes.selectEffectiveAttributes(f.user, overseas).attributes()).hasSize(6);
        // 직접 정의 속성 API 의 의미는 그대로 (자기 속성만)
        assertThat(logical.selectAttributes(f.user, overseas)).extracting(LogicalModelDto.Attribute::id).containsExactly(region);

        // 서브타입 자체 보조 식별자는 허용, 주 식별자는 금지
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, overseas, "국가 식별자", false, List.of(region), null));
        error(() -> logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, overseas, "자체 PK", true, List.of(region), null)), ErrorCode.INVALID_LOGICAL_MODEL, "자체 주 식별자");
        // D-17 (M-6 최종 정책) : 자체 보조 식별자는 직접 정의 속성만 — 상속 속성만·직접 + 상속 혼합·형제/다른 모델/다른 프로젝트 속성은 거절, 식별자 행 없음
        int identifierRows = count("select count(*) from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ?", overseas);
        error(() -> logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, overseas, "상속 속성 식별자", false, List.of(email), null)), ErrorCode.INVALID_LOGICAL_MODEL, "같은 엔터티의 속성");
        error(() -> logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, overseas, "혼합 식별자", false, List.of(region, bizNo), null)), ErrorCode.INVALID_LOGICAL_MODEL, "같은 엔터티의 속성");
        long sibling = attr(f, local, "국내 지역 코드");
        error(() -> logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, overseas, "형제 속성 식별자", false, List.of(sibling), null)), ErrorCode.INVALID_LOGICAL_MODEL, "같은 엔터티의 속성");
        GenerationFixture otherModel = f.otherModel("st-attr-다른 모델");
        long foreignEntity = entity(otherModel, "다른 모델 엔터티");
        long foreignAttr = attr(otherModel, foreignEntity, "다른 모델 속성");
        error(() -> logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, overseas, "다른 모델 식별자", false, List.of(foreignAttr), null)), ErrorCode.INVALID_LOGICAL_MODEL, "같은 엔터티의 속성");
        GenerationFixture otherProject = fixture("st-attr-other");
        long otherProjectEntity = entity(otherProject, "다른 프로젝트 엔터티");
        long otherProjectAttr = attr(otherProject, otherProjectEntity, "다른 프로젝트 속성");
        SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
        error(() -> logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, overseas, "다른 프로젝트 식별자", false, List.of(otherProjectAttr), null)), ErrorCode.INVALID_LOGICAL_MODEL, "같은 엔터티의 속성");
        assertThat(count("select count(*) from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ?", overseas)).as("거절된 식별자는 남지 않는다").isEqualTo(identifierRows);
        // 자체 복합 보조 식별자의 구성 순서, 상위 보조 식별자의 직접 상속(2단계)·간접 상속(3단계) — 원본 식별자·속성 ID 그대로
        long regionGroup = attr(f, overseas, "권역 코드");
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, overseas, "권역·국가 식별자", false, List.of(regionGroup, region), null));
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, company, "사업자 식별자", false, List.of(bizNo), null));
        long emailIdentifier = jdbc.queryForObject("select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ? and IDNTFR_NM = '이메일 식별자'", Long.class, customer);
        assertThat(subtypes.selectInheritance(f.user, company).identifiers()).filteredOn(i -> !i.isPrimary())
                .extracting(LogicalSubtypeDto.EffectiveIdentifier::inherited, LogicalSubtypeDto.EffectiveIdentifier::definedEntityId, LogicalSubtypeDto.EffectiveIdentifier::attributeIds)
                .containsExactly(tuple(true, customer, List.of(email)), tuple(false, company, List.of(bizNo)));
        assertThat(subtypes.selectInheritance(f.user, overseas).identifiers()).filteredOn(i -> !i.isPrimary())
                .extracting(LogicalSubtypeDto.EffectiveIdentifier::inherited, LogicalSubtypeDto.EffectiveIdentifier::definedEntityId, LogicalSubtypeDto.EffectiveIdentifier::attributeIds)
                .containsExactly(tuple(true, customer, List.of(email)), tuple(true, company, List.of(bizNo)), tuple(false, overseas, List.of(region)),
                        tuple(false, overseas, List.of(regionGroup, region)));
        assertThat(subtypes.selectInheritance(f.user, overseas).identifiers()).filteredOn(i -> i.inherited() && !i.isPrimary() && i.definedEntityId().equals(customer))
                .singleElement().satisfies(i -> assertThat(i.identifierId()).isEqualTo(emailIdentifier));
        // 서브타입 문맥으로 상속 속성을 고치는 요청 : 거절 (정의 엔터티에서만)
        error(() -> logical.saveAttribute(f.user, new LogicalModelDto.Attribute(no, overseas, "고객 번호 2", null, null, false, null, null, null, null, null)),
                ErrorCode.INVALID_LOGICAL_MODEL, "정의한 엔터티에서");
        assertThat(jdbc.queryForObject("select ATRB_NM from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", String.class, no)).isEqualTo("고객 번호");

        // 이름 충돌 : 서브타입에 조상 이름, 슈퍼타입에 후손 이름, 슈퍼타입 속성 이름을 후손 이름으로 바꾸기 (대소문자 무시) — 모두 거절
        error(() -> attr(f, overseas, "고객명"), ErrorCode.SAVED_DATA_EXISTS);
        error(() -> attr(f, overseas, "사업자등록번호"), ErrorCode.SAVED_DATA_EXISTS);
        error(() -> attr(f, customer, "국가 코드"), ErrorCode.SAVED_DATA_EXISTS);
        error(() -> logical.saveAttribute(f.user, new LogicalModelDto.Attribute(name, customer, "생년월일", null, null, false, null, null, null, null, null)), ErrorCode.SAVED_DATA_EXISTS);
        // 형제 서브타입끼리는 같은 이름이어도 된다
        attr(f, person, "등급");
        attr(f, company, "등급");
        attr(f, local, "지역");
        attr(f, overseas, "지역");
        // 연결할 때의 충돌 : 슈퍼타입과 같은 이름의 속성이 있는 엔터티, 자체 주 식별자가 있는 엔터티
        long dup = entity(f, "중복 고객");
        attr(f, dup, "고객명");
        long other = entity(f, "기타 고객");
        error(() -> group(f, customer, "충돌", dup, other), ErrorCode.SAVED_DATA_EXISTS);
        long keyed = entity(f, "키 있는 고객");
        pk(f, keyed, attr(f, keyed, "키"));
        error(() -> group(f, customer, "키", keyed, other), ErrorCode.INVALID_LOGICAL_MODEL, "자체 주 식별자");
        assertThat(count("select count(*) from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ? and BSC_YN = true", keyed)).as("자체 주 식별자를 지우지 않는다").isOne();

        // 루트 주 식별자 없는 상속 : 설계 중에는 허용, 표시
        long root = entity(f, "키 없는 루트");
        long c1 = entity(f, "하위 1");
        long c2 = entity(f, "하위 2");
        group(f, root, "키 없음", c1, c2);
        assertThat(subtypes.selectInheritance(f.user, c1).rootPrimaryKeyMissing()).isTrue();
        assertThat(subtypes.selectInheritance(f.user, overseas).rootPrimaryKeyMissing()).isFalse();
    }

    // ------------------------------------------------------------------ 분류 속성

    @Test
    @DisplayName("분류 속성 : 직접·직접 상속·간접 상속 속성 지정, 관련 없는 엔터티·다른 모델 속성 거절, 배타 그룹 분류 값 중복 거절(중복 가능 그룹은 허용), 분류 속성 삭제·무효화 거절")
    void classification() {
        GenerationFixture f = fixture("st-clsf");
        long customer = entity(f, "고객");
        long rootType = attr(f, customer, "고객 유형 코드");
        long company = entity(f, "기업 고객");
        long companyType = attr(f, company, "기업 유형 코드");
        long person = entity(f, "개인 고객");
        var type = save(f, null, customer, "고객 유형", false, true, rootType, members(company, "CORPORATE", person, "INDIVIDUAL"), null);
        assertThat(type.classificationAttributeId()).isEqualTo(rootType);
        assertThat(type.subtypes()).extracting(LogicalSubtypeDto.Member::classificationValue).containsExactly("CORPORATE", "INDIVIDUAL");
        // 직접 상속(기업 유형 코드 — 슈퍼타입 자기 속성) · 간접 상속(고객 유형 코드 — 조상 속성)
        long big = entity(f, "대기업");
        long small = entity(f, "중소기업");
        var size = save(f, null, company, "규모", false, true, companyType, members(big, " BIG ", small, "SMALL"), null);
        assertThat(size.subtypes()).extracting(LogicalSubtypeDto.Member::classificationValue).as("앞뒤 공백을 뺀다").containsExactly("BIG", "SMALL");
        long g1 = entity(f, "그룹 1");
        long g2 = entity(f, "그룹 2");
        var inherited = save(f, null, company, "상속 분류", true, false, rootType, of(g1, g2), null);
        assertThat(inherited.classificationAttributeEntityId()).isEqualTo(customer);

        // 관련 없는 엔터티(형제)의 속성, 다른 모델의 속성
        long personAttr = attr(f, person, "생년월일");
        long a = entity(f, "A");
        long b = entity(f, "B");
        error(() -> save(f, null, company, "형제 속성", false, true, personAttr, of(a, b), null), ErrorCode.INVALID_LOGICAL_MODEL, "유효 속성");
        GenerationFixture other = f.otherModel("다른 모델 " + System.nanoTime());
        long foreignAttr = attr(other, entity(other, "다른 엔터티"), "다른 속성");
        error(() -> save(f, null, company, "다른 모델 속성", false, true, foreignAttr, of(a, b), null), ErrorCode.INVALID_LOGICAL_MODEL, "유효 속성");
        // 배타 그룹은 분류 값이 겹치면 안 된다 (대소문자·공백 무시), 중복 가능 그룹은 된다
        long g3 = entity(f, "그룹 3");
        error(() -> save(f, null, b, "배타 중복", false, true, null, members(a, "X", g3, " x "), null), ErrorCode.INVALID_LOGICAL_MODEL, "겹칠 수 없습니다");
        long o1 = entity(f, "O1");
        long o2 = entity(f, "O2");
        save(f, null, a, "중복 가능", true, true, null, members(o1, "X", o2, "X"), null);

        // 분류 속성은 지울 수 없다, 분류 속성이 무효가 되는 상속 변경(기업 고객을 고객에서 빼기 → 상속 분류의 고객 유형 코드가 사라진다)도 거절
        error(() -> logical.deleteAttribute(f.user, rootType), ErrorCode.RELATION_EXISTS);
        long newcomer = entity(f, "신규");
        error(() -> members(f, type, members(person, "INDIVIDUAL", newcomer, "NEW")), ErrorCode.RELATION_EXISTS, "분류 속성");
        // 분류 속성을 바꾸면 된다
        LogicalSubtypeDto.Group now = subtypes.selectGroup(f.user, inherited.id());
        save(f, now.id(), company, now.groupName(), true, false, companyType, of(now.subtypes().get(0).entityId(), now.subtypes().get(1).entityId()), now.modifiedAt());
        members(f, type, members(person, "INDIVIDUAL", newcomer, "NEW")); // 이제 기업 고객 해제 가능 (속성 짝·분류 속성 참조 없음)
        assertThat(subtypes.selectInheritance(f.user, company).level()).isOne();
    }

    // ------------------------------------------------------------------ 관계

    @Test
    @DisplayName("관계 : 슈퍼타입 관계를 서브타입에서 상속 관계로 조회(복제 없음), 서브타입이 부모인 관계의 짝은 상속 주 식별자(복합 포함), 관련 없는 속성 거절, 짝이 있으면 상속 해제·변경 거절")
    void relationships() {
        GenerationFixture f = fixture("st-rel");
        long customer = entity(f, "고객");
        long no = attr(f, customer, "고객 번호");
        long branch = attr(f, customer, "지점 코드");
        pk(f, customer, no, branch);
        long order = entity(f, "주문");
        long orderCustomer = attr(f, order, "주문 고객 번호");
        long orderBranch = attr(f, order, "주문 지점 코드");
        var placed = f.rel("주문함", customer, order, false, 1);
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 유형", company, person);
        int relationships = count("select count(*) from TB_LGCL_REL where LGCL_MDL_SN = ?", f.modelId);

        var inheritance = subtypes.selectInheritance(f.user, company);
        assertThat(inheritance.inheritedRelationships()).extracting(r -> r.relationship().id(), LogicalSubtypeDto.InheritedRelationship::definedEntityId,
                LogicalSubtypeDto.InheritedRelationship::role).containsExactly(tuple(placed.id(), customer, "SOURCE"));
        assertThat(logical.selectEntityRelationships(f.user, company)).as("직접 관계 목록은 그대로").isEmpty();
        assertThat(count("select count(*) from TB_LGCL_REL where LGCL_MDL_SN = ?", f.modelId)).as("관계를 복제하지 않는다").isEqualTo(relationships);

        // 서브타입이 부모 : 기업 고객 → 기업 계약, 짝의 부모 속성은 고객의 주 식별자 (복합, 순서 그대로)
        long contract = entity(f, "기업 계약");
        long contractNo = attr(f, contract, "계약 고객 번호");
        long contractBranch = attr(f, contract, "계약 지점 코드");
        var signed = f.rel("계약함", company, contract, false, 1);
        var candidates = logical.selectRelationshipPairs(f.user, signed.id());
        assertThat(candidates.parentCandidates()).extracting(LogicalModelDto.PairCandidate::attributeId).containsExactly(no, branch);
        f.pairs(signed, new long[]{no, contractNo}, new long[]{branch, contractBranch});
        assertThat(logical.selectRelationshipPairs(f.user, signed.id()).state().status()).isEqualTo("COMPLETE");
        assertThat(logical.selectEntityRelationships(f.user, company)).singleElement().satisfies(r -> assertThat(r.attributePairs().status()).isEqualTo("COMPLETE"));
        // 관련 없는 속성(자식의 속성을 부모 자리에, 다른 엔터티의 비식별 속성)
        error(() -> f.pairs(signed, new long[]{contractNo, contractBranch}), ErrorCode.INVALID_LOGICAL_MODEL);
        error(() -> f.pairs(signed, new long[]{orderCustomer, contractNo}), ErrorCode.INVALID_LOGICAL_MODEL);
        // 일반 엔터티 사이 관계는 그대로
        f.pairs(placed, new long[]{no, orderCustomer}, new long[]{branch, orderBranch});
        assertThat(logical.selectRelationshipPairs(f.user, placed.id()).state().status()).isEqualTo("COMPLETE");

        // 짝이 있는 동안 기업 고객의 상속을 풀거나 바꿀 수 없다
        var groupNow = subtypes.selectGroupRows(f.user, f.modelId, null, 0, 10, null).getContent().getFirst();
        long newcomer = entity(f, "신규 고객");
        error(() -> members(f, subtypes.selectGroup(f.user, groupNow.id()), of(person, newcomer)), ErrorCode.RELATION_EXISTS, "속성 짝");
        error(() -> deleteGroup(f, subtypes.selectGroup(f.user, groupNow.id())), ErrorCode.RELATION_EXISTS);
        long otherRoot = entity(f, "다른 루트");
        pk(f, otherRoot, attr(f, otherRoot, "다른 번호"));
        error(() -> save(f, groupNow.id(), otherRoot, "고객 유형", false, true, null, of(company, person), subtypes.selectGroup(f.user, groupNow.id()).modifiedAt()), ErrorCode.RELATION_EXISTS);
        // 짝을 지우면 해제된다
        logical.saveRelationshipPairs(f.user, signed.id(), new LogicalModelDto.PairSave(logical.selectRelationshipPairs(f.user, signed.id()).modifiedAt(), List.of()));
        deleteGroup(f, subtypes.selectGroup(f.user, groupNow.id()));
        assertThat(subtypes.selectInheritance(f.user, company).inheritedRelationships()).isEmpty();
    }

    // ------------------------------------------------------------------ 승인

    @Test
    @DisplayName("승인 : 승인된 엔터티의 상속 변경 거절(관리자도), 조상 승인 뒤에만 서브타입 승인·루트 주 식별자 필요, 승인된 하위가 있으면 상위 승인 취소·속성·식별자·관계 변경 거절, 주제 영역 분류는 허용")
    void approval() {
        GenerationFixture f = fixture("st-approval");
        long customer = entity(f, "고객");
        long no = attr(f, customer, "고객 번호");
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        var type = group(f, customer, "고객 유형", company, person);
        long overseas = entity(f, "해외 기업 고객");
        long local = entity(f, "국내 기업 고객");
        group(f, company, "기업 구분", overseas, local);

        // 승인 순서 : 루트 주 식별자 없음 → 거절, 미승인 조상 → 거절, 자동 승인 없음
        logical.updateEntityApproval(f.user, customer, true);
        error(() -> logical.updateEntityApproval(f.user, company, true), ErrorCode.INVALID_LOGICAL_MODEL, "주 식별자");
        logical.updateEntityApproval(f.user, customer, false);
        pk(f, customer, no);
        error(() -> logical.updateEntityApproval(f.user, company, true), ErrorCode.INVALID_LOGICAL_MODEL, "승인되지 않아");
        logical.updateEntityApproval(f.user, customer, true);
        error(() -> logical.updateEntityApproval(f.user, overseas, true), ErrorCode.INVALID_LOGICAL_MODEL, "승인되지 않아");
        logical.updateEntityApproval(f.user, company, true);
        logical.updateEntityApproval(f.user, overseas, true);
        assertThat(count("select count(*) from TB_LGCL_ENTT where LGCL_ENTT_SN in (?, ?) and APRV_YN = true", person, local)).as("다른 엔터티를 함께 승인하지 않는다").isZero();

        // 승인 취소 : 승인된 하위(자식·손자)가 있으면 거절
        error(() -> logical.updateEntityApproval(f.user, customer, false), ErrorCode.INVALID_LOGICAL_MODEL, "승인된 하위 엔터티");
        error(() -> logical.updateEntityApproval(f.user, company, false), ErrorCode.INVALID_LOGICAL_MODEL);

        // 승인된 하위에 영향을 주는 변경 : 관리자(승인 권한자)라도 거절
        error(() -> attr(f, customer, "신규 속성"), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE, "승인된 하위 엔터티");
        error(() -> logical.saveAttribute(f.user, new LogicalModelDto.Attribute(no, customer, "고객 번호 변경", null, null, false, null, null, null, null, null)),
                ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        error(() -> logical.deleteIdentifier(f.user, jdbc.queryForObject("select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ?", Long.class, customer)),
                ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        long order = entity(f, "주문");
        error(() -> f.rel("주문함", customer, order, false, 1), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        error(() -> f.rel("주문함", order, company, false, 1), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        // 상속 구조 변경 : 승인된 엔터티가 걸리면 누구든 거절
        long newcomer = entity(f, "신규");
        error(() -> members(f, type, of(company, person, newcomer)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE, "상속 정의");
        error(() -> deleteGroup(f, type), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        long approvedOther = f.entity("승인된 엔터티"); // 관리자 등록 = 승인
        long root = entity(f, "미승인 루트");
        error(() -> group(f, root, "승인 연결", approvedOther, newcomer), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        // 직접 영향이 없는 변경 : 승인된 하위가 없는 서브타입(개인 고객)의 속성, 주제 영역 분류
        attr(f, person, "생년월일");
        var area = areas.save(f.user, new com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Save(null, f.modelId, null, "영역", null, null, null));
        var nodes = tools.jackson.databind.node.JsonNodeFactory.instance;
        areas.assign(f.user, f.modelId, new com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Assignment(nodes.numberNode(area.id()),
                List.of(new com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.AssignmentItem(overseas, nodes.nullNode()))));

        // 하위부터 승인 취소하면 위도 취소된다
        logical.updateEntityApproval(f.user, overseas, false);
        logical.updateEntityApproval(f.user, company, false);
        logical.updateEntityApproval(f.user, customer, false);
        attr(f, customer, "신규 속성");
    }

    // ------------------------------------------------------------------ 삭제

    @Test
    @DisplayName("삭제 : 슈퍼타입·서브타입 엔터티 삭제 거절(상속 먼저 해제, 자동 연쇄 없음), 그룹 삭제 뒤 엔터티 삭제 가능")
    void deletion() {
        GenerationFixture f = fixture("st-delete");
        long customer = entity(f, "고객");
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        var type = group(f, customer, "고객 유형", company, person);
        error(() -> logical.deleteEntity(f.user, customer), ErrorCode.RELATION_EXISTS, "슈퍼타입");
        error(() -> logical.deleteEntity(f.user, company), ErrorCode.RELATION_EXISTS, "서브타입");
        assertThat(count("select count(*) from TB_LGCL_SBTYP where LGCL_SBTYP_GROUP_SN = ?", type.id())).isEqualTo(2);
        deleteGroup(f, type);
        logical.deleteEntity(f.user, company);
        logical.deleteEntity(f.user, customer);
        assertThat(count("select count(*) from TB_LGCL_ENTT where LGCL_ENTT_SN = ?", person)).isOne();
    }

    // ------------------------------------------------------------------ 논리 ERD

    @Test
    @DisplayName("논리 ERD : 범위 안 그룹·범위 밖 상속 연결 수(일반 관계와 따로)·상속 주 식별자, 슈퍼타입·서브타입만 범위인 경우, 이웃 탐색이 상속 연결을 한 단계로")
    void erd() {
        GenerationFixture f = fixture("st-erd");
        long customer = entity(f, "고객");
        long no = attr(f, customer, "고객 번호");
        pk(f, customer, no);
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        long overseas = entity(f, "해외 기업 고객");
        long local = entity(f, "국내 기업 고객");
        save(f, null, customer, "고객 유형", false, true, null, members(company, "C", person, "P"), null);
        save(f, null, company, "기업 구분", true, false, null, of(overseas, local), null);
        long order = entity(f, "주문");
        f.rel("주문함", customer, order, false, 1);

        // 전체 : 그룹 2개, 범위 밖 없음, 서브타입 노드에 상속 주 식별자
        var all = logical.selectGraphPart(f.user, f.modelId, null, 0, 100);
        assertThat(all.subtypeGroups()).extracting(LogicalModelDto.GraphSubtypeGroup::groupName, LogicalModelDto.GraphSubtypeGroup::overlapping,
                LogicalModelDto.GraphSubtypeGroup::total, LogicalModelDto.GraphSubtypeGroup::subtypeCount).containsExactlyInAnyOrder(tuple("고객 유형", false, true, 2), tuple("기업 구분", true, false, 2));
        assertThat(all.outsideInheritances()).isEmpty();
        assertThat(all.entities()).filteredOn(e -> e.id().equals(overseas)).singleElement().satisfies(e -> {
            assertThat(e.supertypeEntityId()).isEqualTo(company);
            assertThat(e.inheritedKey()).extracting(LogicalModelDto.InheritedKey::attributeId, LogicalModelDto.InheritedKey::definedEntityName).containsExactly(tuple(no, "고객"));
        });
        assertThat(all.entities()).filteredOn(e -> e.id().equals(customer)).singleElement().satisfies(e -> assertThat(e.inheritedKey()).isEmpty());
        assertThat(all.relationships()).as("상속은 일반 관계선이 아니다").hasSize(1);

        // 서브타입만 범위 ("해외") : 노드를 더하지 않고 범위 밖 상속 수만
        var overseasOnly = logical.selectGraphPart(f.user, f.modelId, "해외", 0, 100);
        assertThat(overseasOnly.entities()).extracting(LogicalModelDto.GraphEntity::id).containsExactly(overseas);
        assertThat(overseasOnly.subtypeGroups()).isEmpty();
        assertThat(overseasOnly.outsideInheritances()).containsExactly(Map.entry(overseas, 1));
        assertThat(overseasOnly.entities().getFirst().inheritedKey()).as("루트가 범위 밖이어도 상속 주 식별자").extracting(LogicalModelDto.InheritedKey::name).containsExactly("고객 번호");
        // 슈퍼타입 + 일부 서브타입 ("기업") : 기업 고객·해외 기업 고객·국내 기업 고객 → 기업 구분 그룹만, 기업 고객은 위(고객)가 범위 밖
        var companies = logical.selectGraphPart(f.user, f.modelId, "기업", 0, 100);
        assertThat(companies.subtypeGroups()).extracting(LogicalModelDto.GraphSubtypeGroup::groupName).containsExactly("기업 구분");
        assertThat(companies.outsideInheritances()).containsExactly(Map.entry(company, 1));
        // 이웃 : 해외 기업 고객에서 1단계 = 기업 고객(상속), 2단계 = 그 위(고객)·옆(국내 기업 고객), 3단계 = 개인 고객(상속)·주문(일반 관계)
        assertThat(logical.selectNeighbors(f.user, f.modelId, overseas, 1).entities()).extracting(LogicalModelDto.GraphEntity::id).containsExactlyInAnyOrder(overseas, company);
        assertThat(logical.selectNeighbors(f.user, f.modelId, overseas, 2).entities()).extracting(LogicalModelDto.GraphEntity::id)
                .containsExactlyInAnyOrder(overseas, company, customer, local);
        assertThat(logical.selectNeighbors(f.user, f.modelId, overseas, 3).entities()).extracting(LogicalModelDto.GraphEntity::id)
                .containsExactlyInAnyOrder(overseas, company, customer, local, person, order);
    }

    // ------------------------------------------------------------------ 물리 모델

    @Test
    @DisplayName("물리 생성 : 상속 참여 엔터티는 B형(M-7 2차 계획·3차 반영) — 기존 루트 테이블 재사용, 미리보기 뒤 상속에 연결되면 409, 매핑된 엔터티 연결 허용·매핑 보존·정합성 표시")
    void physicalGeneration() {
        GenerationFixture f = fixture("st-physical");
        long employee = entity(f, "직원");
        long employeeNo = f.attr(employee, "직원 번호", true, "EMP_NO");
        f.pk(employee, employeeNo);
        long team = entity(f, "팀");
        f.attr(team, "팀 이름", false, "TEAM_NM");
        long dept = entity(f, "부서");
        f.attr(dept, "부서 이름", false, "DEPT_NM");
        long product = entity(f, "상품");
        f.pk(product, f.attr(product, "상품 번호", true, "PRDT_NO"));

        // 일반 엔터티일 때 직원 테이블을 먼저 만든다 (매핑이 있는 엔터티를 나중에 슈퍼타입으로 연결)
        var first = generation.preview(f.user, f.request(List.of(employee)));
        generation.apply(f.user, f.request(List.of(employee), first.planToken()));
        long employeeTable = f.table(employee);
        int employeeColumns = f.count("select count(*) from TB_COL where TBL_SN = ?", employeeTable);
        var employeeSearch = new com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search(f.modelId, employee, null, null, null, null);
        List<String> employeeRowsBefore = quality.selectRows(f.user, employeeSearch, org.springframework.data.domain.PageRequest.of(0, 100)).getContent().stream()
                .map(row -> row.id() + ":" + row.status()).toList();

        // 미리보기 (상품만 일반 엔터티) → 그 뒤 상품을 상속에 연결 → 같은 토큰으로 반영하면 409
        var stale = generation.preview(f.user, f.request(List.of(product, team)));
        assertThat(stale.creatable()).isTrue();
        group(f, employee, "직원 구분", team, dept);
        error(() -> generation.apply(f.user, f.request(List.of(product, team), stale.planToken())), ErrorCode.MODEL_CONFLICT);

        // 지금 미리보기 : 직원(루트, 이미 매핑)은 SKIP, 팀·부서(서브타입)는 B형 CREATE, 상품은 CREATE — 3차부터 반영된다 (기존 루트 테이블을 참조하는 상속 FK)
        var plan = generation.preview(f.user, f.request(List.of(employee, team, dept, product)));
        assertThat(plan.tables()).extracting(PhysicalGenerationDto.TablePlan::entityId, PhysicalGenerationDto.TablePlan::status)
                .containsExactlyInAnyOrder(tuple(employee, "SKIP"), tuple(team, "CREATE"), tuple(dept, "CREATE"), tuple(product, "CREATE"));
        assertThat(plan.tables()).filteredOn(t -> t.entityId().equals(team)).singleElement()
                .satisfies(t -> assertThat(t.inheritance().role()).isEqualTo("SUBTYPE"));
        assertThat(plan.creatable()).isTrue();
        assertThat(plan.executable()).isTrue();
        assertThat(plan.executionBlockedReason()).isNull();
        var result = generation.apply(f.user, f.request(List.of(employee, team, dept, product), plan.planToken()));
        assertThat(result.tables()).isEqualTo(3);
        assertThat(result.inheritanceForeignKeys()).isEqualTo(2);
        assertThat(result.inheritedKeyMappings()).isEqualTo(2);
        assertThat(f.count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.LGCL_ENTT_SN in (?, ?) and s.LGCL_ATRB_SN = ?",
                team, dept, employeeNo)).as("서브타입마다 상속 PK 매핑 — 원본은 루트 주 식별자 속성").isEqualTo(2);
        assertThat(f.count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN in (?, ?) and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK'", f.table(team), f.table(dept), employeeTable))
                .as("상속 FK 는 기존 루트 테이블로").isEqualTo(2);
        // 매핑 보존 : 직원 매핑·테이블·컬럼 그대로
        assertThat(f.table(employee)).isEqualTo(employeeTable);
        assertThat(f.count("select count(*) from TB_COL where TBL_SN = ?", employeeTable)).isEqualTo(employeeColumns);
        // 정합성 : 직접 정의 속성의 판정은 그대로, 상속 구조 참여 표시만
        assertThat(quality.selectSummary(f.user, f.modelId).inheritanceEntities()).isEqualTo(3);
        var rows = quality.selectRows(f.user, employeeSearch, org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
        assertThat(rows).isNotEmpty().allMatch(com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row::inheritance);
        assertThat(rows.stream().map(row -> row.id() + ":" + row.status()).toList()).as("직접 정의 속성의 판정은 바뀌지 않는다 (MISMATCH 로 바꾸지 않는다)").isEqualTo(employeeRowsBefore);
        var productSearch = new com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search(f.modelId, product, null, null, null, null);
        assertThat(quality.selectRows(f.user, productSearch, org.springframework.data.domain.PageRequest.of(0, 100)).getContent()).noneMatch(com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row::inheritance);
    }

    // ------------------------------------------------------------------ 이력·버전

    @Test
    @DisplayName("이력·버전 : 그룹 등록·수정(정책·구성원 배열)·삭제 이력, 실패·같은 값 저장은 이력 없음, 상속 속성 이력 없음. 버전 형식 8 에 그룹·구성원, 비교")
    void historyAndVersions() {
        GenerationFixture f = fixture("st-history");
        AccountDto member = f.member();
        long customer = entity(f, "고객");
        long typeCode = attr(f, customer, "고객 유형 코드");
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        ModelVersionDto.Summary before = versions.create(member, new ModelVersionDto.Create("st-before-" + System.nanoTime(), null));
        Long project = f.user.getProjectId();
        int attributeHistory = count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'LOGICAL_ATTRIBUTE'", project);
        var type = save(f, null, customer, "고객 유형", false, true, typeCode, members(company, "C", person, "P"), null);
        // 같은 내용 재저장 → 이력 없음, 실패 → 이력 없음
        LogicalSubtypeDto.Group now = subtypes.selectGroup(f.user, type.id());
        save(f, now.id(), customer, "고객 유형", false, true, typeCode, members(company, "C", person, "P"), now.modifiedAt());
        error(() -> members(f, type, of(company)), ErrorCode.INVALID_LOGICAL_MODEL);
        // 정책·구성원 변경
        long vip = entity(f, "우수 고객");
        now = subtypes.selectGroup(f.user, type.id());
        save(f, now.id(), customer, "고객 유형", true, false, typeCode, members(company, "C", person, "P", vip, "V"), now.modifiedAt());
        assertThat(jdbc.queryForList("select CHG_TYPE_CD from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'SUBTYPE_GROUP' order by MDL_CHG_HSTRY_SN", String.class, project))
                .containsExactly("CREATE", "UPDATE");
        String update = jdbc.queryForObject("select CHG_BFR_CN || ' → ' || CHG_AFTR_CN from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'SUBTYPE_GROUP' and CHG_TYPE_CD = 'UPDATE'",
                String.class, project);
        assertThat(update).contains("\"overlapping\":false", "\"overlapping\":true", "\"subtypeEntityName\":\"우수 고객\"", "\"classificationValue\":\"V\"",
                "\"classificationAttributeName\":\"고객 유형 코드\"", "\"supertypeEntityName\":\"고객\"");
        assertThat(jdbc.queryForObject("select TRGT_NM from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'SUBTYPE_GROUP' and CHG_TYPE_CD = 'CREATE'", String.class, project))
                .isEqualTo("고객 / 고객 유형");
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'LOGICAL_ATTRIBUTE'", project)).as("상속 속성 이력 없음").isEqualTo(attributeHistory);

        ModelVersionDto.Summary after = versions.create(member, new ModelVersionDto.Create("st-after-" + System.nanoTime(), null));
        assertThat(after.formatVersion()).isEqualTo(10);
        assertThat(after.counts().get("SUBTYPE_GROUP").asInt()).isOne();
        // 과거 버전의 독립성 : 버전을 만든 뒤 상속 구조·원본 속성·식별자를 바꿔도 저장된 버전 내용(상속 정의 포함)은 그대로
        String stored = jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, after.id());
        assertThat(stored).contains("\"groupName\":\"고객 유형\"", "\"subtypeEntityName\":\"우수 고객\"", "\"classificationAttributeName\":\"고객 유형 코드\"");
        pk(f, customer, attr(f, customer, "고객 번호"));
        logical.saveAttribute(f.user, new LogicalModelDto.Attribute(typeCode, customer, "고객 구분 코드", null, null, false, null, null, null, null, null));
        now = subtypes.selectGroup(f.user, type.id());
        save(f, now.id(), customer, "고객 유형 변경", false, true, null, members(company, "C", person, "P"), now.modifiedAt());
        assertThat(jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, after.id())).as("버전 행 그대로").isEqualTo(stored);
        assertThat(versions.compare(member, after.id(), null).changes()).extracting(ModelVersionDto.Change::targetType, ModelVersionDto.Change::change)
                .as("현재와 비교하면 바뀐 것만").contains(tuple("SUBTYPE_GROUP", "MODIFIED"), tuple("LOGICAL_ATTRIBUTE", "MODIFIED"), tuple("LOGICAL_IDENTIFIER", "ADDED"));
        assertThat(versions.compare(member, before.id(), after.id()).changes()).extracting(ModelVersionDto.Change::targetType, ModelVersionDto.Change::change)
                .as("두 과거 버전의 비교는 현재 모델과 무관 (그 사이 등록한 우수 고객·그룹만)").containsExactlyInAnyOrder(tuple("LOGICAL_ENTITY", "ADDED"), tuple("SUBTYPE_GROUP", "ADDED"));
        assertThat(versions.compare(member, before.id(), after.id()).changes()).extracting(ModelVersionDto.Change::targetType, ModelVersionDto.Change::change)
                .contains(tuple("SUBTYPE_GROUP", "ADDED"));
        deleteGroup(f, type);
        assertThat(versions.compare(member, after.id(), null).changes()).extracting(ModelVersionDto.Change::targetType, ModelVersionDto.Change::change)
                .contains(tuple("SUBTYPE_GROUP", "REMOVED")).doesNotContain(tuple("SUBTYPE_GROUP", "MODIFIED"));
        assertThat(jdbc.queryForList("select CHG_TYPE_CD from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'SUBTYPE_GROUP' order by MDL_CHG_HSTRY_SN", String.class, project))
                .containsExactly("CREATE", "UPDATE", "UPDATE", "DELETE");
    }

    // ------------------------------------------------------------------ 교환 형식 5

    @Test
    @DisplayName("교환 형식 5 : 그룹·구성원·분류 속성·상속 주 식별자 짝을 새 ID 로 옮기고 왕복해도 같다. 형식 4 파일은 그룹 없이, 잘못된 상속은 파일 전체 거절")
    void exchangeFormat5() {
        GenerationFixture f = fixture("st-export");
        long customer = entity(f, "고객");
        long no = attr(f, customer, "고객 번호");
        pk(f, customer, no);
        long typeCode = attr(f, customer, "고객 유형 코드");
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        save(f, null, customer, "고객 유형", false, true, typeCode, members(company, "C", person, "P"), null);
        long overseas = entity(f, "해외 기업 고객");
        long local = entity(f, "국내 기업 고객");
        save(f, null, company, "기업 구분", true, false, typeCode, of(overseas, local), null);
        long contract = entity(f, "기업 계약");
        long contractNo = attr(f, contract, "계약 고객 번호");
        var signed = f.rel("계약함", company, contract, false, 1);
        f.pairs(signed, new long[]{no, contractNo});
        long keyed = entity(f, "키 엔터티");
        pk(f, keyed, attr(f, keyed, "키"));
        long dupName = entity(f, "이름 겹침");
        attr(f, dupName, "고객 번호");
        long plain = entity(f, "평범");
        AccountDto source = f.member();
        String modelName = jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, f.modelId);

        ModelExchangeDto.Document document = exchange.export(source);
        assertThat(document.formatVersion()).isEqualTo(7);
        assertThat(document.model().get("SUBTYPE_GROUP")).hasSize(2);

        GenerationFixture targetFixture = fixture("st-import");
        AccountDto target = targetFixture.member();
        Long targetProject = target.getProjectId();
        ModelExchangeDto.Selection selection = new ModelExchangeDto.Selection(List.of(), List.of(modelName), List.of());
        var plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(targetProject, document, selection));
        assertThat(plan.counts()).containsEntry("SUBTYPE_GROUP", 2);
        var result = exchange.apply(target, new ModelExchangeDto.ApplyRequest(targetProject, document, selection, plan.planToken(), plan.requestId()));
        assertThat(result.created()).containsEntry("SUBTYPE_GROUP", 2).containsEntry("LOGICAL_SUBTYPE", 4);
        assertThat(jdbc.queryForList("select s.ENTT_NM || '>' || g.SBTYP_GROUP_NM || '>' || e.ENTT_NM || ':' || coalesce(m.CLSF_VL, '-') || ':' || a.ATRB_NM || ':' || ae.ENTT_NM"
                        + " from TB_LGCL_SBTYP m join TB_LGCL_SBTYP_GROUP g on g.LGCL_SBTYP_GROUP_SN = m.LGCL_SBTYP_GROUP_SN join TB_LGCL_ENTT s on s.LGCL_ENTT_SN = g.SPRTYP_ENTT_SN"
                        + " join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.SBTYP_ENTT_SN join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = g.CLSF_ATRB_SN join TB_LGCL_ENTT ae on ae.LGCL_ENTT_SN = a.LGCL_ENTT_SN"
                        + " where m.PRJ_SN = ?", String.class, targetProject))
                .as("DB 정렬 규칙과 무관하게 비교").containsExactlyInAnyOrder("고객>고객 유형>개인 고객:P:고객 유형 코드:고객", "고객>고객 유형>기업 고객:C:고객 유형 코드:고객",
                        "기업 고객>기업 구분>국내 기업 고객:-:고객 유형 코드:고객", "기업 고객>기업 구분>해외 기업 고객:-:고객 유형 코드:고객");
        assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP g join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = g.SPRTYP_ENTT_SN where g.PRJ_SN = ? and e.PRJ_SN = ?",
                targetProject, targetProject)).as("새 ID 로 다시 잇는다").isEqualTo(2);
        // 상속 주 식별자 짝 : 새 고객 번호 속성 ↔ 새 계약 고객 번호
        assertThat(jdbc.queryForList("select pa.ATRB_NM || '@' || pe.ENTT_NM || '>' || ca.ATRB_NM from TB_LGCL_REL_ATRB p join TB_LGCL_REL r on r.LGCL_REL_SN = p.LGCL_REL_SN"
                        + " join TB_LGCL_ATRB pa on pa.LGCL_ATRB_SN = p.ORGNL_ATRB_SN join TB_LGCL_ENTT pe on pe.LGCL_ENTT_SN = pa.LGCL_ENTT_SN join TB_LGCL_ATRB ca on ca.LGCL_ATRB_SN = p.TRGT_ATRB_SN"
                        + " where r.PRJ_SN = ? and pe.PRJ_SN = ?", String.class, targetProject, targetProject)).containsExactly("고객 번호@고객>계약 고객 번호");
        // 왕복
        ModelExchangeDto.Document again = exchange.export(target);
        assertThat(shape(again)).isEqualTo(shape(document));

        // 형식 4 파일 : 그룹 없이 가져온다
        GenerationFixture oldFixture = fixture("st-import-old");
        AccountDto oldTarget = oldFixture.member();
        ModelExchangeDto.Document format4 = new ModelExchangeDto.Document(document.format(), 4, document.exportedAt(), document.dbType(), document.counts(),
                withoutGroups(document), null);
        // 형식 4 에는 상속 주 식별자 짝이 있을 수 없다 (서브타입이 없으면 부모 주 식별자가 아니다) — 짝을 뺀 관계로
        var oldPlan = exchange.plan(oldTarget, new ModelExchangeDto.PlanRequest(oldTarget.getProjectId(), withoutPairs(format4), selection));
        exchange.apply(oldTarget, new ModelExchangeDto.ApplyRequest(oldTarget.getProjectId(), withoutPairs(format4), selection, oldPlan.planToken(), oldPlan.requestId()));
        assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where PRJ_SN = ?", oldTarget.getProjectId())).isZero();
        assertThat(count("select count(*) from TB_LGCL_ENTT where PRJ_SN = ?", oldTarget.getProjectId())).isEqualTo(9);

        // 거절 : 형식 4 에 그룹, 순환, 4단계, 단일 상속 위반, 구성원 1개, 없는 슈퍼타입, 다른 모델 분류 속성, 이름 충돌, 자체 주 식별자, 배타성 값, 상속 주 식별자가 아닌 짝
        AccountDto clean = fixture("st-import-bad").member();
        Long cleanProject = clean.getProjectId();
        Consumer<ModelExchangeDto.Document> rejected = bad -> assertThatThrownBy(() -> exchange.plan(clean, new ModelExchangeDto.PlanRequest(cleanProject, bad, selection)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID));
        rejected.accept(new ModelExchangeDto.Document(document.format(), 4, document.exportedAt(), document.dbType(), document.counts(), document.model(), null));
        Long typeGroup = groupId(document, "고객 유형");
        Long companyGroup = groupId(document, "기업 구분");
        rejected.accept(withGroup(document, typeGroup, "supertypeEntityId", overseas)); // 고객 유형의 슈퍼타입을 해외 기업 고객으로 → 기업 고객 ↔ 해외 기업 고객 순환
        rejected.accept(withGroup(document, typeGroup, "supertypeEntityId", 999_999L)); // 없는 슈퍼타입
        rejected.accept(withGroup(document, typeGroup, "overlapping", "Y")); // 배타성 값
        rejected.accept(withGroup(document, typeGroup, "total", null)); // 완전성 없음
        rejected.accept(withGroup(document, companyGroup, "classificationAttributeId", contractNo)); // 슈퍼타입의 유효 속성이 아님
        rejected.accept(withGroup(document, companyGroup, "subtypes", List.of(Map.of("subtypeEntityId", overseas, "sortOrder", 1)))); // 1개
        rejected.accept(withGroup(document, companyGroup, "subtypes", List.of(Map.of("subtypeEntityId", person, "sortOrder", 1), Map.of("subtypeEntityId", local, "sortOrder", 2)))); // 단일 상속
        rejected.accept(withGroup(document, companyGroup, "subtypes", List.of(Map.of("subtypeEntityId", keyed, "sortOrder", 1), Map.of("subtypeEntityId", local, "sortOrder", 2)))); // 자체 주 식별자
        rejected.accept(withGroup(document, companyGroup, "subtypes", List.of(Map.of("subtypeEntityId", dupName, "sortOrder", 1), Map.of("subtypeEntityId", local, "sortOrder", 2)))); // 상속 속성명 충돌
        // 4단계 : 해외 기업 고객(3단계) 아래에 그룹 추가
        Map<String, Object> deep = new LinkedHashMap<>(groupItem(document, companyGroup).state());
        deep.put("supertypeEntityId", overseas);
        deep.put("classificationAttributeId", null);
        deep.put("subtypes", List.of(Map.of("subtypeEntityId", contract, "sortOrder", 1), Map.of("subtypeEntityId", plain, "sortOrder", 2)));
        rejected.accept(addGroup(document, new ModelExchangeDto.Item(888_888L, null, deep)));
        // 형식 5 인데 그룹을 빼면 기업 고객이 부모인 짝(고객 번호)이 상속 주 식별자가 아니다
        rejected.accept(new ModelExchangeDto.Document(document.format(), 5, document.exportedAt(), document.dbType(), document.counts(), withoutGroups(document), null));
        assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where PRJ_SN = ?", cleanProject)).isZero();
    }

    /** 파일의 상속 구조 (ID 없이) */
    private static List<String> shape(ModelExchangeDto.Document document) {
        Map<Long, String> entities = new LinkedHashMap<>();
        document.model().get("LOGICAL_ENTITY").forEach(e -> entities.put(e.id(), (String) e.state().get("entityName")));
        List<String> shape = new ArrayList<>();
        for (ModelExchangeDto.Item g : document.model().get("SUBTYPE_GROUP")) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> subtypes = (List<Map<String, Object>>) g.state().get("subtypes");
            for (Map<String, Object> m : subtypes) {
                shape.add(entities.get(((Number) g.state().get("supertypeEntityId")).longValue()) + ">" + g.state().get("groupName") + ">" + g.state().get("overlapping") + g.state().get("total")
                        + ">" + entities.get(((Number) m.get("subtypeEntityId")).longValue()) + ":" + m.get("classificationValue") + ":" + g.state().get("classificationAttributeName"));
            }
        }
        return shape.stream().sorted().toList();
    }

    private static Long groupId(ModelExchangeDto.Document document, String name) {
        return groupItem(document, document.model().get("SUBTYPE_GROUP").stream().filter(g -> name.equals(g.state().get("groupName"))).findFirst().orElseThrow().id()).id();
    }

    private static ModelExchangeDto.Item groupItem(ModelExchangeDto.Document document, Long id) {
        return document.model().get("SUBTYPE_GROUP").stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private static ModelExchangeDto.Document withGroup(ModelExchangeDto.Document document, Long groupId, String field, Object value) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        model.put("SUBTYPE_GROUP", model.get("SUBTYPE_GROUP").stream().map(item -> {
            if (!item.id().equals(groupId)) return item;
            Map<String, Object> state = new LinkedHashMap<>(item.state());
            state.put(field, value);
            return new ModelExchangeDto.Item(item.id(), item.name(), state);
        }).toList());
        return new ModelExchangeDto.Document(document.format(), document.formatVersion(), document.exportedAt(), document.dbType(), document.counts(), model, null);
    }

    private static ModelExchangeDto.Document addGroup(ModelExchangeDto.Document document, ModelExchangeDto.Item group) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        List<ModelExchangeDto.Item> groups = new ArrayList<>(model.get("SUBTYPE_GROUP"));
        groups.add(group);
        model.put("SUBTYPE_GROUP", groups);
        return new ModelExchangeDto.Document(document.format(), document.formatVersion(), document.exportedAt(), document.dbType(), document.counts(), model, null);
    }

    private static Map<String, List<ModelExchangeDto.Item>> withoutGroups(ModelExchangeDto.Document document) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        model.remove("SUBTYPE_GROUP");
        return model;
    }

    private static ModelExchangeDto.Document withoutPairs(ModelExchangeDto.Document document) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(document.model());
        model.put("LOGICAL_RELATIONSHIP", model.get("LOGICAL_RELATIONSHIP").stream().map(item -> {
            Map<String, Object> state = new LinkedHashMap<>(item.state());
            state.put("attributes", List.of());
            return new ModelExchangeDto.Item(item.id(), item.name(), state);
        }).toList());
        return new ModelExchangeDto.Document(document.format(), document.formatVersion(), document.exportedAt(), document.dbType(), document.counts(), model, null);
    }

    // ------------------------------------------------------------------ 동시성 (실제 독립 트랜잭션)

    /** 첫 트랜잭션이 모델 행을 잠근 채(작업을 마치고 커밋 전) 둘째 요청을 시작한다 : 둘째는 잠금을 기다렸다가 첫째가 커밋한 상태에서 검사한다 */
    private String raced(Runnable first, Runnable second) throws Exception {
        return raced(first, second, true);
    }

    /** waits = false : 둘째 요청이 기다리지 않고(NOWAIT 잠금) 첫째가 잡고 있는 동안 끝나야 한다 */
    private String raced(Runnable first, Runnable second, boolean waits) throws Exception {
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
        if (waits) {
            Thread.sleep(300);
            assertThat(waiter).as("둘째 요청은 모델 행 잠금을 기다린다").isNotDone();
        } else {
            String early = waiter.get(10, TimeUnit.SECONDS); // 첫째 트랜잭션이 아직 열려 있다
            assertThat(holder).as("첫째는 아직 끝나지 않았다").isNotDone();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return early;
        }
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        return waiter.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("동시성 : 서로를 상속하는 동시 등록(순환), 같은 서브타입에 다른 슈퍼타입, 오래된 그룹 수정, 연결 ↔ 이름 충돌 속성, 연결 ↔ 엔터티 삭제, 해제 ↔ 속성 짝, 연결 ↔ 승인, 상위 속성 ↔ 하위 승인 — 잘못된 구조가 남지 않는다")
    void concurrency() throws Exception {
        GenerationFixture f = fixture("st-race");
        long a = entity(f, "A");
        long b = entity(f, "B");
        long a1 = entity(f, "A1");
        long b1 = entity(f, "B1");
        // A ⊃ {B, A1} 와 B ⊃ {A, B1} 을 동시에 : 둘째는 순환으로 거절
        assertThat(raced(() -> group(f, a, "A 그룹", b, a1), () -> group(f, b, "B 그룹", a, b1))).isEqualTo("INVALID_LOGICAL_MODEL");
        assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where LGCL_MDL_SN = ? and SPRTYP_ENTT_SN = ?", f.modelId, b)).isZero();
        // 같은 서브타입(X)을 서로 다른 슈퍼타입에 동시에
        long p = entity(f, "P");
        long q = entity(f, "Q");
        long x = entity(f, "X");
        long p1 = entity(f, "P1");
        long q1 = entity(f, "Q1");
        assertThat(raced(() -> group(f, p, "P 그룹", x, p1), () -> group(f, q, "Q 그룹", x, q1))).isEqualTo("INVALID_LOGICAL_MODEL");
        assertThat(count("select count(*) from TB_LGCL_SBTYP where SBTYP_ENTT_SN = ?", x)).isOne();
        // 오래된 그룹 수정 : 같은 기준값으로 둘
        var pg = subtypes.selectGroup(f.user, jdbc.queryForObject("select LGCL_SBTYP_GROUP_SN from TB_LGCL_SBTYP_GROUP where SPRTYP_ENTT_SN = ?", Long.class, p));
        long p2 = entity(f, "P2");
        long p3 = entity(f, "P3");
        assertThat(raced(() -> save(f, pg.id(), p, "P 그룹", false, true, null, of(x, p1, p2), pg.modifiedAt()),
                () -> save(f, pg.id(), p, "P 그룹", false, true, null, of(x, p1, p3), pg.modifiedAt()))).isEqualTo("MODEL_CONFLICT");
        // 연결 ↔ 이름이 겹치는 속성 등록 : 연결이 먼저면 속성 등록이 거절
        long s = entity(f, "S");
        attr(f, s, "공통");
        long s1 = entity(f, "S1");
        long s2 = entity(f, "S2");
        assertThat(raced(() -> group(f, s, "S 그룹", s1, s2), () -> attr(f, s1, "공통"))).isEqualTo("SAVED_DATA_EXISTS");
        // 연결 ↔ 엔터티 삭제 : 삭제가 먼저면 연결은 찾을 수 없음
        long t = entity(f, "T");
        long t1 = entity(f, "T1");
        long t2 = entity(f, "T2");
        assertThat(raced(() -> logical.deleteEntity(f.user, t2), () -> group(f, t, "T 그룹", t1, t2))).isEqualTo("LOGICAL_MODEL_IS_NOT_DEFINED");
        // 해제 ↔ 속성 짝 : 짝이 먼저 생기면 해제는 거절
        long u = entity(f, "U");
        long uKey = attr(f, u, "U 번호");
        pk(f, u, uKey);
        long u1 = entity(f, "U1");
        long u2 = entity(f, "U2");
        long u3 = entity(f, "U3");
        var ug = group(f, u, "U 그룹", u1, u2);
        long child = entity(f, "U 자식");
        long childRef = attr(f, child, "U 참조");
        var rel = f.rel("U1 관계", u1, child, false, 1);
        LocalDateTime relBase = logical.selectRelationshipPairs(f.user, rel.id()).modifiedAt();
        LocalDateTime groupBase = subtypes.selectGroup(f.user, ug.id()).modifiedAt();
        assertThat(raced(() -> logical.saveRelationshipPairs(f.user, rel.id(), new LogicalModelDto.PairSave(relBase, List.of(new LogicalModelDto.PairInput(uKey, childRef))))
                , () -> save(f, ug.id(), u, "U 그룹", false, true, null, of(u2, u3), groupBase))).isEqualTo("RELATION_EXISTS");
        // 연결 ↔ 승인 : 승인이 먼저면 연결 거절
        long v = entity(f, "V");
        long v1 = entity(f, "V1");
        long v2 = entity(f, "V2");
        assertThat(raced(() -> logical.updateEntityApproval(f.user, v1, true), () -> group(f, v, "V 그룹", v1, v2))).isEqualTo("APPROVED_DATA_CANNOT_BE_UPDATE");
        // 상위 속성 추가 ↔ 하위 승인 : 하위 승인이 먼저면 상위 속성 추가 거절
        long w = entity(f, "W");
        pk(f, w, attr(f, w, "W 번호"));
        long w1 = entity(f, "W1");
        long w2 = entity(f, "W2");
        group(f, w, "W 그룹", w1, w2);
        logical.updateEntityApproval(f.user, w, true);
        assertThat(raced(() -> logical.updateEntityApproval(f.user, w1, true), () -> attr(f, w, "W 새 속성"))).isEqualTo("APPROVED_DATA_CANNOT_BE_UPDATE");
        // 최종 구조 : 순환·이중 슈퍼타입 없음
        for (long id : new long[]{a, b, x, s1, t1}) {
            assertThat(subtypes.selectInheritance(f.user, id).level()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("동시성 (최종 보완) : 상속 연결 ↔ 물리 생성 반영(NOWAIT 409, 뒤의 옛 토큰 409, 반대 순서는 기다린 뒤 연결), 그룹 삭제 ↔ 상속 주 식별자 속성 짝·상속 분류 속성 — 잘못된 참조가 남지 않고 실패한 쪽은 아무것도 바꾸지 않는다")
    void concurrencyFinal() throws Exception {
        GenerationFixture f = fixture("st-race2");
        // (1) 상속 연결이 모델 행을 잡고 있는 동안 물리 생성 반영 : 기다리지 않고 409, 풀린 뒤 같은 토큰도 409 (계획이 UNSUPPORTED 로 바뀜), 테이블 없음
        long sup = entity(f, "직원");
        f.pk(sup, f.attr(sup, "생성 번호", true, "GEN_NO"));
        long m1 = entity(f, "배송");
        f.attr(m1, "하위1 이름", false, "SUB1_NM");
        long m2 = entity(f, "결제");
        long plain = entity(f, "상품"); // 함께 만들 일반 엔터티 (테이블명은 표준 단어로)
        f.pk(plain, f.attr(plain, "독립 번호", true, "SOLO_NO"));
        var plan = generation.preview(f.user, f.request(List.of(plain, m1)));
        assertThat(plan.creatable()).as(plan.toString()).isTrue();
        int history = count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", f.user.getProjectId());
        assertThat(raced(() -> group(f, sup, "생성 그룹", m1, m2), () -> generation.apply(f.user, f.request(List.of(plain, m1), plan.planToken())), false)).isEqualTo("MODEL_CONFLICT");
        error(() -> generation.apply(f.user, f.request(List.of(plain, m1), plan.planToken())), ErrorCode.MODEL_CONFLICT);
        assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", plain)).as("409 는 아무것도 만들지 않는다").isZero();
        assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", m1)).isZero();
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_TABLE'", f.user.getProjectId())).isZero();
        assertThat(count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", f.user.getProjectId())).as("그룹 등록 이력만").isEqualTo(history + 1);
        // (2) 반대 순서 : 생성 반영이 먼저 잡으면 연결은 기다렸다가 진행 — 막 만든 테이블에 매핑된 엔터티를 슈퍼타입으로 연결 (D-48), 매핑·테이블 그대로
        long n1 = entity(f, "지점");
        f.pk(n1, f.attr(n1, "일반 번호", true, "PLAIN_NO"));
        long n2 = entity(f, "공항");
        long n3 = entity(f, "항공편");
        var plan2 = generation.preview(f.user, f.request(List.of(n1)));
        assertThat(plan2.creatable()).isTrue();
        assertThat(raced(() -> generation.apply(f.user, f.request(List.of(n1), plan2.planToken())), () -> group(f, n1, "생성 그룹2", n2, n3))).isEqualTo("OK");
        assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", n1)).isOne();
        assertThat(subtypes.selectInheritance(f.user, n2).parent().groupName()).isEqualTo("생성 그룹2");

        // (3) 그룹 삭제 ↔ 상속 주 식별자 속성 짝 : 삭제가 먼저면 짝은 서브타입의 (없는) 주 식별자가 아니라 거절, 짝 없음
        long a = entity(f, "삭제 슈퍼");
        long aKey = attr(f, a, "삭제 번호");
        pk(f, a, aKey);
        long a1 = entity(f, "삭제 하위1");
        long a2 = entity(f, "삭제 하위2");
        var ag = group(f, a, "삭제 그룹", a1, a2);
        long child = entity(f, "삭제 자식");
        long childRef = attr(f, child, "삭제 참조");
        var rel = f.rel("하위1 관계", a1, child, false, 1);
        LocalDateTime relBase = logical.selectRelationshipPairs(f.user, rel.id()).modifiedAt();
        LocalDateTime groupBase = subtypes.selectGroup(f.user, ag.id()).modifiedAt();
        assertThat(raced(() -> subtypes.deleteGroup(f.user, ag.id(), groupBase),
                () -> logical.saveRelationshipPairs(f.user, rel.id(), new LogicalModelDto.PairSave(relBase, List.of(new LogicalModelDto.PairInput(aKey, childRef)))))).isEqualTo("INVALID_LOGICAL_MODEL");
        assertThat(count("select count(*) from TB_LGCL_REL_ATRB where LGCL_REL_SN = ?", rel.id())).isZero();
        // (4) 그룹 삭제 ↔ 상속 분류 속성을 쓰는 하위 그룹 등록 : 삭제가 먼저면 분류 속성이 유효 속성이 아니라 거절, 그룹 없음
        long b = entity(f, "분류 슈퍼");
        long code = attr(f, b, "분류 코드");
        long b1 = entity(f, "분류 하위1");
        long b2 = entity(f, "분류 하위2");
        var bg = group(f, b, "분류 그룹", b1, b2);
        long c1 = entity(f, "손자1");
        long c2 = entity(f, "손자2");
        LocalDateTime bBase = subtypes.selectGroup(f.user, bg.id()).modifiedAt();
        assertThat(raced(() -> subtypes.deleteGroup(f.user, bg.id(), bBase),
                () -> save(f, null, b1, "손자 그룹", false, true, code, of(c1, c2), null))).isEqualTo("INVALID_LOGICAL_MODEL");
        assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where SPRTYP_ENTT_SN = ?", b1)).isZero();
        // 반대 순서 : 하위 그룹이 먼저면 상위 그룹 삭제는 분류 속성을 무효화하므로 거절, 둘 다 남는다
        var bg2 = group(f, b, "분류 그룹 다시", b1, b2);
        LocalDateTime bBase2 = subtypes.selectGroup(f.user, bg2.id()).modifiedAt();
        assertThat(raced(() -> save(f, null, b1, "손자 그룹", false, true, code, of(c1, c2), null),
                () -> subtypes.deleteGroup(f.user, bg2.id(), bBase2))).isEqualTo("RELATION_EXISTS");
        assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where SPRTYP_ENTT_SN in (?, ?)", b, b1)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 권한·격리

    @Test
    @DisplayName("권한 : VIEWER 는 조회만, USER·DBA·MANAGER·ADMIN 은 그룹 관리 (@PreAuthorize). 기준값 없는 수정·삭제 400, 다른 프로젝트의 그룹·엔터티는 찾을 수 없음")
    void permissions() {
        GenerationFixture f = fixture("st-roles");
        long customer = entity(f, "고객");
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        var type = group(f, customer, "고객 유형", company, person);
        Long project = f.user.getProjectId();
        int i = 0;
        for (String role : List.of("ROLE_VIEWER", "ROLE_USER", "ROLE_DBA", "ROLE_MANAGER", "ROLE_ADMIN")) {
            AccountDto user = SecurityTestSupport.loginAs(project, f.user.getId(), role);
            assertThat(subtypeController.selectGroupRows(user, f.modelId, 0, 10, null, null).getBody()).containsKey("data");
            assertThat(subtypeController.selectGroup(user, type.id()).getBody().subtypes()).hasSize(2);
            assertThat(subtypeController.selectInheritance(user, company).getBody().level()).isEqualTo(2);
            assertThat(subtypeController.selectEffectiveAttributes(user, company).getBody().entityId()).isEqualTo(company);
            long s = entity(f, "슈퍼 " + i);
            long m1 = entity(f, "구성 " + i + "-1");
            long m2 = entity(f, "구성 " + i + "-2");
            i++;
            Runnable create = () -> subtypeController.saveGroup(user, new Save(null, f.modelId, s, "그룹", null, false, true, null, of(m1, m2), null));
            Runnable remove = () -> subtypeController.deleteGroup(user, 987_654_321L, LocalDateTime.now());
            if (role.equals("ROLE_VIEWER")) {
                assertThatThrownBy(create::run).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(remove::run).isInstanceOf(AccessDeniedException.class);
            } else {
                create.run();
                error(remove, ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
            }
        }
        AccountDto admin = SecurityTestSupport.loginAs(project, f.user.getId(), "ROLE_ADMIN");
        error(() -> subtypeController.saveGroup(admin, new Save(type.id(), f.modelId, customer, "고객 유형", null, false, true, null, of(company, person), null)), ErrorCode.MODEL_BASE_REQUIRED);
        error(() -> subtypeController.deleteGroup(admin, type.id(), null), ErrorCode.MODEL_BASE_REQUIRED);
        // 정렬·페이지 검사 (공용 목록 정책)
        error(() -> subtypeController.selectGroupRows(admin, f.modelId, 0, 101, null, null), ErrorCode.INVALID_PAGE_REQUEST);
        error(() -> subtypeController.selectGroupRows(admin, f.modelId, 0, 10, "supertypeEntity.projectId,asc", null), ErrorCode.INVALID_PAGE_REQUEST);
        for (String sort : List.of("groupName,asc", "supertypeEntityName,desc", "modifiedAt,desc")) {
            assertThat(subtypeController.selectGroupRows(admin, f.modelId, 0, 10, sort, null).getBody()).containsKey("data");
        }
        assertThat(subtypes.selectGroupRows(admin, f.modelId, "기업", 0, 10, null).getContent()).isEmpty();
        assertThat(subtypes.selectGroupRows(admin, f.modelId, "고객 유", 0, 10, null).getContent()).singleElement()
                .satisfies(r -> assertThat(r.subtypeCount()).isEqualTo(2));
        // 다른 프로젝트 : 그룹·엔터티·모델 모두 찾을 수 없음
        GenerationFixture stranger = fixture("st-roles-other");
        error(() -> subtypes.selectGroup(stranger.user, type.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> subtypes.selectInheritance(stranger.user, company), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> subtypes.selectGroupRows(stranger.user, f.modelId, null, 0, 10, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> subtypes.deleteGroup(stranger.user, type.id(), LocalDateTime.now()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> subtypes.saveGroup(stranger.user, new Save(null, stranger.modelId, customer, "남의 것", null, false, true, null, of(company, person), null)),
                ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where LGCL_SBTYP_GROUP_SN = ?", type.id())).isOne();
    }
}
