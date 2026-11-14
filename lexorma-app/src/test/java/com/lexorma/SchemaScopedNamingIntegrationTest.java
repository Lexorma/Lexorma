package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.ddl.domain.dto.DdlExportDto;
import com.lexorma.data.ddl.service.DdlExportService;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.view.domain.dto.ViewDto;
import com.lexorma.data.view.service.PhysicalViewService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스키마 단위 TABLE·VIEW 식별 (2026-11-13, D-25 변경) : 내부 ID(TBL_SN·VIEW_SN)는 그대로, 이름은 스키마 안에서 하나 (UX_TBL_01·UX_VIEW_01 + 같은 스키마의 TABLE·VIEW 교차 검사),
 * 이름 기반 참조(참조 테이블명·컬럼 엑셀의 테이블명)의 스키마 해석과 모호성, 이름 변경·이동·스키마명 변경 뒤에도 같은 테이블을 가리킴,
 * 스키마별 같은 이름 테이블의 인덱스·DDL·통합 Export, 스키마 단위 잠금의 동시성. H2 와 PostgreSQL(전체 테스트를 PostgreSQL 로 돌릴 때) 같은 기대값이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SchemaScopedNamingIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnManageService columnManageService;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired TableRelationRepository relationRepository;
    @Autowired PhysicalViewService viewService;
    @Autowired DdlExportService exportService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("TABLE 이름 : 같은 스키마는 중복 금지, 다른 스키마·다른 프로젝트는 허용, 다른 프로젝트 스키마 지정 금지, 이름 변경·이동은 옮길 스키마에서 확인, DB 의 UX_TBL_01 도 막는다")
    void tableNamesAreUniquePerSchema() {
        AccountDto admin = loginAsAdmin("sch-table");
        long projectId = admin.getProjectId();
        words(projectId);
        Long archive = schema(admin, "ARCHIVE");
        Long main = defaultSchema(projectId);

        TableInfo user = saveTable(admin, null, "회원");
        TableInfo archived = saveTable(admin, archive, "회원");
        assertThat(user.getTableName()).isEqualTo("MBR").isEqualTo(archived.getTableName());
        assertThat(user.getSchema().getId()).isEqualTo(main);
        assertThat(archived.getSchema().getId()).isEqualTo(archive);
        assertInvalid(() -> saveTable(admin, null, "회원"), ErrorCode.SAVED_DATA_EXISTS);
        assertInvalid(() -> saveTable(admin, archive, "회원"), ErrorCode.SAVED_DATA_EXISTS);

        // 다른 프로젝트 : 같은 이름 가능, 이 프로젝트의 스키마는 지정할 수 없다
        AccountDto other = loginAsAdmin("sch-table-other");
        words(other.getProjectId());
        assertThat(saveTable(other, null, "회원").getTableName()).isEqualTo("MBR");
        assertInvalid(() -> saveTable(other, archive, "코드"), ErrorCode.SCHEMA_IS_NOT_DEFINED);

        // 이름 변경 : 같은 스키마에 그 이름이 있으면 안 되고, 다른 스키마에만 있으면 된다
        login(projectId, admin.getId());
        TableInfo code = saveTable(admin, null, "코드");
        assertInvalid(() -> update(admin, code, "MBR", null), ErrorCode.SAVED_DATA_EXISTS);
        TableInfo archivedCode = saveTable(admin, archive, "코드");
        update(admin, archivedCode, "CD_OLD", null);
        // 이동 : 옮길 스키마에 같은 이름이 있으면 안 된다 (ARCHIVE.MBR → 기본 스키마에는 MBR 이 있다)
        assertInvalid(() -> update(admin, archived, "MBR", main), ErrorCode.SAVED_DATA_EXISTS);
        update(admin, archivedCode, "CD_OLD", main);
        assertThat(tableInfoRepository.findById(archivedCode.getId()).orElseThrow().getSchema().getId()).isEqualTo(main);

        // DB 의 UNIQUE (스키마 + 이름) : 서비스를 거치지 않아도 같은 스키마에 같은 이름은 들어가지 않는다
        long freeId = jdbc.queryForObject("select coalesce(max(TBL_SN), 0) + 1000 from TB_TBL", Long.class); // 같은 검증 DB 를 다시 써도 겹치지 않게
        assertThatThrownBy(() -> jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values(?, ?, ?, 'MBR', true)", freeId, projectId, archive))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values(?, ?, ?, 'MBR_OK', true)", freeId + 1, projectId, archive);
    }

    @Test
    @DisplayName("참조 테이블명 : 같은 스키마를 먼저, 없으면 프로젝트에서 하나뿐일 때, 여러 스키마에 있으면 스키마명.테이블명 을 요구하고, 확정된 관계는 나중에 모호해져도 유지된다")
    void referenceNamesResolvePerSchema() throws Exception {
        AccountDto admin = loginAsAdmin("sch-ref");
        long projectId = admin.getProjectId();
        words(projectId);
        Long archive = schema(admin, "ARCHIVE");
        Long sales = schema(admin, "SALES");
        StandardTerm id = term(projectId, "아이디", "ID");
        StandardTerm memberId = term(projectId, "회원_아이디", "MBR_ID");
        StandardTerm codeId = term(projectId, "코드_아이디", "CD_ID");

        TableInfo user = saveTable(admin, null, "회원");
        TableInfo archivedUser = saveTable(admin, archive, "회원");
        column(admin, user, id, null, true);
        column(admin, archivedUser, id, null, true);

        // 1) 같은 스키마 : 기본 스키마의 주문 → MBR = 기본 스키마의 MBR, ARCHIVE 의 이력 → MBR = ARCHIVE.MBR
        TableInfo order = saveTable(admin, null, "주문");
        TableInfo history = saveTable(admin, archive, "이력");
        column(admin, order, memberId, "mbr", false);
        column(admin, history, memberId, "MBR", false);
        assertThat(refTarget(order)).isEqualTo(user.getId());
        assertThat(refTarget(history)).isEqualTo(archivedUser.getId());
        assertThat(ddl(admin, order)).contains("REFERENCES MBR (ID);");
        assertThat(ddl(admin, history)).contains("REFERENCES ARCHIVE.MBR (ID);");

        // 2) 모호 : SALES 에는 MBR 이 없고 두 스키마에 있다 → 등록 거절, 스키마명.테이블명이면 된다
        TableInfo salesOrder = saveTable(admin, sales, "주문");
        assertThatThrownBy(() -> column(admin, salesOrder, memberId, "MBR", false)).isInstanceOf(CustomException.class)
                .hasMessageContaining("여러 스키마에 있습니다");
        column(admin, salesOrder, memberId, "archive.mbr", false);
        assertThat(refTarget(salesOrder)).isEqualTo(archivedUser.getId());
        assertThat(ddl(admin, salesOrder)).contains("REFERENCES ARCHIVE.MBR (ID);");

        // 3) 하나뿐인 이름은 다른 스키마에서도 이름만으로 (예전 호환), 나중에 같은 이름이 생겨 모호해져도 확정된 관계는 그대로
        TableInfo code = saveTable(admin, null, "코드");
        column(admin, code, id, null, true);
        column(admin, salesOrder, codeId, "CD", false);
        Long codeRelation = relationTo(code.getId());
        assertThat(codeRelation).isNotNull();
        TableInfo archivedCode = saveTable(admin, archive, "코드");
        column(admin, archivedCode, id, null, true); // 관계 동기화가 다시 돈다
        assertThat(relationTo(code.getId())).as("모호해져도 이름으로 다시 추론해 바꾸지 않는다").isEqualTo(codeRelation);
        assertThat(ddl(admin, salesOrder)).contains("REFERENCES CD (ID);").doesNotContain("REFERENCES ARCHIVE.CD");
        // 확정 관계가 없는 새 컬럼은 모호한 이름으로 등록할 수 없다 (SALES 에는 CD 가 없고 두 스키마에 있다). 같은 스키마에 있으면 그것이다
        StandardTerm codeId2 = term(projectId, "코드_아이디2", "CD_ID2");
        TableInfo salesHistory = saveTable(admin, sales, "이력");
        assertThatThrownBy(() -> column(admin, salesHistory, codeId2, "CD", false)).hasMessageContaining("여러 스키마에 있습니다");
        column(admin, history, codeId2, "CD", false);
        assertThat(inTx(() -> relationRepository.findAllByProjectIdAndSourceTable_Id(projectId, history.getId()).stream()
                .filter(r -> "FK_HIST_CD_ID2".equals(r.getRelationName())).map(r -> r.getTargetTable().getId()).findFirst().orElse(null))).isEqualTo(archivedCode.getId());

        // 4) 이름 변경·이동·스키마명 변경 : 이 테이블을 가리키던 참조 테이블명이 함께 바뀌어 같은 테이블을 계속 가리킨다
        update(admin, archivedUser, "MBR_HIST", null);
        assertThat(columnInfoRepository.findById(columnOf(salesOrder, "MBR_ID")).orElseThrow().getRefTableName()).isEqualTo("ARCHIVE.MBR_HIST");
        assertThat(columnInfoRepository.findById(columnOf(history, "MBR_ID")).orElseThrow().getRefTableName()).isEqualTo("MBR_HIST");
        assertThat(columnInfoRepository.findById(columnOf(order, "MBR_ID")).orElseThrow().getRefTableName()).as("다른 스키마의 같은 이름 참조는 그대로").isEqualToIgnoringCase("MBR");
        assertThat(refTarget(order)).isEqualTo(user.getId());
        schemaService.saveSchema(admin, new SchemaDto(archive, "ARCHIVE2", null, false, 0L, null, null, null, null));
        assertThat(columnInfoRepository.findById(columnOf(salesOrder, "MBR_ID")).orElseThrow().getRefTableName()).isEqualTo("ARCHIVE2.MBR_HIST");
        assertThat(refTarget(salesOrder)).isEqualTo(archivedUser.getId());
        assertThat(ddl(admin, salesOrder)).contains("REFERENCES ARCHIVE2.MBR_HIST (ID);");

        // 5) 컬럼 엑셀 업로드 : 여러 스키마에 있는 테이블명은 스키마명.테이블명 으로 적는다
        StandardTerm memo = term(projectId, "메모", "MEMO");
        assertThatThrownBy(() -> columnManageService.uploadColumnExcelFile(columnExcel(List.<String[]>of(columnRow("JMN", "MEMO"))), admin))
                .isInstanceOf(CustomException.class).hasMessageContaining("스키마명.테이블명");
        columnManageService.uploadColumnExcelFile(columnExcel(List.<String[]>of(columnRow("sales.JMN", "MEMO"))), admin);
        assertThat(columnInfoRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(projectId, salesOrder.getId(), "MEMO")).singleElement()
                .satisfies(c -> assertThat(c.getTableName()).isEqualTo("JMN"));
        assertThat(columnInfoRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(projectId, order.getId(), "MEMO")).isEmpty();
        assertThat(memo.getId()).isNotNull();
    }

    @Test
    @DisplayName("같은 이름 테이블의 인덱스·VIEW 참조·통합 DDL : 내부 ID 로 정확한 테이블을 가리키고, 스키마명이 붙어 서로 다른 DDL 이 나온다. 스키마명 포함이 다른 스키마와 같아 겹치면 만들지 않는다")
    void sameNameTablesInDdlAndViews() {
        AccountDto admin = loginAsAdmin("sch-ddl");
        long projectId = admin.getProjectId();
        words(projectId);
        Long archive = schema(admin, "ARCHIVE");
        StandardTerm id = term(projectId, "아이디", "ID");
        StandardTerm memo = term(projectId, "메모", "MEMO");
        TableInfo user = saveTable(admin, null, "회원");
        TableInfo archivedUser = saveTable(admin, archive, "회원");
        column(admin, user, id, null, true);
        column(admin, archivedUser, id, null, true);
        column(admin, archivedUser, memo, null, false);
        Long memoColumn = columnOf(archivedUser, "MEMO");
        physicalModelService.saveIndex(admin, new PhysicalModelDto.Index(null, archivedUser.getId(), "IX_MBR_MEMO", null, false,
                List.of(new PhysicalModelDto.ColumnRef(memoColumn, null)), null));

        // VIEW 이름도 스키마 단위 + 같은 이름 테이블을 ID 로 따로 참조
        ViewDto.SaveResult view = viewService.create(admin, new ViewDto.Save(null, "VW_MBR", null, null, "SELECT ID FROM MBR", List.of(),
                List.of(new ViewDto.Reference("TABLE", user.getId(), null, null, null))));
        ViewDto.SaveResult archivedView = viewService.create(admin, new ViewDto.Save(archive, "VW_MBR", null, null, "SELECT ID FROM ARCHIVE.MBR", List.of(),
                List.of(new ViewDto.Reference("TABLE", archivedUser.getId(), null, null, null))));
        assertThat(viewService.detail(admin, view.id()).view().references()).singleElement().satisfies(r -> assertThat(r.tableId()).isEqualTo(user.getId()));
        assertThat(viewService.detail(admin, archivedView.id()).view().references()).singleElement().satisfies(r -> assertThat(r.tableId()).isEqualTo(archivedUser.getId()));
        // 모델 밖 참조 : 이 VIEW 의 스키마(기본)에 MBR 이 있으면 이름으로 적을 수 없고, 모델에 없는 스키마의 같은 이름은 된다
        assertThatThrownBy(() -> viewService.create(admin, new ViewDto.Save(null, "VW_EXT", null, null, "SELECT 1", List.of(),
                List.of(new ViewDto.Reference("TABLE", null, null, null, "MBR"))))).hasMessageContaining("모델에 있는 객체");
        viewService.create(admin, new ViewDto.Save(null, "VW_EXT", null, null, "SELECT 1", List.of(), List.of(new ViewDto.Reference("TABLE", null, null, "LEGACY", "MBR"))));

        DdlExportDto export = exportService.export(admin, null, null, null, false);
        assertThat(export.errors()).isEmpty();
        assertThat(export.ddl()).contains("CREATE TABLE MBR (", "CREATE TABLE ARCHIVE.MBR (", "CREATE INDEX IX_MBR_MEMO ON ARCHIVE.MBR (MEMO);",
                "CREATE VIEW VW_MBR AS", "CREATE VIEW ARCHIVE.VW_MBR AS");
        assertThat(export.warnings()).noneMatch(w -> w.startsWith("같은 스키마 안에서"));
        assertThat(exportService.export(admin, archive, null, null, false).ddl()).contains("CREATE TABLE ARCHIVE.MBR (").doesNotContain("CREATE TABLE MBR (");
        // 스키마명 포함 = 다른 스키마의 이름 : 두 스키마의 MBR 이 한 스키마로 합쳐지므로 만들지 않는다
        DdlExportDto merged = exportService.export(admin, null, null, "archive", false);
        assertThat(merged.ddl()).isEmpty();
        assertThat(merged.errors()).singleElement().asString().contains("ARCHIVE", "MBR", "VW_MBR");
        assertThat(exportService.export(admin, null, null, "app", false).ddl()).contains("CREATE TABLE app.MBR (", "CREATE TABLE ARCHIVE.MBR (");
    }

    @Test
    @DisplayName("동시성 : 같은 스키마의 같은 이름(TABLE·TABLE, VIEW·VIEW, TABLE·VIEW, 이름 변경·VIEW 등록)은 하나만, 다른 스키마의 같은 이름은 모두 성공, 엑셀 업로드와 등록도 하나만 남는다")
    void concurrentRegistrationsPerSchema() throws Exception {
        AccountDto admin = loginAsAdmin("sch-race");
        long projectId = admin.getProjectId();
        String adminId = admin.getId();
        words(projectId);
        Long a = schema(admin, "SA");
        Long b = schema(admin, "SB");

        assertThat(race(List.<Callable<Object>>of(() -> saveTable(login(projectId, adminId), a, "회원"), () -> saveTable(login(projectId, adminId), a, "회원"))))
                .containsExactlyInAnyOrder("ok", "SAVED_DATA_EXISTS");
        assertThat(race(List.<Callable<Object>>of(() -> saveTable(login(projectId, adminId), b, "코드"), () -> saveTable(login(projectId, adminId), null, "코드"))))
                .as("다른 스키마").containsExactly("ok", "ok");
        assertThat(race(List.<Callable<Object>>of(() -> createView(login(projectId, adminId), a, "VW_X"), () -> createView(login(projectId, adminId), a, "VW_X"))))
                .containsExactlyInAnyOrder("ok", "SAVED_DATA_EXISTS");
        assertThat(race(List.<Callable<Object>>of(() -> createView(login(projectId, adminId), a, "VW_Y"), () -> createView(login(projectId, adminId), b, "VW_Y"))))
                .as("다른 스키마").containsExactly("ok", "ok");
        assertThat(race(List.<Callable<Object>>of(() -> saveTable(login(projectId, adminId), b, "주문"), () -> createView(login(projectId, adminId), b, "JMN"))))
                .as("같은 스키마의 TABLE·VIEW").containsExactlyInAnyOrder("ok", "SAVED_DATA_EXISTS");
        TableInfo code = tableInfoRepository.findAllBySchema_IdAndTableName(b, "CD").get(0);
        assertThat(race(List.<Callable<Object>>of(() -> { update(login(projectId, adminId), code, "CD_NEW", null); return null; }, () -> createView(login(projectId, adminId), b, "CD_NEW"))))
                .as("이름 변경과 VIEW 등록").containsExactlyInAnyOrder("ok", "SAVED_DATA_EXISTS");

        // 엑셀 업로드(기본 스키마)와 같은 이름 등록 : 둘 다 끝나도 기본 스키마에 그 이름은 하나
        Long main = defaultSchema(projectId);
        race(List.<Callable<Object>>of(() -> { tableDesignService.uploadTableExcelFile(tableExcel("MBR"), login(projectId, adminId)); return null; },
                () -> saveTable(login(projectId, adminId), null, "회원")));
        assertThat(tableInfoRepository.findAllBySchema_IdAndTableName(main, "MBR")).hasSize(1);
        // 이름 공간 정합 : 어느 스키마에도 TABLE·VIEW 이름이 겹치지 않는다
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL t join TB_VIEW v on v.SCHM_SN = t.SCHM_SN and v.VIEW_NM = t.TBL_NM where t.PRJ_SN = ?", Integer.class, projectId)).isZero();
    }

    @Test
    @DisplayName("PostgreSQL 실제 실행 : 두 스키마의 같은 이름 TABLE·VIEW, 스키마 간 FK·VIEW 참조를 통합 DDL 로 만들어 빈 임시 스키마에서 실행하면 각자 맞는 스키마에 생기고 참조도 맞다")
    void sameNamesRunOnPostgresql() {
        String url = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null, "PostgreSQL 검증 DB 미지정");
        AccountDto admin = loginAsAdmin("sch-pg");
        long projectId = admin.getProjectId();
        words(projectId);
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String base = "sch_" + suffix;
        String archiveName = "ARC_" + suffix.toUpperCase();
        Long archive = schema(admin, archiveName);
        StandardTerm id = term(projectId, "아이디", "ID");
        StandardTerm memberId = term(projectId, "회원_아이디", "MBR_ID");
        TableInfo user = saveTable(admin, null, "회원");
        TableInfo archivedUser = saveTable(admin, archive, "회원");
        column(admin, user, id, null, true);
        column(admin, archivedUser, id, null, true);
        TableInfo history = saveTable(admin, archive, "이력");
        column(admin, history, id, null, true);
        column(admin, history, memberId, "MBR", false);                 // 같은 스키마 → ARCHIVE.MBR
        TableInfo order = saveTable(admin, null, "주문");
        column(admin, order, id, null, true);
        column(admin, order, memberId, archiveName + ".MBR", false);    // 스키마 간 FK → ARCHIVE.MBR
        ViewDto.SaveResult mainView = viewService.create(admin, new ViewDto.Save(null, "VW_MBR", null, null, "SELECT ID FROM MBR", List.of(),
                List.of(new ViewDto.Reference("TABLE", user.getId(), null, null, null))));
        ViewDto.SaveResult archivedView = viewService.create(admin, new ViewDto.Save(archive, "VW_MBR", null, null, "SELECT ID FROM " + archiveName + ".MBR", List.of(),
                List.of(new ViewDto.Reference("TABLE", archivedUser.getId(), null, null, null))));
        viewService.create(admin, new ViewDto.Save(null, "VW_ALL", null, null, "SELECT ID FROM VW_MBR UNION ALL SELECT ID FROM " + archiveName + ".VW_MBR", List.of(),
                List.of(new ViewDto.Reference("VIEW", null, mainView.id(), null, null),
                        new ViewDto.Reference("VIEW", null, archivedView.id(), null, null)))); // 같은 이름 VIEW 둘 (스키마 간 참조 포함) : 참조 정보대로 둘 다 먼저 만든다
        DdlExportDto export = exportService.export(admin, null, "POSTGRESQL", base, false);
        assertThat(export.errors()).isEmpty();

        var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(url, "lexorma", "lexorma", true);
        JdbcTemplate pg = new JdbcTemplate(dataSource);
        String arc = archiveName.toLowerCase();
        pg.execute("create schema " + base);
        try {
            pg.execute("set search_path to " + base);
            pg.execute(export.ddl());
            assertThat(pg.queryForList("select table_schema || '.' || table_name from information_schema.tables where table_schema in (?, ?) and table_type = 'BASE TABLE' order by 1",
                    String.class, base, arc)).containsExactly(arc + ".hist", arc + ".mbr", base + ".jmn", base + ".mbr");
            assertThat(pg.queryForList("select table_schema || '.' || table_name from information_schema.views where table_schema in (?, ?) order by 1", String.class, base, arc))
                    .containsExactly(arc + ".vw_mbr", base + ".vw_all", base + ".vw_mbr");
            // FK 대상 : 두 FK 모두 ARCHIVE 스키마의 MBR (같은 이름의 기본 스키마 MBR 이 아니다)
            assertThat(pg.queryForList("select cn.nspname || '.' || c.relname || '->' || tn.nspname || '.' || t.relname from pg_constraint k "
                    + "join pg_class c on c.oid = k.conrelid join pg_namespace cn on cn.oid = c.relnamespace join pg_class t on t.oid = k.confrelid join pg_namespace tn on tn.oid = t.relnamespace "
                    + "where k.contype = 'f' and cn.nspname in (?, ?) order by 1", String.class, base, arc))
                    .containsExactly(arc + ".hist->" + arc + ".mbr", base + ".jmn->" + arc + ".mbr");
            pg.update("insert into " + arc + ".mbr(id) values ('A')");
            pg.update("insert into mbr(id) values ('M')");
            assertThat(pg.queryForList("select id from vw_all order by id", String.class)).containsExactly("A", "M");
            assertThatThrownBy(() -> pg.update("insert into jmn(id, mbr_id) values ('O1', 'M')")).as("FK 는 ARCHIVE.MBR 를 본다").isNotNull();
        } finally {
            pg.execute("drop schema if exists " + arc + " cascade");
            pg.execute("drop schema if exists " + base + " cascade");
            dataSource.destroy();
        }
    }

    // ------------------------------------------------------------------ 보조

    private void words(long projectId) {
        inTx(() -> {
            for (String[] w : new String[][]{{"회원", "MBR"}, {"코드", "CD"}, {"주문", "JMN"}, {"이력", "HIST"}}) {
                wordRepository.save(StandardWord.builder().projectId(projectId).revisionNumber(1).commonStandardWordName(w[0])
                        .commonStandardWordAbbreviation(w[1]).commonStandardWordEnglishName(w[1]).isFormatWord(false).isApproval(true).build());
            }
        });
    }

    private StandardTerm term(long projectId, String name, String abbreviation) {
        return inTx(() -> termRepository.save(StandardTerm.builder().projectId(projectId).revisionNumber(1).commonStandardTermName(name)
                .commonStandardTermAbbreviation(abbreviation).isApproval(true).build()));
    }

    private Long schema(AccountDto admin, String name) {
        return schemaService.saveSchema(admin, new SchemaDto(null, name, null, false, 0L, null, null, null, null)).id();
    }

    private Long defaultSchema(long projectId) {
        return inTx(() -> schemaService.resolveSchema(projectId, null).getId());
    }

    private TableInfo saveTable(AccountDto admin, Long schemaId, String korName) {
        tableDesignService.saveTable(TableInfoDto.builder().tableKorName(korName).tableDescription(korName).isMasterTable("N").sortOrder(1).schemaId(schemaId).build(), admin);
        Long target = schemaId != null ? schemaId : defaultSchema(admin.getProjectId());
        String name = tableDesignService.generateTableName(korName, admin).tableName();
        return inTx(() -> {
            TableInfo table = tableInfoRepository.findAllBySchema_IdAndTableName(target, name).get(0);
            table.getSchema().getSchemaName();
            return table;
        });
    }

    private void update(AccountDto admin, TableInfo table, String name, Long schemaId) {
        TableInfo current = inTx(() -> tableInfoRepository.findById(table.getId()).orElseThrow());
        if (Boolean.TRUE.equals(current.getIsApproval())) tableDesignService.updateTableIsApproval(false, admin, List.of(TableInfoDto.builder().id(table.getId()).build()));
        tableDesignService.updateTable(TableInfoDto.builder().id(table.getId()).tableName(name).tableDescription("설명").isMasterTable("N").sortOrder(1).schemaId(schemaId).build(), admin);
    }

    private void column(AccountDto admin, TableInfo table, StandardTerm term, String refTableName, boolean pk) {
        columnManageService.insertColumn(admin, ColumnInfoDto.builder().tableInfoId(table.getId()).termId(term.getId()).tableName(table.getTableName())
                .columnName(term.getCommonStandardTermAbbreviation()).columnDesc(term.getCommonStandardTermName()).dataType("VARCHAR").maxLength(20)
                .isPk(pk ? "Y" : "N").isNullable(pk ? "N" : "Y").isRequired("N").isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N")
                .refTableName(refTableName).sortOrder(1).build());
    }

    private Long columnOf(TableInfo table, String columnName) {
        return columnInfoRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(table.getProjectId(), table.getId(), columnName).get(0).getId();
    }

    /** 이 테이블 컬럼의 참조 테이블명에서 만든 관계(REF_COLUMN)의 대상 테이블 ID */
    private Long refTarget(TableInfo source) {
        return inTx(() -> relationRepository.findAllByProjectIdAndSourceTable_Id(source.getProjectId(), source.getId()).stream()
                .filter(r -> r.getRelationSource() == TableRelation.Source.REF_COLUMN).map(r -> r.getTargetTable().getId()).findFirst().orElse(null));
    }

    private Long relationTo(Long tableId) {
        return inTx(() -> relationRepository.findAll().stream()
                .filter(r -> r.getRelationSource() == TableRelation.Source.REF_COLUMN && r.getTargetTable().getId().equals(tableId))
                .map(TableRelation::getId).findFirst().orElse(null));
    }

    private String ddl(AccountDto admin, TableInfo table) {
        return tableDesignService.generateTableDdl(table.getId(), admin, null).ddl();
    }

    private Object createView(AccountDto admin, Long schemaId, String name) {
        return viewService.create(admin, new ViewDto.Save(schemaId, name, null, null, "SELECT 1", List.of(), List.of()));
    }

    private static String[] columnRow(String table, String column) {
        return new String[]{table, column, "메모", "VARCHAR", "100", "0", "0", "Y", "", "1", "N", "", "N", "N", "N", "N", "N", "N", ""};
    }

    private static MockMultipartFile columnExcel(List<String[]> rows) throws Exception {
        return excel(rows, "columns.xlsx");
    }

    private static MockMultipartFile tableExcel(String tableName) throws Exception {
        return excel(List.<String[]>of(new String[]{tableName, "엑셀 테이블", "N", "1"}), "tables.xlsx");
    }

    private static MockMultipartFile excel(List<String[]> rows, String name) throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            for (int i = 0; i < rows.size(); i++) {
                Row row = sheet.createRow(i + 1);
                for (int c = 0; c < rows.get(i).length; c++) row.createCell(c).setCellValue(rows.get(i)[c]);
            }
            workbook.write(out);
            return new MockMultipartFile("file", name, null, out.toByteArray());
        }
    }

    /** 동시에 실행한 결과 (호출 순서대로) : 성공 "ok", 실패는 ErrorCode 이름 */
    private static List<String> race(List<Callable<Object>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        call.call();
                        return "ok";
                    } catch (CustomException e) {
                        return e.getErrorCode().name();
                    } finally {
                        SecurityTestSupport.clear();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertInvalid(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(code));
    }

    private AccountDto login(long projectId, String accountId) {
        return SecurityTestSupport.loginAs(projectRepository, projectId, accountId, "ROLE_ADMIN");
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).dbType(DbType.POSTGRESQL).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    private void inTx(Runnable runnable) {
        tx.executeWithoutResult(s -> runnable.run());
    }
}
