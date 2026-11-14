package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.physical.repository.TableConstraintRepository;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.controller.SourceCompareRestController;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.AttributeResult;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectKind;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.TableReason;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.service.SourceCompareService;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.repository.PhysicalViewRepository;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec.Namespace;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원본 ↔ 물리 모델 비교 (M-4d) : 컬럼·PK·UNIQUE·CHECK·FK·인덱스의 차이, 이름 변경 후보·모호함, 판단하지 못한 것(UNKNOWN), 매핑 상태(미매핑·후보·스키마 미매핑·
 * 재확인·충돌·원본 없음·모델 없음·모델에만), FULL/PARTIAL 수집, 비교 기준(기준 연결·반영 순서)의 고정, 권한·프로젝트 격리, 읽기 전용(모델·매핑·카탈로그 무변경).
 * 원본 DB 에 접속하지 않는다 : 연결 주소는 없는 호스트이고 수집 결과(SourceCollectionSpec)를 직접 넘긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceCompareIntegrationTest {

    @Autowired SourceCompareService service;
    @Autowired SourceCompareRestController api;
    @Autowired SourceMappingService mapping;
    @Autowired SourceCollectionService collections;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TableConstraintRepository constraintRepository;
    @Autowired TableIndexRepository indexRepository;
    @Autowired TableRelationRepository relationRepository;
    @Autowired PhysicalViewRepository viewRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("비교 정확성 : 같은 TABLE, 원본에만·모델에만 컬럼, 타입·길이·NULL·DEFAULT·순서 차이, SERIAL·리터럴 형 변환·CHECK 식 정규화, 복합 PK·FK·UNIQUE·인덱스, 제약조건 인덱스 제외, 이름 변경 후보·모호함, UNKNOWN")
    void accuracy() {
        Env e = prepared("m4d-acc");
        String before = fingerprint(e);

        // 같은 TABLE : 이름을 모델이 만드는 PK 는 비교하지 않음(NOT_COMPARED)이어도 SAME
        SourceCompareDto.TableDetail same = detail(e, "same_tbl");
        assertThat(same.table().status()).isEqualTo(CompareStatus.SAME);
        assertThat(object(same, ObjectKind.PRIMARY_KEY, "same_tbl_pkey").status()).isEqualTo(CompareStatus.SAME);
        assertThat(attr(object(same, ObjectKind.PRIMARY_KEY, "same_tbl_pkey"), "name").result()).isEqualTo(AttributeResult.NOT_COMPARED);

        SourceCompareDto.TableDetail orders = detail(e, "orders");
        assertThat(orders.table().status()).isEqualTo(CompareStatus.MODIFIED);
        // SERIAL ↔ nextval 기본값, 리터럴 형 변환('NEW'::character varying), CURRENT_TIMESTAMP, numeric(12,2)
        assertThat(object(orders, ObjectKind.COLUMN, "id").status()).isEqualTo(CompareStatus.SAME);
        assertThat(attr(object(orders, ObjectKind.COLUMN, "id"), "default").note()).isEqualTo(SourceCompareDto.Note.SERIAL_DEFAULT);
        assertThat(object(orders, ObjectKind.COLUMN, "status").status()).isEqualTo(CompareStatus.SAME);
        assertThat(object(orders, ObjectKind.COLUMN, "amount").status()).isEqualTo(CompareStatus.SAME);
        assertThat(attr(object(orders, ObjectKind.COLUMN, "amount"), "precision").source()).isEqualTo("12");
        // 타입 차이 (bigint ↔ INTEGER)
        SourceCompareDto.ObjectDiff customerId = object(orders, ObjectKind.COLUMN, "customer_id");
        assertThat(customerId.status()).isEqualTo(CompareStatus.MODIFIED);
        assertThat(attr(customerId, "dataType").result()).isEqualTo(AttributeResult.DIFFERENT);
        // 길이·NULL 허용·DEFAULT 차이
        assertThat(attr(object(orders, ObjectKind.COLUMN, "code"), "length").result()).isEqualTo(AttributeResult.DIFFERENT);
        assertThat(attr(object(orders, ObjectKind.COLUMN, "code"), "nullable").result()).isEqualTo(AttributeResult.DIFFERENT);
        assertThat(attr(object(orders, ObjectKind.COLUMN, "priority"), "default").result()).isEqualTo(AttributeResult.DIFFERENT);
        // 컬럼 순서 (양쪽 모두에 있는 컬럼 사이) : note ↔ created_at 이 바뀌었다
        assertThat(attr(object(orders, ObjectKind.COLUMN, "note"), "order").result()).isEqualTo(AttributeResult.DIFFERENT);
        assertThat(attr(object(orders, ObjectKind.COLUMN, "created_at"), "order").result()).isEqualTo(AttributeResult.DIFFERENT);
        assertThat(attr(object(orders, ObjectKind.COLUMN, "amount"), "order").result()).isEqualTo(AttributeResult.SAME);
        // 원본 COMMENT 는 모델 업무 설명과 비교해 상태를 정하고(description, 2026-11-14), 원본 COMMENT 칸(V3)과의 차이는 가져오기 대상으로만 둔다(comment, NOT_COMPARED)
        assertThat(attr(object(orders, ObjectKind.COLUMN, "note"), "description")).extracting(SourceCompareDto.AttributeDiff::source, SourceCompareDto.AttributeDiff::model,
                SourceCompareDto.AttributeDiff::result).containsExactly("메모", null, AttributeResult.DIFFERENT);
        assertThat(attr(object(orders, ObjectKind.COLUMN, "note"), "comment").result()).isEqualTo(AttributeResult.NOT_COMPARED);
        assertThat(attr(object(orders, ObjectKind.COLUMN, "note"), "comment").model()).isNull();
        assertThat(object(orders, ObjectKind.TABLE, "orders").status()).isEqualTo(CompareStatus.MODIFIED); // TABLE COMMENT '주문' ↔ 한글명 없음
        assertThat(attr(object(orders, ObjectKind.TABLE, "orders"), "korName")).extracting(SourceCompareDto.AttributeDiff::source, SourceCompareDto.AttributeDiff::model)
                .containsExactly("주문", null);
        // 이름 변경 후보 : memo2 ↔ REMARK (하나씩) / region_code·zone_code ↔ AREA_CD (모호함)
        SourceCompareDto.ObjectDiff memo = object(orders, ObjectKind.COLUMN, "memo2");
        assertThat(memo.status()).isEqualTo(CompareStatus.RENAME_CANDIDATE);
        assertThat(memo.candidates()).containsExactly("REMARK");
        assertThat(memo.ambiguous()).isFalse();
        assertThat(object(orders, ObjectKind.COLUMN, "region_code").ambiguous()).isTrue();
        SourceCompareDto.ObjectDiff area = modelObject(orders, ObjectKind.COLUMN, "AREA_CD");
        assertThat(area.status()).isEqualTo(CompareStatus.RENAME_CANDIDATE);
        assertThat(area.candidates()).containsExactlyInAnyOrder("region_code", "zone_code");
        assertThat(area.ambiguous()).isTrue();
        // 원본에만·모델에만 (후보 없음)
        assertThat(object(orders, ObjectKind.COLUMN, "legacy_flag").status()).isEqualTo(CompareStatus.ADDED_IN_SOURCE);
        assertThat(modelObject(orders, ObjectKind.COLUMN, "MODEL_EXTRA").status()).isEqualTo(CompareStatus.ADDED_IN_MODEL);
        assertThat(orders.renameCandidates()).extracting(SourceCompareDto.RenameCandidate::sourceName).contains("memo2", "region_code", "zone_code");

        // UNIQUE : 컬럼 고유값 표시 ↔ 원본 제약조건 (구성으로 짝, 이름 비교 안 함). 제약조건이 만든 인덱스는 인덱스 비교에서 뺀다
        assertThat(object(orders, ObjectKind.UNIQUE, "orders_code_key").status()).isEqualTo(CompareStatus.SAME);
        assertThat(orders.objects()).filteredOn(o -> o.kind() == ObjectKind.INDEX).extracting(SourceCompareDto.ObjectDiff::sourceName)
                .doesNotContain("orders_pkey", "orders_code_key");
        assertThat(orders.warnings()).extracting(SourceCompareDto.Warning::code).contains(SourceCompareDto.WarningCode.CONSTRAINT_INDEX);
        // CHECK : 형 변환·괄호·대소문자만 다르면 SAME, 식 모양이 다르면 UNKNOWN (의미를 추정하지 않는다)
        assertThat(object(orders, ObjectKind.CHECK, "orders_amount_check").status()).isEqualTo(CompareStatus.SAME);
        SourceCompareDto.ObjectDiff statusCheck = object(orders, ObjectKind.CHECK, "orders_status_check");
        assertThat(statusCheck.status()).isEqualTo(CompareStatus.UNKNOWN);
        assertThat(attr(statusCheck, "expression").note()).isEqualTo(SourceCompareDto.Note.EXPRESSION);
        // FK : 이름으로 짝, 참조 대상은 매핑으로 같음. 삭제·수정 규칙은 모델 관계의 규칙과 비교한다 (V7) : 원본 CASCADE ↔ 모델 기본(NO ACTION) = 다름
        SourceCompareDto.ObjectDiff fk = object(orders, ObjectKind.FOREIGN_KEY, "orders_customer_fk");
        assertThat(attr(fk, "referencedTable").result()).isEqualTo(AttributeResult.SAME);
        assertThat(attr(fk, "deleteRule")).extracting(SourceCompareDto.AttributeDiff::source, SourceCompareDto.AttributeDiff::model, SourceCompareDto.AttributeDiff::result)
                .containsExactly("CASCADE", "NO ACTION", AttributeResult.DIFFERENT);
        assertThat(attr(fk, "updateRule").result()).isEqualTo(AttributeResult.SAME);
        assertThat(fk.status()).isEqualTo(CompareStatus.MODIFIED);
        // 모델 관계에 CASCADE 를 두면 같다
        jdbc.update("update TB_TBL_REL set DEL_RULE_CD = 'CASCADE' where PRJ_SN = ? and REL_NM = 'ORDERS_CUSTOMER_FK'", e.project.getId());
        SourceCompareDto.ObjectDiff cascaded = object(detail(e, "orders"), ObjectKind.FOREIGN_KEY, "orders_customer_fk");
        assertThat(attr(cascaded, "deleteRule").result()).isEqualTo(AttributeResult.SAME);
        assertThat(cascaded.status()).isEqualTo(CompareStatus.SAME);
        jdbc.update("update TB_TBL_REL set DEL_RULE_CD = null where PRJ_SN = ? and REL_NM = 'ORDERS_CUSTOMER_FK'", e.project.getId());
        // 인덱스 : 이름으로 짝(정렬 포함 SAME), 이름만 다르고 구성이 같으면 후보
        assertThat(object(orders, ObjectKind.INDEX, "ix_orders_status").status()).isEqualTo(CompareStatus.SAME);
        assertThat(object(orders, ObjectKind.INDEX, "ix_orders_cust_created").status()).isEqualTo(CompareStatus.RENAME_CANDIDATE);
        assertThat(object(orders, ObjectKind.INDEX, "ix_orders_cust_created").candidates()).containsExactly("IX_ORDERS_CUST");

        // 복합 PK·복합 FK·복합 인덱스, 이름이 다른 복합 UNIQUE 는 후보
        SourceCompareDto.TableDetail shipment = detail(e, "shipment");
        assertThat(object(detail(e, "line_item"), ObjectKind.PRIMARY_KEY, "line_item_pkey").status()).isEqualTo(CompareStatus.SAME);
        assertThat(attr(object(detail(e, "line_item"), ObjectKind.PRIMARY_KEY, "line_item_pkey"), "columns").source()).isEqualTo("order_id, line_no");
        assertThat(object(shipment, ObjectKind.FOREIGN_KEY, "shipment_line_fk").status()).isEqualTo(CompareStatus.SAME);
        assertThat(object(shipment, ObjectKind.INDEX, "ix_shipment_line").status()).isEqualTo(CompareStatus.SAME);
        assertThat(object(shipment, ObjectKind.UNIQUE, "shipment_order_line_key").status()).isEqualTo(CompareStatus.RENAME_CANDIDATE);
        // 길이 차이
        assertThat(attr(object(detail(e, "customer"), ObjectKind.COLUMN, "name"), "length").result()).isEqualTo(AttributeResult.DIFFERENT);
        // 별칭을 모르는 타입 : 원문이 다르면 UNKNOWN (SAME·차이로 단정하지 않는다)
        SourceCompareDto.TableDetail unknown = detail(e, "unknown_tbl");
        assertThat(unknown.table().status()).isEqualTo(CompareStatus.UNKNOWN);
        assertThat(attr(object(unknown, ObjectKind.COLUMN, "mood"), "dataType").note()).isEqualTo(SourceCompareDto.Note.UNSUPPORTED_TYPE);
        // kind 로 거르기
        assertThat(service.table(e.manager, null, null, key(e, "orders"), "INDEX,CHECK").objects()).extracting(SourceCompareDto.ObjectDiff::kind)
                .containsOnly(ObjectKind.INDEX, ObjectKind.CHECK);

        // 이름 변경 후보 목록 (비교한 모든 TABLE)
        SourceCompareDto.RenamePage renames = service.renames(e.manager, null, null, null, null, 0, 100);
        assertThat(renames.rows()).extracting(SourceCompareDto.RenameCandidate::sourceName).contains("memo2", "ix_orders_cust_created", "shipment_order_line_key");
        assertThat(service.renames(e.manager, null, null, "COLUMN", null, 0, 100).rows()).allSatisfy(r -> assertThat(r.kind()).isEqualTo(ObjectKind.COLUMN));

        // 읽기 전용 : 모델·매핑·원본 카탈로그가 그대로
        assertThat(fingerprint(e)).isEqualTo(before);
    }

    @Test
    @DisplayName("TABLE 상태 : 비교함·원본에만·후보(UNKNOWN)·스키마 미매핑·모델 VIEW 이름 충돌·모델에만, VIEW 제외. 요약 건수와 필터·검색·쪽")
    void tableStatuses() {
        Env e = prepared("m4d-status");
        tx.executeWithoutResult(s -> viewRepository.save(PhysicalView.builder().projectId(e.project.getId()).schema(schemaRepository.findById(e.defaultSchema.getId()).orElseThrow())
                .viewName("V_TAKEN").viewKorName("V").definition("select 1").isApproval(false).build()));
        Map<String, SourceCompareDto.TableRow> rows = rows(e);
        assertThat(rows.get("public.only_src").status()).isEqualTo(CompareStatus.ADDED_IN_SOURCE);
        assertThat(rows.get("public.only_src").reason()).isEqualTo(TableReason.UNMAPPED);
        assertThat(rows.get("public.cand_tbl").status()).isEqualTo(CompareStatus.UNKNOWN);
        assertThat(rows.get("public.cand_tbl").reason()).isEqualTo(TableReason.MAPPING_CANDIDATE);
        assertThat(rows.get("audit.log").reason()).isEqualTo(TableReason.SCHEMA_UNMAPPED);
        assertThat(rows.get("public.v_taken").status()).isEqualTo(CompareStatus.CONFLICT);
        assertThat(rows.get("public.v_taken").reason()).isEqualTo(TableReason.VIEW_NAME_TAKEN);
        assertThat(rows).doesNotContainKey("public.v_orders");
        SourceCompareDto.TableRow modelOnly = rows.get("DEFAULT.MODEL_ONLY");
        assertThat(modelOnly.status()).isEqualTo(CompareStatus.ADDED_IN_MODEL);
        assertThat(modelOnly.reason()).isEqualTo(TableReason.MODEL_ONLY);
        assertThat(rows.get("public.orders").differences()).isGreaterThan(0);

        SourceCompareDto.Summary summary = service.summary(e.manager);
        assertThat(summary.comparable()).isTrue();
        assertThat(summary.viewCount()).isEqualTo(1);
        assertThat(summary.comparedTables()).isEqualTo(6);
        assertThat(summary.sameTables()).isEqualTo(2); // same_tbl, line_item
        assertThat(summary.differentTables()).isEqualTo(3); // orders, customer, shipment
        assertThat(summary.unknownTables()).isEqualTo(1);
        assertThat(summary.modelOnlyTables()).isEqualTo(1);
        assertThat(summary.unmappedTables()).isEqualTo(4); // only_src, cand_tbl, v_taken, audit.log
        assertThat(summary.reflectedOrder()).isEqualTo(1);

        SourceCompareDto.TablePage modified = service.tables(e.manager, e.connection, 1, null, "MODIFIED", null, 0, 2);
        assertThat(modified.rows()).hasSize(2).allSatisfy(r -> assertThat(r.status()).isEqualTo(CompareStatus.MODIFIED));
        assertThat(modified.total()).isEqualTo(summary.differentTables());
        assertThat(service.tables(e.manager, e.connection, 1, null, "MODIFIED", null, 1, 2).rows()).hasSize((int) Math.min(2, summary.differentTables() - 2));
        assertThat(service.tables(e.manager, null, null, null, null, "ship", 0, 50).rows()).extracting(SourceCompareDto.TableRow::sourceTableName).containsExactly("shipment");
        // 모델에만 있는 TABLE 의 상세 : 하위 객체 비교 없이 행만
        SourceCompareDto.TableDetail onlyModel = service.table(e.manager, e.connection, 1, modelOnly.key(), null);
        assertThat(onlyModel.objects()).isEmpty();
        rejected(() -> service.tables(e.manager, null, null, null, "NOPE", null, 0, 10), ErrorCode.SOURCE_MAPPING_INVALID);
    }

    @Test
    @DisplayName("매핑·수집 상태 : PARTIAL 은 범위 밖을 삭제로 보지 않음, FULL 삭제 → 모델에만(원본 없음), 재생성 → 재확인(UNKNOWN), 모델 삭제 → 원본에만, 모델 이동 → 충돌, 수집 버전이 바뀌면 새 시점으로")
    void mappingAndCollectionStates() {
        Env e = prepared("m4d-coll");
        // PARTIAL(audit 만) : public 의 비교는 그대로
        Map<String, List<SourceCollectionSpec.Table>> auditOnly = new LinkedHashMap<>();
        auditOnly.put("audit", catalog(true).get("audit"));
        collect(e, SourceCollectionSpec.Request.partial(new Namespace(null, Name.of("audit"))), auditOnly);
        assertThat(rows(e).get("public.same_tbl").status()).isEqualTo(CompareStatus.SAME);
        assertThat(service.summary(e.manager).reflectedOrder()).isEqualTo(2);

        // FULL 에서 same_tbl 이 사라짐 → 모델에만 (원본 없음, 삭제 후보 — 자동 삭제 없음)
        collect(e, SourceCollectionSpec.Request.full(), catalog(false));
        SourceCompareDto.TableRow removed = rows(e).get("public.same_tbl");
        assertThat(removed.status()).isEqualTo(CompareStatus.ADDED_IN_MODEL);
        assertThat(removed.reason()).isEqualTo(TableReason.SOURCE_REMOVED);
        assertThat(removed.mappingStatus()).isEqualTo(SourceMappingDto.Status.SOURCE_MISSING);
        // 다시 생김 → 재확인 필요 : 비교하지 않는다 (UNKNOWN)
        collect(e, SourceCollectionSpec.Request.full(), catalog(true));
        SourceCompareDto.TableRow recreated = rows(e).get("public.same_tbl");
        assertThat(recreated.status()).isEqualTo(CompareStatus.UNKNOWN);
        assertThat(recreated.reason()).isEqualTo(TableReason.RECHECK);
        assertThat(service.table(e.manager, null, null, recreated.key(), null).objects()).isEmpty();

        // 모델 TABLE 이동 → 매핑 충돌
        tx.executeWithoutResult(s -> tableRepository.findById(e.table("CUSTOMER").getId()).orElseThrow().setSchema(schemaRepository.findById(e.archiveSchema.getId()).orElseThrow()));
        assertThat(rows(e).get("public.customer").status()).isEqualTo(CompareStatus.CONFLICT);
        assertThat(rows(e).get("public.customer").reason()).isEqualTo(TableReason.MAPPING_CONFLICT);
        // 이제 orders 의 FK 대상(customer)이 유효하게 매핑되지 않음 → 참조 대상 판단 불가
        assertThat(attr(object(detail(e, "orders"), ObjectKind.FOREIGN_KEY, "orders_customer_fk"), "referencedTable").result()).isEqualTo(AttributeResult.UNKNOWN);
        // 모델 TABLE 삭제 → 원본에만 (모델 없음)
        jdbc.update("delete from TB_TBL_IDX_COL where IDX_SN in (select IDX_SN from TB_TBL_IDX where TBL_SN = ?)", e.table("UNKNOWN_TBL").getId());
        jdbc.update("delete from TB_TBL_IDX where TBL_SN = ?", e.table("UNKNOWN_TBL").getId());
        jdbc.update("delete from TB_COL where TBL_SN = ?", e.table("UNKNOWN_TBL").getId());
        jdbc.update("delete from TB_TBL where TBL_SN = ?", e.table("UNKNOWN_TBL").getId());
        assertThat(rows(e).get("public.unknown_tbl").status()).isEqualTo(CompareStatus.ADDED_IN_SOURCE);
        assertThat(rows(e).get("public.unknown_tbl").reason()).isEqualTo(TableReason.MODEL_DELETED);

        // 수집 버전 변경 : 원본 컬럼 길이가 바뀐 새 수집 → 새 시점으로 비교하고, 이전 시점을 보낸 요청은 409
        int order = service.summary(e.manager).reflectedOrder();
        Map<String, List<SourceCollectionSpec.Table>> changed = catalog(true);
        changed.get("public").replaceAll(t -> t.table().name().value().equals("customer") ? customer(120) : t);
        collect(e, SourceCollectionSpec.Request.full(), changed);
        assertThat(service.summary(e.manager).reflectedOrder()).isEqualTo(order + 1);
        rejected(() -> service.tables(e.manager, e.connection, order, null, null, null, 0, 10), ErrorCode.SOURCE_COMPARE_STALE);
        rejected(() -> service.table(e.manager, e.connection, order, key(e, "orders"), null), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(service.tables(e.manager, e.connection, order + 1, null, null, null, 0, 10).reflectedOrder()).isEqualTo(order + 1);
    }

    @Test
    @DisplayName("기준 연결 변경 : 다른 연결의 객체가 섞이지 않고, 이전 기준으로 보낸 요청은 409. 기준·수집이 없으면 비교 불가 안내")
    void baseConnectionChange() {
        Env e = prepared("m4d-base");
        SourceCompareDto.Summary first = service.summary(e.manager);
        Long second = connectionApi.create(e.manager, new SourceConnectionDto.Request("두 번째", "POSTGRESQL", "db2.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(second, true));
        rejected(() -> service.tables(e.manager, e.connection, first.reflectedOrder(), null, null, null, 0, 10), ErrorCode.SOURCE_COMPARE_STALE);
        SourceCompareDto.Summary switched = service.summary(e.manager);
        assertThat(switched.base().id()).isEqualTo(second);
        assertThat(switched.comparable()).isFalse();
        assertThat(switched.notComparable()).isEqualTo("NO_COLLECTION");
        assertThat(service.tables(e.manager, null, null, null, null, null, 0, 10).rows()).isEmpty();

        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(null, true));
        assertThat(service.summary(e.manager).notComparable()).isEqualTo("NO_BASE");
        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        assertThat(service.summary(e.manager).comparedTables()).isEqualTo(first.comparedTables());
    }

    @Test
    @DisplayName("권한·격리 : VIEWER 조회 가능(컨트롤러), 다른 프로젝트의 TABLE key 는 없는 것과 같고 그 프로젝트의 결과에 이 프로젝트의 객체가 없다. 비교는 아무것도 바꾸지 않는다")
    void permissionsAndIsolation() {
        Env e = prepared("m4d-auth");
        String before = fingerprint(e);
        Account viewer = account("m4d-vwr", "ROLE_VIEWER");
        projectMemberRepository.save(ProjectMember.builder().project(e.project).account(viewer).isActive(true).build());
        AccountDto viewerDto = SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        assertThat(api.summary(viewerDto).getBody().comparedTables()).isEqualTo(6);
        assertThat(api.tables(viewerDto, null, null, null, null, null, 0, 10).getBody().rows()).isNotEmpty();
        assertThat(api.table(viewerDto, key(e, "orders"), null, null, null).getBody().objects()).isNotEmpty();
        assertThat(api.renameCandidates(viewerDto, null, null, null, null, 0, 10).getBody().total()).isGreaterThan(0);

        Env other = env("m4d-auth-other");
        mapping.setBase(other.manager, new SourceMappingDto.BaseRequest(other.connection, null));
        collect(other, SourceCollectionSpec.Request.full(), catalog(true));
        rejected(() -> service.table(other.manager, null, null, key(e, "orders"), null), ErrorCode.SOURCE_MAPPING_INVALID);
        List<Long> mine = jdbc.queryForList("select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = ?", Long.class, e.connection);
        assertThat(service.tables(other.manager, null, null, null, null, null, 0, 500).rows()).isNotEmpty()
                .allSatisfy(r -> assertThat(r.sourceTableId()).isNotIn(mine.toArray()));
        // 다른 프로젝트의 연결을 비교 기준으로 보내면 기준이 달라 거절
        rejected(() -> service.tables(other.manager, e.connection, null, null, null, null, 0, 10), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(fingerprint(e)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 예제

    /**
     * public : orders, customer, line_item, shipment, same_tbl, unknown_tbl, only_src(모델 없음), cand_tbl(같은 이름 모델, 미매핑), v_taken(모델 VIEW 이름), v_orders(VIEW).
     * audit : log (스키마 미매핑)
     */
    private Map<String, List<SourceCollectionSpec.Table>> catalog(boolean withSame) {
        Map<String, List<SourceCollectionSpec.Table>> schemas = new LinkedHashMap<>();
        List<SourceCollectionSpec.Table> pub = new ArrayList<>();
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, "주문"),
                List.of(col("id", 1, "bigint", null, null, null, false, "nextval('orders_id_seq'::regclass)", null),
                        col("code", 2, "character varying(20)", 20, null, null, false, null, null),
                        col("amount", 3, "numeric(12,2)", null, new BigDecimal("12"), 2, true, "0", null),
                        col("status", 4, "character varying(10)", 10, null, null, true, "'NEW'::character varying", null),
                        col("note", 6, "text", null, null, null, true, null, "메모"),
                        col("created_at", 7, "timestamp without time zone", null, null, null, false, "CURRENT_TIMESTAMP", null),
                        col("customer_id", 8, "bigint", null, null, null, true, null, null),
                        col("priority", 9, "integer", null, null, null, true, "1", null),
                        col("memo2", 10, "character varying(50)", 50, null, null, true, null, null),
                        col("region_code", 11, "character(2)", 2, null, null, true, null, null),
                        col("zone_code", 12, "character(2)", 2, null, null, true, null, null),
                        col("legacy_flag", 13, "boolean", null, null, null, true, null, null)),
                List.of(constraint("orders_pkey", SourceConstraintType.PRIMARY_KEY, null, "id"), constraint("orders_code_key", SourceConstraintType.UNIQUE, null, "code"),
                        constraint("orders_amount_check", SourceConstraintType.CHECK, "(amount >= (0)::numeric)", "amount"),
                        constraint("orders_status_check", SourceConstraintType.CHECK,
                                "((status)::text = ANY ((ARRAY['NEW'::character varying, 'DONE'::character varying])::text[]))", "status")),
                List.of(index("orders_pkey", true, member("id", null)), index("orders_code_key", true, member("code", null)),
                        index("ix_orders_status", false, member("status", "DESC")), index("ix_orders_cust_created", false, member("customer_id", null), member("created_at", null))),
                List.of(new SourceCatalogSpec.Relation("orders_customer_fk", null, Name.of("public"), Name.of("customer"), "CASCADE", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("customer_id"), Name.of("id")))))));
        pub.add(customer(100));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("line_item"), SourceTableType.TABLE, null),
                List.of(col("order_id", 1, "bigint", null, null, null, false, null, null), col("line_no", 2, "integer", null, null, null, false, null, null)),
                List.of(constraint("line_item_pkey", SourceConstraintType.PRIMARY_KEY, null, "order_id", "line_no")), List.of(index("line_item_pkey", true,
                member("order_id", null), member("line_no", null))), List.of()));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("shipment"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "bigint", null, null, null, false, null, null), col("order_id", 2, "bigint", null, null, null, true, null, null),
                        col("line_no", 3, "integer", null, null, null, true, null, null)),
                List.of(constraint("shipment_pkey", SourceConstraintType.PRIMARY_KEY, null, "id"),
                        constraint("shipment_order_line_key", SourceConstraintType.UNIQUE, null, "order_id", "line_no")),
                List.of(index("shipment_pkey", true, member("id", null)), index("shipment_order_line_key", true, member("order_id", null), member("line_no", null)),
                        index("ix_shipment_line", false, member("order_id", null), member("line_no", null))),
                List.of(new SourceCatalogSpec.Relation("shipment_line_fk", null, Name.of("public"), Name.of("line_item"), "NO ACTION", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("order_id"), Name.of("order_id")), new SourceCatalogSpec.ColumnPair(Name.of("line_no"), Name.of("line_no")))))));
        if (withSame) pub.add(simple("same_tbl"));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("unknown_tbl"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, null, null, false, null, null), col("mood", 2, "mood", null, null, null, true, null, null)),
                List.of(constraint("unknown_tbl_pkey", SourceConstraintType.PRIMARY_KEY, null, "id")), List.of(), List.of()));
        pub.add(simple("only_src"));
        pub.add(simple("cand_tbl"));
        pub.add(simple("v_taken"));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("v_orders"), SourceTableType.VIEW, null),
                List.of(col("id", 1, "bigint", null, null, null, true, null, null)), List.of(), List.of(), List.of()));
        schemas.put("public", pub);
        schemas.put("audit", new ArrayList<>(List.of(simple("log"))));
        return schemas;
    }

    private static SourceCollectionSpec.Table customer(int nameLength) {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("customer"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, null, null, false, null, null), col("name", 2, "character varying(" + nameLength + ")", nameLength, null, null, false, null, null)),
                List.of(constraint("customer_pkey", SourceConstraintType.PRIMARY_KEY, null, "id")), List.of(index("customer_pkey", true, member("id", null))), List.of());
    }

    private static SourceCollectionSpec.Table simple(String name) {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of(name), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, null, null, false, null, null), col("name", 2, "character varying(30)", 30, null, null, true, null, null)),
                List.of(constraint(name + "_pkey", SourceConstraintType.PRIMARY_KEY, null, "id")), List.of(index(name + "_pkey", true, member("id", null))), List.of());
    }

    private static SourceCatalogSpec.Column col(String name, int ordinal, String type, Integer length, BigDecimal precision, Integer scale, boolean nullable, String defaultValue,
                                                String comment) {
        return new SourceCatalogSpec.Column(Name.of(name), ordinal, type, length, precision, scale, nullable, defaultValue, comment);
    }

    private static SourceCatalogSpec.Constraint constraint(String name, SourceConstraintType type, String check, String... columns) {
        return new SourceCatalogSpec.Constraint(name, type, check, java.util.Arrays.stream(columns).map(Name::of).toList());
    }

    private static SourceCatalogSpec.IndexMember member(String column, String sort) {
        return new SourceCatalogSpec.IndexMember(Name.of(column), null, sort);
    }

    private static SourceCatalogSpec.Index index(String name, boolean unique, SourceCatalogSpec.IndexMember... members) {
        return new SourceCatalogSpec.Index(name, "btree", unique, List.of(members), null);
    }

    /** 모델 : DEFAULT 에 ORDERS·CUSTOMER·LINE_ITEM·SHIPMENT·SAME_TBL·UNKNOWN_TBL·CAND_TBL·MODEL_ONLY. 스키마·TABLE 매핑 확정까지 (cand_tbl 은 미매핑) */
    private Env prepared(String name) {
        Env e = env(name);
        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true));
        Long pid = e.project.getId();
        tx.executeWithoutResult(s -> {
            TableInfo orders = table(e, "ORDERS");
            Map<String, ColumnInfo> c = new LinkedHashMap<>();
            c.put("ID", column(orders, "ID", "BIGSERIAL", null, null, null, false, true, false, null, 1));
            c.put("CODE", column(orders, "CODE", "VARCHAR", 30, null, null, true, false, true, null, 2));
            c.put("AMOUNT", column(orders, "AMOUNT", "NUMERIC", null, new BigDecimal("12"), 2, true, false, false, "0", 3));
            c.put("STATUS", column(orders, "STATUS", "VARCHAR", 10, null, null, true, false, false, "NEW", 4));
            c.put("CREATED_AT", column(orders, "CREATED_AT", "TIMESTAMP", null, null, null, false, false, false, "CURRENT_TIMESTAMP", 5));
            c.put("NOTE", column(orders, "NOTE", "TEXT", null, null, null, true, false, false, null, 6));
            c.put("CUSTOMER_ID", column(orders, "CUSTOMER_ID", "INTEGER", null, null, null, true, false, false, null, 7));
            c.put("PRIORITY", column(orders, "PRIORITY", "INTEGER", null, null, null, true, false, false, "2", 8));
            c.put("REMARK", column(orders, "REMARK", "VARCHAR", 50, null, null, true, false, false, null, 9));
            c.put("AREA_CD", column(orders, "AREA_CD", "CHAR", 2, null, null, true, false, false, null, 10));
            c.put("MODEL_EXTRA", column(orders, "MODEL_EXTRA", "DATE", null, null, null, true, false, false, null, 11));
            constraintRepository.save(check(orders, "ORDERS_AMOUNT_CHECK", "AMOUNT >= 0", c.get("AMOUNT")));
            constraintRepository.save(check(orders, "ORDERS_STATUS_CHECK", "STATUS IN ('NEW', 'DONE')", c.get("STATUS")));
            indexRepository.save(index(orders, "IX_ORDERS_STATUS", false, new Object[]{c.get("STATUS"), "DESC"}));
            indexRepository.save(index(orders, "IX_ORDERS_CUST", false, new Object[]{c.get("CUSTOMER_ID"), null}, new Object[]{c.get("CREATED_AT"), null}));

            TableInfo customer = table(e, "CUSTOMER");
            ColumnInfo customerId = column(customer, "ID", "INTEGER", null, null, null, false, true, false, null, 1);
            column(customer, "NAME", "VARCHAR", 80, null, null, false, false, false, null, 2);
            relationRepository.save(relation(orders, customer, "ORDERS_CUSTOMER_FK", List.of(c.get("CUSTOMER_ID")), List.of(customerId)));

            TableInfo line = table(e, "LINE_ITEM");
            ColumnInfo lineOrder = column(line, "ORDER_ID", "BIGINT", null, null, null, false, true, false, null, 1);
            ColumnInfo lineNo = column(line, "LINE_NO", "INTEGER", null, null, null, false, true, false, null, 2);
            TableInfo shipment = table(e, "SHIPMENT");
            column(shipment, "ID", "BIGINT", null, null, null, false, true, false, null, 1);
            ColumnInfo shipOrder = column(shipment, "ORDER_ID", "BIGINT", null, null, null, true, false, false, null, 2);
            ColumnInfo shipLine = column(shipment, "LINE_NO", "INTEGER", null, null, null, true, false, false, null, 3);
            relationRepository.save(relation(shipment, line, "SHIPMENT_LINE_FK", List.of(shipOrder, shipLine), List.of(lineOrder, lineNo)));
            indexRepository.save(index(shipment, "IX_SHIPMENT_LINE", false, new Object[]{shipOrder, null}, new Object[]{shipLine, null}));
            TableConstraint unique = TableConstraint.builder().projectId(pid).tableInfo(shipment).constraintName("UX_SHIPMENT_ORDER_LINE").constraintType(TableConstraint.Type.UNIQUE)
                    .isApproval(false).columns(new ArrayList<>()).build();
            unique.getColumns().add(TableConstraintColumn.builder().tableConstraint(unique).column(shipOrder).columnOrder(1).build());
            unique.getColumns().add(TableConstraintColumn.builder().tableConstraint(unique).column(shipLine).columnOrder(2).build());
            constraintRepository.save(unique);

            for (String simple : List.of("SAME_TBL", "CAND_TBL")) {
                TableInfo t = table(e, simple);
                column(t, "ID", "INTEGER", null, null, null, false, true, false, null, 1);
                column(t, "NAME", "VARCHAR", 30, null, null, true, false, false, null, 2);
            }
            TableInfo unknown = table(e, "UNKNOWN_TBL");
            column(unknown, "ID", "INTEGER", null, null, null, false, true, false, null, 1);
            column(unknown, "MOOD", "MOOD_TYPE", null, null, null, true, false, false, null, 2);
            TableInfo modelOnly = table(e, "MODEL_ONLY");
            column(modelOnly, "ID", "INTEGER", null, null, null, false, true, false, null, 1);
        });
        SourceMappingDto.Overview overview = mapping.overview(e.manager);
        Long publicSchema = overview.schemas().stream().filter(r -> r.sourceSchemaName().equals("public")).findFirst().orElseThrow().sourceSchemaId();
        mapping.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId()));
        for (String t : List.of("orders", "customer", "line_item", "shipment", "same_tbl", "unknown_tbl")) {
            mapping.mapTable(e.manager, new SourceMappingDto.TableRequest(e.sourceTableId("public", t), e.table(t.toUpperCase()).getId()));
        }
        return e;
    }

    private TableInfo table(Env e, String name) {
        // 한글명·업무 설명은 비워 둔다 : TABLE COMMENT ↔ 한글명, 컬럼 COMMENT ↔ 설명 비교(2026-11-14)가 이 시험의 다른 차이에 섞이지 않게 (따로 본다)
        TableInfo table = tableRepository.save(TableInfo.builder().projectId(e.project.getId()).tableName(name).isMasterTable(false)
                .sortOrder(1).isApproval(false).schema(schemaRepository.findById(e.defaultSchema.getId()).orElseThrow()).build());
        e.tables.put(name, table);
        return table;
    }

    private ColumnInfo column(TableInfo table, String name, String type, Integer length, BigDecimal precision, Integer scale, boolean nullable, boolean pk, boolean unique,
                              String defaultValue, int order) {
        return columnRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableInfo(table).tableName(table.getTableName()).columnName(name).dataType(type)
                .maxLength(length).precision(precision).scale(scale).isNullable(nullable).isRequired(false).isPk(pk).isUnique(unique).isIndex(false).defaultValue(defaultValue)
                .sortOrder(order).isApproval(false).isMasterData(false).isSensitive(false).isEncrypted(false).build());
    }

    private static TableConstraint check(TableInfo table, String name, String expression, ColumnInfo column) {
        TableConstraint constraint = TableConstraint.builder().projectId(table.getProjectId()).tableInfo(table).constraintName(name).constraintType(TableConstraint.Type.CHECK)
                .checkExpression(expression).isApproval(false).columns(new ArrayList<>()).build();
        constraint.getColumns().add(TableConstraintColumn.builder().tableConstraint(constraint).column(column).columnOrder(1).build());
        return constraint;
    }

    /** @param members {컬럼, 정렬} */
    private static TableIndex index(TableInfo table, String name, boolean unique, Object[]... members) {
        TableIndex index = TableIndex.builder().projectId(table.getProjectId()).tableInfo(table).indexName(name).isUnique(unique).indexSource(TableIndex.Source.USER)
                .isApproval(false).columns(new ArrayList<>()).build();
        for (int i = 0; i < members.length; i++) {
            index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column((ColumnInfo) members[i][0]).sort((String) members[i][1]).columnOrder(i + 1).build());
        }
        return index;
    }

    private static TableRelation relation(TableInfo source, TableInfo target, String name, List<ColumnInfo> columns, List<ColumnInfo> targets) {
        TableRelation relation = TableRelation.builder().projectId(source.getProjectId()).sourceTable(source).targetTable(target).relationName(name)
                .relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
        for (int i = 0; i < columns.size(); i++) {
            relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(columns.get(i)).targetColumn(targets.get(i)).columnOrder(i + 1).build());
        }
        return relation;
    }

    // ------------------------------------------------------------------ 준비 · 확인

    private final class Env {
        final Project project;
        final AccountDto manager;
        final Long connection;
        final SchemaInfo defaultSchema;
        final SchemaInfo archiveSchema;
        final Map<String, TableInfo> tables = new LinkedHashMap<>();

        Env(Project project, AccountDto manager, Long connection, SchemaInfo defaultSchema, SchemaInfo archiveSchema) {
            this.project = project;
            this.manager = manager;
            this.connection = connection;
            this.defaultSchema = defaultSchema;
            this.archiveSchema = archiveSchema;
        }

        TableInfo table(String name) {
            return tables.get(name);
        }

        Long sourceTableId(String schema, String name) {
            return jdbc.queryForObject("select t.SRC_TBL_SN from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = ? "
                    + "and t.TBL_NM = ?", Long.class, connection, schema, name);
        }
    }

    private Env env(String name) {
        Account owner = account("own", "ROLE_DBA");
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_DBA");
        SchemaInfo defaultSchema = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("DEFAULT").isDefault(true).build()));
        SchemaInfo archive = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("ARCHIVE").isDefault(false).build()));
        Long connection = connectionApi.create(manager, new SourceConnectionDto.Request("원본", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        return new Env(project, manager, connection, defaultSchema, archive);
    }

    private void collect(Env e, SourceCollectionSpec.Request request, Map<String, List<SourceCollectionSpec.Table>> schemas) {
        List<SourceCollectionSpec.Schema> result = new ArrayList<>();
        schemas.forEach((schema, tables) -> result.add(SourceCollectionSpec.Schema.completed(null, Name.of(schema), tables)));
        ExecutionContext context = ExecutionContext.user(e.manager);
        collections.apply(context, collections.start(context, e.connection, request).jobId(), new SourceCollectionSpec.Result(result));
    }

    /** 원본 행 : 원본스키마.원본TABLE, 모델에만 있는 행 : 모델스키마.모델TABLE */
    private Map<String, SourceCompareDto.TableRow> rows(Env e) {
        Map<String, SourceCompareDto.TableRow> rows = new LinkedHashMap<>();
        service.tables(e.manager, null, null, null, null, null, 0, 500).rows()
                .forEach(r -> rows.put(r.sourceTableId() != null ? r.sourceSchemaName() + "." + r.sourceTableName() : r.schemaName() + "." + r.tableName(), r));
        return rows;
    }

    private String key(Env e, String sourceTable) {
        return "S" + e.sourceTableId("public", sourceTable);
    }

    private SourceCompareDto.TableDetail detail(Env e, String sourceTable) {
        return service.table(e.manager, null, null, key(e, sourceTable), null);
    }

    private static SourceCompareDto.ObjectDiff object(SourceCompareDto.TableDetail detail, ObjectKind kind, String sourceName) {
        return detail.objects().stream().filter(o -> o.kind() == kind && sourceName.equals(o.sourceName())).findFirst()
                .orElseThrow(() -> new AssertionError(kind + " " + sourceName + " 없음 : " + detail.objects()));
    }

    private static SourceCompareDto.ObjectDiff modelObject(SourceCompareDto.TableDetail detail, ObjectKind kind, String modelName) {
        return detail.objects().stream().filter(o -> o.kind() == kind && o.sourceName() == null && modelName.equals(o.modelName())).findFirst()
                .orElseThrow(() -> new AssertionError(kind + " " + modelName + " 없음 : " + detail.objects()));
    }

    private static SourceCompareDto.AttributeDiff attr(SourceCompareDto.ObjectDiff object, String name) {
        return object.attributes().stream().filter(a -> a.attribute().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " 없음 : " + object));
    }

    /** 모델(스키마·TABLE·컬럼·제약·인덱스·관계·이력)·매핑·원본 카탈로그의 지문 : 비교가 아무것도 바꾸지 않았는지 */
    private String fingerprint(Env e) {
        Long pid = e.project.getId();
        StringBuilder text = new StringBuilder();
        for (String table : List.of("TB_SCHM", "TB_TBL", "TB_COL", "TB_TBL_CNSTRT", "TB_TBL_IDX", "TB_TBL_REL", "TB_MDL_CHG_HSTRY", "TB_SRC_SCHM_MPNG", "TB_SRC_TBL_MPNG",
                "TB_PRJ_SRC_CNTN")) {
            text.append(table).append('=').append(jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, pid)).append(';');
        }
        text.append(String.join("|", jdbc.queryForList("select COL_SN || ':' || COL_NM || ':' || coalesce(DATA_TYPE_NM, '') || ':' || coalesce(cast(MAX_LEN as varchar(20)), '')"
                + " from TB_COL where PRJ_SN = ? order by COL_SN", String.class, pid)));
        for (String table : List.of("TB_SRC_TBL", "TB_SRC_COL", "TB_SRC_TBL_VER", "TB_SRC_CLCT_EXCN")) {
            String where = table.equals("TB_SRC_COL") ? "SRC_TBL_SN in (select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = ?)" : "SRC_CNTN_SN = ?";
            text.append(';').append(table).append('=').append(jdbc.queryForObject("select count(*) from " + table + " where " + where, Integer.class, e.connection));
        }
        return text.toString();
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(expected);
    }
}
