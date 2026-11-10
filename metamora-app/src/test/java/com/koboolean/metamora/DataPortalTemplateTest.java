package com.koboolean.metamora;

import com.koboolean.metamora.common.utils.ExcelUtils;
import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.AccountRepository;
import com.koboolean.metamora.core.support.SecurityTestSupport;
import com.koboolean.metamora.data.dictionary.repository.StandardDomainRepository;
import com.koboolean.metamora.data.dictionary.repository.StandardTermRepository;
import com.koboolean.metamora.data.dictionary.repository.StandardWordRepository;
import com.koboolean.metamora.data.dictionary.service.DataDictionaryService;
import com.koboolean.metamora.data.dictionary.service.StandardTermService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 공통표준용어 템플릿(static/excel/dataPortal.xlsx)
 * - 표준용어명은 영문약어명과 같은 수의 단어를 '_' 로 잇는다. (GIS_자료_여부 / GIS_DATA_YN)
 * - 템플릿 그대로 데이터사전 업로드가 끝까지 성공해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DataPortalTemplateTest {

    @Autowired DataDictionaryService dataDictionaryService;
    @Autowired StandardTermRepository standardTermRepository;
    @Autowired StandardWordRepository standardWordRepository;
    @Autowired StandardDomainRepository standardDomainRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private static MockMultipartFile template() throws IOException {
        try (InputStream in = DataPortalTemplateTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx")) {
            assertThat(in).as("dataPortal.xlsx").isNotNull();
            return new MockMultipartFile("file", "dataPortal.xlsx", null, in.readAllBytes());
        }
    }

    @Test
    @DisplayName("모든 표준용어의 용어명과 영문약어명은 '_' 기준 단어 수가 같다")
    void termNamesMatchAbbreviations() throws IOException {
        List<String> rows = new ArrayList<>();
        ExcelUtils.readExcelRows(template(), 0, List.of("id", "revision", "name", "description", "abbreviation"), 1, row -> {
            if (row.get("name").isEmpty()) return;
            rows.add(row.get("name"));
            assertThatCode(() -> StandardTermService.validateWordCount(row.get("name"), row.get("abbreviation")))
                    .as(row.get("name") + " / " + row.get("abbreviation"))
                    .doesNotThrowAnyException();
        });

        assertThat(rows).hasSizeGreaterThan(1_000).contains("GIS_자료_여부", "HTML_사용_여부");
    }

    @Test
    @DisplayName("템플릿 그대로 데이터사전 업로드(도메인 → 단어 → 용어)가 성공한다")
    void uploadTemplate() throws IOException {
        // 다른 테스트 데이터와 섞이지 않도록 새 프로젝트에 올린다
        Account admin = accountRepository.findAll().stream()
                .filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder()
                .projectName("dataPortal-template-test").isActive(true).account(admin).build());
        AccountDto login = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");

        long before = standardTermRepository.count();
        dataDictionaryService.uploadDictionaryExcelFile(template(), login);

        List<Map<String, String>> terms = new ArrayList<>();
        ExcelUtils.readExcelRows(template(), 0, List.of("id", "revision", "name"), 1, row -> {
            if (!row.get("name").isEmpty()) terms.add(row);
        });
        assertThat(standardTermRepository.count() - before).isEqualTo(terms.size());
    }

    @Test
    @DisplayName("이미 등록된 도메인·단어·용어는 건너뛰고 나머지만 등록한다 (같은 파일을 두 번 올려도, 예제 사전이 있는 META-MORA 에 올려도 성공)")
    void skipDuplicates() throws IOException {
        Account admin = accountRepository.findAll().stream()
                .filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder()
                .projectName("dataPortal-duplicate-test").isActive(true).account(admin).build());
        AccountDto login = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");

        dataDictionaryService.uploadDictionaryExcelFile(template(), login);
        long domains = standardDomainRepository.findAllByProjectId(project.getId()).size();
        long words = standardWordRepository.findAllByProjectId(project.getId()).size();
        long terms = standardTermRepository.findAllByProjectId(project.getId()).size();

        dataDictionaryService.uploadDictionaryExcelFile(template(), login);
        assertThat(standardDomainRepository.findAllByProjectId(project.getId())).hasSize((int) domains);
        assertThat(standardWordRepository.findAllByProjectId(project.getId())).hasSize((int) words);
        assertThat(standardTermRepository.findAllByProjectId(project.getId())).hasSize((int) terms);

        // V0 예제 사전(공통표준 단어·용어·도메인 일부 + 기관 표준단어)이 있는 기본 프로젝트
        AccountDto main = SecurityTestSupport.loginAs(projectRepository, 0L, String.valueOf(admin.getId()), "ROLE_ADMIN");
        long seededTerms = standardTermRepository.findAllByProjectId(0L).size();
        dataDictionaryService.uploadDictionaryExcelFile(template(), main);
        assertThat(standardTermRepository.findAllByProjectId(0L).stream().filter(t -> "등록_일시".equals(t.getCommonStandardTermName())))
                .as("예제와 템플릿에 모두 있는 용어는 한 번만").hasSize(1);
        assertThat(standardTermRepository.findAllByProjectId(0L).size()).isGreaterThan((int) seededTerms);
    }
}
