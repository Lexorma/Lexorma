package com.lexorma;

import com.lexorma.common.i18n.Messages;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRuleGuide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-7 AI7-D11 : 서버 규칙 안내 카탈로그 ↔ 화면 규칙 카탈로그(lexorma-web/src/features/quality/model.ts RULES) 정합성.
 * 프런트에 테스트 실행기가 없으므로 여기서 model.ts 를 텍스트로 읽어 규칙별 이름·설명이 같은 문자열인지 본다 — 한쪽만 고치면 실패한다.
 */
class StandardizationRuleGuideTest {

    private static final Pattern RULE = Pattern.compile("\\{ rule: '(\\w+)', group: '(\\w+)', label: t\\('([^']+)'\\), description: t\\('([^']+)'\\) \\}");

    @Test
    @DisplayName("규칙 20개 모두 안내가 있고, 이름·설명은 화면 문구와 같으며, 모든 문구에 영어가 있다")
    void guideMatchesScreen() throws IOException {
        String model = Files.readString(Path.of("").toAbsolutePath().getParent().resolve("lexorma-web/src/features/quality/model.ts"));
        Map<String, String[]> screen = new LinkedHashMap<>();
        Matcher matcher = RULE.matcher(model);
        while (matcher.find()) screen.put(matcher.group(1), new String[]{matcher.group(2), matcher.group(3), matcher.group(4)});
        assertThat(screen).hasSize(StandardizationRule.values().length);
        for (StandardizationRule rule : StandardizationRule.values()) {
            StandardizationRuleGuide.Text text = StandardizationRuleGuide.text(rule);
            String[] ui = screen.get(rule.name());
            assertThat(ui).as(rule + " 화면 정의").isNotNull();
            assertThat(text.name()).as(rule + " 이름").isEqualTo(ui[1]);
            assertThat(text.description()).as(rule + " 설명").isEqualTo(ui[2]);
            assertThat(text.fixHint()).as(rule + " 개선 안내").isNotBlank();
            for (String message : new String[]{text.name(), text.description(), text.fixHint()}) {
                assertThat(Messages.hasEnglish(message)).as("영어 : " + message).isTrue();
            }
            // 요청 밖(언어 정보 없음) = 한국어 원문
            assertThat(StandardizationRuleGuide.guide(rule).name()).isEqualTo(text.name());
        }
    }
}
