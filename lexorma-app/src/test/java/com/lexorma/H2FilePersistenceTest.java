package com.lexorma;

import com.lexorma.config.H2FileDatabaseCheck;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * h2-local 프로필의 H2 파일 DB 영속성. 전체 애플리케이션을 이 테스트 JVM 에서 두 번 띄우면 공유되는 정적 상태(AuthUtil 등)가 캐시된 다른 테스트 컨텍스트를
 * 흔들므로, 실제 프로필 설정의 JDBC URL·Flyway 마이그레이션·H2 파일로 확인한다 (애플리케이션 재시작 검증은 단일 JAR 로 따로 했다 : docs/reference/h2-file-storage.md).
 * 임시 디렉터리만 쓰고 사용자의 H2 파일(~/.lexorma)은 열지 않는다.
 */
class H2FilePersistenceTest {

    @TempDir
    Path temp;

    /** application-h2-local.yml 의 URL 을 LEXORMA_DATA_DIR 로 풀어 쓴다 (실제 설정 파일을 읽는다) */
    private static String h2LocalUrl(Path dataDir) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-h2-local.yml"));
        Properties properties = yaml.getObject();
        MockEnvironment environment = new MockEnvironment().withProperty("LEXORMA_DATA_DIR", dataDir.toString());
        return environment.resolveRequiredPlaceholders(properties.getProperty("spring.datasource.url"));
    }

    @Test
    @DisplayName("h2-local 은 파일 DB 다 : 기본 위치는 사용자 홈의 .lexorma/data, LEXORMA_DATA_DIR 로 바꿀 수 있고 JVM 종료 훅으로 닫지 않는다. 테스트 프로필은 메모리 DB 그대로")
    void profileUsesFileDatabase() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-h2-local.yml"));
        String raw = yaml.getObject().getProperty("spring.datasource.url");
        assertThat(raw).startsWith("jdbc:h2:file:${LEXORMA_DATA_DIR:${user.home}/.lexorma/data}/lexorma").contains("DB_CLOSE_ON_EXIT=FALSE").doesNotContain("mem:");
        String defaultUrl = new MockEnvironment().withProperty("user.home", "/home/someone").resolveRequiredPlaceholders(raw);
        assertThat(defaultUrl).startsWith("jdbc:h2:file:/home/someone/.lexorma/data/lexorma;");
        assertThat(h2LocalUrl(temp)).startsWith("jdbc:h2:file:" + temp + "/lexorma;");

        YamlPropertiesFactoryBean test = new YamlPropertiesFactoryBean();
        test.setResources(new ClassPathResource("application-test.yml"));
        assertThat(test.getObject().getProperty("spring.datasource.url")).as("자동화 테스트는 메모리 DB").startsWith("jdbc:h2:mem:");
    }

    @Test
    @DisplayName("재시작 영속성 : 처음 연결하면 파일을 만들고 Flyway 가 전부 적용, 데이터를 넣고 닫은 뒤 다시 열면 마이그레이션을 다시 하지 않고 데이터·이력이 남아 있다")
    void dataSurvivesReopen() throws Exception {
        Path dataDir = temp.resolve("data");
        String url = h2LocalUrl(dataDir);
        H2FileDatabaseCheck.check(url);
        assertThat(dataDir).isDirectory();

        MigrateResult first = flyway(url).migrate();
        assertThat(first.migrationsExecuted).isPositive();
        assertThat(dataDir.resolve("lexorma.mv.db")).exists();
        int history;
        try (Connection connection = DriverManager.getConnection(url, "lexorma", "lexorma"); Statement statement = connection.createStatement()) {
            history = count(statement, "select count(*) from \"flyway_schema_history\"");
            // 새 설치에는 계정이 없다 (기본 관리자 계정 없음) — 최초 관리자 계정 만들기처럼 한 행을 넣는다
            statement.executeUpdate("insert into TB_ACNT(ACNT_SN, USER_ID, USER_NM, PSWD, PSWD_CHG_YN, ACTVTN_YN, RGTR_ID, MDFR_ID) "
                    + "values(next value for SQ_ACNT, 'owner', 'persisted-owner', '{bcrypt}x', true, true, 't', 't')");
            statement.executeUpdate("insert into TB_AI_PRVDR(AI_PRVDR_SN, PRVDR_NM, PRVDR_TYPE_CD, BSC_URL_ADDR, BSC_MDL_NM, ACTVTN_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) "
                    + "values(next value for SQ_AI_PRVDR, 'persisted provider', 'OLLAMA', 'http://127.0.0.1:11434', 'llama3.2', true, current_timestamp, current_timestamp, 't', 't')");
            statement.executeUpdate("update TB_AI_STNG set BSC_AI_PRVDR_SN = (select AI_PRVDR_SN from TB_AI_PRVDR where PRVDR_NM = 'persisted provider')");
        }
        // 마지막 연결이 닫히면 H2 가 DB 를 닫는다 (DB_CLOSE_DELAY 없음) : 다른 프로세스처럼 파일 잠금을 다시 잡을 수 있다
        H2FileDatabaseCheck.check(url);

        MigrateResult second = flyway(url).migrate();
        assertThat(second.migrationsExecuted).as("재시작 때 이미 적용한 마이그레이션을 다시 하지 않는다").isZero();
        try (Connection connection = DriverManager.getConnection(url, "lexorma", "lexorma"); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "select count(*) from \"flyway_schema_history\"")).isEqualTo(history);
            assertThat(count(statement, "select count(*) from \"flyway_schema_history\" where \"success\" = false")).isZero();
            assertThat(count(statement, "select count(*) from TB_ACNT where USER_ID = 'owner' and USER_NM = 'persisted-owner'")).isEqualTo(1);
            assertThat(count(statement, "select count(*) from TB_AI_STNG s join TB_AI_PRVDR p on p.AI_PRVDR_SN = s.BSC_AI_PRVDR_SN where p.PRVDR_NM = 'persisted provider'"))
                    .as("AI Provider 와 시스템 기본 지정").isEqualTo(1);
            assertThat(count(statement, "select count(*) from TB_TBL where PRJ_SN = 0")).as("예제 메타데이터").isPositive();
        }
    }

    @Test
    @DisplayName("파일 잠금 : DB 를 다른 연결(프로세스)이 열고 있으면 시작 점검이 경로와 해결 방법을 담은 오류로 멈춘다")
    void detectsDatabaseInUse() throws Exception {
        String url = h2LocalUrl(temp.resolve("locked"));
        H2FileDatabaseCheck.check(url);
        try (Connection open = DriverManager.getConnection(url, "lexorma", "lexorma")) {
            assertThat(open.isValid(1)).isTrue();
            assertThatThrownBy(() -> H2FileDatabaseCheck.check(url))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("다른 프로세스가 쓰고 있습니다").hasMessageContaining("lexorma.mv.db")
                    .hasMessageContaining("LEXORMA_DATA_DIR");
        }
    }

    @Test
    @DisplayName("저장 디렉터리 권한 : 쓸 수 없는 위치면 경로와 LEXORMA_DATA_DIR 안내를 담은 오류로 멈춘다. 메모리 DB·PostgreSQL 은 점검하지 않는다")
    void reportsUnwritableDirectory() throws Exception {
        Path readOnly = Files.createDirectory(temp.resolve("readonly"));
        try {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
            assertThatThrownBy(() -> H2FileDatabaseCheck.check(h2LocalUrl(readOnly.resolve("data"))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("H2 데이터 디렉터리").hasMessageContaining("LEXORMA_DATA_DIR");
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        H2FileDatabaseCheck.check("jdbc:h2:mem:lexorma;DB_CLOSE_DELAY=-1");
        H2FileDatabaseCheck.check("jdbc:postgresql://localhost:15439/lexorma");
        assertThat(H2FileDatabaseCheck.databaseFile("jdbc:h2:file:~/x/lexorma;A=1")).isEqualTo(Path.of(System.getProperty("user.home"), "x", "lexorma"));
    }

    private static Flyway flyway(String url) {
        return Flyway.configure().dataSource(url, "lexorma", "lexorma").locations("classpath:db/migration/h2").load();
    }

    private static int count(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
