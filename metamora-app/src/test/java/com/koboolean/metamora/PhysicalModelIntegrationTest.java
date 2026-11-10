package com.koboolean.metamora;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.AccountRepository;
import com.koboolean.metamora.core.support.SecurityTestSupport;
import com.koboolean.metamora.data.column.domain.dto.ColumnInfoDto;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.column.repository.ColumnInfoRepository;
import com.koboolean.metamora.data.column.service.ColumnManageService;
import com.koboolean.metamora.data.physical.domain.dto.PhysicalModelDto;
import com.koboolean.metamora.data.physical.domain.entity.TableRelation;
import com.koboolean.metamora.data.physical.repository.TableRelationRepository;
import com.koboolean.metamora.data.physical.service.PhysicalModelService;
import com.koboolean.metamora.data.schema.domain.dto.SchemaDto;
import com.koboolean.metamora.data.schema.domain.entity.SchemaInfo;
import com.koboolean.metamora.data.schema.service.SchemaService;
import com.koboolean.metamora.data.table.domain.dto.TableDdlDto;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
import com.koboolean.metamora.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-1 물리 모델 (H2, 실제 JPA 매핑·쿼리·FK) : 스키마, 참조 테이블명 → 관계 동기화, 복합 FK·인덱스·제약조건 DDL,
 * 컬럼 삭제 정책, 프로젝트 격리, 승인 제한
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalModelIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired TableRelationRepository relationRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired TableDesignService tableDesignService;
    @Autowired ColumnManageService columnManageService;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 참조 테이블명 → 관계

    @Test
    @DisplayName("참조 테이블명은 대상 PK 가 단일일 때만 관계가 되고, 대상 PK 가 바뀌면 다시 맞춘다")
    void referenceSync() {
        AccountDto admin = loginAsAdmin("m1-sync");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT", null);
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", true, null);
        TableInfo child = table(projectId, "TB_CHILD", null);
        ColumnInfo childRef = column(projectId, child, "PARENT_ID", false, "tb_parent");

        sync(projectId, child.getId());
        assertThat(derived(childRef)).as("단일 PK 참조 → 관계").isNotNull();
        assertThat(pair(derived(childRef))).isEqualTo(childRef.getId() + "->" + parentId.getId());

        ColumnInfo parentSeq = column(projectId, parent, "SEQ", true, null); // 복합 PK 가 됨
        sync(projectId, parent.getId());
        assertThat(derived(childRef)).as("대상이 복합 PK → 관계 제거 (참조 테이블명은 남음)").isNull();
        assertThat(columnInfoRepository.findById(childRef.getId()).orElseThrow().getRefTableName()).isEqualTo("tb_parent");

        // 컬럼 삭제(서비스) → 다시 단일 PK → 관계 복구
        columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(parentSeq.getId()).build()));
        assertThat(derived(childRef)).isNotNull();

        // 참조하던 PK 컬럼 삭제 → 관계 함께 정리 (FK 오류 없이)
        columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(parentId.getId()).build()));
        assertThat(derived(childRef)).isNull();
    }

    // ------------------------------------------------------------------ 복합 FK·인덱스·제약조건 + DDL

    @Test
    @DisplayName("스키마가 다른 테이블 사이 복합 FK, 복합 인덱스, 복합 UNIQUE, CHECK 를 저장하고 DDL 에 반영한다")
    void compositeModelDdl() {
        AccountDto admin = loginAsAdmin("m1-ddl");
        long projectId = admin.getProjectId();
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", "영업", null));

        TableInfo code = table(projectId, "TB_CODE", null);
        ColumnInfo grp = column(projectId, code, "GRP_CD", true, null);
        ColumnInfo cd = column(projectId, code, "CD", true, null);
        TableInfo order = table(projectId, "TB_ORDER", sales.id());
        column(projectId, order, "ORDER_NO", true, null);
        ColumnInfo statGrp = column(projectId, order, "STAT_GRP_CD", false, null);
        ColumnInfo stat = column(projectId, order, "STAT_CD", false, null);

        PhysicalModelDto.Relation saved = physicalModelService.saveRelation(admin, new PhysicalModelDto.Relation(null, null, null, null,
                order.getId(), null, code.getId(), null,
                List.of(new PhysicalModelDto.ColumnPair(statGrp.getId(), null, grp.getId(), null),
                        new PhysicalModelDto.ColumnPair(stat.getId(), null, cd.getId(), null)), null));
        assertThat(saved.relationSource()).isEqualTo("USER");
        assertThat(saved.columns()).extracting(PhysicalModelDto.ColumnPair::sourceColumnName).containsExactly("STAT_GRP_CD", "STAT_CD");
        assertThat(saved.isApproval()).as("관리자 등록은 승인").isTrue();

        physicalModelService.saveIndex(admin, new PhysicalModelDto.Index(null, order.getId(), null, "btree", false,
                List.of(ref(stat), ref(statGrp)), null));
        physicalModelService.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), null, "UNIQUE", null,
                List.of(ref(statGrp), ref(stat)), null));
        physicalModelService.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), null, "CHECK", "STAT_CD <> ''", null, null));

        assertThatThrownBy(() -> physicalModelService.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), "UK_AGAIN", "UNIQUE", null,
                List.of(ref(stat), ref(statGrp)), null)))
                .as("같은 컬럼 구성의 UNIQUE 는 중복").isInstanceOf(CustomException.class);

        TableDdlDto ddl = tableDesignService.generateTableDdl(order.getId(), admin);
        assertThat(ddl.ddl())
                .contains("CREATE TABLE sales.TB_ORDER (")
                .contains("CONSTRAINT UK_TB_ORDER_STAT_GRP_CD_STAT_CD UNIQUE (STAT_GRP_CD, STAT_CD)")
                .contains("CONSTRAINT CK_TB_ORDER_1 CHECK (STAT_CD <> '')")
                .contains("CREATE INDEX IX_TB_ORDER_STAT_CD_STAT_GRP_CD ON sales.TB_ORDER (STAT_CD, STAT_GRP_CD);")
                .contains("ALTER TABLE sales.TB_ORDER ADD CONSTRAINT FK_TB_ORDER_STAT_GRP_CD FOREIGN KEY (STAT_GRP_CD, STAT_CD) REFERENCES TB_CODE (GRP_CD, CD);");

        PhysicalModelDto.TableModel model = physicalModelService.selectTableModel(admin, code.getId());
        assertThat(model.incomingRelations()).hasSize(1);
        assertThat(model.schemaName()).isEqualTo(SchemaInfo.DEFAULT_NAME);

        // 관계에 쓰인 컬럼은 삭제할 수 없다 (승인 전 컬럼이어도)
        assertThatThrownBy(() -> columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(cd.getId()).build())))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RELATION_EXISTS));

        // 스키마 : 테이블이 있으면 삭제 불가, 기본 스키마 삭제 불가
        assertThatThrownBy(() -> schemaService.deleteSchema(admin, sales.id())).isInstanceOf(CustomException.class);
        Long defaultSchemaId = schemaService.resolveSchema(projectId, null).getId();
        assertThatThrownBy(() -> schemaService.deleteSchema(admin, defaultSchemaId)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> schemaService.saveSchema(admin, new SchemaDto(null, "SALES", null, null)))
                .as("대소문자만 다른 스키마명").isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> schemaService.saveSchema(admin, new SchemaDto(null, "1bad name", null, null)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_SCHEMA_NAME));
        assertThatThrownBy(() -> physicalModelService.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), null, "CHECK",
                "1 = 1; DROP TABLE TB_ORDER", null, null)))
                .as("CHECK 조건식은 한 문장").isInstanceOf(CustomException.class);
    }

    // ------------------------------------------------------------------ 프로젝트 격리 / 승인

    @Test
    @DisplayName("다른 프로젝트의 테이블·컬럼·스키마·관계 ID 로는 조회·등록·삭제할 수 없다")
    void projectIsolation() {
        AccountDto a = loginAsAdmin("m1-iso-a");
        TableInfo aTable = table(a.getProjectId(), "TB_A", null);
        ColumnInfo aId = column(a.getProjectId(), aTable, "A_ID", true, null);
        ColumnInfo aRef = column(a.getProjectId(), aTable, "A_REF", false, null);
        PhysicalModelDto.Relation aRelation = physicalModelService.saveRelation(a, new PhysicalModelDto.Relation(null, null, "LOGICAL", null,
                aTable.getId(), null, aTable.getId(), null, List.of(new PhysicalModelDto.ColumnPair(aRef.getId(), null, aId.getId(), null)), null));
        physicalModelService.updateApproval(a, PhysicalModelService.Kind.RELATION, aRelation.id(), false);
        Long aSchemaId = schemaService.resolveSchema(a.getProjectId(), null).getId();

        AccountDto b = loginAsAdmin("m1-iso-b");
        TableInfo bTable = table(b.getProjectId(), "TB_B", null);
        ColumnInfo bId = column(b.getProjectId(), bTable, "B_ID", true, null);

        assertThatThrownBy(() -> physicalModelService.selectTableModel(b, aTable.getId())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physicalModelService.saveRelation(b, new PhysicalModelDto.Relation(null, null, null, null,
                bTable.getId(), null, aTable.getId(), null, List.of(new PhysicalModelDto.ColumnPair(bId.getId(), null, aId.getId(), null)), null)))
                .as("다른 프로젝트 테이블을 대상으로").isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physicalModelService.saveIndex(b, new PhysicalModelDto.Index(null, bTable.getId(), null, null, false, List.of(ref(aRef)), null)))
                .as("다른 프로젝트 컬럼으로 인덱스").isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physicalModelService.delete(b, PhysicalModelService.Kind.RELATION, aRelation.id())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> schemaService.resolveSchema(b.getProjectId(), aSchemaId))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        assertThatThrownBy(() -> schemaService.saveSchema(b, new SchemaDto(aSchemaId, "hijack", null, null))).isInstanceOf(CustomException.class);
        assertThat(relationRepository.findById(aRelation.id())).as("A 의 관계는 그대로").isPresent();
    }

    @Test
    @DisplayName("승인 권한이 없으면 미승인으로 등록되고, 승인·스키마 변경은 할 수 없으며, 승인된 항목은 삭제할 수 없다")
    void approval() {
        AccountDto admin = loginAsAdmin("m1-approval");
        long projectId = admin.getProjectId();
        TableInfo t = table(projectId, "TB_T", null);
        ColumnInfo id = column(projectId, t, "ID", true, null);
        ColumnInfo name = column(projectId, t, "NM", false, null);
        ColumnInfo code = column(projectId, t, "CD", false, null);

        AccountDto user = SecurityTestSupport.loginAs(projectRepository, projectId, "999999", "ROLE_USER");
        PhysicalModelDto.Index index = physicalModelService.saveIndex(user, new PhysicalModelDto.Index(null, t.getId(), null, null, false, List.of(ref(name), ref(code)), null));
        assertThat(index.isApproval()).isFalse();
        assertThatThrownBy(() -> physicalModelService.updateApproval(user, PhysicalModelService.Kind.INDEX, index.id(), true)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> schemaService.saveSchema(user, new SchemaDto(null, "user_schema", null, null))).isInstanceOf(CustomException.class);
        physicalModelService.delete(user, PhysicalModelService.Kind.INDEX, index.id()); // 미승인은 삭제 가능

        SecurityTestSupport.loginAs(projectRepository, projectId, String.valueOf(admin.getId()), "ROLE_ADMIN");
        PhysicalModelDto.Constraint unique = physicalModelService.saveConstraint(admin, new PhysicalModelDto.Constraint(null, t.getId(), null, "UNIQUE", null, List.of(ref(name), ref(code)), null));
        assertThatThrownBy(() -> physicalModelService.delete(admin, PhysicalModelService.Kind.CONSTRAINT, unique.id()))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED));
        assertThat(id.getId()).isNotNull();
    }

    // ------------------------------------------------------------------ 보조

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name, Long schemaId) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, String ref) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(pk).refTableName(ref).isApproval(false).build()));
    }

    private void sync(long projectId, Long tableId) {
        tx.executeWithoutResult(s -> physicalModelService.syncReferenceRelations(projectId, List.of(tableId)));
    }

    private TableRelation derived(ColumnInfo column) {
        return relationRepository.findAllByDerivedColumnIdIn(List.of(column.getId())).stream().findFirst().orElse(null);
    }

    private String pair(TableRelation relation) {
        return inTx(() -> {
            var c = relationRepository.findById(relation.getId()).orElseThrow().getColumns().get(0);
            return c.getSourceColumn().getId() + "->" + c.getTargetColumn().getId();
        });
    }

    private static PhysicalModelDto.ColumnRef ref(ColumnInfo column) {
        return new PhysicalModelDto.ColumnRef(column.getId(), null);
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }
}
