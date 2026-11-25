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
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.dictionary.service.StandardDomainService;
import com.lexorma.data.dictionary.service.StandardDomainService.DuplicateGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [DICT-2] 표준 도메인 이름 중복 : 같은 프로젝트에서 대소문자를 무시하고 같은 이름은 등록 거절(400 DUPLICATE_STANDARD_DOMAIN_NAME), 다른 프로젝트는 허용,
 * 예전 중복 데이터는 진단만(지우거나 합치지 않음), 이름으로 찾은 도메인이 여럿이면 임의로 고르지 않고 409 AMBIGUOUS_STANDARD_DOMAIN + 전체 롤백,
 * 동시 등록은 프로젝트 행 배타 잠금으로 하나만. 예전 중복 데이터는 지금 API 로 만들 수 없어 JDBC 로 넣는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StandardDomainDuplicateIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(876_000_000L);

    @Autowired DataDictionaryService dictionary;
    @Autowired StandardDomainService domains;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("등록 : CODE 성공 → code·Code 거절(400), 다른 이름 성공, 다른 프로젝트의 CODE 성공, 미승인 기존 도메인도 막는다, 오류에 다른 프로젝트 정보 없음")
    void registration() {
        Context a = project("dict2-register-a");
        Context b = project("dict2-register-b");
        register(a, "CODE");
        for (String name : List.of("code", "Code", "cOdE")) {
            assertThatThrownBy(() -> register(a, name)).isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_STANDARD_DOMAIN_NAME);
        }
        assertThat(ErrorCode.DUPLICATE_STANDARD_DOMAIN_NAME.getHttpStatus().value()).isEqualTo(400);
        register(a, "STATUS");
        register(b, "CODE"); // 다른 프로젝트
        assertThat(names(a)).containsExactlyInAnyOrder("CODE", "STATUS");
        assertThat(names(b)).containsExactly("CODE");

        // 미승인 도메인도 같은 이름이면 막는다 (승인 상태와 무관)
        insertRaw(a, "PENDING", false);
        assertThatThrownBy(() -> register(a, "pending")).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_STANDARD_DOMAIN_NAME);
        // 중복 판단은 현재 프로젝트만 : B 에만 있는 이름은 A 에서 등록된다
        insertRaw(b, "ONLY_B", true);
        register(a, "only_b");
    }

    @Test
    @DisplayName("예전 중복 데이터 : 진단 = 현재 프로젝트의 대소문자 무시 묶음·ID·원래 이름·승인·참조 수, 다른 프로젝트 제외, 진단은 데이터를 바꾸지 않는다, 새 등록은 막힌다")
    void diagnosis() {
        Context a = project("dict2-diagnosis-a");
        Context b = project("dict2-diagnosis-b");
        assertThat(domains.findDuplicateGroups(a.projectId)).as("중복 없는 프로젝트").isEmpty();

        long same1 = insertRaw(a, "DUP", true);
        long same2 = insertRaw(a, "DUP", false);
        long lower = insertRaw(a, "dup", true);
        long caseOnly1 = insertRaw(a, "Flag", true);
        long caseOnly2 = insertRaw(a, "FLAG", true);
        insertRaw(a, "UNIQUE", true);
        insertRaw(b, "DUP", true);
        insertRaw(b, "dup", true); // 다른 프로젝트의 묶음은 A 진단에 나오지 않는다
        long term = insertTerm(a, same1);
        // 용어를 쓰는 컬럼 하나 (진단은 프로젝트·용어로만 센다). V6 부터 컬럼에는 부모 테이블이 필수라 빈 테이블 하나를 둔다 (M7-U05)
        long schema = IDS.incrementAndGet();
        long table = IDS.incrementAndGet();
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'DUP_SCHM', false)", schema, a.projectId);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values (?, ?, ?, 'DUP_TBL', false)", table, a.projectId, schema);
        jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, COL_NM, STD_TRM_SN, APRV_YN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, ?, 'DUP_COL', ?, false, 1, current_timestamp, current_timestamp, 'test', 'test')", IDS.incrementAndGet(), a.projectId, table, term);
        String before = snapshot(a);

        List<DuplicateGroup> groups = domains.findDuplicateGroups(a.projectId);

        assertThat(snapshot(a)).as("진단은 아무것도 바꾸지 않는다").isEqualTo(before);
        assertThat(groups).extracting(DuplicateGroup::normalizedName).containsExactly("dup", "flag");
        DuplicateGroup dup = groups.getFirst();
        assertThat(dup.count()).isEqualTo(3);
        assertThat(dup.members()).extracting(m -> m.domainId()).containsExactly(same1, same2, lower);
        assertThat(dup.members()).extracting(m -> m.domainName()).containsExactly("DUP", "DUP", "dup");
        assertThat(dup.members()).extracting(m -> m.isApproval()).containsExactly(true, false, true);
        assertThat(dup.members()).extracting(m -> m.termCount()).containsExactly(1L, 0L, 0L);
        assertThat(dup.members()).extracting(m -> m.columnCount()).containsExactly(1L, 0L, 0L);
        assertThat(dup.members().getFirst().registeredAt()).isNotNull();
        assertThat(groups.get(1).members()).extracting(m -> m.domainId()).containsExactly(caseOnly1, caseOnly2);
        assertThat(domains.findDuplicateGroups(b.projectId)).singleElement().satisfies(g -> assertThat(g.count()).isEqualTo(2));

        assertThatThrownBy(() -> register(a, "Dup")).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_STANDARD_DOMAIN_NAME);
        assertThat(snapshot(a)).as("거절된 등록 뒤에도 그대로").isEqualTo(before);
    }

    @Test
    @DisplayName("이름 조회 = 대소문자 무시 : 0건 null(NOT_FOUND), 1건 그 도메인(요청 대소문자와 달라도), 2건 이상 409 — 정확히 같은 이름·승인된 것을 고르지 않는다, 다른 프로젝트 제외")
    void lookupIgnoresCase() {
        Context a = project("dict2-lookup-a");
        Context b = project("dict2-lookup-b");
        long code = insertRaw(a, "CODE", true);
        long umlaut = insertRaw(a, "ÄNDERUNG코드", true);
        insertRaw(b, "OTHERONLY", true);
        for (String name : List.of("CODE", "code", "Code", "CoDe")) {
            assertThat(domains.findByCommonStandardDomainNameAndProjectId(name, a.projectId).getId()).as(name).isEqualTo(code);
        }
        assertThat(domains.findByCommonStandardDomainNameAndProjectId("änderung코드", a.projectId).getId()).as("한글·비ASCII 대소문자").isEqualTo(umlaut);
        assertThat(domains.findByCommonStandardDomainNameAndProjectId("NONE", a.projectId)).isNull();
        assertThat(domains.findByCommonStandardDomainNameAndProjectId(null, a.projectId)).isNull();
        assertThat(domains.findByCommonStandardDomainNameAndProjectId("otheronly", a.projectId)).as("다른 프로젝트의 도메인은 찾지 않는다").isNull();
        assertThat(domains.findByCommonStandardDomainNameAndProjectId(" CODE", a.projectId)).as("공백은 정규화하지 않는다").isNull();

        insertRaw(a, "code", false); // 예전 중복 (대소문자만 다름, 미승인)
        for (String name : List.of("CODE", "code", "CoDe")) {
            assertThatThrownBy(() -> domains.findByCommonStandardDomainNameAndProjectId(name, a.projectId)).as(name + " : 정확히 같은 이름도 고르지 않는다")
                    .extracting("errorCode").isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN);
        }
        assertThat(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN.getHttpStatus().value()).isEqualTo(409);
        assertThat(domains.findByCommonStandardDomainNameAndProjectId("CODE", b.projectId)).as("B 에는 CODE 가 없다").isNull();
        assertThat(names(a)).as("조회는 데이터를 바꾸지 않는다").containsExactlyInAnyOrder("CODE", "ÄNDERUNG코드", "code");
    }

    @Test
    @DisplayName("용어 등록·수정 : 도메인명 대소문자가 달라도 그 도메인 ID 로 연결(도메인 이름 그대로), 예전 중복이면 409 + 롤백, 없으면 NOT_FOUND, 다른 프로젝트 도메인은 안 쓴다")
    void termsUseCaseInsensitiveLookup() {
        Context a = project("dict2-term-a");
        Context b = project("dict2-term-b");
        insertWord(a, "회원", "MBR", "-");
        insertWord(a, "번호", "NO", "번호");
        long code = insertRaw(a, "CODE", true);
        long status = insertRaw(a, "STATUS", true);
        insertRaw(b, "ONLYB", true);

        dictionary.insertStandardTerms(a.user, term(null, "code"));
        long termId = jdbc.queryForObject("select STD_TRM_SN from TB_STD_TRM where PRJ_SN = ?", Long.class, a.projectId);
        assertThat(termDomain(termId)).as("요청 'code' → CODE").isEqualTo(code);
        assertThat(jdbc.queryForObject("select APRV_YN from TB_STD_TRM where STD_TRM_SN = ?", Boolean.class, termId)).isTrue();
        // 승인된 용어는 고칠 수 없다 (M-5 7차) : 승인 취소 뒤 수정
        dictionary.approvalStandardTerms(List.of(StandardTermDto.builder().id(termId).build()), a.user, false);

        transactions.executeWithoutResult(status1 -> dictionary.updateStandardTerms(a.user, term(termId, "status")));
        assertThat(termDomain(termId)).as("수정 : 다른 도메인으로, 대소문자 무시").isEqualTo(status);
        transactions.executeWithoutResult(status1 -> dictionary.updateStandardTerms(a.user, term(termId, "Code")));
        assertThat(termDomain(termId)).isEqualTo(code);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status1 -> dictionary.updateStandardTerms(a.user, term(termId, "없는도메인"))))
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_DOMAIN_DATA);
        assertThatThrownBy(() -> dictionary.insertStandardTerms(a.user, term(null, "onlyb"))).as("다른 프로젝트의 도메인")
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_DOMAIN_DATA);

        insertRaw(a, "code", true); // 예전 중복
        assertThatThrownBy(() -> transactions.executeWithoutResult(status1 -> dictionary.updateStandardTerms(a.user, term(termId, "CoDe"))))
                .extracting("errorCode").isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN);
        assertThat(termDomain(termId)).as("수정 롤백").isEqualTo(code);
        jdbc.update("delete from TB_STD_TRM_WORD where STD_TRM_SN = ?", termId);
        jdbc.update("delete from TB_STD_TRM_SYNM where STD_TRM_SN = ?", termId);
        jdbc.update("delete from TB_STD_TRM where STD_TRM_SN = ?", termId);
        assertThatThrownBy(() -> dictionary.insertStandardTerms(a.user, term(null, "CODE")))
                .extracting("errorCode").isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN);
        assertThat(count("select count(*) from TB_STD_TRM where PRJ_SN = ?", a.projectId)).as("등록 롤백").isZero();
        assertThat(names(a)).as("도메인은 그대로").containsExactlyInAnyOrder("CODE", "STATUS", "code");
    }

    @Test
    @DisplayName("동시 실행 : 도메인 등록 ↔ 그 이름(대소문자 다름)을 쓰는 용어 등록 — 교착·500 없이 둘 다 성공하거나 용어만 NOT_FOUND, 다른 프로젝트의 같은 이름 조회는 서로 섞이지 않는다")
    void registrationAndLookupRace() throws Exception {
        for (int round = 0; round < 3; round++) {
            Context a = project("dict2-lookup-race-" + round);
            insertWord(a, "회원", "MBR", "-");
            insertWord(a, "번호", "NO", "번호");
            List<Throwable> failures = race(List.of(() -> register(a, "RACEDOM"), () -> {
                SecurityTestSupport.loginAs(projectRepository, a.projectId, a.user.getId(), "ROLE_ADMIN");
                dictionary.insertStandardTerms(a.user, term(null, "racedom"));
            }));
            assertThat(names(a)).containsExactly("RACEDOM");
            assertThat(failures).allSatisfy(e -> assertThat(e).isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_DOMAIN_DATA));
            if (failures.isEmpty()) {
                assertThat(jdbc.queryForObject("select d.DMN_NM from TB_STD_TRM t join TB_STD_DMN d on d.STD_DMN_SN = t.STD_DMN_SN where t.PRJ_SN = ?", String.class, a.projectId))
                        .isEqualTo("RACEDOM");
            }
        }
        Context x = project("dict2-lookup-race-x");
        Context y = project("dict2-lookup-race-y");
        long xId = insertRaw(x, "SAME", true);
        long yId = insertRaw(y, "same", true);
        assertThat(race(List.of(
                () -> assertThat(domains.findByCommonStandardDomainNameAndProjectId("Same", x.projectId).getId()).isEqualTo(xId),
                () -> assertThat(domains.findByCommonStandardDomainNameAndProjectId("Same", y.projectId).getId()).isEqualTo(yId)))).isEmpty();
    }

    @Test
    @DisplayName("수정 : 이름은 수정 대상이 아니다(보낸 이름 무시, 다른 필드만), 중복 데이터의 도메인도 다른 필드 수정 가능, 승인된 도메인은 수정 불가(M8-D03), 없는 도메인 400")
    void update() {
        Context a = project("dict2-update");
        long dup1 = insertRaw(a, "DUP", false); // 승인된 도메인은 고칠 수 없다 (M8-C1 M8-D03) — 미승인 중복 행으로 다른 필드 수정을 본다
        long approved = insertRaw(a, "dup", true);
        dictionary.updateStandardDomain(a.user, StandardDomainDto.builder().id(dup1).commonStandardDomainName("OTHER").commonStandardDomainDescription("바뀐 설명")
                .dataType("VARCHAR").dataLength(20).dataDecimalLength(0).unit("").revisionNumber(2).build());
        assertThat(jdbc.queryForMap("select DMN_NM, DMN_EXPLN, DATA_LEN from TB_STD_DMN where STD_DMN_SN = ?", dup1))
                .containsEntry("DMN_NM", "DUP").containsEntry("DMN_EXPLN", "바뀐 설명").containsEntry("DATA_LEN", 20);
        assertThatThrownBy(() -> dictionary.updateStandardDomain(a.user, StandardDomainDto.builder().id(approved).commonStandardDomainDescription("승인된 도메인 수정")
                .dataType("VARCHAR").dataLength(20).dataDecimalLength(0).unit("").revisionNumber(2).build()))
                .extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        assertThatThrownBy(() -> dictionary.updateStandardDomain(a.user, StandardDomainDto.builder().id(-1L).build()))
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_DOMAIN_DATA);
    }

    @Test
    @DisplayName("동시 등록 : 같은 프로젝트의 CODE·code 동시 → 하나만 성공·나머지 중복 거절, 다른 프로젝트끼리는 모두 성공, 롤백된 등록 뒤 재등록 성공")
    void concurrentRegistration() throws Exception {
        for (int round = 0; round < 3; round++) {
            Context a = project("dict2-race-" + round);
            List<Throwable> failures = race(List.of(() -> register(a, "RACE"), () -> register(a, "race"), () -> register(a, "Race")));
            assertThat(count("select count(*) from TB_STD_DMN where PRJ_SN = ? and lower(DMN_NM) = 'race'", a.projectId)).as("하나만 남는다").isEqualTo(1);
            assertThat(failures).hasSize(2).allSatisfy(e -> assertThat(e).isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_STANDARD_DOMAIN_NAME));
        }

        Context x = project("dict2-race-x");
        Context y = project("dict2-race-y");
        assertThat(race(List.of(() -> register(x, "SHARED"), () -> register(y, "shared")))).as("다른 프로젝트는 서로 막지 않는다").isEmpty();

        Context r = project("dict2-rollback");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            register(r, "ROLLED");
            throw new IllegalStateException("롤백");
        })).isInstanceOf(IllegalStateException.class);
        register(r, "rolled");
        assertThat(names(r)).containsExactly("rolled");
    }

    // ------------------------------------------------------------------ 보조

    private record Context(Long projectId, AccountDto user) {}

    private Context project(String name) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + System.nanoTime()).isActive(true).account(admin).build());
        return new Context(project.getId(), SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN"));
    }

    /** 화면과 같은 등록 경로 (DataDictionaryService → StandardDomainService.insertStandardDomain) — 스레드마다 로그인 */
    private void register(Context context, String name) {
        SecurityTestSupport.loginAs(projectRepository, context.projectId, context.user.getId(), "ROLE_ADMIN");
        dictionary.insertStandardDomain(context.user, StandardDomainDto.builder().revisionNumber(1).commonStandardDomainGroupName("문자")
                .commonStandardDomainCategory("번호").commonStandardDomainName(name).commonStandardDomainDescription("DICT-2")
                .dataType("VARCHAR").dataLength(10).dataDecimalLength(0).storageFormat("").displayFormat("").unit("").allowedValues("").build());
    }

    /** 지금 API 로는 만들 수 없는 예전 중복 데이터 */
    private long insertRaw(Context context, String name, boolean approved) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '문자', '번호', ?, 'DICT-2', 'VARCHAR', 10, 0, ?, current_timestamp, current_timestamp, 'test', 'test')", id, context.projectId, name, approved);
        return id;
    }

    private long insertTerm(Context context, long domainId) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '중복_용어', 'DICT-2', 'DUP_TRM', ?, true, current_timestamp, current_timestamp, 'test', 'test')", id, context.projectId, domainId);
        return id;
    }

    private void insertWord(Context context, String name, String abbreviation, String category) {
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, false, ?, true, true, current_timestamp, current_timestamp, 'test', 'test')",
                IDS.incrementAndGet(), context.projectId, name, abbreviation, abbreviation, name, category);
    }

    private static StandardTermDto term(Long id, String domainName) {
        return StandardTermDto.builder().id(id).commonStandardTermName("회원_번호").commonStandardTermDescription("DICT-2")
                .commonStandardTermAbbreviation("MBR_NO").commonStandardDomainName(domainName).synonyms("").build();
    }

    private long termDomain(long termId) {
        return jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, termId);
    }

    private List<String> names(Context context) {
        return jdbc.queryForList("select DMN_NM from TB_STD_DMN where PRJ_SN = ?", String.class, context.projectId);
    }

    /** 프로젝트의 도메인·용어 행 전체 (진단 전후 비교) */
    private String snapshot(Context context) {
        return jdbc.queryForList("select STD_DMN_SN, DMN_NM, APRV_YN, MDFCN_DT from TB_STD_DMN where PRJ_SN = ? order by STD_DMN_SN", context.projectId)
                + "|" + jdbc.queryForList("select STD_TRM_SN, STD_DMN_SN from TB_STD_TRM where PRJ_SN = ? order by STD_TRM_SN", context.projectId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
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
}
