package com.lexorma;

import com.lexorma.core.ai.AiProviderType;
import com.lexorma.core.network.ConnectionType;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposalHistory;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예제 프로젝트(LEXORMA, 프로젝트 0)의 코드성 표준 용어 정의 ↔ 실제 enum (AI-5-QUAL-1). AI 설명 제안은 표준 용어 정의를 근거로 값 목록을 말하므로,
 * 예제 정의가 enum 보다 낡으면 틀린 목록이 근거가 된다 (예제의 검토_상태_코드 에 SUPERSEDED·CLOSED 가 없었다 — 2026-11-15 AI-5-QUAL-1 에서 V1 을 고침).
 * 정의의 괄호 밖 대문자 값 집합이 enum 상수 집합과 같아야 한다 — 값을 더하거나 빼면 이 테스트가 실패하므로 예제 정의(V1, 적용된 뒤에는 새 마이그레이션)도 함께 고친다.
 * 기대값은 enum 에서 읽는다 (같은 목록을 테스트에 복사해 두지 않는다). 값 목록을 적지 않은 정의(예 : 삭제_규칙_코드 "참조 대상이 삭제될 때의 규칙.")는 대상이 아니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ExampleTermCodeDefinitionTest {

    /** 대문자 값 : 영문 대문자로 시작하는 3자 이상 (괄호 안 설명·한글은 뺀 뒤) */
    private static final Pattern CODE = Pattern.compile("\\b[A-Z][A-Z_]{2,}\\b");

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("예제 코드 용어 정의의 값 목록 = enum 상수 (빠진 값·없는 값 모두 실패). 검토_상태_코드 에 SUPERSEDED·CLOSED 포함")
    void definitionsMatchEnums() {
        Map<String, Enum<?>[]> terms = new LinkedHashMap<>();
        terms.put("검토_상태_코드", AiDescriptionProposal.ReviewStatus.values());
        terms.put("처리_유형_코드", AiDescriptionProposalHistory.Action.values());
        terms.put("확실성_코드", AiDescriptionProposal.Certainty.values());
        terms.put("생성_방법_코드", AiDescriptionProposal.CreationMethod.values());
        terms.put("인덱스_출처_코드", TableIndex.Source.values());
        terms.put("참조_유형_코드", PhysicalViewReference.Type.values());
        terms.put("접속_유형_코드", ConnectionType.values());
        terms.put("제공자_유형_코드", AiProviderType.values());
        for (Map.Entry<String, Enum<?>[]> e : terms.entrySet()) {
            String definition = jdbc.queryForObject("select TRM_EXPLN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = ?", String.class, e.getKey());
            Set<String> expected = Arrays.stream(e.getValue()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
            assertThat(codes(definition)).as("%s : %s", e.getKey(), definition).isEqualTo(expected);
        }
        String review = jdbc.queryForObject("select TRM_EXPLN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '검토_상태_코드'", String.class);
        assertThat(review).contains("SUPERSEDED", "CLOSED");
    }

    // ------------------------------------------------------------------ 준비

    /** "이름 : 값·값·…" 의 값 부분(첫 " : " 뒤)에서 괄호 안 설명을 뺀 대문자 값 */
    private static Set<String> codes(String definition) {
        int colon = definition.indexOf(" : ");
        String outside = (colon < 0 ? definition : definition.substring(colon + 3)).replaceAll("\\([^)]*\\)", " ");
        Set<String> found = new TreeSet<>();
        Matcher m = CODE.matcher(outside);
        while (m.find()) found.add(m.group());
        return found;
    }
}
