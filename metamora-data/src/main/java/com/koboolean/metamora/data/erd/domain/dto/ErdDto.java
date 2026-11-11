package com.koboolean.metamora.data.erd.domain.dto;

import com.koboolean.metamora.data.physical.domain.dto.PhysicalModelDto;

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

    /** termName : 표준 용어명 (컬럼 논리명), domainName : 그 용어의 표준 도메인명 (둘 다 없을 수 있다). 컬럼 순서는 sortOrder → id */
    public record Column(Long id, Long tableId, String columnName, String termName, String columnDesc, String dataType,
                         Integer maxLength, BigDecimal precision, Integer scale, Boolean isNullable, Boolean isPk,
                         Boolean isApproval, String domainName) {
    }

    /**
     * 조회 범위의 테이블·컬럼과, 그 테이블이 한쪽이라도 걸린 관계 (상대 테이블이 범위 밖일 수 있다).
     * totalCount : 조건에 맞는 전체 테이블 수, truncated : 한도 때문에 일부만 담았는지
     */
    public record Graph(List<Table> tables, List<Column> columns, List<PhysicalModelDto.Relation> relations,
                        long totalCount, boolean truncated) {
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
