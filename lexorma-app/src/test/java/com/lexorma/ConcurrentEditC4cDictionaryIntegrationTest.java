package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.dictionary.controller.DataDictionaryRestController;
import com.lexorma.data.dictionary.controller.DictionaryUsageRestController;
import com.lexorma.data.dictionary.domain.dto.DictionaryUsageDto;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M8-C4c-1 C4c-D02·D04·D05 (docs/ongoing/modeling-concurrency-m8.md 20장) : 표준 도메인·단어·용어 삭제는 프로젝트 행 배타 → 지울 행 NOWAIT → 다시 읽기 → 승인 거절 →
 * 화면이 본 수정 일시 비교(409) → 참조 검사. 하나라도 거절되면 전체 롤백. 승인된 단어는 직접 고칠 수 없다. 도메인 승인 취소는 참조를 그대로 두고 영향 범위만 알려 준다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC4cDictionaryIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired DataDictionaryService dictionary;
    @Autowired DataDictionaryRestController dictionaryApi;
    @Autowired DictionaryUsageRestController usageApi;
    @Autowired StandardDomainRepository domains;
    @Autowired StandardWordRepository words;
    @Autowired StandardTermRepository terms;
    @Autowired ProjectRepository projectRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired com.lexorma.data.mapping.service.PhysicalGenerationService generation;

    /** 논리 → 물리 생성 (컬럼이 속성의 표준 용어를 참조) */
    private void generate(GenerationFixture f, long entity) {
        var plan = generation.preview(f.user, f.request(List.of(entity)));
        generation.apply(f.user, f.request(List.of(entity), plan.planToken()));
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private ConcurrentEditC4cSupport support() {
        return new ConcurrentEditC4cSupport(tx, jdbc, dataSource, projectRepository);
    }

    private static String code(Runnable call) {
        return ConcurrentEditC4cSupport.outcome(call);
    }

    private GenerationFixture fixture(String name) {
        return GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
    }

    /** 참조 없는 미승인 도메인 (이름 = type+length) */
    private long unusedDomain(GenerationFixture f, String type, int length) {
        long term = f.term("TMP", type, length);
        long domain = jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, term);
        jdbc.update("delete from TB_STD_TRM where STD_TRM_SN = ?", term);
        jdbc.update("update TB_STD_DMN set APRV_YN = false where STD_DMN_SN = ?", domain);
        return domain;
    }

    private StandardDomainDto domainRow(long id) {
        return tx.execute(s -> StandardDomainDto.fromEntity(domains.findById(id).orElseThrow()));
    }

    private StandardWordDto wordRow(long id) {
        return tx.execute(s -> StandardWordDto.fromEntity(words.findById(id).orElseThrow()));
    }

    private StandardTermDto termRow(long id) {
        return tx.execute(s -> StandardTermDto.fromEntity(terms.findById(id).orElseThrow()));
    }

    private int exists(String table, String key, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + key + " = ?", Integer.class, id);
    }

    private long wordId(GenerationFixture f, String name) {
        return jdbc.queryForObject("select STD_WORD_SN from TB_STD_WORD where PRJ_SN = ? and WORD_NM = ?", Long.class, f.user.getProjectId(), name);
    }

    @Test
    @DisplayName("목록을 본 뒤 다른 사용자가 도메인·단어·용어를 고치면 예전 행의 삭제는 409 · 지워지지 않는다 → 다시 읽은 행이면 삭제")
    void staleDeleteIsRejected() {
        GenerationFixture f = fixture("c4c-d1");
        long domain = unusedDomain(f, "VARCHAR", 31);
        StandardDomainDto seen = domainRow(domain);
        StandardDomainDto edit = domainRow(domain);
        edit.setCommonStandardDomainDescription("다른 사용자가 고친 설명");
        dictionary.updateStandardDomain(f.user, edit);
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardDomains(List.of(seen), f.user))).isEqualTo("MODEL_CONFLICT");
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", domain)).isEqualTo(1);
        dictionary.deleteDataDictionaryStandardDomains(List.of(domainRow(domain)), f.user);
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", domain)).isZero();

        long word = wordId(f, "지점");
        jdbc.update("update TB_STD_WORD set APRV_YN = false where STD_WORD_SN = ?", word);
        StandardWordDto seenWord = wordRow(word);
        StandardWordDto editWord = wordRow(word);
        editWord.setCommonStandardWordDescription("고친 단어 설명");
        dictionary.updateStandardWords(f.user, editWord);
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardWords(List.of(seenWord), f.user))).isEqualTo("MODEL_CONFLICT");
        assertThat(exists("TB_STD_WORD", "STD_WORD_SN", word)).isEqualTo(1);

        long term = f.term("C4C_T1", "VARCHAR", 32);
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", term);
        StandardTermDto seenTerm = termRow(term);
        jdbc.update("update TB_STD_TRM set TRM_EXPLN = '다른 사용자', MDFCN_DT = MDFCN_DT + interval '1' second where STD_TRM_SN = ?", term);
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardTerms(List.of(seenTerm), f.user))).isEqualTo("MODEL_CONFLICT");
        assertThat(exists("TB_STD_TRM", "STD_TRM_SN", term)).isEqualTo(1);
    }

    @Test
    @DisplayName("일괄 삭제 중 한 행만 바뀌었어도 409 · 전체 롤백 (앞 행도 남는다) · 다른 프로젝트 행은 예전처럼 건너뛴다")
    void partialMismatchRollsBackAll() {
        GenerationFixture f = fixture("c4c-d2");
        long d1 = unusedDomain(f, "VARCHAR", 41);
        long d2 = unusedDomain(f, "VARCHAR", 42);
        StandardDomainDto fresh = domainRow(d1);
        StandardDomainDto stale = domainRow(d2);
        jdbc.update("update TB_STD_DMN set MDFCN_DT = MDFCN_DT + interval '1' second where STD_DMN_SN = ?", d2);
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardDomains(List.of(fresh, stale), f.user))).isEqualTo("MODEL_CONFLICT");
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", d1)).isEqualTo(1);
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", d2)).isEqualTo(1);

        GenerationFixture other = fixture("c4c-d2o");
        long foreign = unusedDomain(other, "VARCHAR", 43);
        dictionary.deleteDataDictionaryStandardDomains(List.of(domainRow(foreign)), f.user);
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", foreign)).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP : 삭제 행마다 modifiedAt 필수 (400 MODEL_BASE_REQUIRED, 아무것도 지우지 않음)")
    void httpRequiresBase() {
        GenerationFixture f = fixture("c4c-d3");
        long d1 = unusedDomain(f, "VARCHAR", 51);
        StandardDomainDto noBase = StandardDomainDto.builder().id(d1).build();
        assertThatThrownBy(() -> dictionaryApi.deleteDataDictionaryStandardDomains(List.of(domainRow(d1), noBase), f.user))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        assertThatThrownBy(() -> dictionaryApi.deleteDataDictionaryStandardWords(List.of(StandardWordDto.builder().id(wordId(f, "지점")).build()), f.user))
                .extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        assertThatThrownBy(() -> dictionaryApi.deleteDataDictionaryStandardTerms(List.of(StandardTermDto.builder().id(1L).build()), f.user))
                .extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", d1)).isEqualTo(1);
    }

    @Test
    @DisplayName("물리 컬럼·논리 속성이 쓰는 용어 삭제는 RELATION_EXISTS (예전 : 물리 컬럼 참조는 DB FK 위반 500)")
    void termReferencedByColumn() {
        GenerationFixture f = fixture("c4c-d4");
        long entity = f.entity("고객");
        long attribute = f.attr(entity, "고객번호", true, "CUST_NO");
        long term = jdbc.queryForObject("select STD_TRM_SN from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", Long.class, attribute);
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", term);
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardTerms(List.of(termRow(term)), f.user))).isEqualTo("RELATION_EXISTS");

        f.pk(entity, attribute);
        generate(f, entity); // 물리 생성 : 컬럼이 같은 용어를 참조
        jdbc.update("update TB_LGCL_ATRB set STD_TRM_SN = null where LGCL_ATRB_SN = ?", attribute);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where STD_TRM_SN = ?", Integer.class, term)).isPositive();
        assertThat(code(() -> dictionary.deleteDataDictionaryStandardTerms(List.of(termRow(term)), f.user))).isEqualTo("RELATION_EXISTS");
        assertThat(exists("TB_STD_TRM", "STD_TRM_SN", term)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 : A 가 도메인을 수정하는 동안 B 가 예전 행으로 삭제 → B 409 (기다리지 않음 또는 다시 읽은 뒤), 도메인은 A 의 수정대로 남는다")
    void deleteWhileUpdating() throws Exception {
        GenerationFixture f = fixture("c4c-d5");
        long domain = unusedDomain(f, "VARCHAR", 61);
        StandardDomainDto seen = domainRow(domain);
        StandardDomainDto edit = domainRow(domain);
        edit.setCommonStandardDomainDescription("A 의 수정");
        String[] r = support().both(f.user, "ROLE_ADMIN",
                () -> dictionary.updateStandardDomain(f.user, edit),
                () -> dictionary.deleteDataDictionaryStandardDomains(List.of(seen), f.user));
        ConcurrentEditC4cSupport.report("도메인 수정 vs 삭제", r);
        assertThat(r).containsExactly("OK", "MODEL_CONFLICT");
        assertThat(jdbc.queryForObject("select DMN_EXPLN from TB_STD_DMN where STD_DMN_SN = ?", String.class, domain)).isEqualTo("A 의 수정");
    }

    @Test
    @DisplayName("동시 : A 가 그 도메인을 쓰는 용어를 등록하는 동안 B 가 도메인 삭제 → B 는 A 커밋 뒤 참조를 보고 RELATION_EXISTS, 용어·도메인 모두 남는다")
    void deleteWhileNewReference() throws Exception {
        GenerationFixture f = fixture("c4c-d6");
        long domain = unusedDomain(f, "VARCHAR", 71);
        String domainName = jdbc.queryForObject("select DMN_NM from TB_STD_DMN where STD_DMN_SN = ?", String.class, domain);
        StandardDomainDto seen = domainRow(domain);
        String[] r = support().both(f.user, "ROLE_ADMIN",
                () -> dictionary.insertStandardTerms(f.user, StandardTermDto.builder().revisionNumber(1).commonStandardTermName("고객_주문")
                        .commonStandardTermDescription("새 참조").commonStandardTermAbbreviation("CUST_ORD").commonStandardDomainName(domainName).synonyms("").build()),
                () -> dictionary.deleteDataDictionaryStandardDomains(List.of(seen), f.user));
        ConcurrentEditC4cSupport.report("용어 등록(새 참조) vs 도메인 삭제", r);
        assertThat(r).containsExactly("OK", "RELATION_EXISTS");
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", domain)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_STD_TRM where STD_DMN_SN = ?", Integer.class, domain)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 : 같은 두 도메인을 반대 순서로 일괄 삭제 → 교착 없이 하나는 지우고 다른 하나는 이미 없는 행을 건너뛴다")
    void oppositeOrderBulkDelete() throws Exception {
        GenerationFixture f = fixture("c4c-d7");
        long d1 = unusedDomain(f, "VARCHAR", 81);
        long d2 = unusedDomain(f, "VARCHAR", 82);
        StandardDomainDto r1 = domainRow(d1);
        StandardDomainDto r2 = domainRow(d2);
        String[] r = support().both(f.user, "ROLE_ADMIN",
                () -> dictionary.deleteDataDictionaryStandardDomains(List.of(r1, r2), f.user),
                () -> dictionary.deleteDataDictionaryStandardDomains(List.of(r2, r1), f.user));
        ConcurrentEditC4cSupport.report("도메인 일괄 삭제 반대 순서", r);
        assertThat(r).containsExactly("OK", "OK");
        assertThat(exists("TB_STD_DMN", "STD_DMN_SN", d1) + exists("TB_STD_DMN", "STD_DMN_SN", d2)).isZero();
    }

    @Test
    @DisplayName("동시 : A 가 단어를 승인하는 동안 B 가 예전 행으로 삭제 → B 는 승인된 단어라 거절 또는 409, 단어는 승인된 채 남는다")
    void deleteWhileApproving() throws Exception {
        GenerationFixture f = fixture("c4c-d8");
        long word = wordId(f, "지점");
        jdbc.update("update TB_STD_WORD set APRV_YN = false where STD_WORD_SN = ?", word);
        StandardWordDto seen = wordRow(word);
        String[] r = support().both(f.user, "ROLE_ADMIN",
                () -> dictionary.approvalStandardWords(List.of(wordRow(word)), f.user, true),
                () -> dictionary.deleteDataDictionaryStandardWords(List.of(seen), f.user));
        ConcurrentEditC4cSupport.report("단어 승인 vs 삭제", r);
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isIn("MODEL_CONFLICT", "APPROVED_DATA_CANNOT_BE_DELETED");
        assertThat(exists("TB_STD_WORD", "STD_WORD_SN", word)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select APRV_YN from TB_STD_WORD where STD_WORD_SN = ?", Boolean.class, word)).isTrue();
    }

    @Test
    @DisplayName("승인된 단어는 직접 고칠 수 없다 (APPROVED_DATA_CANNOT_BE_UPDATE, 값 그대로) → 승인 취소 뒤에는 수정된다")
    void approvedWordCannotBeUpdated() {
        GenerationFixture f = fixture("c4c-d9");
        long word = wordId(f, "지점");
        StandardWordDto edit = wordRow(word);
        String before = edit.getCommonStandardWordDescription();
        edit.setCommonStandardWordDescription("승인된 단어 직접 수정");
        assertThat(code(() -> dictionary.updateStandardWords(f.user, edit))).isEqualTo("APPROVED_DATA_CANNOT_BE_UPDATE");
        assertThat(jdbc.queryForObject("select WORD_EXPLN from TB_STD_WORD where STD_WORD_SN = ?", String.class, word)).isEqualTo(before);
        dictionary.approvalStandardWords(List.of(wordRow(word)), f.user, false);
        StandardWordDto again = wordRow(word);
        again.setCommonStandardWordDescription("승인 취소 뒤 수정");
        dictionary.updateStandardWords(f.user, again);
        assertThat(jdbc.queryForObject("select WORD_EXPLN from TB_STD_WORD where STD_WORD_SN = ?", String.class, word)).isEqualTo("승인 취소 뒤 수정");
    }

    @Test
    @DisplayName("도메인 승인 취소 : 영향 범위(용어·승인된 용어·속성·컬럼 수)를 알려 주고, 취소해도 참조와 그 승인 상태는 그대로 · 다른 프로젝트 도메인은 조회되지 않는다")
    void domainRevokeImpact() {
        GenerationFixture f = fixture("c4c-d10");
        long entity = f.entity("고객");
        long attribute = f.attr(entity, "고객번호", true, "CUST_NO");
        f.pk(entity, attribute);
        generate(f, entity);
        long term = jdbc.queryForObject("select STD_TRM_SN from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", Long.class, attribute);
        long domain = jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, term);
        long second = f.term("CUST_NM", "VARCHAR", 20); // 같은 도메인의 두 번째 용어 (미승인)
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", second);

        DictionaryUsageDto.DomainImpact impact = usageApi.domainImpact(f.user, domain).getBody();
        assertThat(impact.termCount()).isEqualTo(2);
        assertThat(impact.approvedTermCount()).isEqualTo(1);
        assertThat(impact.attributeCount()).isEqualTo(1);
        assertThat(impact.columnCount()).isEqualTo(jdbc.queryForObject("select count(*) from TB_COL where STD_TRM_SN in (?, ?)", Long.class, term, second));
        assertThat(impact.columnCount()).isPositive();

        dictionary.approvalStandardDomains(List.of(domainRow(domain)), f.user, false);
        assertThat(jdbc.queryForObject("select APRV_YN from TB_STD_DMN where STD_DMN_SN = ?", Boolean.class, domain)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from TB_STD_TRM where STD_DMN_SN = ?", Integer.class, domain)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select APRV_YN from TB_STD_TRM where STD_TRM_SN = ?", Boolean.class, term)).isTrue();
        assertThat(usageApi.domainImpact(f.user, domain).getBody().isApproval()).isFalse();

        GenerationFixture other = fixture("c4c-d10o");
        assertThatThrownBy(() -> usageApi.domainImpact(other.user, domain)).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_DOMAIN_DATA);
    }
}
