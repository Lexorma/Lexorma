package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Model;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Relationship;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.physical.controller.PhysicalModelRestController;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.ColumnPair;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.Relation;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.controller.TableDesignRestController;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.version.controller.ModelVersionRestController;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.domain.dto.ModelVersionDto.Change;
import com.lexorma.data.version.domain.dto.ModelVersionDto.Comparison;
import com.lexorma.data.version.domain.dto.ModelVersionDto.Create;
import com.lexorma.data.version.domain.dto.ModelVersionDto.Summary;
import com.lexorma.data.version.repository.ModelVersionRepository;
import com.lexorma.data.version.service.ModelVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-9 3차 (H2 / PostgreSQL) : 모델 버전 만들기·조회·비교.
 * 화면이 부르는 컨트롤러·서비스로 모델을 바꾸면서, 저장된 버전이 그대로 남는지와 버전 사이·버전과 현재 모델의 차이가 객체 ID 기준으로 판정되는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelVersionIntegrationTest {

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired ModelVersionRepository versionRepository;
    @Autowired ModelChangeHistoryRepository historyRepository;
    @Autowired SchemaService schemaService;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingService mappings;
    @Autowired TableDesignRestController tables;
    @Autowired PhysicalModelRestController physical;
    @Autowired ModelVersionRestController versions;
    @Autowired ModelVersionService versionService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("버전 생성·목록·상세·내용 : 스키마, 물리(복합 FK·인덱스·UNIQUE·CHECK), 논리(식별자·관계), 매핑이 담기고 이후 모델을 바꿔도 저장된 버전은 그대로다")
    void createAndRead() {
        AccountDto admin = loginAsAdmin("m9v-create");
        long projectId = admin.getProjectId();
        Fixture f = fixture(admin);
        long historyBefore = historyRepository.count();

        Summary v1 = versions.create(admin, new Create("  v1.0  ", " 배포 전 모델 ")).getBody();
        assertThat(v1).extracting(Summary::versionName, Summary::description, Summary::formatVersion, Summary::createdBy).containsExactly("v1.0", "배포 전 모델", 1, admin.getUsername());
        assertThat(v1.createdAt()).isNotNull();
        assertThat(counts(v1)).containsEntry("SCHEMA", 1).containsEntry("PHYSICAL_TABLE", 2).containsEntry("PHYSICAL_COLUMN", 5).containsEntry("PHYSICAL_RELATION", 1)
                .containsEntry("PHYSICAL_INDEX", 1).containsEntry("PHYSICAL_CONSTRAINT", 2).containsEntry("LOGICAL_MODEL", 1).containsEntry("LOGICAL_ENTITY", 2)
                .containsEntry("LOGICAL_ATTRIBUTE", 3).containsEntry("LOGICAL_IDENTIFIER", 1).containsEntry("LOGICAL_RELATIONSHIP", 1)
                .containsEntry("TABLE_MAPPING", 1).containsEntry("COLUMN_MAPPING", 1);
        // 버전 생성은 모델 변경이 아니다 : 변경 이력이 늘지 않는다
        assertThat(historyRepository.count()).isEqualTo(historyBefore);

        // 내용 : 이력과 같은 모양의 상태 + 그 시점의 이름
        JsonNode objects = versions.snapshot(admin, v1.id()).getBody().objects();
        assertThat(object(objects, "SCHEMA", f.order.getSchema().getId()).get("state").get("isDefault").asBoolean()).isTrue();
        JsonNode orderTable = object(objects, "PHYSICAL_TABLE", f.order.getId());
        assertThat(orderTable.get("name").asString()).isEqualTo("TB_ORD");
        assertThat(orderTable.get("state").get("schemaId").asLong()).isEqualTo(f.order.getSchema().getId());
        JsonNode stat = object(objects, "PHYSICAL_COLUMN", f.stat.getId());
        assertThat(stat.get("name").asString()).isEqualTo("TB_ORD.STAT_CD");
        assertThat(stat.get("state").get("dataType").asString()).isEqualTo("VARCHAR");
        assertThat(stat.get("state").get("maxLength").asInt()).isEqualTo(10);
        assertThat(object(objects, "PHYSICAL_COLUMN", f.orderNo.getId()).get("state").get("isPk").asBoolean()).isTrue();
        // 복합 FK : 컬럼 쌍이 순서대로
        JsonNode fk = object(objects, "PHYSICAL_RELATION", f.fk.id()).get("state");
        assertThat(fk.get("relationType").asString()).isEqualTo("FK");
        assertThat(fk.get("columns")).hasSize(2);
        assertThat(fk.get("columns").get(0).get("sourceColumnId").asLong()).isEqualTo(f.statGrp.getId());
        assertThat(fk.get("columns").get(0).get("targetColumnName").asString()).isEqualTo("GRP_CD");
        assertThat(fk.get("columns").get(1).get("sourceColumnName").asString()).isEqualTo("STAT_CD");
        // 복합 인덱스, UNIQUE, CHECK
        assertThat(object(objects, "PHYSICAL_INDEX", f.index.id()).get("state").get("columns")).hasSize(2);
        assertThat(object(objects, "PHYSICAL_CONSTRAINT", f.unique.id()).get("state").get("constraintType").asString()).isEqualTo("UNIQUE");
        assertThat(object(objects, "PHYSICAL_CONSTRAINT", f.unique.id()).get("state").get("columns")).hasSize(2);
        assertThat(object(objects, "PHYSICAL_CONSTRAINT", f.check.id()).get("state").get("checkExpression").asString()).isEqualTo("STAT_CD <> ''");
        // 논리 모델 · 매핑
        assertThat(object(objects, "LOGICAL_ENTITY", f.orderEntity.id()).get("state").get("modelName").asString()).isEqualTo("주문 모델");
        assertThat(object(objects, "LOGICAL_ATTRIBUTE", f.orderNoAttribute.id()).get("name").asString()).isEqualTo("주문.주문 번호");
        assertThat(object(objects, "LOGICAL_IDENTIFIER", f.identifier.id()).get("state").get("attributes").get(0).get("attributeId").asLong()).isEqualTo(f.orderNoAttribute.id());
        assertThat(object(objects, "LOGICAL_RELATIONSHIP", f.relationship.id()).get("state").get("sourceEntityName").asString()).isEqualTo("주문");
        assertThat(object(objects, "TABLE_MAPPING", f.entityMapping.id()).get("state").get("tableName").asString()).isEqualTo("TB_ORD");
        assertThat(object(objects, "COLUMN_MAPPING", f.attributeMapping.id()).get("state").get("columnId").asLong()).isEqualTo(f.orderNo.getId());

        // 현재 모델과 차이가 없다
        Comparison same = versions.compare(admin, v1.id(), null).getBody();
        assertThat(same.changes()).isEmpty();
        assertThat(same.totals()).containsEntry("ADDED", 0).containsEntry("REMOVED", 0).containsEntry("MODIFIED", 0).containsEntry("UNCHANGED", 22);
        assertThat(same.target().id()).isNull();

        // 모델을 바꿔도 저장된 버전의 내용은 한 글자도 달라지지 않는다
        String stored = jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, v1.id());
        tables.updateTable(TableInfoDto.builder().id(f.order.getId()).tableName("TB_ORDER").tableDescription("주문").isMasterTable("N").build(), admin);
        physical.updateApproval(admin, "constraint", f.check.id(), false);
        physical.delete(admin, "constraint", f.check.id());
        logical.saveAttribute(admin, new Attribute(null, f.orderEntity.id(), "주문 일자", null, null, false, null, null, null, null, null));
        assertThat(jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, v1.id())).isEqualTo(stored);
        assertThat(object(versions.snapshot(admin, v1.id()).getBody().objects(), "PHYSICAL_TABLE", f.order.getId()).get("name").asString()).isEqualTo("TB_ORD");
        assertThat(versions.detail(admin, v1.id()).getBody()).isEqualTo(v1);

        // 목록 : 최신 순, 모델 내용 없이 규모만
        Summary v2 = versions.create(admin, new Create("v1.1", null)).getBody();
        Map<String, Object> page = versions.search(admin, 0, 10).getBody();
        assertThat(page.get("recordsTotal")).isEqualTo(2L);
        assertThat((List<?>) page.get("data")).extracting(row -> ((Summary) row).versionName()).containsExactly("v1.1", "v1.0");
        assertThat(counts(v2)).containsEntry("PHYSICAL_CONSTRAINT", 1).containsEntry("LOGICAL_ATTRIBUTE", 4);
        assertThat(projectId).isPositive();
    }

    @Test
    @DisplayName("비교 : 추가·삭제·수정, 이름 변경은 같은 객체의 수정, 지우고 같은 이름으로 다시 만들면 삭제 + 추가. 버전 ↔ 버전, 버전 ↔ 현재 모델")
    void compare() {
        AccountDto admin = loginAsAdmin("m9v-compare");
        Fixture f = fixture(admin);
        Summary v1 = versions.create(admin, new Create("v1", null)).getBody();

        // 수정 : 테이블 이름 변경, 컬럼 타입 길이·NULL 정책·기본값, PK 변경 / 추가 : 컬럼, 속성 / 삭제 : CHECK, 매핑 / 재생성 : 인덱스
        tables.updateTable(TableInfoDto.builder().id(f.order.getId()).tableName("TB_ORDER").tableDescription("주문").isMasterTable("N").build(), admin);
        tx.executeWithoutResult(s -> {
            ColumnInfo stat = columnInfoRepository.findById(f.stat.getId()).orElseThrow();
            stat.setMaxLength(20);
            stat.setIsNullable(false);
            stat.setDefaultValue("'00'");
        });
        ColumnInfo memo = column(admin.getProjectId(), f.order, "MEMO", false);
        physical.updateApproval(admin, "constraint", f.check.id(), false);
        physical.delete(admin, "constraint", f.check.id());
        mappings.deleteAttributeMapping(admin, f.attributeMapping.id());
        physical.updateApproval(admin, "index", f.index.id(), false);
        physical.delete(admin, "index", f.index.id());
        PhysicalModelDto.Index recreated = physical.saveIndex(admin, new PhysicalModelDto.Index(null, f.order.getId(), null, null, false, List.of(ref(f.stat), ref(f.statGrp)), null)).getBody();
        Attribute date = logical.saveAttribute(admin, new Attribute(null, f.orderEntity.id(), "주문 일자", null, null, false, null, null, null, null, null));

        Comparison current = versions.compare(admin, v1.id(), null).getBody();
        assertThat(current.base()).extracting(ModelVersionDto.Side::id, ModelVersionDto.Side::versionName).containsExactly(v1.id(), "v1");
        // 이름을 바꾼 테이블은 같은 객체의 수정이다 (삭제 + 추가가 아니다)
        Change renamed = change(current, "PHYSICAL_TABLE", f.order.getId());
        assertThat(renamed.change()).isEqualTo("MODIFIED");
        assertThat(renamed.changedFields()).contains("tableName", "tableDescription");
        assertThat(renamed.before().get("tableName").asString()).isEqualTo("TB_ORD");
        assertThat(renamed.after().get("tableName").asString()).isEqualTo("TB_ORDER");
        Change statChange = change(current, "PHYSICAL_COLUMN", f.stat.getId());
        assertThat(statChange.changedFields()).contains("maxLength", "isNullable", "defaultValue");
        assertThat(statChange.before().get("maxLength").asInt()).isEqualTo(10);
        assertThat(statChange.after().get("maxLength").asInt()).isEqualTo(20);
        assertThat(change(current, "PHYSICAL_COLUMN", memo.getId()).change()).isEqualTo("ADDED");
        assertThat(change(current, "PHYSICAL_COLUMN", memo.getId()).name()).endsWith(".MEMO");
        assertThat(change(current, "PHYSICAL_COLUMN", memo.getId()).before()).isNull();
        assertThat(change(current, "PHYSICAL_CONSTRAINT", f.check.id())).extracting(Change::change, change -> change.after() == null).containsExactly("REMOVED", true);
        assertThat(change(current, "COLUMN_MAPPING", f.attributeMapping.id()).change()).isEqualTo("REMOVED");
        assertThat(change(current, "LOGICAL_ATTRIBUTE", date.id()).change()).isEqualTo("ADDED");
        // 지우고 같은 구성으로 다시 만든 인덱스 : ID 가 다르므로 같은 객체로 보지 않는다
        assertThat(change(current, "PHYSICAL_INDEX", f.index.id()).change()).isEqualTo("REMOVED");
        assertThat(change(current, "PHYSICAL_INDEX", recreated.id()).change()).isEqualTo("ADDED");
        // 바뀌지 않은 것은 목록에 없다 : 다른 테이블, 관계, 논리 관계
        assertThat(current.changes()).noneMatch(change -> change.targetType().equals("PHYSICAL_TABLE") && change.targetId().equals(f.code.getId()));
        assertThat(current.changes()).noneMatch(change -> change.targetType().equals("LOGICAL_RELATIONSHIP") || change.targetType().equals("PHYSICAL_RELATION"));
        assertThat(current.byType().get("PHYSICAL_INDEX")).containsEntry("ADDED", 1).containsEntry("REMOVED", 1).containsEntry("MODIFIED", 0);
        assertThat(current.totals().get("ADDED") + current.totals().get("REMOVED") + current.totals().get("MODIFIED")).isEqualTo(current.changes().size());

        // 버전 ↔ 버전 : 현재 모델과 비교한 결과와 같고, 방향을 뒤집으면 추가와 삭제가 바뀐다
        Summary v2 = versions.create(admin, new Create("v2", null)).getBody();
        Comparison forward = versions.compare(admin, v1.id(), v2.id()).getBody();
        assertThat(forward.target().versionName()).isEqualTo("v2");
        assertThat(forward.totals()).isEqualTo(current.totals());
        assertThat(forward.changes()).extracting(Change::targetType, Change::targetId, Change::change)
                .containsExactlyInAnyOrderElementsOf(current.changes().stream().map(change -> tuple(change.targetType(), change.targetId(), change.change())).toList());
        Comparison backward = versions.compare(admin, v2.id(), v1.id()).getBody();
        assertThat(backward.totals()).containsEntry("ADDED", forward.totals().get("REMOVED")).containsEntry("REMOVED", forward.totals().get("ADDED"))
                .containsEntry("MODIFIED", forward.totals().get("MODIFIED"));
        assertThat(versions.compare(admin, v2.id(), null).getBody().changes()).isEmpty();

        // UNIQUE 의 컬럼 순서만 바뀐 것, 참조 대상의 이름만 바뀐 것은 변경이 아니다
        // (서비스는 같은 컬럼 구성의 UNIQUE 를 다시 저장하지 않으므로 순서만 직접 뒤집는다)
        jdbc.update("update TB_TBL_CNSTRT_COL set COL_ORD = COL_ORD + 10 where CNSTRT_SN = ?", f.unique.id());
        jdbc.update("update TB_TBL_CNSTRT_COL set COL_ORD = 13 - COL_ORD where CNSTRT_SN = ?", f.unique.id());
        logical.updateEntityApproval(admin, f.itemEntity.id(), false);
        logical.saveEntity(admin, new Entity(f.itemEntity.id(), f.model.id(), "주문 품목", null, null, null, null));
        Comparison minor = versions.compare(admin, v2.id(), null).getBody();
        assertThat(minor.changes()).noneMatch(change -> change.targetType().equals("PHYSICAL_CONSTRAINT"));
        assertThat(change(minor, "LOGICAL_ENTITY", f.itemEntity.id()).changedFields()).contains("entityName");
        assertThat(minor.changes()).noneMatch(change -> change.targetType().equals("LOGICAL_RELATIONSHIP"));

        // 잘못된 요청 : 같은 버전끼리, 없는 버전
        assertThatThrownBy(() -> versions.compare(admin, v1.id(), v1.id())).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_MODEL_VERSION));
        assertThatThrownBy(() -> versions.compare(admin, v1.id(), 999_999_999L)).isInstanceOfSatisfying(CustomException.class, ModelVersionIntegrationTest::versionNotFound);
        assertThatThrownBy(() -> versions.compare(admin, 999_999_999L, null)).isInstanceOfSatisfying(CustomException.class, ModelVersionIntegrationTest::versionNotFound);
        assertThatThrownBy(() -> versions.detail(admin, 999_999_999L)).isInstanceOfSatisfying(CustomException.class, ModelVersionIntegrationTest::versionNotFound);
        assertThatThrownBy(() -> versions.snapshot(admin, -1L)).isInstanceOfSatisfying(CustomException.class, ModelVersionIntegrationTest::versionNotFound);
    }

    @Test
    @DisplayName("이력과 버전의 정합성 : 객체마다 마지막 변경 이력의 '변경 후' 와 버전에 담긴 상태가 같고, 버전을 만든 뒤 값을 바꿔도 담긴 값(타입·길이·NULL·기본값·PK)은 그대로다")
    void historyAndVersionAgree() {
        AccountDto admin = loginAsAdmin("m9v-history");
        long projectId = admin.getProjectId();
        Fixture f = fixture(admin);
        tx.executeWithoutResult(s -> {
            ColumnInfo stat = columnInfoRepository.findById(f.stat.getId()).orElseThrow();
            stat.setDataType("NUMERIC");
            stat.setMaxLength(null);
            stat.setPrecision(new java.math.BigDecimal("12.00")); // 이력·버전에는 12 로 남는다
            stat.setScale(2);
            stat.setIsNullable(false);
            stat.setDefaultValue("0");
            stat.setColumnDesc("상태 금액");
        });
        Summary v1 = versions.create(admin, new Create("v1", null)).getBody();
        JsonNode objects = versions.snapshot(admin, v1.id()).getBody().objects();

        // 객체별 마지막 이력 (삭제된 것은 없다) : 이력이 남긴 값과 버전에 담긴 값이 필드 하나까지 같다
        Map<String, JsonNode> lastAfter = new java.util.LinkedHashMap<>();
        historyRepository.findAll().stream().filter(history -> history.getProjectId().equals(projectId)).sorted(java.util.Comparator.comparing(history -> history.getId()))
                .forEach(history -> lastAfter.put(history.getTargetType() + ":" + history.getTargetId(), JSON.readTree(history.getAfterData())));
        int compared = 0;
        for (String type : objects.propertyNames()) {
            if (type.equals("SCHEMA")) continue; // 스키마는 이력 대상이 아니다
            for (JsonNode object : objects.get(type)) {
                assertThat(object.get("state")).as("%s #%s", type, object.get("id")).isEqualTo(lastAfter.get(type + ":" + object.get("id").asLong()));
                compared++;
            }
        }
        assertThat(compared).isEqualTo(lastAfter.size()).isEqualTo(21);

        // 버전을 만든 뒤 다시 바꾼다 → 버전에는 만들 때의 값이 남아 있고, 비교에는 전후가 그대로 나온다
        tx.executeWithoutResult(s -> {
            ColumnInfo stat = columnInfoRepository.findById(f.stat.getId()).orElseThrow();
            stat.setDataType("VARCHAR");
            stat.setMaxLength(30);
            stat.setPrecision(null);
            stat.setScale(null);
            stat.setIsNullable(true);
            stat.setDefaultValue(null);
            columnInfoRepository.findById(f.orderNo.getId()).orElseThrow().setIsPk(false);
        });
        JsonNode kept = object(versions.snapshot(admin, v1.id()).getBody().objects(), "PHYSICAL_COLUMN", f.stat.getId()).get("state");
        assertThat(kept.get("dataType").asString()).isEqualTo("NUMERIC");
        assertThat(kept.get("maxLength").isNull()).isTrue();
        assertThat(kept.get("precision").asInt()).isEqualTo(12);
        assertThat(kept.get("scale").asInt()).isEqualTo(2);
        assertThat(kept.get("isNullable").asBoolean()).isFalse();
        assertThat(kept.get("defaultValue").asString()).isEqualTo("0");
        assertThat(kept.get("columnDesc").asString()).isEqualTo("상태 금액");
        assertThat(object(versions.snapshot(admin, v1.id()).getBody().objects(), "PHYSICAL_COLUMN", f.orderNo.getId()).get("state").get("isPk").asBoolean()).isTrue();
        Comparison changed = versions.compare(admin, v1.id(), null).getBody();
        assertThat(change(changed, "PHYSICAL_COLUMN", f.stat.getId()).changedFields()).containsExactlyInAnyOrder("dataType", "maxLength", "precision", "scale", "isNullable", "defaultValue");
        assertThat(change(changed, "PHYSICAL_COLUMN", f.orderNo.getId()).changedFields()).containsExactly("isPk");
        assertThat(changed.changes()).hasSize(2);
    }

    @Test
    @DisplayName("권한·격리·검증 : VIEWER 는 조회만, USER 부터 생성. 다른 프로젝트의 버전은 ID 를 알아도 볼 수 없고, 버전명은 프로젝트 안에서만 겹치지 않으면 된다")
    void permissionIsolationValidation() {
        AccountDto admin = loginAsAdmin("m9v-auth");
        long projectId = admin.getProjectId();
        table(projectId, "TB_A");
        Summary v1 = versions.create(admin, new Create("v1", null)).getBody();

        // 같은 프로젝트의 같은 이름 (앞뒤 공백은 뺀다), 빈 이름, 너무 긴 이름·설명 : 거절되고 버전이 남지 않는다
        assertThatThrownBy(() -> versions.create(admin, new Create(" v1 ", null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> versions.create(admin, new Create("  ", null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> versions.create(admin, new Create(null, null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> versions.create(admin, new Create("x".repeat(101), null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> versions.create(admin, new Create("v2", "x".repeat(1001)))).isInstanceOf(CustomException.class);
        assertThat(versionCount(projectId)).isEqualTo(1);
        assertThat(versions.create(admin, new Create("x".repeat(100), "x".repeat(1000))).getBody().versionName()).hasSize(100);

        // 저장 직전에 실패하면 (UNIQUE 위반 : 중복 검사를 건너뛴 상황) 트랜잭션 전체가 롤백되어 아무것도 남지 않는다
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("insert into TB_MDL_VER(MDL_VER_SN, PRJ_SN, VER_NM, FRMT_VER_NO, MDL_CNT_CN, MDL_CN, REG_DT) values(-1, ?, 'v3', 1, '{}', '{}', current_timestamp)", projectId);
            jdbc.update("insert into TB_MDL_VER(MDL_VER_SN, PRJ_SN, VER_NM, FRMT_VER_NO, MDL_CNT_CN, MDL_CN, REG_DT) values(-2, ?, 'v3', 1, '{}', '{}', current_timestamp)", projectId);
        })).isInstanceOf(RuntimeException.class);
        assertThat(versionCount(projectId)).isEqualTo(2);

        // VIEWER : 목록·상세·내용·비교는 되고 생성은 안 된다. USER 는 생성할 수 있다
        AccountDto viewer = login(projectId, "901", "ROLE_VIEWER");
        assertThat(versions.search(viewer, 0, 10).getBody().get("recordsTotal")).isEqualTo(2L);
        assertThat(versions.detail(viewer, v1.id()).getBody().versionName()).isEqualTo("v1");
        assertThat(versions.snapshot(viewer, v1.id()).getBody().objects().get("PHYSICAL_TABLE")).hasSize(1);
        assertThat(versions.compare(viewer, v1.id(), null).getBody().changes()).isEmpty();
        assertThatThrownBy(() -> versions.create(viewer, new Create("viewer", null))).isInstanceOf(AccessDeniedException.class);
        login(projectId, "902", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> versions.search(viewer, 0, 10)).isInstanceOf(AccessDeniedException.class);
        AccountDto user = login(projectId, "903", "ROLE_USER");
        assertThat(versions.create(user, new Create("by-user", null)).getBody().createdBy()).isEqualTo(user.getUsername());
        assertThat(versionCount(projectId)).isEqualTo(3);

        // 다른 프로젝트 : 같은 버전명을 쓸 수 있고, 서로의 버전은 목록·상세·내용·비교 어디에서도 보이지 않는다
        AccountDto other = loginAsAdmin("m9v-auth-other");
        assertThat(versions.search(other, 0, 10).getBody().get("recordsTotal")).isEqualTo(0L);
        Summary otherV1 = versions.create(other, new Create("v1", null)).getBody();
        assertThat(otherV1.id()).isNotEqualTo(v1.id());
        // 다른 프로젝트의 버전은 "없는 버전" 과 똑같이 답한다 (있는지조차 알려 주지 않는다)
        assertThatThrownBy(() -> versions.detail(other, v1.id())).isInstanceOfSatisfying(CustomException.class, ModelVersionIntegrationTest::versionNotFound);
        assertThatThrownBy(() -> versions.snapshot(other, v1.id())).isInstanceOfSatisfying(CustomException.class, ModelVersionIntegrationTest::versionNotFound);
        assertThatThrownBy(() -> versions.compare(other, v1.id(), null)).isInstanceOfSatisfying(CustomException.class, ModelVersionIntegrationTest::versionNotFound);
        assertThatThrownBy(() -> versions.compare(other, otherV1.id(), v1.id())).isInstanceOfSatisfying(CustomException.class, ModelVersionIntegrationTest::versionNotFound);
        assertThat(versions.snapshot(other, otherV1.id()).getBody().objects().get("PHYSICAL_TABLE")).isEmpty();
    }

    @Test
    @DisplayName("동시에 같은 이름으로 만들면 하나만 저장되고, 다른 이름은 모두 저장된다")
    void concurrentCreate() throws Exception {
        AccountDto admin = loginAsAdmin("m9v-concurrent");
        long projectId = admin.getProjectId();
        table(projectId, "TB_A");

        int threads = 6;
        var pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> same = new ArrayList<>();
            List<Future<Boolean>> different = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                same.add(pool.submit(() -> attempt(start, admin, "same")));
                different.add(pool.submit(() -> attempt(start, admin, "v" + n)));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : same) if (result.get()) succeeded++;
            assertThat(succeeded).isEqualTo(1);
            for (Future<Boolean> result : different) assertThat(result.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(versionCount(projectId)).isEqualTo(threads + 1);
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ? and VER_NM = 'same'", Integer.class, projectId)).isEqualTo(1);
    }

    private boolean attempt(CountDownLatch start, AccountDto account, String name) throws InterruptedException {
        start.await();
        try {
            versionService.create(account, new Create(name, null));
            return true;
        } catch (CustomException e) {
            return false;
        }
    }

    /** 모델 버전 전용 오류 : 관계·인덱스 오류 문구가 아니라 "모델 버전을 찾을 수 없습니다." (404) */
    private static void versionNotFound(CustomException e) {
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_VERSION_NOT_FOUND);
        assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(404);
        assertThat(e.getMessage()).contains("모델 버전을 찾을 수 없습니다.").doesNotContain("관계");
    }

    // ------------------------------------------------------------------ 준비

    private record Fixture(TableInfo code, TableInfo order, ColumnInfo grp, ColumnInfo cd, ColumnInfo orderNo, ColumnInfo statGrp, ColumnInfo stat,
                           Relation fk, PhysicalModelDto.Index index, PhysicalModelDto.Constraint unique, PhysicalModelDto.Constraint check,
                           Model model, Entity orderEntity, Entity itemEntity, Attribute orderNoAttribute, Identifier identifier, Relationship relationship,
                           EntityMapping entityMapping, AttributeMapping attributeMapping) {
    }

    /** 테이블 2 · 컬럼 5 · 복합 FK 1 · 복합 인덱스 1 · UNIQUE 1 · CHECK 1 / 논리 모델 1 · 엔터티 2 · 속성 3 · 식별자 1 · 관계 1 / 매핑 2 */
    private Fixture fixture(AccountDto admin) {
        long projectId = admin.getProjectId();
        TableInfo code = table(projectId, "TB_CODE");
        ColumnInfo grp = column(projectId, code, "GRP_CD", true);
        ColumnInfo cd = column(projectId, code, "CD", true);
        TableInfo order = table(projectId, "TB_ORD");
        ColumnInfo orderNo = column(projectId, order, "ORD_NO", true);
        ColumnInfo statGrp = column(projectId, order, "STAT_GRP_CD", false);
        ColumnInfo stat = column(projectId, order, "STAT_CD", false);
        Relation fk = physical.saveRelation(admin, new Relation(null, null, "FK", null, order.getId(), null, code.getId(), null,
                List.of(new ColumnPair(statGrp.getId(), null, grp.getId(), null), new ColumnPair(stat.getId(), null, cd.getId(), null)), null)).getBody();
        PhysicalModelDto.Index index = physical.saveIndex(admin, new PhysicalModelDto.Index(null, order.getId(), null, null, false, List.of(ref(stat), ref(statGrp)), null)).getBody();
        PhysicalModelDto.Constraint unique = physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), null, "UNIQUE", null, List.of(ref(statGrp), ref(stat)), null)).getBody();
        PhysicalModelDto.Constraint check = physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), "CK_ORD_STAT", "CHECK", "STAT_CD <> ''", null, null)).getBody();

        Model model = logical.saveModel(admin, new Model(null, "주문 모델", null));
        Entity orderEntity = logical.saveEntity(admin, new Entity(null, model.id(), "주문", null, null, null, null));
        Entity itemEntity = logical.saveEntity(admin, new Entity(null, model.id(), "주문 상품", null, null, null, null));
        Attribute orderNoAttribute = logical.saveAttribute(admin, new Attribute(null, orderEntity.id(), "주문 번호", null, null, true, null, null, null, null, null));
        logical.saveAttribute(admin, new Attribute(null, orderEntity.id(), "상태", null, null, false, null, null, null, null, null));
        logical.saveAttribute(admin, new Attribute(null, itemEntity.id(), "상품 번호", null, null, true, null, null, null, null, null));
        Identifier identifier = logical.saveIdentifier(admin, new Identifier(null, orderEntity.id(), "주문 식별자", true, List.of(orderNoAttribute.id()), null));
        Relationship relationship = logical.saveRelationship(admin, new Relationship(null, model.id(), null, null, orderEntity.id(), null, itemEntity.id(), null, false, 1, 1, 0, null, null, null));
        EntityMapping entityMapping = mappings.saveEntityMapping(admin, new EntityMapping(null, orderEntity.id(), order.getId()));
        AttributeMapping attributeMapping = mappings.saveAttributeMapping(admin, new AttributeMapping(null, entityMapping.id(), orderNoAttribute.id(), orderNo.getId()));
        return new Fixture(code, order, grp, cd, orderNo, statGrp, stat, fk, index, unique, check, model, orderEntity, itemEntity, orderNoAttribute, identifier, relationship, entityMapping, attributeMapping);
    }

    private static Map<String, Integer> counts(Summary summary) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        summary.counts().propertyNames().forEach(name -> result.put(name, summary.counts().get(name).asInt()));
        return result;
    }

    private static JsonNode object(JsonNode objects, String type, Long id) {
        for (JsonNode object : objects.get(type)) {
            if (object.get("id").asLong() == id) return object;
        }
        throw new AssertionError(type + " #" + id + " 가 버전에 없습니다.");
    }

    private static Change change(Comparison comparison, String type, Long id) {
        return comparison.changes().stream().filter(change -> change.targetType().equals(type) && change.targetId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(type + " #" + id + " 의 변경이 없습니다."));
    }

    private int versionCount(long projectId) {
        return jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, projectId);
    }

    private static PhysicalModelDto.ColumnRef ref(ColumnInfo column) {
        return new PhysicalModelDto.ColumnRef(column.getId(), null);
    }

    private AccountDto login(long projectId, String accountId, String role) {
        return SecurityTestSupport.loginAs(projectRepository, projectId, accountId, role);
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(false).isMasterTable(false)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR").maxLength(10).isPk(pk).isApproval(false).build()));
    }
}
