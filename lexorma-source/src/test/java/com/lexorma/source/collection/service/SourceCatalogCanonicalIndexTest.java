package com.lexorma.source.collection.service;

import com.lexorma.core.project.domain.enums.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCatalogCanonicalIndexTest {

    @Test
    @DisplayName("컬럼만의 오름차순 인덱스는 AI-1c(FORMAT 1)와 같은 JSON·해시다 : 표현식·정렬·조건 항목은 있을 때만 들어간다")
    void plainIndexKeepsFormat1() {
        SourceCatalogCanonical.Index plain = new SourceCatalogCanonical.Index("name:ix_a", "ix_a", "btree", false, List.of("a", "b"), Arrays.asList(null, null),
                Arrays.asList(null, null), null);
        String json = SourceCatalogCanonical.json(plain.json());
        assertThat(json).isEqualTo("{\"key\":\"name:ix_a\",\"name\":\"ix_a\",\"indexType\":\"btree\",\"unique\":false,\"columns\":[\"a\",\"b\"]}");
        assertThat(SourceCatalogCanonical.hash(json)).isEqualTo("v1:" + sha256(json));
        assertThat(plain.expressions()).isNull();
        assertThat(plain.sorts()).isNull();
        // 정렬이 모두 null 이면 없는 것과 같다 (현재 행에서 만든 것과 수집 결과에서 만든 것이 같아진다)
        assertThat(plain).isEqualTo(new SourceCatalogCanonical.Index("name:ix_a", "ix_a", "btree", false, List.of("a", "b"), null, null, null));
        assertThat(SourceCatalogCanonical.indexKey(DbType.POSTGRESQL, null, true, List.of("a", "b"), null)).isEqualTo("sig:U:a,b");
    }

    @Test
    @DisplayName("표현식·혼합·DESC·부분 인덱스 : 구성 요소 순서대로 컬럼/표현식/정렬, 조건이 JSON 에 남는다")
    void expressionIndex() {
        SourceCatalogCanonical.Index mixed = new SourceCatalogCanonical.Index("name:ix_m", "ix_m", "btree", false, Arrays.asList("created_at", null),
                Arrays.asList(null, "lower((email)::text)"), Arrays.asList("DESC", null), "(active = true)");
        assertThat(SourceCatalogCanonical.json(mixed.json())).isEqualTo("{\"key\":\"name:ix_m\",\"name\":\"ix_m\",\"indexType\":\"btree\",\"unique\":false,"
                + "\"columns\":[\"created_at\",null],\"expressions\":[null,\"lower((email)::text)\"],\"sorts\":[\"DESC\",null],\"predicate\":\"(active = true)\"}");
        assertThat(SourceCatalogCanonical.indexKey(DbType.POSTGRESQL, null, false, Arrays.asList("created_at", null), Arrays.asList(null, "lower(email)")))
                .isEqualTo("sig:N:created_at,(lower(email))");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
