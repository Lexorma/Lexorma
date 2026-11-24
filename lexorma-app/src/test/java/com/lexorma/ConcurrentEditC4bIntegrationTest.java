package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.commoncolumn.domain.dto.CommonColumnDto;
import com.lexorma.data.commoncolumn.service.CommonColumnService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.repository.TableConstraintRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8-C4b (docs/ongoing/modeling-concurrency-m8.md 17장) : FK 등록 대상 테이블 잠금(P-04), 논리 속성 순서(L-02), 컬럼 엑셀 테이블명 해석(P-03),
 * 스키마 등록(P-06), 순서 번호(P-05·공통 컬럼). 두 트랜잭션은 C1~C4a 와 같은 방식으로 엇갈린다 — A 가 트랜잭션 안에서 할 일을 마친 뒤 커밋 전에 B 를
 * 다른 스레드(다른 DB 연결)에서 시작하고, B 가 끝났거나 DB 잠금을 기다리는 것을 DB 세션 정보로 확인한 뒤 A 를 커밋한다 (sleep 으로 순서를 정하지 않는다).
 * 결과는 [A, B] = OK · 오류 코드 · 예외 이름(500 이 될 것) · SQLSTATE. 응답만이 아니라 끝난 뒤의 DB 상태(관계·컬럼·스키마·순서)를 함께 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC4bIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired SchemaService schemaService;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired ColumnManageService columns;
    @Autowired TableDesignService tableDesign;
    @Autowired LogicalModelService logical;
    @Autowired com.lexorma.data.logical.controller.LogicalModelRestController logicalApi;
    @Autowired PhysicalModelService physical;
    @Autowired TableConstraintRepository constraints;
    @Autowired CommonColumnService commonColumns;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ================================================================== C4b-1 FK 등록 ↔ 대상 테이블 (P-04)

    @Test
    @DisplayName("FK 등록 ↔ 대상 컬럼 삭제 : 삭제가 대상 테이블을 잡은 동안의 FK 등록은 기다렸다가 400 (예전에는 커밋 때 FK 위반 500), 반대 순서의 삭제는 기다렸다가 사용 중 400 (예전에는 FK 등록이 500)")
    void fkVsTargetColumnDelete() throws Exception {
        AccountDto admin = login("c4b-fk1");
        TableInfo target = table(admin.getProjectId(), "TB_C4B_T1", false);
        column(target, "T1_ID", 1, true, false);
        ColumnInfo unique = column(target, "T1_UQ", 2, false, true);
        TableInfo source = table(admin.getProjectId(), "TB_C4B_S1", false);
        ColumnInfo sourceColumn = column(source, "S1_UQ", 1, false, false);

        String[] deleteFirst = both(admin, () -> columns.deleteColumn(admin, List.of(columnDto(unique))), () -> physical.saveRelation(admin, fk(source, sourceColumn, target, unique)));
        report("FK 등록 ↔ 대상 컬럼 삭제 (삭제 먼저)", deleteFirst);
        System.out.println("[M8-C4b] 삭제 먼저 뒤 상태 : 관계 " + relations(source, target) + ", 대상 컬럼 남음 " + columnExists(unique.getId())
                + ", 없는 컬럼을 가리키는 관계 컬럼 " + jdbc.queryForObject("select count(*) from TB_TBL_REL_COL rc where not exists (select 1 from TB_COL c where c.COL_SN = rc.TRGT_COL_SN)", Integer.class));
        assertThat(deleteFirst).containsExactly("OK", "INVALID_PHYSICAL_MODEL");
        assertThat(relations(source, target)).isZero();
        assertThat(columnExists(unique.getId())).isFalse();

        ColumnInfo unique2 = column(target, "T1_UQ2", 3, false, true);
        ColumnInfo sourceColumn2 = column(source, "S1_UQ2", 2, false, false);
        String[] registerFirst = both(admin, () -> physical.saveRelation(admin, fk(source, sourceColumn2, target, unique2)), () -> columns.deleteColumn(admin, List.of(columnDto(unique2))));
        report("FK 등록 ↔ 대상 컬럼 삭제 (등록 먼저)", registerFirst);
        assertThat(registerFirst).containsExactly("OK", "RELATION_EXISTS");
        assertThat(relations(source, target)).isEqualTo(1);
        assertThat(columnExists(unique2.getId())).isTrue();
        assertNoOrphanRelationColumns();
    }

    @Test
    @DisplayName("FK 등록 ↔ 대상 키 구성 변경 (PK 해제 · UNIQUE 제약조건 삭제) : 키 변경이 대상 테이블을 잡은 동안의 FK 등록은 기다렸다가 키가 아니라서 400 — 키가 아닌 대상 FK 가 생기지 않는다")
    void fkVsTargetKeyChange() throws Exception {
        AccountDto admin = login("c4b-fk2");
        TableInfo target = table(admin.getProjectId(), "TB_C4B_T2", true);
        ColumnInfo pk = column(target, "T2_ID", 1, true, false);
        TableInfo source = table(admin.getProjectId(), "TB_C4B_S2", false);
        ColumnInfo sourceColumn = column(source, "S2_T2_ID", 1, false, false);
        ColumnInfoDto pkSeen = columnDto(pk);
        pkSeen.setIsPk("N");
        pkSeen.setIsUnique("N"); // 고유값으로 남으면 여전히 FK 가 참조할 수 있는 키다
        String[] pkOff = both(admin, () -> bulk(admin, target, List.of(bulkRow(pkSeen))), () -> physical.saveRelation(admin, fk(source, sourceColumn, target, pk)));
        report("FK 등록 ↔ 대상 PK 해제", pkOff);
        System.out.println("[M8-C4b] PK 해제 뒤 상태 : 관계 " + relations(source, target) + ", 대상 PK " + inTx(() -> columnRepository.findById(pk.getId()).orElseThrow().getIsPk()));
        assertThat(pkOff).containsExactly("OK", "INVALID_PHYSICAL_MODEL");
        assertThat(relations(source, target)).isZero();

        TableInfo target3 = table(admin.getProjectId(), "TB_C4B_T3", false);
        column(target3, "T3_ID", 1, true, false);
        ColumnInfo code = column(target3, "T3_CD", 2, false, false);
        var uq = physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, target3.getId(), "UK_C4B_T3", "UNIQUE", null,
                List.of(new PhysicalModelDto.ColumnRef(code.getId(), code.getColumnName())), null));
        ColumnInfo sourceColumn3 = column(source, "S2_T3_CD", 2, false, false);
        tx.executeWithoutResult(st -> constraints.findById(uq.id()).orElseThrow().setIsApproval(false)); // 관리자 등록 = 승인 : 승인된 제약조건은 지울 수 없다
        java.time.LocalDateTime uqSeen = inTx(() -> constraints.findById(uq.id()).orElseThrow().modifiedAt());
        String[] uniqueOff = both(admin, () -> physical.delete(admin, PhysicalModelService.Kind.CONSTRAINT, uq.id(), uqSeen),
                () -> physical.saveRelation(admin, fk(source, sourceColumn3, target3, code)));
        report("FK 등록 ↔ 대상 UNIQUE 삭제", uniqueOff);
        assertThat(uniqueOff).containsExactly("OK", "INVALID_PHYSICAL_MODEL");
        assertThat(relations(source, target3)).isZero();
    }

    @Test
    @DisplayName("FK 등록 ↔ 대상 테이블 이름 변경 : 등록이 대상을 잡은 동안의 이름 변경(키 변경 경로, NOWAIT)은 409 로 끝나고, 이름 변경 중의 등록은 기다렸다가 성공 — 관계는 내부 ID 라 이름과 어긋나지 않는다")
    void fkVsTargetRename() throws Exception {
        AccountDto admin = login("c4b-fk3");
        TableInfo target = table(admin.getProjectId(), "TB_C4B_T4", false);
        ColumnInfo pk = column(target, "T4_ID", 1, true, false);
        TableInfo source = table(admin.getProjectId(), "TB_C4B_S4", false);
        ColumnInfo c1 = column(source, "S4_A", 1, false, false);
        ColumnInfo c2 = column(source, "S4_B", 2, false, false);

        String[] registerFirst = both(admin, () -> physical.saveRelation(admin, fk(source, c1, target, pk)), () -> rename(admin, target, "TB_C4B_T4_NEW"));
        report("FK 등록 ↔ 대상 이름 변경 (등록 먼저)", registerFirst);
        assertThat(registerFirst[0]).isEqualTo("OK");
        assertThat(registerFirst[1]).isIn("OK", "MODEL_CONFLICT");
        String[] renameFirst = both(admin, () -> rename(admin, target, "TB_C4B_T4_RENAMED"), () -> physical.saveRelation(admin, fk(source, c2, target, pk)));
        report("FK 등록 ↔ 대상 이름 변경 (이름 변경 먼저)", renameFirst);
        assertThat(renameFirst).containsExactly("OK", "OK");
        assertThat(relations(source, target)).isEqualTo(2);
        assertThat(inTx(() -> tableRepository.findById(target.getId()).orElseThrow().getTableName())).isEqualTo("TB_C4B_T4_RENAMED");
    }

    @Test
    @DisplayName("A → B 와 B → A 동시 FK 등록 · 같은 대상의 복합 FK 동시 등록 · 자기 참조 : 교착 없이 모두 성공 (두 테이블을 ID 오름차순으로 잠근다)")
    void fkOppositeAndComposite() throws Exception {
        AccountDto admin = login("c4b-fk4");
        TableInfo a = table(admin.getProjectId(), "TB_C4B_A", false);
        ColumnInfo aPk = column(a, "A_ID", 1, true, false);
        ColumnInfo aRef = column(a, "A_B_ID", 2, false, false);
        TableInfo b = table(admin.getProjectId(), "TB_C4B_B", false);
        ColumnInfo bPk = column(b, "B_ID", 1, true, false);
        ColumnInfo bRef = column(b, "B_A_ID", 2, false, false);
        String[] opposite = both(admin, () -> physical.saveRelation(admin, fk(a, aRef, b, bPk)), () -> physical.saveRelation(admin, fk(b, bRef, a, aPk)));
        report("A → B ↔ B → A", opposite);
        assertThat(opposite).containsExactly("OK", "OK");
        assertThat(relations(a, b)).isEqualTo(1);
        assertThat(relations(b, a)).isEqualTo(1);

        TableInfo parent = table(admin.getProjectId(), "TB_C4B_P", false);
        ColumnInfo p1 = column(parent, "P_K1", 1, true, false);
        ColumnInfo p2 = column(parent, "P_K2", 2, true, false);
        TableInfo child1 = table(admin.getProjectId(), "TB_C4B_C1", false);
        ColumnInfo c11 = column(child1, "C1_K1", 1, false, false);
        ColumnInfo c12 = column(child1, "C1_K2", 2, false, false);
        TableInfo child2 = table(admin.getProjectId(), "TB_C4B_C2", false);
        ColumnInfo c21 = column(child2, "C2_K1", 1, false, false);
        ColumnInfo c22 = column(child2, "C2_K2", 2, false, false);
        String[] composite = both(admin, () -> physical.saveRelation(admin, fk2(child1, List.of(c11, c12), parent, List.of(p1, p2))),
                () -> physical.saveRelation(admin, fk2(child2, List.of(c21, c22), parent, List.of(p1, p2))));
        report("복합 FK 동시 등록", composite);
        assertThat(composite).containsExactly("OK", "OK");
        assertThat(relations(child1, parent) + relations(child2, parent)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL_COL rc join TB_TBL_REL r on r.REL_SN = rc.REL_SN where r.TRGT_TBL_SN = ?", Integer.class, parent.getId())).isEqualTo(4);

        TableInfo self = table(admin.getProjectId(), "TB_C4B_SELF", false);
        ColumnInfo selfPk = column(self, "SELF_ID", 1, true, false);
        ColumnInfo selfUp = column(self, "UP_SELF_ID", 2, false, false);
        assertThat(outcome(() -> physical.saveRelation(admin, fk(self, selfUp, self, selfPk)))).isEqualTo("OK");
        assertThat(relations(self, self)).isEqualTo(1);
    }

    @Test
    @DisplayName("FK 등록 검증 회귀 : 다른 프로젝트 대상·없는 대상·없는 원본은 대상 없음 400, 중복 FK 400, 고아 관계 컬럼 없음")
    void fkValidation() {
        AccountDto other = login("c4b-fk5-other");
        TableInfo foreign = table(other.getProjectId(), "TB_C4B_FOREIGN", false);
        ColumnInfo foreignPk = column(foreign, "F_ID", 1, true, false);
        AccountDto admin = login("c4b-fk5");
        TableInfo source = table(admin.getProjectId(), "TB_C4B_S5", false);
        ColumnInfo sc = column(source, "S5_F_ID", 1, false, false);
        TableInfo target = table(admin.getProjectId(), "TB_C4B_T5", false);
        ColumnInfo tc = column(target, "T5_ID", 1, true, false);
        assertThat(outcome(() -> physical.saveRelation(admin, fk(source, sc, foreign, foreignPk)))).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(outcome(() -> physical.saveRelation(admin, new PhysicalModelDto.Relation(null, null, "FK", null, source.getId(), null, 999_999_999L, null,
                List.of(new PhysicalModelDto.ColumnPair(sc.getId(), null, tc.getId(), null)), null)))).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(outcome(() -> physical.saveRelation(admin, new PhysicalModelDto.Relation(null, null, "FK", null, 999_999_998L, null, target.getId(), null,
                List.of(new PhysicalModelDto.ColumnPair(sc.getId(), null, tc.getId(), null)), null)))).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(outcome(() -> physical.saveRelation(admin, fk(source, sc, target, tc)))).isEqualTo("OK");
        assertThat(outcome(() -> physical.saveRelation(admin, fk(source, sc, target, tc)))).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(relations(source, target)).isEqualTo(1);
        assertNoOrphanRelationColumns();
    }

    // ================================================================== C4b-2 논리 속성 순서 (L-02)

    @Test
    @DisplayName("L-02 : 같은 엔터티 속성 순서 동시 변경 — 뒤의 저장(같은 예전 순서 기준)은 기다렸다가 409, 앞의 순서가 남는다")
    void attributeOrderConcurrent() throws Exception {
        AccountDto admin = login("c4b-l1");
        long[] e = entityWithAttributes(admin, "주문", "주문번호", "주문일자", "주문금액");
        List<LogicalModelDto.AttributeOrder> seen = orders(e[0]);
        String[] r = both(admin, () -> logical.reorderAttributes(admin, e[0], reorder(seen, 2, 0, 1)), () -> logical.reorderAttributes(admin, e[0], reorder(seen, 1, 0, 2)));
        report("속성 순서 동시 변경", r);
        assertThat(r).containsExactly("OK", "MODEL_CONFLICT");
        assertThat(attributeIdsInOrder(e[0])).containsExactly(e[3], e[1], e[2]);
        assertThat(sortOrders(e[0])).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("L-02 : 속성 추가·삭제 뒤의 오래된 순서 409, 다른 엔터티 속성·중복 ID 400, 기준값 없는 HTTP 400, 설명만 바뀐 속성은 순서 저장을 막지 않는다, 정상 순서 변경은 이력에 남는다")
    void attributeOrderRules() {
        AccountDto admin = login("c4b-l2");
        long[] e = entityWithAttributes(admin, "고객", "고객번호", "고객명", "고객등급");
        List<LogicalModelDto.AttributeOrder> seen = orders(e[0]);

        long added = logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, e[0], "고객주소", null, null, false, null, null, null, null, null)).id();
        assertThat(outcome(() -> logical.reorderAttributes(admin, e[0], reorder(seen, 2, 1, 0)))).isEqualTo("MODEL_CONFLICT");
        List<LogicalModelDto.AttributeOrder> afterAdd = orders(e[0]);
        logical.deleteAttribute(admin, added, inTx(() -> attributeModifiedAt(added)));
        assertThat(outcome(() -> logical.reorderAttributes(admin, e[0], reorder(afterAdd, 3, 2, 1, 0)))).isEqualTo("MODEL_CONFLICT");
        assertThat(attributeIdsInOrder(e[0])).containsExactly(e[1], e[2], e[3]);

        // 같은 프로젝트의 다른 엔터티 속성을 섞으면 요청 오류 400, 다른 프로젝트의 속성은 '없는 속성' 이라 구성 변경과 같은 409 (다른 프로젝트 정보를 드러내지 않는다)
        long[] o = entityWithAttributes(admin, "상품", "상품번호");
        List<LogicalModelDto.AttributeOrder> mixed = new ArrayList<>(orders(e[0]));
        mixed.set(0, new LogicalModelDto.AttributeOrder(o[1], 1));
        assertThat(outcome(() -> logical.reorderAttributes(admin, e[0], mixed))).isEqualTo("INVALID_LOGICAL_MODEL");
        AccountDto other = login("c4b-l2-other");
        long[] foreign = entityWithAttributes(other, "상품", "상품번호");
        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        List<LogicalModelDto.AttributeOrder> crossProject = new ArrayList<>(orders(e[0]));
        crossProject.set(0, new LogicalModelDto.AttributeOrder(foreign[1], 1));
        assertThat(outcome(() -> logical.reorderAttributes(admin, e[0], crossProject))).isEqualTo("MODEL_CONFLICT");
        assertThat(attributeIdsInOrder(foreign[0])).containsExactly(foreign[1]);
        List<LogicalModelDto.AttributeOrder> current = orders(e[0]);
        assertThat(outcome(() -> logical.reorderAttributes(admin, e[0], List.of(current.get(0), current.get(0), current.get(1))))).isEqualTo("INVALID_LOGICAL_MODEL");
        assertThat(outcome(() -> logicalApi.reorderAttributes(admin, e[0], tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(List.of(e[1], e[2], e[3]))))).isEqualTo("MODEL_BASE_REQUIRED");

        // 다른 사용자가 속성 설명만 고쳤다 : 순서 기준값(정렬 순서)은 그대로라 순서 저장은 성공하고, 그 설명이 남는다
        var attribute = inTx(() -> LogicalModelDto.Attribute.fromEntity(entityAttribute(e[0], e[2])));
        logical.saveAttribute(admin, new LogicalModelDto.Attribute(attribute.id(), e[0], attribute.attributeName(), "다른 사용자의 설명", null, false, null, null, null, null, null, attribute.modifiedAt()));
        long historyBefore = history(admin.getProjectId());
        assertThat(outcome(() -> logical.reorderAttributes(admin, e[0], reorder(current, 2, 0, 1)))).isEqualTo("OK");
        assertThat(attributeIdsInOrder(e[0])).containsExactly(e[3], e[1], e[2]);
        assertThat(inTx(() -> entityAttribute(e[0], e[2]).getAttributeDescription())).isEqualTo("다른 사용자의 설명");
        assertThat(history(admin.getProjectId())).isGreaterThan(historyBefore);
        // 같은 순서를 다시 보내면 바뀌는 행이 없다 (기준값만 맞으면 성공, 이력 추가 없음)
        long historyAfter = history(admin.getProjectId());
        assertThat(outcome(() -> logical.reorderAttributes(admin, e[0], orders(e[0])))).isEqualTo("OK");
        assertThat(history(admin.getProjectId())).isEqualTo(historyAfter);
    }

    // ================================================================== C4b-3 컬럼 엑셀 업로드 테이블명 해석 (P-03)

    @Test
    @DisplayName("엑셀 업로드 ↔ 테이블 이름 변경 : 이름 변경이 테이블을 잡은 동안의 업로드는 기다렸다가 대상 없음 400 — 예전 이름(TBL_NM 사본)으로 컬럼이 들어가지 않는다")
    void excelVsRename() throws Exception {
        GenerationFixture f = fixture("c4b-x1");
        f.term("EX1_CD", "VARCHAR", 20);
        TableInfo t = table(f.user.getProjectId(), "TB_C4B_X1", false);
        String[] r = both(f.user, () -> rename(f.user, t, "TB_C4B_X1_NEW"), () -> upload(f.user, rows(new String[]{"TB_C4B_X1", "EX1_CD"})));
        report("엑셀 ↔ 테이블 이름 변경", r);
        assertThat(r).containsExactly("OK", "TABLE_IS_NOT_DEFINED");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, t.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where c.PRJ_SN = ? and c.TBL_NM <> t.TBL_NM", Integer.class, f.user.getProjectId())).isZero();
        assertThat(outcome(() -> upload(f.user, rows(new String[]{"TB_C4B_X1_NEW", "EX1_CD"})))).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select TBL_NM from TB_COL where TBL_SN = ?", String.class, t.getId())).isEqualTo("TB_C4B_X1_NEW");
    }

    @Test
    @DisplayName("엑셀 업로드 ↔ 테이블 삭제 · 삭제 뒤 같은 이름 재등록 : 대상 없음 400 / 409 — 예전 ID 로 넣다 FK 위반 500 이 나지 않고, 새 테이블에 자동으로 넣지 않는다")
    void excelVsDelete() throws Exception {
        GenerationFixture f = fixture("c4b-x2");
        f.term("EX2_CD", "VARCHAR", 20);
        TableInfo t = table(f.user.getProjectId(), "TB_C4B_X2", false);
        TableInfoDto del = forDelete(f.user, t.getId());
        String[] deleted = both(f.user, () -> tableDesign.deleteTable(List.of(del), f.user), () -> upload(f.user, rows(new String[]{"TB_C4B_X2", "EX2_CD"})));
        report("엑셀 ↔ 테이블 삭제", deleted);
        assertThat(deleted).containsExactly("OK", "TABLE_IS_NOT_DEFINED");

        TableInfo t3 = table(f.user.getProjectId(), "TB_C4B_X3", false);
        TableInfoDto del3 = forDelete(f.user, t3.getId());
        AtomicReference<Long> recreated = new AtomicReference<>();
        String[] recreate = both(f.user, () -> {
            tableDesign.deleteTable(List.of(del3), f.user);
            tableRepository.flush();
            recreated.set(tableRepository.save(TableInfo.builder().projectId(f.user.getProjectId()).tableName("TB_C4B_X3").isApproval(false)
                    .schema(schemaService.resolveSchema(f.user.getProjectId(), null)).build()).getId());
        }, () -> upload(f.user, rows(new String[]{"TB_C4B_X3", "EX2_CD"})));
        report("엑셀 ↔ 삭제 후 같은 이름 재등록", recreate);
        assertThat(recreate).containsExactly("OK", "MODEL_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, recreated.get())).isZero();
        assertThat(outcome(() -> upload(f.user, rows(new String[]{"TB_C4B_X3", "EX2_CD"})))).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, recreated.get())).isEqualTo(1);
    }

    @Test
    @DisplayName("엑셀 업로드 ↔ 스키마 이름 변경 : 스키마명.테이블명으로 적은 업로드는 이름 변경 커밋을 기다렸다가 같은 테이블(ID)에 들어가고, 이름 변경 뒤의 예전 스키마명은 대상 없음 400")
    void excelVsSchemaRename() throws Exception {
        GenerationFixture f = fixture("c4b-x4");
        f.term("EX4_CD", "VARCHAR", 20);
        f.term("EX4_NM", "VARCHAR", 20);
        SchemaDto schema = schemaService.saveSchema(f.user, new SchemaDto(null, "SC_C4B_OLD", null, null));
        TableInfo t = inTx(() -> tableRepository.save(TableInfo.builder().projectId(f.user.getProjectId()).tableName("TB_C4B_X4").isApproval(false)
                .schema(schemaRepository.findById(schema.id()).orElseThrow()).build()));
        SchemaDto seen = inTx(() -> SchemaDto.fromEntity(schemaRepository.findById(schema.id()).orElseThrow()));
        String[] r = both(f.user, () -> schemaService.saveSchema(f.user, new SchemaDto(seen.id(), "SC_C4B_NEW", null, false, null, null, seen.updated(), null, null)),
                () -> upload(f.user, rows(new String[]{"SC_C4B_OLD.TB_C4B_X4", "EX4_CD"})));
        report("엑셀 ↔ 스키마 이름 변경", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, t.getId())).isEqualTo(1);
        assertThat(outcome(() -> upload(f.user, rows(new String[]{"SC_C4B_OLD.TB_C4B_X4", "EX4_NM"})))).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(outcome(() -> upload(f.user, rows(new String[]{"SC_C4B_NEW.TB_C4B_X4", "EX4_NM"})))).isEqualTo("OK");
    }

    @Test
    @DisplayName("엑셀 업로드 회귀 : 같은 테이블 동시 업로드(다른 컬럼 둘 다 · 같은 컬럼 하나만), 여러 테이블, 다른 스키마의 같은 테이블명(이름만 = 대상 없음, 스키마명 = 그 테이블)")
    void excelRegression() throws Exception {
        GenerationFixture f = fixture("c4b-x5");
        f.term("EX5_A", "VARCHAR", 20);
        f.term("EX5_B", "VARCHAR", 20);
        f.term("EX5_C", "VARCHAR", 20);
        TableInfo t = table(f.user.getProjectId(), "TB_C4B_X5", false);
        TableInfo t2 = table(f.user.getProjectId(), "TB_C4B_X6", false);
        String[] parallel = both(f.user, () -> upload(f.user, rows(new String[]{"TB_C4B_X5", "EX5_A"})), () -> upload(f.user, rows(new String[]{"TB_C4B_X5", "EX5_B"})));
        assertThat(parallel).containsExactly("OK", "OK");
        String[] same = both(f.user, () -> upload(f.user, rows(new String[]{"TB_C4B_X5", "EX5_C"})), () -> upload(f.user, rows(new String[]{"TB_C4B_X5", "EX5_C"})));
        assertThat(same).containsExactly("OK", "SAVED_DATA_EXISTS");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, t.getId())).isEqualTo(3);
        assertThat(outcome(() -> upload(f.user, rows(new String[]{"TB_C4B_X6", "EX5_A"}, new String[]{"tb_c4b_x5", "EX5_A"})))).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(outcome(() -> upload(f.user, rows(new String[]{"TB_C4B_X6", "EX5_A"}, new String[]{"TB_C4B_X6", "EX5_B"})))).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, t2.getId())).isEqualTo(2);

        SchemaDto other = schemaService.saveSchema(f.user, new SchemaDto(null, "SC_C4B_DUP", null, null));
        TableInfo dupDefault = table(f.user.getProjectId(), "TB_C4B_DUP", false);
        TableInfo dupOther = inTx(() -> tableRepository.save(TableInfo.builder().projectId(f.user.getProjectId()).tableName("TB_C4B_DUP").isApproval(false)
                .schema(schemaRepository.findById(other.id()).orElseThrow()).build()));
        assertThat(outcome(() -> upload(f.user, rows(new String[]{"TB_C4B_DUP", "EX5_A"})))).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(outcome(() -> upload(f.user, rows(new String[]{"SC_C4B_DUP.TB_C4B_DUP", "EX5_A"})))).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, dupOther.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, dupDefault.getId())).isZero();
    }

    // ================================================================== C4b-4 스키마 동시 등록 (P-06)

    @Test
    @DisplayName("스키마 동시 등록 : 대소문자만 다른 이름·같은 이름은 뒤의 등록이 기다렸다가 중복 400 (예전 : 둘 다 등록 / UNIQUE 위반 500), 다른 이름·다른 프로젝트는 둘 다 등록")
    void schemaConcurrentRegistration() throws Exception {
        AccountDto admin = login("c4b-s1");
        String[] caseOnly = both(admin, () -> schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_SALES", null, null)),
                () -> schemaService.saveSchema(admin, new SchemaDto(null, "sc_c4b_sales", null, null)));
        report("스키마 대소문자만 다른 동시 등록", caseOnly);
        assertThat(caseOnly).containsExactly("OK", "SAVED_DATA_EXISTS");
        assertThat(schemaCount(admin.getProjectId(), "SC_C4B_SALES")).isEqualTo(1);

        String[] exact = both(admin, () -> schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_MART", null, null)),
                () -> schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_MART", null, null)));
        report("스키마 같은 이름 동시 등록", exact);
        assertThat(exact).containsExactly("OK", "SAVED_DATA_EXISTS");
        assertThat(schemaCount(admin.getProjectId(), "SC_C4B_MART")).isEqualTo(1);

        String[] different = both(admin, () -> schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_ONE", null, null)),
                () -> schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_TWO", null, null)));
        assertThat(different).containsExactly("OK", "OK");

        AccountDto other = login("c4b-s1-other");
        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        String[] projects = both(admin, () -> schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_SHARED", null, null)),
                () -> schemaService.saveSchema(other, new SchemaDto(null, "SC_C4B_SHARED", null, null)));
        assertThat(projects).containsExactly("OK", "OK");
        assertThat(schemaCount(other.getProjectId(), "SC_C4B_SHARED")).isEqualTo(1);
    }

    @Test
    @DisplayName("스키마 등록 ↔ 이름 변경·삭제 : 이름 변경 중 같은 이름(대소문자 무시) 등록은 기다렸다가 400, 삭제 중 같은 이름 등록은 400 (삭제 전 상태), 기본 스키마 동시 생성은 하나")
    void schemaRegistrationVsRenameDelete() throws Exception {
        AccountDto admin = login("c4b-s2");
        SchemaDto old = schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_OLD", null, null));
        SchemaDto seen = inTx(() -> SchemaDto.fromEntity(schemaRepository.findById(old.id()).orElseThrow()));
        String[] rename = both(admin, () -> schemaService.saveSchema(admin, new SchemaDto(seen.id(), "SC_C4B_NEW", null, false, null, null, seen.updated(), null, null)),
                () -> schemaService.saveSchema(admin, new SchemaDto(null, "sc_c4b_new", null, null)));
        report("스키마 이름 변경 ↔ 같은 이름 등록", rename);
        assertThat(rename).containsExactly("OK", "SAVED_DATA_EXISTS");
        assertThat(schemaCount(admin.getProjectId(), "SC_C4B_NEW")).isEqualTo(1);

        SchemaDto del = schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_DEL", null, null));
        java.time.LocalDateTime delSeen = inTx(() -> schemaRepository.findById(del.id()).orElseThrow().modifiedAt());
        String[] delete = both(admin, () -> schemaService.deleteSchema(admin, del.id(), delSeen), () -> schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_DEL", null, null)));
        report("스키마 삭제 ↔ 같은 이름 등록", delete);
        assertThat(delete).containsExactly("OK", "SAVED_DATA_EXISTS");
        assertThat(outcome(() -> schemaService.saveSchema(admin, new SchemaDto(null, "SC_C4B_DEL", null, null)))).isEqualTo("OK");

        Account account = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project fresh = projectRepository.save(Project.builder().projectName("c4b-s2-fresh-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(account).build());
        String[] defaults = both(admin, () -> schemaService.resolveSchema(fresh.getId(), null), () -> schemaService.resolveSchema(fresh.getId(), null));
        assertThat(defaults).containsExactly("OK", "OK");
        assertThat(jdbc.queryForObject("select count(*) from TB_SCHM where PRJ_SN = ? and BSC_YN = true", Integer.class, fresh.getId())).isEqualTo(1);
    }

    // ================================================================== C4b-5 순서 번호 (P-05)

    @Test
    @DisplayName("순서 번호 : 다른 사용자가 컬럼을 더한 뒤의 일괄 저장(순서 변경·추가)은 그 컬럼의 순서를 비켜 번호를 매긴다 — 순서가 겹치지 않고 그 컬럼은 그대로 (동시 · 순차)")
    void bulkSaveSortOrder() throws Exception {
        GenerationFixture f = fixture("c4b-o1");
        long termX = f.term("O1_X", "VARCHAR", 20);
        long termY = f.term("O1_Y", "VARCHAR", 20);
        long termZ = f.term("O1_Z", "VARCHAR", 20);
        TableInfo t = table(f.user.getProjectId(), "TB_C4B_O1", true);
        ColumnInfo a = column(t, "O1_A", 1, false, false);
        ColumnInfo b = column(t, "O1_B", 2, false, false);
        ColumnInfoDto seenA = columnDto(a);
        ColumnInfoDto seenB = columnDto(b);
        // 다른 사용자 : A·B 는 바꾸지 않고 X 를 더한다 (X = 3) — 이 편집기는 그 사이 B, A 순서로 바꾸고 Y 를 더한다
        String[] r = both(f.user, () -> bulk(f.user, t, List.of(unchangedRow(seenA), unchangedRow(seenB), newRow("x", termX))),
                () -> bulk(f.user, t, List.of(bulkRow(seenB), bulkRow(seenA), newRow("y", termY))));
        report("일괄 저장 순서 번호 (동시)", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(duplicateSortOrders(t.getId())).isZero();
        assertThat(jdbc.queryForObject("select SORT_ORD from TB_COL where TBL_SN = ? and COL_NM = 'O1_X'", Integer.class, t.getId())).isEqualTo(3);
        assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? order by SORT_ORD", String.class, t.getId())).containsExactly("O1_B", "O1_A", "O1_X", "O1_Y");

        // 순차 : 열어 둔 편집기(A, B, X, Y 를 본 상태)가 그 사이 더해진 Z 를 모른 채 새 컬럼 W 를 더한다
        List<ColumnInfoDto> seen = inTx(() -> columnRepository.findAllByTableInfo_IdIn(List.of(t.getId())).stream().map(ColumnInfoDto::fromEntity).toList());
        assertThat(outcome(() -> bulk(f.user, t, List.of(newRow("z", termZ))))).isEqualTo("OK");
        long termW = f.term("O1_W", "VARCHAR", 20);
        List<ColumnBulkSaveDto.Row> stale = new ArrayList<>(seen.stream().sorted(java.util.Comparator.comparing(ColumnInfoDto::getSortOrder)).map(ConcurrentEditC4bIntegrationTest::bulkRow).toList());
        stale.add(newRow("w", termW));
        assertThat(outcome(() -> bulk(f.user, t, stale))).isEqualTo("OK");
        assertThat(duplicateSortOrders(t.getId())).isZero();
    }

    @Test
    @DisplayName("순서 번호 : 같은 엔터티 속성 동시 추가는 모델 행 잠금으로 순서가 겹치지 않고, 다른 엔터티·다른 테이블은 서로 기다리지 않는다")
    void attributeAddAndDifferentParents() throws Exception {
        AccountDto admin = login("c4b-o2");
        long[] e = entityWithAttributes(admin, "계약", "계약번호");
        String[] r = both(admin, () -> logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, e[0], "계약일자", null, null, false, null, null, null, null, null)),
                () -> logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, e[0], "계약금액", null, null, false, null, null, null, null, null)));
        assertThat(r).containsExactly("OK", "OK");
        assertThat(sortOrders(e[0])).containsExactly(1, 2, 3);

        GenerationFixture f = fixture("c4b-o2b");
        long term1 = f.term("O2_A", "VARCHAR", 20);
        long term2 = f.term("O2_B", "VARCHAR", 20);
        TableInfo t1 = table(f.user.getProjectId(), "TB_C4B_O2A", true);
        TableInfo t2 = table(f.user.getProjectId(), "TB_C4B_O2B", true);
        String[] tables = both(f.user, () -> bulk(f.user, t1, List.of(newRow("a", term1))), () -> bulk(f.user, t2, List.of(newRow("b", term2))));
        assertThat(tables).containsExactly("OK", "OK");
    }

    @Test
    @DisplayName("공통 컬럼 세트 통째 교체 동시 두 건 : 뒤의 교체가 기다렸다가 앞의 결과를 교체한다 — 500·중복 행 없음, 순서 1..n")
    void commonColumnsReplace() throws Exception {
        GenerationFixture f = fixture("c4b-o3");
        long t1 = f.term("CM_A", "VARCHAR", 20);
        long t2 = f.term("CM_B", "VARCHAR", 20);
        long t3 = f.term("CM_C", "VARCHAR", 20);
        commonColumns.replaceCommonColumns(f.user, List.of(common(t1)));
        String[] r = both(f.user, () -> commonColumns.replaceCommonColumns(f.user, List.of(common(t2), common(t1))),
                () -> commonColumns.replaceCommonColumns(f.user, List.of(common(t3))));
        report("공통 컬럼 동시 교체", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(jdbc.queryForList("select STD_TRM_SN from TB_CMN_COL where PRJ_SN = ? order by SORT_ORD", Long.class, f.user.getProjectId())).containsExactly(t3);
    }

    // ------------------------------------------------------------------ 준비

    private AccountDto login(String prefix) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private GenerationFixture fixture(String name) {
        return GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
    }

    private TableInfo table(long projectId, String name, boolean approved) {
        return inTx(() -> tableRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(TableInfo table, String name, int order, boolean pk, boolean unique) {
        return inTx(() -> columnRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableName(table.getTableName())
                .tableInfo(tableRepository.getReferenceById(table.getId())).columnName(name).columnDesc(name).sortOrder(order)
                .dataType("BIGINT").isPk(pk).isUnique(unique || pk).isRequired(pk).isNullable(!pk).isApproval(false).build()));
    }

    private ColumnInfoDto columnDto(ColumnInfo c) {
        return inTx(() -> ColumnInfoDto.fromEntity(columnRepository.findById(c.getId()).orElseThrow()));
    }

    private static PhysicalModelDto.Relation fk(TableInfo source, ColumnInfo sourceColumn, TableInfo target, ColumnInfo targetColumn) {
        return fk2(source, List.of(sourceColumn), target, List.of(targetColumn));
    }

    private static PhysicalModelDto.Relation fk2(TableInfo source, List<ColumnInfo> sourceColumns, TableInfo target, List<ColumnInfo> targetColumns) {
        List<PhysicalModelDto.ColumnPair> pairs = new ArrayList<>();
        for (int i = 0; i < sourceColumns.size(); i++) pairs.add(new PhysicalModelDto.ColumnPair(sourceColumns.get(i).getId(), null, targetColumns.get(i).getId(), null));
        return new PhysicalModelDto.Relation(null, null, "FK", null, source.getId(), null, target.getId(), null, pairs, null);
    }

    private int relations(TableInfo source, TableInfo target) {
        return jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Integer.class, source.getId(), target.getId());
    }

    private boolean columnExists(Long id) {
        return jdbc.queryForObject("select count(*) from TB_COL where COL_SN = ?", Integer.class, id) > 0;
    }

    private void assertNoOrphanRelationColumns() {
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL r where not exists (select 1 from TB_TBL_REL_COL rc where rc.REL_SN = r.REL_SN)", Integer.class)).isZero();
    }

    private Object rename(AccountDto account, TableInfo table, String newName) {
        TableInfoDto dto = new TableInfoDto();
        dto.setId(table.getId());
        dto.setTableName(newName);
        dto.setTableDescription(newName);
        dto.setIsMasterTable("N");
        dto.setBaseModifiedAt(jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", java.sql.Timestamp.class, table.getId()).toLocalDateTime());
        tableDesign.updateTable(dto, account);
        return null;
    }

    private TableInfoDto forDelete(AccountDto account, Long id) {
        TableInfoDto d = inTx(() -> TableInfoDto.fromEntity(tableRepository.findById(id).orElseThrow()));
        d.setComposition(tableDesign.selectTableDeletions(List.of(id), account).getFirst().composition());
        return d;
    }

    private void bulk(AccountDto account, TableInfo table, List<ColumnBulkSaveDto.Row> rows) {
        ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(account, ColumnBulkSaveDto.builder().tableInfoId(table.getId()).rows(rows).build());
        if (!result.result()) throw new IllegalStateException("일괄 저장 실패 : " + result.errors());
    }

    private static ColumnBulkSaveDto.Row bulkRow(ColumnInfoDto c) {
        return ColumnBulkSaveDto.Row.builder().key(String.valueOf(c.getId())).id(c.getId()).columnDesc(c.getColumnDesc()).dataType(c.getDataType()).maxLength(c.getMaxLength())
                .precision(c.getPrecision()).scale(c.getScale()).defaultValue(c.getDefaultValue()).identity(c.getIdentity()).refTableName(c.getRefTableName())
                .example(c.getExample()).isPk(c.getIsPk()).isNullable(c.getIsNullable()).isRequired(c.getIsRequired()).isUnique(c.getIsUnique()).isIndex(c.getIsIndex())
                .isMasterData(c.getIsMasterData()).isSensitive(c.getIsSensitive()).isEncrypted(c.getIsEncrypted()).baseModifiedAt(c.getModifiedAt()).build();
    }

    private static ColumnBulkSaveDto.Row unchangedRow(ColumnInfoDto c) {
        return ColumnBulkSaveDto.Row.builder().key(String.valueOf(c.getId())).id(c.getId()).unchanged(true).build();
    }

    private static ColumnBulkSaveDto.Row newRow(String key, long termId) {
        return ColumnBulkSaveDto.Row.builder().key(key).termId(termId).columnDesc("설명 " + key).dataType("VARCHAR").maxLength(20).isPk("N").isNullable("Y").isRequired("N")
                .isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N").build();
    }

    private int duplicateSortOrders(long tableId) {
        return jdbc.queryForObject("select count(*) from (select SORT_ORD from TB_COL where TBL_SN = ? group by SORT_ORD having count(*) > 1) d", Integer.class, tableId);
    }

    private static CommonColumnDto common(long termId) {
        return CommonColumnDto.builder().termId(termId).isNullable("Y").isIndex("N").build();
    }

    private int schemaCount(long projectId, String name) {
        return jdbc.queryForObject("select count(*) from TB_SCHM where PRJ_SN = ? and upper(SCHM_NM) = upper(?)", Integer.class, projectId, name);
    }

    /** [엔터티 ID, 속성 ID…] */
    private long[] entityWithAttributes(AccountDto account, String entityName, String... attributeNames) {
        var model = logical.saveModel(account, new LogicalModelDto.Model(null, "c4b-" + UUID.randomUUID().toString().substring(0, 6), null));
        var entity = logical.saveEntity(account, new LogicalModelDto.Entity(null, model.id(), entityName, null, null, null, null));
        long[] ids = new long[attributeNames.length + 1];
        ids[0] = entity.id();
        for (int i = 0; i < attributeNames.length; i++) {
            ids[i + 1] = logical.saveAttribute(account, new LogicalModelDto.Attribute(null, entity.id(), attributeNames[i], null, null, false, null, null, null, null, null)).id();
        }
        return ids;
    }

    /** 화면이 본 순서 : 지금 속성 순서대로 (ID, 정렬 순서) */
    private List<LogicalModelDto.AttributeOrder> orders(long entityId) {
        return jdbc.query("select LGCL_ATRB_SN, SORT_ORD from TB_LGCL_ATRB where LGCL_ENTT_SN = ? order by SORT_ORD, LGCL_ATRB_SN",
                (rs, i) -> new LogicalModelDto.AttributeOrder(rs.getLong(1), (Integer) rs.getObject(2)), entityId);
    }

    /** seen 을 positions 순서로 (positions[i] = 새 i 번째에 둘 seen 의 위치) */
    private static List<LogicalModelDto.AttributeOrder> reorder(List<LogicalModelDto.AttributeOrder> seen, int... positions) {
        List<LogicalModelDto.AttributeOrder> out = new ArrayList<>();
        for (int p : positions) out.add(seen.get(p));
        return out;
    }

    private List<Long> attributeIdsInOrder(long entityId) {
        return jdbc.queryForList("select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ? order by SORT_ORD, LGCL_ATRB_SN", Long.class, entityId);
    }

    private List<Integer> sortOrders(long entityId) {
        return jdbc.queryForList("select SORT_ORD from TB_LGCL_ATRB where LGCL_ENTT_SN = ? order by SORT_ORD, LGCL_ATRB_SN", Integer.class, entityId);
    }

    private com.lexorma.data.logical.domain.entity.LogicalAttribute entityAttribute(long entityId, long attributeId) {
        return context.getBean(com.lexorma.data.logical.repository.LogicalAttributeRepository.class).findById(attributeId).orElseThrow();
    }

    private java.time.LocalDateTime attributeModifiedAt(long attributeId) {
        return context.getBean(com.lexorma.data.logical.repository.LogicalAttributeRepository.class).findById(attributeId).orElseThrow().modifiedAt();
    }

    private long history(long projectId) {
        return jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Long.class, projectId);
    }

    private static List<String[]> rows(String[]... rows) {
        return Arrays.asList(rows);
    }

    private Object upload(AccountDto account, List<String[]> rows) {
        try {
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(rows)), account);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    private static byte[] workbook(List<String[]> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            int r = 1;
            for (String[] v : rows) {
                List<String> values = Arrays.asList(v[0], v[1], "설명", "VARCHAR", "20", "0", "0", "Y", "", "9", "N", "", "N", "N", "N", "N", "N", "N");
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void report(String label, String[] result) {
        System.out.println("[M8-C4b] " + label + " : A=" + result[0] + " B=" + result[1]);
    }

    // ------------------------------------------------------------------ 동시 실행 도구 (C1~C4a 와 같은 방식)

    /**
     * A 를 한 트랜잭션에서 실행하고, 커밋 전에 B 를 다른 스레드(다른 DB 연결)에서 시작한다. B 가 끝났거나 DB 잠금을 기다리기 시작하면 A 를 커밋한다.
     * 결과 [A, B] — A 의 커밋 실패(예 : 커밋 때의 FK 위반)도 결과로 남긴다
     */
    private String[] both(AccountDto account, Runnable a, Runnable b) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<String>> other = new AtomicReference<>();
        try {
            String first = outcome(() -> tx.executeWithoutResult(s -> {
                a.run();
                other.set(pool.submit(() -> {
                    SecurityTestSupport.loginAs(projectRepository, account.getProjectId(), String.valueOf(account.getId()), "ROLE_ADMIN");
                    try {
                        return outcome(b);
                    } finally {
                        SecurityTestSupport.clear();
                    }
                }));
                awaitDoneOrLockWait(other.get());
            }));
            String second = other.get() == null ? "NOT_STARTED" : other.get().get(30, TimeUnit.SECONDS);
            return new String[]{first, second};
        } finally {
            pool.shutdownNow();
        }
    }

    private static String outcome(Runnable call) {
        try {
            call.run();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof CustomException c) return c.getErrorCode().name();
            }
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException s && s.getSQLState() != null) return e.getClass().getSimpleName() + "/SQLSTATE:" + s.getSQLState();
            }
            return e.getClass().getSimpleName() + (e instanceof IllegalStateException ? ":" + e.getMessage() : "");
        }
    }

    private void awaitDoneOrLockWait(Future<?> work) {
        String sql = postgres() ? "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select count(*) from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (work.isDone()) return;
            if (jdbc.queryForObject(sql, Integer.class) > 0) return;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("B 가 끝나지도, 잠금을 기다리지도 않았다"
                + ConcurrencyDiagnostics.describe("C4b B 대기", started, jdbc, dataSource, projectRepository));
    }

    private boolean postgres() {
        try {
            return jdbc.queryForObject("select version()", String.class).contains("PostgreSQL");
        } catch (Exception e) {
            return false;
        }
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }
}
