package com.lexorma.core.logs.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    @DisplayName("JSON : 중첩 객체/배열의 비밀번호 계열 key 값을 마스킹한다")
    void maskJson() {
        String masked = SensitiveDataMasker.mask(
                "{\"username\":\"admin\",\"password\":\"secret\",\"items\":[{\"currentPassword\":\"old\",\"pwd\":\"x\",\"name\":\"n\"}],\"changedPasswd\":null}");

        assertThat(masked)
                .contains("\"username\":\"admin\"")
                .contains("\"password\":\"****\"")
                .contains("\"currentPassword\":\"****\"")
                .contains("\"pwd\":\"****\"")
                .contains("\"name\":\"n\"")
                .contains("\"changedPasswd\":null")
                .doesNotContain("secret", "\"old\"");
    }

    @Test
    @DisplayName("form-urlencoded : 비밀번호 계열 파라미터 값을 마스킹한다")
    void maskForm() {
        assertThat(SensitiveDataMasker.mask("username=admin&password=secret&project_id=0"))
                .isEqualTo("username=admin&password=****&project_id=0");
    }

    @Test
    @DisplayName("JSON 형식이 깨진 경우에도 비밀 key 의 값은 가리고, 비밀값이 없으면 그대로 둔다. 빈 값은 그대로 반환한다")
    void invalidOrEmpty() {
        assertThat(SensitiveDataMasker.mask("{broken")).isEqualTo("{broken");
        assertThat(SensitiveDataMasker.mask(null)).isNull();
        assertThat(SensitiveDataMasker.mask("")).isEmpty();
        // 잘린 JSON : 읽지 못해도 원문을 그대로 남기지 않는다
        assertThat(SensitiveDataMasker.mask("{\"name\":\"db\",\"password\":\"p@ss\",\"apiKey\":\"sk-123\",\"memo\":\"잘림"))
                .contains("\"name\":\"db\"").contains("\"password\":\"****\"").contains("\"apiKey\":\"****\"").doesNotContain("p@ss", "sk-123");
    }

    @Test
    @DisplayName("key 이름 : 대소문자와 '_'·'-' 표기 차이를 없애고 token·secret·apiKey·authorization·credential·accessKey·secretKey·privateKey·connectionString 을 가린다")
    void sensitiveKeys() {
        for (String key : List.of("password", "PASSWD", "userPwd", "token", "accessToken", "refresh_token", "X-CSRF-TOKEN", "secret", "clientSecret", "client_secret",
                "apiKey", "api_key", "API-KEY", "x-api-key", "authorization", "Authorization", "credential", "credentials", "dbCredential",
                "accessKey", "access_key", "secretKey", "SECRET_KEY", "privateKey", "private-key", "connectionString", "connection_string", "passphrase")) {
            assertThat(SensitiveDataMasker.isSensitiveKey(key)).as(key).isTrue();
            assertThat(SensitiveDataMasker.mask("{\"" + key + "\":\"VALUE-123\"}")).as(key).isEqualTo("{\"" + key + "\":\"****\"}");
            assertThat(SensitiveDataMasker.mask("a=1&" + key + "=VALUE-123&b=2")).as(key).isEqualTo("a=1&" + key + "=****&b=2");
        }
    }

    @Test
    @DisplayName("비밀값이 아닌 일반 필드는 그대로 둔다 : tokenCount, 설정 여부(참·거짓), 개수·길이·종류")
    void benignKeys() {
        for (String key : List.of("tokenCount", "token_count", "maxTokens", "promptTokens", "totalTokensUsed", "tokenType", "passwordLength", "secretCount", "apiKeyType",
                "username", "keyword", "primaryKey", "publicKeyId", "author", "description", "passwordPolicy")) {
            assertThat(SensitiveDataMasker.isSensitiveKey(key)).as(key).isFalse();
        }
        String body = "{\"tokenCount\":120,\"maxTokens\":4096,\"model\":\"llama\",\"isPasswdCheck\":true,\"secretConfigured\":false,\"apiKey\":null}";
        assertThat(SensitiveDataMasker.mask(body)).isEqualTo(body);
        assertThat(SensitiveDataMasker.mask("tokenCount=120&page=0")).isEqualTo("tokenCount=120&page=0");
    }

    @Test
    @DisplayName("비밀 key 의 값이 객체·배열·숫자여도 통째로 가린다")
    void sensitiveContainer() {
        assertThat(SensitiveDataMasker.mask("{\"credentials\":{\"user\":\"scott\",\"pw\":\"tiger\"},\"secrets\":[\"a1\",\"b2\"],\"pwd\":1234,\"name\":\"n\"}"))
                .isEqualTo("{\"credentials\":\"****\",\"secrets\":\"****\",\"pwd\":\"****\",\"name\":\"n\"}");
    }

    @Test
    @DisplayName("값 안의 비밀 : JDBC URL·URI 의 계정 정보와 파라미터, Bearer·Basic 값은 key 이름과 상관없이 가린다 (JSON·form·오류 메시지)")
    void secretsInsideValues() {
        assertThat(SensitiveDataMasker.maskText("jdbc:postgresql://scott:tiger@db.internal:5432/app")).isEqualTo("jdbc:postgresql://scott:****@db.internal:5432/app");
        assertThat(SensitiveDataMasker.maskText("jdbc:postgresql://db:5432/app?user=scott&password=tiger&ssl=true")).isEqualTo("jdbc:postgresql://db:5432/app?user=scott&password=****&ssl=true");
        assertThat(SensitiveDataMasker.maskText("jdbc:oracle:thin:scott/tiger@db:1521:ORCL")).isEqualTo("jdbc:oracle:thin:scott/****@db:1521:ORCL");
        assertThat(SensitiveDataMasker.maskText("jdbc:sqlserver://db:1433;databaseName=app;user=sa;password=tiger;encrypt=true")).isEqualTo("jdbc:sqlserver://db:1433;databaseName=app;user=sa;password=****;encrypt=true");
        assertThat(SensitiveDataMasker.maskText("http://ollama:s3cret@10.0.0.5:11434/api/tags?api_key=abc123&model=llama")).isEqualTo("http://ollama:****@10.0.0.5:11434/api/tags?api_key=****&model=llama");
        assertThat(SensitiveDataMasker.maskText("Authorization: Bearer eyJhbGciOi.payload.sig")).isEqualTo("Authorization: Bearer ****");
        assertThat(SensitiveDataMasker.maskText("Connection to jdbc:mariadb://app:pw123@10.0.0.9/db refused")).doesNotContain("pw123").contains("refused");
        // 인증 정보가 없는 주소는 그대로다
        assertThat(SensitiveDataMasker.maskText("http://10.0.0.5:11434/api/tags?model=llama&page=2")).isEqualTo("http://10.0.0.5:11434/api/tags?model=llama&page=2");

        // key 이름은 평범하지만(url, endpoint) 값에 비밀이 들어 있다
        assertThat(SensitiveDataMasker.mask("{\"url\":\"jdbc:postgresql://db/app?user=scott&password=tiger\",\"endpoint\":\"https://u:p4ss@ai.internal/v1\",\"header\":\"Basic c2NvdHQ6dGlnZXI=\"}"))
                .doesNotContain("tiger", "p4ss", "c2NvdHQ6dGlnZXI=").contains("password=****").contains("https://u:****@ai.internal/v1").contains("Basic ****");
        assertThat(SensitiveDataMasker.mask("name=db&url=jdbc%3Apostgresql%3A%2F%2Fscott%3Atiger%40db%2Fapp"))
                .startsWith("name=db&url=").doesNotContain("tiger").contains("****");
    }
}
