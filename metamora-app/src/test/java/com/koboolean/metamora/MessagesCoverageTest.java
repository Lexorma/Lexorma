package com.koboolean.metamora;

import com.koboolean.metamora.common.i18n.Messages;
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
 * 서버 메시지(한국어 원문)에 영어 문구가 빠지지 않았는지 소스에서 확인한다.
 * 대상 : Messages.get("...") / new CustomException(ErrorCode.X, "...") / invalid("...") / JsonResponses.error(.., "...")
 */
class MessagesCoverageTest {

    private static final Pattern MESSAGE = Pattern.compile(
            "(?:Messages\\.get\\(|invalid\\(|new CustomException\\(ErrorCode\\.\\w+,|JsonResponses\\.error\\([^;\"]*\"[A-Z_]+\",)\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern KOREAN = Pattern.compile("[가-힣]");

    @Test
    @DisplayName("소스에 쓴 서버 메시지는 모두 영어 문구가 등록되어 있다")
    void everyMessageHasEnglish() throws IOException {
        Path root = Path.of("").toAbsolutePath().getParent();
        Set<String> missing = new TreeSet<>();
        int found = 0;
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(path -> path.getFileName().toString().startsWith("metamora-")).toList()) {
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) continue;
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        Matcher matcher = MESSAGE.matcher(Files.readString(file));
                        while (matcher.find()) {
                            String message = matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"");
                            if (!KOREAN.matcher(message).find()) continue;
                            found++;
                            if (!Messages.hasEnglish(message)) missing.add(message);
                        }
                    }
                }
            }
        }
        assertThat(found).isGreaterThan(50);
        assertThat(missing).isEmpty();
    }
}
