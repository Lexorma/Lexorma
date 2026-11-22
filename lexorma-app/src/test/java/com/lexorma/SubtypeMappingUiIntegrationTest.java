package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.controller.ModelMappingRestController;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Plan;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.service.PhysicalModelService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-7 4차 : 상속 PK 매핑 화면과 정합성 비교가 쓰는 서버 응답 (docs/completed/subtype-physical-transformation.md 31장).
 * 목록의 상속 역할·상속 PK 현황, 상속 PK 매핑 상세의 구조 점검(기존 부모 재사용 판정과 같은 결과), 정합성 비교의 상속 구조 행·현황, 생성 미리보기의 조상 쓰임,
 * 조회 수가 행 수에 비례하지 않는지, 역할별 권한. 판정은 서버가 하고 화면은 다시 계산하지 않으므로 서버 응답끼리 서로 어긋나지 않는지를 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class SubtypeMappingUiIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingService mappings;
    @Autowired ModelMappingQualityService quality;
    @Autowired ModelMappingRestController controller;
    @Autowired EntityTableMappingRepository entityMappings;
    @Autowired PhysicalModelService physical;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private static final Set<String> BLOCKING = Set.of("ERROR", "MISSING", "UNKNOWN");

    private long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private LogicalSubtypeDto.Group group(GenerationFixture f, long supertype, String name, boolean overlapping, boolean total, long... members) {
        List<MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new MemberInput(m, null));
        return subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, overlapping, total, null, list, null));
    }

    private void generate(GenerationFixture f, Long... entities) {
        Plan plan = generation.preview(f.user, f.request(List.of(entities)));
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(t -> t.errors().stream()).toList())).isTrue();
        generation.apply(f.user, f.request(List.of(entities), plan.planToken()));
    }

    private long mappingId(long entity) {
        return entityMappings.findByEntity_Id(entity).orElseThrow().getId();
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private Map<Long, ModelMappingDto.EntityRow> rows(GenerationFixture f) {
        Map<Long, ModelMappingDto.EntityRow> rows = new HashMap<>();
        mappings.selectEntityRows(f.user, new ModelMappingDto.EntitySearch(f.modelId, null, null, null, null), PageRequest.of(0, 100))
                .forEach(r -> rows.put(r.entityId(), r));
        return rows;
    }

    private ModelMappingDto.SubtypeKeyDetail keys(GenerationFixture f, long entity) {
        return mappings.selectSubtypeKeys(f.user, mappingId(entity));
    }

    private List<ModelMappingQualityDto.Row> inheritanceRows(GenerationFixture f, Long entity) {
        return quality.selectRows(f.user, new ModelMappingQualityDto.Search(f.modelId, entity, ModelMappingQualityDto.CheckType.INHERITANCE, null, null, null),
                PageRequest.of(0, 100)).getContent();
    }

    private static Map<String, String> levels(ModelMappingDto.SubtypeKeyDetail detail) {
        Map<String, String> result = new HashMap<>();
        detail.structure().checks().forEach(c -> result.put(c.code(), c.level()));
        return result;
    }

    /**
     * 한 서브타입의 서버 응답이 서로 어긋나지 않는다 : 상세의 판정(= 기존 부모 재사용 판정) VALID ⇔ 막는 점검(ERROR·MISSING·UNKNOWN) 없음,
     * 목록 행의 status·result = 상세, 정합성 비교의 구조 행 상태 = 상세의 result, (child 가 있으면) 그 자식 미리보기가 이 엔터티를 유효한 기존 부모로 쓰는지 = VALID
     */
    private ModelMappingDto.SubtypeKeyDetail consistent(GenerationFixture f, long entity, Long child) {
        ModelMappingDto.SubtypeKeyDetail detail = keys(f, entity);
        boolean blocked = detail.structure().checks().stream().anyMatch(c -> BLOCKING.contains(c.level()));
        assertThat(blocked).as("판정 %s ↔ 점검 %s", detail.status(), detail.structure().checks()).isEqualTo(!"VALID".equals(detail.status()));
        ModelMappingDto.Inheritance row = rows(f).get(entity).inheritance();
        assertThat(row.status()).isEqualTo(detail.status());
        assertThat(row.result()).isEqualTo(detail.structure().result());
        assertThat(inheritanceRows(f, entity).stream().filter(r -> r.attributeId() == null).map(r -> r.status().name()).toList())
                .containsExactly(detail.structure().result());
        if (child != null) {
            Plan plan = generation.preview(f.user, f.request(List.of(child)));
            PhysicalGenerationDto.AncestorUse use = plan.ancestors().stream().filter(a -> a.entityId().equals(entity)).findFirst().orElseThrow();
            assertThat(use.state()).as("미리보기의 기존 부모 판정").isEqualTo("VALID".equals(detail.status()) ? "REUSE" : "REVIEW");
            if (!"VALID".equals(detail.status())) assertThat(use.mappingId()).isEqualTo(mappingId(entity));
        }
        return detail;
    }

    private void expect(GenerationFixture f, long entity, Long child, String status, String result, Consumer<ModelMappingDto.SubtypeKeyDetail> more) {
        ModelMappingDto.SubtypeKeyDetail detail = consistent(f, entity, child);
        assertThat(detail.status()).isEqualTo(status);
        assertThat(detail.structure().result()).isEqualTo(result);
        more.accept(detail);
    }

    @Test
    @DisplayName("목록 : 역할(루트·중간·말단·일반, 여러 그룹의 슈퍼타입), 상속 PK 연결 수는 직접 속성 수와 따로(자기 속성 없는 서브타입 포함), 매핑 안 된 서브타입은 판정 없음")
    void listRoles() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ui-list");
        long customer = entity(f, "고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        long company = entity(f, "기업 고객");
        f.attr(company, "사업자 번호", false, "BIZ_NO");
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 구분", false, true, company, person);
        long a = entity(f, "갑");
        long b = entity(f, "을");
        group(f, customer, "고객 구분2", true, false, a, b);
        long party = entity(f, "당사자");
        f.pk(party, f.attr(party, "당사자 번호", true, "PTY_NO"), f.attr(party, "기관 코드", true, "ORG_CD"));
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);
        long product = entity(f, "상품");
        f.pk(product, f.attr(product, "상품 번호", true, "PRDT_NO"));
        generate(f, customer, company, person, party, org, human, product);

        Map<Long, ModelMappingDto.EntityRow> rows = rows(f);
        assertThat(rows.get(product).inheritance()).as("일반 엔터티").isNull();
        assertThat(rows.get(customer).inheritance()).satisfies(i -> {
            assertThat(i.role()).as("그룹이 둘이어도 역할은 루트 하나").isEqualTo("ROOT");
            assertThat(i.supertype()).isTrue();
            assertThat(i.subtype()).isFalse();
            assertThat(i.requiredKeys()).as("루트는 상속 PK 대상이 아니다").isZero();
            assertThat(i.result()).isNull();
        });
        assertThat(rows.get(org).inheritance()).satisfies(i -> {
            assertThat(i.role()).isEqualTo("INTERMEDIATE");
            assertThat(i.supertype()).isTrue();
            assertThat(i.subtype()).isTrue();
            assertThat(i.parentEntityId()).isEqualTo(party);
            assertThat(i.rootEntityId()).isEqualTo(party);
            assertThat(i.requiredKeys()).isEqualTo(2);
            assertThat(i.mappedKeys()).isEqualTo(2);
            assertThat(i.status()).isEqualTo("VALID");
            assertThat(i.result()).isEqualTo("MATCH");
        });
        assertThat(rows.get(person)).satisfies(r -> {
            assertThat(r.attributeCount()).as("자기 속성 없는 서브타입 : 직접 속성 0").isZero();
            assertThat(r.mappedAttributeCount()).isZero();
            assertThat(r.inheritance().role()).isEqualTo("SUBTYPE");
            assertThat(r.inheritance().mappedKeys()).isEqualTo(1);
            assertThat(r.inheritance().requiredKeys()).isEqualTo(1);
        });
        assertThat(rows.get(company)).satisfies(r -> {
            assertThat(r.attributeCount()).as("상속 PK 는 직접 속성 수에 들어가지 않는다").isOne();
            assertThat(r.mappedAttributeCount()).isOne();
            assertThat(r.inheritance().mappedKeys()).isOne();
        });
        assertThat(rows.get(dept).inheritance()).satisfies(i -> {
            assertThat(i.role()).isEqualTo("SUBTYPE");
            assertThat(i.requiredKeys()).isEqualTo(2);
            assertThat(i.mappedKeys()).isZero();
            assertThat(i.status()).as("매핑 없는 엔터티는 판정하지 않는다").isNull();
        });
        // 직접 속성 매핑률 : 상속 PK 는 분자·분모에 들어가지 않는다 (속성 = 엔터티에 직접 정의한 행)
        ModelMappingDto.Status status = mappings.selectStatus(f.user, f.modelId).getFirst();
        assertThat(status.totalAttributes()).isEqualTo(f.count("select count(*) from TB_LGCL_ATRB a join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = a.LGCL_ENTT_SN where e.LGCL_MDL_SN = ?", f.modelId));
        assertThat(status.mappedAttributes()).isEqualTo(f.count("select count(*) from TB_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where m.PRJ_SN = ?", f.user.getProjectId()));
        // 상세 응답의 매핑 행에도 같은 상속 요약
        assertThat(mappings.selectEntityMapping(f.user, mappingId(org)).mapping().inheritance()).isEqualTo(rows.get(org).inheritance());
        // 물리 미보장 규칙은 구조 판정을 바꾸지 않고 안내만 한다
        ModelMappingDto.SubtypeKeyDetail corp = keys(f, company);
        assertThat(corp.structure().result()).isEqualTo("MATCH");
        assertThat(corp.structure().constraintNote()).contains("Disjoint").contains("Total");
        assertThat(keys(f, org).structure().constraintNote()).isNull();
        // 다른 프로젝트 : 목록은 비고, 상세는 거절
        GenerationFixture other = GenerationFixture.create(context, "ui-list-other-" + System.nanoTime(), com.lexorma.core.project.domain.enums.DbType.POSTGRESQL);
        assertThat(mappings.selectEntityRows(other.user, new ModelMappingDto.EntitySearch(f.modelId, null, null, null, null), PageRequest.of(0, 100))).isEmpty();
        assertThatThrownBy(() -> mappings.selectSubtypeKeys(other.user, mappingId(org)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED));
        assertThatThrownBy(() -> quality.selectSummary(other.user, f.modelId))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED));
    }

    @Test
    @DisplayName("구조 점검 = 기존 부모 재사용 판정 : 정상·PK 순서·길이 차이(참고)·PK 아님·NULL 허용·타입 불일치·상위 경로·부모 FK 없음·다른 테이블 참조·복합 FK 일부 누락·판단 정보 부족 — 상세·목록·정합성 비교·미리보기가 같다")
    void structureMatchesJudge() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ui-structure");
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        long orgCd = f.attr(party, "기관 코드", true, "ORG_CD");
        f.pk(party, partyNo, orgCd);
        long org = entity(f, "조직");
        f.attr(org, "조직 이름", false, "ORG_NM");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);
        long customer = entity(f, "고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 구분", true, false, company, person);
        long product = entity(f, "상품");
        f.pk(product, f.attr(product, "상품 번호", true, "PRDT_NO"));
        generate(f, party, org, human, dept, customer, company, product);
        long orgTable = f.table(org);
        long orgPtyNo = column(orgTable, "PTY_NO");
        long orgOrgCd = column(orgTable, "ORG_CD");

        // 정상 : 모든 점검 OK, 테이블 PK·상위 키·상속 FK 하나
        expect(f, org, team, "VALID", "MATCH", d -> {
            assertThat(levels(d).values()).containsOnly("OK");
            assertThat(levels(d).keySet()).contains("KEY_COMPLETE", "COLUMN_OWNER", "PK_SET", "PK_ORDER", "NOT_NULL", "PARENT_TABLE", "ANCESTOR_PATH", "KEY_TYPE", "PARENT_FK");
            assertThat(d.structure().tableKey()).extracting(ModelMappingDto.KeyColumn::columnName).containsExactly("PTY_NO", "ORG_CD");
            assertThat(d.structure().parentKey()).extracting(ModelMappingDto.KeyColumn::columnName).containsExactly("PTY_NO", "ORG_CD");
            assertThat(d.structure().foreignKeys()).singleElement().satisfies(fk -> {
                assertThat(fk.parentLink()).isTrue();
                assertThat(fk.childColumns()).containsExactly("PTY_NO", "ORG_CD");
            });
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::check).containsExactly("OK", "OK");
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::attributeEntityName).containsOnly("당사자");
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::attributeDataType).containsOnly("VARCHAR(20)");
        });
        expect(f, dept, null, "VALID", "MATCH", d -> assertThat(d.parentEntityId()).isEqualTo(org));

        // PK 순서만 다름 : 판정은 그대로 (상속 FK 는 식별자 순서로 짝짓는다) — 참고로만 보인다
        jdbc.update("update TB_COL set SORT_ORD = 9 where COL_SN = ?", orgPtyNo);
        expect(f, org, team, "VALID", "MATCH", d -> assertThat(levels(d)).containsEntry("PK_ORDER", "WARNING"));
        jdbc.update("update TB_COL set SORT_ORD = 1 where COL_SN = ?", orgPtyNo);
        // 길이만 다름 : FK 로 묶일 수 있다 — 참고
        jdbc.update("update TB_COL set MAX_LEN = 30 where COL_SN = ?", orgPtyNo);
        expect(f, org, team, "VALID", "MATCH", d -> {
            assertThat(levels(d)).containsEntry("KEY_LENGTH", "WARNING");
            assertThat(d.keys().getFirst().check()).isEqualTo("LENGTH_DIFFERS");
        });
        jdbc.update("update TB_COL set MAX_LEN = 20 where COL_SN = ?", orgPtyNo);
        // PK 아님 + NULL 허용 : PK 구성 불일치 · NOT NULL 불일치, 손자(부서)는 상위 경로 불일치
        jdbc.update("update TB_COL set PK_YN = false, NULL_ALWNC_YN = true, ESNTL_YN = false where COL_SN = ?", orgOrgCd);
        expect(f, org, team, "PARENT_KEY_MISMATCH", "MISMATCH", d -> {
            assertThat(levels(d)).containsEntry("PK_SET", "ERROR").containsEntry("NOT_NULL", "ERROR");
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::check).containsExactly("OK", "NOT_PK");
        });
        expect(f, dept, null, "ANCESTOR_INVALID", "MISMATCH", d -> {
            assertThat(levels(d)).containsEntry("ANCESTOR_PATH", "ERROR").containsEntry("KEY_TYPE", "UNKNOWN");
            assertThat(d.structure().parentKey()).as("상위가 유효하지 않으면 상위 키를 정하지 않는다").isEmpty();
        });
        jdbc.update("update TB_COL set PK_YN = true, NULL_ALWNC_YN = false where COL_SN = ?", orgOrgCd);
        // 타입 불일치 (FK 로 묶일 수 없음)
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'DATE', MAX_LEN = null where COL_SN = ?", orgPtyNo);
        expect(f, org, team, "INHERITED_KEY_TYPE_MISMATCH", "MISMATCH", d -> {
            assertThat(levels(d)).containsEntry("KEY_TYPE", "ERROR");
            assertThat(d.keys().getFirst().check()).isEqualTo("TYPE_MISMATCH");
        });
        // PK 아님 + 타입 불일치 : 키 표시는 첫 문제(PK 아님)지만 타입 점검은 따로 불일치로 남는다
        jdbc.update("update TB_COL set PK_YN = false where COL_SN = ?", orgPtyNo);
        expect(f, org, team, "PARENT_KEY_MISMATCH", "MISMATCH", d -> {
            assertThat(levels(d)).containsEntry("PK_SET", "ERROR").containsEntry("KEY_TYPE", "ERROR");
            assertThat(d.keys().getFirst().check()).isEqualTo("NOT_PK");
        });
        jdbc.update("update TB_COL set PK_YN = true, DATA_TYPE_NM = 'VARCHAR', MAX_LEN = 20 where COL_SN = ?", orgPtyNo);
        expect(f, org, team, "VALID", "MATCH", d -> { });

        // 단일 상속 FK : 다른 테이블 참조 → FK 없음
        long corpTable = f.table(company);
        long corpKey = column(corpTable, "CUST_NO");
        String productTable = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, f.table(product));
        jdbc.update("update TB_COL set RFRNC_TBL_NM = ? where COL_SN = ?", productTable, corpKey);
        tx.executeWithoutResult(s -> physical.syncReferenceRelations(f.user.getProjectId(), List.of(corpTable)));
        expect(f, company, null, "INHERITANCE_FK_MISSING", "MISMATCH", d -> {
            assertThat(levels(d)).containsEntry("PARENT_FK", "ERROR");
            assertThat(d.structure().checks().stream().filter(c -> c.code().equals("PARENT_FK")).findFirst().orElseThrow().message()).contains(productTable);
            assertThat(d.structure().foreignKeys()).singleElement().satisfies(fk -> assertThat(fk.parentLink()).isFalse());
        });
        jdbc.update("update TB_COL set RFRNC_TBL_NM = null where COL_SN = ?", corpKey);
        tx.executeWithoutResult(s -> physical.syncReferenceRelations(f.user.getProjectId(), List.of(corpTable)));
        expect(f, company, null, "INHERITANCE_FK_MISSING", "MISMATCH", d -> assertThat(d.structure().foreignKeys()).isEmpty());

        // 복합 상속 FK 의 컬럼 쌍 하나 누락
        jdbc.update("delete from TB_TBL_REL_COL where TRGT_COL_SN = ? and REL_SN in (select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ?)",
                column(f.table(party), "ORG_CD"), orgTable);
        expect(f, org, team, "INHERITANCE_FK_MISSING", "MISMATCH", d -> {
            assertThat(levels(d)).containsEntry("PARENT_FK", "ERROR");
            assertThat(d.structure().foreignKeys()).singleElement().satisfies(fk -> assertThat(fk.childColumns()).containsExactly("PTY_NO"));
        });

        // 판단 정보 부족 : 직계 상위가 테이블에 매핑되지 않음 (상속 PK 는 완성)
        long root = entity(f, "루트");
        long rootNo = f.attr(root, "루트 번호", true, "ROOT_NO");
        f.pk(root, rootNo);
        long foreign = entity(f, "외국");
        long foreignNo = f.attr(foreign, "외국 번호", false, "FRGN_NO");
        long other = entity(f, "을");
        generate(f, foreign);
        group(f, root, "루트 구분", true, false, foreign, other);
        long foreignColumn = column(f.table(foreign), "FRGN_NO");
        mappings.deleteAttributeMapping(f.user, jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, foreignColumn));
        mappings.saveSubtypeKeyMapping(f.user, new ModelMappingDto.SubtypeKeyMapping(null, mappingId(foreign), rootNo, foreignColumn));
        jdbc.update("update TB_COL set PK_YN = true, NULL_ALWNC_YN = false where COL_SN = ?", foreignColumn);
        expect(f, foreign, null, "ANCESTOR_INVALID", "NOT_COMPARABLE", d -> {
            assertThat(levels(d)).containsEntry("PARENT_TABLE", "UNKNOWN").containsEntry("PK_SET", "OK");
            assertThat(levels(d)).doesNotContainValue("ERROR");
        });
        assertThat(foreignNo).isPositive();
    }

    @Test
    @DisplayName("정합성 비교 : 상속 구조 행(서브타입마다 1행)·부분 매핑 UNMAPPED·유효하지 않은 기존 매핑 행(식별자 변경·상속 해제)·현황 수치(분자 ≤ 분모, 무효는 연결 수에 안 들어감)·필터 건수 = 요약")
    void qualityRowsAndSummary() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ui-quality");
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        long company = entity(f, "기업 고객");
        f.attr(company, "사업자 번호", false, "BIZ_NO");
        long person = entity(f, "개인 고객");
        LogicalSubtypeDto.Group g = group(f, customer, "고객 구분", true, false, company, person);
        long product = entity(f, "상품");
        f.pk(product, f.attr(product, "상품 번호", true, "PRDT_NO"));
        generate(f, customer, company, person, product);

        ModelMappingQualityDto.InheritanceSummary summary = quality.selectSummary(f.user, f.modelId).inheritance();
        assertThat(summary).isEqualTo(new ModelMappingQualityDto.InheritanceSummary(2, 2, 2, 2, 0, 0, 2, 0, 0, 0));
        assertThat(inheritanceRows(f, null)).hasSize(2).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(ModelMappingQualityDto.CheckStatus.MATCH);
            assertThat(r.inheritance()).isTrue();
        });
        assertThat(inheritanceRows(f, customer)).as("루트·일반 엔터티는 상속 구조 대상이 아니다").isEmpty();
        assertThat(inheritanceRows(f, product)).isEmpty();

        // 루트 주 식별자에 속성 추가 → 상속 PK 일부만 (UNMAPPED, 분자 1 ≤ 분모 2)
        long extra = f.attr(customer, "고객 구분 코드", true, "CUST_SE_CD");
        Long identifierId = jdbc.queryForObject("select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ? and BSC_YN = true", Long.class, customer);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(identifierId, customer, "식별자", true, List.of(customerNo, extra), null,
                jdbc.queryForObject("select MDFCN_DT from TB_LGCL_IDNTFR where LGCL_IDNTFR_SN = ?", LocalDateTime.class, identifierId)));
        expect(f, company, null, "INHERITED_KEY_MAPPING_MISSING", "UNMAPPED", d -> {
            assertThat(levels(d)).containsEntry("KEY_COMPLETE", "MISSING").containsEntry("PK_SET", "UNKNOWN");
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::check).containsExactly("OK", "UNMAPPED");
        });
        assertThat(quality.selectSummary(f.user, f.modelId).inheritance()).isEqualTo(new ModelMappingQualityDto.InheritanceSummary(2, 2, 4, 2, 2, 0, 0, 0, 2, 0));
        // 지금 식별자 = 새 속성만 → 기존 CUST_NO 매핑은 유효하지 않은 기존 매핑 (연결 수에 들어가지 않는다)
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(identifierId, customer, "식별자", true, List.of(extra), null,
                jdbc.queryForObject("select MDFCN_DT from TB_LGCL_IDNTFR where LGCL_IDNTFR_SN = ?", LocalDateTime.class, identifierId)));
        Map<Long, ModelMappingDto.EntityRow> rows = rows(f);
        assertThat(rows.get(company).inheritance()).satisfies(i -> {
            assertThat(i.requiredKeys()).isOne();
            assertThat(i.mappedKeys()).isZero();
            assertThat(i.invalidKeys()).isOne();
        });
        expect(f, company, null, "INHERITED_KEY_MAPPING_MISSING", "UNMAPPED", d -> {
            assertThat(levels(d)).containsEntry("STALE_MAPPING", "WARNING");
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::current, ModelMappingDto.SubtypeKeyRow::check).containsExactly(
                    tuple(true, "UNMAPPED"), tuple(false, "STALE"));
        });
        assertThat(inheritanceRows(f, company)).extracting(ModelMappingQualityDto.Row::attributeId, ModelMappingQualityDto.Row::status).containsExactly(
                tuple(null, ModelMappingQualityDto.CheckStatus.UNMAPPED),
                tuple(customerNo, ModelMappingQualityDto.CheckStatus.MISMATCH));
        // 속성명 검색 : 엔터티 단위 구조 행은 빠지고 그 속성의 기존 매핑 행만
        assertThat(quality.selectRows(f.user, new ModelMappingQualityDto.Search(f.modelId, null, ModelMappingQualityDto.CheckType.INHERITANCE, null, "attributeName", "고객 번호"),
                PageRequest.of(0, 100)).getContent()).extracting(ModelMappingQualityDto.Row::attributeId).containsOnly(customerNo);
        summary = quality.selectSummary(f.user, f.modelId).inheritance();
        assertThat(summary).isEqualTo(new ModelMappingQualityDto.InheritanceSummary(2, 2, 2, 0, 2, 2, 0, 0, 2, 0));
        assertThat(summary.mappedKeys()).isLessThanOrEqualTo(summary.requiredKeys());
        // 상태 필터 건수 = 요약 (MISMATCH 2 = 기존 매핑 행 2, UNMAPPED 2 = 구조 행 2)
        long mismatch = quality.selectRows(f.user, new ModelMappingQualityDto.Search(f.modelId, null, ModelMappingQualityDto.CheckType.INHERITANCE,
                ModelMappingQualityDto.CheckStatus.MISMATCH, null, null), PageRequest.of(0, 1)).getTotalElements();
        assertThat(mismatch).isEqualTo(summary.mismatch() + summary.invalidMappings());
        long totalMismatch = quality.selectSummary(f.user, f.modelId).mismatch();
        assertThat(quality.selectRows(f.user, new ModelMappingQualityDto.Search(f.modelId, null, null, ModelMappingQualityDto.CheckStatus.MISMATCH, null, null),
                PageRequest.of(0, 1)).getTotalElements()).as("요약 카드 수 = 그 상태 목록 수").isEqualTo(totalMismatch);
        // 상속 해제 → 남은 매핑은 모두 유효하지 않은 기존 매핑, 역할 없음
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(identifierId, customer, "식별자", true, List.of(customerNo), null,
                jdbc.queryForObject("select MDFCN_DT from TB_LGCL_IDNTFR where LGCL_IDNTFR_SN = ?", LocalDateTime.class, identifierId)));
        subtypes.deleteGroup(f.user, g.id(), subtypes.selectGroup(f.user, g.id()).modifiedAt());
        rows = rows(f);
        assertThat(rows.get(company).inheritance()).satisfies(i -> {
            assertThat(i.role()).isNull();
            assertThat(i.subtype()).isFalse();
            assertThat(i.invalidKeys()).isOne();
            assertThat(i.status()).isEqualTo("NOT_SUBTYPE");
            assertThat(i.result()).isEqualTo("MISMATCH");
        });
        assertThat(rows.get(customer).inheritance()).as("그룹이 없어진 루트는 일반 엔터티").isNull();
        ModelMappingDto.SubtypeKeyDetail detached = keys(f, company);
        assertThat(detached.subtype()).isFalse();
        assertThat(detached.structure()).isNull();
        assertThat(detached.keys()).extracting(ModelMappingDto.SubtypeKeyRow::check).containsExactly("STALE");
        assertThat(inheritanceRows(f, null)).extracting(ModelMappingQualityDto.Row::status).containsOnly(ModelMappingQualityDto.CheckStatus.MISMATCH).hasSize(2);
        assertThat(quality.selectSummary(f.user, f.modelId).inheritance()).isEqualTo(new ModelMappingQualityDto.InheritanceSummary(0, 0, 0, 0, 0, 2, 0, 0, 0, 0));
        // 해제하면 남지 않는다 (직접 속성 매핑은 그대로)
        int direct = f.count("select count(*) from TB_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where m.LGCL_ENTT_SN = ?", company);
        mappings.deleteSubtypeKeyMapping(f.user, detached.keys().getFirst().id());
        assertThat(rows(f).get(company).inheritance()).isNull();
        assertThat(f.count("select count(*) from TB_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where m.LGCL_ENTT_SN = ?", company)).isEqualTo(direct);
        // 일반 진단 회귀 : 직접 속성 진단 유형은 그대로
        assertThat(quality.selectRows(f.user, new ModelMappingQualityDto.Search(f.modelId, product, null, null, null, null), PageRequest.of(0, 100)).getContent())
                .extracting(ModelMappingQualityDto.Row::type).doesNotContain(ModelMappingQualityDto.CheckType.INHERITANCE)
                .contains(ModelMappingQualityDto.CheckType.TERM, ModelMappingQualityDto.CheckType.IDENTIFIER);
    }

    @Test
    @DisplayName("미리보기의 조상 쓰임 : 선택 필요(= requiredAncestors)·이번에 생성·기존 재사용·기존 매핑 검토 필요, 서버는 조상을 생성 대상에 더하지 않는다, 같은 조상을 여러 서브타입이 필요로 함")
    void ancestorUses() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ui-ancestors");
        long party = entity(f, "당사자");
        f.pk(party, f.attr(party, "당사자 번호", true, "PTY_NO"));
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);

        Plan alone = generation.preview(f.user, f.request(List.of(dept, team)));
        assertThat(alone.tables()).extracting(PhysicalGenerationDto.TablePlan::entityId).as("고른 것만 계획에 있다").containsExactly(dept, team);
        assertThat(alone.ancestors()).extracting(PhysicalGenerationDto.AncestorUse::entityId, PhysicalGenerationDto.AncestorUse::state).containsExactly(
                tuple(party, "MISSING"), tuple(org, "MISSING"));
        assertThat(alone.ancestors()).allSatisfy(a -> assertThat(a.requiredBy()).containsExactly(dept, team));
        assertThat(alone.requiredAncestors()).extracting(PhysicalGenerationDto.RequiredAncestor::entityId).containsExactly(party, org);
        assertThat(alone.creatable()).isFalse();
        // 화면이 '함께 선택' 으로 더한 뒤의 계획 : 조상은 이번에 생성
        Plan merged = generation.preview(f.user, f.request(List.of(dept, team, party, org)));
        assertThat(merged.ancestors()).extracting(PhysicalGenerationDto.AncestorUse::state).containsOnly("SELECTED");
        assertThat(merged.requiredAncestors()).isEmpty();
        assertThat(merged.creatable()).isTrue();
        generate(f, party, org);
        Plan reuse = generation.preview(f.user, f.request(List.of(dept)));
        assertThat(reuse.ancestors()).extracting(PhysicalGenerationDto.AncestorUse::state, PhysicalGenerationDto.AncestorUse::mappingId).containsExactly(
                tuple("REUSE", mappingId(party)), tuple("REUSE", mappingId(org)));
        assertThat(reuse.ancestors()).extracting(PhysicalGenerationDto.AncestorUse::tableName).doesNotContainNull();
        // 이미 매핑된 조상을 함께 골라도 다시 만들지 않는다 (SKIP) — 쓰임은 재사용
        Plan withMapped = generation.preview(f.user, f.request(List.of(dept, org)));
        assertThat(withMapped.tables()).filteredOn(t -> t.entityId().equals(org)).extracting(PhysicalGenerationDto.TablePlan::status).containsExactly("SKIP");
        assertThat(withMapped.ancestors()).filteredOn(a -> a.entityId().equals(org)).extracting(PhysicalGenerationDto.AncestorUse::state).containsExactly("REUSE");
        // 기존 중간 부모가 맞지 않음 → 검토 필요 (함께 선택으로 해결되지 않는다)
        long orgPk = column(f.table(org), "PTY_NO");
        jdbc.update("update TB_COL set PK_YN = false where COL_SN = ?", orgPk);
        Plan review = generation.preview(f.user, f.request(List.of(dept)));
        assertThat(review.ancestors()).filteredOn(a -> a.entityId().equals(org)).singleElement().satisfies(a -> {
            assertThat(a.state()).isEqualTo("REVIEW");
            assertThat(a.reason()).isEqualTo("PARENT_KEY_MISMATCH");
            assertThat(a.mappingId()).isEqualTo(mappingId(org));
        });
        assertThat(review.requiredAncestors()).isEmpty();
        assertThat(review.creatable()).isFalse();
    }

    @Test
    @DisplayName("조회 수 : 목록 페이지·정합성 비교·상속 PK 상세의 SQL 수가 서브타입·엔터티 수에 비례하지 않는다 (엔터티별 계층·매핑·FK 조회 없음)")
    void noPerRowQueries() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        long[] small = measure(stats, 2);
        long[] large = measure(stats, 12);
        assertThat(large[0]).as("목록 페이지 (가족 2 → 12)").isEqualTo(small[0]);
        assertThat(large[1]).as("정합성 비교 요약").isEqualTo(small[1]);
        assertThat(large[2]).as("상속 PK 상세").isEqualTo(small[2]);
    }

    /** 가족(루트 1 + 복합 키 서브타입 2 + 손자 1 + 매핑 안 된 손자 1) n 개를 만들고 [목록, 정합성 요약, 상세] 의 준비된 문장 수 */
    private long[] measure(Statistics stats, int families) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ui-n1-" + families);
        long lastSubtype = 0;
        for (int i = 0; i < families; i++) {
            // 엔터티명마다 표준 단어를 둬 가족마다 다른 테이블명이 되게 한다
            String[] names = {"가족루트" + i, "가족중간" + i, "가족말단" + i, "가족손자" + i, "가족미생성" + i};
            for (int n = 0; n < names.length; n++) word(f, names[n], "FM" + n + "X" + i);
            long root = entity(f, names[0]);
            f.pk(root, f.attr(root, "번호", true, "PTY_NO"), f.attr(root, "코드", true, "ORG_CD"));
            long a = entity(f, names[1]);
            f.attr(a, "이름", false, "ORG_NM");
            long b = entity(f, names[2]);
            long c = entity(f, names[3]);
            long d = entity(f, names[4]);
            group(f, root, "구분", true, false, a, b);
            group(f, a, "하위", true, false, c, d);
            generate(f, root, a, b, c);
            lastSubtype = c;
        }
        long mapping = mappingId(lastSubtype);
        stats.clear();
        assertThat(mappings.selectEntityRows(f.user, new ModelMappingDto.EntitySearch(f.modelId, null, null, null, null), PageRequest.of(0, 100)).getContent())
                .filteredOn(r -> r.inheritance() != null && "MATCH".equals(r.inheritance().result())).hasSize(families * 3);
        long list = stats.getPrepareStatementCount();
        stats.clear();
        assertThat(quality.selectSummary(f.user, f.modelId).inheritance().valid()).isEqualTo(families * 3L);
        long summary = stats.getPrepareStatementCount();
        stats.clear();
        assertThat(mappings.selectSubtypeKeys(f.user, mapping).status()).isEqualTo("VALID");
        long detail = stats.getPrepareStatementCount();
        return new long[]{list, summary, detail};
    }

    private void word(GenerationFixture f, String name, String abbreviation) {
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), name, abbreviation, abbreviation, name);
    }

    @Test
    @DisplayName("권한 : VIEWER 는 목록·상속 PK 상세·후보·정합성 조회만, USER·DBA·MANAGER·ADMIN 은 등록·해제·생성 요청이 서비스까지 간다 (버튼 숨김이 아니라 서버가 거절)")
    void roles() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ui-roles");
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 구분", true, false, company, person);
        generate(f, customer, company, person);
        long mapping = mappingId(company);
        Long project = f.user.getProjectId();
        String account = f.user.getId();

        AccountDto viewer = SecurityTestSupport.loginAs(project, account, "ROLE_VIEWER");
        assertThat(controller.selectSubtypeKeys(viewer, mapping).getBody().structure().result()).isEqualTo("MATCH");
        assertThat(controller.selectSubtypeKeyCandidates(viewer, mapping, 0, 10, null, null, null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.selectQualitySummary(viewer, f.modelId).getBody().inheritance().mappedSubtypes()).isEqualTo(2);
        assertThat(controller.selectEntityRows(viewer, 0, 10, null, null, null, f.modelId, null, null, null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThatThrownBy(() -> controller.saveSubtypeKeyMapping(viewer, new ModelMappingDto.SubtypeKeyMapping(null, mapping, customerNo, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteSubtypeKeyMapping(viewer, 1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.generatePreview(new PhysicalGenerationDto.Request(List.of(company), null, null), viewer)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.generate(new PhysicalGenerationDto.Request(List.of(company), null, "x"), viewer)).isInstanceOf(AccessDeniedException.class);
        for (String role : List.of("ROLE_USER", "ROLE_DBA", "ROLE_MANAGER", "ROLE_ADMIN")) {
            AccountDto user = SecurityTestSupport.loginAs(project, account, role);
            // 권한은 통과하고 서비스가 업무 오류로 거절한다 (이미 매핑된 속성)
            assertThatThrownBy(() -> controller.saveSubtypeKeyMapping(user, new ModelMappingDto.SubtypeKeyMapping(null, mapping, customerNo, column(f.table(company), "CUST_NO"))))
                    .as(role).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SAVED_DATA_EXISTS));
            assertThat(controller.generatePreview(new PhysicalGenerationDto.Request(List.of(company), null, null), user).getBody().tables()).as(role).hasSize(1);
        }
        // 다른 프로젝트의 매핑 ID 를 직접 보내도 거절 (등록·해제·조회)
        GenerationFixture other = GenerationFixture.create(context, "ui-roles-other-" + System.nanoTime(), com.lexorma.core.project.domain.enums.DbType.POSTGRESQL);
        Long key = keys(f, company).keys().getFirst().id();
        assertThatThrownBy(() -> mappings.deleteSubtypeKeyMapping(other.user, key))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED));
        assertThatThrownBy(() -> mappings.saveSubtypeKeyMapping(other.user, new ModelMappingDto.SubtypeKeyMapping(key, null, null, column(f.table(company), "CUST_NO"))))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED));
        assertThatThrownBy(() -> mappings.selectSubtypeKeyCandidates(other.user, mapping, null, null, PageRequest.of(0, 10)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED));
        assertThat(f.count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG where SBTYP_IDNTFR_COL_MPNG_SN = ?", key)).isOne();
    }
}
