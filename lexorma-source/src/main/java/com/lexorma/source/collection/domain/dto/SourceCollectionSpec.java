package com.lexorma.source.collection.domain.dto;

import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.collection.domain.enums.CollectionType;
import com.lexorma.source.collection.domain.enums.TargetResult;

import java.util.List;

/**
 * 수집 실행의 입력 (AI-1c). 내부 경로 전용이다 : 공개 API 로 받지 않는다 — 수집 도구(AI-2)가 원본 DB 를 읽어 이 모양으로 넘기고, 테스트가 쓴다.
 * 테이블 하나의 내용은 AI-1b 의 {@link SourceCatalogSpec} 을 그대로 쓴다.
 */
public final class SourceCollectionSpec {

    private SourceCollectionSpec() {
    }

    /** 이름 공간 (카탈로그·스키마 중 하나는 있어야 한다) */
    public record Namespace(Name catalog, Name schema) {
    }

    /**
     * 수집 등록. FULL 은 schemas 를 비워 두고(연결 전체), PARTIAL 은 대상 스키마를 하나 이상 준다.
     */
    public record Request(CollectionType type, List<Namespace> schemas) {
        public static Request full() {
            return new Request(CollectionType.FULL, List.of());
        }

        public static Request partial(Namespace... schemas) {
            return new Request(CollectionType.PARTIAL, List.of(schemas));
        }
    }

    /**
     * 수집 결과. 스키마마다 결과를 따로 알린다 : 도구는 그 스키마의 모든 객체를 빠짐없이 읽었을 때만 COMPLETED 를 준다.
     * 하나라도 COMPLETED 가 아니면 실행 전체를 반영하지 않는다.
     */
    public record Result(List<Schema> schemas) {
    }

    /** @param errorCode 실패한 경우의 오류 코드 (메시지·접속 정보를 넣지 않는다) */
    public record Schema(Name catalog, Name schema, TargetResult status, String errorCode, List<Table> tables) {
        public static Schema completed(Name catalog, Name schema, List<Table> tables) {
            return new Schema(catalog, schema, TargetResult.COMPLETED, null, tables);
        }
    }

    public record Table(SourceCatalogSpec.Table table, List<SourceCatalogSpec.Column> columns, List<SourceCatalogSpec.Constraint> constraints,
                        List<SourceCatalogSpec.Index> indexes, List<SourceCatalogSpec.Relation> relations) {
    }
}
