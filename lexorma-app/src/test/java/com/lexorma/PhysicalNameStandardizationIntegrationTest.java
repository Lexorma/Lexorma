package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.TermResolveDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Model;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M7-NAME-1 물리명 추천·표준화 판정 통일 (docs/completed/physical-name-recommendation-m7.md 12장).
 * <ul>
 *   <li>NAME-T02 : 컬럼 엑셀 업로드·붙여넣기의 표준 용어 조회 — 대소문자 무시, 같은 약어 여러 개는 고르지 않는다 (엑셀 = 업로드 전체 취소, 붙여넣기 = 사용자가 고른다), 다른 프로젝트 격리</li>
 *   <li>NAME-T03 : 추천 목록의 '일치' 판정 — 행 상태(자바 StandardNames)·상태 필터·요약·서버 페이징이 탭·줄바꿈·비 ASCII 글자에서도 같다</li>
 * </ul>
 * H2 / PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameStandardizationIntegrationTest {

    private static final Pageable ALL = PageRequest.of(0, 500);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired SchemaService schemaService;
    @Autowired LogicalModelService logicalService;
    @Autowired ModelMappingService mappingService;
    @Autowired ColumnManageService columns;
    @Autowired PhysicalNameRecommendationService recommendation;
    @Autowired PhysicalNameRenameService rename;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lexorma.data.dictionary.service.StandardTermAbbreviationLookup lookup;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ NAME-T02 표준 용어 조회

    @Test
    @DisplayName("NAME-T02 엑셀 업로드 : 소문자·대문자·혼합 대소문자로 저장된 약어를 찾고, 같은 약어 용어가 여럿이면(도메인·승인 달라도) 업로드 전체 취소 · 없으면 TERM_IS_NOT_DEFINED · 다른 프로젝트 용어는 못 찾는다")
    void excelLookup() throws IOException {
        AccountDto admin = loginAsAdmin("name-t02-" + System.nanoTime());
        long projectId = admin.getProjectId();
        SchemaInfo schema = tx.execute(s -> schemaService.resolveSchema(projectId, null));
        TableInfo table = table(projectId, schema, "TB_LOOKUP", true);
        StandardDomain text = domain(projectId, "명V100");
        StandardDomain code = domain(projectId, "코드V10");
        StandardTerm lower = term(projectId, "사용자_아이디", "user_id", text, true);
        StandardTerm upper = term(projectId, "회원_번호", "MBR_NO", text, true);
        StandardTerm mixed = term(projectId, "주문_일자", "Ord_Dt", text, false);
        term(projectId, "중복_코드_1", "DUP_CD", text, true);
        term(projectId, "중복_코드_2", "dup_cd", code, false);
        AccountDto other = loginAsAdmin("name-t02-other-" + System.nanoTime());
        term(other.getProjectId(), "다른_코드", "OTHER_CD", domain(other.getProjectId(), "명V100"), true);
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");

        columns.uploadColumnExcelFile(xlsx(row("TB_LOOKUP", "USER_ID"), row("TB_LOOKUP", "mbr_no"), row("TB_LOOKUP", " ORD_DT ")), admin);
        Map<String, Long> saved = columnInfoRepository.findAll().stream().filter(c -> table.getId().equals(c.getTableInfo().getId()))
                .collect(Collectors.toMap(c -> c.getColumnName().trim(), c -> c.getStandardTerms().getId()));
        assertThat(saved).containsEntry("USER_ID", lower.getId()).containsEntry("mbr_no", upper.getId()).containsEntry("ORD_DT", mixed.getId());

        // 모호 : 앞 행이 정상이어도 업로드 전체가 취소된다 (아무것도 남지 않는다)
        int before = count(table);
        term(projectId, "메모_코드", "MEMO_CD", text, true);
        assertThatThrownBy(() -> columns.uploadColumnExcelFile(xlsx(row("TB_LOOKUP", "MEMO_CD"), row("TB_LOOKUP", "Dup_Cd")), admin))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_TERM);
                    assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("Dup_Cd");
                });
        assertThat(count(table)).as("모호성 오류는 앞 행까지 전체 롤백").isEqualTo(before);
        // 없음 · 다른 프로젝트
        assertThatThrownBy(() -> columns.uploadColumnExcelFile(xlsx(row("TB_LOOKUP", "NONE_CD")), admin))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TERM_IS_NOT_DEFINED));
        assertThatThrownBy(() -> columns.uploadColumnExcelFile(xlsx(row("TB_LOOKUP", "OTHER_CD")), admin))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TERM_IS_NOT_DEFINED));
        // 내부 공백·밑줄·숫자 접미사는 같은 약어가 아니다
        for (String name : List.of("USERID", "USER ID", "USER__ID", "USER_ID2", "USER-ID")) {
            assertThatThrownBy(() -> columns.uploadColumnExcelFile(xlsx(row("TB_LOOKUP", name)), admin)).as(name)
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TERM_IS_NOT_DEFINED));
        }
        assertThat(count(table)).isEqualTo(before);
    }

    @Test
    @DisplayName("NAME-T02 붙여넣기·일괄 저장 : 소문자로 저장된 약어도 찾고, 같은 약어가 여럿이면 AMBIGUOUS 로 후보 전체를 돌려준다(고르지 않음) — 사용자가 고른 용어 ID 로 저장, 다른 프로젝트 용어 ID 는 거절")
    void pasteAndBulk() {
        AccountDto admin = loginAsAdmin("name-t02b-" + System.nanoTime());
        long projectId = admin.getProjectId();
        SchemaInfo schema = tx.execute(s -> schemaService.resolveSchema(projectId, null));
        TableInfo table = table(projectId, schema, "TB_PASTE", true);
        StandardDomain text = domain(projectId, "명V100");
        StandardDomain code = domain(projectId, "코드V10");
        StandardTerm lower = term(projectId, "사용자_아이디", "user_id", text, true);
        StandardTerm a = term(projectId, "중복_코드_1", "DUP_CD", text, false);
        StandardTerm b = term(projectId, "중복_코드_2", "dup_cd", code, true);
        AccountDto other = loginAsAdmin("name-t02b-other-" + System.nanoTime());
        StandardTerm foreign = term(other.getProjectId(), "다른_코드", "USER_ID", domain(other.getProjectId(), "명V100"), true);
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");

        // HTTP 요청은 OSIV 안에서 돈다 — 테스트는 읽기 트랜잭션으로 감싼다 (용어 DTO 가 동의어 목록을 읽는다)
        List<TermResolveDto> resolved = tx.execute(s -> columns.resolveTerms(admin, List.of("USER_ID", "Dup_Cd", "NONE_CD")));
        assertThat(resolved).extracting(TermResolveDto::input, TermResolveDto::status)
                .containsExactly(tuple("USER_ID", "MATCHED"), tuple("Dup_Cd", "AMBIGUOUS"), tuple("NONE_CD", "NOT_FOUND"));
        assertThat(resolved.get(0).terms()).extracting(t -> t.getId()).containsExactly(lower.getId());
        assertThat(resolved.get(1).terms()).extracting(t -> t.getId()).containsExactlyInAnyOrder(a.getId(), b.getId());

        // 사용자가 고른 용어 (도메인 문맥) 로 저장 — 컬럼명 = 그 용어의 약어
        ColumnBulkSaveDto.Result ok = columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(table.getId()).rows(List.of(
                newRow("r1", a.getId()), newRow("r2", lower.getId()))).build());
        assertThat(ok.result()).as(String.valueOf(ok.errors())).isTrue();
        assertThat(columnInfoRepository.findAll().stream().filter(c -> table.getId().equals(c.getTableInfo().getId())).map(c -> c.getColumnName() + ":" + c.getStandardTerms().getId()))
                .containsExactlyInAnyOrder("DUP_CD:" + a.getId(), "user_id:" + lower.getId());
        // 다른 프로젝트 용어 · 같은 테이블의 같은 이름 (대소문자만 다른 용어 b) 은 전체 실패
        int before = count(table);
        ColumnBulkSaveDto.Result rejected = columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(table.getId()).rows(List.of(
                newRow("x1", foreign.getId()), newRow("x2", b.getId()))).build());
        assertThat(rejected.result()).isFalse();
        assertThat(rejected.errors()).extracting(ColumnBulkSaveDto.RowError::key).containsExactlyInAnyOrder("x1", "x2");
        assertThat(count(table)).isEqualTo(before);
    }

    @Test
    @DisplayName("NAME-F01 약어 조회 공백 : 저장된 약어·입력 양쪽의 앞뒤 공백·탭·줄바꿈(String.trim 범위)은 무시, 대소문자 무시, 안쪽 공백·탭·줄바꿈·Unicode 공백(U+00A0)은 그대로 — 정규화 뒤 같은 약어는 모호, 엑셀 전체 롤백·붙여넣기 선택, 프로젝트 격리")
    void lookupWhitespace() throws IOException {
        AccountDto admin = loginAsAdmin("name-f01-" + System.nanoTime());
        long projectId = admin.getProjectId();
        SchemaInfo schema = tx.execute(s -> schemaService.resolveSchema(projectId, null));
        TableInfo table = table(projectId, schema, "TB_F01", true);
        StandardDomain text = domain(projectId, "명V100");
        StandardDomain code = domain(projectId, "코드V10");
        StandardTerm user = term(projectId, "사용자_아이디", "USER_ID", text, true);
        StandardTerm userTab = term(projectId, "사용자_아이디_코드", "\tuser_id\n", code, false);
        StandardTerm member = term(projectId, "회원_번호", " \t\r\nMbr_No \t", text, true);
        StandardTerm inner = term(projectId, "안쪽_공백", "IN NER", text, true);
        StandardTerm innerTab = term(projectId, "안쪽_탭", "TAB\tIN", text, true);
        StandardTerm innerLf = term(projectId, "안쪽_줄", "LF\nIN", text, true);
        term(projectId, "앞_NBSP", "\u00A0NBSP_CD", text, true);
        AccountDto other = loginAsAdmin("name-f01-other-" + System.nanoTime());
        term(other.getProjectId(), "다른_아이디", "\tOTHER_ID", domain(other.getProjectId(), "명V100"), true);
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");

        Map<String, List<Long>> expected = new LinkedHashMap<>();
        for (String input : List.of("USER_ID", "user_id", "User_Id", " USER_ID ", "\tUSER_ID", "USER_ID\n", " \t USER_ID\r\n")) {
            expected.put(input, List.of(user.getId(), userTab.getId()));
        }
        for (String input : List.of("MBR_NO", "mbr_no", "\nMbr_No\t")) expected.put(input, List.of(member.getId()));
        expected.put("IN NER", List.of(inner.getId()));
        expected.put("in ner", List.of(inner.getId()));
        expected.put("TAB\tIN", List.of(innerTab.getId()));
        expected.put("LF\nIN", List.of(innerLf.getId()));
        for (String input : List.of("INNER", "IN  NER", "TAB IN", "LF IN", "NBSP_CD", "OTHER_ID", "USERID", "USER__ID", "USER-ID", "USER_ID2")) expected.put(input, List.of());
        Map<String, List<StandardTerm>> found = tx.execute(s -> lookup.find(projectId, expected.keySet()));
        expected.forEach((input, ids) -> assertThat(found.get(input)).extracting(StandardTerm::getId).as("[" + input + "]").containsExactlyElementsOf(ids));

        // 엑셀 : 앞뒤 탭이 붙은 저장 약어도 찾고, 정규화 뒤 같은 약어가 둘(도메인 다름·승인 다름)이면 409 + 앞 행까지 롤백
        columns.uploadColumnExcelFile(xlsx(row("TB_F01", "mbr_no")), admin);
        assertThat(columnInfoRepository.findAll().stream().filter(c -> table.getId().equals(c.getTableInfo().getId())).map(c -> c.getStandardTerms().getId()))
                .containsExactly(member.getId());
        int before = count(table);
        assertThatThrownBy(() -> columns.uploadColumnExcelFile(xlsx(row("TB_F01", "IN NER"), row("TB_F01", "User_Id")), admin))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_TERM));
        assertThat(count(table)).isEqualTo(before);
        // 붙여넣기 : 모호하면 후보 둘을 모두 주고 고르지 않는다 → 고른 용어 ID 로 저장
        List<TermResolveDto> resolved = tx.execute(s -> columns.resolveTerms(admin, List.of("user_id", "MBR_NO")));
        assertThat(resolved).extracting(TermResolveDto::status).containsExactly("AMBIGUOUS", "MATCHED");
        assertThat(resolved.get(0).terms()).extracting(t -> t.getId()).containsExactlyInAnyOrder(user.getId(), userTab.getId());
        ColumnBulkSaveDto.Result ok = columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(table.getId()).rows(List.of(newRow("p1", user.getId()))).build());
        assertThat(ok.result()).as(String.valueOf(ok.errors())).isTrue();
    }

    // ------------------------------------------------------------------ NAME-T03 추천 목록 판정

    @Test
    @DisplayName("NAME-T03 추천 목록 : 앞뒤 공백·탭·줄바꿈·ß·터키어 ı·합자·전각이 섞인 이름에서도 행 상태 = 상태 필터 = 요약 = 서버 페이징, 일치는 StandardNames 기준 (미리보기도 같다)")
    void listMatchesJava() {
        AccountDto admin = loginAsAdmin("name-t03-" + System.nanoTime());
        long projectId = admin.getProjectId();
        SchemaInfo schema = tx.execute(s -> schemaService.resolveSchema(projectId, null));
        Model model = logicalService.saveModel(admin, new Model(null, "판정 통일", null));
        // [약어, 현재 컬럼명, 자바 판정]
        String[][] specs = {
                {"USER_ID", "user_id", "ALREADY_MATCHED"},
                {"SPACE_CD", "  SPACE_CD  ", "ALREADY_MATCHED"},
                {"TAB_CD", "TAB_CD\t", "ALREADY_MATCHED"},
                {"LF_CD", "LF_CD\n", "ALREADY_MATCHED"},
                {"STRASSE_CD", "STRAßE_CD", "AVAILABLE"},
                {"straße_nm", "STRAßE_NM", "ALREADY_MATCHED"},
                {"ID_CD", "ıD_CD", "ALREADY_MATCHED"}, // equalsIgnoreCase : toUpperCase(dotless i) = I
                {"FI_CD", "ﬁ_CD", "AVAILABLE"},
                {"FULL_CD", "ＦＵＬＬ_CD", "AVAILABLE"},
                {"É_CD", "é_cd", "ALREADY_MATCHED"},
                {"INNER_CD", "INNER CD", "AVAILABLE"},
                {"UNDER_CD", "UNDER__CD", "AVAILABLE"},
                {"HYPHEN_CD", "HYPHEN-CD", "AVAILABLE"},
                {"SUFFIX_CD", "SUFFIX_CD2", "AVAILABLE"},
                {" PAD_CD ", "pad_cd", "ALREADY_MATCHED"},
                {"\tTABTERM_CD", "TABTERM_CD", "ALREADY_MATCHED"},
        };
        Map<Long, String> expected = new LinkedHashMap<>();
        Map<Long, Long> columnOf = new LinkedHashMap<>();
        int i = 0;
        for (String[] spec : specs) {
            TableInfo table = table(projectId, schema, "TB_J" + (i++), false);
            Entity entity = logicalService.saveEntity(admin, new Entity(null, model.id(), "엔터티 " + i, null, null, null, null));
            mappingService.saveEntityMapping(admin, new EntityMapping(null, entity.id(), table.getId()));
            StandardTerm term = term(projectId, "용어_" + i, spec[0], null, true);
            ColumnInfo column = column(projectId, table, spec[1], term);
            Long attribute = logicalService.saveAttribute(admin, new Attribute(null, entity.id(), "속성 " + i, null, null, false, null, term.getId(), null, null, null)).id();
            Long mappingId = mappingService.selectEntityRows(admin, new com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntitySearch(null, null, null, null, null, entity.id()), ALL)
                    .getContent().get(0).mappingId();
            mappingService.saveAttributeMapping(admin, new AttributeMapping(null, mappingId, attribute, column.getId()));
            expected.put(attribute, spec[2]);
            columnOf.put(attribute, column.getId());
        }
        List<ColumnRow> rows = recommendation.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, null, null, null), ALL).getContent();
        Map<Long, ColumnRow> byAttribute = rows.stream().collect(Collectors.toMap(ColumnRow::logicalAttributeId, r -> r));
        expected.forEach((attribute, status) -> assertThat(byAttribute.get(attribute).status().name())
                .as(byAttribute.get(attribute).currentPhysicalName() + " ↔ " + byAttribute.get(attribute).termAbbreviation()).isEqualTo(status));
        ColumnSummary summary = recommendation.selectColumnSummary(admin, model.id());
        for (Status status : Status.values()) {
            List<Long> want = rows.stream().filter(r -> r.status() == status).map(ColumnRow::logicalAttributeId).toList();
            assertThat(recommendation.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, status, null, null), ALL).getContent())
                    .extracting(ColumnRow::logicalAttributeId).as("필터 " + status).containsExactlyInAnyOrderElementsOf(want);
            long counted = switch (status) {
                case AVAILABLE -> summary.available();
                case ALREADY_MATCHED -> summary.alreadyMatched();
                case UNAVAILABLE -> summary.unavailable();
                case CONFLICT -> summary.conflict();
            };
            assertThat(counted).as("요약 " + status).isEqualTo(want.size());
            // 서버 페이징 (3건씩) : 전체 건수와 모은 행이 같다
            List<Long> paged = new ArrayList<>();
            for (int page = 0; page * 3 < Math.max(want.size(), 1); page++) {
                var p = recommendation.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, status, null, null), PageRequest.of(page, 3));
                assertThat(p.getTotalElements()).isEqualTo(want.size());
                p.getContent().forEach(r -> paged.add(r.logicalAttributeId()));
            }
            assertThat(paged).containsExactlyInAnyOrderElementsOf(want);
        }
        // 미리보기 : 일치 행은 바꿀 것 없음, 추천 가능 행은 같은 추천명
        expected.forEach((attribute, status) -> {
            ColumnRow row = byAttribute.get(attribute);
            if (row.recommendedPhysicalName() == null) return;
            PhysicalNameRenameDto.Plan plan = rename.preview(admin, new PhysicalNameRenameDto.Request(List.of(
                    new PhysicalNameRenameDto.Item(TargetType.COLUMN, columnOf.get(attribute), row.recommendedPhysicalName(), null)), null));
            assertThat(plan.items().get(0).noChange()).as(row.currentPhysicalName()).isEqualTo(row.status() == Status.ALREADY_MATCHED);
        });
    }

    @Test
    @DisplayName("NAME-T03 DB 식 : standard_name_plain 은 인쇄 가능 ASCII 만 1, standard_name_upper 는 ASCII a~z 만 대문자 (로캘·확장 대문자 없음)")
    void sqlFunctions() {
        Map<String, Integer> plain = new LinkedHashMap<>();
        for (String value : List.of("ABC_1", "a b", "", "A\tB", "AB\n", "é", "ß", "ＡＢ")) {
            plain.put(value, entityManager.createQuery("select standard_name_plain(cast(:v as String))", Integer.class).setParameter("v", value).getSingleResult());
        }
        assertThat(plain).containsExactly(Map.entry("ABC_1", 1), Map.entry("a b", 1), Map.entry("", 1), Map.entry("A\tB", 0), Map.entry("AB\n", 0),
                Map.entry("é", 0), Map.entry("ß", 0), Map.entry("ＡＢ", 0));
        assertThat(entityManager.createQuery("select standard_name_upper(cast(:v as String))", String.class).setParameter("v", "user_id-ß é i").getSingleResult())
                .isEqualTo("USER_ID-ß é I");
        // NAME-F01 : standard_name_trim = String.trim() (앞뒤 U+0020 이하만, 안쪽·U+00A0 그대로)
        for (String value : List.of("USER_ID", " \t\r\nUSER_ID \t\n", "\u0001A B\tC\u001f", "IN\tNER", "\u00A0NBSP\u00A0", " \t ", "")) {
            assertThat(entityManager.createQuery("select standard_name_trim(cast(:v as String))", String.class).setParameter("v", value).getSingleResult())
                    .as("[" + value + "]").isEqualTo(value.trim());
        }
    }

    // ------------------------------------------------------------------ 도구

    private int count(TableInfo table) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, table.getId());
    }

    private static String[] row(String table, String column) {
        return new String[]{table, column, "설명", "VARCHAR", "10", "0", "0", "Y", "", "1", "N", "", "N", "N", "N", "N", "N", "N"};
    }

    private static MockMultipartFile xlsx(String[]... rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i + 1);
                for (int c = 0; c < rows[i].length; c++) row.createCell(c).setCellValue(rows[i][c]);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "column.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static ColumnBulkSaveDto.Row newRow(String key, Long termId) {
        return ColumnBulkSaveDto.Row.builder().key(key).termId(termId).columnDesc("설명").dataType("VARCHAR").maxLength(10).isPk("N").isNullable("Y").isRequired("N")
                .isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N").build();
    }

    private StandardDomain domain(long projectId, String name) {
        return domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName(name).commonStandardDomainCategory("명")
                .dataType("VARCHAR").dataLength(100).isApproval(true).build());
    }

    private StandardTerm term(long projectId, String name, String abbreviation, StandardDomain domain, boolean approved) {
        return termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation)
                .standardDomain(domain).isApproval(approved).build());
    }

    private TableInfo table(long projectId, SchemaInfo schema, String name, boolean approved) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(entityManager.find(SchemaInfo.class, schema.getId())).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").standardTerms(term).isApproval(false).build()));
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).tablePrefix("TB_").account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }
}
