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
import com.lexorma.data.physical.repository.TableConstraintRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.controller.SourceImportRestController;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Item;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Reason;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.service.SourceCompareService;
import com.lexorma.data.sourcemapping.service.SourceImportService;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.source.connection.service.SourceConnectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원본 메타데이터 선택 가져오기 (M-4e) : 대상(고를 수 있는 것·이유), 계획(읽기 전용·의존 항목), 확정(고른 속성만, 새 TABLE + 매핑, 원본 COMMENT 분리, IMPORT 이력·실행 이력),
 * 재검증(수집·모델·기준 연결 변경, 같은 요청 두 번), 롤백, 동시성(연결 삭제·같은 계획 동시 확정 — PostgreSQL 잠금), 권한·승인.
 * 원본 DB 에 접속하지 않는다 (수집 결과를 직접 넘긴다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceImportIntegrationTest {

    @Autowired SourceImportService service;
    @Autowired SourceImportRestController api;
    @Autowired com.lexorma.data.version.service.ModelVersionService versions;
    @Autowired SourceCompareService compare;
    @Autowired SourceMappingService mapping;
    @Autowired SourceCollectionService collections;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SourceConnectionService connectionService;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TableConstraintRepository constraintRepository;
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
    @DisplayName("대상 : 차이만 고를 수 있고, 판단 불가·이름 변경 후보·승인된 객체·교체가 필요한 것·순서는 이유와 함께 막는다. IDENTITY 와 FK 규칙은 그대로 가져온다 (V7), 생성 컬럼은 빼는 속성으로 보인다")
    void candidates() {
        Env e = prepared("m4e-cand");
        Map<String, Item> customer = items(service.candidates(e.manager, null, null, key(e, "customer")));
        assertThat(customer.get("COLUMN:name:TYPE").selectable()).isTrue();
        assertThat(customer.get("COLUMN:name:TYPE").before()).isEqualTo("VARCHAR(50)");
        assertThat(customer.get("COLUMN:name:TYPE").after()).isEqualTo("VARCHAR(100)");
        assertThat(customer.get("COLUMN:name:NULLABLE").selectable()).isTrue();
        assertThat(customer.get("COLUMN:grade:DEFAULT").reason()).isEqualTo(Reason.APPROVED); // 승인된 컬럼은 바꾸지 않는다
        assertThat(customer.get("COLUMN:amount:TYPE").selectable()).isTrue();
        assertThat(customer.get("COLUMN:note:COMMENT").selectable()).isTrue();
        assertThat(customer.get("COLUMN:email:ADD").selectable()).isTrue();
        assertThat(customer.get("COLUMN:email:COMMENT").dependsOn()).containsExactly("COLUMN:email:ADD");
        assertThat(customer.get("COLUMN:tags:ADD").after()).startsWith("integer[]");
        // IDENTITY 는 모델 속성으로 가져온다 (V7). 생성 컬럼은 모델이 표현하지 못해 만들지 않는다 (속성은 보인다)
        assertThat(customer.get("COLUMN:code:ADD").selectable()).isTrue();
        assertThat(customer.get("COLUMN:code:ADD").excluded()).isEmpty();
        assertThat(customer.get("COLUMN:total:ADD").reason()).isEqualTo(Reason.UNSUPPORTED_ATTRIBUTE);
        assertThat(customer.get("COLUMN:total:ADD").excluded()).singleElement().asString().startsWith("GENERATED ALWAYS AS (");
        // 기존 IDENTITY 컬럼 : 원본 COMMENT 는 IDENTITY 와 무관해 고를 수 있고, 자동 증가 차이는 칸이 없어 고를 수 없다
        assertThat(customer.get("COLUMN:seq_no:COMMENT").selectable()).isTrue();
        assertThat(customer.get("COLUMN:seq_no:AUTO_INCREMENT").reason()).isEqualTo(Reason.NO_FIELD);
        assertThat(customer.get("UNIQUE:customer_email_key").dependsOn()).containsExactly("COLUMN:email:ADD");
        assertThat(customer.get("CHECK:customer_amount_check").selectable()).isTrue();
        assertThat(customer.get("INDEX:ix_customer_name_grade").selectable()).isTrue();
        assertThat(customer.get("TABLE:COMMENT").reason()).isEqualTo(Reason.APPROVED); // 승인된 TABLE
        assertThat(customer.get("COLUMN:legacy_ord:ORDER").reason()).isEqualTo(Reason.ORDER_NOT_SUPPORTED);
        assertThat(customer).doesNotContainKey("PRIMARY_KEY:customer_pkey"); // 같음
        assertThat(customer.keySet()).noneMatch(k -> k.startsWith("COLUMN:MODEL_ONLY")); // 모델에만 있는 컬럼은 지우지 않는다 (대상 아님)

        Map<String, Item> legacy = items(service.candidates(e.manager, null, null, key(e, "legacy")));
        assertThat(legacy.get("COLUMN:nick:ADD").reason()).isEqualTo(Reason.RENAME_CANDIDATE);
        Map<String, Item> orders = items(service.candidates(e.manager, null, null, key(e, "orders")));
        assertThat(orders.get("CHECK:orders_status_check").reason()).isEqualTo(Reason.UNKNOWN);
        assertThat(orders.get("PRIMARY_KEY:orders_pkey").selectable()).isTrue();
        // FK 삭제·수정 규칙은 관계에 그대로 가져온다 (V7) : 빼는 속성이 없다
        assertThat(orders.get("FOREIGN_KEY:orders_customer_fk").excluded()).isEmpty();
        assertThat(orders.get("FOREIGN_KEY:orders_customer_fk").reason()).isNotEqualTo(Reason.UNSUPPORTED_ATTRIBUTE);
        Map<String, Item> audit = items(service.candidates(e.manager, null, null, key(e, "audit_ref")));
        for (String id : List.of("FOREIGN_KEY:audit_set_null", "FOREIGN_KEY:audit_set_default", "FOREIGN_KEY:audit_on_update", "FOREIGN_KEY:audit_plain")) {
            assertThat(audit.get(id).excluded()).as(id).isEmpty();
            assertThat(audit.get(id).selectable()).as(id).isTrue();
        }

        SourceImportDto.TableCandidates line = service.candidates(e.manager, null, null, key(e, "line_item"));
        assertThat(line.create()).isTrue();
        assertThat(line.modelTableName()).isEqualTo("LINE_ITEM");
        assertThat(items(line).get("TABLE:CREATE").selectable()).isTrue();
        assertThat(items(line).get("COLUMN:order_id:ADD").dependsOn()).containsExactly("TABLE:CREATE");
        // 같은 이름의 모델 TABLE 이 있는 미매핑 원본 : 매핑 관리에서 확정 (새로 만들지 않는다)
        assertThat(service.candidates(e.manager, null, null, key(e, "cand_tbl")).tableReason()).isEqualTo(Reason.MAPPING_CANDIDATE);
    }

    @Test
    @DisplayName("계획 : 읽기 전용, 의존 항목이 빠지면 적용할 수 없고, 막힌 항목·없는 항목도 이유가 보인다. 계획만으로는 아무것도 바뀌지 않는다")
    void planIsReadOnly() {
        Env e = prepared("m4e-plan");
        String before = fingerprint(e);
        SourceImportDto.Plan plan = service.plan(e.manager, request(e, Map.of("customer", List.of("UNIQUE:customer_email_key", "COLUMN:grade:DEFAULT", "COLUMN:nope:ADD"))));
        assertThat(plan.applicable()).isFalse();
        Map<String, Item> items = planItems(plan, "customer");
        assertThat(items.get("UNIQUE:customer_email_key").reason()).isEqualTo(Reason.MISSING_DEPENDENCY);
        assertThat(items.get("UNIQUE:customer_email_key").dependsOn()).containsExactly("COLUMN:email:ADD");
        assertThat(items.get("COLUMN:grade:DEFAULT").reason()).isEqualTo(Reason.APPROVED);
        assertThat(items.get("COLUMN:nope:ADD").reason()).isEqualTo(Reason.NOT_FOUND);
        // 적용할 수 없는 계획은 확정할 수 없다
        rejected(() -> service.apply(e.manager, confirm(e, plan, Map.of("customer", List.of("UNIQUE:customer_email_key", "COLUMN:grade:DEFAULT", "COLUMN:nope:ADD")))),
                ErrorCode.SOURCE_IMPORT_INVALID);
        // 화면에서 고를 수 없는 항목을 직접 보내도 계획·확정이 거절한다
        Map<String, List<String>> blocked = Map.of("customer", List.of("COLUMN:total:ADD"), "orders", List.of("CHECK:orders_status_check"));
        SourceImportDto.Plan blockedPlan = service.plan(e.manager, request(e, blocked));
        assertThat(blockedPlan.applicable()).isFalse();
        assertThat(planItems(blockedPlan, "customer").get("COLUMN:total:ADD").reason()).isEqualTo(Reason.UNSUPPORTED_ATTRIBUTE);
        rejected(() -> service.apply(e.manager, confirm(e, blockedPlan, blocked)), ErrorCode.SOURCE_IMPORT_INVALID);
        // FK 대상의 키가 없으면 (orders PK 를 고르지 않음)
        SourceImportDto.Plan fk = service.plan(e.manager, request(e, Map.of("line_item", List.of("TABLE:CREATE", "COLUMN:order_id:ADD", "COLUMN:line_no:ADD",
                "FOREIGN_KEY:line_order_fk"))));
        assertThat(planItems(fk, "line_item").get("FOREIGN_KEY:line_order_fk").reason()).isEqualTo(Reason.TARGET_NOT_KEY);
        assertThat(fingerprint(e)).isEqualTo(before);
    }

    @Test
    @DisplayName("확정 : 고른 속성만 바뀌고(타입만 고르면 NULL 허용은 그대로), 새 컬럼·제약조건·인덱스·새 TABLE·매핑·FK(복합·새 TABLE 끼리)가 미승인으로 생긴다. 원본 COMMENT 는 원본 COMMENT 칸에만, IMPORT 이력과 실행 이력이 남는다")
    void applySelected() {
        Env e = prepared("m4e-apply");
        Map<String, List<String>> selection = new LinkedHashMap<>();
        selection.put("customer", List.of("COLUMN:name:TYPE", "COLUMN:amount:TYPE", "COLUMN:email:ADD", "COLUMN:email:COMMENT", "COLUMN:tags:ADD", "COLUMN:code:ADD",
                "COLUMN:note:COMMENT", "COLUMN:seq_no:COMMENT", "UNIQUE:customer_email_key", "CHECK:customer_amount_check", "INDEX:ix_customer_name_grade"));
        selection.put("orders", List.of("TABLE:COMMENT", "PRIMARY_KEY:orders_pkey"));
        selection.put("line_item", List.of("TABLE:CREATE", "TABLE:COMMENT", "COLUMN:order_id:ADD", "COLUMN:line_no:ADD", "PRIMARY_KEY:line_item_pkey",
                "FOREIGN_KEY:line_order_fk"));
        selection.put("line_note", List.of("TABLE:CREATE", "COLUMN:order_id:ADD", "COLUMN:line_no:ADD", "COLUMN:memo:ADD", "FOREIGN_KEY:line_note_fk"));
        SourceImportDto.Plan plan = service.plan(e.manager, request(e, selection));
        assertThat(plan.tables()).flatExtracting(SourceImportDto.PlanTable::items).filteredOn(i -> !i.selectable()).isEmpty();
        assertThat(plan.applicable()).isTrue();
        assertThat(plan.counts()).isEqualTo(new SourceImportDto.Counts(2, 2, 8, 2, 6, 1, 5)); // 추가 컬럼 8 = IDENTITY 컬럼 CODE 포함 (V7)

        SourceImportDto.Result result = service.apply(e.manager, confirm(e, plan, selection));
        Long pid = e.project.getId();
        // 고른 속성만 : NAME 은 타입만 (NULL 허용은 그대로 true), AMOUNT 정밀도
        Map<String, Object> name = row("select DATA_TYPE_NM, MAX_LEN, NULL_ALWNC_YN, COL_EXPLN from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'NAME'", pid);
        assertThat(value(name, "MAX_LEN")).isEqualTo(100);
        assertThat(value(name, "NULL_ALWNC_YN")).isEqualTo(true);
        assertThat(((Number) value(row("select DATA_PRCSN from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'AMOUNT'", pid), "DATA_PRCSN")).intValue())
                .isEqualTo(12);
        // GRADE 의 DEFAULT 는 고르지 않았다 (승인된 컬럼이기도 하다)
        assertThat(value(row("select BSC_VL from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'GRADE'", pid), "BSC_VL")).isEqualTo("B");
        // 새 컬럼 : 미승인, 원본 COMMENT 는 원본 칸, 업무 설명은 비어 있다. IDENTITY 는 빼고 NOT NULL 만
        Map<String, Object> email = row("select SRC_ANTN_CN, COL_EXPLN, APRV_YN, SORT_ORD from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'EMAIL'", pid);
        assertThat(value(email, "SRC_ANTN_CN")).isEqualTo("이메일");
        assertThat(value(email, "COL_EXPLN")).isNull();
        assertThat(value(email, "APRV_YN")).isEqualTo(false);
        assertThat(value(row("select DATA_TYPE_NM from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'TAGS'", pid), "DATA_TYPE_NM")).isEqualTo("integer[]");
        // 새 IDENTITY 컬럼은 IDENTITY 로 만든다 (V7, 기본값 아님). 생성 컬럼은 만들지 않았다. 기존 IDENTITY 컬럼은 원본 COMMENT 만 반영 (IDENTITY 변경은 아직 가져오지 않는다)
        Map<String, Object> code = row("select AUTO_CRT_MTHD_CD, BSC_VL from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'CODE'", pid);
        assertThat(value(code, "AUTO_CRT_MTHD_CD")).isEqualTo("ALWAYS");
        assertThat(value(code, "BSC_VL")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'TOTAL'", Integer.class, pid)).isZero();
        Map<String, Object> seq = row("select SRC_ANTN_CN, BSC_VL from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'SEQ_NO'", pid);
        assertThat(value(seq, "SRC_ANTN_CN")).isEqualTo("순번");
        assertThat(value(seq, "BSC_VL")).isNull();
        // 기존 컬럼의 원본 COMMENT : 업무 설명은 그대로
        Map<String, Object> note = row("select SRC_ANTN_CN, COL_EXPLN from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'NOTE'", pid);
        assertThat(value(note, "SRC_ANTN_CN")).isEqualTo("메모");
        assertThat(value(note, "COL_EXPLN")).isEqualTo("업무 설명");
        assertThat(value(row("select SRC_ANTN_CN, TBL_EXPLN from TB_TBL where PRJ_SN = ? and TBL_NM = 'ORDERS'", pid), "SRC_ANTN_CN")).isEqualTo("주문");
        assertThat(value(row("select SRC_ANTN_CN, TBL_EXPLN from TB_TBL where PRJ_SN = ? and TBL_NM = 'ORDERS'", pid), "TBL_EXPLN")).isEqualTo("주문 업무");
        // 제약조건·인덱스 (복합·정렬), PK
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_CNSTRT where PRJ_SN = ? and CNSTRT_NM in ('CUSTOMER_EMAIL_KEY', 'CUSTOMER_AMOUNT_CHECK') and APRV_YN = false",
                Integer.class, pid)).isEqualTo(2);
        assertThat(jdbc.queryForList("select c.COL_NM || ':' || coalesce(ic.SORT_CD, '') from TB_TBL_IDX i join TB_TBL_IDX_COL ic on ic.IDX_SN = i.IDX_SN join TB_COL c on c.COL_SN = ic.COL_SN"
                + " where i.PRJ_SN = ? and i.IDX_NM = 'IX_CUSTOMER_NAME_GRADE' order by ic.COL_ORD", String.class, pid)).containsExactly("NAME:", "GRADE:DESC");
        assertThat(value(row("select PK_YN from TB_COL where PRJ_SN = ? and TBL_NM = 'ORDERS' and COL_NM = 'ID'", pid), "PK_YN")).isEqualTo(true);
        // 새 TABLE : 미승인, 매핑 확정(같은 트랜잭션), 복합 PK·새 TABLE 끼리의 복합 FK(IMPORTED)
        assertThat(value(row("select APRV_YN from TB_TBL where PRJ_SN = ? and TBL_NM = 'LINE_ITEM'", pid), "APRV_YN")).isEqualTo(false);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ? and TBL_NM = 'LINE_ITEM' and PK_YN = true", Integer.class, pid)).isEqualTo(2);
        assertThat(jdbc.queryForList("select r.REL_NM || ':' || r.REL_SRC_CD || ':' || t.TBL_NM from TB_TBL_REL r join TB_TBL t on t.TBL_SN = r.TRGT_TBL_SN where r.PRJ_SN = ?"
                + " and r.REL_SRC_CD = 'IMPORTED' order by r.REL_NM", String.class, pid)).containsExactly("LINE_NOTE_FK:IMPORTED:LINE_ITEM", "LINE_ORDER_FK:IMPORTED:ORDERS");
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL_COL rc join TB_TBL_REL r on r.REL_SN = rc.REL_SN where r.REL_NM = 'LINE_NOTE_FK' and r.PRJ_SN = ?",
                Integer.class, pid)).isEqualTo(2);
        Map<String, SourceCompareDto.TableRow> rows = compareRows(e);
        assertThat(rows.get("line_item").mappingStatus()).isEqualTo(SourceMappingDto.Status.MAPPED);
        // 가져오기는 원본 COMMENT 를 원본 COMMENT 칸에만 쓰고 업무 설명은 비워 둔다 → 원본 COMMENT ↔ 모델 설명 비교(2026-11-14)에서 TABLE COMMENT 차이 하나만 남는다
        assertThat(rows.get("line_item").status()).isEqualTo(CompareStatus.MODIFIED);
        assertThat(rows.get("line_item").differencesByKind()).containsExactly(Map.entry(SourceCompareDto.ObjectKind.TABLE, 1));
        assertThat(rows.get("line_note").status()).isEqualTo(CompareStatus.SAME);
        // 이력 : 모델 변경 이력(IMPORT, 변경 그룹 = 요청 식별자)과 실행 이력
        assertThat(jdbc.queryForList("select distinct CHG_SRC_CD from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ?", String.class, pid, result.changeGroupId()))
                .containsExactly("IMPORT");
        SourceImportDto.ExecutionDetail detail = service.execution(e.manager, result.executionId());
        assertThat(detail.execution().reflectedOrder()).isEqualTo(1);
        assertThat(detail.execution().connectionId()).isEqualTo(e.connection);
        assertThat(detail.changes()).extracting(SourceImportDto.Change::targetName).contains("LINE_ITEM", "CUSTOMER.EMAIL", "CUSTOMER.NAME");
        assertThat(service.executions(e.manager, 0, 10).rows()).extracting(SourceImportDto.Execution::changeGroupId).containsExactly(result.changeGroupId());
        // 가져오기 직전 모델 버전 (V-01·V-06·V-07) : 여러 TABLE 이어도 확정 하나에 버전 하나, 실행 이력과 이어진다. 바꾸기 전 상태다
        assertThat(result.modelVersionId()).isNotNull();
        assertThat(result.modelVersionName()).isEqualTo("IMPORT-" + result.changeGroupId());
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, pid)).isEqualTo(1);
        assertThat(detail.execution().summary()).containsEntry("modelVersionName", result.modelVersionName());
        tools.jackson.databind.JsonNode snapshot = versions.snapshot(e.manager, result.modelVersionId()).objects();
        List<String> tablesBefore = new ArrayList<>();
        snapshot.get("PHYSICAL_TABLE").forEach(t -> tablesBefore.add(t.get("name").asString()));
        assertThat(tablesBefore).contains("CUSTOMER", "ORDERS").doesNotContain("LINE_ITEM", "LINE_NOTE"); // 새 TABLE 은 만들기 전 상태
        tools.jackson.databind.JsonNode nameBefore = null;
        tools.jackson.databind.JsonNode customerBefore = null;
        for (tools.jackson.databind.JsonNode c : snapshot.get("PHYSICAL_COLUMN")) if ("CUSTOMER.NAME".equals(c.get("name").asString())) nameBefore = c.get("state");
        for (tools.jackson.databind.JsonNode t : snapshot.get("PHYSICAL_TABLE")) if ("CUSTOMER".equals(t.get("name").asString())) customerBefore = t.get("state");
        assertThat(nameBefore.get("maxLength").asInt()).isEqualTo(50); // 바꾸기 전 값
        assertThat(customerBefore.get("isApproval").asBoolean()).isTrue(); // 승인 상태 그대로 (버전이 승인을 바꾸지 않는다)
        assertThat(versions.compare(e.manager, result.modelVersionId(), null).changes()).extracting(c -> c.change() + ":" + c.name())
                .contains("ADDED:LINE_ITEM", "MODIFIED:CUSTOMER.NAME", "ADDED:CUSTOMER.EMAIL");
        assertThat(value(row("select APRV_YN from TB_TBL where PRJ_SN = ? and TBL_NM = 'CUSTOMER'", pid), "APRV_YN")).isEqualTo(true);
        // 같은 요청을 다시 보내면 거절 (중복 적용·중복 버전 없음, V-03)
        rejected(() -> service.apply(e.manager, confirm(e, plan, selection)), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, pid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'EMAIL'", Integer.class, pid)).isEqualTo(1);
    }

    @Test
    @DisplayName("재검증 : 미리보기 이후 새 수집·모델 변경·기준 연결 변경이 있으면 409 이고 아무것도 바뀌지 않는다. 계획 전체가 한 트랜잭션이라 뒤에서 실패하면 모델·매핑·이력이 모두 롤백된다")
    void revalidationAndRollback() {
        Env e = prepared("m4e-stale");
        Map<String, List<String>> selection = Map.of("customer", List.of("COLUMN:name:TYPE", "COLUMN:email:ADD"), "line_item", List.of("TABLE:CREATE", "COLUMN:order_id:ADD"));
        String before = fingerprint(e);
        // 모델 변경 (NAME 길이)
        SourceImportDto.Plan plan = service.plan(e.manager, request(e, selection));
        jdbc.update("update TB_COL set MAX_LEN = 60 where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'NAME'", e.project.getId());
        rejected(() -> service.apply(e.manager, confirm(e, plan, selection)), ErrorCode.SOURCE_COMPARE_STALE);
        jdbc.update("update TB_COL set MAX_LEN = 50 where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'NAME'", e.project.getId());
        assertThat(fingerprint(e)).isEqualTo(before);
        // 같은 이름의 모델 TABLE 이 그사이 생김 (다른 사용자의 등록)
        SourceImportDto.Plan plan2 = service.plan(e.manager, request(e, selection));
        Long taken = tx.execute(s -> table(e, "LINE_ITEM", false).getId());
        rejected(() -> service.apply(e.manager, confirm(e, plan2, selection)), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, taken)).isZero();
        jdbc.update("delete from TB_TBL where TBL_SN = ?", taken);
        before = fingerprint(e);

        // 롤백 : 확정 뒤 같은 트랜잭션에서 실패
        SourceImportDto.Plan plan3 = service.plan(e.manager, request(e, selection));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            service.apply(e.manager, confirm(e, plan3, selection));
            throw new IllegalStateException("확정 뒤 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(fingerprint(e)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_SRC_CD = 'IMPORT'", Integer.class, e.project.getId())).isZero();

        // 버전을 남기지 못하면 (같은 이름의 버전이 이미 있다) 모델도 바뀌지 않는다 (V-08)
        SourceImportDto.Plan planVersion = service.plan(e.manager, request(e, selection));
        versions.create(e.manager, new com.lexorma.data.version.domain.dto.ModelVersionDto.Create("IMPORT-" + planVersion.requestId(), "미리 있는 버전"));
        String withVersion = fingerprint(e);
        rejected(() -> service.apply(e.manager, confirm(e, planVersion, selection)), ErrorCode.SAVED_DATA_EXISTS);
        assertThat(fingerprint(e)).isEqualTo(withVersion);
        // 새 수집 (반영 순서가 바뀐다)
        SourceImportDto.Plan plan4 = service.plan(e.manager, request(e, selection));
        collect(e, catalog());
        rejected(() -> service.apply(e.manager, confirm(e, plan4, selection)), ErrorCode.SOURCE_COMPARE_STALE);
        // 기준 연결 변경
        SourceImportDto.Plan plan5 = service.plan(e.manager, request(e, selection));
        Long second = connectionApi.create(e.manager, new SourceConnectionDto.Request("두 번째", "POSTGRESQL", "db2.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(second, true));
        rejected(() -> service.apply(e.manager, confirm(e, plan5, selection)), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_IMPRT_EXCN where PRJ_SN = ?", Integer.class, e.project.getId())).isZero();
    }

    @Test
    @DisplayName("동시성 (PostgreSQL 잠금) : 같은 계획 동시 확정은 하나만 반영, 확정 중 연결 삭제는 확정 뒤에 지워 새 매핑까지 정리, 연결 삭제 중 확정은 거절")
    void concurrency() throws Exception {
        Env e = prepared("m4e-race");
        Map<String, List<String>> selection = Map.of("customer", List.of("COLUMN:email:ADD"), "line_item", List.of("TABLE:CREATE", "COLUMN:order_id:ADD"));
        SourceImportDto.Request sameRequest = confirm(e, service.plan(e.manager, request(e, selection)), selection);
        Object[] same = heldThenRun(() -> service.apply(e.manager, sameRequest), () -> service.apply(e.manager, sameRequest));
        assertThat(same[0]).isInstanceOf(SourceImportDto.Result.class);
        assertRejected(same[1], ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'EMAIL'", Integer.class, e.project.getId())).isEqualTo(1);

        // 서로 다른 TABLE 의 두 가져오기 : 프로젝트 배타 잠금으로 차례대로, 교착 없이 둘 다 반영되고 버전도 하나씩
        Env two = prepared("m4e-race-two");
        Map<String, List<String>> left = Map.of("customer", List.of("COLUMN:email:ADD"));
        Map<String, List<String>> right = Map.of("line_item", List.of("TABLE:CREATE", "COLUMN:order_id:ADD"));
        SourceImportDto.Request leftRequest = confirm(two, service.plan(two.manager, request(two, left)), left);
        SourceImportDto.Request rightRequest = confirm(two, service.plan(two.manager, request(two, right)), right);
        Object[] both = heldThenRun(() -> service.apply(two.manager, leftRequest), () -> service.apply(two.manager, rightRequest));
        assertThat(both).allSatisfy(r -> assertThat(r).isInstanceOf(SourceImportDto.Result.class));
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, two.project.getId())).isEqualTo(2);

        // 다른 프로젝트 (T-06) : 한 프로젝트의 확정이 잠금을 잡고 있어도 다른 프로젝트의 확정은 기다리지 않는다 (전역 직렬화 없음)
        Env p1 = prepared("m4e-race-p1");
        Env p2 = prepared("m4e-race-p2");
        SourceImportDto.Request p1Request = confirm(p1, service.plan(p1.manager, request(p1, left)), left);
        SourceImportDto.Request p2Request = confirm(p2, service.plan(p2.manager, request(p2, left)), left);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Object> holder = pool.submit(caught(() -> tx.execute(s -> {
                Object r = service.apply(p1.manager, p1Request);
                held.countDown();
                await(release);
                return r;
            })));
            await(held);
            assertThat(service.apply(p2.manager, p2Request)).isInstanceOf(SourceImportDto.Result.class); // p1 이 커밋 전인데 끝난다
            release.countDown();
            assertThat(holder.get(30, TimeUnit.SECONDS)).isInstanceOf(SourceImportDto.Result.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // 모델 버전 직접 만들기와 경합 (T-09) : 둘 다 프로젝트 행을 배타로 먼저 잡아 차례대로, 교착 없이 둘 다 끝난다
        Env v = prepared("m4e-race-version");
        SourceImportDto.Request vRequest = confirm(v, service.plan(v.manager, request(v, left)), left);
        Object[] withVersion = heldThenRun(() -> service.apply(v.manager, vRequest),
                () -> versions.create(v.manager, new com.lexorma.data.version.domain.dto.ModelVersionDto.Create("사용자 버전", null)));
        assertThat(withVersion[0]).isInstanceOf(SourceImportDto.Result.class);
        assertThat(withVersion[1]).isInstanceOf(com.lexorma.data.version.domain.dto.ModelVersionDto.Summary.class);
        // 사용자 버전은 잠금을 얻은 뒤의 최신 상태 = 가져오기 뒤 (T-09, docs/source-import-m4e.md 12장). 가져오기 직전 버전보다 EMAIL 1개가 더 있다
        Long userVersion = ((com.lexorma.data.version.domain.dto.ModelVersionDto.Summary) withVersion[1]).id();
        List<String> columnsInVersion = new ArrayList<>();
        versions.snapshot(v.manager, userVersion).objects().get("PHYSICAL_COLUMN").forEach(c -> columnsInVersion.add(c.get("name").asString()));
        int importVersionColumns = versions.snapshot(v.manager, ((SourceImportDto.Result) withVersion[0]).modelVersionId()).objects().get("PHYSICAL_COLUMN").size();
        assertThat(columnsInVersion).hasSize(importVersionColumns + 1).contains("CUSTOMER.EMAIL");

        // 확정이 먼저 : 연결 삭제가 기다렸다가 새 TABLE 의 매핑까지 지운다 (고아 매핑 없음, 모델은 남는다)
        Env d = prepared("m4e-race-del");
        Map<String, List<String>> create = Map.of("line_item", List.of("TABLE:CREATE", "COLUMN:order_id:ADD"));
        SourceImportDto.Request createRequest = confirm(d, service.plan(d.manager, request(d, create)), create);
        Object[] deleted = heldThenRun(() -> service.apply(d.manager, createRequest), () -> connectionService.delete(d.manager, d.connection));
        assertThat(deleted[0]).isInstanceOf(SourceImportDto.Result.class);
        assertThat(deleted[1]).isInstanceOf(Integer.class);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL_MPNG where PRJ_SN = ?", Integer.class, d.project.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where PRJ_SN = ? and TBL_NM = 'LINE_ITEM'", Integer.class, d.project.getId())).isEqualTo(1);

        // 연결 삭제가 먼저 : 확정은 기다렸다가 거절되고 아무것도 만들지 않는다
        Env f = prepared("m4e-race-first");
        SourceImportDto.Request lateRequest = confirm(f, service.plan(f.manager, request(f, create)), create);
        Object[] rejectedLate = heldThenRun(() -> connectionService.delete(f.manager, f.connection), () -> service.apply(f.manager, lateRequest));
        assertThat(rejectedLate[1]).isInstanceOf(CustomException.class);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where PRJ_SN = ? and TBL_NM = 'LINE_ITEM'", Integer.class, f.project.getId())).isZero();
    }

    @Test
    @DisplayName("모델 수정과 경합 (T-07) : 다른 사용자가 같은 TABLE 의 컬럼을 바꾸는 중(커밋 전)이어도 교착하지 않는다. 확정은 그 행을 기다리지 않고 409, 그 사용자의 저장은 커밋된다")
    void concurrentModelEdit() throws Exception {
        Env e = prepared("m4e-edit-race");
        Map<String, List<String>> selection = Map.of("customer", List.of("COLUMN:name:TYPE"));
        SourceImportDto.Request confirmRequest = confirm(e, service.plan(e.manager, request(e, selection)), selection);
        Long nameId = jdbc.queryForObject("select COL_SN from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'NAME'", Long.class, e.project.getId());
        // 다른 사용자 : 기존 컬럼 수정 + 새 컬럼 등록 (등록한 트랜잭션은 커밋 직전에 프로젝트 행을 공유 잠금으로 확인한다)
        Callable<Object> edit = () -> {
            ColumnInfo name = columnRepository.findById(nameId).orElseThrow();
            name.setColumnDesc("다른 사용자의 수정");
            column(name.getTableInfo(), "EDITOR_COL", "INTEGER", null, null, null, true, false, null, 99, false, null);
            columnRepository.flush();
            return "committed";
        };
        Object[] results = editThenImport(edit, () -> service.apply(e.manager, confirmRequest));
        assertThat(results[0]).isEqualTo("committed");
        assertRejected(results[1], ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(value(row("select COL_EXPLN from TB_COL where PRJ_SN = ? and TBL_NM = 'CUSTOMER' and COL_NM = 'NAME'", e.project.getId()), "COL_EXPLN"))
                .isEqualTo("다른 사용자의 수정");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, e.project.getId())).isZero();

        // 승인 상태 변경 경합 (T-08) : 대상 TABLE 의 승인 취소가 커밋 전이면 확정은 409, 승인 변경은 그대로 커밋된다 (승인 정책을 우회하지 않는다)
        Map<String, List<String>> orders = Map.of("orders", List.of("TABLE:COMMENT"));
        SourceImportDto.Request ordersRequest = confirm(e, service.plan(e.manager, request(e, orders)), orders);
        Long ordersId = e.tables.get("ORDERS").getId();
        Object[] approval = editThenImport(() -> {
            tableRepository.findById(ordersId).orElseThrow().setIsApproval(true);
            tableRepository.flush();
            return "committed";
        }, () -> service.apply(e.manager, ordersRequest));
        assertThat(approval[0]).isEqualTo("committed");
        assertRejected(approval[1], ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(value(row("select APRV_YN, SRC_ANTN_CN from TB_TBL where TBL_SN = ?", ordersId), "SRC_ANTN_CN")).isNull();
        // 이제 승인된 TABLE : 새로 계획하면 원본 COMMENT 는 막힌다
        assertThat(planItems(service.plan(e.manager, request(e, orders)), "orders").get("TABLE:COMMENT").reason()).isEqualTo(Reason.APPROVED);
    }

    /** 수정 트랜잭션을 커밋 전까지 진행해 둔 채 확정을 부르고, 확정이 끝나거나 잠금을 기다리기 시작하면 수정을 커밋시킨다. 두 결과(또는 예외 문구) */
    private Object[] editThenImport(Callable<Object> edit, Callable<Object> importCall) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Object> editor = pool.submit(() -> {
                try {
                    return tx.execute(s -> {
                        Object result;
                        try {
                            result = edit.call();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                        held.countDown();
                        await(release);
                        return result;
                    });
                } catch (Exception ex) {
                    return "editor failed : " + rootMessage(ex);
                }
            });
            await(held);
            Future<Object> importer = pool.submit(() -> {
                try {
                    return importCall.call();
                } catch (CustomException ex) {
                    return ex;
                } catch (Exception ex) {
                    return "import failed : " + rootMessage(ex);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!importer.isDone() && System.nanoTime() < deadline) {
                String sql = postgres() ? "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                        : "select count(*) from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
                if (jdbc.queryForObject(sql, Integer.class) > 0) break;
                Thread.sleep(5);
            }
            release.countDown();
            return new Object[]{editor.get(60, TimeUnit.SECONDS), importer.get(60, TimeUnit.SECONDS)};
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) root = root.getCause();
        return root.getClass().getSimpleName() + " " + root.getMessage();
    }

    @Test
    @DisplayName("권한 : VIEWER 는 대상·계획·이력 조회만(확정은 역할에서 거절), 관리자가 아닌 USER 는 확정 거절, 다른 프로젝트의 TABLE 은 없는 것과 같다")
    void permissions() {
        Env e = prepared("m4e-auth");
        Account viewer = account("m4e-vwr", "ROLE_VIEWER");
        Account user = account("m4e-usr", "ROLE_USER");
        for (Account member : List.of(viewer, user)) projectMemberRepository.save(ProjectMember.builder().project(e.project).account(member).isActive(true).build());
        AccountDto viewerDto = SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        Map<String, List<String>> selection = Map.of("customer", List.of("COLUMN:email:ADD"));
        assertThat(api.candidates(viewerDto, key(e, "customer"), null, null).getBody().items()).isNotEmpty();
        SourceImportDto.Plan plan = api.plan(viewerDto, request(e, selection)).getBody();
        assertThat(plan.applicable()).isTrue();
        assertThat(api.executions(viewerDto, 0, 10).getBody().total()).isZero();
        assertThatThrownBy(() -> api.apply(viewerDto, confirm(e, plan, selection))).isInstanceOf(AccessDeniedException.class);
        AccountDto userDto = SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(user.getId()), "ROLE_USER");
        rejected(() -> api.apply(userDto, confirm(e, plan, selection)), ErrorCode.EXECUTION_PERMISSION_DENIED);
        Env other = prepared("m4e-auth-other");
        rejected(() -> service.candidates(other.manager, null, null, key(e, "customer")), ErrorCode.SOURCE_IMPORT_INVALID);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ? and COL_NM = 'EMAIL'", Integer.class, e.project.getId())).isZero();
    }

    // ------------------------------------------------------------------ 예제

    /**
     * public : customer(매핑, 승인된 TABLE), orders(매핑, PK 없음), legacy(매핑, 이름 변경 후보), line_item·line_note(미매핑 → 새 TABLE), cand_tbl(같은 이름 모델 후보)
     */
    private static Map<String, List<SourceCollectionSpec.Table>> catalog() {
        List<SourceCollectionSpec.Table> pub = new ArrayList<>();
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("customer"), SourceTableType.TABLE, "고객"),
                List.of(col("id", 1, "integer", null, null, null, false, null, null), col("name", 2, "character varying(100)", 100, null, null, false, null, null),
                        col("email", 3, "character varying(200)", 200, null, null, true, null, "이메일"),
                        col("grade", 4, "character(1)", 1, null, null, true, "'A'::bpchar", null),
                        col("amount", 5, "numeric(12,2)", null, new BigDecimal("12"), 2, true, null, null), col("note", 6, "text", null, null, null, true, null, "메모"),
                        col("tags", 7, "integer[]", null, null, null, true, null, null), col("code", 8, "bigint", null, null, null, false, "GENERATED ALWAYS AS IDENTITY", null),
                        col("legacy_ord", 10, "integer", null, null, null, true, null, null), col("legacy_first", 9, "integer", null, null, null, true, null, null),
                        col("seq_no", 11, "bigint", null, null, null, false, "GENERATED BY DEFAULT AS IDENTITY", "순번"),
                        col("total", 12, "numeric", null, null, null, true, "GENERATED ALWAYS AS ((amount * (2)::numeric)) STORED", null)),
                List.of(constraint("customer_pkey", SourceConstraintType.PRIMARY_KEY, null, "id"), constraint("customer_email_key", SourceConstraintType.UNIQUE, null, "email"),
                        constraint("customer_amount_check", SourceConstraintType.CHECK, "(amount >= (0)::numeric)", "amount")),
                List.of(index("customer_pkey", true, member("id", null)), index("customer_email_key", true, member("email", null)),
                        index("ix_customer_name_grade", false, member("name", null), member("grade", "DESC"))),
                List.of()));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, "주문"),
                List.of(col("id", 1, "bigint", null, null, null, false, null, null), col("customer_id", 2, "integer", null, null, null, true, null, null),
                        col("status", 3, "character varying(10)", 10, null, null, true, null, null)),
                List.of(constraint("orders_pkey", SourceConstraintType.PRIMARY_KEY, null, "id"),
                        constraint("orders_status_check", SourceConstraintType.CHECK, "((status)::text = ANY ((ARRAY['A'::character varying])::text[]))", "status")),
                List.of(index("orders_pkey", true, member("id", null))),
                List.of(new SourceCatalogSpec.Relation("orders_customer_fk", null, Name.of("public"), Name.of("customer"), "CASCADE", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("customer_id"), Name.of("id")))))));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("legacy"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, null, null, false, null, null), col("nick", 2, "character varying(30)", 30, null, null, true, null, null)),
                List.of(constraint("legacy_pkey", SourceConstraintType.PRIMARY_KEY, null, "id")), List.of(), List.of()));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("line_item"), SourceTableType.TABLE, "주문 항목"),
                List.of(col("order_id", 1, "bigint", null, null, null, false, null, null), col("line_no", 2, "integer", null, null, null, false, null, null)),
                List.of(constraint("line_item_pkey", SourceConstraintType.PRIMARY_KEY, null, "order_id", "line_no")),
                List.of(index("line_item_pkey", true, member("order_id", null), member("line_no", null))),
                List.of(new SourceCatalogSpec.Relation("line_order_fk", null, Name.of("public"), Name.of("orders"), "NO ACTION", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("order_id"), Name.of("id")))))));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("line_note"), SourceTableType.TABLE, null),
                List.of(col("order_id", 1, "bigint", null, null, null, false, null, null), col("line_no", 2, "integer", null, null, null, false, null, null),
                        col("memo", 3, "text", null, null, null, true, null, null)),
                List.of(), List.of(),
                List.of(new SourceCatalogSpec.Relation("line_note_fk", null, Name.of("public"), Name.of("line_item"), "NO ACTION", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("order_id"), Name.of("order_id")), new SourceCatalogSpec.ColumnPair(Name.of("line_no"), Name.of("line_no")))))));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("audit_ref"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "bigint", null, null, null, false, null, null), col("a_id", 2, "integer", null, null, null, true, null, null),
                        col("b_id", 3, "integer", null, null, null, true, null, null), col("c_id", 4, "integer", null, null, null, true, null, null),
                        col("d_id", 5, "integer", null, null, null, true, null, null)),
                List.of(), List.of(),
                List.of(fk("audit_set_null", "a_id", "SET NULL", "NO ACTION"), fk("audit_set_default", "b_id", "SET DEFAULT", "NO ACTION"),
                        fk("audit_on_update", "c_id", "NO ACTION", "CASCADE"), fk("audit_plain", "d_id", "NO ACTION", "NO ACTION"))));
        pub.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("cand_tbl"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, null, null, false, null, null)), List.of(), List.of(), List.of()));
        Map<String, List<SourceCollectionSpec.Table>> schemas = new LinkedHashMap<>();
        schemas.put("public", pub);
        return schemas;
    }

    private static SourceCatalogSpec.Relation fk(String name, String column, String deleteRule, String updateRule) {
        return new SourceCatalogSpec.Relation(name, null, Name.of("public"), Name.of("customer"), deleteRule, updateRule,
                List.of(new SourceCatalogSpec.ColumnPair(Name.of(column), Name.of("id"))));
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

    /** 모델 : CUSTOMER(승인, GRADE 승인), ORDERS(PK 없음, 같은 이름 CHECK 다른 식), LEGACY(NICKNAME), CAND_TBL. 스키마·TABLE 매핑 확정까지 */
    private Env prepared(String name) {
        Env e = env(name);
        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, catalog());
        tx.executeWithoutResult(s -> {
            TableInfo customer = table(e, "CUSTOMER", true);
            column(customer, "ID", "INTEGER", null, null, null, false, true, null, 1, false, null);
            column(customer, "NAME", "VARCHAR", 50, null, null, true, false, null, 2, false, null);
            column(customer, "GRADE", "CHAR", 1, null, null, true, false, "B", 3, true, null);
            column(customer, "AMOUNT", "NUMERIC", null, new BigDecimal("10"), 2, true, false, null, 4, false, null);
            column(customer, "NOTE", "TEXT", null, null, null, true, false, null, 5, false, "업무 설명");
            column(customer, "LEGACY_ORD", "INTEGER", null, null, null, true, false, null, 6, false, null);
            column(customer, "LEGACY_FIRST", "INTEGER", null, null, null, true, false, null, 7, false, null);
            column(customer, "MODEL_ONLY", "INTEGER", null, null, null, true, false, null, 8, false, null);
            column(customer, "SEQ_NO", "BIGINT", null, null, null, false, false, null, 9, false, null);
            TableInfo orders = table(e, "ORDERS", false);
            orders.setTableDescription("주문 업무");
            column(orders, "ID", "BIGINT", null, null, null, false, false, null, 1, false, null);
            column(orders, "CUSTOMER_ID", "INTEGER", null, null, null, true, false, null, 2, false, null);
            ColumnInfo status = column(orders, "STATUS", "VARCHAR", 10, null, null, true, false, null, 3, false, null);
            TableConstraint check = TableConstraint.builder().projectId(orders.getProjectId()).tableInfo(orders).constraintName("ORDERS_STATUS_CHECK")
                    .constraintType(TableConstraint.Type.CHECK).checkExpression("STATUS IN ('A')").isApproval(false).columns(new ArrayList<>()).build();
            check.getColumns().add(TableConstraintColumn.builder().tableConstraint(check).column(status).columnOrder(1).build());
            constraintRepository.save(check);
            TableInfo legacy = table(e, "LEGACY", false);
            column(legacy, "ID", "INTEGER", null, null, null, false, true, null, 1, false, null);
            column(legacy, "NICKNAME", "VARCHAR", 30, null, null, true, false, null, 2, false, null);
            TableInfo cand = table(e, "CAND_TBL", false);
            column(cand, "ID", "INTEGER", null, null, null, false, true, null, 1, false, null);
        });
        Long publicSchema = mapping.overview(e.manager).schemas().getFirst().sourceSchemaId();
        mapping.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(publicSchema, e.schema.getId()));
        for (String t : List.of("customer", "orders", "legacy")) {
            mapping.mapTable(e.manager, new SourceMappingDto.TableRequest(e.sourceTableId(t), e.tables.get(t.toUpperCase()).getId()));
        }
        return e;
    }

    private TableInfo table(Env e, String name, boolean approved) {
        TableInfo table = tableRepository.save(TableInfo.builder().projectId(e.project.getId()).tableName(name).tableKorName(name).tableDescription(name).isMasterTable(false)
                .sortOrder(1).isApproval(approved).schema(schemaRepository.findById(e.schema.getId()).orElseThrow()).build());
        e.tables.put(name, table);
        return table;
    }

    private ColumnInfo column(TableInfo table, String name, String type, Integer length, BigDecimal precision, Integer scale, boolean nullable, boolean pk,
                              String defaultValue, int order, boolean approved, String description) {
        return columnRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableInfo(table).tableName(table.getTableName()).columnName(name).dataType(type)
                .maxLength(length).precision(precision).scale(scale).isNullable(nullable).isRequired(false).isPk(pk).isUnique(false).isIndex(false).defaultValue(defaultValue)
                .sortOrder(order).isApproval(approved).isMasterData(false).isSensitive(false).isEncrypted(false).columnDesc(description).build());
    }

    private final class Env {
        final Project project;
        final AccountDto manager;
        final Long connection;
        final SchemaInfo schema;
        final Map<String, TableInfo> tables = new LinkedHashMap<>();

        Env(Project project, AccountDto manager, Long connection, SchemaInfo schema) {
            this.project = project;
            this.manager = manager;
            this.connection = connection;
            this.schema = schema;
        }

        Long sourceTableId(String name) {
            return jdbc.queryForObject("select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = ? and TBL_NM = ?", Long.class, connection, name);
        }
    }

    private Env env(String name) {
        Account owner = account("own", "ROLE_DBA");
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_DBA");
        SchemaInfo schema = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("DEFAULT").isDefault(true).build()));
        Long connection = connectionApi.create(manager, new SourceConnectionDto.Request("원본", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        return new Env(project, manager, connection, schema);
    }

    private void collect(Env e, Map<String, List<SourceCollectionSpec.Table>> schemas) {
        List<SourceCollectionSpec.Schema> result = new ArrayList<>();
        schemas.forEach((schema, tables) -> result.add(SourceCollectionSpec.Schema.completed(null, Name.of(schema), tables)));
        ExecutionContext context = ExecutionContext.user(e.manager);
        collections.apply(context, collections.start(context, e.connection, SourceCollectionSpec.Request.full()).jobId(), new SourceCollectionSpec.Result(result));
    }

    private String key(Env e, String sourceTable) {
        return "S" + e.sourceTableId(sourceTable);
    }

    private SourceImportDto.Request request(Env e, Map<String, List<String>> selection) {
        List<SourceImportDto.TableSelection> tables = new ArrayList<>();
        selection.forEach((table, items) -> tables.add(new SourceImportDto.TableSelection(key(e, table), null, items)));
        return new SourceImportDto.Request(e.connection, null, tables, null, null);
    }

    private SourceImportDto.Request confirm(Env e, SourceImportDto.Plan plan, Map<String, List<String>> selection) {
        SourceImportDto.Request request = request(e, selection);
        return new SourceImportDto.Request(plan.connectionId(), plan.reflectedOrder(), request.tables(), plan.planToken(), plan.requestId());
    }

    private static Map<String, Item> items(SourceImportDto.TableCandidates candidates) {
        Map<String, Item> items = new LinkedHashMap<>();
        candidates.items().forEach(i -> items.put(i.id(), i));
        return items;
    }

    private static Map<String, Item> planItems(SourceImportDto.Plan plan, String sourceTable) {
        Map<String, Item> items = new LinkedHashMap<>();
        plan.tables().stream().filter(t -> t.sourceTableName().endsWith("." + sourceTable)).findFirst().orElseThrow().items().forEach(i -> items.put(i.id(), i));
        return items;
    }

    private Map<String, SourceCompareDto.TableRow> compareRows(Env e) {
        Map<String, SourceCompareDto.TableRow> rows = new LinkedHashMap<>();
        compare.tables(e.manager, null, null, null, null, null, 0, 500).rows().stream().filter(r -> r.sourceTableName() != null).forEach(r -> rows.put(r.sourceTableName(), r));
        return rows;
    }

    private Map<String, Object> row(String sql, Long projectId) {
        return jdbc.queryForMap(sql, projectId);
    }

    private static Object value(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(column)) return entry.getValue();
        return null;
    }

    /** 모델·매핑·이력·실행 이력의 지문 */
    private String fingerprint(Env e) {
        Long pid = e.project.getId();
        StringBuilder text = new StringBuilder();
        for (String table : List.of("TB_TBL", "TB_COL", "TB_TBL_CNSTRT", "TB_TBL_IDX", "TB_TBL_REL", "TB_MDL_CHG_HSTRY", "TB_SRC_TBL_MPNG", "TB_SRC_IMPRT_EXCN",
                "TB_MDL_VER")) {
            text.append(table).append('=').append(jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, pid)).append(';');
        }
        text.append(String.join("|", jdbc.queryForList("select COL_SN || ':' || COL_NM || ':' || coalesce(DATA_TYPE_NM, '') || ':' || coalesce(cast(MAX_LEN as varchar(20)), '')"
                + " || ':' || coalesce(SRC_ANTN_CN, '') from TB_COL where PRJ_SN = ? order by COL_SN", String.class, pid)));
        return text.toString();
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    // ------------------------------------------------------------------ 동시성 도우미 (SourceMappingIntegrationTest 와 같은 방식 : DB 잠금 대기로 순서를 정한다)

    private Object[] heldThenRun(Callable<Object> first, Callable<Object> then) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Object> holder = pool.submit(caught(() -> tx.execute(s -> {
                Object result;
                try {
                    result = first.call();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                held.countDown();
                await(release);
                return result;
            })));
            await(held);
            Future<Object> waiter = pool.submit(caught(then));
            try {
                awaitLockWait(waiter);
            } finally {
                release.countDown();
            }
            return new Object[]{holder.get(30, TimeUnit.SECONDS), waiter.get(30, TimeUnit.SECONDS)};
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitLockWait(Future<?> waiter) throws InterruptedException {
        String sql = postgres()
                ? "select query from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select EXECUTING_STATEMENT from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertThat(waiter.isDone()).as("잠금을 기다리지 않고 끝났다 (경합이 일어나지 않았다)").isFalse();
            if (!jdbc.queryForList(sql, String.class).isEmpty()) return;
            Thread.sleep(5);
        }
        throw new AssertionError("잠금 대기가 생기지 않았다");
    }

    private boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("기다리는 신호가 오지 않았다");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Callable<Object> caught(Callable<Object> body) {
        return () -> {
            try {
                return body.call();
            } catch (CustomException e) {
                return e;
            } catch (IllegalStateException e) {
                if (e.getCause() instanceof CustomException c) return c;
                throw e;
            }
        };
    }

    private static void assertRejected(Object result, ErrorCode... expected) {
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isIn((Object[]) expected);
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(expected);
    }
}
