package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.controller.ModelMappingRestController;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 논리 → 물리 선택 생성 (DEC-10) : 미리보기(이름·타입·PK·문제), planToken 확인 뒤 미승인 테이블·컬럼·매핑 생성, 계획이 바뀌면 409, 막는 문제가 있으면 아무것도 만들지 않음,
 * 이미 매핑된 엔터티는 건너뜀(덮어쓰지 않음), 같은 이름의 테이블이 있으면 오류, 다른 프로젝트 엔터티 거부.
 * M-7 보강 1차 : 논리 관계로 FK 를 추론하지 않는다(P1-a — 관계마다 미생성 사유), DBMS 식별자 진단(경고만, 생성 가능 여부와 분리), 미승인 엔터티 경고(P15).
 * AI Provider 를 등록하지 않은 상태로 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalGenerationIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(870_000_000L);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LogicalModelService logical;
    @Autowired PhysicalGenerationService generation;
    @Autowired ModelMappingRestController controller;
    @Autowired TableInfoRepository tables;
    @Autowired TableIndexRepository indexes;
    @Autowired EntityTableMappingRepository mappings;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesign;
    @Autowired ModelVersionService versionService;
    @Autowired ModelExchangeService exchange;
    @Autowired ProjectMemberRepository projectMembers;
    @Autowired com.lexorma.data.column.repository.ColumnInfoRepository columnRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("미리보기 → 반영 : 표준 단어 테이블명, 용어 약어 컬럼명, 도메인 타입(문자 길이·숫자 정밀도), 주 식별자 PK, 모두 미승인 + 매핑. 논리 관계는 FK 를 만들지 않고 사유를 보인다. 다시 하면 건너뜀")
    void generate() {
        Fixture f = fixture("gen-ok");
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, null);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        assertThat(plan.creatable()).as(plan.tables().toString()).isTrue();
        assertThat(plan.dbType()).isEqualTo("POSTGRESQL");
        assertThat(plan.identifiers()).as("짧은 영문 이름 : 식별자 진단 없음").isEmpty();
        assertThat(plan.tables()).extracting(PhysicalGenerationDto.TablePlan::tableName).containsExactly("CUST", "ORD");
        PhysicalGenerationDto.TablePlan order = plan.tables().get(1);
        assertThat(order.columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).containsExactly("ORD_ID", "CUST_ID", "ORD_AMT");
        assertThat(order.columns().get(0).pk()).isTrue();
        // P1-a : 예전에는 같은 용어(CUST_ID)로 추론해 참조 테이블 CUST 를 넣었다. 이제 속성 짝이 없으면 FK 를 만들지 않는다
        assertThat(order.columns()).allSatisfy(c -> assertThat(c.refTableName()).isNull());
        assertThat(plan.relations()).singleElement().satisfies(r -> {
            assertThat(r.parentEntityId()).isEqualTo(f.customer);
            assertThat(r.childEntityId()).isEqualTo(f.order);
            assertThat(r.fkCreated()).isFalse();
            assertThat(r.reasons()).containsExactly("ATTRIBUTE_PAIRS_MISSING");
            assertThat(r.message()).contains("FK 를 만들지 않습니다");
        });
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
                assertThat(c.getRefTableName()).isNull();
            });
        });
        assertThat(relationCount(f.user)).as("FK 관계(REF_COLUMN 포함)를 만들지 않는다").isZero();
        assertThat(tableDesign.generateTableDdl(result.tableIds().get(1), f.user, null).ddl()).doesNotContain("FOREIGN KEY");
        assertThat(mappings.findByEntity_Id(f.order)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from TB_ATRB_COL_MPNG m join TB_ENTT_TBL_MPNG e on e.ENTT_TBL_MPNG_SN = m.ENTT_TBL_MPNG_SN where e.PRJ_SN = ?",
                Integer.class, f.user.getProjectId())).isEqualTo(4);

        // 다시 : 이미 매핑된 엔터티는 건너뛴다 — 만들 것이 없다 (건너뛰는 자식의 관계는 계획에 없다)
        PhysicalGenerationDto.Plan again = generation.preview(f.user, request);
        assertThat(again.tables()).extracting(PhysicalGenerationDto.TablePlan::status).containsOnly("SKIP");
        assertThat(again.creatable()).isFalse();
        assertThat(again.relations()).isEmpty();
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
        assertThat(mappings.findByEntity_Id(f.order)).isEmpty();

        Fixture other = fixture("gen-other");
        assertThatThrownBy(() -> generation.preview(other.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, null)))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("P1-a 회귀 : 자기 참조(예전엔 PK 에 자기 테이블 참조 → DDL 이 건너뜀)·같은 부모의 관계 두 개(예전엔 FK 하나로 합쳐짐)·복합 PK 부모 — 모두 FK 미생성 + 사유, 미리보기 = 생성 결과 = DDL")
    void noInferredForeignKeys() {
        Fixture f = fixture("gen-p1a");
        long p = f.user.getProjectId();
        for (String[] w : new String[][]{{"부서", "DEPT"}, {"공항", "ARPT"}, {"항공편", "FLT"}, {"상위", "UPR"}, {"코드", "CD"}, {"상세", "DTL"}, {"번호", "NO"}}) word(p, w[0], w[1]);
        long text = domain(p, "코드V10", "VARCHAR", 10, null);
        long deptId = term(p, "부서_아이디", "DEPT_ID", text);
        long uprDeptId = term(p, "상위_부서_아이디", "UPR_DEPT_ID", text);
        long arptCd = term(p, "공항_코드", "ARPT_CD", text);
        long fltNo = term(p, "항공편_번호", "FLT_NO", text);
        long ordNo = term(p, "주문_번호", "ORD_NO", text);
        long dtlNo = term(p, "상세_번호", "DTL_NO", text);
        LogicalModelDto.Model model = logical.saveModel(f.user, new LogicalModelDto.Model(null, "gen-p1a-2", null));
        // 자기 참조 : 부서(PK 부서 아이디) + 상위 부서 아이디
        var dept = entity(f.user, model.id(), "부서");
        var deptPk = attribute(f.user, dept.id(), "부서 아이디", true, deptId);
        attribute(f.user, dept.id(), "상위 부서 아이디", false, uprDeptId);
        identifier(f.user, dept.id(), deptPk.id());
        relationship(f.user, model.id(), "상위 부서", dept.id(), dept.id());
        // 같은 부모의 관계 두 개 : 공항 → 항공편 (출발·도착)
        var arpt = entity(f.user, model.id(), "공항");
        identifier(f.user, arpt.id(), attribute(f.user, arpt.id(), "공항 코드", true, arptCd).id());
        var flt = entity(f.user, model.id(), "항공편");
        identifier(f.user, flt.id(), attribute(f.user, flt.id(), "항공편 번호", true, fltNo).id());
        attribute(f.user, flt.id(), "출발 공항 코드", true, arptCd);
        relationship(f.user, model.id(), "출발", arpt.id(), flt.id());
        relationship(f.user, model.id(), "도착", arpt.id(), flt.id());
        // 복합 PK 부모 : 주문 상세(주문 번호 + 상세 번호) → 상세 이력은 만들지 않고 부모만 / 자식은 같은 속성 구성
        var ordDtl = entity(f.user, model.id(), "주문 상세");
        var o1 = attribute(f.user, ordDtl.id(), "주문 번호", true, ordNo);
        var o2 = attribute(f.user, ordDtl.id(), "상세 번호", true, dtlNo);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, ordDtl.id(), "식별자", true, List.of(o1.id(), o2.id()), null));
        var child = entity(f.user, model.id(), "상세");
        var c1 = attribute(f.user, child.id(), "주문 번호", true, ordNo);
        var c2 = attribute(f.user, child.id(), "상세 번호", true, dtlNo);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, child.id(), "식별자", true, List.of(c1.id(), c2.id()), null));
        relationship(f.user, model.id(), "주문 상세 상세", ordDtl.id(), child.id());

        List<Long> ids = List.of(dept.id(), arpt.id(), flt.id(), ordDtl.id(), child.id());
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, new PhysicalGenerationDto.Request(ids, null, null));
        assertThat(plan.creatable()).as(plan.tables().toString()).isTrue();
        assertThat(plan.tables()).flatExtracting(PhysicalGenerationDto.TablePlan::columns).allSatisfy(c -> assertThat(c.refTableName()).isNull());
        assertThat(plan.tables()).flatExtracting(PhysicalGenerationDto.TablePlan::warnings).noneMatch(w -> w.contains("FK"));
        assertThat(plan.relations()).allSatisfy(r -> {
            assertThat(r.fkCreated()).isFalse();
            assertThat(r.reasons()).first().isEqualTo("ATTRIBUTE_PAIRS_MISSING");
        });
        assertThat(plan.relations()).filteredOn(r -> r.childEntityId().equals(dept.id())).singleElement()
                .satisfies(r -> assertThat(r.reasons()).containsExactly("ATTRIBUTE_PAIRS_MISSING", "SELF_REFERENCE"));
        assertThat(plan.relations()).filteredOn(r -> r.childEntityId().equals(flt.id())).hasSize(2)
                .allSatisfy(r -> assertThat(r.reasons()).containsExactly("ATTRIBUTE_PAIRS_MISSING", "MULTIPLE_RELATIONSHIPS"));
        assertThat(plan.relations()).filteredOn(r -> r.childEntityId().equals(child.id())).singleElement()
                .satisfies(r -> assertThat(r.reasons()).containsExactly("ATTRIBUTE_PAIRS_MISSING", "COMPOSITE_PARENT_KEY"));

        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(ids, null, plan.planToken()));
        assertThat(relationCount(f.user)).isZero();
        // 미리보기 = 생성 결과 : 테이블·컬럼 이름, PK, 참조 테이블 없음
        tx.executeWithoutResult(s -> {
            List<String> created = new ArrayList<>();
            for (Long tableId : result.tableIds()) {
                TableInfo table = tables.findById(tableId).orElseThrow();
                table.getColumns().stream().sorted(java.util.Comparator.comparing(ColumnInfo::getSortOrder))
                        .forEach(c -> created.add(table.getTableName() + "." + c.getColumnName() + ":" + c.getIsPk() + ":" + c.getRefTableName()));
            }
            List<String> planned = new ArrayList<>();
            plan.tables().forEach(t -> t.columns().forEach(c -> planned.add(t.tableName() + "." + c.columnName() + ":" + c.pk() + ":" + c.refTableName())));
            assertThat(created).isEqualTo(planned);
        });
        // DDL : FK 없음, 예전의 "PK 가 자기 자신이라 FK 를 만들지 않았습니다" 경고도 없다
        for (Long tableId : result.tableIds()) {
            var ddl = tableDesign.generateTableDdl(tableId, f.user, null);
            assertThat(ddl.ddl()).doesNotContain("FOREIGN KEY");
            assertThat(ddl.warnings()).noneMatch(w -> w.contains("자기 자신"));
        }
    }

    @Test
    @DisplayName("planToken : 관계 추가·삭제·방향 변경, 프로젝트 DBMS 변경이면 계획이 달라져 409 (FK 를 만들지 않아도)")
    void planTokenFollowsRelationsAndDbms() {
        Fixture f = fixture("gen-token");
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, null);

        // 관계 추가
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        var added = relationship(f.user, f.modelId, "추가 관계", f.customer, f.order);
        assertStale(f, plan);
        // 관계 삭제 (승인된 관계는 지울 수 없어 승인을 푼다 — 승인 상태는 계획에 들어가지 않는다)
        jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", added.id());
        plan = generation.preview(f.user, request);
        logical.deleteRelationship(f.user, added.id(), null);
        assertStale(f, plan);
        // 방향 변경 (원본 ↔ 대상) : 승인된 관계는 바꿀 수 없어 승인을 풀고 바꾼다
        jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", f.relationship.id());
        plan = generation.preview(f.user, request);
        logical.saveRelationship(f.user, new LogicalModelDto.Relationship(f.relationship.id(), f.modelId, "고객 주문", null, f.order, null, f.customer, null,
                false, 1, 1, 0, null, null, null, f.relationship.modifiedAt()));
        PhysicalGenerationDto.Plan reversed = generation.preview(f.user, request);
        assertThat(reversed.relations()).singleElement().satisfies(r -> assertThat(r.childEntityId()).isEqualTo(f.customer));
        assertStale(f, plan);
        // 프로젝트 DBMS 변경
        plan = generation.preview(f.user, request);
        jdbc.update("update TB_PRJ set DB_TYPE_CD = 'ORACLE' where PRJ_SN = ?", f.user.getProjectId());
        assertThat(generation.preview(f.user, request).dbType()).isEqualTo("ORACLE");
        assertStale(f, plan);
        // 같은 상태의 새 미리보기는 반영된다
        PhysicalGenerationDto.Plan fresh = generation.preview(f.user, request);
        assertThat(generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, fresh.planToken())).tables()).isEqualTo(2);
    }

    @Test
    @DisplayName("식별자 진단 : 프로젝트 DBMS 기준(Oracle 30바이트) 테이블·컬럼 길이, PK 이름 잘림, 대상 스키마 기존 인덱스와의 최종 이름 충돌 — 경고만, 생성 가능 여부는 그대로")
    void identifierIssues() {
        Fixture f = fixture("gen-ident");
        long p = f.user.getProjectId();
        jdbc.update("update TB_PRJ set DB_TYPE_CD = 'ORACLE' where PRJ_SN = ?", p);
        // 31자 테이블명 : 단어 약어가 긴 엔터티
        word(p, "아주긴이름", "VERYLONGWORDFORANENTITYNAMEX");
        long text = domain(p, "아이디V21", "VARCHAR", 21, null);
        long longTerm = term(p, "긴_아이디", "LONG_IDENTIFIER_COLUMN_NAME_OVER", text);
        var big = entity(f.user, f.modelId, "아주긴이름 주문");
        var key = attribute(f.user, big.id(), "긴 아이디", true, longTerm);
        identifier(f.user, big.id(), key.id());

        PhysicalGenerationDto.Plan plan = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(big.id(), f.customer), null, null));
        assertThat(plan.dbType()).isEqualTo("ORACLE");
        assertThat(plan.creatable()).as("식별자 진단은 생성을 막지 않는다").isTrue();
        assertThat(plan.tables()).filteredOn(t -> t.entityId().equals(big.id())).singleElement()
                .satisfies(t -> assertThat(t.tableName()).isEqualTo("VERYLONGWORDFORANENTITYNAMEX_ORD"));
        assertThat(plan.identifiers()).extracting(PhysicalGenerationDto.IdentifierIssue::code, PhysicalGenerationDto.IdentifierIssue::kind,
                        PhysicalGenerationDto.IdentifierIssue::entityId, PhysicalGenerationDto.IdentifierIssue::attributeId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("IDENTIFIER_LENGTH_EXCEEDED", "TABLE", big.id(), null),
                        org.assertj.core.groups.Tuple.tuple("IDENTIFIER_LENGTH_EXCEEDED", "COLUMN", big.id(), key.id()),
                        org.assertj.core.groups.Tuple.tuple("CONSTRAINT_NAME_TRUNCATED", "PRIMARY_KEY", big.id(), null));
        assertThat(plan.identifiers().get(0)).satisfies(i -> {
            assertThat(i.severity()).isEqualTo("ERROR");
            assertThat(i.length()).isEqualTo(32);
            assertThat(i.maxLength()).isEqualTo(30);
            assertThat(i.basis()).contains("Oracle");
            assertThat(i.guide()).isNotBlank();
        });
        assertThat(plan.identifiers().get(2).ddlName()).hasSize(30);

        // PostgreSQL 로 돌리면 같은 이름이 문제없다 — 대신 대상 스키마의 기존 인덱스 PK_CUST 와 만들 PK 가 충돌
        jdbc.update("update TB_PRJ set DB_TYPE_CD = 'POSTGRESQL' where PRJ_SN = ?", p);
        tx.executeWithoutResult(s -> {
            TableInfo other = tables.save(TableInfo.builder().projectId(p).schema(schemaService.resolveSchema(p, null)).tableName("OTHER_T")
                    .tableDescription("기존").isApproval(false).build());
            indexes.save(TableIndex.builder().projectId(p).tableInfo(other).indexName("PK_CUST").isUnique(false).isApproval(false).build());
        });
        PhysicalGenerationDto.Plan collision = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, null));
        assertThat(collision.creatable()).isTrue();
        assertThat(collision.identifiers()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("CONSTRAINT_NAME_COLLISION");
            assertThat(i.kind()).isEqualTo("PRIMARY_KEY");
            assertThat(i.entityId()).isEqualTo(f.customer);
            assertThat(i.ddlName()).isEqualTo("PK_CUST");
            assertThat(i.related()).singleElement().satisfies(r -> assertThat(r).contains("PK_CUST").contains("OTHER_T"));
        });
        // 반영해도 이름을 고치지 않는다
        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, collision.planToken()));
        assertThat(tables.findById(result.tableIds().get(0)).orElseThrow().getTableName()).isEqualTo("CUST");
    }

    @Test
    @DisplayName("P15 : 미승인 엔터티도 만들되 경고, 미승인 관계는 approved=false. 미리보기·반영은 USER 부터 (VIEWER 거절)")
    void unapprovedAndRoles() {
        Fixture f = fixture("gen-role");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", f.order);
        jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", f.relationship.id());
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, null);

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, f.user.getProjectId(), "900", "ROLE_VIEWER");
        assertThatThrownBy(() -> controller.generatePreview(request, viewer)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.generate(new PhysicalGenerationDto.Request(List.of(f.customer), null, "x"), viewer)).isInstanceOf(AccessDeniedException.class);

        AccountDto user = SecurityTestSupport.loginAs(projectRepository, f.user.getProjectId(), "901", "ROLE_USER");
        PhysicalGenerationDto.Plan plan = controller.generatePreview(request, user).getBody();
        assertThat(plan).isNotNull();
        assertThat(plan.creatable()).isTrue();
        assertThat(plan.tables().get(1).warnings()).contains("엔터티 주문 이(가) 승인 전입니다.");
        assertThat(plan.tables().get(0).warnings()).noneMatch(w -> w.contains("승인 전입니다") && w.startsWith("엔터티"));
        assertThat(plan.relations()).singleElement().satisfies(r -> assertThat(r.approved()).isFalse());
        assertThat(controller.generate(new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, plan.planToken()), user).getBody().tables()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ M-7 보강 2차 (P3 · P7 · P10 · P11)

    @Test
    @DisplayName("P3 : 주 식별자 속성을 식별자 순서대로 먼저, 나머지는 속성 순서 — 미리보기 = 저장 순서 = DDL 복합 PK 순서. 식별자 순서가 같으면 식별자 속성 ID 순")
    void primaryKeyOrder() {
        Fixture f = fixture("gen-p3");
        long p = f.user.getProjectId();
        for (String[] w : new String[][]{{"상세", "DTL"}, {"번호", "NO"}, {"명", "NM"}, {"등록", "REG"}, {"일시", "DT"}}) word(p, w[0], w[1]);
        long text = domain(p, "번호V10", "VARCHAR", 10, null);
        long ordNm = term(p, "주문_명", "ORD_NM", text);
        long ordNo = term(p, "주문_번호", "ORD_NO", text);
        long dtlNo = term(p, "상세_번호", "DTL_NO", text);
        long regDt = term(p, "등록_일시", "REG_DT", text);
        var entity = entity(f.user, f.modelId, "주문 상세");
        var name = attribute(f.user, entity.id(), "주문명", false, ordNm);       // 속성 순서 1
        var no = attribute(f.user, entity.id(), "주문번호", true, ordNo);         // 2
        var detail = attribute(f.user, entity.id(), "주문상세번호", true, dtlNo);  // 3
        attribute(f.user, entity.id(), "등록일시", false, regDt);                 // 4
        // 식별자 순서 : 상세번호 → 주문번호 (속성 순서와 다르다)
        var identifier = logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity.id(), "식별자", true, List.of(detail.id(), no.id()), null));
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(entity.id()), null, null);

        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        assertThat(plan.tables().get(0).columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName, PhysicalGenerationDto.ColumnPlan::pk)
                .containsExactly(tuple("DTL_NO", true), tuple("ORD_NO", true), tuple("ORD_NM", false), tuple("REG_DT", false));
        assertThat(plan.tables().get(0).attributes()).extracting(PhysicalGenerationDto.AttributeChoice::attributeId).first().isEqualTo(detail.id());

        // 식별자 순서를 바꾸면 계획이 달라진다 (409)
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(identifier.id(), entity.id(), "식별자", true, List.of(no.id(), detail.id()), null, identifier.modifiedAt()));
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(entity.id()), null, plan.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        // 식별자 속성 순서가 같으면(직접 고침) 식별자 속성 ID 순으로 안정적이다
        jdbc.update("update TB_LGCL_IDNTFR_ATRB set SORT_ORD = 1 where LGCL_IDNTFR_SN = ?", identifier.id());
        PhysicalGenerationDto.Plan tie = generation.preview(f.user, request);
        assertThat(generation.preview(f.user, request).planToken()).as("같은 계획이면 같은 토큰").isEqualTo(tie.planToken());
        List<Long> attributeIdOrder = jdbc.queryForList("select LGCL_ATRB_SN from TB_LGCL_IDNTFR_ATRB where LGCL_IDNTFR_SN = ? order by LGCL_IDNTFR_ATRB_SN", Long.class, identifier.id());
        assertThat(tie.tables().get(0).columns().subList(0, 2)).extracting(PhysicalGenerationDto.ColumnPlan::attributeId).containsExactlyElementsOf(attributeIdOrder);

        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(entity.id()), null, tie.planToken()));
        tx.executeWithoutResult(s -> {
            TableInfo table = tables.findById(result.tableIds().get(0)).orElseThrow();
            assertThat(table.getColumns().stream().sorted(java.util.Comparator.comparing(ColumnInfo::getSortOrder)).map(ColumnInfo::getColumnName).toList())
                    .containsExactlyElementsOf(tie.tables().get(0).columns().stream().map(PhysicalGenerationDto.ColumnPlan::columnName).toList());
        });
        String pk = String.join(", ", tie.tables().get(0).columns().subList(0, 2).stream().map(PhysicalGenerationDto.ColumnPlan::columnName).toList());
        assertThat(tableDesign.generateTableDdl(result.tableIds().get(0), f.user, null).ddl()).contains("PRIMARY KEY (" + pk + ")");
        assertThat(name.id()).isNotNull();
    }

    @Test
    @DisplayName("planToken (2차) : 속성명 변경·도메인 길이 변경 → 409, 같은 계획은 같은 토큰, 계획과 무관한 도메인 설명 변경은 토큰 그대로")
    void tokenFollowsNamesAndDomains() {
        Fixture f = fixture("gen-token-2");
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.customer), null, null);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        assertThat(generation.preview(f.user, request).planToken()).isEqualTo(plan.planToken());
        long domainId = jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, f.custIdTerm);
        jdbc.update("update TB_STD_DMN set DMN_EXPLN = '설명만 바꿈' where STD_DMN_SN = ?", domainId);
        assertThat(generation.preview(f.user, request).planToken()).as("계획과 무관한 값").isEqualTo(plan.planToken());

        jdbc.update("update TB_STD_DMN set DATA_LEN = 40 where STD_DMN_SN = ?", domainId);
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, plan.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        PhysicalGenerationDto.Plan longer = generation.preview(f.user, request);
        assertThat(longer.tables().get(0).columns().get(0).maxLength()).isEqualTo(40);

        logical.saveAttribute(f.user, new LogicalModelDto.Attribute(f.customerId, f.customer, "고객 식별 아이디", null, null, true, 1, f.custIdTerm, null, null, null));
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, longer.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(versionCount(f.user)).isZero();
    }

    @Test
    @DisplayName("P7 : 일반 속성 제외(용어 없는 속성 빼고 생성), 주 식별자 속성은 제외 요청해도 포함 + 경고, 제외 속성은 컬럼·매핑 없음, 제외가 바뀌면 토큰이 바뀐다")
    void attributeSelection() {
        Fixture f = fixture("gen-p7");
        long p = f.user.getProjectId();
        var note = logical.saveAttribute(f.user, new LogicalModelDto.Attribute(null, f.order, "비고", null, "VARCHAR(100)", false, null, null, null, null, null));
        LogicalModelDto.Attribute orderId = logical.selectAttributes(f.user, f.order).stream().filter(a -> "주문 아이디".equals(a.attributeName())).findFirst().orElseThrow();
        long amount = logical.selectAttributes(f.user, f.order).stream().filter(a -> "주문 금액".equals(a.attributeName())).findFirst().orElseThrow().id();

        // 용어 없는 속성 때문에 엔터티 전체가 오류
        assertThat(generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.order), null, null)).tables().get(0).status()).isEqualTo("ERROR");
        // 빼면 만들 수 있다. 주 식별자 속성(주문 아이디)을 빼라고 해도 만든다
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.order), null, null, List.of(note.id(), orderId.id()));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        PhysicalGenerationDto.TablePlan table = plan.tables().get(0);
        assertThat(plan.creatable()).as(table.toString()).isTrue();
        assertThat(table.columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).containsExactly("ORD_ID", "CUST_ID", "ORD_AMT");
        assertThat(table.attributes()).filteredOn(a -> a.attributeId().equals(orderId.id())).singleElement()
                .satisfies(a -> { assertThat(a.primary()).isTrue(); assertThat(a.excluded()).isFalse(); assertThat(a.excludeRequested()).isTrue(); });
        assertThat(table.attributes()).filteredOn(a -> a.attributeId().equals(note.id())).singleElement().satisfies(a -> assertThat(a.excluded()).isTrue());
        assertThat(table.warnings()).contains("주 식별자 속성 주문 아이디 은(는) 제외할 수 없어 포함했습니다.");
        // PK 를 빼라는 요청과 빼지 않은 요청은 같은 계획이다 (정규화한 결과로 토큰을 만든다)
        assertThat(generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.order), null, null, List.of(note.id()))).planToken()).isEqualTo(plan.planToken());

        // 제외를 바꾸면 이전 토큰은 쓸 수 없다
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.order), null, plan.planToken(), List.of(note.id(), amount))))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        PhysicalGenerationDto.Plan fewer = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.order), null, null, List.of(note.id(), amount)));
        assertThat(fewer.planToken()).isNotEqualTo(plan.planToken());
        assertThat(fewer.tables().get(0).columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).containsExactly("ORD_ID", "CUST_ID");

        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.order), null, plan.planToken(), List.of(note.id(), orderId.id())));
        assertThat(result.columns()).isEqualTo(3);
        assertThat(jdbc.queryForList("select m.LGCL_ATRB_SN from TB_ATRB_COL_MPNG m join TB_ENTT_TBL_MPNG e on e.ENTT_TBL_MPNG_SN = m.ENTT_TBL_MPNG_SN where e.PRJ_SN = ?",
                Long.class, p)).hasSize(3).doesNotContain(note.id());
    }

    @Test
    @DisplayName("P7 예외 : 다른 엔터티·다른 프로젝트·없는 속성 ID, 중복 ID 는 400. PK 만 남기기, 주 식별자 없이 모두 제외하면 오류")
    void attributeSelectionErrors() {
        Fixture f = fixture("gen-p7-err");
        Fixture other = fixture("gen-p7-err-other");
        long otherAttribute = other.customerId;
        long notChosen = f.customerId; // 고객 엔터티의 속성 (요청은 주문만)
        for (List<Long> wrong : List.of(List.of(notChosen), List.of(otherAttribute), List.of(-1L))) {
            assertThatThrownBy(() -> generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.order), null, null, wrong)))
                    .as(wrong.toString()).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        }
        List<LogicalModelDto.Attribute> orderAttributes = logical.selectAttributes(f.user, f.order);
        long amount = orderAttributes.stream().filter(a -> "주문 금액".equals(a.attributeName())).findFirst().orElseThrow().id();
        long customerRef = orderAttributes.stream().filter(a -> "고객 아이디".equals(a.attributeName())).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.order), null, null, List.of(amount, amount))))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);

        // 일반 속성을 모두 빼면 PK 만 남는다
        PhysicalGenerationDto.Plan onlyKey = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.order), null, null, List.of(amount, customerRef)));
        assertThat(onlyKey.creatable()).isTrue();
        assertThat(onlyKey.tables().get(0).columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).containsExactly("ORD_ID");

        // 주 식별자가 없는 엔터티의 속성을 모두 빼면 만들 컬럼이 없다 (오류)
        var loose = entity(f.user, f.modelId, "주문 금액");
        var only = attribute(f.user, loose.id(), "주문 금액", false, onlyTermOf(f, amount));
        PhysicalGenerationDto.Plan empty = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(loose.id()), null, null, List.of(only.id())));
        assertThat(empty.creatable()).isFalse();
        assertThat(empty.tables().get(0).errors()).contains("모든 속성을 제외해 만들 컬럼이 없습니다.");
    }

    @Test
    @DisplayName("P10 : 오류 엔터티가 있으면 만들지 않고, 그 엔터티를 뺀 새 미리보기(새 토큰)로만 만든다. 이전 토큰 재사용 거부, 경고만 있는 엔터티는 오류가 아니다, 모두 빼면 요청 불가")
    void dropErrorEntities() {
        Fixture f = fixture("gen-p10");
        logical.saveAttribute(f.user, new LogicalModelDto.Attribute(null, f.order, "비고", null, "VARCHAR(100)", false, null, null, null, null, null));
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", f.customer); // 경고만 (미승인)
        PhysicalGenerationDto.Plan mixed = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, null));
        assertThat(mixed.creatable()).isFalse();
        assertThat(mixed.tables()).extracting(PhysicalGenerationDto.TablePlan::entityId, PhysicalGenerationDto.TablePlan::status)
                .containsExactly(tuple(f.customer, "CREATE"), tuple(f.order, "ERROR"));
        assertThat(mixed.tables().get(0).warnings()).isNotEmpty();
        long versions = versionCount(f.user);
        // 서버는 오류 엔터티를 빼고 만들지 않는다
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, mixed.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        // 이전 토큰으로 고객만 요청 → 계획이 달라 409
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, mixed.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(versionCount(f.user)).as("실패한 반영은 버전을 남기지 않는다").isEqualTo(versions);
        // 오류 엔터티를 뺀 새 미리보기
        PhysicalGenerationDto.Plan clean = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, null));
        assertThat(clean.creatable()).isTrue();
        assertThat(generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer), null, clean.planToken())).tables()).isEqualTo(1);
        assertThat(mappings.findByEntity_Id(f.order)).isEmpty();
        assertThatThrownBy(() -> generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(), null, null)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
    }

    @Test
    @DisplayName("P11 : 반영 직전 모델 버전(GENERATE-요청 ID, 생성 전 상태) — 미리보기·409·만들 것 없음은 버전 없음, 버전 목록·스냅샷·현재 모델 비교 그대로")
    void versionBeforeGeneration() {
        Fixture f = fixture("gen-p11");
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, null);
        long before = versionCount(f.user);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        generation.preview(f.user, request);
        assertThat(versionCount(f.user)).as("미리보기는 버전을 만들지 않는다").isEqualTo(before);

        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, plan.planToken()));
        assertThat(result.versionName()).startsWith("GENERATE-");
        assertThat(versionCount(f.user)).isEqualTo(before + 1);
        var summary = versionService.detail(f.user, result.versionId());
        assertThat(summary.description()).contains("논리 → 물리 생성 직전 상태");
        // 스냅샷은 생성 전 : 논리 엔터티는 있고 새 테이블은 없다
        String snapshot = versionService.snapshot(f.user, result.versionId()).objects().toString();
        assertThat(snapshot).contains("고객").doesNotContain("\"CUST\"");
        // 현재 모델과 비교하면 새 테이블 2개가 ADDED
        var comparison = versionService.compare(f.user, result.versionId(), null);
        assertThat(comparison.changes()).filteredOn(c -> "ADDED".equals(c.change()) && "PHYSICAL_TABLE".equals(c.targetType()))
                .extracting(ModelVersionDto.Change::name).contains("CUST", "ORD");

        // 다시 : 모두 건너뜀 → 만들 것이 없어 반영 불가, 버전 없음
        PhysicalGenerationDto.Plan again = generation.preview(f.user, request);
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, again.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(versionCount(f.user)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("P11 동시성 : 같은 계획 두 번 동시 반영 → 하나만 성공(다른 쪽 409), 버전 1개. 같은 프로젝트의 다른 논리 모델·모델 가져오기와 동시 → 교착 없이 모두 성공")
    void concurrentApply() throws Exception {
        Fixture f = fixture("gen-p11-race");
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, null);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        long before = versionCount(f.user);
        List<Object> outcomes = race(
                () -> generation.apply(login(f), new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, plan.planToken())),
                () -> generation.apply(login(f), new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, plan.planToken())));
        assertThat(outcomes).filteredOn(o -> o instanceof PhysicalGenerationDto.Result).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof CustomException c && c.getErrorCode() == ErrorCode.MODEL_CONFLICT).hasSize(1);
        assertThat(versionCount(f.user)).isEqualTo(before + 1);
        assertThat(tables.findAllByProjectId(f.user.getProjectId(), org.springframework.data.domain.Pageable.unpaged()).getContent()).hasSize(2);

        // 같은 프로젝트의 다른 논리 모델 + 모델 가져오기(다른 프로젝트에서 내보낸 테이블)와 동시에
        long p = f.user.getProjectId();
        LogicalModelDto.Model second = logical.saveModel(f.user, new LogicalModelDto.Model(null, "gen-p11-race-2", null));
        var amount = entity(f.user, second.id(), "금액");
        identifier(f.user, amount.id(), attribute(f.user, amount.id(), "주문 금액", true, onlyTermOf(f, logical.selectAttributes(f.user, f.order).stream()
                .filter(a -> "주문 금액".equals(a.attributeName())).findFirst().orElseThrow().id())).id());
        PhysicalGenerationDto.Plan secondPlan = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(amount.id()), null, null));
        Fixture source = fixture("gen-p11-race-src");
        tx.executeWithoutResult(s -> {
            TableInfo imported = tables.save(TableInfo.builder().projectId(source.user.getProjectId()).schema(schemaService.resolveSchema(source.user.getProjectId(), null))
                    .tableName("IMPORTED_T").tableDescription("가져올 테이블").isApproval(true).build());
            columnRepository.save(ColumnInfo.builder().projectId(source.user.getProjectId()).tableInfo(imported).tableName("IMPORTED_T").columnName("IMPORTED_ID")
                    .dataType("VARCHAR").maxLength(20).isPk(true).isNullable(false).sortOrder(1).isApproval(true).build());
        });
        SecurityTestSupport.loginAs(projectRepository, source.user.getProjectId(), source.user.getId(), "ROLE_ADMIN");
        var document = exchange.export(source.user);
        var selection = new ModelExchangeDto.Selection(List.of("DEFAULT.IMPORTED_T"), List.of(), List.of());
        login(f);
        // 모델 가져오기는 대상 프로젝트의 멤버(관리자·ADMIN)여야 한다 (ProjectAuthority.canManage)
        projectMembers.save(ProjectMember.builder().project(projectRepository.findById(p).orElseThrow())
                .account(accountRepository.findById(Long.valueOf(f.user.getId())).orElseThrow()).isActive(true).build());
        var importPlan = exchange.plan(f.user, new ModelExchangeDto.PlanRequest(p, document, selection));
        assertThat(importPlan.applicable()).as(importPlan.toString()).isTrue();
        long versionsBefore = versionCount(f.user);
        List<Object> mixed = race(
                () -> generation.apply(login(f), new PhysicalGenerationDto.Request(List.of(amount.id()), null, secondPlan.planToken())),
                () -> { login(f); return exchange.apply(f.user, new ModelExchangeDto.ApplyRequest(p, document, selection, importPlan.planToken(), importPlan.requestId())); });
        assertThat(mixed).as(mixed.toString()).allMatch(o -> o instanceof PhysicalGenerationDto.Result || o instanceof ModelExchangeDto.Result);
        assertThat(versionCount(f.user)).isEqualTo(versionsBefore + 2);
    }

    /** 두 작업을 동시에 시작해 결과(값 또는 예외)를 모은다. 60초 안에 끝나지 않으면 교착으로 보고 실패 */
    private List<Object> race(java.util.concurrent.Callable<Object> a, java.util.concurrent.Callable<Object> b) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Object>> futures = new ArrayList<>();
            for (var task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    } finally {
                        SecurityTestSupport.clear();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (var future : futures) outcomes.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private AccountDto login(Fixture f) {
        return SecurityTestSupport.loginAs(projectRepository, f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
    }

    private long versionCount(AccountDto user) {
        return jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Long.class, user.getProjectId());
    }

    private long onlyTermOf(Fixture f, long attributeId) {
        return jdbc.queryForObject("select STD_TRM_SN from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", Long.class, attributeId);
    }

    private void assertStale(Fixture f, PhysicalGenerationDto.Plan plan) {
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(f.customer, f.order), null, plan.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(mappings.findByEntity_Id(f.customer)).isEmpty();
    }

    private int relationCount(AccountDto user) {
        return jdbc.queryForObject("select count(*) from TB_TBL_REL where PRJ_SN = ?", Integer.class, user.getProjectId());
    }

    private record Fixture(AccountDto user, Long modelId, Long customer, Long order, Long customerId, Long custIdTerm, LogicalModelDto.Relationship relationship) {
    }

    private Fixture fixture(String name) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name).isActive(true).account(admin).dbType(DbType.POSTGRESQL).build());
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        long p = project.getId();
        for (String[] w : new String[][]{{"고객", "CUST"}, {"주문", "ORD"}, {"아이디", "ID"}, {"금액", "AMT"}}) word(p, w[0], w[1]);
        long text = domain(p, "아이디V20", "VARCHAR", 20, null);
        long money = domain(p, "금액N15", "NUMERIC", 15, 2);
        long custId = term(p, "고객_아이디", "CUST_ID", text);
        long ordId = term(p, "주문_아이디", "ORD_ID", text);
        long ordAmt = term(p, "주문_금액", "ORD_AMT", money);
        LogicalModelDto.Model model = logical.saveModel(user, new LogicalModelDto.Model(null, name, null));
        LogicalModelDto.Entity customer = logical.saveEntity(user, new LogicalModelDto.Entity(null, model.id(), "고객", "고객 정보", null, null, null));
        LogicalModelDto.Attribute customerId = attribute(user, customer.id(), "고객 아이디", true, custId);
        identifier(user, customer.id(), customerId.id());
        LogicalModelDto.Entity order = logical.saveEntity(user, new LogicalModelDto.Entity(null, model.id(), "주문", "주문 정보", null, null, null));
        LogicalModelDto.Attribute orderId = attribute(user, order.id(), "주문 아이디", true, ordId);
        attribute(user, order.id(), "고객 아이디", true, custId);
        logical.saveAttribute(user, new LogicalModelDto.Attribute(null, order.id(), "주문 금액", null, null, false, null, ordAmt, null, null, null));
        identifier(user, order.id(), orderId.id());
        LogicalModelDto.Relationship relationship = relationship(user, model.id(), "고객 주문", customer.id(), order.id());
        return new Fixture(user, model.id(), customer.id(), order.id(), customerId.id(), custId, relationship);
    }

    private LogicalModelDto.Entity entity(AccountDto user, Long modelId, String name) {
        return logical.saveEntity(user, new LogicalModelDto.Entity(null, modelId, name, null, null, null, null));
    }

    private LogicalModelDto.Attribute attribute(AccountDto user, Long entityId, String name, boolean required, long termId) {
        return logical.saveAttribute(user, new LogicalModelDto.Attribute(null, entityId, name, null, null, required, null, termId, null, null, null));
    }

    private void identifier(AccountDto user, Long entityId, Long attributeId) {
        logical.saveIdentifier(user, new LogicalModelDto.Identifier(null, entityId, "식별자", true, List.of(attributeId), null));
    }

    private LogicalModelDto.Relationship relationship(AccountDto user, Long modelId, String name, Long parent, Long child) {
        return logical.saveRelationship(user, new LogicalModelDto.Relationship(null, modelId, name, null, parent, null, child, null, false, 1, 1, 0, null, null, null));
    }

    private void word(long projectId, String name, String abbreviation) {
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", IDS.incrementAndGet(), projectId, name, abbreviation, abbreviation, name);
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
