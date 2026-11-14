package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.controller.SourceMappingRestController;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Status;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec.Namespace;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.source.connection.service.SourceConnectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원본 DB 스키마·TABLE 매핑 (M-4c) : 기준 연결, 1:1, 후보와 확정의 분리, 상태 계산(재확인·충돌·원본 없음·모델 없음), 원본·모델 삭제, PARTIAL 수집,
 * 동시 확정, 권한·프로젝트 격리, 물리 모델을 바꾸지 않음. 원본 DB 에 접속하지 않고 수집 결과(SourceCollectionSpec)를 직접 넘긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceMappingIntegrationTest {

    @Autowired SourceMappingService service;
    @Autowired SourceMappingRestController api;
    @Autowired SourceCollectionService collections;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SourceConnectionService connectionService;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("기준 연결 → 후보(제안만) → 스키마·TABLE 1:1 확정. public → DEFAULT 제안, 같은 이름·다른 스키마, 잘못된 스키마·VIEW·중복·수집 없음은 거절. 물리 모델은 그대로다")
    void mappingRules() {
        Env e = env("m4c-rules");
        // 기준 연결이 없으면 확정할 수 없다
        assertThat(service.overview(e.manager).base()).isNull();
        rejected(() -> service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(1L, e.defaultSchema.getId())), ErrorCode.SOURCE_MAPPING_INVALID);
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        // 수집이 없으면 확정할 수 없다
        rejected(() -> service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(1L, e.defaultSchema.getId())), ErrorCode.SOURCE_MAPPING_INVALID);
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        String modelBefore = modelFingerprint(e.project.getId());

        SourceMappingDto.Overview overview = service.overview(e.manager);
        assertThat(overview.reflectedOrder()).isEqualTo(1);
        assertThat(overview.viewCount()).isEqualTo(1);
        SourceMappingDto.SchemaRow publicRow = schemaRow(overview, "public");
        SourceMappingDto.SchemaRow archiveRow = schemaRow(overview, "archive");
        // 후보는 제안뿐 : 상태는 미매핑, 매핑 행 없음
        assertThat(publicRow.status()).isEqualTo(Status.UNMAPPED);
        assertThat(publicRow.candidate().id()).isEqualTo(e.defaultSchema.getId());
        assertThat(archiveRow.candidate().id()).isEqualTo(e.archiveSchema.getId());
        assertThat(count("TB_SRC_SCHM_MPNG", e)).isZero();
        assertThat(schemaRow(overview, "audit").candidate()).isNull();

        SourceMappingDto.SchemaRow mapped = service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(publicRow.sourceSchemaId(), e.defaultSchema.getId()));
        assertThat(mapped.status()).isEqualTo(Status.MAPPED);
        assertThat(mapped.defaultSchema()).isTrue();
        assertThat(mapped.confirmedOrder()).isEqualTo(1);
        // 모델 스키마 1:1 : DEFAULT 는 이미 public 의 것
        rejected(() -> service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(archiveRow.sourceSchemaId(), e.defaultSchema.getId())), ErrorCode.SOURCE_MAPPING_CONFLICT);
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(archiveRow.sourceSchemaId(), e.archiveSchema.getId()));
        // 다른 프로젝트의 모델 스키마는 고를 수 없다
        Env other = env("m4c-other");
        rejected(() -> service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(archiveRow.sourceSchemaId(), other.archiveSchema.getId())), ErrorCode.SOURCE_MAPPING_INVALID);

        // TABLE : 후보 (같은 이름, 매핑된 모델 스키마 안). 같은 이름의 tb_user 가 두 스키마에 있어도 각자의 스키마에서만 찾는다
        Map<String, SourceMappingDto.TableRow> rows = tables(e);
        assertThat(rows).containsKeys("public.tb_user", "archive.tb_user", "public.orders").doesNotContainKey("public.v_user");
        assertThat(rows.get("public.tb_user").candidate().id()).isEqualTo(e.table("DEFAULT", "TB_USER").getId());
        assertThat(rows.get("archive.tb_user").candidate().id()).isEqualTo(e.table("ARCHIVE", "TB_USER").getId());
        assertThat(rows.get("public.orders").candidate()).isNull(); // 모델에 같은 이름이 없다
        assertThat(count("TB_SRC_TBL_MPNG", e)).isZero();

        Long sourceUser = rows.get("public.tb_user").sourceTableId();
        Long sourceArchiveUser = rows.get("archive.tb_user").sourceTableId();
        // 잘못된 스키마 : public 의 원본을 ARCHIVE 의 모델 테이블에
        rejected(() -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(sourceUser, e.table("ARCHIVE", "TB_USER").getId())), ErrorCode.SOURCE_MAPPING_INVALID);
        // VIEW 는 매핑하지 않는다
        Long viewId = e.sourceTableId("public", "v_user");
        rejected(() -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(viewId, e.table("DEFAULT", "TB_USER").getId())), ErrorCode.SOURCE_MAPPING_INVALID);
        SourceMappingDto.TableRow userRow = service.mapTable(e.manager, new SourceMappingDto.TableRequest(sourceUser, e.table("DEFAULT", "TB_USER").getId()));
        assertThat(userRow.status()).isEqualTo(Status.MAPPED);
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(sourceArchiveUser, e.table("ARCHIVE", "TB_USER").getId()));
        // 모델 테이블 1:1 : 같은 모델 테이블을 다른 원본에
        rejected(() -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("public.orders").sourceTableId(), e.table("DEFAULT", "TB_USER").getId())),
                ErrorCode.SOURCE_MAPPING_CONFLICT);
        // 이름이 다른 모델 테이블도 사용자가 고르면 매핑된다 (orders → TB_ORDER_X), 같은 원본을 다른 모델로 바꾸면 행 하나가 바뀐다
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("public.orders").sourceTableId(), e.table("DEFAULT", "TB_ORDER_X").getId()));
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("public.orders").sourceTableId(), e.table("DEFAULT", "TB_SPARE").getId()));
        assertThat(count("TB_SRC_TBL_MPNG", e)).isEqualTo(3);
        assertThat(tables(e).get("public.orders").tableName()).isEqualTo("TB_SPARE");

        // 물리 모델은 바뀌지 않았다
        assertThat(modelFingerprint(e.project.getId())).isEqualTo(modelBefore);
        SourceMappingDto.TablePage mappedOnly = service.tables(e.manager, null, "MAPPED", null, 0, 50);
        assertThat(mappedOnly.total()).isEqualTo(3);
        assertThat(mappedOnly.counts()).containsEntry(Status.MAPPED, 3L).containsEntry(Status.UNMAPPED, 1L);
    }

    @Test
    @DisplayName("상태 : 원본 삭제(FULL) → 원본 없음, 같은 이름 재생성 → 재확인, PARTIAL 은 범위 밖을 삭제로 보지 않음, 모델 테이블·스키마 삭제 → 모델 없음, 모델 테이블 이동 → 충돌")
    void statuses() {
        Env e = env("m4c-status");
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        mapAll(e);

        // PARTIAL(archive) 에 public 이 없어도 public 의 매핑은 그대로
        Map<String, List<SourceCollectionSpec.Table>> archiveOnly = new LinkedHashMap<>();
        archiveOnly.put("archive", catalog(true, true).get("archive"));
        collect(e, SourceCollectionSpec.Request.partial(new Namespace(null, Name.of("archive"))), archiveOnly);
        assertThat(tables(e).values()).extracting(SourceMappingDto.TableRow::status).containsOnly(Status.MAPPED, Status.UNMAPPED);

        // FULL 에서 public.tb_user 가 사라짐 → 원본 없음 (매핑 행은 남는다)
        collect(e, SourceCollectionSpec.Request.full(), catalog(false, true));
        Map<String, SourceMappingDto.TableRow> rows = tables(e);
        assertThat(rows.get("public.tb_user").status()).isEqualTo(Status.SOURCE_MISSING);
        assertThat(rows.get("public.tb_user").reason()).isEqualTo(SourceMappingDto.Reason.REMOVED);
        // 같은 이름으로 다시 생김 → 재확인 (자동으로 이어 붙이지 않는다)
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        rows = tables(e);
        assertThat(rows.get("public.tb_user").status()).isEqualTo(Status.RECHECK);
        assertThat(rows.get("public.tb_user").reason()).isEqualTo(SourceMappingDto.Reason.RECREATED);
        // 다시 확정하면 매핑됨 (확정한 생성 차수·반영 순서가 바뀐다)
        SourceMappingDto.TableRow reconfirmed = service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("public.tb_user").sourceTableId(),
                e.table("DEFAULT", "TB_USER").getId()));
        assertThat(reconfirmed.status()).isEqualTo(Status.MAPPED);
        assertThat(reconfirmed.confirmedOrder()).isEqualTo(4);

        // 모델 테이블을 다른 스키마로 옮김 → 충돌 (스키마 매핑과 다름)
        tx.executeWithoutResult(s -> {
            TableInfo moved = tableRepository.findById(e.table("DEFAULT", "TB_ORDER_X").getId()).orElseThrow();
            moved.setSchema(schemaRepository.findById(e.archiveSchema.getId()).orElseThrow());
        });
        assertThat(tables(e).get("public.orders").status()).isEqualTo(Status.CONFLICT);
        assertThat(tables(e).get("public.orders").reason()).isEqualTo(SourceMappingDto.Reason.SCHEMA_MISMATCH);

        // 모델 테이블 삭제 → 모델 없음 (FK ON DELETE SET NULL, 매핑 행은 남아 사용자가 해제한다)
        jdbc.update("delete from TB_COL where TBL_SN = ?", e.table("ARCHIVE", "TB_USER").getId());
        jdbc.update("delete from TB_TBL where TBL_SN = ?", e.table("ARCHIVE", "TB_USER").getId());
        SourceMappingDto.TableRow missingModel = tables(e).get("archive.tb_user");
        assertThat(missingModel.status()).isEqualTo(Status.MODEL_MISSING);
        service.unmapTable(e.manager, missingModel.mappingId());
        assertThat(tables(e).get("archive.tb_user").status()).isEqualTo(Status.UNMAPPED);

        // 모델 스키마 삭제 → 스키마 매핑은 모델 없음, 그 스키마의 TABLE 매핑은 충돌(스키마 매핑이 유효하지 않음)
        jdbc.update("delete from TB_COL where TBL_SN in (select TBL_SN from TB_TBL where SCHM_SN = ?)", e.archiveSchema.getId());
        jdbc.update("delete from TB_TBL where SCHM_SN = ?", e.archiveSchema.getId());
        jdbc.update("delete from TB_SCHM where SCHM_SN = ?", e.archiveSchema.getId());
        assertThat(schemaRow(service.overview(e.manager), "archive").status()).isEqualTo(Status.MODEL_MISSING);
        // 스키마 매핑 해제는 그 스키마의 TABLE 매핑도 해제한다
        Map<String, Object> released = service.unmapSchema(e.manager, schemaRow(service.overview(e.manager), "archive").sourceSchemaId());
        assertThat(released).containsEntry("releasedTables", 0);
        assertThat(schemaRow(service.overview(e.manager), "archive").status()).isEqualTo(Status.UNMAPPED);
    }

    @Test
    @DisplayName("기준 연결 변경 : 매핑이 있으면 확인(acknowledge) 없이는 거절하고, 확인하면 매핑을 남긴 채 바꾼다(다른 연결에 옮기지 않음). 연결 삭제는 그 연결의 매핑·기준 지정을 지운다")
    void baseConnectionAndDeletion() {
        Env e = env("m4c-base");
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        mapAll(e);
        Long second = connectionApi.create(e.manager, new SourceConnectionDto.Request("두 번째", "POSTGRESQL", "db2.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        // 다른 프로젝트의 연결은 기준이 될 수 없다
        Env other = env("m4c-base-other");
        rejected(() -> service.setBase(e.manager, new SourceMappingDto.BaseRequest(other.connection, true)), ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        // 확인 없이 바꾸면 거절 (영향 안내)
        rejected(() -> service.setBase(e.manager, new SourceMappingDto.BaseRequest(second, null)), ErrorCode.SOURCE_MAPPING_CONFLICT);
        SourceMappingDto.Overview switched = service.setBase(e.manager, new SourceMappingDto.BaseRequest(second, true));
        assertThat(switched.base().id()).isEqualTo(second);
        assertThat(switched.reflectedOrder()).isNull(); // 두 번째 연결은 수집 전 : 매핑을 확정할 수 없다
        assertThat(switched.schemas()).isEmpty();
        assertThat(switched.preserved()).singleElement().satisfies(p -> {
            assertThat(p.connectionId()).isEqualTo(e.connection);
            assertThat(p.schemaMappings()).isEqualTo(2);
            assertThat(p.tableMappings()).isEqualTo(3);
        });
        rejected(() -> service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(1L, e.defaultSchema.getId())), ErrorCode.SOURCE_MAPPING_INVALID);
        // 되돌리면 남겨 둔 매핑이 다시 쓰인다 (두 번째 연결에는 매핑이 없어 확인이 필요 없다)
        SourceMappingDto.Overview back = service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        assertThat(back.schemaCounts()).containsEntry(Status.MAPPED, 2L);
        assertThat(back.tableCounts()).containsEntry(Status.MAPPED, 3L);

        // 연결 삭제 : 그 연결의 매핑과 기준 지정이 같은 트랜잭션에서 지워진다 (다른 연결·다른 프로젝트는 그대로)
        connectionApi.delete(e.manager, e.connection);
        assertThat(count("TB_SRC_SCHM_MPNG", e)).isZero();
        assertThat(count("TB_SRC_TBL_MPNG", e)).isZero();
        assertThat(count("TB_PRJ_SRC_CNTN", e)).isZero();
        assertThat(service.overview(e.manager).base()).isNull();
        assertThat(service.overview(e.manager).connections()).extracting(SourceMappingDto.Connection::id).containsExactly(second);
    }

    @Test
    @DisplayName("동시 확정 : 같은 모델 테이블을 두 원본에, 같은 원본을 두 모델 테이블에 동시에 확정해도 1:1 이 깨지지 않는다")
    void concurrentConfirm() throws Exception {
        Env e = env("m4c-race");
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        Map<String, SourceMappingDto.SchemaRow> schemas = new LinkedHashMap<>();
        service.overview(e.manager).schemas().forEach(r -> schemas.put(r.sourceSchemaName(), r));
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(schemas.get("public").sourceSchemaId(), e.defaultSchema.getId()));
        Map<String, SourceMappingDto.TableRow> rows = tables(e);
        Long user = rows.get("public.tb_user").sourceTableId();
        Long orders = rows.get("public.orders").sourceTableId();
        Long target = e.table("DEFAULT", "TB_USER").getId();

        List<Object> results = race(List.of(
                () -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(user, target)),
                () -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(orders, target))));
        assertThat(results).filteredOn(r -> r instanceof SourceMappingDto.TableRow).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof CustomException c && c.getErrorCode() == ErrorCode.SOURCE_MAPPING_CONFLICT).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL_MPNG where PRJ_SN = ? and TBL_SN = ?", Integer.class, e.project.getId(), target)).isEqualTo(1);

        Long free = rows.get("public.orders").sourceTableId();
        jdbc.update("delete from TB_SRC_TBL_MPNG where PRJ_SN = ?", e.project.getId());
        List<Object> same = race(List.of(
                () -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(free, e.table("DEFAULT", "TB_ORDER_X").getId())),
                () -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(free, e.table("DEFAULT", "TB_SPARE").getId()))));
        assertThat(same).allSatisfy(r -> assertThat(r).isInstanceOf(SourceMappingDto.TableRow.class)); // 나중 것이 바꾼다 (잠금으로 차례대로)
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL_MPNG where PRJ_SN = ? and SRC_TBL_SN = ?", Integer.class, e.project.getId(), free)).isEqualTo(1);
    }

    @Test
    @DisplayName("연결 삭제 ↔ 매핑 확정 (T-01) : 확정·기준 지정이 먼저 잠그면 연결 삭제가 그 커밋을 기다렸다가 방금 저장한 매핑·기준 지정까지 지운다. 고아 매핑이 남지 않는다")
    void deleteWaitsForConfirm() throws Exception {
        Env e = env("m4c-del-after");
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(schemaRow(service.overview(e.manager), "public").sourceSchemaId(), e.defaultSchema.getId()));
        Long user = e.sourceTableId("public", "tb_user");

        // 확정 트랜잭션이 저장(flush)까지 마치고 커밋 전에 멈춘 사이에 연결을 지운다
        Object[] confirmed = heldThenRun(() -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(user, e.table("DEFAULT", "TB_USER").getId())),
                () -> connectionService.delete(e.manager, e.connection));
        assertThat(confirmed[0]).isInstanceOf(SourceMappingDto.TableRow.class);
        assertThat(confirmed[1]).isInstanceOf(Integer.class); // 삭제도 성공한다 (확정이 커밋된 뒤에)
        assertNoOrphans(e);
        assertThat(count("TB_SRC_TBL_MPNG", e)).isZero();

        // 기준 연결 지정이 먼저면 : 연결 삭제가 그 지정까지 지운다 (지운 연결을 기준으로 가리키는 프로젝트가 남지 않는다)
        Env b = env("m4c-del-base");
        Long second = connectionService.create(b.manager, new SourceConnectionDto.Request("두 번째", "POSTGRESQL", "db2.internal", null, "app", null, "reader", null, null,
                null, null, null)).id();
        service.setBase(b.manager, new SourceMappingDto.BaseRequest(second, null));
        Object[] based = heldThenRun(() -> service.setBase(b.manager, new SourceMappingDto.BaseRequest(b.connection, true)),
                () -> connectionService.delete(b.manager, b.connection));
        assertThat(based[0]).isInstanceOf(SourceMappingDto.Overview.class);
        assertThat(based[1]).isInstanceOf(Integer.class);
        assertNoOrphans(b);
        assertThat(service.overview(b.manager).base()).isNull();
    }

    @Test
    @DisplayName("연결 삭제 ↔ 매핑 확정 (T-02) : 연결 삭제가 먼저 잠그면 확정·기준 지정은 그 커밋을 기다렸다가 거절된다 (지운 연결로 매핑이 생기지 않는다)")
    void confirmWaitsForDelete() throws Exception {
        Env e = env("m4c-del-before");
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(schemaRow(service.overview(e.manager), "public").sourceSchemaId(), e.defaultSchema.getId()));
        Long user = e.sourceTableId("public", "tb_user");

        Object[] results = heldThenRun(() -> connectionService.delete(e.manager, e.connection),
                () -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(user, e.table("DEFAULT", "TB_USER").getId())));
        assertThat(results[0]).isInstanceOf(Integer.class);
        assertRejected(results[1], ErrorCode.SOURCE_CONNECTION_NOT_FOUND, ErrorCode.SOURCE_MAPPING_INVALID);
        assertNoOrphans(e);

        Env b = env("m4c-del-before-base");
        Object[] based = heldThenRun(() -> connectionService.delete(b.manager, b.connection),
                () -> service.setBase(b.manager, new SourceMappingDto.BaseRequest(b.connection, null)));
        assertRejected(based[1], ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        assertNoOrphans(b);
        assertThat(count("TB_PRJ_SRC_CNTN", b)).isZero();
    }

    @Test
    @DisplayName("연결 삭제 순서 (T-03·T-04·T-05) : 확정 후 삭제는 매핑까지 지우고, 삭제 후 확정·기준 지정은 거절되며, 삭제가 실패(롤백)하면 연결·매핑·기준 지정이 모두 남는다")
    void deletionOrderAndRollback() {
        Env e = env("m4c-del-order");
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        mapAll(e);
        Long user = e.sourceTableId("public", "tb_user");

        // T-05 : 같은 트랜잭션의 뒤 작업이 실패하면 매핑 정리도 함께 되돌아간다
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            connectionService.delete(e.manager, e.connection);
            throw new IllegalStateException("삭제 뒤 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CNTN where SRC_CNTN_SN = ?", Integer.class, e.connection)).isEqualTo(1);
        assertThat(count("TB_SRC_SCHM_MPNG", e)).isEqualTo(2);
        assertThat(count("TB_SRC_TBL_MPNG", e)).isEqualTo(3);
        assertThat(count("TB_PRJ_SRC_CNTN", e)).isEqualTo(1);

        // T-03 : 확정한 뒤 삭제하면 매핑·기준 지정까지 지운다
        connectionService.delete(e.manager, e.connection);
        assertThat(count("TB_SRC_SCHM_MPNG", e)).isZero();
        assertThat(count("TB_SRC_TBL_MPNG", e)).isZero();
        assertThat(count("TB_PRJ_SRC_CNTN", e)).isZero();
        // T-04 : 지운 연결로는 확정·기준 지정이 안 된다
        rejected(() -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(user, e.table("DEFAULT", "TB_USER").getId())), ErrorCode.SOURCE_MAPPING_INVALID);
        rejected(() -> service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null)), ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        assertNoOrphans(e);
    }

    @Test
    @DisplayName("기준 연결 변경 ↔ TABLE 확정 (T-07) : 어느 쪽이 먼저든 다른 연결의 원본이 섞이지 않는다 (확정은 그 연결의 매핑으로 남거나 거절된다)")
    void baseChangeWhileConfirming() throws Exception {
        Env e = env("m4c-base-race");
        Long second = connectionService.create(e.manager, new SourceConnectionDto.Request("두 번째", "POSTGRESQL", "db2.internal", null, "app", null, "reader", null, null,
                null, null, null)).id();
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(schemaRow(service.overview(e.manager), "public").sourceSchemaId(), e.defaultSchema.getId()));
        Long user = e.sourceTableId("public", "tb_user");
        Long orders = e.sourceTableId("public", "orders");

        // 확정이 먼저 : 기준 변경이 기다렸다가 바꾼다. 확정한 매핑은 첫 연결의 매핑으로 남는다
        Object[] first = heldThenRun(() -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(user, e.table("DEFAULT", "TB_USER").getId())),
                () -> service.setBase(e.manager, new SourceMappingDto.BaseRequest(second, true)));
        assertThat(first[0]).isInstanceOf(SourceMappingDto.TableRow.class);
        assertThat(((SourceMappingDto.Overview) first[1]).base().id()).isEqualTo(second);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL_MPNG where PRJ_SN = ? and SRC_CNTN_SN = ?", Integer.class, e.project.getId(), e.connection))
                .isEqualTo(1);

        // 기준 변경이 먼저 : 첫 연결의 원본으로 하던 확정은 거절된다 (바뀐 기준 연결에 첫 연결의 원본이 매핑되지 않는다)
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        Object[] second2 = heldThenRun(() -> service.setBase(e.manager, new SourceMappingDto.BaseRequest(second, true)),
                () -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(orders, e.table("DEFAULT", "TB_ORDER_X").getId())));
        assertThat(second2[0]).isInstanceOf(SourceMappingDto.Overview.class);
        assertRejected(second2[1], ErrorCode.SOURCE_MAPPING_CONFLICT, ErrorCode.SOURCE_MAPPING_INVALID);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL_MPNG where PRJ_SN = ? and SRC_CNTN_SN = ?", Integer.class, e.project.getId(), second)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL_MPNG where PRJ_SN = ?", Integer.class, e.project.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("스키마 매핑 해제 ↔ TABLE 확정 (T-08) : 해제가 먼저면 확정이 거절되고, 확정이 먼저면 해제가 방금 확정한 TABLE 매핑까지 함께 해제한다")
    void unmapSchemaWhileConfirming() throws Exception {
        Env e = env("m4c-unmap-race");
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        Long publicSchema = schemaRow(service.overview(e.manager), "public").sourceSchemaId();
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId()));
        Long user = e.sourceTableId("public", "tb_user");

        Object[] unmapFirst = heldThenRun(() -> service.unmapSchema(e.manager, publicSchema),
                () -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(user, e.table("DEFAULT", "TB_USER").getId())));
        assertThat(unmapFirst[0]).isInstanceOf(Map.class);
        assertRejected(unmapFirst[1], ErrorCode.SOURCE_MAPPING_INVALID);
        assertThat(count("TB_SRC_TBL_MPNG", e)).isZero();

        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId()));
        Object[] confirmFirst = heldThenRun(() -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(user, e.table("DEFAULT", "TB_USER").getId())),
                () -> service.unmapSchema(e.manager, publicSchema));
        assertThat(confirmFirst[0]).isInstanceOf(SourceMappingDto.TableRow.class);
        assertThat(((Map<?, ?>) confirmFirst[1]).get("releasedTables")).isEqualTo(1);
        assertThat(count("TB_SRC_TBL_MPNG", e)).isZero();
        assertThat(count("TB_SRC_SCHM_MPNG", e)).isZero();
    }

    @Test
    @DisplayName("권한 : 조회는 VIEWER 이상, 변경은 프로젝트 관리자(USER 역할 포함)·ADMIN 만. 관리자가 아닌 MANAGER·USER 는 거절, VIEWER 는 역할에서 거절. 다른 프로젝트의 매핑 ID 는 없는 것과 같다")
    void permissions() {
        Env e = env("m4c-auth");
        Account manager = account("m4c-mgr", "ROLE_MANAGER");
        Account user = account("m4c-usr", "ROLE_USER");
        Account viewer = account("m4c-vwr", "ROLE_VIEWER");
        Account admin = account("m4c-adm", "ROLE_ADMIN");
        for (Account member : List.of(manager, user, viewer, admin)) {
            projectMemberRepository.save(ProjectMember.builder().project(e.project).account(member).isActive(true).build());
        }
        // 프로젝트 관리자(USER 역할)
        AccountDto owner = login(e, e.owner, "ROLE_USER");
        api.setBase(owner, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        Long publicSchema = schemaRow(service.overview(e.manager), "public").sourceSchemaId();

        AccountDto managerDto = login(e, manager, "ROLE_MANAGER");
        rejected(() -> api.mapSchema(managerDto, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId())), ErrorCode.EXECUTION_PERMISSION_DENIED);
        AccountDto userDto = login(e, user, "ROLE_USER");
        rejected(() -> api.mapSchema(userDto, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId())), ErrorCode.EXECUTION_PERMISSION_DENIED);
        AccountDto viewerDto = login(e, viewer, "ROLE_VIEWER");
        assertThatThrownBy(() -> api.mapSchema(viewerDto, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId()))).isInstanceOf(AccessDeniedException.class);
        assertThat(api.overview(viewerDto).getBody().base().id()).isEqualTo(e.connection); // 조회는 된다
        assertThat(api.tables(viewerDto, null, null, null, 0, 10).getBody().total()).isEqualTo(4);

        AccountDto adminDto = login(e, admin, "ROLE_ADMIN");
        assertThat(api.mapSchema(adminDto, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId())).getBody().status()).isEqualTo(Status.MAPPED);
        SourceMappingDto.TableRow mapped = api.mapTable(login(e, e.owner, "ROLE_USER"), new SourceMappingDto.TableRequest(e.sourceTableId("public", "tb_user"),
                e.table("DEFAULT", "TB_USER").getId())).getBody();

        // 다른 프로젝트의 관리자 : 이 프로젝트의 매핑 ID 로 해제할 수 없고, 이 프로젝트의 원본·모델로 확정할 수 없다
        Env other = env("m4c-auth-other");
        service.setBase(other.manager, new SourceMappingDto.BaseRequest(other.connection, null));
        rejected(() -> service.unmapTable(other.manager, mapped.mappingId()), ErrorCode.SOURCE_MAPPING_INVALID);
        rejected(() -> service.mapSchema(other.manager, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId())), ErrorCode.SOURCE_MAPPING_INVALID);
        assertThat(count("TB_SRC_TBL_MPNG", e)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 예제

    /** public : tb_user, orders, v_user(VIEW). archive : tb_user. audit : log (모델에 같은 이름의 스키마가 없다) */
    private static Map<String, List<SourceCollectionSpec.Table>> catalog(boolean withPublicUser, boolean withArchive) {
        Map<String, List<SourceCollectionSpec.Table>> schemas = new LinkedHashMap<>();
        List<SourceCollectionSpec.Table> publicTables = new ArrayList<>();
        if (withPublicUser) publicTables.add(table("tb_user", SourceTableType.TABLE));
        publicTables.add(table("orders", SourceTableType.TABLE));
        publicTables.add(table("v_user", SourceTableType.VIEW));
        schemas.put("public", publicTables);
        if (withArchive) schemas.put("archive", List.of(table("tb_user", SourceTableType.TABLE)));
        schemas.put("audit", List.of(table("log", SourceTableType.TABLE)));
        return schemas;
    }

    private static SourceCollectionSpec.Table table(String name, SourceTableType type) {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of(name), type, null),
                List.of(new SourceCatalogSpec.Column(Name.of("id"), 1, "bigint", null, null, null, false, null, null)),
                type == SourceTableType.TABLE ? List.of(new SourceCatalogSpec.Constraint(name + "_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))) : List.of(),
                List.of(), List.of());
    }

    // ------------------------------------------------------------------ 준비 · 확인

    private final class Env {
        final Project project;
        final Account owner;
        final AccountDto manager;
        final Long connection;
        final SchemaInfo defaultSchema;
        final SchemaInfo archiveSchema;
        final Map<String, TableInfo> tables = new LinkedHashMap<>();

        Env(Project project, Account owner, AccountDto manager, Long connection, SchemaInfo defaultSchema, SchemaInfo archiveSchema) {
            this.project = project;
            this.owner = owner;
            this.manager = manager;
            this.connection = connection;
            this.defaultSchema = defaultSchema;
            this.archiveSchema = archiveSchema;
        }

        TableInfo table(String schema, String name) {
            return tables.get(schema + "." + name);
        }

        Long sourceTableId(String schema, String name) {
            return jdbc.queryForObject("select t.SRC_TBL_SN from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = ? "
                    + "and t.TBL_NM = ?", Long.class, connection, schema, name);
        }
    }

    /** 프로젝트(관리자 = 소유자, DBA 역할) + 모델 스키마 DEFAULT·ARCHIVE + 모델 테이블 (DEFAULT : TB_USER·TB_ORDER_X·TB_SPARE, ARCHIVE : TB_USER) + PostgreSQL 연결 */
    private Env env(String name) {
        Account owner = account("own", "ROLE_DBA");
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_DBA");
        SchemaInfo defaultSchema = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("DEFAULT").isDefault(true).build()));
        SchemaInfo archive = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("ARCHIVE").isDefault(false).build()));
        Long connection = connectionApi.create(manager, new SourceConnectionDto.Request("원본", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        Env env = new Env(project, owner, manager, connection, defaultSchema, archive);
        for (String[] t : new String[][]{{"DEFAULT", "TB_USER"}, {"DEFAULT", "TB_ORDER_X"}, {"DEFAULT", "TB_SPARE"}, {"ARCHIVE", "TB_USER"}}) {
            SchemaInfo schema = t[0].equals("DEFAULT") ? defaultSchema : archive;
            env.tables.put(t[0] + "." + t[1], tx.execute(s -> tableRepository.save(TableInfo.builder().projectId(project.getId()).tableName(t[1]).tableKorName(t[1])
                    .tableDescription(t[1]).isMasterTable(false).sortOrder(1).isApproval(false).schema(schema).build())));
        }
        return env;
    }

    private void mapAll(Env e) {
        SourceMappingDto.Overview overview = service.overview(e.manager);
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(schemaRow(overview, "public").sourceSchemaId(), e.defaultSchema.getId()));
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(schemaRow(overview, "archive").sourceSchemaId(), e.archiveSchema.getId()));
        Map<String, SourceMappingDto.TableRow> rows = tables(e);
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("public.tb_user").sourceTableId(), e.table("DEFAULT", "TB_USER").getId()));
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("public.orders").sourceTableId(), e.table("DEFAULT", "TB_ORDER_X").getId()));
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("archive.tb_user").sourceTableId(), e.table("ARCHIVE", "TB_USER").getId()));
    }

    private void collect(Env e, SourceCollectionSpec.Request request, Map<String, List<SourceCollectionSpec.Table>> schemas) {
        List<SourceCollectionSpec.Schema> result = new ArrayList<>();
        schemas.forEach((schema, tables) -> result.add(SourceCollectionSpec.Schema.completed(null, Name.of(schema), tables)));
        ExecutionContext context = ExecutionContext.user(e.manager);
        collections.apply(context, collections.start(context, e.connection, request).jobId(), new SourceCollectionSpec.Result(result));
    }

    private Map<String, SourceMappingDto.TableRow> tables(Env e) {
        Map<String, SourceMappingDto.TableRow> rows = new LinkedHashMap<>();
        service.tables(e.manager, null, null, null, 0, 500).rows().forEach(r -> rows.put(r.sourceSchemaName() + "." + r.sourceTableName(), r));
        return rows;
    }

    private static SourceMappingDto.SchemaRow schemaRow(SourceMappingDto.Overview overview, String name) {
        return overview.schemas().stream().filter(r -> name.equals(r.sourceSchemaName())).findFirst().orElseThrow();
    }

    private int count(String table, Env e) {
        return jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, e.project.getId());
    }

    /** 물리 모델의 지문 : 스키마·테이블·컬럼의 이름·소속·승인 상태와 모델 변경 이력 수 */
    private String modelFingerprint(Long projectId) {
        return String.join("|", jdbc.queryForList("select SCHM_SN || ':' || SCHM_NM from TB_SCHM where PRJ_SN = ? order by SCHM_SN", String.class, projectId))
                + "#" + String.join("|", jdbc.queryForList("select TBL_SN || ':' || SCHM_SN || ':' || TBL_NM || ':' || APRV_YN from TB_TBL where PRJ_SN = ? order by TBL_SN",
                String.class, projectId))
                + "#" + jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ?", Integer.class, projectId)
                + "#" + jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, projectId);
    }

    private AccountDto login(Env e, Account account, String role) {
        return SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(account.getId()), role);
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    /** 두 호출을 동시에 시작한다. 결과 또는 예외를 순서대로 */
    private static List<Object> race(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (CustomException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) results.add(future.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * first 를 한 트랜잭션에서 실행하고 커밋 전에 멈춘 사이에 then 을 시작한다. then 이 DB 잠금을 기다리기 시작한 것을 확인한 뒤(잠금 대기가 없으면 실패)
     * first 를 커밋시키고 두 결과(또는 예외)를 돌려준다. Thread.sleep 으로 순서를 짐작하지 않고 DB 가 알려 주는 잠금 대기로 순서를 정한다.
     */
    private Object[] heldThenRun(Callable<Object> first, Callable<Object> then) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Object> holder = pool.submit(caught(() -> tx.execute(s -> {
                Object result;
                try {
                    result = first.call();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                held.countDown();
                await(release);
                return result;
            })));
            await(held);
            Future<Object> waiter = pool.submit(caught(then));
            try {
                awaitLockWait(waiter);
            } finally {
                release.countDown();
            }
            return new Object[]{holder.get(30, TimeUnit.SECONDS), waiter.get(30, TimeUnit.SECONDS)};
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** waiter 가 다른 트랜잭션의 잠금을 기다리기 시작할 때까지 기다린다 (PostgreSQL pg_stat_activity, H2 SESSIONS.BLOCKER_ID). 기다리지 않고 끝나면 실패한다 */
    private void awaitLockWait(Future<?> waiter) throws InterruptedException {
        String sql = postgres()
                ? "select query from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select EXECUTING_STATEMENT from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertThat(waiter.isDone()).as("잠금을 기다리지 않고 끝났다 (경합이 일어나지 않았다)").isFalse();
            if (!jdbc.queryForList(sql, String.class).isEmpty()) return;
            Thread.sleep(5);
        }
        throw new AssertionError("잠금 대기가 생기지 않았다");
    }

    private boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("기다리는 신호가 오지 않았다");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 예외도 결과로 돌려준다 (트랜잭션 안에서 감싼 예외는 풀어서) */
    private static Callable<Object> caught(Callable<Object> body) {
        return () -> {
            try {
                return body.call();
            } catch (CustomException e) {
                return e;
            } catch (IllegalStateException e) {
                if (e.getCause() instanceof CustomException c) return c;
                throw e;
            }
        };
    }

    private static void assertRejected(Object result, ErrorCode... expected) {
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isIn((Object[]) expected);
    }

    /** 지워진 원본 연결을 가리키는 매핑·기준 지정이 없다 */
    private void assertNoOrphans(Env e) {
        for (String table : List.of("TB_PRJ_SRC_CNTN", "TB_SRC_SCHM_MPNG", "TB_SRC_TBL_MPNG")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " m where m.PRJ_SN = ? and not exists (select 1 from TB_SRC_CNTN c where c.SRC_CNTN_SN = m.SRC_CNTN_SN)",
                    Integer.class, e.project.getId())).as(table + " 고아 행").isZero();
        }
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(expected);
    }
}
