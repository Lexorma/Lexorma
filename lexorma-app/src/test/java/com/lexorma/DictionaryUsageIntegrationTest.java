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
import com.lexorma.data.dictionary.domain.dto.DictionaryUsageDto;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.dictionary.service.DictionaryUsageService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * M-5 1차 : 표준 항목 사용 현황 (H2, 실제 JPA 매핑·쿼리).
 * 실제 참조(컬럼 → 용어, 용어 → 도메인, 용어 ↔ 단어 매핑)만 따라가고 이름이 같은 것은 세지 않는지, 중복 집계·검색·페이징·프로젝트 격리를 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DictionaryUsageIntegrationTest {

    private static final Pageable PAGE = PageRequest.of(0, 100);
    private static final DictionaryUsageDto.ColumnSearch ALL = new DictionaryUsageDto.ColumnSearch(null, null, null, null);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired SchemaService schemaService;
    @Autowired DictionaryUsageService usageService;
    @Autowired ColumnManageService columnManageService;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("표준 용어·도메인·단어의 사용 현황은 실제 참조만 집계하고, 조건·페이징·정렬·프로젝트 격리를 지킨다")
    void usage() {
        AccountDto admin = loginAsAdmin("m5-usage");
        long projectId = admin.getProjectId();
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", null, null));

        StandardDomain codeDomain = domain(projectId, "코드V10");
        StandardDomain unusedDomain = domain(projectId, "미사용V10");
        StandardWord code = word(projectId, "코드", "CD");
        StandardWord name = word(projectId, "이름", "NM");
        StandardWord unusedWord = word(projectId, "미사용", "UNUSED");
        // 코드_코드 : 같은 단어가 두 번 매핑된다 (용어는 한 번만 세야 한다)
        StandardTerm codeCode = term(projectId, "코드_코드", "CD_CD", codeDomain, code, code);
        StandardTerm codeName = term(projectId, "코드_이름", "CD_NM", codeDomain, code, name);
        // 이름에 '미사용' 이 들어가지만 단어 매핑은 없다 (문자열이 같다는 이유로 사용 중이 아니다)
        StandardTerm noMapping = term(projectId, "미사용_표시", "UNUSED_MARK", unusedDomain);

        TableInfo codeTable = table(projectId, "TB_CODE", "코드", null);
        TableInfo orderTable = table(projectId, "TB_ORDER", "주문", sales.id());
        ColumnInfo c1 = column(projectId, codeTable, "CD_CD", codeCode, true);
        ColumnInfo c2 = column(projectId, codeTable, "CD_NM", codeName, true);
        ColumnInfo c3 = column(projectId, orderTable, "CD_CD", codeCode, false);
        // 컬럼명은 용어 약어와 같지만 용어를 참조하지 않는다
        column(projectId, orderTable, "CD_NM", null, true);

        // ---- 표준 용어 → 컬럼
        Page<DictionaryUsageDto.ColumnUsage> termColumns = usageService.selectTermColumns(admin, codeCode.getId(), ALL, PAGE);
        assertThat(termColumns.getTotalElements()).isEqualTo(2);
        assertThat(termColumns.getContent())
                .extracting(DictionaryUsageDto.ColumnUsage::id, DictionaryUsageDto.ColumnUsage::schemaName, DictionaryUsageDto.ColumnUsage::tableName,
                        DictionaryUsageDto.ColumnUsage::tableKorName, DictionaryUsageDto.ColumnUsage::columnName, DictionaryUsageDto.ColumnUsage::termName,
                        DictionaryUsageDto.ColumnUsage::domainName, DictionaryUsageDto.ColumnUsage::isApproval)
                .containsExactly(
                        tuple(c1.getId(), SchemaInfo.DEFAULT_NAME, "TB_CODE", "코드", "CD_CD", "코드_코드", "코드V10", true),
                        tuple(c3.getId(), "sales", "TB_ORDER", "주문", "CD_CD", "코드_코드", "코드V10", false));
        assertThat(termColumns.getContent().get(1).tableId()).isEqualTo(orderTable.getId());
        assertThat(termColumns.getContent().get(1).schemaId()).isEqualTo(sales.id());
        assertThat(usageService.selectTermColumns(admin, noMapping.getId(), ALL, PAGE).getTotalElements()).as("사용하는 컬럼 없음").isZero();

        // 조건 : 스키마, 승인 상태, 테이블명·컬럼명 검색 (모르는 검색 컬럼은 무시)
        assertThat(ids(usageService.selectTermColumns(admin, codeCode.getId(), new DictionaryUsageDto.ColumnSearch(sales.id(), null, null, null), PAGE))).containsExactly(c3.getId());
        assertThat(ids(usageService.selectTermColumns(admin, codeCode.getId(), new DictionaryUsageDto.ColumnSearch(null, false, null, null), PAGE))).containsExactly(c3.getId());
        assertThat(ids(usageService.selectTermColumns(admin, codeCode.getId(), new DictionaryUsageDto.ColumnSearch(null, true, null, null), PAGE))).containsExactly(c1.getId());
        assertThat(ids(usageService.selectTermColumns(admin, codeCode.getId(), new DictionaryUsageDto.ColumnSearch(null, null, "tableName", "order"), PAGE))).containsExactly(c3.getId());
        assertThat(ids(usageService.selectTermColumns(admin, codeCode.getId(), new DictionaryUsageDto.ColumnSearch(null, null, "columnName", "cd_"), PAGE))).hasSize(2);
        assertThat(ids(usageService.selectTermColumns(admin, codeCode.getId(), new DictionaryUsageDto.ColumnSearch(null, null, "unknown", "x"), PAGE))).hasSize(2);

        // 페이징·정렬 (허용하지 않은 정렬 필드는 무시)
        Page<DictionaryUsageDto.ColumnUsage> second = usageService.selectTermColumns(admin, codeCode.getId(), ALL, PageRequest.of(1, 1, Sort.by("tableName")));
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(ids(second)).containsExactly(c3.getId());
        assertThat(ids(usageService.selectTermColumns(admin, codeCode.getId(), ALL, PageRequest.of(0, 10, Sort.by(Sort.Order.desc("schemaName"), Sort.Order.asc("dropTable"))))))
                .containsExactly(c3.getId(), c1.getId());

        // ---- 표준 도메인 → 컬럼 (용어를 거친 간접 참조만 있다), 컬럼마다 한 번
        assertThat(ids(usageService.selectDomainColumns(admin, codeDomain.getId(), ALL, PAGE))).containsExactly(c1.getId(), c2.getId(), c3.getId());
        assertThat(usageService.selectDomainColumns(admin, unusedDomain.getId(), ALL, PAGE).getTotalElements()).isZero();

        // ---- 표준 단어 → 용어 (매핑 기준), 용어별 사용 컬럼 수
        Page<DictionaryUsageDto.TermUsage> wordTerms = usageService.selectWordTerms(admin, code.getId(), null, null, PAGE);
        assertThat(wordTerms.getTotalElements()).isEqualTo(2);
        assertThat(wordTerms.getContent())
                .extracting(DictionaryUsageDto.TermUsage::termName, DictionaryUsageDto.TermUsage::termAbbreviation, DictionaryUsageDto.TermUsage::domainName, DictionaryUsageDto.TermUsage::columnCount)
                .containsExactly(tuple("코드_이름", "CD_NM", "코드V10", 1L), tuple("코드_코드", "CD_CD", "코드V10", 2L));
        assertThat(usageService.selectWordTerms(admin, code.getId(), "termAbbreviation", "cd_nm", PAGE).getContent())
                .extracting(DictionaryUsageDto.TermUsage::id).containsExactly(codeName.getId());
        assertThat(usageService.selectWordTerms(admin, unusedWord.getId(), null, null, PAGE).getTotalElements()).as("이름에 문자열이 들어간 용어는 세지 않는다").isZero();

        // ---- 목록의 사용 건수
        List<StandardTermDto> terms = List.of(dto(codeCode), dto(codeName), dto(noMapping));
        usageService.fillTermUsage(admin, terms);
        assertThat(terms).extracting(StandardTermDto::getUsageCount).containsExactly(2L, 1L, 0L);
        List<StandardDomainDto> domains = List.of(StandardDomainDto.builder().id(codeDomain.getId()).build(), StandardDomainDto.builder().id(unusedDomain.getId()).build());
        usageService.fillDomainUsage(admin, domains);
        assertThat(domains).extracting(StandardDomainDto::getUsageCount).containsExactly(3L, 0L);
        List<StandardWordDto> words = List.of(StandardWordDto.builder().id(code.getId()).build(), StandardWordDto.builder().id(name.getId()).build(), StandardWordDto.builder().id(unusedWord.getId()).build());
        usageService.fillWordUsage(admin, words);
        assertThat(words).extracting(StandardWordDto::getUsageCount).as("코드_코드 는 한 번만").containsExactly(2L, 1L, 0L);
        usageService.fillTermUsage(admin, List.of()); // 빈 페이지

        // ---- 컬럼 목록 : 테이블 ID 로 정확히 한 테이블만 (컬럼 관리로 이동할 때)
        assertThat(columnManageService.selectTableData(PAGE, admin, null, null, orderTable.getId()).getContent())
                .extracting(ColumnInfoDto::getTableName).containsOnly("TB_ORDER").hasSize(2);
        assertThat(columnManageService.selectTableData(PAGE, admin, "columnName", "cd_cd", orderTable.getId()).getContent())
                .extracting(ColumnInfoDto::getId).containsExactly(c3.getId());
        assertThat(columnManageService.selectTableData(PAGE, admin, "tableName", "tb_", null).getContent()).hasSize(4);

        // ---- 프로젝트 격리 : 다른 프로젝트에서는 표준 항목 ID 를 알아도 조회할 수 없고, 건수에도 잡히지 않는다
        AccountDto other = loginAsAdmin("m5-usage-other");
        assertThatThrownBy(() -> usageService.selectTermColumns(other, codeCode.getId(), ALL, PAGE))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND_TERM_DATA));
        assertThatThrownBy(() -> usageService.selectDomainColumns(other, codeDomain.getId(), ALL, PAGE))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND_DOMAIN_DATA));
        assertThatThrownBy(() -> usageService.selectWordTerms(other, code.getId(), null, null, PAGE))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND_WORD_DATA));
        assertThatThrownBy(() -> usageService.selectTermColumns(other, 999_999_999L, ALL, PAGE)).isInstanceOf(CustomException.class);
        List<StandardTermDto> foreign = List.of(dto(codeCode));
        usageService.fillTermUsage(other, foreign);
        assertThat(foreign.get(0).getUsageCount()).isZero();
        assertThat(columnManageService.selectTableData(PAGE, other, null, null, orderTable.getId()).getContent()).isEmpty();
    }

    // ------------------------------------------------------------------ 보조

    private static List<Long> ids(Page<DictionaryUsageDto.ColumnUsage> page) {
        return page.getContent().stream().map(DictionaryUsageDto.ColumnUsage::id).toList();
    }

    private static StandardTermDto dto(StandardTerm term) {
        return StandardTermDto.builder().id(term.getId()).build();
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private StandardDomain domain(long projectId, String name) {
        return domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName(name).dataType("VARCHAR").dataLength(10).isApproval(true).build());
    }

    private StandardWord word(long projectId, String name, String abbreviation) {
        return wordRepository.save(StandardWord.builder().projectId(projectId).commonStandardWordName(name).commonStandardWordAbbreviation(abbreviation).isApproval(true).build());
    }

    private StandardTerm term(long projectId, String name, String abbreviation, StandardDomain domain, StandardWord... words) {
        return tx.execute(s -> {
            List<StandardTermWordMapping> mappings = new ArrayList<>();
            StandardTerm term = StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation)
                    .standardDomain(domain).isApproval(true).termWordMappings(mappings).build();
            for (int i = 0; i < words.length; i++) {
                StandardTermWordMapping mapping = new StandardTermWordMapping();
                mapping.setStandardTerm(term);
                mapping.setStandardWord(words[i]);
                mapping.setOrderIndex(i + 1);
                mappings.add(mapping);
            }
            return termRepository.save(term);
        });
    }

    private TableInfo table(long projectId, String name, String korName, Long schemaId) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).tableKorName(korName).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term, boolean approved) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR").maxLength(10).standardTerms(term).isApproval(approved).build()));
    }
}
