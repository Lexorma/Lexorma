package com.lexorma.data.erd.domain.dto;

import com.lexorma.data.physical.domain.dto.PhysicalModelDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 물리 ERD 조회·레이아웃 DTO (읽기 전용, 엔티티를 거치지 않고 필요한 컬럼만 조회한다) */
public final class ErdDto {

    private ErdDto() {
    }

    public record Table(Long id, Long schemaId, String tableName, String tableKorName, String tableDescription,
                        Boolean isApproval, Boolean isMasterTable) {
    }

    /**
     * termName : 표준 용어명 (컬럼 논리명), domainName : 그 용어의 표준 도메인명 (둘 다 없을 수 있다). 컬럼 순서는 sortOrder → id.
     * isUnique (단일 컬럼 UNIQUE)·isRequired (필수 입력, DDL 에서 NOT NULL) 는 M-2 개선 2차에 뒤에 더했다 (응답 필드 추가만)
     */
    public record Column(Long id, Long tableId, String columnName, String termName, String columnDesc, String dataType,
                         Integer maxLength, BigDecimal precision, Integer scale, Boolean isNullable, Boolean isPk,
                         Boolean isApproval, String domainName, Boolean isUnique, Boolean isRequired) {
    }

    /**
     * 관계를 어디까지 담을지 (M-2 개선 2차 D-05·D-15). ALL (기본, 예전 그대로) : 범위 테이블이 한쪽이라도 걸린 관계 전부.
     * INNER : 양 끝이 모두 범위 안인 관계만 담고, 범위 밖 상대와의 관계는 테이블별 수(outsideRelations)로만 준다 — 목록은 테이블별 관계 조회로
     */
    public enum RelationScope { ALL, INNER }

    /**
     * 조회 범위의 테이블·컬럼과 관계 (relationScope 에 따라 범위 밖 상대와의 관계를 담거나 수만 준다).
     * totalCount : 조건에 맞는 전체 테이블 수, truncated : 한도 때문에 일부만 담았는지.
     * outsideRelations : INNER 일 때만, 범위 안 테이블 ID → 범위 밖 테이블과의 관계 수 (FK 제약조건 1개 = 1, 없으면 키가 없다). ALL 이면 null.
     * fkColumnIds : INNER 일 때만, 범위 안 테이블에서 FK 관계(relationType FK)의 원본 컬럼 ID — 상대가 범위 밖이어도 노드에 FK 표시를 하려고. ALL 이면 null.
     * cardinalities : INNER 일 때 양 끝이 범위 안인 관계 ID → 물리 제약조건으로 판단한 카디널리티·식별 관계 (조회 때 계산, 저장하지 않는다). ALL 이면 빈 맵
     */
    public record Graph(List<Table> tables, List<Column> columns, List<PhysicalModelDto.Relation> relations,
                        long totalCount, boolean truncated, Map<Long, Long> outsideRelations, List<Long> fkColumnIds, Map<Long, Cardinality> cardinalities) {
        public Graph(List<Table> tables, List<Column> columns, List<PhysicalModelDto.Relation> relations, long totalCount, boolean truncated) {
            this(tables, columns, relations, totalCount, truncated, null, null, Map.of());
        }
    }

    /**
     * 관계 하나의 카디널리티 (M-2 개선 2차 D-08~D-10). 자식 = 참조하는 테이블(관계의 source), 부모 = 참조되는 테이블(target).
     * parentMin·parentMax : 자식 행 하나가 가리키는 부모 행 수의 최소·최대, childMax : 부모 행 하나를 가리키는 자식 행 수의 최대 ("0"·"1"·"N", 판단할 수 없으면 null).
     * 자식 쪽 최소(부모에 자식이 꼭 있어야 하는지)는 FK 로 보장되지 않아 늘 판단하지 않는다.
     * identifying : IDENTIFYING (FK 컬럼이 모두 자식 PK) / PARTIAL (일부만) / NON_IDENTIFYING (PK 와 별도, PK 없음 포함) / null (판단할 수 없음).
     * reasons : 판단하지 못한 사유 코드 (LOGICAL_RELATION, INVALID_REFERENCE, COLUMNS_MISSING, NULLABLE_UNKNOWN, NULLABLE_MIXED)
     */
    public record Cardinality(String parentMin, String parentMax, String childMax, String identifying, List<String> reasons) {
    }

    /** 테이블 찾기 결과 한 건 (M-2 개선 2차 D-02) : 목록 표시와 이동에 필요한 것만 */
    public record TableHit(Long id, Long schemaId, String schemaName, String tableName, String tableKorName) {
    }

    /** 테이블 찾기 : 이름순 최대 50개 + 조건에 맞는 전체 수 */
    public record TableHits(List<TableHit> items, long totalCount) {
    }

    /**
     * 테이블 하나의 관계 목록 한 페이지 (M-2 개선 2차 D-05) : 관계(컬럼 쌍 전부, columnOrder 순)와 그 카디널리티,
     * 상대 테이블 정보(tables : 관계에 나온 테이블 ID → 테이블 — 스키마 구분과 이동용). totalCount : 이 테이블의 전체 관계 수
     */
    public record RelationPage(List<PhysicalModelDto.Relation> items, Map<Long, Cardinality> cardinalities, Map<Long, Table> tables, long totalCount) {
    }

    /** 목록 조회 조건 (모두 선택) */
    public record Search(Long schemaId, String keyword, Boolean isApproval, Boolean isMasterTable) {
    }

    public record Point(double x, double y) {
    }

    public record Viewport(double x, double y, double zoom) {
    }

    /**
     * 내 배치. version 은 저장된 적 없으면 null (viewport 도 null) - 저장 요청에 조회한 version 을 그대로 보낸다.
     * 저장 요청의 nodes 는 화면에 있는 테이블만 보내도 된다 (기존 저장 좌표와 합친다).
     */
    public record Layout(Long version, Map<Long, Point> nodes, Viewport viewport) {
    }
}
