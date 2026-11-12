package com.lexorma;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모듈 경계 : common ← core ← (system, operation, data, source) ← app. 같은 층의 모듈은 서로를 참조하지 않는다.
 * Gradle 의존성이 컴파일을 막지만, 소스와 빌드 파일에서도 한 번 더 확인한다 (테스트 코드 포함 — 테스트가 경계를 넘으면 나중에 본 코드도 넘는다).
 */
class ModuleBoundaryTest {

    /** 모듈 → 참조하면 안 되는 패키지 */
    private static final Map<String, List<String>> FORBIDDEN = Map.of(
            "lexorma-common", List.of("com.lexorma.core.", "com.lexorma.system.", "com.lexorma.operation.", "com.lexorma.data.", "com.lexorma.source."),
            "lexorma-core", List.of("com.lexorma.system.", "com.lexorma.operation.", "com.lexorma.data.", "com.lexorma.source."),
            "lexorma-system", List.of("com.lexorma.operation.", "com.lexorma.data.", "com.lexorma.source."),
            "lexorma-operation", List.of("com.lexorma.system.", "com.lexorma.data.", "com.lexorma.source."),
            "lexorma-data", List.of("com.lexorma.system.", "com.lexorma.operation.", "com.lexorma.source."),
            "lexorma-source", List.of("com.lexorma.system.", "com.lexorma.operation.", "com.lexorma.data."));

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?(com\\.lexorma\\.[\\w.]+)", Pattern.MULTILINE);
    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile("project\\(':([\\w-]+)'\\)");

    private static Path root() {
        return Path.of("").toAbsolutePath().getParent();
    }

    @Test
    @DisplayName("소스 : 각 모듈은 자기보다 아래 층만 import 한다 (data ↔ source, core → 상위 모듈 없음)")
    void imports() throws IOException {
        TreeSet<String> violations = new TreeSet<>();
        for (Map.Entry<String, List<String>> module : FORBIDDEN.entrySet()) {
            Path src = root().resolve(module.getKey()).resolve("src");
            assertThat(src).as(module.getKey()).isDirectory();
            try (Stream<Path> files = Files.walk(src)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    Matcher matcher = IMPORT.matcher(Files.readString(file));
                    while (matcher.find()) {
                        String imported = matcher.group(1);
                        if (module.getValue().stream().anyMatch(imported::startsWith)) violations.add(module.getKey() + " : " + root().relativize(file) + " → " + imported);
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    /** 세션 없는 실행 컨텍스트를 만드는 코드. 정기 배치(SCHEDULED)는 프로젝트 상태만 확인하므로 요청을 받는 쪽에서 만들면 권한 확인을 건너뛰게 된다 */
    private static final Pattern NON_USER_CONTEXT = Pattern.compile("ExecutionContext\\s*\\.\\s*(?:scheduled|async)\\s*\\(|new\\s+ExecutionContext\\s*\\(|ExecutionType\\s*\\.\\s*(?:SCHEDULED|ASYNC)");

    /** 원본 카탈로그 행을 저장소로 직접 쓰는 호출 (조회는 허용) */
    private static final Pattern CATALOG_REPOSITORY_WRITE = Pattern.compile("\\b(?:schema|table|column|constraint|index|relation)Repository\\s*\\.\\s*(?:save\\w*|delete\\w*)\\s*\\(");
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    @Test
    @DisplayName("원본 카탈로그 쓰기 경로 : 운영 코드는 수집 반영(AI-1c)으로만 쓴다. AI-1b 의 한 건 단위 SourceCatalogWriter 는 테스트에서만 부르고, 카탈로그 저장소의 저장·삭제도 Writer 안에만 있다")
    void catalogWritePaths() throws IOException {
        TreeSet<String> violations = new TreeSet<>();
        // 다른 모듈은 lexorma-source 를 참조할 수 없다 (imports 검사) : lexorma-source 안만 본다
        {
            Path main = root().resolve("lexorma-source").resolve("src/main/java");
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String relative = root().relativize(file).toString().replace('\\', '/');
                    if (relative.endsWith("/SourceCatalogWriter.java")) continue;
                    String code = COMMENT.matcher(Files.readString(file)).replaceAll("");
                    if (code.contains("SourceCatalogWriter")) violations.add("SourceCatalogWriter 호출 : " + relative);
                    if (CATALOG_REPOSITORY_WRITE.matcher(code).find()) violations.add("카탈로그 저장소 쓰기 : " + relative);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("실행 경로 : 요청을 받는 컨트롤러는 사용자 실행 컨텍스트만 만들고(배치·비동기 컨텍스트를 만들지 않는다), AI 코드는 원본 DB 도구를 참조하지 않는다")
    void executionPaths() throws IOException {
        TreeSet<String> violations = new TreeSet<>();
        for (String module : FORBIDDEN.keySet()) {
            Path main = root().resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    String relative = root().relativize(file).toString().replace('\\', '/');
                    boolean controller = relative.contains("/controller/") || source.contains("@RestController") || source.contains("@Controller");
                    if (controller && NON_USER_CONTEXT.matcher(source).find()) violations.add("배치·비동기 컨텍스트 : " + relative);
                    if (relative.contains("/ai/") && source.contains("com.lexorma.source.")) violations.add("AI → 원본 DB 도구 : " + relative);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("빌드 파일 : data·source 는 서로를 의존하지 않고, 같은 층의 모듈은 core 만 받는다. app 이 넷을 모두 조립한다")
    void gradle() throws IOException {
        for (String module : List.of("lexorma-common", "lexorma-core", "lexorma-system", "lexorma-operation", "lexorma-data", "lexorma-source")) {
            Path build = root().resolve(module).resolve("build.gradle");
            if (!Files.exists(build)) continue; // system·operation·data·source 는 루트 build.gradle 이 설정한다
            Matcher matcher = PROJECT_DEPENDENCY.matcher(Files.readString(build));
            while (matcher.find()) {
                assertThat(FORBIDDEN.get(module)).as(module + " → " + matcher.group(1))
                        .noneMatch(forbidden -> forbidden.startsWith("com.lexorma." + matcher.group(1).substring("lexorma-".length()) + "."));
            }
        }
        String rootBuild = Files.readString(root().resolve("build.gradle"));
        assertThat(rootBuild).contains("it.name in ['lexorma-system', 'lexorma-operation', 'lexorma-data', 'lexorma-source']").contains("api project(':lexorma-core')");
        assertThat(Files.readString(root().resolve("lexorma-app/build.gradle")))
                .contains("project(':lexorma-system')", "project(':lexorma-operation')", "project(':lexorma-data')", "project(':lexorma-source')");
    }
}
