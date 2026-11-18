package com.lexorma;

import com.lexorma.common.exception.CustomException;
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
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.Group;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.Member;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.Search;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.dictionary.service.TermQualityService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-5 5차 : 표준 용어 중복 = 같은 프로젝트 + 영문약어 대소문자 무시 + 실제 도메인 ID. 등록·키를 바꾸는 수정은 거절(400 DUPLICATE_TERM_DATA),
 * 엑셀은 같은 키의 행을 건너뛴다(기존 계약), 예전 중복 데이터는 진단만 하고 바꾸지 않는다, 동시 실행은 프로젝트 행 배타 잠금으로 하나만.
 * 예전 중복 데이터는 지금 API 로 만들 수 없어 JDBC 로 넣는다. H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StandardTermDuplicateIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(878_000_000L);
    private static final int COL_NAME = 2;
    private static final int COL_ABBREVIATION = 4;
    private static final int COL_DOMAIN = 5;

    @Autowired DataDictionaryService dictionary;
    @Autowired TermQualityService termQuality;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 등록

    @Test
    @DisplayName("등록 : 같은 약어(대소문자 무시) + 같은 도메인 ID 는 400, 도메인명 대소문자만 달라도 같은 ID 면 400, 다른 도메인 ID·다른 프로젝트는 허용")
    void registration() {
        Context a = project("m5-5-register-a");
        Context b = project("m5-5-register-b");
        for (Context context : List.of(a, b)) {
            words(context);
            domain(context, "CODE");
            domain(context, "NUM");
        }
        long first = insertTerm(a, "회원_번호", "MBR_NO", "CODE");

        for (String[] request : List.of(new String[]{"회원_번호", "MBR_NO", "CODE"}, new String[]{"멤버_번호", "mbr_no", "CODE"},
                new String[]{"멤버_번호", "mbr_NO", "code"}, new String[]{"회원_번호", "MBR_NO", "Code"})) {
            assertThatThrownBy(() -> insertTerm(a, request[0], request[1], request[2])).as(String.join("/", request))
                    .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_TERM_DATA);
        }
        assertThat(ErrorCode.DUPLICATE_TERM_DATA.getHttpStatus().value()).isEqualTo(400);
        assertThat(terms(a)).as("거절된 요청은 아무것도 남기지 않는다").containsExactly(first);

        // 같은 약어라도 다른 도메인 ID 는 허용 (기존 정책)
        insertTerm(a, "회원_번호", "MBR_NO", "NUM");
        // 다른 프로젝트의 같은 약어 + 같은 이름 도메인은 허용
        insertTerm(b, "회원_번호", "MBR_NO", "CODE");
        insertTerm(b, "멤버_번호", "mbr_NO", "NUM");
        assertThat(terms(a)).hasSize(2);
        assertThat(terms(b)).hasSize(2);

        // 화면의 중복 확인 API 도 같은 판정 (대소문자 무시, 도메인 ID)
        assertThat(dictionary.checkStandardTermDuplicate("mbr_no", "code", a.user)).containsEntry("duplicated", true);
        assertThat(dictionary.checkStandardTermDuplicate("MBR_NO", "없는도메인", a.user)).containsEntry("duplicated", false);
        assertThat(dictionary.checkStandardTermDuplicate("STTS_CD", "CODE", a.user)).containsEntry("duplicated", false);
    }

    @Test
    @DisplayName("등록 오류 순서는 예전과 같다 : 단어 수 → 중복 → 단어 → 도메인(없음 400·모호 409). 모호한 도메인명이면 중복을 판단하지 않고 409, 아무것도 남지 않는다")
    void registrationErrorOrder() {
        Context a = project("m5-5-order");
        words(a);
        domain(a, "CODE");
        insertTerm(a, "회원_번호", "MBR_NO", "CODE");

        assertThatThrownBy(() -> insertTerm(a, "회원 번호", "MBR_NO", "CODE")).extracting("errorCode").isEqualTo(ErrorCode.TERM_WORD_COUNT_MISMATCH);
        assertThatThrownBy(() -> insertTerm(a, "없음_번호", "XX_NO", "없는도메인")).as("단어가 도메인보다 먼저")
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_WORD_DATA);
        assertThatThrownBy(() -> insertTerm(a, "회원_번호", "MBR_NO", "없는도메인")).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_DOMAIN_DATA);

        insertRawDomain(a, "code"); // 예전 중복 도메인 (DICT-2 이전 데이터)
        assertThatThrownBy(() -> insertTerm(a, "회원_번호", "MBR_NO", "CODE")).as("모호한 도메인 → 중복보다 409")
                .extracting("errorCode").isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN);
        assertThatThrownBy(() -> dictionary.checkStandardTermDuplicate("MBR_NO", "CODE", a.user))
                .extracting("errorCode").isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN);
        assertThat(terms(a)).hasSize(1);
    }

    // ------------------------------------------------------------------ 수정

    @Test
    @DisplayName("수정 : 키(약어 대소문자 무시 + 도메인 ID)를 바꿔 다른 용어와 겹치면 400, 자기 자신·키가 그대로인 수정(약어 대소문자만·설명)·다른 프로젝트와 같은 키는 허용")
    void update() {
        Context a = project("m5-5-update");
        Context b = project("m5-5-update-other");
        words(a);
        words(b);
        domain(a, "CODE");
        long num = domain(a, "NUM");
        domain(b, "CODE");
        domain(b, "NUM");
        long target = insertTerm(a, "회원_번호", "MBR_NO", "CODE");
        long other = insertTerm(a, "번호", "NO", "CODE");
        long pending = insertRawTerm(a, "멤버_번호", "MEMB_NO", num);
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", pending); // 미승인 용어와도 겹치면 안 된다
        insertTerm(b, "번호", "NO", "NUM");

        // 키가 그대로 : 자기 자신만 같은 키 → 허용 (약어 대소문자만 바꾸기, 설명만 바꾸기)
        update(a, target, "회원_번호", "mbr_no", "code", "설명 수정");
        assertThat(abbreviation(target)).isEqualTo("mbr_no");
        // 다른 용어와 겹치는 키로 바꾸기 → 400, 바뀌지 않는다
        assertThatThrownBy(() -> update(a, other, "회원_번호", "MBR_NO", "CODE", "약어 변경")).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_TERM_DATA);
        assertThatThrownBy(() -> update(a, other, "멤버_번호", "memb_no", "NUM", "약어 + 도메인 변경 (미승인 용어와 겹침)"))
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_TERM_DATA);
        assertThat(abbreviation(other)).isEqualTo("NO");
        assertThat(termDomainName(other)).isEqualTo("CODE");
        // 도메인만 바꾸기 : 겹치지 않으면 허용 (다른 프로젝트의 NO/NUM 은 상관없다)
        update(a, other, "번호", "NO", "NUM", "도메인 변경");
        assertThat(termDomainName(other)).isEqualTo("NUM");
    }

    @Test
    @DisplayName("수정 : 이미 중복인 예전 데이터도 키를 그대로 두는 수정(설명 등)은 허용, 한쪽의 키를 바꿔 다른 용어와 겹치면 400. 자동 정리는 없다")
    void updateExistingDuplicates() {
        Context a = project("m5-5-update-legacy");
        words(a);
        long code = domain(a, "CODE");
        long first = insertRawTerm(a, "회원_번호", "MBR_NO", code);
        long second = insertRawTerm(a, "멤버_번호", "mbr_no", code); // 예전 규칙으로 생길 수 있던 중복
        long third = insertTerm(a, "번호", "NO", "CODE");

        update(a, second, "멤버_번호", "mbr_no", "CODE", "설명만 수정");
        update(a, first, "회원_번호", "Mbr_No", "CODE", "약어 대소문자만 수정 — 키 그대로");
        assertThatThrownBy(() -> update(a, third, "회원_번호", "MBR_NO", "CODE", "중복 키로 변경")).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_TERM_DATA);
        assertThat(terms(a)).containsExactlyInAnyOrder(first, second, third);
    }

    // ------------------------------------------------------------------ 진단

    @Test
    @DisplayName("진단 : 예전 중복(약어 대소문자만 다름·같은 도메인 ID)을 오류 그룹으로 묶고, 다른 도메인 ID·다른 프로젝트는 묶지 않는다. 진단은 데이터를 바꾸지 않는다")
    void diagnosis() {
        Context a = project("m5-5-diagnosis-a");
        Context b = project("m5-5-diagnosis-b");
        words(a);
        words(b);
        long code = domain(a, "CODE");
        long num = domain(a, "NUM");
        long codeB = domain(b, "CODE");
        long t1 = insertRawTerm(a, "회원_번호", "MBR_NO", code);
        long t2 = insertRawTerm(a, "멤버_번호", "mbr_no", code);
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", t2); // 승인 상태가 달라도 같은 그룹
        insertRawTerm(a, "회원_번호", "MBR_NO", num);                                  // 다른 도메인 ID → 이 그룹이 아니다
        insertRawTerm(b, "회원_번호", "MBR_NO", codeB);                                // 다른 프로젝트
        String before = snapshot(a);

        List<Group> groups = groups(a, StandardizationRule.TERM_ABBR_DUPLICATE);

        assertThat(snapshot(a)).as("진단은 아무것도 바꾸지 않는다").isEqualTo(before);
        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.severity()).isEqualTo(StandardizationRule.Severity.ERROR);
            assertThat(group.members()).extracting(Member::termId).containsExactly(t1, t2);
            assertThat(group.members()).extracting(Member::abbreviation).containsExactly("MBR_NO", "mbr_no");
            assertThat(group.members()).extracting(Member::isApproval).containsExactly(true, false);
            assertThat(group.members()).extracting(Member::domainId).containsOnly(code);
            assertThat(group.members()).allSatisfy(member -> {
                assertThat(member.registeredAt()).isNotNull();
                assertThat(member.modifiedAt()).isNotNull();
            });
            assertThat(group.evidence()).containsEntry("abbreviation", "MBR_NO").containsEntry("domainName", "CODE");
        });
        assertThat(groups(b, StandardizationRule.TERM_ABBR_DUPLICATE)).isEmpty();
        assertThat(groups(a, StandardizationRule.TERM_ABBR_SHARED)).as("같은 약어·다른 도메인은 기존 주의 규칙 그대로").hasSize(1);
    }

    // ------------------------------------------------------------------ 엑셀

    @Test
    @DisplayName("엑셀 : 같은 키(약어 대소문자 무시 + 도메인 ID)의 행은 DB 에 있든 파일 안에서 앞에 나왔든 건너뛴다 (오류 아님 — 기존 계약), 도메인명 대소문자만 다른 행도 같은 키, 다른 도메인 ID 는 등록")
    void excel() throws IOException {
        Context a = project("m5-5-excel");
        String domainName = templateTermDomain("SMS_USE_YN");
        String gisDomainName = templateTermDomain("GIS_DATA_YN");
        assertThat(domainName.toLowerCase(Locale.ROOT)).as("템플릿 도메인명에 영문이 있어야 대소문자를 바꿀 수 있다").isNotEqualTo(domainName.toUpperCase(Locale.ROOT));
        // 1차 업로드 : 템플릿 단어·도메인을 넣고 용어 하나
        upload(a, List.of(new TermRow("SMS_사용_여부", "SMS_USE_YN", domainName)));
        long domainId = jdbc.queryForObject("select STD_DMN_SN from TB_STD_DMN where PRJ_SN = ? and DMN_NM = ?", Long.class, a.projectId, domainName);
        long gisDomainId = jdbc.queryForObject("select STD_DMN_SN from TB_STD_DMN where PRJ_SN = ? and DMN_NM = ?", Long.class, a.projectId, gisDomainName);
        // 예전 데이터 : 소문자 약어로 같은 도메인에 들어간 용어
        long legacy = insertRawTerm(a, "GIS_자료_여부", "gis_data_yn", gisDomainId);
        long otherDomain = insertRawDomain(a, "M55 다른 도메인");

        upload(a, List.of(
                new TermRow("GIS_자료_여부", "GIS_DATA_YN", gisDomainName),                          // DB 의 gis_data_yn + 같은 도메인 → 건너뜀
                new TermRow("SMS_사용_여부", "SMS_USE_YN", domainName.toLowerCase(Locale.ROOT)),     // DB 와 같은 키 (도메인명 대소문자만 다름) → 건너뜀
                new TermRow("HTML_사용_여부", "HTML_USE_YN", domainName),                            // 새 키 → 등록
                new TermRow("HTML사용여부", "HTML_USE_YN", domainName.toUpperCase(Locale.ROOT)),     // 파일 안 같은 키 → 건너뜀
                new TermRow("HTML_사용_여부", "HTML_USE_YN", "M55 다른 도메인")));                    // 다른 도메인 ID → 등록

        assertThat(count("select count(*) from TB_STD_TRM where PRJ_SN = ? and lower(TRM_ENG_ABBR_NM) = 'gis_data_yn'", a.projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select TRM_ENG_ABBR_NM from TB_STD_TRM where STD_TRM_SN = ?", String.class, legacy)).as("예전 데이터 그대로").isEqualTo("gis_data_yn");
        assertThat(count("select count(*) from TB_STD_TRM where PRJ_SN = ? and TRM_ENG_ABBR_NM = 'SMS_USE_YN'", a.projectId)).isEqualTo(1);
        assertThat(jdbc.queryForList("select STD_DMN_SN from TB_STD_TRM where PRJ_SN = ? and TRM_ENG_ABBR_NM = 'HTML_USE_YN' order by STD_DMN_SN", Long.class, a.projectId))
                .containsExactlyInAnyOrder(domainId, otherDomain);
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    @DisplayName("동시 등록 : 같은 키(약어 대소문자만 다름 포함) → 하나만 성공·나머지 400, 다른 도메인 ID·다른 프로젝트는 모두 성공, 교착·500 없음")
    void concurrentRegistration() throws Exception {
        for (int round = 0; round < 3; round++) {
            Context a = project("m5-5-race-" + round);
            words(a);
            domain(a, "CODE");
            List<Throwable> failures = race(List.of(() -> insertTerm(a, "회원_번호", "MBR_NO", "CODE"), () -> insertTerm(a, "멤버_번호", "mbr_NO", "code"),
                    () -> insertTerm(a, "회원_번호", "MBR_NO", "Code")));
            assertThat(count("select count(*) from TB_STD_TRM where PRJ_SN = ?", a.projectId)).as("하나만 남는다").isEqualTo(1);
            assertThat(failures).hasSize(2).allSatisfy(e -> assertThat(e).isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_TERM_DATA));
        }
        Context x = project("m5-5-race-x");
        Context y = project("m5-5-race-y");
        for (Context context : List.of(x, y)) {
            words(context);
            domain(context, "CODE");
            domain(context, "NUM");
        }
        assertThat(race(List.of(() -> insertTerm(x, "회원_번호", "MBR_NO", "CODE"), () -> insertTerm(x, "회원_번호", "MBR_NO", "NUM"),
                () -> insertTerm(y, "회원_번호", "MBR_NO", "CODE")))).as("다른 도메인 ID·다른 프로젝트는 서로 막지 않는다").isEmpty();
    }

    @Test
    @DisplayName("동시 실행 : 등록 ↔ 같은 키로 바꾸는 수정, 엑셀 업로드 ↔ 같은 키 등록 — 어느 쪽이 먼저든 같은 키는 하나만 남는다")
    void concurrentUpdateAndUpload() throws Exception {
        for (int round = 0; round < 3; round++) {
            Context a = project("m5-5-race-update-" + round);
            words(a);
            domain(a, "CODE");
            long other = insertTerm(a, "번호", "NO", "CODE");
            List<Throwable> failures = race(List.of(() -> insertTerm(a, "회원_번호", "MBR_NO", "CODE"),
                    () -> update(a, other, "회원_번호", "mbr_no", "CODE", "같은 키로 변경")));
            assertThat(count("select count(*) from TB_STD_TRM where PRJ_SN = ? and lower(TRM_ENG_ABBR_NM) = 'mbr_no'", a.projectId)).isEqualTo(1);
            assertThat(failures).singleElement().satisfies(e -> assertThat(e).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_TERM_DATA));
        }

        String domainName = templateTermDomain("GIS_DATA_YN");
        for (int round = 0; round < 3; round++) {
            Context a = project("m5-5-race-upload-" + round);
            upload(a, List.of(new TermRow("SMS_사용_여부", "SMS_USE_YN", null))); // 템플릿 단어·도메인
            List<Throwable> failures = race(List.of(() -> {
                try {
                    upload(a, List.of(new TermRow("GIS_자료_여부", "GIS_DATA_YN", null)));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, () -> insertTerm(a, "GIS_자료_여부", "GIS_DATA_YN", domainName.toLowerCase(Locale.ROOT))));
            assertThat(count("select count(*) from TB_STD_TRM where PRJ_SN = ? and lower(TRM_ENG_ABBR_NM) = 'gis_data_yn'", a.projectId)).isEqualTo(1);
            // 업로드가 늦으면 그 행을 건너뛰고(오류 없음), 등록이 늦으면 400
            assertThat(failures).allSatisfy(e -> assertThat(e).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_TERM_DATA));
        }
    }

    // ------------------------------------------------------------------ 보조

    private record Context(Long projectId, AccountDto user) {}

    private record TermRow(String name, String abbreviation, String domainName) {}

    private Context project(String name) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + System.nanoTime()).isActive(true).isDicAbbrUsed(true).account(admin).build());
        return new Context(project.getId(), SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN"));
    }

    private void login(Context context) {
        SecurityTestSupport.loginAs(projectRepository, context.projectId, context.user.getId(), "ROLE_ADMIN");
    }

    /** 회원 MBR, 멤버 mbr(약어 소문자), 번호 NO */
    private void words(Context context) {
        word(context, "회원", "MBR");
        word(context, "멤버", "mbr");
        word(context, "번호", "NO");
    }

    private void word(Context context, String name, String abbreviation) {
        login(context);
        dictionary.insertStandardWord(context.user, StandardWordDto.builder().revisionNumber(1).commonStandardWordName(name)
                .commonStandardWordAbbreviation(abbreviation).commonStandardWordEnglishName(abbreviation).commonStandardWordDescription(name)
                .isFormatWord("N").commonStandardDomainCategory("-").synonyms("").restrictedWords("").build());
    }

    private long domain(Context context, String name) {
        login(context);
        dictionary.insertStandardDomain(context.user, StandardDomainDto.builder().revisionNumber(1).commonStandardDomainGroupName("문자")
                .commonStandardDomainCategory("번호").commonStandardDomainName(name).commonStandardDomainDescription("M-5 5차")
                .dataType("VARCHAR").dataLength(10).dataDecimalLength(0).storageFormat("").displayFormat("").unit("").allowedValues("").build());
        return jdbc.queryForObject("select STD_DMN_SN from TB_STD_DMN where PRJ_SN = ? and DMN_NM = ?", Long.class, context.projectId, name);
    }

    /** 지금 API 로는 만들 수 없는 예전 중복 도메인 */
    private long insertRawDomain(Context context, String name) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '문자', '번호', ?, 'M-5 5차', 'VARCHAR', 10, 0, true, current_timestamp, current_timestamp, 'test', 'test')", id, context.projectId, name);
        return id;
    }

    /** 지금 API 로는 만들 수 없는 예전 중복 용어 (단어 매핑 없이) */
    private long insertRawTerm(Context context, String name, String abbreviation, long domainId) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, 'M-5 5차', ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", id, context.projectId, name, abbreviation, domainId);
        return id;
    }

    /** 화면과 같은 용어 등록 경로. 새 용어 ID */
    private long insertTerm(Context context, String name, String abbreviation, String domainName) {
        login(context);
        dictionary.insertStandardTerms(context.user, term(null, name, abbreviation, domainName, "M-5 5차"));
        return jdbc.queryForList("select STD_TRM_SN from TB_STD_TRM where PRJ_SN = ? and TRM_NM = ? and TRM_ENG_ABBR_NM = ? order by STD_TRM_SN desc",
                Long.class, context.projectId, name, abbreviation).getFirst();
    }

    private void update(Context context, long id, String name, String abbreviation, String domainName, String description) {
        login(context);
        dictionary.updateStandardTerms(context.user, term(id, name, abbreviation, domainName, description));
    }

    private static StandardTermDto term(Long id, String name, String abbreviation, String domainName, String description) {
        return StandardTermDto.builder().id(id).revisionNumber(1).commonStandardTermName(name).commonStandardTermDescription(description)
                .commonStandardTermAbbreviation(abbreviation).commonStandardDomainName(domainName).synonyms("").build();
    }

    private List<Group> groups(Context context, StandardizationRule rule) {
        login(context);
        return termQuality.selectGroups(context.user, new Search(rule, null, null, null), PageRequest.of(0, 50)).getContent();
    }

    private List<Long> terms(Context context) {
        return jdbc.queryForList("select STD_TRM_SN from TB_STD_TRM where PRJ_SN = ? order by STD_TRM_SN", Long.class, context.projectId);
    }

    private String abbreviation(long termId) {
        return jdbc.queryForObject("select TRM_ENG_ABBR_NM from TB_STD_TRM where STD_TRM_SN = ?", String.class, termId);
    }

    private String termDomainName(long termId) {
        return jdbc.queryForObject("select d.DMN_NM from TB_STD_TRM t join TB_STD_DMN d on d.STD_DMN_SN = t.STD_DMN_SN where t.STD_TRM_SN = ?", String.class, termId);
    }

    /** 프로젝트의 용어 행 전체 (진단 전후 비교) */
    private String snapshot(Context context) {
        return String.valueOf(jdbc.queryForList("select STD_TRM_SN, TRM_NM, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, MDFCN_DT from TB_STD_TRM where PRJ_SN = ? order by STD_TRM_SN",
                context.projectId));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** 실제 엑셀 업로드 경로 (도메인·단어 시트는 템플릿 그대로, 용어 시트만 바꾼다) */
    private void upload(Context context, List<TermRow> rows) throws IOException {
        login(context);
        dictionary.uploadDictionaryExcelFile(templateWithTerms(rows), context.user);
    }

    /** 작업들을 동시에 시작한다. 실패한 예외 목록 (교착이면 시간 초과로 실패) */
    private static List<Throwable> race(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        task.run();
                    } catch (Throwable e) {
                        failures.add(e);
                    } finally {
                        SecurityTestSupport.clear();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    /** 템플릿 용어 시트에서 그 영문약어 행의 도메인명 */
    private static String templateTermDomain(String abbreviation) throws IOException {
        try (InputStream in = StandardTermDuplicateIntegrationTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx");
             Workbook workbook = new XSSFWorkbook(in)) {
            DataFormatter formatter = new DataFormatter();
            for (Row row : workbook.getSheetAt(0)) {
                if (abbreviation.equals(formatter.formatCellValue(row.getCell(COL_ABBREVIATION)))) return formatter.formatCellValue(row.getCell(COL_DOMAIN));
            }
            throw new AssertionError("템플릿에 " + abbreviation + " 행이 있어야 한다");
        }
    }

    /** 템플릿에서 용어 시트(0)만 시험용 행으로 바꾼다. 나머지 열은 템플릿의 같은 약어 행(없으면 GIS_DATA_YN 행)을 복사하고, domainName 이 있으면 도메인명 열을 바꾼다 */
    private static MockMultipartFile templateWithTerms(List<TermRow> rows) throws IOException {
        try (InputStream in = StandardTermDuplicateIntegrationTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx");
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet terms = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Map<String, List<String>> templateByAbbreviation = new HashMap<>();
            for (int r = 1; r <= terms.getLastRowNum(); r++) {
                Row row = terms.getRow(r);
                if (row == null) continue;
                List<String> values = new ArrayList<>();
                for (int c = 0; c < 12; c++) values.add(formatter.formatCellValue(row.getCell(c)));
                templateByAbbreviation.putIfAbsent(values.get(COL_ABBREVIATION), values);
            }
            for (int r = terms.getLastRowNum(); r >= 1; r--) {
                Row row = terms.getRow(r);
                if (row != null) terms.removeRow(row);
            }
            for (int i = 0; i < rows.size(); i++) {
                TermRow termRow = rows.get(i);
                List<String> source = templateByAbbreviation.getOrDefault(termRow.abbreviation(), templateByAbbreviation.get("GIS_DATA_YN"));
                Row row = terms.createRow(i + 1);
                for (int c = 0; c < source.size(); c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(source.get(c));
                }
                row.getCell(COL_NAME).setCellValue(termRow.name());
                row.getCell(COL_ABBREVIATION).setCellValue(termRow.abbreviation());
                if (termRow.domainName() != null) row.getCell(COL_DOMAIN).setCellValue(termRow.domainName());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile("file", "m5-5-term-test.xlsx", null, out.toByteArray());
        }
    }
}
