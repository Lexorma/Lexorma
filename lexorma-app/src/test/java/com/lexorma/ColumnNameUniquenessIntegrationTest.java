package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.column.service.ColumnNameKey;
import com.lexorma.data.column.service.ColumnRenameService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import jakarta.persistence.EntityManager;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 컬럼명 UNIQUE (M7-U01~U04, V5 — docs/completed/column-name-uniqueness-m7.md) : 같은 테이블 안 물리 컬럼명은 UPPER(TRIM(COL_NM)) 기준으로 하나.
 * 등록·중복 확인·일괄 저장·엑셀·이름 변경이 같은 기준으로 거절하고(기존 오류 계약 그대로), 저장된 이름은 입력 그대로이며, DB 생성 컬럼 + UX_COL_01 이 최종 방어한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ColumnNameUniquenessIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ColumnManageService columns;
    @Autowired ColumnRenameService renames;
    @Autowired PhysicalNameRenameService physicalRename;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 고객(CUST : CUST_NO PK, CUST_NM — 둘 다 속성 매핑) · 주문(ORD : ORD_NO PK) */
    private record Fx(GenerationFixture f, long cust, long ord, long custNm, long custNmTerm) {
        long project() {
            return f.user.getProjectId();
        }
    }

    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "cnu-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        f.attr(customer, "고객 명", false, "CUST_NM");
        long order = f.entity("주문");
        f.pk(order, f.attr(order, "주문 번호", true, "ORD_NO"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer, order)));
        generation.apply(f.user, f.request(List.of(customer, order), plan.planToken()));
        long cust = f.table(customer);
        long custNm = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = 'CUST_NM'", Long.class, cust);
        long term = jdbc.queryForObject("select STD_TRM_SN from TB_COL where COL_SN = ?", Long.class, custNm);
        return new Fx(f, cust, f.table(order), custNm, term);
    }

    private void insert(Fx x, long table, String name, long term) {
        ColumnInfoDto dto = new ColumnInfoDto();
        dto.setTableInfoId(table);
        dto.setTermId(term);
        dto.setColumnName(name);
        dto.setColumnDesc("설명 " + name);
        dto.setDataType("VARCHAR");
        dto.setMaxLength(20);
        dto.setIsPk("N");
        dto.setIsNullable("Y");
        dto.setIsMasterData("N");
        dto.setIsRequired("N");
        dto.setIsSensitive("N");
        dto.setIsUnique("N");
        dto.setIsIndex("N");
        dto.setIsEncrypted("N");
        columns.insertColumn(x.f().user, dto);
    }

    /** 승인 전 컬럼으로 등록 (관리자 등록은 승인으로 저장된다 — 삭제·이름 변경은 승인 전 컬럼만 된다) */
    private void insertUnapproved(Fx x, long table, String name, long term) {
        insert(x, table, name, term);
        jdbc.update("update TB_COL set APRV_YN = false where TBL_SN = ? and COL_NM = ?", table, name);
    }

    private String insertResult(Fx x, long table, String name, long term) {
        try {
            insert(x, table, name, term);
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        }
    }

    private List<String> names(long table) {
        return jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? order by COL_SN", String.class, table);
    }

    private String key(long column) {
        return jdbc.queryForObject("select COL_NM_CMPR_VL from TB_COL where COL_SN = ?", String.class, column);
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ------------------------------------------------------------------ 등록 · 중복 확인

    @Test
    @DisplayName("단건 등록 : 정확히 같은 이름·대소문자만 다른 이름·앞뒤 공백만 다른 이름은 400 SAVED_DATA_EXISTS, 다른 이름·다른 테이블의 같은 이름은 저장 — 저장 이름은 입력 그대로")
    void insertRules() {
        Fx x = fixture("insert");
        List<String> before = names(x.cust());
        assertThat(insertResult(x, x.cust(), "CUST_NM", x.custNmTerm())).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(insertResult(x, x.cust(), "cust_nm", x.custNmTerm())).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(insertResult(x, x.cust(), "Cust_Nm", x.custNmTerm())).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(insertResult(x, x.cust(), "  CUST_NM ", x.custNmTerm())).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(names(x.cust())).as("거절된 등록은 아무것도 남기지 않는다").isEqualTo(before);

        long addr = x.f().term("CUST_ADDR", "VARCHAR", 20);
        assertThat(insertResult(x, x.cust(), "cust_addr", addr)).isEqualTo("OK");
        assertThat(insertResult(x, x.ord(), " cust_nm", x.custNmTerm())).as("다른 테이블의 같은 이름").isEqualTo("OK");
        // 원래 이름 보존 (대문자로 바꾸지 않는다) · 생성 컬럼은 DB 가 계산
        long lower = column(x.cust(), "cust_addr");
        assertThat(key(lower)).isEqualTo("CUST_ADDR");
        long spaced = column(x.ord(), " cust_nm");
        assertThat(key(spaced)).isEqualTo("CUST_NM");
        assertThat(key(x.custNm())).isEqualTo("CUST_NM");

        // 화면의 중복 확인도 같은 기준 (다른 테이블은 보지 않는다)
        assertThat(columns.selectDuplicateColumns(x.f().user, x.cust(), " Cust_Addr ")).hasSize(1);
        assertThat(columns.selectDuplicateColumns(x.f().user, x.cust(), "CUST_ADDR2")).isEmpty();
        // 프로젝트 격리 : 다른 프로젝트 ID 로는 찾지 않는다
        assertThat(columnRepository.findSameName(x.project() + 1_000_000, x.cust(), "CUST_ADDR")).isEmpty();
    }

    // ------------------------------------------------------------------ DB 최종 방어

    @Test
    @DisplayName("DB 생성 컬럼 + UX_COL_01 : 직접 SQL 로도 같은 테이블의 같은 비교 이름은 들어가지 않고(서비스를 거치지 않아도), 이름을 바꾸면 비교 값도 바뀐다. FK·다른 오류는 이름 충돌로 보지 않는다")
    void databaseGuard() {
        Fx x = fixture("db");
        long id = GenerationFixture.nextId();
        for (String name : List.of("CUST_NM", "cust_nm", " CUST_NM ")) {
            assertThatThrownBy(() -> jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', ?, false)", id, x.project(), x.cust(), name))
                    .as(name).isInstanceOf(DataAccessException.class).satisfies(e -> assertThat(ColumnNameKey.isNameConflict(e)).isTrue());
        }
        // 생성 컬럼에 직접 쓸 수 없다
        assertThatThrownBy(() -> jdbc.update("update TB_COL set COL_NM_CMPR_VL = 'X' where COL_SN = ?", x.custNm())).isInstanceOf(DataAccessException.class);
        // 다른 테이블 · 같은 테이블의 다른 이름은 된다
        jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', 'cust_nm', false)", id, x.project(), x.ord());
        assertThat(key(id)).isEqualTo("CUST_NM");
        // 이름을 바꾸면 비교 값이 따라 바뀐다 (대소문자만 바꾸는 것도 된다 — 자기 자신과는 겹치지 않는다)
        jdbc.update("update TB_COL set COL_NM = 'Cust_Nm' where COL_SN = ?", id);
        assertThat(key(id)).isEqualTo("CUST_NM");
        jdbc.update("update TB_COL set COL_NM = 'memo_txt' where COL_SN = ?", id);
        assertThat(key(id)).isEqualTo("MEMO_TXT");
        // NULL 이름·부모 없는 컬럼은 V5 의 NOT NULL 로 막힌다 (M7-U05 — ColumnRequiredValuesIntegrationTest) : 이름 충돌로 보지 않는다
        assertThatThrownBy(() -> jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', null, false)", GenerationFixture.nextId(), x.project(), x.cust()))
                .isInstanceOf(DataAccessException.class).satisfies(e -> assertThat(ColumnNameKey.isNameConflict(e)).isFalse());
        // FK 위반은 이름 충돌이 아니다
        assertThatThrownBy(() -> jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', 'CUST_NM', false)",
                GenerationFixture.nextId(), x.project(), 999_999_999_999L)).isInstanceOf(DataAccessException.class).satisfies(e -> assertThat(ColumnNameKey.isNameConflict(e)).isFalse());
        // 최대 길이 경계 : 255자 이름은 들어가고 비교 값도 255자
        String longName = "A".repeat(254) + "b";
        long longId = GenerationFixture.nextId();
        jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', ?, false)", longId, x.project(), x.ord(), longName);
        assertThat(key(longId)).isEqualTo("A".repeat(254) + "B").isEqualTo(ColumnNameKey.of(longName));
    }

    /** DB 가 계산한 비교 값 : 표(ORD)에 직접 넣어 생성 컬럼을 읽고 지운다 */
    private String dbKey(Fx x, String name) {
        long id = GenerationFixture.nextId();
        jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', ?, false)", id, x.project(), x.ord(), name);
        try {
            return key(id);
        } finally {
            jdbc.update("delete from TB_COL where COL_SN = ?", id);
        }
    }

    @Test
    @DisplayName("M7-U06 B3 정규화 : Java(ColumnNameKey) = DB 생성 컬럼 (앞뒤 U+0020 만 제거 + 로캘 중립 확장 대문자). 확정 표본은 같음/다름이 기대대로이고 DB UNIQUE 도 같은 판정, 저장 이름은 그대로")
    void normalizationMatches() {
        Fx x = fixture("norm");
        // 26개 표본 (ASCII · 독일어 · 합자 · 그리스어 · 터키어 · 악센트 · 한중일 · 전각 · 결합 문자 · 보조 평면 · 공백류)
        List<String> samples = List.of("i", "I", "customer_id", "ß", "ẞ", "SS", "STRAßE", "ﬁ", "ﬃ", "σ", "ς", "Σ", "\u0390", "ᾳ", "İ", "ı", "é", "É", "e\u0301",
                "고객번호", "顧客番号", "客户编号", "ＡＢＣ", "ａｂｃ", "\uD801\uDC28", "  Cust_No  ", "\tab\t", "\u00A0ab", "\u3000ab", "ǆ", "\u212A", "ſ");
        for (String name : samples) assertThat(dbKey(x, name)).as(name).isEqualTo(ColumnNameKey.of(name));
        assertThat(ColumnNameKey.of("straße")).isEqualTo("STRASSE");
        assertThat(ColumnNameKey.of("\u0390")).isEqualTo("\u0399\u0308\u0301");
        assertThat(ColumnNameKey.of("ᾳ")).isEqualTo("ΑΙ");
        assertThat(ColumnNameKey.of("  Cust_No  ")).isEqualTo("CUST_NO");
        assertThat(ColumnNameKey.of("\tab\t")).as("탭은 지우지 않는다").isEqualTo("\tAB\t");
        // 확정 비교 의미 (같음 true / 다름 false) — Case Folding · NFC · NFKC 가 아니다 (é ≠ e+U+0301, ＡＢＣ ≠ ABC, K(켈빈) ≠ K, ẞ ≠ SS)
        Object[][] pairs = {{"CUSTOMER_ID", "customer_id", true}, {"Customer_Id", "CUSTOMER_ID", true}, {" CUST_NO ", "CUST_NO", true}, {"STRAßE", "STRASSE", true},
                {"ß", "SS", true}, {"ﬁ", "FI", true}, {"é", "É", true}, {"σ", "Σ", true}, {"ς", "Σ", true}, {"고객번호", "고객번호", true}, {"고객번호", "주문번호", false},
                {"CUST_NO", "CUSTNO", false}, {"i", "I", true}, {"ı", "I", true}, {"İ", "I", false}, {"ẞ", "SS", false}, {"é", "e\u0301", false}, {"ａｂｃ", "ＡＢＣ", true},
                {"ＡＢＣ", "ABC", false}, {"\u212A", "K", false}, {"ſ", "S", true}, {"\u0390", "\u0399\u0308\u0301", true}, {"\u00A0A", "A", false}, {"\tA", "A", false}};
        for (Object[] pair : pairs) {
            String a = (String) pair[0];
            String b = (String) pair[1];
            boolean same = (Boolean) pair[2];
            assertThat(ColumnNameKey.same(a, b)).as(a + " / " + b).isEqualTo(same);
            long first = GenerationFixture.nextId();
            jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', ?, false)", first, x.project(), x.ord(), a);
            try {
                long second = GenerationFixture.nextId();
                if (same) {
                    assertThatThrownBy(() -> jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', ?, false)", second, x.project(), x.ord(), b))
                            .as("DB 도 같은 이름 : " + a + " / " + b).isInstanceOf(DataAccessException.class).satisfies(e -> assertThat(ColumnNameKey.isNameConflict(e)).isTrue());
                } else {
                    jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', ?, false)", second, x.project(), x.ord(), b);
                    assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, second)).as("저장 이름 그대로").isEqualTo(b);
                    jdbc.update("delete from TB_COL where COL_SN = ?", second);
                }
                assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, first)).isEqualTo(a);
            } finally {
                jdbc.update("delete from TB_COL where COL_SN = ?", first);
            }
        }
    }

    @Test
    @DisplayName("M7-U06 : 확장 대문자로만 같은 이름 — 단건 등록·중복 확인 API 는 400 SAVED_DATA_EXISTS·중복, 이름 변경도 거절, 다른 테이블은 허용. 저장 이름은 입력 그대로")
    void unicodeInsertRenameRules() {
        Fx x = fixture("uni");
        insert(x, x.cust(), "STRAßE", x.custNmTerm());
        assertThat(names(x.cust())).contains("STRAßE");
        assertThat(key(column(x.cust(), "STRAßE"))).isEqualTo("STRASSE");
        for (String dup : List.of("STRASSE", "strasse", " Straße ", "STRAßE"))
            assertThat(insertResult(x, x.cust(), dup, x.custNmTerm())).as(dup).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(insertResult(x, x.cust(), "STRAẞE", x.custNmTerm())).as("ẞ(U+1E9E) 의 대문자는 그대로라 SS 가 아니다").isEqualTo("OK");
        assertThat(columns.selectDuplicateColumns(x.f().user, x.cust(), "strasse")).hasSize(1);
        assertThat(columns.selectDuplicateColumns(x.f().user, x.cust(), "STRAS")).isEmpty();
        assertThat(insertResult(x, x.ord(), "strasse", x.custNmTerm())).as("다른 테이블").isEqualTo("OK");
        insert(x, x.cust(), "ﬁ_NM", x.custNmTerm());
        assertThat(insertResult(x, x.cust(), "fi_nm", x.custNmTerm())).isEqualTo("SAVED_DATA_EXISTS");
        insert(x, x.cust(), "σ_CD", x.custNmTerm());
        assertThat(insertResult(x, x.cust(), "ς_cd", x.custNmTerm())).isEqualTo("SAVED_DATA_EXISTS");
        insert(x, x.cust(), "고객번호", x.custNmTerm());
        assertThat(insertResult(x, x.cust(), "고객번호", x.custNmTerm())).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(insertResult(x, x.cust(), "주문번호", x.custNmTerm())).isEqualTo("OK");
        // 이름 변경 : 다른 컬럼과 확장 대문자로만 같으면 거절 (자기 이름의 대소문자 변경은 된다)
        jdbc.update("update TB_COL set APRV_YN = false where TBL_SN = ?", x.cust());
        long fi = column(x.cust(), "ﬁ_NM");
        assertThat(rename(fi, "Straße")).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(rename(fi, "strasse ")).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(rename(fi, "FI_NM")).as("자기 자신 (ﬁ_NM → FI_NM 은 비교 값이 같다)").isEqualTo("OK");
        assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, fi)).isEqualTo("FI_NM");
    }

    // ------------------------------------------------------------------ 일괄 저장

    private ColumnBulkSaveDto.Row newRow(String key, long term) {
        ColumnBulkSaveDto.Row row = new ColumnBulkSaveDto.Row();
        row.setKey(key);
        row.setTermId(term);
        row.setColumnDesc("설명 " + key);
        row.setDataType("VARCHAR");
        row.setMaxLength(20);
        row.setIsPk("N");
        row.setIsNullable("Y");
        row.setIsRequired("N");
        row.setIsUnique("N");
        row.setIsIndex("N");
        row.setIsMasterData("N");
        row.setIsSensitive("N");
        row.setIsEncrypted("N");
        return row;
    }

    private ColumnBulkSaveDto bulk(long table, List<Long> deleted, ColumnBulkSaveDto.Row... rows) {
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(table);
        request.setDeletedIds(new ArrayList<>(deleted));
        request.setRows(new ArrayList<>(List.of(rows)));
        return request;
    }

    @Test
    @DisplayName("일괄 저장 : 기존 컬럼과 대소문자만 다른 새 행·새 행끼리의 중복은 행 오류(아무것도 저장 안 함). X 삭제 + X(대소문자 달라도) 추가는 한 번에 된다. 매핑된 컬럼 삭제는 여전히 막혀 재등록으로 우회되지 않는다")
    void bulkRules() {
        Fx x = fixture("bulk");
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.cust());
        long lowerCustNm = x.f().term("cust_nm", "VARCHAR", 20);
        ColumnBulkSaveDto.Result dupExisting = columns.saveColumnsBulk(x.f().user, bulk(x.cust(), List.of(), newRow("r1", lowerCustNm)));
        assertThat(dupExisting.result()).isFalse();
        assertThat(dupExisting.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("cust_nm"));

        long etc = x.f().term("ETC_CD", "VARCHAR", 20);
        long etcLower = x.f().term(" etc_cd", "VARCHAR", 20);
        ColumnBulkSaveDto.Result dupRows = columns.saveColumnsBulk(x.f().user, bulk(x.cust(), List.of(), newRow("r1", etc), newRow("r2", etcLower)));
        assertThat(dupRows.result()).isFalse();
        assertThat(dupRows.errors()).extracting(ColumnBulkSaveDto.RowError::key).containsExactly("r2");
        List<String> before = names(x.cust());
        assertThat(before).doesNotContain("ETC_CD");

        // X 삭제 + X 추가 (같은 저장) : 삭제를 먼저 내보내 UNIQUE 에 걸리지 않는다
        insertUnapproved(x, x.cust(), "ETC_CD", etc);
        long old = column(x.cust(), "ETC_CD");
        ColumnBulkSaveDto.Result readd = columns.saveColumnsBulk(x.f().user, bulk(x.cust(), List.of(old), newRow("r1", etcLower)));
        assertThat(readd.result()).as(String.valueOf(readd.errors())).isTrue();
        assertThat(readd.deleted()).isOne();
        assertThat(readd.created()).isOne();
        assertThat(count("select count(*) from TB_COL where COL_SN = ?", old)).isZero();
        assertThat(count("select count(*) from TB_COL where TBL_SN = ? and COL_NM_CMPR_VL = 'ETC_CD'", x.cust())).isOne();
        assertThat(names(x.cust())).contains(" etc_cd");

        // 매핑된 CUST_NM 삭제 + CUST_NM 추가 : 삭제 정책(매핑)이 먼저 막는다 — 아무것도 바뀌지 않는다
        List<String> mapped = names(x.cust());
        assertThatThrownBy(() -> columns.saveColumnsBulk(x.f().user, bulk(x.cust(), List.of(x.custNm()), newRow("r1", lowerCustNm))))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RELATION_EXISTS));
        assertThat(names(x.cust())).isEqualTo(mapped);
        assertThat(count("select count(*) from TB_ATRB_COL_MPNG where COL_SN = ?", x.custNm())).isOne();
    }

    @Test
    @DisplayName("일괄 저장 (M7-U06) : STRASSE·STRAßE 는 요청 안 중복으로 행 오류(아무것도 저장 안 함). 애플리케이션이 놓쳐 DB UNIQUE 에 걸리면 같은 중복 오류(400 SAVED_DATA_EXISTS)이고 삭제까지 전체 롤백 — "
            + "PostgreSQL ICU 가 Java 21 보다 새 대소문자 짝을 아는 글자(ƛ → Ƛ, Unicode 16.0)로 재현")
    void bulkRollbackOnDatabaseConflict() {
        Fx x = fixture("bulk-rb");
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.cust());
        long etc = x.f().term("ETC_CD", "VARCHAR", 20);
        insertUnapproved(x, x.cust(), "ETC_CD", etc);
        long old = column(x.cust(), "ETC_CD");
        long strasse = x.f().term("STRASSE", "VARCHAR", 20);
        long strasseSharp = x.f().term("STRAßE", "VARCHAR", 20);
        List<String> before = names(x.cust());
        ColumnBulkSaveDto.Result rows = columns.saveColumnsBulk(x.f().user, bulk(x.cust(), List.of(old), newRow("r1", etc), newRow("r2", strasse), newRow("r3", strasseSharp)));
        assertThat(rows.result()).isFalse();
        assertThat(rows.errors()).extracting(ColumnBulkSaveDto.RowError::key).containsExactly("r3");
        assertThat(names(x.cust())).isEqualTo(before);

        String lambda = "\u019B_CD";       // ƛ : Java 21 (Unicode 15.0) 은 대문자가 없다
        String lambdaUpper = "\uA7DC_CD";  // Ƛ : Unicode 16.0 에 생긴 대문자
        boolean icuKnows = dbKey(x, lambda).equals(dbKey(x, lambdaUpper));
        assertThat(ColumnNameKey.same(lambda, lambdaUpper)).isFalse();
        org.junit.jupiter.api.Assumptions.assumeTrue(icuKnows, "이 PostgreSQL 의 ICU 가 Unicode 16.0 보다 오래돼 재현할 수 없다");
        long l1 = x.f().term(lambda, "VARCHAR", 20);
        long l2 = x.f().term(lambdaUpper, "VARCHAR", 20);
        ColumnBulkSaveDto request = bulk(x.cust(), List.of(old), newRow("r1", etc), newRow("r2", l1), newRow("r3", l2));
        assertThatThrownBy(() -> columns.saveColumnsBulk(x.f().user, request))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SAVED_DATA_EXISTS));
        assertThat(names(x.cust())).as("삭제도 롤백").isEqualTo(before);
        assertThat(count("select count(*) from TB_COL where COL_SN = ?", old)).isOne();
    }

    // ------------------------------------------------------------------ 엑셀

    private String upload(Fx x, String[]... rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            int r = 1;
            for (String[] values : rows) {
                List<String> cells = List.of(values[0], values[1], "설명 " + values[1], "VARCHAR", "20", "0", "0", "Y", "", "9", "N", "", "N", "N", "N", "N", "N", "N");
                Row row = sheet.createRow(r++);
                for (int c = 0; c < cells.size(); c++) row.createCell(c).setCellValue(cells.get(c));
            }
            workbook.write(out);
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()),
                    x.f().user);
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("엑셀 업로드 : 파일 안 대소문자·공백만 다른 중복은 COLUMN_DUPLICATE_IN_FILE, 기존 컬럼과 대소문자만 다르면 SAVED_DATA_EXISTS (업로드 전체 취소), 다른 테이블의 같은 이름은 저장")
    void excelRules() {
        Fx x = fixture("excel");
        String cust = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.cust());
        String ord = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.ord());
        x.f().term("CUST_ADDR", "VARCHAR", 20);
        List<String> before = names(x.cust());
        assertThat(upload(x, new String[]{cust, "CUST_ADDR"}, new String[]{cust, "cust_addr"})).isEqualTo("COLUMN_DUPLICATE_IN_FILE");
        assertThat(upload(x, new String[]{ord, "CUST_ADDR"}, new String[]{cust, "cust_nm"})).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(names(x.cust())).isEqualTo(before);
        assertThat(names(x.ord())).doesNotContain("CUST_ADDR");
        assertThat(upload(x, new String[]{cust, "cust_addr"}, new String[]{ord, "CUST_ADDR"})).isEqualTo("OK");
        assertThat(names(x.cust())).contains("cust_addr");
        assertThat(names(x.ord())).contains("CUST_ADDR");
        // M7-U06 : 확장 대문자로만 같은 이름 — 파일 안 (STRAßE_X · STRASSE_X), 기존 컬럼과 (ﬁ_X ↔ FI_X), 다른 테이블은 허용
        x.f().term("STRAßE_X", "VARCHAR", 20);
        x.f().term("ﬁ_X", "VARCHAR", 20);
        x.f().term("STRASSE_X", "VARCHAR", 20); // 엑셀은 컬럼명(대문자) = 용어 약어로 용어를 찾는다
        x.f().term("FI_X", "VARCHAR", 20);
        List<String> beforeUnicode = names(x.cust());
        assertThat(upload(x, new String[]{cust, "STRAßE_X"}, new String[]{cust, "strasse_x"})).isEqualTo("COLUMN_DUPLICATE_IN_FILE");
        assertThat(upload(x, new String[]{cust, "ﬁ_X"})).isEqualTo("OK");
        assertThat(upload(x, new String[]{ord, "ﬁ_X"}, new String[]{cust, "FI_X"})).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(names(x.ord())).as("업로드 전체 취소").doesNotContain("ﬁ_X");
        assertThat(upload(x, new String[]{cust, "STRAßE_X"}, new String[]{ord, "STRASSE_X"})).isEqualTo("OK");
        assertThat(names(x.cust())).containsAll(beforeUnicode).contains("ﬁ_X", "STRAßE_X");
        assertThat(names(x.ord())).contains("STRASSE_X");
    }

    // ------------------------------------------------------------------ 이름 변경

    private String rename(long columnId, String newName) {
        try {
            tx.executeWithoutResult(status -> {
                ColumnInfo column = entityManager.find(ColumnInfo.class, columnId);
                renames.renameLockedColumn(column.getProjectId(), column, newName);
                entityManager.flush();
            });
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        }
    }

    @Test
    @DisplayName("이름 변경(물리명 일괄 적용의 쓰기 경로) : 다른 컬럼의 대소문자만 다른 이름은 SAVED_DATA_EXISTS, 자기 이름의 대소문자만 바꾸는 것은 된다, 맞바꾸기(A ↔ B)는 예전처럼 거절")
    void renameRules() {
        Fx x = fixture("rename");
        long a = x.f().term("AAA_CD", "VARCHAR", 20);
        insertUnapproved(x, x.cust(), "aaa_cd", a);
        insertUnapproved(x, x.cust(), "BBB_CD", a);
        long aaa = column(x.cust(), "aaa_cd");
        long bbb = column(x.cust(), "BBB_CD");
        assertThat(rename(aaa, "AAA_CD")).as("자기 자신의 대소문자만").isEqualTo("OK");
        assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, aaa)).isEqualTo("AAA_CD");
        assertThat(key(aaa)).isEqualTo("AAA_CD");
        assertThat(rename(bbb, " aaa_cd ")).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(rename(aaa, "BBB_CD")).as("맞바꾸기의 첫 단계 — 대상 이름을 지금 다른 컬럼이 쓴다").isEqualTo("SAVED_DATA_EXISTS");
        assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, bbb)).isEqualTo("BBB_CD");
        assertThat(rename(bbb, "CCC_CD")).isEqualTo("OK");
        assertThat(key(bbb)).isEqualTo("CCC_CD");
    }

    @Test
    @DisplayName("M7-U06 : 논리 → 물리 생성은 확장 대문자로 같은 이름이 되는 두 속성을 막고, 물리명 일괄 변경 미리보기는 다른 컬럼과 확장 대문자로만 같은 추천명을 NAME_CONFLICT 로 막아 적용도 거절된다")
    void unicodeGenerationAndPhysicalRename() {
        GenerationFixture f = GenerationFixture.create(context, "cnu-gen-" + System.nanoTime(), DbType.POSTGRESQL);
        long entity = f.entity("거리");
        f.pk(entity, f.attr(entity, "거리 번호", true, "STREET_NO"));
        f.attr(entity, "거리 명", false, "STRAßE_NM");
        f.attr(entity, "거리 별칭", false, "strasse_nm");
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(entity)));
        assertThat(plan.tables()).singleElement().satisfies(t -> assertThat(t.errors()).anyMatch(e -> e.contains("STRASSE_NM")));
        assertThat(plan.creatable()).isFalse();

        Fx x = fixture("uni-rename");
        long classTerm = x.f().term("CLASS_CD", "VARCHAR", 20);
        insertUnapproved(x, x.cust(), "OLD_CLASS_CD", classTerm);
        long target = column(x.cust(), "OLD_CLASS_CD");
        long attribute = jdbc.queryForObject("select a.LGCL_ATRB_SN from TB_LGCL_ATRB a join TB_ATRB_COL_MPNG m on m.LGCL_ATRB_SN = a.LGCL_ATRB_SN where m.COL_SN = ?", Long.class, x.custNm());
        jdbc.update("update TB_LGCL_ATRB set STD_TRM_SN = ? where LGCL_ATRB_SN = ?", classTerm, attribute);
        jdbc.update("update TB_ATRB_COL_MPNG set COL_SN = ? where COL_SN = ?", target, x.custNm());
        insertUnapproved(x, x.cust(), "claß_cd", x.custNmTerm());
        PhysicalNameRenameDto.Plan preview = physicalRename.preview(x.f().user, new PhysicalNameRenameDto.Request(
                List.of(new PhysicalNameRenameDto.Item(PhysicalNameRenameDto.TargetType.COLUMN, target, "CLASS_CD", null)), null));
        assertThat(preview.items()).singleElement().satisfies(i -> assertThat(i.applicability().blockers()).extracting(ApplyIssue::code).contains(ApplyCode.NAME_CONFLICT));
        PhysicalNameRenameDto.PlanItem item = preview.items().get(0);
        assertThatThrownBy(() -> physicalRename.apply(x.f().user, new PhysicalNameRenameDto.Request(
                List.of(new PhysicalNameRenameDto.Item(PhysicalNameRenameDto.TargetType.COLUMN, target, "CLASS_CD", item.modifiedAt())), preview.planToken())))
                .isInstanceOf(CustomException.class);
        assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, target)).isEqualTo("OLD_CLASS_CD");
    }
}
