package com.lexorma.data.version.domain.dto;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 모델 버전 요청·응답 */
public final class ModelVersionDto {

    private ModelVersionDto() {
    }

    public record Create(String versionName, String description) {
    }

    /** 목록·상세 : counts = 대상 종류별 객체 수 */
    public record Summary(Long id, String versionName, String description, Integer formatVersion, JsonNode counts, String createdBy, LocalDateTime createdAt) {
    }

    /** 저장된 모델 내용 : objects = 대상 종류 → [{id, name, state}] */
    public record Snapshot(Summary version, JsonNode objects) {
    }

    /** 비교의 한쪽. 현재 모델이면 id 가 null 이다 */
    public record Side(Long id, String versionName, LocalDateTime createdAt) {
    }

    /**
     * 비교한 객체 하나. change = ADDED(before 없음) · REMOVED(after 없음) · MODIFIED. changedFields 는 MODIFIED 에서 값이 다른 필드.
     * ERD 비교에서는 그리는 데 필요한 달라지지 않은 객체도 UNCHANGED(before 와 after 가 같은 값)로 담긴다
     */
    public record Change(String targetType, Long targetId, String name, String change, List<String> changedFields, JsonNode before, JsonNode after) {
    }

    /** totals·byType : ADDED·REMOVED·MODIFIED·UNCHANGED 건수. changes 에는 달라진 것만 들어 있다 */
    public record Comparison(Side base, Side target, Map<String, Integer> totals, Map<String, Map<String, Integer>> byType, List<Change> changes) {
    }

    /** ERD 의 노드 하나 : node = 테이블·엔터티, items = 컬럼·속성(달라지지 않은 것 포함), extras = 달라진 인덱스·제약조건 / 엔터티의 식별자 전부 */
    public record ErdNode(Change node, List<Change> items, List<Change> extras) {
    }

    /**
     * 비교 결과를 ERD 로 그릴 범위 (M-10 ③) : kind = PHYSICAL · LOGICAL. 모델 전체가 아니라 달라진 노드와 그 이웃만 담는다.
     * changedNodeCount = 달라졌거나 달라진 관계가 걸린 노드 수, truncated = 그중 일부가 범위(limit)에 들지 못함,
     * edges = 범위 안의 노드에 한쪽 끝이라도 걸린 관계 (상대 노드가 범위 밖일 수 있다)
     */
    public record ErdComparison(Side base, Side target, String kind, int changedNodeCount, boolean truncated, List<ErdNode> nodes, List<Change> edges) {
    }
}
