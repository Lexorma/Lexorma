package com.lexorma;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 테스트 DB 안전 확인 (DB-1 2차, PG-D19) : 테스트 컨텍스트는 Flyway 로 스키마·예제·테스트 계정을 넣고 테스트 데이터를 대량으로 만든다 — 개발·운영 DB 를 가리키면 그 DB 를 더럽힌다.
 * 테스트 클래스패스의 {@code @Component} 라 {@code @SpringBootTest} 컨텍스트마다 DataSource·Flyway 보다 먼저 돈다 (BeanFactoryPostProcessor, 운영 ManagedDatabaseCheck 보다 먼저).
 * <ul>
 *   <li>테스트 전용 키 {@code LEXORMA_TEST_POSTGRES_URL} · {@code LEXORMA_TEST_POSTGRES_USER} 가 있어야 한다 (환경변수 또는 같은 이름의 시스템 속성)</li>
 *   <li>실제로 쓰일 {@code spring.datasource.url} 이 그 테스트 URL 과 같은 DB 여야 한다 (쿼리 매개변수 — 예 : 별도 스키마 {@code currentSchema} — 만 다를 수 있다).
 *       일반 설정({@code SPRING_DATASOURCE_URL} 등)이 테스트 설정을 덮어쓰면 멈춘다</li>
 *   <li>사용자명도 테스트 키와 같아야 한다</li>
 *   <li>DB 이름에 {@code test} 가 있어야 한다 — 보조 안전장치일 뿐 이것만으로 안전하다고 보지 않는다 (테스트 전용 계정 권장)</li>
 * </ul>
 * DB 가 필요 없는 테스트(스프링 컨텍스트 없음)는 이 확인을 거치지 않는다. 스프링 컨텍스트 없이 테스트 DB 에 직접 연결하는 테스트(마이그레이션 신규 설치 시험 등)는
 * {@link #directDataSource()} 로만 연결한다 — 같은 확인을 거치고, 키가 없으면 건너뛰지 않고 실패한다 (DB-1 3차).
 */
@Component
public class TestDatabaseGuard implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    static final String URL_KEY = "LEXORMA_TEST_POSTGRES_URL";
    static final String USER_KEY = "LEXORMA_TEST_POSTGRES_USER";
    static final String PASSWORD_KEY = "LEXORMA_TEST_POSTGRES_PASSWORD";

    private Environment environment;

    /** 테스트 DB URL (없으면 null) */
    static String url() {
        return value(URL_KEY);
    }

    /**
     * 테스트 DB 에 직접 연결하는 DataSource (테스트 전용 키 3개만 — 일반 SPRING_DATASOURCE_* · 고정 계정을 쓰지 않는다). 연결 전에 {@link #check} 와 같은 확인을 한다.
     * 비밀번호는 인증 방식에 따라 없을 수 있다
     */
    static DriverManagerDataSource directDataSource() {
        String url = url(), user = value(USER_KEY);
        check(url, user, url, user);
        String password = System.getProperty(PASSWORD_KEY, System.getenv(PASSWORD_KEY));
        return new DriverManagerDataSource(url, user, password);
    }

    /** 같은 확인 뒤 연결 하나를 계속 쓰는 DataSource ({@code set search_path} 처럼 세션 설정이 이어져야 하는 실행용 — 쓴 뒤 {@code destroy()}) */
    static SingleConnectionDataSource singleConnectionDataSource() {
        DriverManagerDataSource d = directDataSource();
        return new SingleConnectionDataSource(d.getUrl(), d.getUsername(), d.getPassword(), true);
    }

    private static String value(String key) {
        String v = System.getProperty(key, System.getenv(key));
        return v == null || v.isBlank() ? null : v.trim();
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(url(), value(USER_KEY), environment.getProperty("spring.datasource.url"), environment.getProperty("spring.datasource.username"));
    }

    static void check(String testUrl, String testUser, String effectiveUrl, String effectiveUser) {
        if (testUrl == null || testUser == null) {
            throw new IllegalStateException("테스트 DB 가 지정되지 않았습니다 : " + URL_KEY + " · " + USER_KEY + " (· LEXORMA_TEST_POSTGRES_PASSWORD) 로 이름에 test 가 들어간 빈 PostgreSQL DB 를 지정하세요.");
        }
        if (!testUrl.regionMatches(true, 0, "jdbc:postgresql:", 0, "jdbc:postgresql:".length())) {
            throw new IllegalStateException(URL_KEY + " 는 jdbc:postgresql: URL 이어야 합니다.");
        }
        if (effectiveUrl == null || !base(effectiveUrl).equals(base(testUrl))) {
            throw new IllegalStateException("테스트가 쓸 spring.datasource.url 이 " + URL_KEY + " 와 다른 DB 를 가리킵니다 — 일반 설정(SPRING_DATASOURCE_URL 등)을 테스트에 쓰지 않습니다. 그 설정을 지우세요.");
        }
        if (!testUser.equals(effectiveUser)) {
            throw new IllegalStateException("테스트가 쓸 spring.datasource.username 이 " + USER_KEY + " 와 다릅니다 — 일반 설정(SPRING_DATASOURCE_USERNAME 등)을 지우세요.");
        }
        if (!databaseName(testUrl).toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(URL_KEY + " 의 DB 이름에 test 가 없습니다 — 개발·운영 DB 를 테스트에 쓰지 않도록 테스트 전용 DB(예 : lexorma_test)를 만들어 지정하세요.");
        }
    }

    /** 쿼리 매개변수를 뺀 URL (대소문자 무시 비교용) */
    static String base(String url) {
        int q = url.indexOf('?');
        return (q < 0 ? url : url.substring(0, q)).trim().toLowerCase(Locale.ROOT);
    }

    static String databaseName(String url) {
        String b = base(url);
        int slash = b.lastIndexOf('/');
        return slash < 0 ? b.substring(b.lastIndexOf(':') + 1) : b.substring(slash + 1);
    }
}
