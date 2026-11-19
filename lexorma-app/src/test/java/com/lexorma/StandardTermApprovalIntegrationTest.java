package com.lexorma;

import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.dictionary.controller.DataDictionaryRestController;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-5 7차 : 표준 용어 영문약어는 저장값(TRM_ENG_ABBR_NM)으로 조회·표시·수정한다 (단어 약어 조합으로 덮어쓰지 않는다),
 * 승인된 용어는 수정 API 에서도 고칠 수 없고(400 APPROVED_DATA_CANNOT_BE_UPDATE — 테이블·컬럼·논리 모델과 같은 오류), 수정은 승인 여부를 바꾸지 않는다.
 * 승인·승인 취소와 수정은 용어 행 잠금으로 직렬화된다. H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StandardTermApprovalIntegrationTest {

    /** 프로젝트 관리자가 아닌 계정 (승인 권한 없음) */
    private static final String MEMBER = "900000777";

    @Autowired DataDictionaryService dictionary;
    @Autowired DataDictionaryRestController controller;
    @Autowired ColumnManageService columns;
    @Autowired StandardTermRepository termRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 영문약어 저장값

    @Test
    @DisplayName("저장 약어 DLX_DT·단어 조합 DEL_DT : 목록·컬럼 용어 검색은 DLX_DT, 설명만 고치면(화면처럼 목록 값 그대로) DLX_DT 유지, 약어 생략도 유지, 실제 변경은 5차 중복 검사")
    void storedAbbreviationIsKept() {
        Context a = project("m5-7-abbr", true);
        word(a, "삭제", "DEL");
        word(a, "일시", "DT");
        domain(a, "DTM");
        long id = insertTerm(a, "삭제_일시", "DEL_DT", "DTM");
        word(a, "지연", "DLY");
        long other = insertTerm(a, "지연_일시", "DLY_DT", "DTM");
        unapprove(a, id, other);
        // 예전 데이터 : 저장값이 단어 조합과 다르다 (API 로 약어를 바꾼 용어·약어 미사용 프로젝트의 엑셀 용어와 같은 상태)
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = 'DLX_DT' where STD_TRM_SN = ?", id);

        assertThat(listed(a, id).getCommonStandardTermAbbreviation()).as("목록·상세(그리드 행)").isEqualTo("DLX_DT");
        login(a, "ROLE_ADMIN");
        List<StandardTermDto> searched = transactions.execute(status -> columns.selectTermData(a.user, "termName", "삭제_일시"));
        assertThat(searched).as("컬럼 화면의 용어 검색")
                .filteredOn(term -> term.getId() == id).singleElement().extracting(StandardTermDto::getCommonStandardTermAbbreviation).isEqualTo("DLX_DT");
        String fromWords = transactions.execute(status -> wordAbbreviation(termRepository.findById(id).orElseThrow()));
        assertThat(fromWords).as("단어 조합은 따로 계산").isEqualTo("DEL_DT");

        // 화면 수정 폼 : 목록 행을 그대로 보내고 설명만 바꾼다
        StandardTermDto row = listed(a, id);
        row.setCommonStandardTermDescription("설명만 수정");
        update(a, row);
        assertThat(stored(id)).isEqualTo("DLX_DT");
        assertThat(description(id)).isEqualTo("설명만 수정");

        // 약어 생략(null) → 저장값 유지
        StandardTermDto omitted = listed(a, id);
        omitted.setCommonStandardTermAbbreviation(null);
        omitted.setCommonStandardTermDescription("약어 생략");
        update(a, omitted);
        assertThat(stored(id)).isEqualTo("DLX_DT");

        // 대소문자만 바꾸기 : 키 그대로라 중복 검사 없이 요청한 대로 (5차)
        StandardTermDto lower = listed(a, id);
        lower.setCommonStandardTermAbbreviation("dlx_dt");
        update(a, lower);
        assertThat(stored(id)).isEqualTo("dlx_dt");

        // 실제 변경 : 다른 용어와 같은 키면 400, 아니면 바뀐다
        StandardTermDto clash = listed(a, id);
        clash.setCommonStandardTermAbbreviation("DLY_DT");
        assertThatThrownBy(() -> update(a, clash)).extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_TERM_DATA);
        assertThat(stored(id)).isEqualTo("dlx_dt");
        StandardTermDto change = listed(a, id);
        change.setCommonStandardTermAbbreviation("DLZ_DT");
        update(a, change);
        assertThat(stored(id)).isEqualTo("DLZ_DT");
    }

    @Test
    @DisplayName("예제(프로젝트 0)의 용어는 목록 약어 = 저장값 = 단어 조합 (회귀)")
    void exampleTermsUnchanged() {
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, 0L, MEMBER, "ROLE_VIEWER");
        List<StandardTermDto> listed = transactions.execute(status -> dictionary.getStandardTermsData(PageRequest.of(0, 100_000), viewer, null, null).getContent());
        Map<Long, String> stored = jdbc.query("select STD_TRM_SN, TRM_ENG_ABBR_NM from TB_STD_TRM where PRJ_SN = 0 and RGTR_ID = 'SYSTEM'",
                rs -> { Map<Long, String> map = new java.util.HashMap<>(); while (rs.next()) map.put(rs.getLong(1), rs.getString(2)); return map; });
        assertThat(stored).hasSizeGreaterThanOrEqualTo(350);
        assertThat(listed).filteredOn(term -> stored.containsKey(term.getId())).hasSize(stored.size())
                .allSatisfy(term -> assertThat(term.getCommonStandardTermAbbreviation()).isEqualTo(stored.get(term.getId())));
        transactions.executeWithoutResult(status -> assertThat(termRepository.findAllById(stored.keySet()))
                .allSatisfy(term -> assertThat(wordAbbreviation(term)).as(term.getCommonStandardTermName()).isEqualTo(term.getCommonStandardTermAbbreviation())));
    }

    @Test
    @DisplayName("약어 미사용 프로젝트의 엑셀 용어 : 목록 약어 = 저장값(단어 영문명), 단어 약어 조합과 다른 용어를 설명만 고쳐도 저장값 유지")
    void englishNameProjectExcel() throws IOException {
        Context a = project("m5-7-excel", false);
        login(a, "ROLE_ADMIN");
        dictionary.uploadDictionaryExcelFile(template(), a.user);
        List<StandardTermDto> listed = transactions.execute(status -> dictionary.getStandardTermsData(PageRequest.of(0, 100_000), a.user, null, null).getContent());
        Map<Long, String> stored = jdbc.query("select STD_TRM_SN, TRM_ENG_ABBR_NM from TB_STD_TRM where PRJ_SN = ?",
                rs -> { Map<Long, String> map = new java.util.HashMap<>(); while (rs.next()) map.put(rs.getLong(1), rs.getString(2)); return map; }, a.projectId);
        assertThat(listed).hasSize(stored.size()).allSatisfy(term -> assertThat(term.getCommonStandardTermAbbreviation()).isEqualTo(stored.get(term.getId())));

        // 단어 약어 조합과 저장값이 다른 용어 하나를 화면처럼 고친다
        Long differing = transactions.execute(status -> termRepository.findAllById(stored.keySet()).stream()
                .filter(term -> !wordAbbreviation(term).equals(term.getCommonStandardTermAbbreviation()))
                .map(StandardTerm::getId).findFirst().orElseThrow());
        String before = stored.get(differing);
        unapprove(a, differing);
        StandardTermDto row = listed(a, differing);
        row.setCommonStandardTermDescription("엑셀 용어 설명 수정");
        update(a, row);
        assertThat(stored(differing)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 승인된 용어 수정 차단

    @Test
    @DisplayName("승인된 용어 : USER·DBA·ADMIN 모두 400 APPROVED_DATA_CANNOT_BE_UPDATE·값 그대로, VIEWER 는 403 그대로. 승인 취소 → 수정(승인 여부 그대로) → 재승인")
    void approvedTermRejected() {
        Context a = project("m5-7-approved", true);
        word(a, "삭제", "DEL");
        word(a, "일시", "DT");
        domain(a, "DTM");
        long id = insertTerm(a, "삭제_일시", "DEL_DT", "DTM");
        assertThat(approved(id)).as("관리자 등록 = 승인 (등록 정책 그대로)").isTrue();
        String before = row(id);

        for (String role : List.of("ROLE_USER", "ROLE_DBA", "ROLE_MANAGER", "ROLE_ADMIN")) {
            StandardTermDto request = listed(a, id);
            request.setCommonStandardTermDescription("승인된 용어 수정 " + role);
            AccountDto user = login(a, role);
            assertThatThrownBy(() -> dictionary.updateStandardTerms(user, request)).as(role).extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        }
        assertThat(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE.getHttpStatus().value()).isEqualTo(400);
        StandardTermDto viewerRequest = listed(a, id);
        AccountDto viewer = login(a, "ROLE_VIEWER");
        assertThatThrownBy(() -> controller.updateStandardTerms(viewer, viewerRequest)).as("VIEWER : 권한 검사가 먼저 (403)").isInstanceOf(AccessDeniedException.class);
        assertThat(row(id)).as("거절된 요청은 아무것도 바꾸지 않는다").isEqualTo(before);

        // 승인 취소(승인 권한자) → USER 수정 → 미승인 그대로 → 재승인
        login(a, "ROLE_ADMIN");
        dictionary.approvalStandardTerms(List.of(StandardTermDto.builder().id(id).build()), a.user, false);
        StandardTermDto edit = listed(a, id);
        edit.setCommonStandardTermDescription("승인 취소 뒤 USER 수정");
        AccountDto member = login(a, "ROLE_USER");
        dictionary.updateStandardTerms(member, edit);
        assertThat(description(id)).isEqualTo("승인 취소 뒤 USER 수정");
        assertThat(approved(id)).isFalse();
        // ADMIN 이 미승인 용어를 고쳐도 자동 승인하지 않는다 (예전에는 승인)
        StandardTermDto adminEdit = listed(a, id);
        adminEdit.setCommonStandardTermDescription("ADMIN 수정");
        dictionary.updateStandardTerms(login(a, "ROLE_ADMIN"), adminEdit);
        assertThat(approved(id)).as("수정은 승인 여부를 바꾸지 않는다").isFalse();
        dictionary.approvalStandardTerms(List.of(StandardTermDto.builder().id(id).build()), login(a, "ROLE_ADMIN"), true);
        assertThat(approved(id)).isTrue();
        assertThat(description(id)).isEqualTo("ADMIN 수정");
    }

    @Test
    @DisplayName("다른 프로젝트의 용어는 승인 여부와 관계없이 NOT_FOUND_TERM_DATA (승인 상태를 드러내지 않는다)")
    void otherProject() {
        Context a = project("m5-7-iso-a", true);
        Context b = project("m5-7-iso-b", true);
        word(a, "삭제", "DEL");
        word(a, "일시", "DT");
        domain(a, "DTM");
        long approvedId = insertTerm(a, "삭제_일시", "DEL_DT", "DTM");
        word(a, "지연", "DLY");
        long pendingId = insertTerm(a, "지연_일시", "DLY_DT", "DTM");
        unapprove(a, pendingId);
        StandardTermDto approvedRequest = listed(a, approvedId);
        StandardTermDto pendingRequest = listed(a, pendingId);
        for (StandardTermDto request : List.of(approvedRequest, pendingRequest)) {
            request.setCommonStandardTermDescription("다른 프로젝트에서");
            assertThatThrownBy(() -> dictionary.updateStandardTerms(login(b, "ROLE_ADMIN"), request)).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_TERM_DATA);
        }
        assertThat(description(approvedId)).isEqualTo("M-5 7차");
        assertThat(description(pendingId)).isEqualTo("M-5 7차");
    }

    // ------------------------------------------------------------------ 동시 실행

    @Test
    @DisplayName("수정 중 승인 : 승인은 수정이 끝날 때까지 기다리고, 수정 내용을 덮어쓰지 않은 채 승인한다 (잠그지 않으면 수정 전 내용으로 덮어쓴다)")
    void approveWhileUpdating() throws Exception {
        Context a = project("m5-7-race-approve", true);
        long id = prepared(a);
        StandardTermDto edit = listed(a, id);
        edit.setCommonStandardTermDescription("수정 중 승인");

        Raced raced = hold(a, () -> dictionary.updateStandardTerms(a.user, edit),
                () -> dictionary.approvalStandardTerms(List.of(StandardTermDto.builder().id(id).build()), a.user, true));
        assertThat(raced.secondWaited).as("승인은 수정의 행 잠금을 기다린다").isTrue();
        assertThat(raced.failure).isNull();
        assertThat(description(id)).isEqualTo("수정 중 승인");
        assertThat(approved(id)).isTrue();
    }

    @Test
    @DisplayName("승인 중 수정 : 수정은 승인이 끝날 때까지 기다리고, 승인된 용어라 거절된다 (값 그대로)")
    void updateWhileApproving() throws Exception {
        Context a = project("m5-7-race-update", true);
        long id = prepared(a);
        StandardTermDto edit = listed(a, id);
        edit.setCommonStandardTermDescription("승인 중 수정");

        Raced raced = hold(a, () -> dictionary.approvalStandardTerms(List.of(StandardTermDto.builder().id(id).build()), a.user, true),
                () -> dictionary.updateStandardTerms(a.user, edit));
        assertThat(raced.secondWaited).isTrue();
        assertThat(raced.failure).extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        assertThat(description(id)).isEqualTo("M-5 7차");
        assertThat(approved(id)).isTrue();
    }

    @Test
    @DisplayName("승인 취소 중 수정 : 수정은 승인 취소가 끝날 때까지 기다린다. 승인 취소 전 기준값이면 409 (승인 취소도 기준값을 바꾼다 — 8차), 취소 뒤 다시 불러오면 저장")
    void updateWhileUnapproving() throws Exception {
        Context a = project("m5-7-race-cancel", true);
        long id = prepared(a);
        login(a, "ROLE_ADMIN");
        dictionary.approvalStandardTerms(List.of(StandardTermDto.builder().id(id).build()), a.user, true);
        StandardTermDto edit = listed(a, id);
        edit.setCommonStandardTermDescription("승인 취소 중 수정");

        Raced raced = hold(a, () -> dictionary.approvalStandardTerms(List.of(StandardTermDto.builder().id(id).build()), a.user, false),
                () -> dictionary.updateStandardTerms(a.user, edit));
        assertThat(raced.secondWaited).isTrue();
        assertThat(raced.failure).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(description(id)).isEqualTo("M-5 7차");
        assertThat(approved(id)).isFalse();
        StandardTermDto reloaded = listed(a, id);
        reloaded.setCommonStandardTermDescription("승인 취소 뒤 수정");
        update(a, reloaded);
        assertThat(description(id)).isEqualTo("승인 취소 뒤 수정");
        assertThat(approved(id)).isFalse();
    }

    // ------------------------------------------------------------------ 기준값(modifiedAt) 충돌 — M-5 8차

    @Test
    @DisplayName("수정 대 수정 : 같은 기준값으로 연 A·B 중 A 저장 뒤 B 는 409 MODEL_CONFLICT·A 값 유지, 같은 요청 재전송도 409, 다시 불러오면 저장. 이음동의어만 바꿔도 기준값이 움직인다")
    void staleUpdateRejected() {
        Context a = project("m5-8-update", true);
        long id = prepared(a);
        StandardTermDto first = listed(a, id);
        StandardTermDto second = listed(a, id);
        assertThat(first.getModifiedAt()).isNotNull().isEqualTo(second.getModifiedAt());

        first.setCommonStandardTermDescription("A 의 수정");
        update(a, first);
        second.setCommonStandardTermDescription("B 의 수정");
        assertThatThrownBy(() -> update(a, second)).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThatThrownBy(() -> update(a, second)).as("같은 요청 재전송").extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        // 문구 : 공통 문구("…저장하세요") 없이 수정 안내만 (M-7 후속)
        assertThatThrownBy(() -> update(a, second)).extracting("customMessage").asString()
                .contains("최신 정보를 확인한 후 다시 수정해 주세요").doesNotContain("저장하세요");
        assertThat(ErrorCode.MODEL_CONFLICT.getHttpStatus().value()).isEqualTo(409);
        assertThat(description(id)).isEqualTo("A 의 수정");

        // 잘못된 기준값(미래 시각) 도 409, 기준값이 1µs 미만으로만 다르면 같다 (PostgreSQL 반올림·JSON 왕복)
        StandardTermDto wrong = listed(a, id);
        wrong.setModifiedAt(wrong.getModifiedAt().plusSeconds(1));
        assertThatThrownBy(() -> update(a, wrong)).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        StandardTermDto nearly = listed(a, id);
        nearly.setModifiedAt(nearly.getModifiedAt().plusNanos(400));
        nearly.setCommonStandardTermDescription("다시 불러와 수정");
        update(a, nearly);
        assertThat(description(id)).isEqualTo("다시 불러와 수정");

        // 이음동의어만 바꾼 수정도 기준값을 바꾼다 (행 값이 그대로면 UPDATE 가 나가지 않던 것 — markModified)
        StandardTermDto before = listed(a, id);
        StandardTermDto synonyms = listed(a, id);
        synonyms.setSynonyms("동의어1");
        update(a, synonyms);
        assertThat(listed(a, id).getModifiedAt()).isAfter(before.getModifiedAt());
        before.setCommonStandardTermDescription("오래된 화면");
        assertThatThrownBy(() -> update(a, before)).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);

        // 약어를 보내지 않아도(기준값은 보냄) 저장 약어 유지 (7차 그대로)
        StandardTermDto omitted = listed(a, id);
        omitted.setCommonStandardTermAbbreviation(null);
        update(a, omitted);
        assertThat(stored(id)).isEqualTo("DEL_DT");
    }

    @Test
    @DisplayName("오류 순서 : 기준값 누락 400 MODEL_BASE_REQUIRED(HTTP 경계), VIEWER 403 이 먼저, 다른 프로젝트 NOT_FOUND, 승인된 용어는 오래된 기준값이어도 400 APPROVED_DATA_CANNOT_BE_UPDATE")
    void errorOrder() {
        Context a = project("m5-8-order", true);
        Context b = project("m5-8-order-b", true);
        long id = prepared(a);
        StandardTermDto missing = listed(a, id);
        missing.setModifiedAt(null);
        assertThatThrownBy(() -> controller.updateStandardTerms(login(a, "ROLE_USER"), missing)).extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        StandardTermDto approvalMissing = listed(a, id);
        approvalMissing.setModifiedAt(null);
        assertThatThrownBy(() -> controller.approvalStandardTerms(List.of(approvalMissing), login(a, "ROLE_ADMIN"), true)).extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        StandardTermDto viewer = listed(a, id);
        AccountDto viewerUser = login(a, "ROLE_VIEWER");
        assertThatThrownBy(() -> controller.updateStandardTerms(viewerUser, missing)).as("권한 검사가 기준값보다 먼저").isInstanceOf(AccessDeniedException.class);

        StandardTermDto stale = listed(a, id);
        stale.setModifiedAt(stale.getModifiedAt().minusDays(1));
        assertThatThrownBy(() -> dictionary.updateStandardTerms(login(b, "ROLE_ADMIN"), stale)).as("다른 프로젝트 : 버전·승인 상태를 드러내지 않는다")
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_TERM_DATA);
        dictionary.approvalStandardTerms(List.of(listed(a, id)), login(a, "ROLE_ADMIN"), true);
        assertThatThrownBy(() -> update(a, viewer)).as("승인된 용어 : 기준값보다 승인 여부가 먼저").extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
    }

    @Test
    @DisplayName("승인 : 화면을 연 뒤 설명·약어·도메인·이음동의어가 바뀌면 409·미승인 그대로, 다시 불러오면 승인. 오래된 화면의 승인 취소도 409")
    void staleApprovalRejected() {
        Context a = project("m5-8-approve", true);
        long id = prepared(a);
        domain(a, "DTM2");
        for (String change : List.of("설명", "약어", "도메인", "이음동의어")) {
            StandardTermDto reviewed = listed(a, id); // 승인자가 본 화면
            StandardTermDto edit = listed(a, id);
            switch (change) {
                case "설명" -> edit.setCommonStandardTermDescription("검토 뒤 바뀐 설명");
                case "약어" -> edit.setCommonStandardTermAbbreviation(edit.getCommonStandardTermAbbreviation().equals("DEL_DT") ? "DLT_DT" : "DEL_DT");
                case "도메인" -> edit.setCommonStandardDomainName(edit.getCommonStandardDomainName().equals("DTM") ? "DTM2" : "DTM");
                default -> edit.setSynonyms("검토 뒤 " + System.nanoTime());
            }
            dictionary.updateStandardTerms(login(a, "ROLE_USER"), edit);
            assertThatThrownBy(() -> dictionary.approvalStandardTerms(List.of(reviewed), login(a, "ROLE_ADMIN"), true)).as(change)
                    .isInstanceOfSatisfying(com.lexorma.common.exception.CustomException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_CONFLICT);
                        assertThat(e.getCustomMessage()).contains("최신 정보를 확인한 후 다시 승인해 주세요").doesNotContain("저장하세요");
                    });
            assertThat(approved(id)).as(change + " : 미승인 그대로").isFalse();
        }
        dictionary.approvalStandardTerms(List.of(listed(a, id)), login(a, "ROLE_ADMIN"), true);
        assertThat(approved(id)).isTrue();

        // 승인 → (화면 A 가 연 뒤) 승인 취소 → 수정 → 재승인 : 화면 A 의 승인 취소는 409, 승인 그대로
        StandardTermDto screenA = listed(a, id);
        dictionary.approvalStandardTerms(List.of(listed(a, id)), login(a, "ROLE_ADMIN"), false);
        StandardTermDto edit = listed(a, id);
        edit.setCommonStandardTermDescription("재승인 전 수정");
        update(a, edit);
        dictionary.approvalStandardTerms(List.of(listed(a, id)), login(a, "ROLE_ADMIN"), true);
        assertThatThrownBy(() -> dictionary.approvalStandardTerms(List.of(screenA), login(a, "ROLE_ADMIN"), false))
                .isInstanceOfSatisfying(com.lexorma.common.exception.CustomException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_CONFLICT);
                    assertThat(e.getCustomMessage()).contains("최신 정보를 확인한 후 다시 승인 취소해 주세요");
                });
        assertThat(approved(id)).isTrue();
        // 이미 그 상태인 행은 바꾸는 것이 없어 기준값을 보지 않는다 (예전 화면에서 승인된 행을 같이 골라도 막지 않는다)
        dictionary.approvalStandardTerms(List.of(screenA), login(a, "ROLE_ADMIN"), true);
        assertThat(approved(id)).isTrue();
    }

    @Test
    @DisplayName("일괄 승인 : 한 건이라도 기준값이 다르면 409 로 전체를 되돌린다 (모두 미승인 그대로), 다시 불러오면 모두 승인")
    void bulkApprovalAllOrNothing() {
        Context a = project("m5-8-bulk", true);
        long first = prepared(a);
        word(a, "지연", "DLY");
        long second = insertTerm(a, "지연_일시", "DLY_DT", "DTM");
        unapprove(a, second);
        StandardTermDto firstRow = listed(a, first);
        StandardTermDto secondRow = listed(a, second);
        StandardTermDto edit = listed(a, second);
        edit.setCommonStandardTermDescription("일괄 승인 화면을 연 뒤 수정");
        dictionary.updateStandardTerms(login(a, "ROLE_USER"), edit);

        assertThatThrownBy(() -> dictionary.approvalStandardTerms(List.of(secondRow, firstRow), login(a, "ROLE_ADMIN"), true))
                .extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(approved(first)).as("기준값이 맞는 행도 되돌린다").isFalse();
        assertThat(approved(second)).isFalse();
        dictionary.approvalStandardTerms(List.of(listed(a, second), listed(a, first)), login(a, "ROLE_ADMIN"), true);
        assertThat(approved(first)).isTrue();
        assertThat(approved(second)).isTrue();
    }

    @Test
    @DisplayName("동시 실행 : 같은 기준값의 수정 둘 — 뒤의 것은 앞의 커밋을 기다린 뒤 409, 같은 기준값의 수정과 승인 — 승인은 기다린 뒤 409, 일괄 승인 중 수정 — 기다린 뒤 승인된 용어라 400")
    void racesWithBase() throws Exception {
        Context a = project("m5-8-race", true);
        long id = prepared(a);
        StandardTermDto first = listed(a, id);
        StandardTermDto second = listed(a, id);
        first.setCommonStandardTermDescription("먼저 잡은 수정");
        second.setCommonStandardTermDescription("기다린 수정");
        Raced updates = hold(a, () -> dictionary.updateStandardTerms(a.user, first), () -> dictionary.updateStandardTerms(a.user, second));
        assertThat(updates.secondWaited()).isTrue();
        assertThat(updates.failure()).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(description(id)).isEqualTo("먼저 잡은 수정");

        StandardTermDto edit = listed(a, id);
        StandardTermDto reviewed = listed(a, id);
        edit.setCommonStandardTermDescription("승인과 겹친 수정");
        Raced approval = hold(a, () -> dictionary.updateStandardTerms(a.user, edit), () -> dictionary.approvalStandardTerms(List.of(reviewed), a.user, true));
        assertThat(approval.secondWaited()).isTrue();
        assertThat(approval.failure()).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(approved(id)).isFalse();

        word(a, "지연", "DLY");
        long other = insertTerm(a, "지연_일시", "DLY_DT", "DTM");
        unapprove(a, other);
        List<StandardTermDto> bulk = List.of(listed(a, other), listed(a, id));
        StandardTermDto late = listed(a, id);
        late.setCommonStandardTermDescription("일괄 승인과 겹친 수정");
        Raced bulkRace = hold(a, () -> dictionary.approvalStandardTerms(bulk, a.user, true), () -> dictionary.updateStandardTerms(a.user, late));
        assertThat(bulkRace.secondWaited()).isTrue();
        assertThat(bulkRace.failure()).extracting("errorCode").isEqualTo(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        assertThat(approved(id)).isTrue();
        assertThat(approved(other)).isTrue();
        assertThat(description(id)).isEqualTo("승인과 겹친 수정");
    }

    // ------------------------------------------------------------------ 보조

    /** 용어 단어들의 단어 약어를 '_' 로 이은 값 (저장값과 비교하는 기준 — 운영 코드는 새 용어 등록 화면에서만 만든다) */
    private static String wordAbbreviation(StandardTerm term) {
        return term.getTermWordMappings() == null ? "" : term.getTermWordMappings().stream()
                .sorted(java.util.Comparator.comparingInt(com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping::getOrderIndex))
                .map(mapping -> mapping.getStandardWord().getCommonStandardWordAbbreviation())
                .collect(java.util.stream.Collectors.joining("_"));
    }

    private record Context(Long projectId, AccountDto user) {}

    private record Raced(boolean secondWaited, Throwable failure) {}

    /**
     * first 를 한 트랜잭션에서 실행한 뒤 커밋하지 않고 잡아 두고, 그동안 다른 스레드에서 second 를 실행한다.
     * second 가 잠깐 동안 끝나지 않으면(행 잠금 대기) first 를 커밋한다. H2 의 잠금 대기 한도(기본 1초) 안에서 끝나도록 짧게 잡는다
     */
    private Raced hold(Context context, Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> {
                login(context, "ROLE_ADMIN");
                transactions.executeWithoutResult(status -> {
                    first.run();
                    locked.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> waiter = executor.submit(() -> {
                login(context, "ROLE_ADMIN");
                try {
                    second.run();
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Thread.sleep(300);
            boolean waited = !waiter.isDone();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return new Raced(waited, waiter.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 단어·도메인·미승인 용어 하나 */
    private long prepared(Context context) {
        word(context, "삭제", "DEL");
        word(context, "일시", "DT");
        domain(context, "DTM");
        long id = insertTerm(context, "삭제_일시", "DEL_DT", "DTM");
        unapprove(context, id);
        return id;
    }

    private Context project(String name, boolean dictionaryAbbreviation) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + System.nanoTime()).isActive(true).isDicAbbrUsed(dictionaryAbbreviation).account(admin).build());
        return new Context(project.getId(), SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN"));
    }

    /** ADMIN 은 프로젝트 관리자 계정(승인 권한), 그 밖의 역할은 관리자가 아닌 계정 */
    private AccountDto login(Context context, String role) {
        return SecurityTestSupport.loginAs(projectRepository, context.projectId, "ROLE_ADMIN".equals(role) ? context.user.getId() : MEMBER, role);
    }

    private void word(Context context, String name, String abbreviation) {
        login(context, "ROLE_ADMIN");
        dictionary.insertStandardWord(context.user, StandardWordDto.builder().revisionNumber(1).commonStandardWordName(name)
                .commonStandardWordAbbreviation(abbreviation).commonStandardWordEnglishName(abbreviation).commonStandardWordDescription(name)
                .isFormatWord("N").commonStandardDomainCategory("-").synonyms("").restrictedWords("").build());
    }

    private void domain(Context context, String name) {
        login(context, "ROLE_ADMIN");
        dictionary.insertStandardDomain(context.user, StandardDomainDto.builder().revisionNumber(1).commonStandardDomainGroupName("문자")
                .commonStandardDomainCategory("일시").commonStandardDomainName(name).commonStandardDomainDescription("M-5 7차")
                .dataType("VARCHAR").dataLength(14).dataDecimalLength(0).storageFormat("").displayFormat("").unit("").allowedValues("").build());
    }

    private long insertTerm(Context context, String name, String abbreviation, String domainName) {
        login(context, "ROLE_ADMIN");
        dictionary.insertStandardTerms(context.user, StandardTermDto.builder().revisionNumber(1).commonStandardTermName(name).commonStandardTermDescription("M-5 7차")
                .commonStandardTermAbbreviation(abbreviation).commonStandardDomainName(domainName).synonyms("").build());
        return jdbc.queryForObject("select STD_TRM_SN from TB_STD_TRM where PRJ_SN = ? and TRM_ENG_ABBR_NM = ?", Long.class, context.projectId, abbreviation);
    }

    /** 승인 취소 API (승인 권한자) */
    private void unapprove(Context context, Long... ids) {
        login(context, "ROLE_ADMIN");
        dictionary.approvalStandardTerms(java.util.Arrays.stream(ids).map(id -> StandardTermDto.builder().id(id).build()).toList(), context.user, false);
    }

    /** 화면 그리드 행 (목록 API 와 같은 경로) */
    private StandardTermDto listed(Context context, long id) {
        login(context, "ROLE_ADMIN");
        return transactions.execute(status -> dictionary.getStandardTermsData(PageRequest.of(0, 100_000), context.user, null, null).getContent().stream()
                .filter(term -> term.getId() == id).findFirst().orElseThrow());
    }

    private void update(Context context, StandardTermDto request) {
        dictionary.updateStandardTerms(login(context, "ROLE_ADMIN"), request);
    }

    private String stored(long id) {
        return jdbc.queryForObject("select TRM_ENG_ABBR_NM from TB_STD_TRM where STD_TRM_SN = ?", String.class, id);
    }

    private String description(long id) {
        return jdbc.queryForObject("select TRM_EXPLN from TB_STD_TRM where STD_TRM_SN = ?", String.class, id);
    }

    private boolean approved(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select APRV_YN from TB_STD_TRM where STD_TRM_SN = ?", Boolean.class, id));
    }

    /** 거절 전후 비교용 행 전체 */
    private String row(long id) {
        return String.valueOf(jdbc.queryForMap("select TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, MDFCN_DT, MDFR_ID from TB_STD_TRM where STD_TRM_SN = ?", id));
    }

    private static MockMultipartFile template() throws IOException {
        try (InputStream in = StandardTermApprovalIntegrationTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx")) {
            assertThat(in).isNotNull();
            return new MockMultipartFile("file", "dataPortal.xlsx", null, in.readAllBytes());
        }
    }
}
