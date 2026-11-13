package com.lexorma.source.tool;

import java.util.List;

/**
 * 메타데이터 도구의 입력·결과 (DBMS 와 상관없는 모양, AI-2b). 객체 하나의 내용은 {@code SourceCatalogSpec} 을 그대로 쓴다.
 */
public final class SourceMetadata {

    private SourceMetadata() {
    }

    /**
     * {@link SourceTools#CONNECTION_TEST} 의 결과.
     *
     * @param tls 전송 암호화 상태
     */
    public record ConnectionInfo(String productName, String productVersion, boolean metadataReadable, Tls tls) {
    }

    /**
     * @param mode                연결에 정한 방식 (sslmode, 없으면 드라이버 기본)
     * @param encrypted           이 접속이 실제로 암호화되었는지 (서버가 알려 준 값)
     * @param certificateVerified 서버 인증서를 신뢰할 수 있는 CA 로 확인했는지 (verify-ca·verify-full 이면서 암호화된 경우만)
     * @param hostnameVerified    인증서의 이름이 호스트와 같은지 확인했는지 (verify-full 만)
     */
    public record Tls(String mode, boolean encrypted, String protocol, boolean certificateVerified, boolean hostnameVerified) {
    }

    /** 테이블 단위 도구의 입력 : 조회할 스키마 (원본 이름 그대로). 한 번의 조회로 이 스키마들을 모두 읽는다 */
    public record Scope(List<String> schemas) {
        public Scope {
            schemas = List.copyOf(schemas);
        }
    }

    /** 테이블 하나에 딸린 객체 (컬럼·제약조건·인덱스·관계) 또는 테이블 자체 */
    public record Owned<T>(String schema, String table, T item) {
    }
}
