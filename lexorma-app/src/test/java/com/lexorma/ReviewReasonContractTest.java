package com.lexorma;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재검토 사유 종류의 서버 ↔ 화면 계약 (AI-5-REASON) : 서버가 만드는 변경 종류(`new …Change("종류"`)와 화면 파서(`reviewReason.ts` 의 CHANGE_KINDS)가 같아야 한다.
 * 서버에 새 종류를 더하면 이 테스트가 실패하므로 화면 파서·문구(ReasonView.tsx, en.ts)를 함께 더한다 — 그러지 않으면 새 사유가 "알 수 없는 사유" 로만 보인다.
 */
class ReviewReasonContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    @Test
    @DisplayName("서버가 만드는 변경 종류 = 화면 파서의 종류 목록")
    void kindsMatch() throws IOException {
        Set<String> server = new TreeSet<>();
        Pattern change = Pattern.compile("new (?:ProposalVersions\\.)?Change\\(\"([A-Z_]+)\"");
        try (Stream<Path> files = Files.walk(ROOT.resolve("lexorma-ai/src/main/java/com/lexorma/ai/dictionary"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = change.matcher(Files.readString(file));
                while (m.find()) server.add(m.group(1));
            }
        }
        String parser = Files.readString(ROOT.resolve("lexorma-web/src/features/dictionary/ai/reviewReason.ts"));
        Matcher list = Pattern.compile("CHANGE_KINDS = \\[([^\\]]*)]").matcher(parser);
        assertThat(list.find()).isTrue();
        Set<String> web = new TreeSet<>();
        Matcher kind = Pattern.compile("'([A-Z_]+)'").matcher(list.group(1));
        while (kind.find()) web.add(kind.group(1));

        assertThat(server).isNotEmpty();
        assertThat(web).isEqualTo(server);
        // 화면 문구 : 종류마다 제목·설명이 있다
        String view = Files.readString(ROOT.resolve("lexorma-web/src/features/dictionary/ai/ReasonView.tsx"));
        for (String k : server) assertThat(view).as(k).containsPattern("\\b" + k + ": t\\(");
    }
}
