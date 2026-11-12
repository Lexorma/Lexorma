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

    /** 달라진 객체 하나. change = ADDED(before 없음) · REMOVED(after 없음) · MODIFIED. changedFields 는 MODIFIED 에서 값이 다른 필드 */
    public record Change(String targetType, Long targetId, String name, String change, List<String> changedFields, JsonNode before, JsonNode after) {
    }

    /** totals·byType : ADDED·REMOVED·MODIFIED·UNCHANGED 건수. changes 에는 달라진 것만 들어 있다 */
    public record Comparison(Side base, Side target, Map<String, Integer> totals, Map<String, Map<String, Integer>> byType, List<Change> changes) {
    }
}
