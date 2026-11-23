package com.lexorma.data.dictionary.quality;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.data.dictionary.service.StandardDomainService;
import com.lexorma.data.dictionary.service.StandardTermService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M-5 4차 : 진단과 등록·생성·추천이 함께 쓰는 이름 판정 함수 (판정만 — 처리 정책은 호출하는 쪽) */
class StandardNamesTest {

    @Test
    @DisplayName("기대 컬럼명 = 영문약어 앞뒤 공백 제거, 비었으면 null (알 수 없음)")
    void expectedColumnName() {
        assertThat(StandardNames.expectedColumnName("USER_NM")).isEqualTo("USER_NM");
        assertThat(StandardNames.expectedColumnName(" user_nm\t")).isEqualTo("user_nm");
        assertThat(StandardNames.expectedColumnName(" ")).isNull();
        assertThat(StandardNames.expectedColumnName("")).isNull();
        assertThat(StandardNames.expectedColumnName(null)).isNull();
    }

    @Test
    @DisplayName("컬럼명 일치 = 앞뒤 공백 제거 + 대소문자 무시. 구분자·내부 공백은 다르게 본다, 기대 이름이 없으면 일치가 아니다")
    void sameColumnName() {
        assertThat(StandardNames.sameColumnName("USER_NM", "USER_NM")).isTrue();
        assertThat(StandardNames.sameColumnName(" user_nm ", "USER_NM ")).isTrue();
        assertThat(StandardNames.sameColumnName("USERNM", "USER_NM")).isFalse();
        assertThat(StandardNames.sameColumnName("USER-NM", "USER_NM")).isFalse();
        assertThat(StandardNames.sameColumnName("USER NM", "USER_NM")).isFalse();
        assertThat(StandardNames.sameColumnName(null, "USER_NM")).isFalse();
        assertThat(StandardNames.sameColumnName("", "USER_NM")).isFalse();
        assertThat(StandardNames.sameColumnName("USER_NM", null)).isFalse();
        assertThat(StandardNames.sameColumnName(null, null)).isFalse();
    }

    @Test
    @DisplayName("NAME-T01 (M7-NAME-1) : 정책 표 그대로 — 대소문자·앞뒤 공백(탭·줄바꿈 포함)은 같고, 내부 공백·'__'·'-'·숫자 접미사는 다르다. 비 ASCII 는 글자 단위 대소문자만 (ß ≠ SS)")
    void policyTable() {
        assertThat(StandardNames.sameColumnName("USER_ID", "USER_ID")).isTrue();
        assertThat(StandardNames.sameColumnName("user_id", "USER_ID")).isTrue();
        assertThat(StandardNames.sameColumnName(" USER_ID ", "USER_ID")).isTrue();
        assertThat(StandardNames.sameColumnName("USER_ID\t", "\nUSER_ID")).isTrue();
        assertThat(StandardNames.sameColumnName("USERID", "USER_ID")).isFalse();
        assertThat(StandardNames.sameColumnName("USER ID", "USER_ID")).isFalse();
        assertThat(StandardNames.sameColumnName("USER__ID", "USER_ID")).isFalse();
        assertThat(StandardNames.sameColumnName("USER-ID", "USER_ID")).isFalse();
        assertThat(StandardNames.sameColumnName("USER_ID2", "USER_ID")).isFalse();
        // 비 ASCII : String.equalsIgnoreCase (글자 하나씩, 로캘 없음) — 확장 대문자·합자·전각·결합 문자는 같게 보지 않는다
        assertThat(StandardNames.sameColumnName("straße_cd", "STRAßE_CD")).isTrue();
        assertThat(StandardNames.sameColumnName("STRAßE_CD", "STRASSE_CD")).isFalse();
        assertThat(StandardNames.sameColumnName("ﬁ_CD", "FI_CD")).isFalse();
        assertThat(StandardNames.sameColumnName("ＦＵＬＬ_CD", "FULL_CD")).isFalse();
        assertThat(StandardNames.sameColumnName("é_cd", "É_CD")).isTrue();
        assertThat(StandardNames.sameColumnName("e\u0301_cd", "É_CD")).isFalse();
        assertThat(StandardNames.sameColumnName("ıd", "ID")).as("toUpperCase('ı') = 'I'").isTrue();
        // null · 빈 값 · 공백만
        assertThat(StandardNames.sameColumnName(null, StandardNames.expectedColumnName(""))).isFalse();
        assertThat(StandardNames.sameColumnName(" ", " ")).as("기대 이름은 호출하는 쪽이 expectedColumnName 으로 걸러 null 이 된다").isTrue();
        assertThat(StandardNames.expectedColumnName("\t")).isNull();
    }

    @Test
    @DisplayName("물리명 추천·정합성 비교의 컬럼명 규칙은 같은 함수다 (추천명·사유·일치 판정)")
    void recommendationUsesSameRule() {
        for (String abbreviation : Arrays.asList("USER_NM", " user_nm ", " ", "", null)) {
            PhysicalNameRecommendationService.ColumnName name = PhysicalNameRecommendationService.recommendColumnName(1L, abbreviation);
            assertThat(name.name()).isEqualTo(StandardNames.expectedColumnName(abbreviation));
            assertThat(name.available()).isEqualTo(StandardNames.expectedColumnName(abbreviation) != null);
            for (String current : Arrays.asList("USER_NM", "user_nm ", "USR_NM", "", null)) {
                assertThat(PhysicalNameRecommendationService.sameName(current, name.name()))
                        .isEqualTo(StandardNames.sameColumnName(current, name.name()));
            }
        }
        assertThat(PhysicalNameRecommendationService.recommendColumnName(null, "USER_NM").available()).as("용어 없음").isFalse();
    }

    @Test
    @DisplayName("용어·단어 이름 키 = 공백·'_' 제거 + 소문자, compact 는 대소문자를 유지한다. null 은 빈 값")
    void nameKeys() {
        assertThat(List.of("회원_이름", "회원 이름", " 회원\t이름 ", "회원__이름")).extracting(StandardNames::termNameKey).containsOnly("회원이름");
        assertThat(StandardNames.termNameKey("Member_ID")).isEqualTo(StandardNames.termNameKey("member id")).isEqualTo("memberid");
        assertThat(StandardNames.compact("Member_ID x")).isEqualTo("MemberIDx");
        assertThat(StandardNames.compact(null)).isEmpty();
        assertThat(StandardNames.termNameKey(null)).isEmpty();
        assertThat(StandardNames.termNameKey(" _ ")).isEmpty();
    }

    @Test
    @DisplayName("영문약어·물리명 키 = 앞뒤 공백 제거 + 대문자 (내부 구분자는 그대로). null 은 빈 값")
    void physicalNameKey() {
        assertThat(StandardNames.physicalNameKey(" mbr_nm ")).isEqualTo("MBR_NM");
        assertThat(StandardNames.physicalNameKey("Tb_Mbr")).isEqualTo("TB_MBR");
        assertThat(StandardNames.physicalNameKey("MBR NM")).isEqualTo("MBR NM");
        assertThat(StandardNames.physicalNameKey(" ")).isEmpty();
        assertThat(StandardNames.physicalNameKey(null)).isEmpty();
    }

    @Test
    @DisplayName("단어 나누기 = '_'·공백 경계, 빈 조각 제외. 테이블명 생성과 금칙어 진단이 같은 경계를 쓴다")
    void splitWords() {
        assertThat(StandardNames.splitWords("사용_내역 관리")).containsExactly("사용", "내역", "관리");
        assertThat(StandardNames.splitWords("  사용__내역\t관리  ")).containsExactly("사용", "내역", "관리");
        assertThat(StandardNames.splitWords("_사용_")).containsExactly("사용");
        assertThat(StandardNames.splitWords("멤버십등급")).containsExactly("멤버십등급");
        assertThat(StandardNames.splitWords(" ")).isEmpty();
        assertThat(StandardNames.splitWords(null)).isEmpty();
    }

    @Test
    @DisplayName("공통화하지 않은 정책 : 도메인명 키는 소문자만(공백·'_' 유지), 용어 등록의 단어 수 규칙은 진단 키보다 엄격하다")
    void policiesStaySeparate() {
        // [DICT-2] 도메인명 : 대소문자만 무시한다 — 용어 이름 키처럼 공백·'_' 를 빼지 않는다
        assertThat(StandardDomainService.nameKey("Code Type")).isEqualTo("code type");
        assertThat(StandardDomainService.nameKey("CODE_TYPE")).isNotEqualTo(StandardDomainService.nameKey("code type"));
        assertThat(StandardNames.termNameKey("CODE_TYPE")).isEqualTo(StandardNames.termNameKey("code type"));

        // 진단에서 같은 이름 키("회원이름")여도 등록은 '_' 구분과 단어 수가 맞아야 한다 (등록 거절 기준은 그대로)
        assertThat(StandardNames.termNameKey("회원 이름")).isEqualTo(StandardNames.termNameKey("회원_이름"));
        StandardTermService.validateWordCount("회원_이름", "MBR_NM");
        assertThatThrownBy(() -> StandardTermService.validateWordCount("회원 이름", "MBR_NM")).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TERM_WORD_COUNT_MISMATCH);
    }
}
