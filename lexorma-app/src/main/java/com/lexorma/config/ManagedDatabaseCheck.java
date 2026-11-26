package com.lexorma.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lexorma 관리 DB(PostgreSQL 전용 — DB-1, docs/completed/postgresql-only-migration.md) 연결 설정을 DataSource·Flyway·Hibernate 보다 먼저 확인한다 (BeanFactoryPostProcessor).
 * 연결은 하지 않는다 — 연결 실패·인증 실패·DB 없음은 그다음 첫 연결(Flyway)이 기동을 멈추고, {@link ManagedDatabaseFailureAnalyzer} 가 원인을 짧게 알려 준다.
 * <ul>
 *   <li>{@code spring.datasource.url} 이 없거나 비면 멈춘다 (임시 DB 로 대신 뜨지 않는다)</li>
 *   <li>{@code jdbc:postgresql:} 이 아니면 지원하지 않는 관리 DB 로 멈춘다</li>
 *   <li>URL 에 인증정보 표현이 있으면 멈춘다 : Hibernate 가 기동 로그에 URL 원문을 남기므로 비밀번호는 {@code spring.datasource.password}(또는 Docker Secret)로만 받는다.
 *       URL 을 pgJDBC 와 같은 구성 요소로 나눠 본다 (DB-1 3차 PG-D40, 단순 문자열 포함 검사의 오탐 제거 — postgresql-only-migration.md 48장) :
 *       <ul>
 *         <li>호스트 부분({@code //} 뒤 첫 {@code /} 까지) : {@code 호스트[:포트]} 를 쉼표로 이은 목록(IPv6 는 {@code [..]})만 — {@code @}(사용자 정보 {@code user:비밀번호@})나 그 밖의 글자는 거절</li>
 *         <li>쿼리 매개변수({@code ?} 뒤, {@code &} 로 나눔) : 이름을 한 번만 URL 디코딩해 영문·숫자·{@code _.-} 이름이 아니면(이중 인코딩 등) 형식 오류로, 이름에 대소문자 없이
 *             {@code password} 가 들어 있으면({@code password}·{@code sslpassword}·{@code PASSWORD}·{@code pass%77ord} …, 빈 값·중복 포함) 거절</li>
 *         <li>매개변수 값과 DB 이름 : 한 번 디코딩한 값 안의 {@code password=}·{@code password:} 같은 인증정보 표현(예 : {@code options=-c password=…})만 거절 —
 *             {@code ApplicationName=password-service} 나 DB 이름 {@code password_service}, 호스트 {@code password-management-db} 는 통과</li>
 *       </ul>
 *       잘못된 {@code %} 표기는 안전하게 거절한다. 임의의 문자열이 인증정보인지 모두 판별할 수는 없다 — 위 표현만 막는다 (한계는 문서 48장)</li>
 *   <li>{@code spring.datasource.username} 이 없으면 멈춘다. 비밀번호는 요구하지 않는다 — 인증 방식(scram·md5·trust·cert·.pgpass)은 서버가 정하고,
 *       비밀번호가 필요한데 없으면 첫 연결이 인증 오류로 멈춘다</li>
 * </ul>
 * 오류 문구에는 URL·사용자명·비밀번호 값을 넣지 않는다.
 */
@Component
public class ManagedDatabaseCheck implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    static final String PREFIX = "jdbc:postgresql:";
    /** 호스트 하나 : 이름(영문·숫자·.-_) 또는 [IPv6], 포트는 숫자 */
    private static final Pattern HOST = Pattern.compile("(?:[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.%A-Za-z]+\\])(?::[0-9]{1,5})?");
    /** pgJDBC 매개변수 이름 (디코딩한 뒤) */
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    /** 값 안의 인증정보 표현 : password= · password: · sslpassword= (구분자 앞뒤 공백 허용, 디코딩하지 않은 %3D·%3A 도) */
    private static final Pattern CREDENTIAL_EXPRESSION = Pattern.compile("(?:^|[^a-z0-9])(?:ssl)?password\\s*(?:=|:|%3d|%3a)");

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(environment.getProperty("spring.datasource.url"), environment.getProperty("spring.datasource.username"));
    }

    /** 확인. 실패하면 {@link ManagedDatabaseConfigurationException} */
    public static void check(String url, String username) {
        if (url == null || url.isBlank()) {
            throw new ManagedDatabaseConfigurationException("관리 DB(PostgreSQL) 연결 설정이 없습니다 : spring.datasource.url 이 비어 있습니다.",
                    "spring.datasource.url (환경변수 SPRING_DATASOURCE_URL) 에 jdbc:postgresql://<호스트>:<포트>/<DB> 를 지정하세요. 프로필(local · dev 포함)은 DB 연결 정보를 주지 않습니다.");
        }
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw new ManagedDatabaseConfigurationException("지원하지 않는 관리 DB 입니다 (" + scheme(trimmed) + "). Lexorma 관리 DB 는 PostgreSQL 만 지원합니다.",
                    "spring.datasource.url 을 jdbc:postgresql://<호스트>:<포트>/<DB> 로 지정하세요.");
        }
        switch (inspect(trimmed)) {
            case CREDENTIAL -> throw new ManagedDatabaseConfigurationException("관리 DB JDBC URL 에 인증정보(password · sslpassword 매개변수, 값 안의 password= 표현 또는 user:password@)를 넣을 수 없습니다.",
                    "URL 에서 인증정보를 빼고 비밀번호는 spring.datasource.password (환경변수 SPRING_DATASOURCE_PASSWORD) 또는 Docker Secret 파일로 주세요.");
            case MALFORMED -> throw new ManagedDatabaseConfigurationException("관리 DB JDBC URL 형식이 올바르지 않습니다 (호스트 목록 · 매개변수 이름 · % 표기).",
                    "spring.datasource.url 을 jdbc:postgresql://<호스트>[:<포트>][,<호스트>[:<포트>]…]/<DB>[?<이름>=<값>&…] 로 지정하세요. 인증정보는 URL 에 넣지 않습니다.");
            case OK -> {
            }
        }
        if (username == null || username.isBlank()) {
            throw new ManagedDatabaseConfigurationException("관리 DB 사용자명이 없습니다 : spring.datasource.username 이 비어 있습니다.",
                    "spring.datasource.username (환경변수 SPRING_DATASOURCE_USERNAME) 을 지정하세요.");
        }
    }

    enum Verdict { OK, CREDENTIAL, MALFORMED }

    /** URL 에 인증정보가 있는가 (형식 오류는 false — {@link #inspect} 가 따로 알린다) */
    static boolean hasCredential(String url) {
        return inspect(url) == Verdict.CREDENTIAL;
    }

    /**
     * {@code jdbc:postgresql:} 뒤를 pgJDBC 처럼 나눠 본다 : 첫 {@code ?} 앞 = 서버 부분({@code //호스트목록/DB} 또는 {@code DB}), 뒤 = {@code &} 로 나눈 매개변수.
     * 디코딩은 한 번만 한다 (드라이버도 한 번). 인증정보가 형식 오류보다 먼저다 (둘 다면 인증정보로 알린다)
     */
    static Verdict inspect(String url) {
        String rest = url.substring(PREFIX.length());
        int q = rest.indexOf('?');
        String server = q < 0 ? rest : rest.substring(0, q);
        String query = q < 0 ? "" : rest.substring(q + 1);
        boolean malformed = false;
        String database = server;
        if (server.startsWith("//")) {
            int slash = server.indexOf('/', 2);
            String authority = slash < 0 ? server.substring(2) : server.substring(2, slash);
            database = slash < 0 ? "" : server.substring(slash + 1);
            if (authority.indexOf('@') >= 0) return Verdict.CREDENTIAL; // user:비밀번호@ · user@ (pgJDBC 는 받지 않고, 오류에 URL 원문이 남는다)
            if (!authority.isEmpty()) {
                for (String host : authority.split(",", -1)) {
                    if (!HOST.matcher(host).matches()) malformed = true;
                }
            }
        }
        String db = decode(database);
        if (db == null) malformed = true;
        else if (CREDENTIAL_EXPRESSION.matcher(db.toLowerCase(Locale.ROOT)).find()) return Verdict.CREDENTIAL;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (name == null || value == null) {
                if (pair.toLowerCase(Locale.ROOT).contains("password")) return Verdict.CREDENTIAL;
                malformed = true;
                continue;
            }
            if (name.toLowerCase(Locale.ROOT).contains("password")) return Verdict.CREDENTIAL;
            if (CREDENTIAL_EXPRESSION.matcher(value.toLowerCase(Locale.ROOT)).find()) return Verdict.CREDENTIAL;
            if (!PARAMETER_NAME.matcher(name).matches()) malformed = true;
        }
        return malformed ? Verdict.MALFORMED : Verdict.OK;
    }

    /** URL 디코딩 한 번 ({@code +} 는 글자 그대로). 잘못된 % 표기면 null */
    private static String decode(String part) {
        try {
            return URLDecoder.decode(part.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** 오류 문구용 : jdbc:xxx 부분만 (URL 나머지는 남기지 않는다) */
    private static String scheme(String url) {
        String[] parts = url.split(":", 3);
        String name = parts.length >= 2 ? parts[0] + ":" + parts[1] : parts[0];
        name = name.replaceAll("[^A-Za-z0-9:_-]", "");
        return name.length() > 30 ? name.substring(0, 30) : name;
    }

    /** 관리 DB 설정 오류 (기동 중단) — 문구에 비밀값이 없다 */
    public static class ManagedDatabaseConfigurationException extends IllegalStateException {

        private final String action;

        public ManagedDatabaseConfigurationException(String message, String action) {
            super(message);
            this.action = action;
        }

        public String getAction() {
            return action;
        }
    }
}
