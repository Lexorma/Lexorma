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
import com.lexorma.data.erd.controller.ErdRestController;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.physical.controller.PhysicalModelRestController;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.ColumnPair;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.Relation;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.controller.TableDesignRestController;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * M-8 3차 : 물리 ERD 편집이 쓰는 호출 순서 (H2 / PostgreSQL). 화면은 새 API 없이 테이블·컬럼 관리와 물리 모델(/api/physical) API 를 쓴다.
 * ERD 조회(Graph)·테이블 물리 모델(TableModel)·DDL 이 같은 변경을 돌려주는지, 모델 변경과 배치 저장이 서로 독립이고
 * 조회 범위 밖 테이블의 배치가 보존되는지, 거절된 변경 뒤에 상태가 그대로인지, 권한·승인·프로젝트 격리가 ERD 경로에서도 같은지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalErdEditIntegrationTest {

    private static final ErdDto.Viewport VIEWPORT = new ErdDto.Viewport(0, 0, 1);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired ColumnManageService columnManageService;
    @Autowired PhysicalModelRestController physical;
    @Autowired TableDesignRestController tables;
    @Autowired ErdRestController erd;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("ERD 에서 등록한 FK(단일·복합·자기 참조·다중)가 ERD 조회·테이블 물리 모델·DDL 에 똑같이 나오고, 삭제하면 모두에서 사라진다")
    void foreignKeysFromErd() {
        AccountDto admin = loginAsAdmin("m8-3-fk");
        long projectId = admin.getProjectId();
        TableInfo code = table(projectId, "TB_CODE", true);
        ColumnInfo grp = column(projectId, code, "GRP_CD", true, null);
        ColumnInfo cd = column(projectId, code, "CD", true, null);
        TableInfo order = table(projectId, "TB_ORDER", true);
        ColumnInfo orderNo = column(projectId, order, "ORDER_NO", true, null);
        ColumnInfo upNo = column(projectId, order, "UP_ORDER_NO", false, null);
        ColumnInfo statGrp = column(projectId, order, "STAT_GRP_CD", false, null);
        ColumnInfo stat = column(projectId, order, "STAT_CD", false, null);
        ColumnInfo payGrp = column(projectId, order, "PAY_GRP_CD", false, null);
        ColumnInfo pay = column(projectId, order, "PAY_CD", false, null);

        // 복합 FK : 컬럼 쌍 순서 그대로
        Relation composite = physical.saveRelation(admin, relation(null, "FK", order, code, pair(statGrp, grp), pair(stat, cd))).getBody();
        // 같은 두 테이블 사이 두 번째 FK (다중), 자기 참조, 논리 관계
        Relation second = physical.saveRelation(admin, relation("FK_ORDER_PAY", "FK", order, code, pair(payGrp, grp), pair(pay, cd))).getBody();
        Relation self = physical.saveRelation(admin, relation(null, "FK", order, order, pair(upNo, orderNo))).getBody();
        assertThat(composite.relationName()).isEqualTo("FK_TB_ORDER_STAT_GRP_CD");
        assertThat(self.sourceTableId()).isEqualTo(self.targetTableId());

        // 거절 : 같은 구성 중복, 다른 테이블의 컬럼, 컬럼 쌍 없음 → 저장된 관계 수는 그대로
        assertThatThrownBy(() -> physical.saveRelation(admin, relation("FK_DUP", "FK", order, code, pair(statGrp, grp), pair(stat, cd)))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physical.saveRelation(admin, relation(null, "FK", order, code, pair(grp, grp)))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physical.saveRelation(admin, relation(null, "FK", order, code))).isInstanceOf(CustomException.class);

        ErdDto.Graph graph = erd(admin);
        assertThat(graph.relations()).extracting(Relation::id).containsExactlyInAnyOrder(composite.id(), second.id(), self.id());
        Relation shown = graph.relations().stream().filter(r -> r.id().equals(composite.id())).findFirst().orElseThrow();
        assertThat(shown.columns()).extracting(ColumnPair::sourceColumnName, ColumnPair::targetColumnName)
                .containsExactly(tuple("STAT_GRP_CD", "GRP_CD"), tuple("STAT_CD", "CD"));

        // 테이블 관리가 읽는 물리 모델과 같다
        PhysicalModelDto.TableModel model = physical.selectTableModel(admin, order.getId()).getBody();
        assertThat(model.outgoingRelations()).extracting(Relation::id).containsExactlyInAnyOrder(composite.id(), second.id(), self.id());
        assertThat(physical.selectTableModel(admin, code.getId()).getBody().incomingRelations()).hasSize(2);

        // DDL : 저장된 FK 와 같은 이름·컬럼 순서·대상
        String ddl = ddl(admin, order);
        assertThat(ddl)
                .contains("ADD CONSTRAINT FK_TB_ORDER_STAT_GRP_CD FOREIGN KEY (STAT_GRP_CD, STAT_CD) REFERENCES TB_CODE (GRP_CD, CD);")
                .contains("ADD CONSTRAINT FK_ORDER_PAY FOREIGN KEY (PAY_GRP_CD, PAY_CD) REFERENCES TB_CODE (GRP_CD, CD);")
                .contains("ADD CONSTRAINT FK_TB_ORDER_UP_ORDER_NO FOREIGN KEY (UP_ORDER_NO) REFERENCES TB_ORDER (ORDER_NO);");

        // FK 에 쓰인 컬럼은 삭제할 수 없다 → ERD·DDL 그대로
        assertThatThrownBy(() -> columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(pay.getId()).build())))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RELATION_EXISTS));
        assertThat(ddl(admin, order)).isEqualTo(ddl);

        // 승인된 관계는 삭제할 수 없다 → 승인취소 후 삭제 → ERD·물리 모델·DDL 에서 사라지고 테이블·컬럼은 그대로
        assertThatThrownBy(() -> physical.delete(admin, "relation", second.id()))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED));
        assertThat(erd(admin).relations()).hasSize(3);
        physical.updateApproval(admin, "relation", second.id(), false);
        physical.delete(admin, "relation", second.id());
        ErdDto.Graph after = erd(admin);
        assertThat(after.relations()).extracting(Relation::id).containsExactlyInAnyOrder(composite.id(), self.id());
        assertThat(after.columns()).hasSize(graph.columns().size());
        assertThat(physical.selectTableModel(admin, order.getId()).getBody().outgoingRelations()).hasSize(2);
        assertThat(ddl(admin, order)).doesNotContain("FK_ORDER_PAY").contains("FK_TB_ORDER_STAT_GRP_CD");

        // 삭제 후 다시 등록(= 수정) : 방향·컬럼을 바꿔 등록할 수 있다. 논리 관계는 DDL 에 들어가지 않는다
        Relation logical = physical.saveRelation(admin, relation("REL_PAY", "LOGICAL", order, code, pair(pay, cd))).getBody();
        assertThat(erd(admin).relations()).extracting(Relation::id).contains(logical.id());
        assertThat(ddl(admin, order)).doesNotContain("REL_PAY");
    }

    @Test
    @DisplayName("참조 테이블명으로 만든 관계는 ERD 에서 삭제·중복 등록할 수 없고, 인덱스·제약조건은 ERD 대화상자의 호출로 DDL 에 반영된다")
    void derivedRelationAndKeys() {
        AccountDto admin = loginAsAdmin("m8-3-keys");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT", true);
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", true, null);
        TableInfo child = table(projectId, "TB_CHILD", true);
        column(projectId, child, "CHILD_ID", true, null);
        ColumnInfo ref = column(projectId, child, "PARENT_ID", false, "TB_PARENT");
        ColumnInfo name = column(projectId, child, "CHILD_NM", false, null);
        ColumnInfo type = column(projectId, child, "TYPE_CD", false, null);
        tx.executeWithoutResult(s -> physicalModelSync(projectId, child));

        Relation derived = erd(admin).relations().stream().filter(r -> "REF_COLUMN".equals(r.relationSource())).findFirst().orElseThrow();
        assertThatThrownBy(() -> physical.delete(admin, "relation", derived.id())).as("참조 테이블명 관계는 컬럼에서 관리").isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physical.saveRelation(admin, relation(null, "FK", child, parent, pair(ref, parentId))))
                .as("참조 테이블명이 있는 컬럼은 사용자 관계로 등록할 수 없다").isInstanceOf(CustomException.class);
        assertThat(erd(admin).relations()).hasSize(1);

        PhysicalModelDto.Index index = physical.saveIndex(admin, new PhysicalModelDto.Index(null, child.getId(), null, null, false, List.of(ref(name), ref(type)), null)).getBody();
        PhysicalModelDto.Constraint unique = physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, child.getId(), null, "UNIQUE", null, List.of(ref(type), ref(name)), null)).getBody();
        physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, child.getId(), "CK_CHILD_TYPE", "CHECK", "TYPE_CD <> ''", null, null));
        assertThatThrownBy(() -> physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, child.getId(), null, "CHECK", "1 = 1; DROP TABLE X", null, null)))
                .isInstanceOf(CustomException.class);

        PhysicalModelDto.TableModel model = physical.selectTableModel(admin, child.getId()).getBody();
        assertThat(model.indexes()).hasSize(1);
        assertThat(model.constraints()).hasSize(2);
        String ddl = ddl(admin, child);
        assertThat(ddl).contains(index.indexName()).contains(unique.constraintName()).contains("CONSTRAINT CK_CHILD_TYPE CHECK (TYPE_CD <> '')");

        physical.updateApproval(admin, "index", index.id(), false);
        physical.delete(admin, "index", index.id());
        assertThat(physical.selectTableModel(admin, child.getId()).getBody().indexes()).isEmpty();
        assertThat(ddl(admin, child)).doesNotContain(index.indexName()).contains(unique.constraintName());
    }

    @Test
    @DisplayName("A·B 만 조회해 A 를 옮겨 저장해도 범위 밖 C 의 배치는 보존되고, 모델 변경은 배치를 바꾸지 않으며, 테이블 삭제 조건은 테이블 관리와 같다")
    void layoutAndTableDelete() {
        AccountDto admin = loginAsAdmin("m8-3-layout");
        long projectId = admin.getProjectId();
        TableInfo a = table(projectId, "TB_A", true);
        ColumnInfo aId = column(projectId, a, "A_ID", true, null);
        TableInfo b = table(projectId, "TB_B", true);
        ColumnInfo bRef = column(projectId, b, "A_ID", false, null);
        TableInfo c = table(projectId, "ZZ_C", false);

        ErdDto.Layout saved = erd.saveLayout(admin, new ErdDto.Layout(null,
                Map.of(a.getId(), new ErdDto.Point(10, 10), b.getId(), new ErdDto.Point(400, 10), c.getId(), new ErdDto.Point(900, 500)), VIEWPORT)).getBody();

        // 부분 조회(TB_ 검색 → A·B) 에서 A 만 옮겨 저장 : 화면은 바뀐 노드만 보낸다
        assertThat(erd.selectGraph(admin, null, "TB_", null, null, 0, 100).getBody().tables()).extracting(ErdDto.Table::tableName).containsExactlyInAnyOrder("TB_A", "TB_B");
        ErdDto.Layout moved = erd.saveLayout(admin, new ErdDto.Layout(saved.version(), Map.of(a.getId(), new ErdDto.Point(77, 88)), VIEWPORT)).getBody();
        assertThat(moved.nodes().get(a.getId())).isEqualTo(new ErdDto.Point(77, 88));
        assertThat(moved.nodes().get(b.getId())).isEqualTo(new ErdDto.Point(400, 10));
        assertThat(moved.nodes().get(c.getId())).as("조회 범위 밖 C 의 배치 보존").isEqualTo(new ErdDto.Point(900, 500));

        // 모델 변경(FK 등록·삭제)은 배치·버전을 바꾸지 않는다
        Relation fk = physical.saveRelation(admin, relation(null, "FK", b, a, pair(bRef, aId))).getBody();
        ErdDto.Layout afterModel = erd.selectLayout(admin).getBody();
        assertThat(afterModel.version()).isEqualTo(moved.version());
        assertThat(afterModel.nodes()).isEqualTo(moved.nodes());

        // 테이블 삭제 : 컬럼이 있으면, 승인됐으면 거절 (테이블 관리와 같은 API) → ERD 그대로
        assertThatThrownBy(() -> tables.deleteTable(admin, List.of(dto(a))))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COLUMN_DEPENDENCY_EXISTS));
        TableInfo approvedEmpty = table(projectId, "TB_EMPTY", true);
        assertThatThrownBy(() -> tables.deleteTable(admin, List.of(dto(approvedEmpty))))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED));
        assertThat(erd(admin).tables()).hasSize(4);
        assertThat(erd(admin).relations()).extracting(Relation::id).containsExactly(fk.id());

        // 승인 전·컬럼 없는 C 삭제 → ERD 에서 사라지고, 다음 배치 저장 때 C 의 좌표가 정리된다 (다른 좌표는 그대로)
        tables.deleteTable(admin, List.of(dto(c)));
        assertThat(erd(admin).tables()).extracting(ErdDto.Table::tableName).doesNotContain("ZZ_C");
        ErdDto.Layout cleaned = erd.saveLayout(admin, new ErdDto.Layout(afterModel.version(), Map.of(), VIEWPORT)).getBody();
        assertThat(cleaned.nodes()).containsOnlyKeys(a.getId(), b.getId());
        assertThat(cleaned.nodes().get(a.getId())).isEqualTo(new ErdDto.Point(77, 88));
        // 삭제된 테이블의 좌표를 보내면 거절
        assertThatThrownBy(() -> erd.saveLayout(admin, new ErdDto.Layout(cleaned.version(), Map.of(c.getId(), new ErdDto.Point(1, 1)), VIEWPORT)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TABLE_IS_NOT_DEFINED));
    }

    @Test
    @DisplayName("조회 권한은 물리 모델을 바꿀 수 없고, 일반 사용자의 등록은 승인 대기이며 승인할 수 없고, 다른 프로젝트의 테이블·관계는 건드릴 수 없다")
    void permissionAndIsolation() {
        AccountDto admin = loginAsAdmin("m8-3-auth");
        long projectId = admin.getProjectId();
        TableInfo a = table(projectId, "TB_A", true);
        ColumnInfo aId = column(projectId, a, "A_ID", true, null);
        TableInfo b = table(projectId, "TB_B", true);
        ColumnInfo bRef = column(projectId, b, "A_ID", false, null);
        ColumnInfo bRef2 = column(projectId, b, "A_ID2", false, null);
        Relation adminFk = physical.saveRelation(admin, relation(null, "FK", b, a, pair(bRef, aId))).getBody();

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, projectId, "940", "ROLE_VIEWER");
        assertThat(erd(viewer).relations()).hasSize(1);
        assertThat(physical.selectTableModel(viewer, b.getId()).getBody().outgoingRelations()).hasSize(1);
        assertThatThrownBy(() -> physical.saveRelation(viewer, relation(null, "FK", b, a, pair(bRef2, aId)))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> physical.delete(viewer, "relation", adminFk.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> physical.saveIndex(viewer, new PhysicalModelDto.Index(null, b.getId(), null, null, false, List.of(ref(bRef2)), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> tables.deleteTable(viewer, List.of(dto(a)))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> tables.saveTable(TableInfoDto.builder().tableKorName("뷰어").build(), viewer)).isInstanceOf(AccessDeniedException.class);

        AccountDto user = SecurityTestSupport.loginAs(projectRepository, projectId, "941", "ROLE_USER");
        Relation userFk = physical.saveRelation(user, relation(null, "FK", b, a, pair(bRef2, aId))).getBody();
        assertThat(userFk.isApproval()).as("승인 권한이 없으면 승인 대기").isFalse();
        assertThatThrownBy(() -> physical.updateApproval(user, "relation", userFk.id(), true))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APPROVAL_PERMISSION_DENIED));
        assertThatThrownBy(() -> physical.delete(user, "relation", adminFk.id())).as("승인된 관계").isInstanceOf(CustomException.class);
        // 승인 대기 FK 도 물리 모델이므로 ERD·DDL 에 나온다 (DDL 은 승인 여부로 거르지 않는다)
        assertThat(erd(user).relations()).hasSize(2);
        assertThat(ddl(user, b)).contains(userFk.relationName());
        physical.delete(user, "relation", userFk.id());
        assertThat(erd(user).relations()).extracting(Relation::id).containsExactly(adminFk.id());

        AccountDto other = loginAsAdmin("m8-3-auth-other");
        TableInfo x = table(other.getProjectId(), "TB_X", true);
        ColumnInfo xRef = column(other.getProjectId(), x, "A_ID", false, null);
        assertThat(erd(other).tables()).extracting(ErdDto.Table::tableName).containsExactly("TB_X");
        assertThatThrownBy(() -> physical.saveRelation(other, relation(null, "FK", x, a, pair(xRef, aId)))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physical.delete(other, "relation", adminFk.id())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physical.selectTableModel(other, a.getId())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> erd.saveLayout(other, new ErdDto.Layout(null, Map.of(a.getId(), new ErdDto.Point(1, 1)), VIEWPORT))).isInstanceOf(CustomException.class);

        SecurityTestSupport.loginAs(projectRepository, projectId, "941", "ROLE_USER");
        assertThat(erd(user).relations()).as("다른 프로젝트의 시도 뒤에도 그대로").extracting(Relation::id).containsExactly(adminFk.id());
    }

    // ------------------------------------------------------------------ 보조

    private void physicalModelSync(long projectId, TableInfo table) {
        // 컬럼 저장 경로(컬럼 편집)가 부르는 참조 테이블명 → 관계 동기화
        physicalModelService.syncReferenceRelations(projectId, List.of(table.getId()));
    }

    private ErdDto.Graph erd(AccountDto account) {
        return erd.selectGraph(account, null, null, null, null, 0, 100).getBody();
    }

    private String ddl(AccountDto account, TableInfo table) {
        return tableDesignService.generateTableDdl(table.getId(), account, null).ddl();
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

    private static TableInfoDto dto(TableInfo table) {
        return TableInfoDto.builder().id(table.getId()).build();
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name, boolean approved) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, String ref) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(pk).refTableName(ref).isApproval(false).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }
}
