package com.lexorma;

import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.dictionary.service.TermQualityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-5 4차 : 판정 함수를 공통화해도 등록·수정의 허용·거절 정책은 그대로다 (D5 — 진단은 알리기만 하고 등록·수정을 막지 않는다).
 * 표준 단어의 등록 중복 기준(단어명 정확 일치)은 표준 도메인([DICT-2], 대소문자 무시)·표준 용어(M-5 5차, 약어 대소문자 무시 + 도메인 ID —
 * {@link StandardTermDuplicateIntegrationTest})와 다르다 — 여기서는 그 현행 기준을 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StandardRulePolicyIntegrationTest {

    @Autowired DataDictionaryService dictionary;
    @Autowired TermQualityService termQuality;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("단어 등록 중복 = 단어명 정확 일치 (400 SAVED_DATA_EXISTS), '_' 는 400. 대소문자만 다른 단어명은 등록된다 — 진단에는 단어 중복 규칙이 없다")
    void wordRegistrationKeepsExactNameRule() {
        Context a = project("m5-4-word");
        word(a, "Member", "MEMB");
        assertThatThrownBy(() -> word(a, "Member", "MBR2")).extracting("errorCode").isEqualTo(ErrorCode.SAVED_DATA_EXISTS);
        word(a, "member", "MEMB2"); // 대소문자만 다름 → 등록 (현행)
        assertThatThrownBy(() -> word(a, "회원_번호", "MBR")).extracting("errorCode").isEqualTo(ErrorCode.WORD_UNDERSCORE_NOT_ALLOWED);
        assertThatThrownBy(() -> word(a, "회원", "MBR_NO")).extracting("errorCode").isEqualTo(ErrorCode.WORD_UNDERSCORE_NOT_ALLOWED);
        // 정렬 순서는 DB 콜레이션마다 달라(PostgreSQL 은 member < Member) 순서 없이 비교한다
        assertThat(jdbc.queryForList("select WORD_NM from TB_STD_WORD where PRJ_SN = ?", String.class, a.projectId))
                .containsExactlyInAnyOrder("Member", "member");
    }

    // ------------------------------------------------------------------ 보조

    private record Context(Long projectId, AccountDto user) {}

    private Context project(String name) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + System.nanoTime()).isActive(true).account(admin).build());
        return new Context(project.getId(), SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN"));
    }

    private void login(Context context) {
        SecurityTestSupport.loginAs(projectRepository, context.projectId, context.user.getId(), "ROLE_ADMIN");
    }

    /** 화면과 같은 단어 등록 경로 */
    private void word(Context context, String name, String abbreviation) {
        login(context);
        dictionary.insertStandardWord(context.user, StandardWordDto.builder().revisionNumber(1).commonStandardWordName(name)
                .commonStandardWordAbbreviation(abbreviation).commonStandardWordEnglishName(abbreviation).commonStandardWordDescription(name)
                .isFormatWord("N").commonStandardDomainCategory("-").synonyms("").restrictedWords("").build());
    }

    private void domain(Context context, String name) {
        login(context);
        dictionary.insertStandardDomain(context.user, StandardDomainDto.builder().revisionNumber(1).commonStandardDomainGroupName("문자")
                .commonStandardDomainCategory("번호").commonStandardDomainName(name).commonStandardDomainDescription("M-5 4차")
                .dataType("VARCHAR").dataLength(10).dataDecimalLength(0).storageFormat("").displayFormat("").unit("").allowedValues("").build());
    }

    /** 화면과 같은 용어 등록 경로. 새 용어 ID */
    private long insertTerm(Context context, String name, String abbreviation, String domainName) {
        login(context);
        dictionary.insertStandardTerms(context.user, term(null, name, abbreviation, domainName));
        return jdbc.queryForObject("select max(STD_TRM_SN) from TB_STD_TRM where PRJ_SN = ?", Long.class, context.projectId);
    }

    private static StandardTermDto term(Long id, String name, String abbreviation, String domainName) {
        return StandardTermDto.builder().id(id).revisionNumber(1).commonStandardTermName(name).commonStandardTermDescription("M-5 4차")
                .commonStandardTermAbbreviation(abbreviation).commonStandardDomainName(domainName).synonyms("").build();
    }

    /** 용어 그룹 판정 결과 (저장 검사의 용어 묶음 — AI-6 3차에 실시간 목록 API 대신) */
    private List<InspectionItem> groups(Context context, StandardizationRule rule) {
        login(context);
        return termQuality.inspect(context.projectId).stream().filter(item -> item.finding().rule() == rule).toList();
    }

    private static List<Long> ids(InspectionItem group) {
        return group.finding().targetIds().stream().sorted().toList();
    }

    private String termDomainName(long termId) {
        Map<String, Object> row = jdbc.queryForMap("select d.DMN_NM from TB_STD_TRM t join TB_STD_DMN d on d.STD_DMN_SN = t.STD_DMN_SN where t.STD_TRM_SN = ?", termId);
        return (String) row.get("DMN_NM");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
