package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.controller.StandardizationQualityRestController;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Summary;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-5 2차 : 표준화 품질 진단 (실제 JPA 매핑·쿼리).
 * 대시보드 요약과 판정 결과(저장 검사의 모델 묶음)가 같은 기준인지, 프로젝트 격리, 권한, 조회가 아무것도 바꾸지 않는지 확인한다.
 * (AI-6 3차 : 실시간 목록 API 를 지워 그 목록의 조건·페이징·정렬 검증도 지웠다 — 저장 결과 목록은 QualityInspectionIntegrationTest.)
 * 판정 규칙의 세부 사례는 StandardizationQualityServiceTest 에 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StandardizationQualityIntegrationTest {

    private static final java.util.Set<StandardizationRule> COLUMN_RULES = java.util.EnumSet.of(StandardizationRule.TERM_NOT_LINKED,
            StandardizationRule.COLUMN_NAME_MISMATCH, StandardizationRule.DOMAIN_DEFINITION_MISMATCH);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired SchemaService schemaService;
    @Autowired StandardizationQualityService qualityService;
    @Autowired StandardizationQualityRestController controller;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("진단 요약·판정 : 유형별 건수와 문제가 있는 컬럼 수(대시보드 요약), 판정 결과의 대상·현재/기대 값·근거, 프로젝트 격리, 조회 전용")
    void quality() {
        AccountDto admin = loginAsAdmin("m5-quality");
        long projectId = admin.getProjectId();
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", null, null));

        StandardDomain code = domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName("코드V10").dataType("VARCHAR").dataLength(10).isApproval(true).build());
        StandardTerm codeCode = term(projectId, "코드_코드", "CD_CD", code);
        StandardTerm codeName = term(projectId, "코드_이름", "CD_NM", code);
        StandardTerm noDomain = term(projectId, "비고", "RMRK", null);

        TableInfo codeTable = table(projectId, "TB_CODE", null);
        TableInfo orderTable = table(projectId, "TB_ORDER", sales.id());
        // 표준을 따르는 컬럼 (대소문자만 다른 컬럼명, 도메인 없는 용어 포함)
        column(projectId, codeTable, "CD_CD", "VARCHAR", 10, codeCode, true);
        column(projectId, codeTable, "rmrk", "CLOB", null, noDomain, true);
        // 컬럼명은 용어 약어와 같지만 용어를 참조하지 않는다
        ColumnInfo notLinked = column(projectId, codeTable, "CD_NM", "VARCHAR", 10, null, true);
        // 컬럼명도 길이도 다르다 (문제 2건, 컬럼 1개)
        ColumnInfo both = column(projectId, orderTable, "CODE_NAME", "VARCHAR", 20, codeName, false);

        // ---- 요약 (대시보드) : 문제 3건, 문제가 있는 컬럼 2개
        assertThat(qualityService.selectSummary(admin)).isEqualTo(new Summary(4, 2, 1, 1, 1));

        // ---- 판정 (저장 검사가 쓰는 결과, AI-6 3차에 실시간 목록 API 대신) : 테이블명 → 컬럼 순서, 요약과 같은 건수
        List<InspectionItem> issues = columnIssues(projectId);
        assertThat(issues).extracting(i -> i.finding().rule(), i -> i.details().get("schemaName"), i -> i.details().get("tableName"), i -> i.details().get("columnName"),
                        i -> i.finding().actualValue(), i -> i.finding().expectedValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(StandardizationRule.TERM_NOT_LINKED, "DEFAULT", "TB_CODE", "CD_NM", null, null),
                        org.assertj.core.groups.Tuple.tuple(StandardizationRule.COLUMN_NAME_MISMATCH, "sales", "TB_ORDER", "CODE_NAME", "CODE_NAME", "CD_NM"),
                        org.assertj.core.groups.Tuple.tuple(StandardizationRule.DOMAIN_DEFINITION_MISMATCH, "sales", "TB_ORDER", "CODE_NAME", "VARCHAR(20)", "VARCHAR(10)"));
        InspectionItem domainIssue = issues.get(2);
        assertThat(domainIssue.finding().evidence()).containsEntry("attributes", "LENGTH");
        assertThat(domainIssue.tableId()).isEqualTo(orderTable.getId());
        assertThat(domainIssue.objectId()).isEqualTo(both.getId());
        assertThat(issues.get(0).objectId()).isEqualTo(notLinked.getId());
        assertThat(issues).allMatch(i -> i.finding().fingerprint().startsWith("v4:"));

        // ---- 조회 전용 : 컬럼명·용어 연결·타입이 그대로다
        tx.executeWithoutResult(s -> {
            assertThat(columnInfoRepository.findById(notLinked.getId()).orElseThrow().getStandardTerms()).isNull();
            ColumnInfo unchanged = columnInfoRepository.findById(both.getId()).orElseThrow();
            assertThat(unchanged.getColumnName()).isEqualTo("CODE_NAME");
            assertThat(unchanged.getMaxLength()).isEqualTo(20);
            assertThat(unchanged.getIsApproval()).isFalse();
        });

        // ---- 프로젝트 격리 : 다른 프로젝트에는 아무것도 나오지 않고, 그 프로젝트에 같은 약어의 용어가 있어도 연결로 보지 않는다
        AccountDto other = loginAsAdmin("m5-quality-other");
        term(other.getProjectId(), "코드_이름", "CD_NM", null);
        assertThat(qualityService.selectSummary(other)).isEqualTo(new Summary(0, 0, 0, 0, 0));
        assertThat(columnIssues(other.getProjectId())).isEmpty();
        assertThat(qualityService.selectSummary(admin).termNotLinked()).isEqualTo(1);
    }

    @Test
    @DisplayName("권한 : 보조 조회(도메인 이름 중복)는 VIEWER 부터, 승인 전 사용자는 거절된다")
    void authorization() {
        Project project = projectRepository.save(Project.builder().projectName("m5-quality-role").isActive(true)
                .account(accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow()).build());

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, project.getId(), "900", "ROLE_VIEWER");
        assertThat(controller.selectDomainDuplicates(viewer).getStatusCode().value()).isEqualTo(200);

        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, project.getId(), "901", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectDomainDuplicates(notApproved)).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ 보조

    /** 컬럼 3규칙의 판정 결과 (모델 판정 묶음, 완전성 규칙 제외) */
    private List<InspectionItem> columnIssues(long projectId) {
        return qualityService.inspect(projectId).stream().filter(i -> COLUMN_RULES.contains(i.finding().rule())).toList();
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private StandardTerm term(long projectId, String name, String abbreviation, StandardDomain domain) {
        return termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation)
                .standardDomain(domain).isApproval(true).build());
    }

    private TableInfo table(long projectId, String name, Long schemaId) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, String dataType, Integer maxLength, StandardTerm term, boolean approved) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType(dataType).maxLength(maxLength).standardTerms(term).isApproval(approved).build()));
    }
}
