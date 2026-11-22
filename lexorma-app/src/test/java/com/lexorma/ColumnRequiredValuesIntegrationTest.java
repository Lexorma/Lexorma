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
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-U05 물리 컬럼 필수값 (V5, docs/completed/column-name-uniqueness-m7.md 10장) : 부모 테이블 없음 = TABLE_IS_NOT_DEFINED (기존), 컬럼명 NULL·빈 문자열·공백류만 =
 * 400 INVALID_PHYSICAL_MODEL (중복 오류 SAVED_DATA_EXISTS 가 아니다), 비영문 이름은 그대로 허용. 서비스를 거치지 않은 저장도 DB NOT NULL·CK_COL_01 이 막는다. H2·PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ColumnRequiredValuesIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ColumnManageService columns;
    @Autowired ColumnRenameService renames;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private record Fx(GenerationFixture f, long table, long term) {
    }

    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "crv-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer)));
        generation.apply(f.user, f.request(List.of(customer), plan.planToken()));
        return new Fx(f, f.table(customer), f.term("ETC_CD", "VARCHAR", 20));
    }

    private String insert(Fx x, Long table, String name) {
        ColumnInfoDto dto = new ColumnInfoDto();
        dto.setTableInfoId(table);
        dto.setTermId(x.term());
        dto.setColumnName(name);
        dto.setColumnDesc("설명");
        dto.setDataType("VARCHAR");
        dto.setMaxLength(20);
        for (String yn : List.of("N")) {
            dto.setIsPk(yn);
            dto.setIsMasterData(yn);
            dto.setIsRequired(yn);
            dto.setIsSensitive(yn);
            dto.setIsUnique(yn);
            dto.setIsIndex(yn);
            dto.setIsEncrypted(yn);
        }
        dto.setIsNullable("Y");
        try {
            columns.insertColumn(x.f().user, dto);
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        }
    }

    private int count(long table) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, table);
    }

    @Test
    @DisplayName("단건 등록 : 부모 없음·없는 테이블·다른 프로젝트 테이블 = TABLE_IS_NOT_DEFINED, 컬럼명 NULL·빈·공백·탭 = INVALID_PHYSICAL_MODEL (중복 오류 아님), 비영문·가운데 공백 이름은 저장")
    void insertRequired() {
        Fx x = fixture("insert");
        Fx other = fixture("other");
        int before = count(x.table());
        assertThat(insert(x, null, "ETC_CD")).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(insert(x, 999_999_999_999L, "ETC_CD")).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(insert(x, other.table(), "ETC_CD")).as("프로젝트 격리").isEqualTo("TABLE_IS_NOT_DEFINED");
        for (String blank : Arrays.asList(null, "", "   ", "\t", " \r\n ")) {
            assertThat(insert(x, x.table(), blank)).as(String.valueOf(blank)).isEqualTo("INVALID_PHYSICAL_MODEL");
        }
        assertThat(count(x.table())).isEqualTo(before);
        assertThat(insert(x, x.table(), "고객번호")).isEqualTo("OK");
        assertThat(insert(x, x.table(), "顧客 番号")).isEqualTo("OK");
        assertThat(insert(x, x.table(), "客户编号")).isEqualTo("OK");
        assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? and COL_NM_CMPR_VL in ('고객번호', '顧客 番号', '客户编号') order by COL_SN", String.class, x.table()))
                .containsExactly("고객번호", "顧客 番号", "客户编号");
        assertThat(insert(x, x.table(), " 고객번호")).as("중복은 여전히 중복 오류").isEqualTo("SAVED_DATA_EXISTS");
    }

    @Test
    @DisplayName("서비스를 거치지 않은 저장 : JPA 저장(이름 NULL·부모 NULL)과 직접 SQL(빈 이름) 모두 DB 가 거절한다 — 이름 충돌로 보지 않는다")
    void databaseRejectsMissingValues() {
        Fx x = fixture("db");
        TableInfo table = tx.execute(s -> entityManager.find(TableInfo.class, x.table()));
        assertThatThrownBy(() -> columnRepository.saveAndFlush(ColumnInfo.builder().projectId(x.f().user.getProjectId()).tableInfo(table).tableName("T").columnName(null).isApproval(false).build()))
                .isInstanceOf(DataIntegrityViolationException.class).satisfies(e -> assertThat(ColumnNameKey.isNameConflict(e)).isFalse());
        assertThatThrownBy(() -> columnRepository.saveAndFlush(ColumnInfo.builder().projectId(x.f().user.getProjectId()).tableInfo(null).tableName("T").columnName("ORPHAN").isApproval(false).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String blank : List.of("", "  ", "\t\n")) {
            assertThatThrownBy(() -> jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', ?, false)",
                    GenerationFixture.nextId(), x.f().user.getProjectId(), x.table(), blank)).as("[" + blank + "]")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(e -> assertThat(ColumnNameKey.isNameConflict(e)).isFalse());
        }
        assertThat(ColumnNameKey.isBlankName(" ")).as("NBSP 만은 빈 이름이 아니다 (DB 와 같은 기준)").isFalse();
        jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN) values (?, ?, ?, 'T', ?, false)", GenerationFixture.nextId(), x.f().user.getProjectId(), x.table(), " ");
    }

    @Test
    @DisplayName("일괄 저장 : 약어가 빈 용어로 만드는 새 행은 행 오류 (아무것도 저장 안 함) · 이름 변경 : 빈 이름은 INVALID_PHYSICAL_MODEL, 이름 그대로")
    void bulkAndRename() {
        Fx x = fixture("bulk");
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.table());
        long blankTerm = x.f().term("  ", "VARCHAR", 20);
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(x.table());
        ColumnBulkSaveDto.Row row = new ColumnBulkSaveDto.Row();
        row.setKey("r1");
        row.setTermId(blankTerm);
        row.setColumnDesc("설명");
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
        request.setRows(new ArrayList<>(List.of(row)));
        int before = count(x.table());
        ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(x.f().user, request);
        assertThat(result.result()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("영문약어"));
        assertThat(count(x.table())).isEqualTo(before);

        jdbc.update("update TB_TBL set APRV_YN = false where TBL_SN = ?", x.table());
        long column = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = 'CUST_NO'", Long.class, x.table());
        for (String blank : Arrays.asList(null, "", " \t ")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                ColumnInfo c = entityManager.find(ColumnInfo.class, column);
                renames.renameLockedColumn(c.getProjectId(), c, blank);
            })).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL));
        }
        assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, column)).isEqualTo("CUST_NO");
    }
}
