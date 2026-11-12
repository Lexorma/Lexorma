package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.service.ErdService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-3 스키마 관리·테이블/컬럼 화면의 스키마 연동 (H2, 실제 JPA 매핑·쿼리) :
 * 목록(테이블 수·등록 정보), 등록·수정·삭제 정책, 기본 스키마 보호, 스키마별 테이블 조회, 컬럼의 소속 스키마, 권한, 프로젝트 격리
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SchemaManagementIntegrationTest {

    private static final Pageable PAGE = PageRequest.of(0, 100, Sort.by("id"));

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired ColumnManageService columnManageService;
    @Autowired ErdService erdService;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("스키마 목록은 기본 스키마 먼저, 소속 테이블 수·등록 정보와 함께 조회되고 등록·수정·삭제 정책을 지킨다")
    void schemaCrud() {
        AccountDto admin = loginAsAdmin("m3-crud");
        long projectId = admin.getProjectId();

        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", "영업", null));
        SchemaDto empty = schemaService.saveSchema(admin, new SchemaDto(null, "archive", null, null));
        TableInfo order = table(projectId, "TB_ORDER", sales.id());
        column(projectId, order, "ORDER_NO");
        table(projectId, "TB_CODE", null);

        List<SchemaDto> schemas = schemaService.selectSchemas(admin);
        assertThat(schemas).extracting(SchemaDto::schemaName).containsExactly(SchemaInfo.DEFAULT_NAME, "archive", "sales");
        assertThat(schemas).extracting(SchemaDto::isDefault).containsExactly(true, false, false);
        assertThat(schemas).extracting(SchemaDto::tableCount).containsExactly(1L, 0L, 1L);
        assertThat(schemas).allSatisfy(schema -> assertThat(schema.created()).isNotNull());
        assertThat(schemas.get(2).schemaDescription()).isEqualTo("영업");

        // 중복 : 대소문자만 달라도 거절, 다른 프로젝트의 같은 이름은 허용
        assertThatThrownBy(() -> schemaService.saveSchema(admin, new SchemaDto(null, "SALES", null, null)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SAVED_DATA_EXISTS));
        assertThatThrownBy(() -> schemaService.saveSchema(admin, new SchemaDto(empty.id(), "Sales", null, null)))
                .as("수정으로도 중복 이름은 만들 수 없다").isInstanceOf(CustomException.class);

        // 수정 : 내부 ID·소속 테이블은 그대로, DDL 접두어만 바뀐다
        assertThat(tableDesignService.generateTableDdl(order.getId(), admin, null).ddl()).contains("CREATE TABLE sales.TB_ORDER (");
        SchemaDto renamed = schemaService.saveSchema(admin, new SchemaDto(sales.id(), "sales_v2", "영업 2", null));
        assertThat(renamed.id()).isEqualTo(sales.id());
        assertThat(tableDesignService.selectTable(order.getId(), admin).getSchemaName()).isEqualTo("sales_v2");
        assertThat(tableDesignService.generateTableDdl(order.getId(), admin, null).ddl()).contains("CREATE TABLE sales_v2.TB_ORDER (");
        assertThat(erdService.selectGraph(admin, new ErdDto.Search(sales.id(), null, null, null), 0, 100).tables())
                .as("ERD 스키마 조회는 ID 기준이라 이름을 바꿔도 같다").extracting(ErdDto.Table::tableName).containsExactly("TB_ORDER");

        // DDL "스키마명 포함" : 기본 스키마 테이블은 받은 이름을 붙이고, 다른 스키마 테이블은 자기 스키마명 그대로
        TableInfo code = tableInfoRepository.findByTableNameAndProjectId("TB_CODE", projectId);
        column(projectId, code, "CD");
        assertThat(tableDesignService.generateTableDdl(code.getId(), admin, null).ddl()).contains("CREATE TABLE TB_CODE (");
        assertThat(tableDesignService.generateTableDdl(code.getId(), admin, " public ").ddl())
                .contains("CREATE TABLE public.TB_CODE (").doesNotContain("DEFAULT.TB_CODE");
        assertThat(tableDesignService.generateTableDdl(order.getId(), admin, "public").ddl()).contains("CREATE TABLE sales_v2.TB_ORDER (");
        assertThatThrownBy(() -> tableDesignService.generateTableDdl(code.getId(), admin, "public; drop table x"))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SCHEMA_NAME));

        // 기본 스키마 : 이름 변경·삭제 불가, 설명은 수정 가능
        SchemaDto defaultSchema = schemas.get(0);
        assertThatThrownBy(() -> schemaService.saveSchema(admin, new SchemaDto(defaultSchema.id(), "main", null, null)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SCHEMA_NAME));
        assertThat(schemaService.saveSchema(admin, new SchemaDto(defaultSchema.id(), SchemaInfo.DEFAULT_NAME, "설명 변경", null)).schemaDescription())
                .isEqualTo("설명 변경");
        assertThatThrownBy(() -> schemaService.deleteSchema(admin, defaultSchema.id()))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DATA_CANNOT_BE_DELETED));

        // 삭제 : 테이블이 있으면 거절 (테이블은 그대로), 비어 있으면 삭제
        assertThatThrownBy(() -> schemaService.deleteSchema(admin, sales.id()))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RELATION_EXISTS));
        assertThat(tableInfoRepository.findById(order.getId())).isPresent();
        schemaService.deleteSchema(admin, empty.id());
        assertThat(schemaService.selectSchemas(admin)).extracting(SchemaDto::schemaName).containsExactly(SchemaInfo.DEFAULT_NAME, "sales_v2");
    }

    @Test
    @DisplayName("테이블 목록은 스키마별로 조회·정렬되고, 컬럼 목록과 ERD 는 소속 스키마·표준 도메인 없이도 조회된다")
    void tableAndColumnSchema() {
        AccountDto admin = loginAsAdmin("m3-table");
        long projectId = admin.getProjectId();
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", null, null));
        TableInfo order = table(projectId, "TB_ORDER", sales.id());
        TableInfo code = table(projectId, "TB_CODE", null);
        table(projectId, "TB_ORDER_ITEM", sales.id());
        ColumnInfo orderNo = column(projectId, order, "ORDER_NO");
        column(projectId, code, "CD");

        assertThat(names(null, null, null, admin)).containsExactlyInAnyOrder("TB_ORDER", "TB_CODE", "TB_ORDER_ITEM");
        assertThat(names(sales.id(), null, null, admin)).containsExactlyInAnyOrder("TB_ORDER", "TB_ORDER_ITEM");
        assertThat(names(sales.id(), "tableName", "ITEM", admin)).containsExactly("TB_ORDER_ITEM");
        assertThat(names(null, "tableName", "CODE", admin)).containsExactly("TB_CODE");
        assertThat(names(null, "unknownColumn", "x", admin)).as("모르는 검색 컬럼은 무시").hasSize(3);

        // 스키마명 정렬 (DTO 필드 → 연관관계 경로)
        Pageable bySchema = PageRequest.of(0, 100, Sort.by(Sort.Order.desc("schemaName"), Sort.Order.asc("tableName")));
        assertThat(tableDesignService.selectTableInfoData(bySchema, admin, null, null, null).getContent())
                .extracting(TableInfoDto::getTableName).containsExactly("TB_ORDER", "TB_ORDER_ITEM", "TB_CODE");

        TableInfoDto detail = tableDesignService.selectTable(order.getId(), admin);
        assertThat(detail.getSchemaId()).isEqualTo(sales.id());
        assertThat(detail.getSchemaName()).isEqualTo("sales");

        // 컬럼 : 테이블의 스키마를 그대로 보여 준다 (컬럼에 따로 저장하지 않는다)
        List<ColumnInfoDto> columns = columnManageService.selectTableData(PAGE, admin, null, null, null).getContent();
        assertThat(columns).extracting(ColumnInfoDto::getColumnName, ColumnInfoDto::getSchemaName, ColumnInfoDto::getTableInfoId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ORDER_NO", "sales", order.getId()),
                        org.assertj.core.groups.Tuple.tuple("CD", SchemaInfo.DEFAULT_NAME, code.getId()));

        // ERD : 표준 용어가 없는 컬럼도 오류 없이 조회된다 (용어·도메인은 null)
        ErdDto.Column erdColumn = erdService.selectGraph(admin, new ErdDto.Search(sales.id(), null, null, null), 0, 100).columns().stream()
                .filter(column -> column.id().equals(orderNo.getId())).findFirst().orElseThrow();
        assertThat(erdColumn.termName()).isNull();
        assertThat(erdColumn.domainName()).isNull();
    }

    @Test
    @DisplayName("V0 예제 : ERD 컬럼에 표준 용어와 표준 도메인이 함께 내려온다")
    void seededTermAndDomain() {
        AccountDto admin = SecurityTestSupport.loginAsAdmin(0L);
        List<ErdDto.Column> columns = erdService.selectGraph(admin, new ErdDto.Search(null, "TB_SCHM", null, null), 0, 100).columns();
        assertThat(columns).isNotEmpty();
        assertThat(columns).filteredOn(column -> column.termName() != null).isNotEmpty()
                .allSatisfy(column -> assertThat(column.domainName()).isNotBlank());
    }

    @Test
    @DisplayName("승인 권한이 없으면(USER·VIEWER) 스키마를 조회만 할 수 있고, 다른 프로젝트의 스키마·테이블은 ID 를 알아도 다룰 수 없다")
    void permissionAndIsolation() {
        AccountDto a = loginAsAdmin("m3-iso-a");
        SchemaDto aSales = schemaService.saveSchema(a, new SchemaDto(null, "sales", null, null));
        TableInfo aTable = table(a.getProjectId(), "TB_A", aSales.id());

        for (String role : List.of("ROLE_USER", "ROLE_VIEWER")) {
            AccountDto member = SecurityTestSupport.loginAs(projectRepository, a.getProjectId(), "999999", role);
            assertThat(schemaService.selectSchemas(member)).as(role + " 조회").hasSize(2);
            assertThatThrownBy(() -> schemaService.saveSchema(member, new SchemaDto(null, "member_schema", null, null)))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_PERMISSION_DENIED));
            assertThatThrownBy(() -> schemaService.saveSchema(member, new SchemaDto(aSales.id(), "renamed", null, null)))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_PERMISSION_DENIED));
            assertThatThrownBy(() -> schemaService.deleteSchema(member, aSales.id()))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_PERMISSION_DENIED));
        }

        AccountDto b = loginAsAdmin("m3-iso-b");
        assertThat(schemaService.saveSchema(b, new SchemaDto(null, "sales", null, null)).id())
                .as("다른 프로젝트의 같은 스키마명은 허용").isNotEqualTo(aSales.id());
        assertThat(schemaService.selectSchemas(b)).extracting(SchemaDto::id).doesNotContain(aSales.id());
        assertThatThrownBy(() -> schemaService.saveSchema(b, new SchemaDto(aSales.id(), "hijack", null, null)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        assertThatThrownBy(() -> schemaService.deleteSchema(b, aSales.id()))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        assertThat(names(aSales.id(), null, null, b)).as("다른 프로젝트 스키마 ID 로 테이블 조회").isEmpty();
        assertThatThrownBy(() -> tableDesignService.selectTable(aTable.getId(), b))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TABLE_IS_NOT_DEFINED));
        assertThatThrownBy(() -> schemaService.resolveSchema(b.getProjectId(), aSales.id()))
                .as("테이블 등록 시 다른 프로젝트 스키마 선택")
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_IS_NOT_DEFINED));

        SecurityTestSupport.loginAs(projectRepository, a.getProjectId(), String.valueOf(a.getId()), "ROLE_ADMIN");
        assertThat(schemaService.selectSchemas(a)).as("A 의 스키마는 그대로").extracting(SchemaDto::schemaName)
                .containsExactly(SchemaInfo.DEFAULT_NAME, "sales");
    }

    // ------------------------------------------------------------------ 보조

    private List<String> names(Long schemaId, String searchColumn, String searchQuery, AccountDto login) {
        return tableDesignService.selectTableInfoData(PAGE, login, searchColumn, searchQuery, schemaId).getContent().stream()
                .map(TableInfoDto::getTableName).toList();
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name, Long schemaId) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(true).isApproval(false).build()));
    }
}
