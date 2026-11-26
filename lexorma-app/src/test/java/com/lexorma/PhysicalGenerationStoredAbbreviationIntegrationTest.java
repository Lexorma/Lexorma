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
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Issue;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Search;
import com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 후속 (2026-11-18) : 논리 → 물리 생성의 컬럼명 = 표준 용어에 저장된 영문 약어 (앞뒤 공백 제거·대문자, {@code StandardNames.expectedColumnName}) — 단어 약어를 다시 잇지 않는다.
 * 생성 계획·생성 결과·물리명 추천(매핑 정합성)·품질 진단이 같은 값을 쓴다. 약어가 바뀌면 planToken 이 달라져 오래된 계획은 409, 이미 만든 물리 모델은 바꾸지 않는다.
 *
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalGenerationStoredAbbreviationIntegrationTest {

    @Autowired DataDictionaryService dictionary;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardizationQualityService quality;
    @Autowired PhysicalNameRecommendationService recommendations;
    @Autowired LogicalModelService logical;
    @Autowired PhysicalGenerationService generation;
    @Autowired EntityTableMappingRepository mappings;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("저장 약어 DLX_DT·단어 조합 DEL_DT : 미리보기·생성 컬럼 = DLX_DT, 물리명 추천은 일치, 품질 진단의 컬럼명 불일치 없음. 저장값 = 단어 조합인 용어는 그대로")
    void storedAbbreviationNamesTheColumn() {
        Fixture f = fixture("m7-stored", true);
        assertThat(wordAbbreviation(f.deleted)).isEqualTo("DEL_DT");
        changeAbbreviation(f, f.deleted, "DLX_DT");

        PhysicalGenerationDto.Plan plan = generation.preview(login(f), request(f, null));
        assertThat(plan.creatable()).as(plan.tables().toString()).isTrue();
        assertThat(plan.tables().getFirst().columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).containsExactly("CUST_ID", "DLX_DT");
        PhysicalGenerationDto.Result result = generation.apply(login(f), request(f, plan.planToken()));
        assertThat(columnNames(result.tableIds().getFirst())).as("계획 = 결과").containsExactly("CUST_ID", "DLX_DT");

        // 매핑 정합성 (물리명 추천) : 두 속성 모두 일치
        assertThat(recommendations.selectColumnRows(login(f), new PhysicalNameRecommendationDto.ColumnSearch(f.modelId, null, null, null, null, null, null), PageRequest.of(0, 50)).getContent())
                .allSatisfy(row -> assertThat(row.status()).as(row.logicalName()).isEqualTo(PhysicalNameRecommendationDto.Status.ALREADY_MATCHED));
        // 품질 진단 : 컬럼명 불일치 없음 (규칙은 그대로)
        assertThat(nameMismatches(f)).isEmpty();
    }

    @Test
    @DisplayName("planToken : 미리보기 뒤 약어를 바꾸면(승인 취소 → 수정) 오래된 계획은 409·아무것도 만들지 않음, 다시 미리보기하면 바뀐 이름으로 생성")
    void planTokenDetectsAbbreviationChange() {
        Fixture f = fixture("m7-token", true);
        changeAbbreviation(f, f.deleted, "DLX_DT");
        PhysicalGenerationDto.Plan stale = generation.preview(login(f), request(f, null));
        PhysicalGenerationDto.Plan same = generation.preview(login(f), request(f, null));
        assertThat(same.planToken()).as("바뀐 것이 없으면 같은 토큰").isEqualTo(stale.planToken());

        changeAbbreviation(f, f.deleted, "DLY_DT");
        assertThatThrownBy(() -> generation.apply(login(f), request(f, stale.planToken()))).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(mappings.findByEntity_Id(f.entityId)).as("만든 것이 없다").isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where PRJ_SN = ?", Integer.class, f.projectId)).isZero();

        PhysicalGenerationDto.Plan fresh = generation.preview(login(f), request(f, null));
        assertThat(fresh.tables().getFirst().columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).containsExactly("CUST_ID", "DLY_DT");
        PhysicalGenerationDto.Result result = generation.apply(login(f), request(f, fresh.planToken()));
        assertThat(columnNames(result.tableIds().getFirst())).containsExactly("CUST_ID", "DLY_DT");
    }

    @Test
    @DisplayName("기존 물리 모델 보호 : DEL_DT 로 만든 뒤 약어를 DLX_DT 로 바꿔도 컬럼명·매핑 그대로, 다시 생성하면 건너뜀, 품질 진단은 불일치를 그대로 알린다")
    void existingModelKept() {
        Fixture f = fixture("m7-existing", true);
        PhysicalGenerationDto.Plan plan = generation.preview(login(f), request(f, null));
        PhysicalGenerationDto.Result result = generation.apply(login(f), request(f, plan.planToken()));
        long tableId = result.tableIds().getFirst();
        assertThat(columnNames(tableId)).containsExactly("CUST_ID", "DEL_DT");
        assertThat(nameMismatches(f)).isEmpty();

        changeAbbreviation(f, f.deleted, "DLX_DT");
        PhysicalGenerationDto.Plan again = generation.preview(login(f), request(f, null));
        assertThat(again.tables()).extracting(PhysicalGenerationDto.TablePlan::status).containsOnly("SKIP");
        assertThat(columnNames(tableId)).as("자동으로 바꾸지 않는다").containsExactly("CUST_ID", "DEL_DT");
        assertThat(mappings.findByEntity_Id(f.entityId)).isPresent();
        assertThat(nameMismatches(f)).as("기존 불일치는 그대로 진단").extracting(Issue::columnName, Issue::expectedValue).containsExactly(org.assertj.core.groups.Tuple.tuple("DEL_DT", "DLX_DT"));
    }

    @Test
    @DisplayName("비정상 저장값 : 약어 NULL·빈 문자열·공백만 → '영문 약어가 없습니다' 오류(단어 조합으로 대신하지 않음), 앞뒤 공백·소문자 → 정리, 같은 컬럼명 → 오류, 63바이트 초과 → 식별자 경고")
    void invalidStoredAbbreviations() {
        Fixture f = fixture("m7-invalid", true);
        for (String stored : new String[]{null, "", "   "}) {
            jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", stored, f.deleted);
            PhysicalGenerationDto.Plan plan = generation.preview(login(f), request(f, null));
            assertThat(plan.creatable()).as(String.valueOf(stored)).isFalse();
            assertThat(plan.tables().getFirst().errors()).as(String.valueOf(stored)).anyMatch(e -> e.contains("영문 약어가 없습니다"));
            assertThat(plan.tables().getFirst().columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).doesNotContain("DEL_DT");
        }
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", "  dlx_dt ", f.deleted);
        assertThat(generation.preview(login(f), request(f, null)).tables().getFirst().columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName)
                .containsExactly("CUST_ID", "DLX_DT");
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", "CUST_ID", f.deleted);
        assertThat(generation.preview(login(f), request(f, null)).tables().getFirst().errors()).anyMatch(e -> e.contains("CUST_ID"));
        String longName = "DLX_" + "A".repeat(66);
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", longName, f.deleted);
        PhysicalGenerationDto.Plan tooLong = generation.preview(login(f), request(f, null));
        assertThat(tooLong.tables().getFirst().columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).contains(longName);
        assertThat(tooLong.identifiers()).as("기존 DBMS 식별자 검사(경고)").anySatisfy(issue -> assertThat(issue.name()).isEqualTo(longName));
    }

    @Test
    @DisplayName("약어 미사용 프로젝트 (공통표준 템플릿 업로드) : 저장된 영문명(GROUP_NAME·DOCUMENT_NAME)으로 생성, 단어 약어 조합과 다르다, 진단 불일치 없음")
    void englishNameProject() throws IOException {
        Fixture f = fixture("m7-english", false);
        login(f);
        dictionary.uploadDictionaryExcelFile(template(), f.user);
        long group = termId(f, "그룹_명");
        long document = termId(f, "문서_명");
        assertThat(stored(group)).isEqualTo("GROUP_NAME");
        assertThat(stored(document)).isEqualTo("DOCUMENT_NAME");
        assertThat(wordAbbreviation(group)).isNotEqualTo("GROUP_NAME");

        LogicalModelDto.Model model = logical.saveModel(login(f), new LogicalModelDto.Model(null, "영문명 모델", null));
        LogicalModelDto.Entity entity = logical.saveEntity(login(f), new LogicalModelDto.Entity(null, model.id(), "그룹", null, null, null, null));
        LogicalModelDto.Attribute groupName = logical.saveAttribute(login(f), new LogicalModelDto.Attribute(null, entity.id(), "그룹 명", null, null, true, null, group, null, null, null));
        logical.saveAttribute(login(f), new LogicalModelDto.Attribute(null, entity.id(), "문서 명", null, null, false, null, document, null, null, null));
        logical.saveIdentifier(login(f), new LogicalModelDto.Identifier(null, entity.id(), "식별자", true, List.of(groupName.id()), null));
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(entity.id()), null, null, null);
        PhysicalGenerationDto.Plan plan = generation.preview(login(f), request);
        assertThat(plan.tables().getFirst().columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).containsExactly("GROUP_NAME", "DOCUMENT_NAME");
        PhysicalGenerationDto.Result result = generation.apply(login(f), new PhysicalGenerationDto.Request(List.of(entity.id()), null, plan.planToken(), null));
        assertThat(columnNames(result.tableIds().getFirst())).containsExactly("GROUP_NAME", "DOCUMENT_NAME");
        assertThat(nameMismatches(f)).isEmpty();
        assertThat(jdbc.queryForObject("select DIC_ABBR_USE_YN from TB_PRJ where PRJ_SN = ?", Boolean.class, f.projectId)).as("프로젝트 설정 그대로").isFalse();
    }

    @Test
    @DisplayName("다른 프로젝트의 엔터티로는 미리보기·생성할 수 없다 (엔터티를 찾을 수 없음)")
    void otherProject() {
        Fixture a = fixture("m7-iso-a", true);
        Fixture b = fixture("m7-iso-b", true);
        PhysicalGenerationDto.Plan plan = generation.preview(login(a), request(a, null));
        assertThatThrownBy(() -> generation.preview(login(b), request(a, null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> generation.apply(login(b), request(a, plan.planToken()))).isInstanceOf(CustomException.class);
        assertThat(mappings.findByEntity_Id(a.entityId)).isEmpty();
    }

    // ------------------------------------------------------------------ 보조

    private record Fixture(AccountDto user, Long projectId, Long modelId, Long entityId, long deleted) {}

    /** 단어 고객·아이디·삭제·일시, 도메인, 용어 고객_아이디(CUST_ID)·삭제_일시(DEL_DT) — 화면과 같은 등록 경로라 단어 매핑이 있다. 엔터티 고객(고객 아이디 PK, 삭제 일시) */
    private Fixture fixture(String name, boolean dictionaryAbbreviation) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + System.nanoTime()).isActive(true).account(admin)
                .isDicAbbrUsed(dictionaryAbbreviation).dbType(DbType.POSTGRESQL).build());
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        Fixture empty = new Fixture(user, project.getId(), null, null, 0);
        if (!dictionaryAbbreviation) return empty;
        for (String[] w : new String[][]{{"고객", "CUST"}, {"아이디", "ID"}, {"삭제", "DEL"}, {"일시", "DT"}}) {
            dictionary.insertStandardWord(login(empty), StandardWordDto.builder().revisionNumber(1).commonStandardWordName(w[0]).commonStandardWordAbbreviation(w[1])
                    .commonStandardWordEnglishName(w[1]).commonStandardWordDescription(w[0]).isFormatWord("N").commonStandardDomainCategory("-").synonyms("").restrictedWords("").build());
        }
        dictionary.insertStandardDomain(login(empty), StandardDomainDto.builder().revisionNumber(1).commonStandardDomainGroupName("문자").commonStandardDomainCategory("-")
                .commonStandardDomainName("M7V20").commonStandardDomainDescription("M-7 후속").dataType("VARCHAR").dataLength(20).dataDecimalLength(0)
                .storageFormat("").displayFormat("").unit("").allowedValues("").build());
        long customerId = insertTerm(empty, "고객_아이디", "CUST_ID");
        long deleted = insertTerm(empty, "삭제_일시", "DEL_DT");
        LogicalModelDto.Model model = logical.saveModel(login(empty), new LogicalModelDto.Model(null, name, null));
        LogicalModelDto.Entity entity = logical.saveEntity(login(empty), new LogicalModelDto.Entity(null, model.id(), "고객", null, null, null, null));
        LogicalModelDto.Attribute id = logical.saveAttribute(login(empty), new LogicalModelDto.Attribute(null, entity.id(), "고객 아이디", null, null, true, null, customerId, null, null, null));
        logical.saveAttribute(login(empty), new LogicalModelDto.Attribute(null, entity.id(), "삭제 일시", null, null, false, null, deleted, null, null, null));
        logical.saveIdentifier(login(empty), new LogicalModelDto.Identifier(null, entity.id(), "식별자", true, List.of(id.id()), null));
        return new Fixture(user, project.getId(), model.id(), entity.id(), deleted);
    }

    private AccountDto login(Fixture f) {
        return SecurityTestSupport.loginAs(projectRepository, f.projectId, f.user.getId(), "ROLE_ADMIN");
    }

    private static PhysicalGenerationDto.Request request(Fixture f, String token) {
        return new PhysicalGenerationDto.Request(List.of(f.entityId), null, token, null);
    }

    private long insertTerm(Fixture f, String name, String abbreviation) {
        dictionary.insertStandardTerms(login(f), StandardTermDto.builder().revisionNumber(1).commonStandardTermName(name).commonStandardTermDescription(name)
                .commonStandardTermAbbreviation(abbreviation).commonStandardDomainName("M7V20").synonyms("").build());
        return termId(f, name);
    }

    private long termId(Fixture f, String name) {
        return jdbc.queryForList("select STD_TRM_SN from TB_STD_TRM where PRJ_SN = ? and TRM_NM = ? order by STD_TRM_SN", Long.class, f.projectId, name).getFirst();
    }

    /** 화면과 같은 절차 : 승인 취소 → 불러온 기준값으로 약어 수정 (M-5 7·8차) */
    private void changeAbbreviation(Fixture f, long termId, String abbreviation) {
        dictionary.approvalStandardTerms(List.of(row(f, termId)), login(f), false);
        StandardTermDto edit = row(f, termId);
        edit.setCommonStandardTermAbbreviation(abbreviation);
        dictionary.updateStandardTerms(login(f), edit);
        assertThat(stored(termId)).isEqualTo(abbreviation);
    }

    private StandardTermDto row(Fixture f, long termId) {
        AccountDto user = login(f);
        return tx.execute(s -> dictionary.getStandardTermsData(PageRequest.of(0, 100_000), user, null, null).getContent().stream()
                .filter(term -> term.getId() == termId).findFirst().orElseThrow());
    }

    private String stored(long termId) {
        return jdbc.queryForObject("select TRM_ENG_ABBR_NM from TB_STD_TRM where STD_TRM_SN = ?", String.class, termId);
    }

    private String wordAbbreviation(long termId) {
        return tx.execute(s -> termRepository.findById(termId).orElseThrow().getTermWordMappings().stream()
                .sorted(Comparator.comparingInt(StandardTermWordMapping::getOrderIndex))
                .map(mapping -> mapping.getStandardWord().getCommonStandardWordAbbreviation()).collect(Collectors.joining("_")));
    }

    private List<String> columnNames(long tableId) {
        return jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? order by SORT_ORD", String.class, tableId);
    }

    private List<Issue> nameMismatches(Fixture f) {
        return quality.selectIssues(login(f), new Search(IssueType.COLUMN_NAME_MISMATCH, null, null, null, null), PageRequest.of(0, 100)).getContent();
    }

    private static MockMultipartFile template() throws IOException {
        try (InputStream in = PhysicalGenerationStoredAbbreviationIntegrationTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx")) {
            assertThat(in).isNotNull();
            return new MockMultipartFile("file", "dataPortal.xlsx", null, in.readAllBytes());
        }
    }
}
