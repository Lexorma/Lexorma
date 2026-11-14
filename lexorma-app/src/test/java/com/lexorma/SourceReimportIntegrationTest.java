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
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Item;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Judgement;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Reason;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.service.SourceImportService;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원본 메타데이터 선택 재가져오기 (M-4f, docs/source-reimport-m4f.md) : 한 번 가져온 원본이 바뀐 뒤 다시 가져올 때, 마지막으로 가져온 값(B)·지금 원본(S)·지금 모델(M) 로
 * 원본만 바뀐 것(고를 수 있음)·모델에서 바꾼 것·둘 다 바뀐 것(고를 수 없음)·기준값 없음을 나누고, 고른 속성만 반영하는지 본다. 원본 DB 에 접속하지 않는다 (수집 결과를 직접 넘긴다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceReimportIntegrationTest {

    @Autowired SourceImportService service;
    @Autowired ModelVersionService versions;
    @Autowired SourceMappingService mapping;
    @Autowired SourceCollectionService collections;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
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
    @DisplayName("재가져오기 판정 (F-01~F-09, F-13, F-17) : 원본만 바뀐 속성은 고를 수 있고, 모델에서 바꾼 속성·둘 다 바뀐 속성은 고를 수 없다. 원본에서 없어진 컬럼은 알리기만 하고, 새 객체는 M-4e 그대로")
    void judgements() {
        Env e = reimported("m4f-judge");
        SourceImportDto.TableCandidates member = service.candidates(e.manager, e.connection, null, key(e, "member"));
        assertThat(member.lastImport()).as("전에 가져온 TABLE : 재가져오기").isNotNull();
        Map<String, Item> items = items(member);

        assertSafe(items.get("TABLE:COMMENT"), "회원");                         // F-01
        assertSafe(items.get("COLUMN:user_name:TYPE"), "VARCHAR(100)");        // F-02
        assertSafe(items.get("COLUMN:email:NULLABLE"), "NULL");               // F-03
        assertSafe(items.get("COLUMN:status:DEFAULT"), "'A'");                // F-04
        assertSafe(items.get("COLUMN:email:COMMENT"), "이메일");               // F-05 (업무 설명과는 비교하지 않는다)

        // F-17 : 원본 'X' → 'Y', 모델 'X' → 'Z' : 둘 다 바뀌었다
        Item grade = items.get("COLUMN:grade:DEFAULT");
        assertThat(grade.judgement()).isEqualTo(Judgement.BOTH_CHANGED);
        assertThat(grade.reason()).isEqualTo(Reason.BOTH_CHANGED);
        assertThat(grade.selectable()).isFalse();
        assertThat(grade.baseline()).isEqualTo("'X'");
        assertThat(grade.before()).isEqualTo("'Z'");
        // 원본 그대로, 모델만 INTEGER → BIGINT : 되돌리지 않는다
        Item age = items.get("COLUMN:age:TYPE");
        assertThat(age.judgement()).isEqualTo(Judgement.MODEL_EDITED);
        assertThat(age.reason()).isEqualTo(Reason.MODEL_EDITED);
        assertThat(age.selectable()).isFalse();

        // F-09 : 원본에서 지운 컬럼은 알림만 (고를 수 없고 모델은 그대로)
        assertThat(items.get("COLUMN:NICK:REMOVED")).isNotNull().extracting(Item::reason, Item::selectable).containsExactly(Reason.REMOVED_IN_SOURCE, false);
        // F-06·F-07 : 새 컬럼·CHECK·인덱스·FK 는 M-4e 의 추가 그대로 (판정 대상 아님)
        assertThat(items.get("COLUMN:last_login_on:ADD")).extracting(Item::selectable, Item::judgement).containsExactly(true, null);
        assertThat(items.get("CHECK:member_age_check").selectable()).isTrue();
        assertThat(items.get("INDEX:ix_member_status").selectable()).isTrue();
        assertThat(items.get("FOREIGN_KEY:member_team_fk").dependsOn()).contains("COLUMN:team_id:ADD");
        // F-08 : 기존 UNIQUE 의 컬럼이 바뀌었다 : 교체가 필요해 가져오지 않는다 (조용히 빠지지 않는다)
        assertThat(items.get("UNIQUE:member_email_key")).isNotNull().extracting(Item::reason).isEqualTo(Reason.REPLACE_REQUIRED);
        // F-13 : IDENTITY 컬럼은 만들지 않는다
        assertThat(items.get("COLUMN:seq:ADD").reason()).isEqualTo(Reason.UNSUPPORTED_ATTRIBUTE);

        // F-12 : 승인된 컬럼은 원본만 바뀌어도 바꾸지 않는다 (판정은 그대로 보여 준다)
        approveColumn(e, "EMAIL", true);
        Item approved = items(service.candidates(e.manager, e.connection, null, key(e, "member"))).get("COLUMN:email:NULLABLE");
        assertThat(approved.reason()).isEqualTo(Reason.APPROVED);
        assertThat(approved.judgement()).isEqualTo(Judgement.SOURCE_CHANGED);
        approveColumn(e, "EMAIL", false);
    }

    @Test
    @DisplayName("재가져오기 반영 (F-14, F-15, F-16, F-22) : 고른 속성만 바뀌고 모델에서 바꾼 값·업무 설명은 그대로, 직전 모델 버전·IMPORT 이력. 같은 원본을 다시 가져오면 바뀔 것이 없다")
    void applyChosen() {
        Env e = reimported("m4f-apply");
        Long pid = e.project.getId();
        int executionsBefore = count("TB_SRC_IMPRT_EXCN", pid);
        int versionsBefore = count("TB_MDL_VER", pid);
        List<String> chosen = List.of("TABLE:COMMENT", "COLUMN:user_name:TYPE", "COLUMN:email:COMMENT", "COLUMN:status:DEFAULT", "COLUMN:last_login_on:ADD",
                "CHECK:member_age_check", "INDEX:ix_member_status", "COLUMN:team_id:ADD", "FOREIGN_KEY:member_team_fk");
        SourceImportDto.Result result = apply(e, Map.of("member", chosen));

        assertThat(column(pid, "USER_NAME", "MAX_LEN")).isEqualTo(200);
        assertThat(column(pid, "EMAIL", "SRC_ANTN_CN")).isEqualTo("회원 이메일");
        assertThat(column(pid, "EMAIL", "COL_EXPLN")).as("업무 설명은 그대로").isEqualTo("업무 설명");
        assertThat(column(pid, "EMAIL", "NULL_ALWNC_YN")).as("고르지 않은 NULL 허용은 그대로").isIn(true, "Y", 1);
        assertThat(column(pid, "GRADE", "BSC_VL")).as("모델에서 바꾼 값은 그대로").isEqualTo("'Z'");
        assertThat(column(pid, "AGE", "DATA_TYPE_NM")).isEqualTo("BIGINT");
        assertThat(column(pid, "NICK", "COL_NM")).as("원본에서 지운 컬럼도 남는다").isEqualTo("NICK");
        assertThat(column(pid, "STATUS", "BSC_VL")).isEqualTo("'B'");
        assertThat(column(pid, "LAST_LOGIN_ON", "DATA_TYPE_NM")).isEqualTo("DATE");
        assertThat(jdbc.queryForObject("select SRC_ANTN_CN from TB_TBL where PRJ_SN = ? and TBL_NM = 'MEMBER'", String.class, pid)).isEqualTo("회원 정보");
        assertThat(count("TB_TBL_CNSTRT", pid)).isEqualTo(2); // UNIQUE(처음 가져오기) + 새 CHECK
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where PRJ_SN = ? and REL_NM = 'MEMBER_TEAM_FK'", Integer.class, pid)).isEqualTo(1);

        // F-22 : 직전 버전(가져오기 전 길이 100)·실행 이력·IMPORT 이력이 같은 요청으로 이어진다
        assertThat(count("TB_SRC_IMPRT_EXCN", pid)).isEqualTo(executionsBefore + 1);
        assertThat(count("TB_MDL_VER", pid)).isEqualTo(versionsBefore + 1);
        String snapshot = jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, result.modelVersionId());
        assertThat(snapshot).contains("\"columnName\":\"USER_NAME\"").contains("\"maxLength\":100").doesNotContain("LAST_LOGIN_ON");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ? and CHG_SRC_CD = 'IMPORT'", Integer.class, pid,
                result.changeGroupId())).isGreaterThan(0);

        // 다시 보면 : 반영한 속성은 차이가 없고, 마지막 가져오기는 방금 것. 고르지 않은 NULL 허용은 여전히 원본만 바뀐 것
        SourceImportDto.TableCandidates after = service.candidates(e.manager, e.connection, null, key(e, "member"));
        assertThat(after.lastImport().executionId()).isEqualTo(result.executionId());
        Map<String, Item> items = items(after);
        assertThat(items).doesNotContainKeys("COLUMN:user_name:TYPE", "TABLE:COMMENT", "COLUMN:status:DEFAULT", "COLUMN:last_login_on:ADD");
        assertSafe(items.get("COLUMN:email:NULLABLE"), "NULL");

        // F-15·F-16 : 같은 항목을 다시 고르면 비교에 없는 항목이라 적용할 수 없고, 모델·버전·이력은 그대로
        SourceImportDto.Plan again = service.plan(e.manager, request(e, Map.of("member", List.of("COLUMN:user_name:TYPE"))));
        assertThat(again.applicable()).isFalse();
        assertThatThrownBy(() -> service.apply(e.manager, confirm(e, again, Map.of("member", List.of("COLUMN:user_name:TYPE")))))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.SOURCE_IMPORT_INVALID);
        assertThat(count("TB_SRC_IMPRT_EXCN", pid)).isEqualTo(executionsBefore + 1);
        assertThat(count("TB_MDL_VER", pid)).isEqualTo(versionsBefore + 1);
    }

    @Test
    @DisplayName("기준값 없음 (F-18) : 가져온 적 없는(직접 만든) 모델 TABLE 은 안전하다고 표시하지 않고, 명시적으로 고르면 M-4e 처럼 반영한다. 그 뒤의 원본 변경부터는 기준값으로 판정한다")
    void noBaseline() {
        Env e = env("m4f-nobase");
        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, Map.of("public", List.of(legacy("character varying(50)"))));
        tx.executeWithoutResult(s -> {
            TableInfo legacy = table(e, "LEGACY");
            column(legacy, "ID", "INTEGER", null, false, true, 1);
            column(legacy, "NICK", "VARCHAR", 30, true, false, 2);
        });
        mapping.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(mapping.overview(e.manager).schemas().getFirst().sourceSchemaId(), e.schema.getId()));
        mapping.mapTable(e.manager, new SourceMappingDto.TableRequest(e.sourceTableId("legacy"), e.tables.get("LEGACY").getId()));

        SourceImportDto.TableCandidates first = service.candidates(e.manager, e.connection, null, key(e, "legacy"));
        assertThat(first.lastImport()).isNull();
        Item nick = items(first).get("COLUMN:nick:TYPE");
        assertThat(nick.judgement()).isEqualTo(Judgement.NO_BASELINE);
        assertThat(nick.baseline()).isNull();
        assertThat(nick.selectable()).as("미승인 객체는 M-4e 처럼 명시적으로 고를 수 있다").isTrue();
        apply(e, Map.of("legacy", List.of("COLUMN:nick:TYPE")));
        assertThat(column(e.project.getId(), "NICK", "MAX_LEN")).isEqualTo(50);

        collect(e, Map.of("public", List.of(legacy("character varying(80)"))));
        Item next = items(service.candidates(e.manager, e.connection, null, key(e, "legacy"))).get("COLUMN:nick:TYPE");
        assertThat(next.judgement()).isEqualTo(Judgement.SOURCE_CHANGED);
        assertThat(next.baseline()).isEqualTo("VARCHAR(50)");
    }

    @Test
    @DisplayName("미리보기 뒤 변경 (F-19~F-21)·실패 롤백 (F-23)·재전송 (F-24)·원본 재생성 (F-10) : 409 또는 오류이고 모델·버전·실행 이력은 그대로")
    void staleAndRollback() {
        Env e = reimported("m4f-stale");
        Long pid = e.project.getId();
        Map<String, List<String>> status = Map.of("member", List.of("COLUMN:status:DEFAULT"));

        // F-19 : 미리보기 뒤 다른 사용자가 같은 속성을 바꿨다
        SourceImportDto.Plan plan = service.plan(e.manager, request(e, status));
        String before = fingerprint(pid);
        tx.executeWithoutResult(s -> columnRepository.findById(columnId(pid, "STATUS")).orElseThrow().setDefaultValue("'Q'"));
        String edited = fingerprint(pid);
        assertThat(edited).isNotEqualTo(before);
        rejected(() -> service.apply(e.manager, confirm(e, plan, status)), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(fingerprint(pid)).isEqualTo(edited);

        // F-20 : 미리보기 뒤 원본을 다시 수집했다
        Map<String, List<String>> comment = Map.of("member", List.of("TABLE:COMMENT"));
        SourceImportDto.Plan beforeCollect = service.plan(e.manager, request(e, comment));
        collect(e, Map.of("public", v2()));
        String collected = fingerprint(pid);
        rejected(() -> service.apply(e.manager, confirm(e, beforeCollect, comment)), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(fingerprint(pid)).isEqualTo(collected);

        // F-23 : 반영 중 실패(직전 버전 이름이 이미 있다) → 전체 롤백
        SourceImportDto.Plan failing = service.plan(e.manager, request(e, comment));
        versions.create(e.manager, new ModelVersionDto.Create("IMPORT-" + failing.requestId(), null));
        String withVersion = fingerprint(pid);
        rejected(() -> service.apply(e.manager, confirm(e, failing, comment)), ErrorCode.SAVED_DATA_EXISTS);
        assertThat(fingerprint(pid)).isEqualTo(withVersion);

        // F-24 : 같은 요청을 두 번 보내면 한 번만 반영된다
        SourceImportDto.Plan once = service.plan(e.manager, request(e, comment));
        service.apply(e.manager, confirm(e, once, comment));
        String applied = fingerprint(pid);
        rejected(() -> service.apply(e.manager, confirm(e, once, comment)), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(fingerprint(pid)).isEqualTo(applied);

        // F-21 : 미리보기 뒤 매핑을 풀었다
        Map<String, List<String>> nullable = Map.of("member", List.of("COLUMN:email:NULLABLE"));
        SourceImportDto.Plan beforeUnmap = service.plan(e.manager, request(e, nullable));
        Long mappingId = jdbc.queryForObject("select SRC_TBL_MPNG_SN from TB_SRC_TBL_MPNG where PRJ_SN = ? and SRC_TBL_SN = ?", Long.class, pid, e.sourceTableId("member"));
        mapping.unmapTable(e.manager, mappingId);
        String unmapped = fingerprint(pid);
        rejected(() -> service.apply(e.manager, confirm(e, beforeUnmap, nullable)), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(fingerprint(pid)).isEqualTo(unmapped);

        // F-10 : 원본 TABLE 이 없어졌다가 같은 이름으로 다시 생기면 기존 매핑을 그대로 쓰지 않는다 (재확인)
        List<SourceCollectionSpec.Table> withoutTeam = new ArrayList<>(v2());
        withoutTeam.removeIf(t -> t.table().name().value().equals("team"));
        collect(e, Map.of("public", withoutTeam));
        collect(e, Map.of("public", v2()));
        assertThat(service.candidates(e.manager, e.connection, null, key(e, "team")).tableReason()).isEqualTo(Reason.RECHECK);
    }

    // ------------------------------------------------------------------ 준비

    /**
     * 수집 v1 → member·team 을 새 TABLE 로 가져온다 (모든 항목) → 모델에서 GRADE 기본값('Z')·AGE 타입(BIGINT)·EMAIL 업무 설명을 바꾼다 → 수집 v2
     * (member COMMENT, user_name 길이, email NOT NULL·COMMENT, status·grade 기본값, nick 삭제, last_login_on·team_id·seq(IDENTITY) 추가, CHECK·인덱스·FK 추가, UNIQUE 컬럼 변경)
     */
    private Env reimported(String name) {
        Env e = env(name);
        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, Map.of("public", v1()));
        mapping.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(mapping.overview(e.manager).schemas().getFirst().sourceSchemaId(), e.schema.getId()));
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (String table : List.of("team", "member")) {
            all.put(table, items(service.candidates(e.manager, e.connection, null, key(e, table))).values().stream().filter(Item::selectable).map(Item::id).toList());
        }
        assertThat(service.candidates(e.manager, e.connection, null, key(e, "member")).lastImport()).as("처음 가져오기").isNull();
        apply(e, all);
        Long pid = e.project.getId();
        tx.executeWithoutResult(s -> {
            columnRepository.findById(columnId(pid, "GRADE")).orElseThrow().setDefaultValue("'Z'");
            columnRepository.findById(columnId(pid, "AGE")).orElseThrow().setDataType("BIGINT");
            columnRepository.findById(columnId(pid, "EMAIL")).orElseThrow().setColumnDesc("업무 설명");
        });
        collect(e, Map.of("public", v2()));
        return e;
    }

    private static List<SourceCollectionSpec.Table> v1() {
        return List.of(team(), new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("member"), SourceTableType.TABLE, "회원"),
                List.of(col("id", 1, "integer", null, false, null, null), col("user_name", 2, "character varying(100)", 100, false, null, "사용자 이름"),
                        col("email", 3, "character varying(200)", 200, true, null, "이메일"), col("status", 4, "character varying(10)", 10, true, "'A'::character varying", null),
                        col("grade", 5, "character(1)", 1, true, "'X'::bpchar", null), col("age", 6, "integer", null, true, null, null),
                        col("nick", 7, "character varying(30)", 30, true, null, null)),
                List.of(constraint("member_pkey", SourceConstraintType.PRIMARY_KEY, null, "id"), constraint("member_email_key", SourceConstraintType.UNIQUE, null, "email")),
                List.of(index("member_pkey", true, "id"), index("member_email_key", true, "email")), List.of()));
    }

    private static List<SourceCollectionSpec.Table> v2() {
        return List.of(team(), new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("member"), SourceTableType.TABLE, "회원 정보"),
                List.of(col("id", 1, "integer", null, false, null, null), col("user_name", 2, "character varying(200)", 200, false, null, "사용자 이름"),
                        col("email", 3, "character varying(200)", 200, false, null, "회원 이메일"), col("status", 4, "character varying(10)", 10, true, "'B'::character varying", null),
                        col("grade", 5, "character(1)", 1, true, "'Y'::bpchar", null), col("age", 6, "integer", null, true, null, null),
                        col("last_login_on", 8, "date", null, true, null, null), col("team_id", 9, "integer", null, true, null, null),
                        col("seq", 10, "bigint", null, false, "GENERATED ALWAYS AS IDENTITY", null)),
                List.of(constraint("member_pkey", SourceConstraintType.PRIMARY_KEY, null, "id"),
                        constraint("member_email_key", SourceConstraintType.UNIQUE, null, "email", "user_name"),
                        constraint("member_age_check", SourceConstraintType.CHECK, "(age >= 0)", "age")),
                List.of(index("member_pkey", true, "id"), index("member_email_key", true, "email", "user_name"), index("ix_member_status", false, "status")),
                List.of(new SourceCatalogSpec.Relation("member_team_fk", null, Name.of("public"), Name.of("team"), "NO ACTION", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("team_id"), Name.of("id")))))));
    }

    private static SourceCollectionSpec.Table team() {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("team"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, false, null, null)), List.of(constraint("team_pkey", SourceConstraintType.PRIMARY_KEY, null, "id")),
                List.of(index("team_pkey", true, "id")), List.of());
    }

    private static SourceCollectionSpec.Table legacy(String nickType) {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("legacy"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, false, null, null), col("nick", 2, nickType, Integer.valueOf(nickType.replaceAll("\\D", "")), true, null, null)),
                List.of(constraint("legacy_pkey", SourceConstraintType.PRIMARY_KEY, null, "id")), List.of(index("legacy_pkey", true, "id")), List.of());
    }

    private static SourceCatalogSpec.Column col(String name, int ordinal, String type, Integer length, boolean nullable, String defaultValue, String comment) {
        return new SourceCatalogSpec.Column(Name.of(name), ordinal, type, length, null, null, nullable, defaultValue, comment);
    }

    private static SourceCatalogSpec.Constraint constraint(String name, SourceConstraintType type, String check, String... columns) {
        return new SourceCatalogSpec.Constraint(name, type, check, java.util.Arrays.stream(columns).map(Name::of).toList());
    }

    private static SourceCatalogSpec.Index index(String name, boolean unique, String... columns) {
        return new SourceCatalogSpec.Index(name, "btree", unique, java.util.Arrays.stream(columns).map(c -> new SourceCatalogSpec.IndexMember(Name.of(c), null, null)).toList(), null);
    }

    private static void assertSafe(Item item, String baseline) {
        assertThat(item).isNotNull();
        assertThat(item.judgement()).as(item.id()).isEqualTo(Judgement.SOURCE_CHANGED);
        assertThat(item.selectable()).as(item.id()).isTrue();
        assertThat(item.baseline()).as(item.id()).isEqualTo(baseline);
    }

    private SourceImportDto.Result apply(Env e, Map<String, List<String>> selection) {
        SourceImportDto.Plan plan = service.plan(e.manager, request(e, selection));
        assertThat(plan.applicable()).as(plan.tables().toString()).isTrue();
        return service.apply(e.manager, confirm(e, plan, selection));
    }

    private void approveColumn(Env e, String name, boolean approved) {
        tx.executeWithoutResult(s -> columnRepository.findById(columnId(e.project.getId(), name)).orElseThrow().setIsApproval(approved));
    }

    private Long columnId(Long projectId, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where PRJ_SN = ? and COL_NM = ?", Long.class, projectId, name);
    }

    private Object column(Long projectId, String name, String field) {
        return jdbc.queryForMap("select * from TB_COL where PRJ_SN = ? and COL_NM = ?", projectId, name).entrySet().stream()
                .filter(x -> x.getKey().equalsIgnoreCase(field)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private int count(String table, Long projectId) {
        return jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, projectId);
    }

    /** 모델·매핑·이력·실행 이력·버전의 지문 */
    private String fingerprint(Long pid) {
        StringBuilder text = new StringBuilder();
        for (String table : List.of("TB_TBL", "TB_COL", "TB_TBL_CNSTRT", "TB_TBL_IDX", "TB_TBL_REL", "TB_MDL_CHG_HSTRY", "TB_SRC_TBL_MPNG", "TB_SRC_IMPRT_EXCN", "TB_MDL_VER")) {
            text.append(table).append('=').append(count(table, pid)).append(';');
        }
        text.append(String.join("|", jdbc.queryForList("select COL_SN || ':' || COL_NM || ':' || coalesce(DATA_TYPE_NM, '') || ':' || coalesce(cast(MAX_LEN as varchar(20)), '')"
                + " || ':' || coalesce(BSC_VL, '') || ':' || coalesce(SRC_ANTN_CN, '') from TB_COL where PRJ_SN = ? order by COL_SN", String.class, pid)));
        text.append(String.join("|", jdbc.queryForList("select coalesce(SRC_ANTN_CN, '') from TB_TBL where PRJ_SN = ? order by TBL_SN", String.class, pid)));
        return text.toString();
    }

    private String key(Env e, String sourceTable) {
        return "S" + e.sourceTableId(sourceTable);
    }

    private SourceImportDto.Request request(Env e, Map<String, List<String>> selection) {
        List<SourceImportDto.TableSelection> tables = new ArrayList<>();
        selection.forEach((table, items) -> tables.add(new SourceImportDto.TableSelection(key(e, table), null, items)));
        return new SourceImportDto.Request(e.connection, null, tables, null, null);
    }

    private SourceImportDto.Request confirm(Env e, SourceImportDto.Plan plan, Map<String, List<String>> selection) {
        return new SourceImportDto.Request(plan.connectionId(), plan.reflectedOrder(), request(e, selection).tables(), plan.planToken(), plan.requestId());
    }

    private static Map<String, Item> items(SourceImportDto.TableCandidates candidates) {
        Map<String, Item> items = new LinkedHashMap<>();
        candidates.items().forEach(i -> items.put(i.id(), i));
        return items;
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(expected);
    }

    private final class Env {
        final Project project;
        final AccountDto manager;
        final Long connection;
        final SchemaInfo schema;
        final Map<String, TableInfo> tables = new LinkedHashMap<>();

        Env(Project project, AccountDto manager, Long connection, SchemaInfo schema) {
            this.project = project;
            this.manager = manager;
            this.connection = connection;
            this.schema = schema;
        }

        Long sourceTableId(String name) {
            return jdbc.queryForObject("select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = ? and TBL_NM = ?", Long.class, connection, name);
        }
    }

    private Env env(String name) {
        Account owner = account();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_DBA");
        SchemaInfo schema = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("DEFAULT").isDefault(true).build()));
        Long connection = connectionApi.create(manager, new SourceConnectionDto.Request("원본", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        return new Env(project, manager, connection, schema);
    }

    private Account account() {
        String username = "m4f-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName("ROLE_DBA")))).projectMember(new ArrayList<>()).build()));
    }

    private void collect(Env e, Map<String, List<SourceCollectionSpec.Table>> schemas) {
        List<SourceCollectionSpec.Schema> result = new ArrayList<>();
        schemas.forEach((schema, tables) -> result.add(SourceCollectionSpec.Schema.completed(null, Name.of(schema), tables)));
        ExecutionContext context = ExecutionContext.user(e.manager);
        collections.apply(context, collections.start(context, e.connection, SourceCollectionSpec.Request.full()).jobId(), new SourceCollectionSpec.Result(result));
    }

    private TableInfo table(Env e, String name) {
        TableInfo table = tableRepository.save(TableInfo.builder().projectId(e.project.getId()).tableName(name).tableKorName(name).tableDescription(name).isMasterTable(false)
                .sortOrder(1).isApproval(false).schema(schemaRepository.findById(e.schema.getId()).orElseThrow()).build());
        e.tables.put(name, table);
        return table;
    }

    private void column(TableInfo table, String name, String type, Integer length, boolean nullable, boolean pk, int order) {
        columnRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableInfo(table).tableName(table.getTableName()).columnName(name).dataType(type)
                .maxLength(length).isNullable(nullable).isRequired(false).isPk(pk).isUnique(false).isIndex(false).sortOrder(order).isApproval(false).isMasterData(false)
                .isSensitive(false).isEncrypted(false).build());
    }
}
