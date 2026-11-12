package com.koboolean.metamora;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.domain.enums.DbType;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.AccountRepository;
import com.koboolean.metamora.core.support.SecurityTestSupport;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.column.repository.ColumnInfoRepository;
import com.koboolean.metamora.data.erd.controller.ErdRestController;
import com.koboolean.metamora.data.physical.controller.PhysicalModelRestController;
import com.koboolean.metamora.data.physical.domain.dto.PhysicalModelDto;
import com.koboolean.metamora.data.physical.domain.dto.PhysicalModelDto.ColumnPair;
import com.koboolean.metamora.data.physical.domain.dto.PhysicalModelDto.Relation;
import com.koboolean.metamora.data.schema.domain.dto.SchemaDto;
import com.koboolean.metamora.data.schema.service.SchemaService;
import com.koboolean.metamora.data.table.controller.TableDesignRestController;
import com.koboolean.metamora.data.table.domain.dto.TableDdlDto;
import com.koboolean.metamora.data.table.domain.dto.TableInfoDto;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
import com.koboolean.metamora.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-8 안정화 (H2 / PostgreSQL) : 화면이 아니라 서버가 지키는 물리 모델 정합성.
 * ① 승인된 테이블은 역할과 관계없이 API 직접 호출로도 수정할 수 없다 ② FK 는 대상 테이블의 키 전체를, 프로젝트 대상 DB 에서 묶일 수 있는 타입으로만 참조한다.
 * 검증을 넣기 전에 저장된 FK 는 지우거나 바꾸지 않고, 조회되며, DDL 은 경고와 함께 건너뛴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelIntegrityHardeningIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired PhysicalModelRestController physical;
    @Autowired TableDesignRestController tables;
    @Autowired ErdRestController erd;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 승인된 테이블 수정 보호

    @Test
    @DisplayName("승인된 테이블은 어떤 역할도 API 로 수정할 수 없고, 승인 대기·승인취소 상태에서는 수정된다. 조회 권한·다른 프로젝트는 수정할 수 없다")
    void approvedTableUpdate() {
        AccountDto admin = loginAsAdmin("hard-table", DbType.POSTGRESQL);
        long projectId = admin.getProjectId();
        TableInfo pending = table(projectId, "TB_PENDING", false, null);
        TableInfo approved = table(projectId, "TB_APPROVED", true, null);
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", null, null));

        // 승인 대기 : 일반 사용자도 수정한다 (기존 동작)
        AccountDto user = login(projectId, "951", "ROLE_USER");
        tables.updateTable(update(pending, "TB_PENDING", "설명 수정", null), user);
        assertThat(reload(pending).getTableDescription()).isEqualTo("설명 수정");

        // 승인 완료 : 이름·설명·스키마 어느 것도, 어떤 역할도 수정할 수 없다 → 저장된 값 그대로
        for (String role : List.of("ROLE_USER", "ROLE_DBA", "ROLE_MANAGER", "ROLE_ADMIN")) {
            AccountDto account = login(projectId, "95" + role.length(), role);
            rejected(() -> tables.updateTable(update(approved, "TB_RENAMED", null, null), account), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
            rejected(() -> tables.updateTable(update(approved, "TB_APPROVED", "설명만", null), account), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
            rejected(() -> tables.updateTable(update(approved, "TB_APPROVED", null, sales.id()), account), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        }
        TableInfo kept = reload(approved);
        assertThat(kept.getTableName()).isEqualTo("TB_APPROVED");
        assertThat(kept.getTableDescription()).isNull();
        assertThat(kept.getSchema().getIsDefault()).isTrue();

        // 조회 권한은 승인 여부와 관계없이 수정할 수 없다
        AccountDto viewer = login(projectId, "950", "ROLE_VIEWER");
        assertThatThrownBy(() -> tables.updateTable(update(pending, "TB_PENDING", "뷰어", null), viewer)).isInstanceOf(AccessDeniedException.class);

        // 승인취소(승인 권한) 후에는 수정된다. 일반 사용자는 승인취소할 수 없다
        rejected(() -> tables.updateTableIsApproval(false, login(projectId, "951", "ROLE_USER"), List.of(dto(approved))), ErrorCode.APPROVAL_PERMISSION_DENIED);
        AccountDto manager = login(projectId, "952", "ROLE_MANAGER");
        tables.updateTableIsApproval(false, manager, List.of(dto(approved)));
        tables.updateTable(update(approved, "TB_RENAMED", "이름 변경", sales.id()), login(projectId, "951", "ROLE_USER"));
        TableInfo renamed = reload(approved);
        assertThat(renamed.getTableName()).isEqualTo("TB_RENAMED");
        assertThat(renamed.getSchema().getId()).isEqualTo(sales.id());

        // 다른 프로젝트 : 테이블 ID 를 알아도 수정·승인할 수 없다 (예전에는 NullPointerException 으로 500 이었다)
        AccountDto other = loginAsAdmin("hard-table-other", DbType.POSTGRESQL);
        rejected(() -> tables.updateTable(update(pending, "TB_STOLEN", null, null), other), ErrorCode.TABLE_IS_NOT_DEFINED);
        rejected(() -> tables.updateTableIsApproval(true, other, List.of(dto(pending))), ErrorCode.TABLE_IS_NOT_DEFINED);
        assertThat(reload(pending).getTableName()).isEqualTo("TB_PENDING");
        assertThat(reload(pending).getIsApproval()).isFalse();
    }

    @Test
    @DisplayName("승인 처리와 수정 요청이 겹치면 행 잠금으로 줄을 선다 : 승인이 먼저 잡았으면 뒤따른 수정은 승인된 상태를 보고 거절된다")
    void approvalAndUpdateRace() throws Exception {
        AccountDto admin = loginAsAdmin("hard-race", DbType.POSTGRESQL);
        long projectId = admin.getProjectId();
        TableInfo table = table(projectId, "TB_RACE", false, null);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch updating = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 승인 : 행을 잠근 채로 수정 요청이 들어올 때까지 커밋을 미룬다
            Future<?> approval = pool.submit(() -> {
                SecurityTestSupport.loginAs(projectRepository, projectId, String.valueOf(admin.getId()), "ROLE_ADMIN");
                try {
                    tx.executeWithoutResult(status -> {
                        tableDesignService.updateTableIsApproval(true, admin, List.of(dto(table)));
                        locked.countDown();
                        await(updating);
                        sleep(300); // 수정 요청이 잠금을 기다리는 동안
                    });
                } finally {
                    SecurityTestSupport.clear();
                }
            });
            Future<ErrorCode> update = pool.submit(() -> {
                await(locked);
                updating.countDown();
                try {
                    tableDesignService.updateTable(update(table, "TB_RACE_V2", "승인과 겹친 수정", null), admin);
                    return null;
                } catch (CustomException e) {
                    return e.getErrorCode();
                }
            });
            approval.get(20, TimeUnit.SECONDS);
            assertThat(update.get(20, TimeUnit.SECONDS)).isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        } finally {
            pool.shutdownNow();
        }
        TableInfo after = reload(table);
        assertThat(after.getIsApproval()).isTrue();
        assertThat(after.getTableName()).isEqualTo("TB_RACE");
    }

    // ------------------------------------------------------------------ FK 참조 키

    @Test
    @DisplayName("FK 대상은 PK 전체·고유값 컬럼·UNIQUE 제약·고유 인덱스의 컬럼 전체여야 한다. 일반 컬럼·키의 일부·서로 다른 키의 조합은 거절된다")
    void foreignKeyTargetKey() {
        AccountDto admin = loginAsAdmin("hard-fk-key", DbType.POSTGRESQL);
        long projectId = admin.getProjectId();
        TableInfo code = table(projectId, "TB_CODE", true, null);
        ColumnInfo grp = column(projectId, code, "GRP_CD", "VARCHAR(10)", true);
        ColumnInfo cd = column(projectId, code, "CD", "VARCHAR(10)", true);
        ColumnInfo name = column(projectId, code, "CD_NM", "VARCHAR(10)", false);
        ColumnInfo alias = column(projectId, code, "ALIAS", "VARCHAR(10)", false);
        ColumnInfo alt1 = column(projectId, code, "ALT_1", "VARCHAR(10)", false);
        ColumnInfo alt2 = column(projectId, code, "ALT_2", "VARCHAR(10)", false);
        ColumnInfo ix1 = column(projectId, code, "IX_1", "VARCHAR(10)", false);
        ColumnInfo ix2 = column(projectId, code, "IX_2", "VARCHAR(10)", false);
        ColumnInfo single = column(projectId, code, "SINGLE_UK", "VARCHAR(10)", false);
        tx.executeWithoutResult(s -> columnInfoRepository.findById(alias.getId()).orElseThrow().setIsUnique(true)); // 컬럼의 고유값 설정

        TableInfo member = table(projectId, "TB_MEMBER", true, null);
        ColumnInfo memberId = column(projectId, member, "MEMBER_ID", "BIGINT", true);

        TableInfo order = table(projectId, "TB_ORDER", true, null);
        ColumnInfo orderNo = column(projectId, order, "ORDER_NO", "BIGINT", true);
        ColumnInfo upNo = column(projectId, order, "UP_ORDER_NO", "BIGINT", false);
        ColumnInfo buyer = column(projectId, order, "BUYER_ID", "BIGINT", false);
        ColumnInfo a = column(projectId, order, "A_CD", "VARCHAR(10)", false);
        ColumnInfo b = column(projectId, order, "B_CD", "VARCHAR(10)", false);
        ColumnInfo c = column(projectId, order, "C_CD", "VARCHAR(10)", false);
        ColumnInfo d = column(projectId, order, "D_CD", "VARCHAR(10)", false);
        ColumnInfo e = column(projectId, order, "E_CD", "VARCHAR(10)", false);

        // 복합 UNIQUE 제약은 일반 사용자가 등록해 승인 대기여도 키로 본다 (DDL 과 같은 기준 : 승인 여부로 거르지 않는다)
        AccountDto user = login(projectId, "961", "ROLE_USER");
        physical.saveConstraint(user, new PhysicalModelDto.Constraint(null, code.getId(), "UK_CODE_ALT", "UNIQUE", null, List.of(ref(alt1), ref(alt2)), null));
        login(projectId, String.valueOf(admin.getId()), "ROLE_ADMIN");
        physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, code.getId(), "UK_CODE_SINGLE", "UNIQUE", null, List.of(ref(single)), null));
        physical.saveIndex(admin, new PhysicalModelDto.Index(null, code.getId(), "UX_CODE_IX", null, true, List.of(ref(ix1), ref(ix2)), null));

        // 거절 : 일반 컬럼, 복합 PK 의 일부, 복합 UNIQUE 의 일부, 서로 다른 키의 컬럼 조합, 키보다 많은 컬럼
        invalid(() -> physical.saveRelation(admin, relation("FK_X1", "FK", order, code, pair(a, name))), "TB_CODE", "CD_NM");
        invalid(() -> physical.saveRelation(admin, relation("FK_X2", "FK", order, code, pair(a, grp))), "GRP_CD");
        invalid(() -> physical.saveRelation(admin, relation("FK_X3", "FK", order, code, pair(a, alt1))), "ALT_1");
        invalid(() -> physical.saveRelation(admin, relation("FK_X4", "FK", order, code, pair(a, alt1), pair(b, ix2))), "ALT_1, IX_2");
        invalid(() -> physical.saveRelation(admin, relation("FK_X5", "FK", order, code, pair(a, grp), pair(b, cd), pair(c, name))), "GRP_CD, CD, CD_NM");
        invalid(() -> physical.saveRelation(admin, relation("FK_X6", "FK", order, code, pair(a, alias), pair(b, single))), "ALIAS, SINGLE_UK");
        assertThat(erd.selectGraph(admin, null, null, null, null, 0, 100).getBody().relations()).as("거절된 요청은 아무것도 남기지 않는다").isEmpty();

        // 허용 : 단일 PK, 자기 참조, 복합 PK, 같은 두 테이블 사이 두 번째 FK(복합 UNIQUE 제약), 고유값 컬럼, 단일 UNIQUE 제약, 복합 고유 인덱스
        physical.saveRelation(admin, relation("FK_BUYER", "FK", order, member, pair(buyer, memberId)));
        physical.saveRelation(admin, relation("FK_UP", "FK", order, order, pair(upNo, orderNo)));
        physical.saveRelation(admin, relation("FK_PK", "FK", order, code, pair(a, grp), pair(b, cd)));
        physical.saveRelation(admin, relation("FK_UK", "FK", order, code, pair(c, alt1), pair(d, alt2)));
        physical.saveRelation(admin, relation("FK_ALIAS", "FK", order, code, pair(c, alias)));
        physical.saveRelation(admin, relation("FK_SINGLE", "FK", order, code, pair(d, single)));
        // 컬럼 쌍 순서 : 대상 키의 컬럼 순서와 달라도 구성이 같으면 등록되고, 저장한 쌍(원본 ↔ 대상)이 그대로 유지된다
        Relation swapped = physical.saveRelation(admin, relation("FK_IX", "FK", order, code, pair(b, ix2), pair(a, ix1))).getBody();
        assertThat(swapped.columns()).extracting(ColumnPair::sourceColumnName, ColumnPair::targetColumnName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("B_CD", "IX_2"), org.assertj.core.groups.Tuple.tuple("A_CD", "IX_1"));
        // 논리 관계는 제약이 아니므로 키가 아닌 컬럼도 가리킬 수 있다 (DDL 에 나가지 않는다)
        physical.saveRelation(admin, relation("LG_NAME", "LOGICAL", order, code, pair(e, name)));

        TableDdlDto ddl = tableDesignService.generateTableDdl(order.getId(), admin, null);
        assertThat(ddl.ddl())
                .contains("ADD CONSTRAINT FK_BUYER FOREIGN KEY (BUYER_ID) REFERENCES TB_MEMBER (MEMBER_ID);")
                .contains("ADD CONSTRAINT FK_UP FOREIGN KEY (UP_ORDER_NO) REFERENCES TB_ORDER (ORDER_NO);")
                .contains("ADD CONSTRAINT FK_PK FOREIGN KEY (A_CD, B_CD) REFERENCES TB_CODE (GRP_CD, CD);")
                .contains("ADD CONSTRAINT FK_UK FOREIGN KEY (C_CD, D_CD) REFERENCES TB_CODE (ALT_1, ALT_2);")
                .contains("ADD CONSTRAINT FK_ALIAS FOREIGN KEY (C_CD) REFERENCES TB_CODE (ALIAS);")
                .contains("ADD CONSTRAINT FK_SINGLE FOREIGN KEY (D_CD) REFERENCES TB_CODE (SINGLE_UK);")
                .contains("ADD CONSTRAINT FK_IX FOREIGN KEY (B_CD, A_CD) REFERENCES TB_CODE (IX_2, IX_1);")
                .doesNotContain("LG_NAME");
        assertThat(ddl.warnings()).noneMatch(w -> w.contains("FK 를 만들지 않았습니다"));

        // DDL 은 설계 모델 그대로다 : 승인 대기 항목을 빼지 않고, 무엇이 승인 전인지 따로 알려 준다
        TableDdlDto codeDdl = tableDesignService.generateTableDdl(code.getId(), admin, null);
        assertThat(codeDdl.ddl()).contains("CONSTRAINT UK_CODE_ALT UNIQUE (ALT_1, ALT_2)").contains("CONSTRAINT UK_CODE_SINGLE UNIQUE (SINGLE_UK)");
        assertThat(codeDdl.pendingApprovals()).contains("제약조건 : UK_CODE_ALT").noneMatch(p -> p.contains("UK_CODE_SINGLE") || p.startsWith("테이블"));
        assertThat(codeDdl.pendingApprovals()).anyMatch(p -> p.startsWith("컬럼 : GRP_CD, CD"));
    }

    @Test
    @DisplayName("검증 전에 저장된(또는 등록 뒤 대상 키가 바뀐) FK 는 지우지 않는다 : 그대로 조회되고, DDL 은 경고와 함께 건너뛴다")
    void legacyForeignKeyKept() {
        AccountDto admin = loginAsAdmin("hard-fk-legacy", DbType.POSTGRESQL);
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT", true, null);
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", "BIGINT", true);
        TableInfo child = table(projectId, "TB_CHILD", true, null);
        column(projectId, child, "CHILD_ID", "BIGINT", true);
        ColumnInfo ref = column(projectId, child, "PARENT_ID", "BIGINT", false);
        Relation fk = physical.saveRelation(admin, relation("FK_CHILD_PARENT", "FK", child, parent, pair(ref, parentId))).getBody();
        assertThat(tableDesignService.generateTableDdl(child.getId(), admin, null).ddl()).contains("FK_CHILD_PARENT");

        // 대상 컬럼이 더 이상 키가 아니고 타입도 달라진 상태 (예전 데이터)
        tx.executeWithoutResult(s -> {
            ColumnInfo column = columnInfoRepository.findById(parentId.getId()).orElseThrow();
            column.setIsPk(false);
            column.setDataType("VARCHAR(20)");
        });

        assertThat(erd.selectGraph(admin, null, null, null, null, 0, 100).getBody().relations()).extracting(Relation::id).containsExactly(fk.id());
        assertThat(physical.selectTableModel(admin, child.getId()).getBody().outgoingRelations()).extracting(Relation::id).containsExactly(fk.id());
        TableDdlDto ddl = tableDesignService.generateTableDdl(child.getId(), admin, null);
        assertThat(ddl.ddl()).doesNotContain("FK_CHILD_PARENT");
        assertThat(ddl.warnings()).anyMatch(w -> w.startsWith("FK_CHILD_PARENT : 대상 컬럼이 TB_PARENT"));

        // 같은 정의를 새로 등록하는 것은 거절된다. 남아 있는 관계는 승인취소 후 삭제할 수 있다
        ColumnInfo ref2 = column(projectId, child, "PARENT_ID2", "BIGINT", false);
        invalid(() -> physical.saveRelation(admin, relation(null, "FK", child, parent, pair(ref2, parentId))), "TB_PARENT");
        physical.updateApproval(admin, "relation", fk.id(), false);
        physical.delete(admin, "relation", fk.id());
        assertThat(physical.selectTableModel(admin, child.getId()).getBody().outgoingRelations()).isEmpty();
    }

    // ------------------------------------------------------------------ FK 타입 / 스키마

    @Test
    @DisplayName("FK 컬럼 쌍의 타입은 프로젝트 대상 DB 에서 확실히 묶일 수 있는 조합만 받는다 (PostgreSQL / MySQL 기준이 다르다)")
    void foreignKeyTypeCompatibility() {
        AccountDto pg = loginAsAdmin("hard-fk-type-pg", DbType.POSTGRESQL);
        Fixture p = fixture(pg.getProjectId());
        physical.saveRelation(pg, relation("FK_SAME", "FK", p.child, p.parent, pair(p.sameId, p.id)));
        physical.saveRelation(pg, relation("FK_INT", "FK", p.child, p.parent, pair(p.intId, p.id)));          // INTEGER ↔ BIGINT
        physical.saveRelation(pg, relation("FK_LEN", "FK", p.child, p.code, pair(p.longCode, p.codeId)));     // VARCHAR(30) ↔ VARCHAR(10)
        physical.saveRelation(pg, relation("FK_NUM", "FK", p.child, p.amount, pair(p.num10, p.amountId)));    // NUMERIC(10) ↔ NUMERIC(12)
        invalid(() -> physical.saveRelation(pg, relation("FK_BAD1", "FK", p.child, p.parent, pair(p.longCode, p.id))), "PostgreSQL", "VARCHAR(30) ↔ BIGINT");
        invalid(() -> physical.saveRelation(pg, relation("FK_BAD2", "FK", p.child, p.code, pair(p.text, p.codeId))), "TEXT ↔ VARCHAR(10)");
        invalid(() -> physical.saveRelation(pg, relation("FK_BAD3", "FK", p.child, p.amount, pair(p.sameId, p.amountId))), "BIGINT ↔ NUMERIC(12)");
        // 길이만 다른 FK 는 만들되 DDL 이 알려 준다 (기존 경고)
        TableDdlDto ddl = tableDesignService.generateTableDdl(p.child.getId(), pg, null);
        assertThat(ddl.ddl()).contains("FK_SAME").contains("FK_INT").contains("FK_LEN").contains("FK_NUM").doesNotContain("FK_BAD");
        assertThat(ddl.warnings()).anyMatch(w -> w.startsWith("LONG_CD : 참조 PK 와 길이/정밀도가 다릅니다."));

        AccountDto mysql = loginAsAdmin("hard-fk-type-mysql", DbType.MYSQL);
        Fixture m = fixture(mysql.getProjectId());
        physical.saveRelation(mysql, relation("FK_SAME", "FK", m.child, m.parent, pair(m.sameId, m.id)));
        physical.saveRelation(mysql, relation("FK_LEN", "FK", m.child, m.code, pair(m.longCode, m.codeId)));
        invalid(() -> physical.saveRelation(mysql, relation("FK_INT", "FK", m.child, m.parent, pair(m.intId, m.id))), "MySQL", "INT ↔ BIGINT");
        invalid(() -> physical.saveRelation(mysql, relation("FK_NUM", "FK", m.child, m.amount, pair(m.num10, m.amountId))), "DECIMAL(10) ↔ DECIMAL(12)");
        // 복합 FK : 쌍마다 본다 (두 번째 쌍이 맞지 않으면 전체 거절)
        invalid(() -> physical.saveRelation(mysql, relation("FK_PAIR", "FK", m.child, m.pair, pair(m.sameId, m.pairA), pair(m.intId, m.pairB))), "INT ↔ BIGINT");
        physical.saveRelation(mysql, relation("FK_PAIR", "FK", m.child, m.pair, pair(m.sameId, m.pairA), pair(m.sameId2, m.pairB)));
        assertThat(physical.selectTableModel(mysql, m.child.getId()).getBody().outgoingRelations()).extracting(Relation::relationName)
                .containsExactlyInAnyOrder("FK_SAME", "FK_LEN", "FK_PAIR");
    }

    @Test
    @DisplayName("다른 스키마의 테이블을 참조하는 FK : 같은 검증을 거치고, DDL 은 대상 테이블에 스키마명을 붙인다. 스키마가 달라도 프로젝트 안의 테이블명은 하나다")
    void crossSchemaForeignKey() {
        AccountDto admin = loginAsAdmin("hard-fk-schema", DbType.POSTGRESQL);
        long projectId = admin.getProjectId();
        SchemaDto mst = schemaService.saveSchema(admin, new SchemaDto(null, "mst", null, null));
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", null, null));
        TableInfo code = table(projectId, "TB_CODE", true, mst.id());
        ColumnInfo cd = column(projectId, code, "CD", "VARCHAR(10)", true);
        ColumnInfo nm = column(projectId, code, "CD_NM", "VARCHAR(10)", false);
        TableInfo order = table(projectId, "TB_ORDER", true, sales.id());
        column(projectId, order, "ORDER_NO", "BIGINT", true);
        ColumnInfo stat = column(projectId, order, "STAT_CD", "VARCHAR(10)", false);
        TableInfo local = table(projectId, "TB_LOCAL", true, null);
        column(projectId, local, "LOCAL_ID", "BIGINT", true);
        ColumnInfo localStat = column(projectId, local, "STAT_CD", "VARCHAR(10)", false);

        invalid(() -> physical.saveRelation(admin, relation(null, "FK", order, code, pair(stat, nm))), "TB_CODE");
        physical.saveRelation(admin, relation("FK_ORDER_STAT", "FK", order, code, pair(stat, cd)));
        physical.saveRelation(admin, relation("FK_LOCAL_STAT", "FK", local, code, pair(localStat, cd)));

        assertThat(tableDesignService.generateTableDdl(order.getId(), admin, null).ddl())
                .contains("CREATE TABLE sales.TB_ORDER (")
                .contains("ALTER TABLE sales.TB_ORDER ADD CONSTRAINT FK_ORDER_STAT FOREIGN KEY (STAT_CD) REFERENCES mst.TB_CODE (CD);");
        // 기본 스키마 테이블 : 자기 이름에는 받은 스키마명만 붙이고, 다른 스키마의 대상에는 그 스키마명을 붙인다
        assertThat(tableDesignService.generateTableDdl(local.getId(), admin, null).ddl())
                .contains("ALTER TABLE TB_LOCAL ADD CONSTRAINT FK_LOCAL_STAT FOREIGN KEY (STAT_CD) REFERENCES mst.TB_CODE (CD);");
        assertThat(tableDesignService.generateTableDdl(local.getId(), admin, "public").ddl())
                .contains("ALTER TABLE public.TB_LOCAL ADD CONSTRAINT FK_LOCAL_STAT FOREIGN KEY (STAT_CD) REFERENCES mst.TB_CODE (CD);");

        // ERD 는 스키마로 조회 범위를 나누고, 범위 테이블이 한쪽인 관계도 함께 돌려준다
        var salesGraph = erd.selectGraph(admin, sales.id(), null, null, null, 0, 100).getBody();
        assertThat(salesGraph.tables()).extracting(t -> t.schemaId() + "." + t.tableName()).containsExactly(sales.id() + ".TB_ORDER");
        assertThat(salesGraph.relations()).extracting(Relation::relationName).containsExactly("FK_ORDER_STAT");
    }

    // ------------------------------------------------------------------ 보조

    private record Fixture(TableInfo parent, ColumnInfo id, TableInfo code, ColumnInfo codeId, TableInfo amount, ColumnInfo amountId,
                           TableInfo pair, ColumnInfo pairA, ColumnInfo pairB,
                           TableInfo child, ColumnInfo sameId, ColumnInfo sameId2, ColumnInfo intId, ColumnInfo longCode, ColumnInfo num10, ColumnInfo text) {}

    private Fixture fixture(long projectId) {
        TableInfo parent = table(projectId, "TB_PARENT", true, null);
        ColumnInfo id = column(projectId, parent, "PARENT_ID", "BIGINT", true);
        TableInfo code = table(projectId, "TB_CODE", true, null);
        ColumnInfo codeId = column(projectId, code, "CD", "VARCHAR(10)", true);
        TableInfo amount = table(projectId, "TB_AMT", true, null);
        ColumnInfo amountId = column(projectId, amount, "AMT_NO", "NUMBER(12)", true);
        TableInfo pair = table(projectId, "TB_PAIR", true, null);
        ColumnInfo pairA = column(projectId, pair, "A_ID", "BIGINT", true);
        ColumnInfo pairB = column(projectId, pair, "B_ID", "BIGINT", true);
        TableInfo child = table(projectId, "TB_CHILD", true, null);
        column(projectId, child, "CHILD_ID", "BIGINT", true);
        return new Fixture(parent, id, code, codeId, amount, amountId, pair, pairA, pairB, child,
                column(projectId, child, "SAME_ID", "BIGINT", false),
                column(projectId, child, "SAME_ID2", "BIGINT", false),
                column(projectId, child, "INT_ID", "INTEGER", false),
                column(projectId, child, "LONG_CD", "VARCHAR(30)", false),
                column(projectId, child, "NUM_10", "NUMBER(10)", false),
                column(projectId, child, "MEMO", "TEXT", false));
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    /** 물리 모델 정의 오류 + 사용자에게 보여 줄 문구에 들어 있어야 하는 값 */
    private static void invalid(Runnable call, String... contains) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
            assertThat(e.getMessage()).contains(contains);
        });
    }

    private static TableInfoDto update(TableInfo table, String tableName, String description, Long schemaId) {
        return TableInfoDto.builder().id(table.getId()).tableName(tableName).tableDescription(description).isMasterTable("N").schemaId(schemaId).build();
    }

    private static TableInfoDto dto(TableInfo table) {
        return TableInfoDto.builder().id(table.getId()).build();
    }

    private static Relation relation(String name, String type, TableInfo source, TableInfo target, ColumnPair... pairs) {
        return new Relation(null, name, type, null, source.getId(), null, target.getId(), null, List.of(pairs), null);
    }

    private static ColumnPair pair(ColumnInfo source, ColumnInfo target) {
        return new ColumnPair(source.getId(), null, target.getId(), null);
    }

    private static PhysicalModelDto.ColumnRef ref(ColumnInfo column) {
        return new PhysicalModelDto.ColumnRef(column.getId(), null);
    }

    private TableInfo reload(TableInfo table) {
        return inTx(() -> {
            TableInfo found = tableInfoRepository.findById(table.getId()).orElseThrow();
            found.getSchema().getIsDefault(); // 지연 로딩
            return found;
        });
    }

    private AccountDto login(long projectId, String accountId, String role) {
        return SecurityTestSupport.loginAs(projectRepository, projectId, accountId, role);
    }

    private AccountDto loginAsAdmin(String projectName, DbType dbType) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).dbType(dbType).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name, boolean approved, Long schemaId) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, String dataType, boolean pk) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType(dataType).isPk(pk).isApproval(false).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
