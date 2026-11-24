package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8-C4b-R2 (docs/completed/modeling-concurrency-m8.md 19장) : 실제 PK 구성 변경(PK 여부 변경·새 PK 컬럼·복합 PK 변경) ↔ 참조 테이블명(REF_COLUMN) 관계 동기화, 불필요한 구조 변경 잠금 방지.
 * C1~C4b 와 같은 방식 : A 를 트랜잭션 안에서 실행하고 커밋 전에 B 를 다른 스레드(다른 연결)에서 시작, B 가 끝났거나 DB 잠금을 기다리는 것을 세션 정보로 확인한 뒤 A 를 커밋한다.
 * 결과 [A, B] 와 끝난 뒤 DB 상태(관계 · 관계 컬럼 · 참조 테이블명)를 함께 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC4bPkChangeIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired ProjectRepository projectRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired SchemaService schemaService;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired ColumnManageService columns;
    @Autowired TableDesignService tableDesign;
    @Autowired PhysicalModelService physical;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ PK 구성 변경 ↔ 참조 등록

    @Test
    @DisplayName("PK 여부 Y → N ↔ 참조 컬럼 등록 : 등록이 PK 변경 커밋을 기다린 뒤 PK 가 없음을 보고 관계를 만들지 않는다 — PK 가 아닌 컬럼을 가리키는 관계 없음")
    void pkOffVsInsert() throws Exception {
        Fx x = fixture("p2a");
        x.approveTarget();
        ColumnInfo pk = column(x.target, "T_ID", 1, true);
        String[] r = both(x.f.user, () -> bulk(x.f.user, x.target, List.of(pkRow(pk, false))), () -> columns.insertColumn(x.f.user, insert(x, "TB_P2A_T")));
        report("PK Y→N ↔ 참조 등록", r);
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("OK");
        assertThat(refRelations(x.source)).isZero();
        assertConsistent(x);
    }

    @Test
    @DisplayName("PK 여부 N → Y (PK 없던 테이블) ↔ 참조 컬럼 등록 : 둘이 끝나면 그 PK 로 관계가 있다 (예전 : 서로 커밋 전 상태를 봐 관계가 빠졌다)")
    void pkOnVsInsert() throws Exception {
        Fx x = fixture("p2b");
        x.approveTarget();
        ColumnInfo c = column(x.target, "T_ID", 1, false);
        String[] r = both(x.f.user, () -> bulk(x.f.user, x.target, List.of(pkRow(c, true))), () -> columns.insertColumn(x.f.user, insert(x, "TB_P2B_T")));
        report("PK N→Y ↔ 참조 등록", r);
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("OK");
        assertThat(refRelations(x.source)).isEqualTo(1);
        assertConsistent(x);
    }

    @Test
    @DisplayName("복합 PK 일부 해제(K1,K2 → K1) ↔ 참조 등록 : 단일 PK 가 되었으니 관계가 있다 · 복합 PK 로 변경(K1 → K1,K2) ↔ 참조 등록 : 관계가 없다")
    void compositeChanges() throws Exception {
        Fx x = fixture("p2c");
        x.approveTarget();
        column(x.target, "K1", 1, true);
        ColumnInfo k2 = column(x.target, "K2", 2, true);
        String[] off = both(x.f.user, () -> bulk(x.f.user, x.target, List.of(pkRow(k2, false))), () -> columns.insertColumn(x.f.user, insert(x, "TB_P2C_T")));
        report("복합 PK 일부 해제 ↔ 참조 등록", off);
        assertThat(off[0]).isEqualTo("OK");
        assertThat(off[1]).isEqualTo("OK");
        assertThat(refRelations(x.source)).isEqualTo(1);
        assertConsistent(x);

        Fx y = fixture("p2d");
        y.approveTarget();
        column(y.target, "K1", 1, true);
        ColumnInfo k2b = column(y.target, "K2", 2, false);
        String[] on = both(y.f.user, () -> bulk(y.f.user, y.target, List.of(pkRow(k2b, true))), () -> columns.insertColumn(y.f.user, insert(y, "TB_P2D_T")));
        report("단일 → 복합 PK ↔ 참조 등록", on);
        assertThat(on[0]).isEqualTo("OK");
        assertThat(on[1]).isEqualTo("OK");
        assertThat(refRelations(y.source)).isZero();
        assertConsistent(y);
    }

    @Test
    @DisplayName("새 PK 컬럼 단건 등록(단일 → 복합) · 엑셀의 PK 행 ↔ 참조 등록 : 관계가 없다 (예전 : 바뀌기 전 단일 PK 로 관계가 남았다)")
    void newPkColumnVsInsert() throws Exception {
        Fx x = fixture("p2e");
        column(x.target, "K1", 1, true);
        long termK2 = x.f.term("K2", "BIGINT", 0);
        String[] single = both(x.f.user, () -> columns.insertColumn(x.f.user, pkInsert(x.target, termK2, "K2")), () -> columns.insertColumn(x.f.user, insert(x, "TB_P2E_T")));
        report("새 PK 컬럼 단건 등록 ↔ 참조 등록", single);
        assertThat(single[0]).isEqualTo("OK");
        assertThat(single[1]).isEqualTo("OK");
        assertThat(refRelations(x.source)).isZero();
        assertConsistent(x);

        Fx y = fixture("p2f");
        column(y.target, "K1", 1, true);
        y.f.term("K2", "BIGINT", 0);
        String[] excel = both(y.f.user, () -> upload(y.f.user, rows(new String[]{"TB_P2F_T", "K2", "", "Y"})), () -> columns.insertColumn(y.f.user, insert(y, "TB_P2F_T")));
        report("엑셀 PK 행 ↔ 참조 등록", excel);
        assertThat(excel[0]).isEqualTo("OK");
        assertThat(excel[1]).isEqualTo("OK");
        assertThat(refRelations(y.source)).isZero();
        assertConsistent(y);
    }

    @Test
    @DisplayName("PK 변경 ↔ 명시적 FK 등록(그 PK 를 대상으로) : 등록이 기다린 뒤 키가 아니라 400 · PK 변경 ↔ 참조 해제 : 둘 다 반영, 관계 없음")
    void explicitFkAndRefClear() throws Exception {
        Fx x = fixture("p2g");
        x.approveTarget();
        ColumnInfo pk = column(x.target, "T_ID", 1, true);
        ColumnInfo plain = column(x.source, "S_T_ID", 5, false);
        String[] fk = both(x.f.user, () -> bulk(x.f.user, x.target, List.of(pkRow(pk, false))),
                () -> physical.saveRelation(x.f.user, new PhysicalModelDto.Relation(null, null, "FK", null, x.source.getId(), null, x.target.getId(), null,
                        List.of(new PhysicalModelDto.ColumnPair(plain.getId(), null, pk.getId(), null)), null)));
        report("PK 해제 ↔ 명시적 FK 등록", fk);
        assertThat(fk[0]).isEqualTo("OK");
        assertThat(fk[1]).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ?", Integer.class, x.source.getId())).isZero();

        Fx y = fixture("p2h");
        y.approveTarget();
        ColumnInfo pk2 = column(y.target, "T_ID", 1, true);
        assertThat(outcome(() -> columns.insertColumn(y.f.user, insert(y, "TB_P2H_T")))).isEqualTo("OK");
        assertThat(refRelations(y.source)).isEqualTo(1);
        tx.executeWithoutResult(s -> columnRepository.findAllByTableInfo_IdIn(List.of(y.source.getId())).forEach(c -> c.setIsApproval(false)));
        ColumnInfoDto ref = dto(columnRepository.findAllByTableInfo_IdIn(List.of(y.source.getId())).getFirst());
        ref.setRefTableName(null);
        String[] clear = both(y.f.user, () -> bulk(y.f.user, y.target, List.of(pkRow(pk2, false))), () -> bulk(y.f.user, y.source, List.of(bulkRow(ref))));
        report("PK 해제 ↔ 참조 해제", clear);
        assertThat(clear[0]).isEqualTo("OK");
        assertThat(clear[1]).isIn("OK", "MODEL_CONFLICT");
        assertThat(refRelations(y.source)).isZero();
        assertConsistent(y);
    }

    // ------------------------------------------------------------------ 구조 변경 잠금을 쓰지 않는 저장 · 쓰는 저장

    @Test
    @DisplayName("PK 구성이 그대로인 저장(PK 컬럼 설명 · Y→Y · N→N · 일반 컬럼 등록 · 바꾸지 않은 PK 행 · PK 행 없는 엑셀)은 프로젝트 배타를 잡지 않는다 — 관계없는 테이블의 저장이 기다리지 않는다. PK 를 바꾸는 저장은 잡는다")
    void noKeyLockUnlessPkChanges() throws Exception {
        Fx x = fixture("p2i");
        x.approveTarget();
        ColumnInfo pk = column(x.target, "T_ID", 1, true);
        ColumnInfo plain = column(x.target, "T_CD", 2, false);
        TableInfo other = table(x.f.user.getProjectId(), "TB_P2I_U", false);
        long[] n = {0};
        Supplier<Runnable> unrelated = () -> {
            long term = x.f.term("U_CD" + (++n[0]), "VARCHAR", 10);
            String name = "U_CD" + n[0];
            return () -> columns.insertColumn(x.f.user, plain(other, term, name));
        };
        ColumnInfoDto desc = dto(pk);
        desc.setColumnDesc("PK 설명만");
        assertNoProjectLock(x, "PK 컬럼 설명", () -> bulk(x.f.user, x.target, List.of(bulkRow(desc))), unrelated.get());
        assertNoProjectLock(x, "PK Y→Y", () -> bulk(x.f.user, x.target, List.of(pkRow(pk, true))), unrelated.get());
        assertNoProjectLock(x, "PK N→N", () -> bulk(x.f.user, x.target, List.of(pkRow(plain, false))), unrelated.get());
        long termNew = x.f.term("T_NEW", "VARCHAR", 10);
        assertNoProjectLock(x, "일반 컬럼 등록", () -> columns.insertColumn(x.f.user, plain(x.target, termNew, "T_NEW")), unrelated.get());
        long termRow = x.f.term("T_ROW", "VARCHAR", 10);
        assertNoProjectLock(x, "바꾸지 않은 PK 행 + 새 일반 컬럼", () -> bulk(x.f.user, x.target, List.of(unchangedRow(pk), newPlainRow("r", termRow))), unrelated.get());
        x.f.term("T_XL", "VARCHAR", 10);
        assertNoProjectLock(x, "PK 행 없는 엑셀", () -> upload(x.f.user, rows(new String[]{"TB_P2I_T", "T_XL", "", "N"})), unrelated.get());

        String[] key = both(x.f.user, () -> bulk(x.f.user, x.target, List.of(pkRow(plain, true))), unrelated.get());
        report("PK 를 바꾸는 저장 ↔ 관계없는 테이블 등록", key);
        assertThat(key[0]).isEqualTo("OK");
        assertThat(key[1]).isEqualTo("OK");
        assertThat(key[2]).as("구조 변경은 프로젝트 배타 — 다른 저장은 커밋 때 기다린다").isEqualTo("true");
    }

    @Test
    @DisplayName("PK 변경은 구조 변경 경로 : 같은 테이블을 다른 저장이 쥐고 있으면 기다리지 않고 409 (전체 롤백 — PK 그대로) · 잠금 전 판정과 달라진 저장은 409 · PK 를 바꾸는 저장 둘(서로 참조)은 교착 없이 차례로")
    void keyPathContracts() throws Exception {
        Fx x = fixture("p2j");
        x.approveTarget();
        ColumnInfo pk = column(x.target, "T_ID", 1, true);
        ColumnInfo plain = column(x.target, "T_CD", 2, false);
        ColumnInfoDto desc = dto(plain);
        desc.setColumnDesc("다른 사용자");
        String[] busy = both(x.f.user, () -> bulk(x.f.user, x.target, List.of(bulkRow(desc))), () -> bulk(x.f.user, x.target, List.of(pkRow(pk, false))));
        report("같은 테이블 저장 중 PK 변경", busy);
        assertThat(busy[0]).isEqualTo("OK");
        assertThat(busy[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> columnRepository.findById(pk.getId()).orElseThrow().getIsPk())).isTrue();

        // 잠금 전에는 PK 를 바꾸지 않는 저장(T_ID 를 PK 그대로)이 잠근 뒤에는 PK 를 바꾸는 저장이 된다 (그 사이 다른 저장이 T_ID 의 PK 를 껐다)
        ColumnInfoDto stale = dto(pk);
        stale.setColumnDesc("예전 화면");
        String[] staleKey = both(x.f.user, () -> bulk(x.f.user, x.target, List.of(pkRow(pk, false))), () -> bulk(x.f.user, x.target, List.of(bulkRow(stale))));
        report("PK 해제 ↔ 예전 화면의 PK 컬럼 저장", staleKey);
        assertThat(staleKey[0]).isEqualTo("OK");
        assertThat(staleKey[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> columnRepository.findById(pk.getId()).orElseThrow().getIsPk())).isFalse();

        // 서로를 참조하는 두 테이블이 동시에 PK 를 켠다 : 프로젝트 배타로 차례로, 교착 없음, 관계 둘 다
        Fx y = fixture("p2k");
        y.approveTarget();
        tx.executeWithoutResult(s -> tableRepository.findById(y.source.getId()).orElseThrow().setIsApproval(true));
        ColumnInfo tId = column(y.target, "T_ID", 1, false);
        ColumnInfo sId = column(y.source, "S_ID", 1, false);
        assertThat(outcome(() -> columns.insertColumn(y.f.user, insert(y, "TB_P2K_T")))).isEqualTo("OK");
        long termBack = y.f.term("R2_BACK", "BIGINT", 0);
        ColumnInfoDto back = plain(y.target, termBack, "R2_BACK");
        back.setDataType("BIGINT");
        back.setMaxLength(null);
        back.setRefTableName("TB_P2K_S");
        assertThat(outcome(() -> columns.insertColumn(y.f.user, back))).isEqualTo("OK");
        String[] cross = both(y.f.user, () -> bulk(y.f.user, y.target, List.of(pkRow(tId, true))), () -> bulk(y.f.user, y.source, List.of(pkRow(sId, true))));
        report("서로 참조하는 두 테이블의 PK 켜기", cross);
        assertThat(cross[0]).isEqualTo("OK");
        assertThat(cross[1]).isEqualTo("OK");
        assertThat(refRelations(y.source)).isEqualTo(1);
        assertThat(refRelations(y.target)).isEqualTo(1);
        assertConsistent(y);
    }

    @Test
    @DisplayName("엑셀 : 여러 테이블 중 일부만 PK 행 — 성공, 참조 정합 · PK 행 없는 여러 테이블 엑셀 회귀")
    void excelMultiTable() throws Exception {
        Fx x = fixture("p2l");
        TableInfo t2 = table(x.f.user.getProjectId(), "TB_P2L_T2", false);
        x.f.term("K1", "BIGINT", 0);
        x.f.term("C1", "BIGINT", 0);
        assertThat(outcome(() -> upload(x.f.user, rows(new String[]{"TB_P2L_T", "K1", "", "Y"}, new String[]{"TB_P2L_T2", "C1", "TB_P2L_T", "N"})))).isEqualTo("OK");
        assertThat(refRelations(t2)).isEqualTo(1);
        assertConsistent(x);
        x.f.term("C2", "BIGINT", 0);
        assertThat(outcome(() -> upload(x.f.user, rows(new String[]{"TB_P2L_T2", "C2", "", "N"})))).isEqualTo("OK");
    }

    private void assertNoProjectLock(Fx x, String label, Runnable a, Runnable unrelated) throws Exception {
        String[] r = both(x.f.user, a, unrelated);
        report(label + " ↔ 관계없는 테이블 등록", r);
        assertThat(r[0]).as(label).isEqualTo("OK");
        assertThat(r[1]).as(label).isEqualTo("OK");
        assertThat(r[2]).as(label + " : 관계없는 저장은 기다리지 않는다 (구조 변경 잠금 없음)").isEqualTo("false");
    }

    private ColumnBulkSaveDto.Row pkRow(ColumnInfo column, boolean pk) {
        ColumnInfoDto d = dto(column);
        d.setIsPk(pk ? "Y" : "N");
        d.setIsUnique(pk ? "Y" : "N");
        d.setIsRequired(pk ? "Y" : "N");
        d.setIsNullable(pk ? "N" : "Y");
        return bulkRow(d);
    }

    private static ColumnInfoDto pkInsert(TableInfo table, long term, String name) {
        ColumnInfoDto d = plain(table, term, name);
        d.setDataType("BIGINT");
        d.setMaxLength(null);
        d.setIsPk("Y");
        d.setIsNullable("N");
        return d;
    }

    private static ColumnBulkSaveDto.Row newPlainRow(String key, long termId) {
        return ColumnBulkSaveDto.Row.builder().key(key).termId(termId).columnDesc("일반 " + key).dataType("VARCHAR").maxLength(10).isPk("N").isNullable("Y")
                .isRequired("N").isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N").build();
    }

    private static ColumnBulkSaveDto.Row unchangedRow(ColumnInfo c) {
        return ColumnBulkSaveDto.Row.builder().key(String.valueOf(c.getId())).id(c.getId()).unchanged(true).build();
    }

    // ------------------------------------------------------------------ 준비

    /** 프로젝트 · 참조하는 테이블 S(TB_<p>_S) · 참조 대상 T(TB_<p>_T) · 참조 컬럼용 표준 용어 */
    private final class Fx {
        final GenerationFixture f;
        final TableInfo source;
        final TableInfo target;
        final long termRef;
        final long termRef2;

        Fx(String prefix) {
            f = GenerationFixture.create(context, "c4b-r2-" + prefix + "-" + System.nanoTime(), DbType.POSTGRESQL);
            String p = prefix.toUpperCase();
            source = table(f.user.getProjectId(), "TB_" + p + "_S", true);
            target = table(f.user.getProjectId(), "TB_" + p + "_T", false);
            termRef = f.term("R1_REF", "BIGINT", 0);
            termRef2 = f.term("R1_REF2", "BIGINT", 0);
        }

        void approveTarget() {
            tx.executeWithoutResult(s -> tableRepository.findById(target.getId()).orElseThrow().setIsApproval(true));
        }
    }

    private Fx fixture(String prefix) {
        return new Fx(prefix);
    }

    private ColumnInfoDto insert(Fx x, String reference) {
        ColumnInfoDto d = new ColumnInfoDto();
        d.setTableInfoId(x.source.getId());
        d.setTermId(x.termRef);
        d.setColumnName("R1_REF");
        d.setColumnDesc("참조");
        d.setDataType("BIGINT");
        d.setRefTableName(reference);
        d.setIsPk("N");
        d.setIsNullable("Y");
        yn(d);
        return d;
    }

    private static ColumnInfoDto plain(TableInfo table, long term, String name) {
        ColumnInfoDto d = new ColumnInfoDto();
        d.setTableInfoId(table.getId());
        d.setTermId(term);
        d.setColumnName(name);
        d.setColumnDesc(name);
        d.setDataType("VARCHAR");
        d.setMaxLength(10);
        d.setIsPk("N");
        d.setIsNullable("Y");
        yn(d);
        return d;
    }

    private static void yn(ColumnInfoDto d) {
        d.setIsRequired("N");
        d.setIsUnique("N");
        d.setIsIndex("N");
        d.setIsMasterData("N");
        d.setIsSensitive("N");
        d.setIsEncrypted("N");
    }

    private TableInfo table(long projectId, String name, boolean approved) {
        return inTx(() -> tableRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(TableInfo table, String name, int order, boolean pk) {
        return inTx(() -> columnRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableName(table.getTableName())
                .tableInfo(tableRepository.getReferenceById(table.getId())).columnName(name).columnDesc(name).sortOrder(order)
                .dataType("BIGINT").isPk(pk).isUnique(pk).isRequired(pk).isNullable(!pk).isApproval(false).build()));
    }

    private ColumnInfoDto dto(ColumnInfo c) {
        return inTx(() -> ColumnInfoDto.fromEntity(columnRepository.findById(c.getId()).orElseThrow()));
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

    private static ColumnBulkSaveDto.Row newRow(String key, long termId, String reference) {
        return ColumnBulkSaveDto.Row.builder().key(key).termId(termId).columnDesc("참조 " + key).dataType("BIGINT").refTableName(reference).isPk("N").isNullable("Y")
                .isRequired("N").isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N").build();
    }

    private int refRelations(TableInfo source) {
        return jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and REL_SRC_CD = 'REF_COLUMN'", Integer.class, source.getId());
    }

    private String refColumn(TableInfo source) {
        return jdbc.queryForObject("select RFRNC_TBL_NM from TB_COL where TBL_SN = ? and COL_NM = 'R1_REF'", String.class, source.getId());
    }

    /** 이 프로젝트의 고아 관계 컬럼(없는 컬럼을 가리킴)·컬럼 없는 관계가 없다 */
    private void assertNoOrphans(Fx x) {
        long p = x.f.user.getProjectId();
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL_COL rc join TB_TBL_REL r on r.REL_SN = rc.REL_SN where r.PRJ_SN = ? and (not exists (select 1 from TB_COL c where c.COL_SN = rc.TRGT_COL_SN)"
                + " or not exists (select 1 from TB_COL c where c.COL_SN = rc.ORGNL_COL_SN))", Integer.class, p)).as("없는 컬럼을 가리키는 관계 컬럼").isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL r where r.PRJ_SN = ? and not exists (select 1 from TB_TBL_REL_COL rc where rc.REL_SN = r.REL_SN)", Integer.class, p)).as("컬럼 없는 관계").isZero();
    }

    /**
     * REF_COLUMN 관계 정합성 : 이 프로젝트의 REF_COLUMN 관계마다 대상 컬럼은 대상 테이블의 PK 이고, 파생 컬럼의 참조 테이블명이 지금 대상 테이블의 이름(또는 스키마명.테이블명)이다
     */
    private void assertConsistent(Fx x) {
        assertNoOrphans(x);
        List<Map<String, Object>> rows = jdbc.queryForList("select r.REL_SN, upper(c.RFRNC_TBL_NM) as REF, upper(t.TBL_NM) as TNAME, upper(s.SCHM_NM) as SNAME, tc.PK_YN as PK"
                + " from TB_TBL_REL r join TB_COL c on c.COL_SN = r.DRVN_COL_SN join TB_TBL t on t.TBL_SN = r.TRGT_TBL_SN join TB_SCHM s on s.SCHM_SN = t.SCHM_SN"
                + " join TB_TBL_REL_COL rc on rc.REL_SN = r.REL_SN join TB_COL tc on tc.COL_SN = rc.TRGT_COL_SN where r.PRJ_SN = ? and r.REL_SRC_CD = 'REF_COLUMN'", x.f.user.getProjectId());
        for (Map<String, Object> row : rows) {
            String ref = String.valueOf(row.get("REF"));
            assertThat(ref.equals(row.get("TNAME")) || ref.equals(row.get("SNAME") + "." + row.get("TNAME"))).as("참조 테이블명 ↔ 관계 대상 " + row).isTrue();
            assertThat(row.get("PK")).as("REF_COLUMN 대상은 PK " + row).isEqualTo(Boolean.TRUE);
        }
    }

    private Object upload(AccountDto account, List<String[]> rows) {
        try {
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(rows)), account);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    private static List<String[]> rows(String[]... rows) {
        return Arrays.asList(rows);
    }

    /** (테이블명, 컬럼명, 참조 테이블명, PK Y/N) */
    private static byte[] workbook(List<String[]> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            int r = 1;
            for (String[] v : rows) {
                boolean pk = v.length > 3 && "Y".equals(v[3]);
                List<String> values = Arrays.asList(v[0], v[1], "설명", "BIGINT", "0", "0", "0", pk ? "N" : "Y", "", "9", "N", v[2], pk ? "Y" : "N", "N", pk ? "Y" : "N", pk ? "Y" : "N", "N", "N");
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
        System.out.println("[M8-C4b-R2] " + label + " : A=" + result[0] + " B=" + result[1] + (result.length > 2 ? " B대기=" + result[2] : ""));
    }

    // ------------------------------------------------------------------ 동시 실행 도구 (C4b 와 같은 방식)

    private String[] both(AccountDto account, Runnable a, Runnable b) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<String>> other = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean waited = new java.util.concurrent.atomic.AtomicBoolean();
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
                waited.set(awaitDoneOrLockWait(other.get()));
            }));
            String second = other.get() == null ? "NOT_STARTED" : other.get().get(30, TimeUnit.SECONDS);
            return new String[]{first, second, String.valueOf(waited.get())};
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

    /** true = B 가 A 커밋 전에 DB 잠금을 기다렸다, false = A 커밋 전에 끝났다 */
    private boolean awaitDoneOrLockWait(Future<?> work) {
        String sql = postgres() ? "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select count(*) from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (work.isDone()) return false;
            if (jdbc.queryForObject(sql, Integer.class) > 0) return true;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        throw new IllegalStateException("B 가 끝나지도, 잠금을 기다리지도 않았다"
                + ConcurrencyDiagnostics.describe("C4b-R2 B 대기", started, jdbc, dataSource, projectRepository));
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

    @SuppressWarnings("unused")
    private SchemaInfo schema(Long id) {
        return schemaRepository.findById(id).orElseThrow();
    }
}
