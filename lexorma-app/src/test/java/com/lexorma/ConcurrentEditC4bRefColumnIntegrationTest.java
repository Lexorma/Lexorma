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
 * M8-C4b-R1 (docs/completed/modeling-concurrency-m8.md 18장) : 참조 테이블명(REF_COLUMN) 관계 동기화 ↔ 참조 대상의 키 변경(PK 컬럼 삭제·테이블 이름 변경·스키마 이름 변경·PK 여부 변경)·삭제.
 * C1~C4b 와 같은 방식 : A 를 트랜잭션 안에서 실행하고 커밋 전에 B 를 다른 스레드(다른 연결)에서 시작, B 가 끝났거나 DB 잠금을 기다리는 것을 세션 정보로 확인한 뒤 A 를 커밋한다.
 * 결과 [A, B] 와 끝난 뒤 DB 상태(관계 · 관계 컬럼 · 참조 테이블명)를 함께 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC4bRefColumnIntegrationTest {

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

    // ------------------------------------------------------------------ 참조 대상 PK 컬럼 삭제 (구조 변경 경로)

    @Test
    @DisplayName("참조 대상 PK 컬럼 삭제 ↔ 참조 컬럼 단건 등록 : 삭제가 끝날 때까지 기다린 뒤 PK 가 없으니 관계를 만들지 않는다 — 지워진 PK 를 가리키는 관계·FK 위반 500 없음")
    void pkDeleteVsInsert() throws Exception {
        Fx x = fixture("r1a");
        ColumnInfo pk = column(x.target, "T_ID", 1, true);
        String[] r = both(x.f.user, () -> columns.deleteColumn(x.f.user, List.of(dto(pk))), () -> columns.insertColumn(x.f.user, insert(x, "TB_R1A_T")));
        report("PK 컬럼 삭제 ↔ 참조 컬럼 등록", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(refRelations(x.source)).isZero();
        assertThat(refColumn(x.source)).isEqualTo("TB_R1A_T");
        assertConsistent(x);
    }

    @Test
    @DisplayName("참조 대상 PK 컬럼 삭제 ↔ 참조를 적은 일괄 저장 · 엑셀 업로드 : 같은 결과 (관계 없음, 500 없음)")
    void pkDeleteVsBulkAndExcel() throws Exception {
        Fx x = fixture("r1b");
        ColumnInfo pk = column(x.target, "T_ID", 1, true);
        String[] bulk = both(x.f.user, () -> columns.deleteColumn(x.f.user, List.of(dto(pk))),
                () -> bulk(x.f.user, x.source, List.of(newRow("n", x.termRef, "TB_R1B_T"))));
        report("PK 컬럼 삭제 ↔ 일괄 저장", bulk);
        assertThat(bulk).containsExactly("OK", "OK");
        assertThat(refRelations(x.source)).isZero();

        Fx y = fixture("r1c");
        ColumnInfo pk2 = column(y.target, "T_ID", 1, true);
        String[] excel = both(y.f.user, () -> columns.deleteColumn(y.f.user, List.of(dto(pk2))),
                () -> upload(y.f.user, rows(new String[]{"TB_R1C_S", "R1_REF", "TB_R1C_T"})));
        report("PK 컬럼 삭제 ↔ 엑셀", excel);
        assertThat(excel).containsExactly("OK", "OK");
        assertThat(refRelations(y.source)).isZero();
        assertConsistent(x);
        assertConsistent(y);
    }

    // ------------------------------------------------------------------ 참조 대상 이름 변경 (구조 변경 경로)

    @Test
    @DisplayName("참조 대상 테이블 이름 변경 ↔ 예전 이름을 적은 참조 컬럼 등록 : 이름 변경 뒤의 이름으로 다시 해석해 관계를 만들지 않는다 — 참조 테이블명과 관계 대상이 어긋나지 않는다")
    void renameVsInsert() throws Exception {
        Fx x = fixture("r1d");
        column(x.target, "T_ID", 1, true);
        String[] r = both(x.f.user, () -> rename(x.f.user, x.target, "TB_R1D_T_NEW"), () -> columns.insertColumn(x.f.user, insert(x, "TB_R1D_T")));
        report("테이블 이름 변경 ↔ 예전 이름 참조 등록", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(refRelations(x.source)).isZero();
        assertConsistent(x);
        // 새 이름으로 적으면 만든다 (정상 경로 회귀)
        ColumnInfoDto again = insert(x, "TB_R1D_T_NEW");
        again.setTermId(x.termRef2);
        again.setColumnName("R1_REF2");
        assertThat(outcome(() -> columns.insertColumn(x.f.user, again))).isEqualTo("OK");
        assertThat(refRelations(x.source)).isEqualTo(1);
        assertConsistent(x);
    }

    @Test
    @DisplayName("참조 대상 이름 변경 ↔ 같은 테이블에 새 참조를 더하는 일괄 저장 : 기다린 뒤 다시 계산할 때 앞서 읽은 다른 컬럼의 예전 참조 테이블명을 쓰지 않는다 — 이름 변경이 고친 기존 참조의 관계가 지워지지 않는다")
    void renameVsBulkKeepsExistingRelation() throws Exception {
        Fx x = fixture("r1k");
        ColumnInfo pk = column(x.target, "T_ID", 1, true);
        assertThat(outcome(() -> columns.insertColumn(x.f.user, insert(x, "TB_R1K_T")))).isEqualTo("OK");
        assertThat(refRelations(x.source)).isEqualTo(1);
        String[] r = both(x.f.user, () -> rename(x.f.user, x.target, "TB_R1K_T_NEW"),
                () -> bulk(x.f.user, x.source, List.of(newRow("n", x.termRef2, "TB_R1K_T"))));
        report("이름 변경 ↔ 기존 참조가 있는 테이블의 새 참조 일괄 저장", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(refColumn(x.source)).as("이름 변경이 고친 기존 참조").isEqualTo("TB_R1K_T_NEW");
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL r join TB_COL c on c.COL_SN = r.DRVN_COL_SN where c.TBL_SN = ? and c.COL_NM = 'R1_REF'", Integer.class, x.source.getId()))
                .as("기존 참조의 관계는 남는다").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL r join TB_COL c on c.COL_SN = r.DRVN_COL_SN where c.TBL_SN = ? and c.COL_NM = 'R1_REF2'", Integer.class, x.source.getId()))
                .as("예전 이름으로 적은 새 참조는 관계가 없다").isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL_COL rc join TB_TBL_REL r on r.REL_SN = rc.REL_SN where r.ORGNL_TBL_SN = ? and rc.TRGT_COL_SN = ?", Integer.class,
                x.source.getId(), pk.getId())).isEqualTo(1);
        assertConsistent(x);
    }

    @Test
    @DisplayName("참조 대상 스키마 이름 변경 ↔ 스키마명.테이블명 참조 등록 : 예전 스키마명은 해석되지 않아 관계가 없다 (스키마 이름 변경이 커밋된 참조만 고친다)")
    void schemaRenameVsInsert() throws Exception {
        Fx x = fixture("r1e");
        SchemaDto schema = schemaService.saveSchema(x.f.user, new SchemaDto(null, "SC_R1E", null, null));
        tx.executeWithoutResult(s -> tableRepository.findById(x.target.getId()).orElseThrow().setSchema(schemaRepository.findById(schema.id()).orElseThrow()));
        column(x.target, "T_ID", 1, true);
        SchemaDto seen = inTx(() -> SchemaDto.fromEntity(schemaRepository.findById(schema.id()).orElseThrow()));
        String[] r = both(x.f.user, () -> schemaService.saveSchema(x.f.user, new SchemaDto(seen.id(), "SC_R1E_NEW", null, false, null, null, seen.updated(), null, null)),
                () -> columns.insertColumn(x.f.user, insert(x, "SC_R1E.TB_R1E_T")));
        report("스키마 이름 변경 ↔ 스키마명 참조 등록", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(refRelations(x.source)).isZero();
        assertConsistent(x);
    }

    // ------------------------------------------------------------------ 참조 대상 PK 여부 변경 (일반 경로 — D11 에서 구조 변경이 아니라고 정함)

    @Test
    @DisplayName("[남은 위험 · 정책 결정 대기] 참조 대상 PK 여부 해제(일반 일괄 저장) ↔ 참조 컬럼 등록 : 서로 기다리지 않아 PK 가 아닌 컬럼을 가리키는 REF_COLUMN 관계가 남는다 (D11 이 '사후 무효 FK' 로 둔 동작 — 교착·500·고아 없음)")
    void pkFlagOffVsInsert() throws Exception {
        Fx x = fixture("r1f");
        x.approveTarget();
        ColumnInfo pk = column(x.target, "T_ID", 1, true);
        ColumnInfoDto off = dto(pk);
        off.setIsPk("N");
        off.setIsUnique("N");
        String[] r = both(x.f.user, () -> bulk(x.f.user, x.target, List.of(bulkRow(off))), () -> columns.insertColumn(x.f.user, insert(x, "TB_R1F_T")));
        report("PK 여부 해제 ↔ 참조 컬럼 등록", r);
        int relations = refRelations(x.source);
        System.out.println("[M8-C4b-R1] PK 여부 해제 뒤 REF 관계 " + relations + ", 대상 PK " + inTx(() -> columnRepository.findById(pk.getId()).orElseThrow().getIsPk()));
        assertThat(r).containsExactly("OK", "OK");
        assertNoOrphans(x);
        // 다음에 그 테이블이 저장되면 동기화가 다시 맞춘다 (관계 없음)
        ColumnInfoDto touch = dto(columnRepository.findAllByTableInfo_IdIn(List.of(x.source.getId())).getFirst());
        touch.setColumnDesc("다시 저장");
        bulk(x.f.user, x.source, List.of(bulkRow(touch)));
        assertThat(refRelations(x.source)).isZero();
    }

    // ------------------------------------------------------------------ 삭제 · 재등록 (기존 정책)

    @Test
    @DisplayName("참조 대상 테이블 삭제(빈 테이블만 지울 수 있다) ↔ 참조 등록 : PK 가 없어 어느 순서든 관계 없음. 지운 뒤 같은 이름의 새 테이블 = 이름으로 해석하는 기존 정책대로 새 테이블의 PK 와 연결된다")
    void tableDeleteAndRecreate() throws Exception {
        Fx x = fixture("r1g");
        TableInfoDto del = forDelete(x.f.user, x.target.getId());
        String[] r = both(x.f.user, () -> tableDesign.deleteTable(List.of(del), x.f.user), () -> columns.insertColumn(x.f.user, insert(x, "TB_R1G_T")));
        report("대상 테이블 삭제 ↔ 참조 등록", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(refRelations(x.source)).isZero();
        TableInfo recreated = table(x.f.user.getProjectId(), "TB_R1G_T", false);
        ColumnInfo newPk = column(recreated, "T_ID", 1, true);
        // 새 테이블의 PK 컬럼 등록이 그 이름을 적어 둔 참조를 확정한다 (기존 동작 — 참조 테이블명이 정의다)
        tx.executeWithoutResult(s -> physical.syncReferenceRelations(x.f.user.getProjectId(), List.of(recreated.getId())));
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_SRC_CD = 'REF_COLUMN'", Integer.class,
                x.source.getId(), recreated.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL_COL rc join TB_TBL_REL r on r.REL_SN = rc.REL_SN where r.ORGNL_TBL_SN = ? and rc.TRGT_COL_SN = ?", Integer.class,
                x.source.getId(), newPk.getId())).isEqualTo(1);
        assertConsistent(x);
    }

    // ------------------------------------------------------------------ 교착 · 범위

    @Test
    @DisplayName("양방향 참조 등록 (S → T 와 T → S 를 동시에) · 명시적 FK 등록 ↔ 같은 대상의 참조 등록 : 교착 없이 모두 성공")
    void bidirectionalAndExplicit() throws Exception {
        Fx x = fixture("r1h");
        column(x.target, "T_ID", 1, true);
        column(x.source, "S_ID", 1, true);
        long termBack = x.f.term("R1_BACK", "BIGINT", 0);
        ColumnInfoDto back = new ColumnInfoDto();
        back.setTableInfoId(x.target.getId());
        back.setTermId(termBack);
        back.setColumnName("R1_BACK");
        back.setColumnDesc("역참조");
        back.setDataType("BIGINT");
        back.setRefTableName("TB_R1H_S");
        back.setIsPk("N");
        back.setIsNullable("Y");
        yn(back);
        String[] r = both(x.f.user, () -> columns.insertColumn(x.f.user, insert(x, "TB_R1H_T")), () -> columns.insertColumn(x.f.user, back));
        report("양방향 참조 등록", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(refRelations(x.source)).isEqualTo(1);
        assertThat(refRelations(x.target)).isEqualTo(1);

        TableInfo other = table(x.f.user.getProjectId(), "TB_R1H_O", false);
        ColumnInfo otherCol = column(other, "O_T_ID", 1, false);
        ColumnInfo tPk = columnRepository.findAllByTableInfo_IdIn(List.of(x.target.getId())).stream().filter(c -> Boolean.TRUE.equals(c.getIsPk())).findFirst().orElseThrow();
        ColumnInfoDto second = insert(x, "TB_R1H_T");
        second.setTermId(x.termRef2);
        second.setColumnName("R1_REF2");
        String[] explicit = both(x.f.user, () -> physical.saveRelation(x.f.user, new PhysicalModelDto.Relation(null, null, "FK", null, other.getId(), null, x.target.getId(), null,
                List.of(new PhysicalModelDto.ColumnPair(otherCol.getId(), null, tPk.getId(), null)), null)), () -> columns.insertColumn(x.f.user, second));
        report("명시적 FK 등록 ↔ 참조 등록", explicit);
        assertThat(explicit).containsExactly("OK", "OK");
        assertThat(refRelations(x.source)).isEqualTo(2);
        assertConsistent(x);
    }

    @Test
    @DisplayName("여러 테이블 참조 · 자기 참조 · 다른 프로젝트 같은 이름 · 관계없는 테이블 동시 등록 · PK 삭제 409 시 전체 롤백")
    void multiSelfForeignUnrelatedRollback() throws Exception {
        Fx x = fixture("r1i");
        column(x.target, "T_ID", 1, true);
        TableInfo t2 = table(x.f.user.getProjectId(), "TB_R1I_T2", false);
        column(t2, "T2_ID", 1, true);
        // 여러 테이블 : 두 대상 중 하나의 이름이 바뀌는 동안 두 참조를 한 번에 저장 → 바뀌지 않은 대상만 관계
        String[] multi = both(x.f.user, () -> rename(x.f.user, t2, "TB_R1I_T2_NEW"),
                () -> bulk(x.f.user, x.source, List.of(newRow("a", x.termRef, "TB_R1I_T"), newRow("b", x.termRef2, "TB_R1I_T2"))));
        report("여러 대상 중 하나 이름 변경 ↔ 두 참조 일괄 저장", multi);
        assertThat(multi).containsExactly("OK", "OK");
        assertThat(refRelations(x.source)).isEqualTo(1);

        // 자기 참조
        ColumnInfo selfPk = column(x.source, "S_ID", 9, true);
        long termUp = x.f.term("UP_S_ID", "BIGINT", 0);
        assertThat(outcome(() -> bulk(x.f.user, x.source, List.of(newRow("up", termUp, "TB_R1I_S"))))).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL_COL rc join TB_TBL_REL r on r.REL_SN = rc.REL_SN where r.ORGNL_TBL_SN = ? and r.TRGT_TBL_SN = ? and rc.TRGT_COL_SN = ?",
                Integer.class, x.source.getId(), x.source.getId(), selfPk.getId())).isEqualTo(1);

        // 다른 프로젝트의 테이블은 대상이 아니다 : 다른 프로젝트에서 이 프로젝트에만 있는 이름(TB_R1I_T)을 참조해도 관계가 없다
        Fx other = fixture("r1j");
        column(other.target, "T_ID", 1, true);
        SecurityTestSupport.loginAs(projectRepository, other.f.user.getProjectId(), String.valueOf(other.f.user.getId()), "ROLE_ADMIN");
        assertThat(outcome(() -> columns.insertColumn(other.f.user, insert(other, "TB_R1I_T")))).isEqualTo("OK");
        assertThat(refRelations(other.source)).isZero();

        // 관계없는 두 테이블 동시 등록 : 둘 다 성공
        SecurityTestSupport.loginAs(projectRepository, x.f.user.getProjectId(), String.valueOf(x.f.user.getId()), "ROLE_ADMIN");
        TableInfo u1 = table(x.f.user.getProjectId(), "TB_R1I_U1", false);
        TableInfo u2 = table(x.f.user.getProjectId(), "TB_R1I_U2", false);
        long tu1 = x.f.term("U1_CD", "VARCHAR", 10);
        long tu2 = x.f.term("U2_CD", "VARCHAR", 10);
        String[] unrelated = both(x.f.user, () -> columns.insertColumn(x.f.user, plain(u1, tu1, "U1_CD")), () -> columns.insertColumn(x.f.user, plain(u2, tu2, "U2_CD")));
        assertThat(unrelated).containsExactly("OK", "OK");

        // PK 삭제(구조 변경, NOWAIT)가 참조 컬럼을 고치는 저장과 겹치면 409 — 삭제 전체 롤백 (PK·관계 그대로)
        ColumnInfo refCol = columnRepository.findAllByTableInfo_IdIn(List.of(x.source.getId())).stream().filter(c -> "TB_R1I_T".equals(c.getRefTableName())).findFirst().orElseThrow();
        ColumnInfoDto refSeen = dto(refCol);
        refSeen.setColumnDesc("참조 컬럼 설명 수정");
        ColumnInfo tPk = columnRepository.findAllByTableInfo_IdIn(List.of(x.target.getId())).getFirst();
        String[] busy = both(x.f.user, () -> bulk(x.f.user, x.source, List.of(bulkRow(refSeen))), () -> columns.deleteColumn(x.f.user, List.of(dto(tPk))));
        report("참조 컬럼 수정 중 대상 PK 삭제", busy);
        assertThat(busy).containsExactly("OK", "MODEL_CONFLICT");
        assertThat(inTx(() -> columnRepository.findById(tPk.getId()).isPresent())).isTrue();
        assertThat(refRelations(x.source)).as("TB_R1I_T 참조 + 자기 참조").isEqualTo(2);
        assertConsistent(x);
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
            f = GenerationFixture.create(context, "c4b-r1-" + prefix + "-" + System.nanoTime(), DbType.POSTGRESQL);
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

    /** (테이블명, 컬럼명, 참조 테이블명) */
    private static byte[] workbook(List<String[]> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            int r = 1;
            for (String[] v : rows) {
                List<String> values = Arrays.asList(v[0], v[1], "설명", "BIGINT", "0", "0", "0", "Y", "", "9", "N", v[2], "N", "N", "N", "N", "N", "N");
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
        System.out.println("[M8-C4b-R1] " + label + " : A=" + result[0] + " B=" + result[1]);
    }

    // ------------------------------------------------------------------ 동시 실행 도구 (C4b 와 같은 방식)

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
            // 같은 연결에서 pg_stat_clear_snapshot() 뒤에 센다 (AES-D17 — A 의 트랜잭션 안에서는 pg_stat_activity 가 첫 조회 값으로 고정된다)
            if (ConcurrencyDiagnostics.lockWaitCount(jdbc, sql, postgres()) > 0) return;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("B 가 끝나지도, 잠금을 기다리지도 않았다"
                + ConcurrencyDiagnostics.describe("C4b-R1 B 대기", started, jdbc, dataSource, projectRepository));
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
