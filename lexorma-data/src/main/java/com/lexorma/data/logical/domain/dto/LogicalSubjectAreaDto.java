package com.lexorma.data.logical.domain.dto;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 논리 모델 주제 영역 (M-6 후속 4차) 조회·저장 DTO. 저장 요청은 id 가 없으면 등록, 있으면 수정(이름·설명·순서·상위 영역 이동)이다 */
public final class LogicalSubjectAreaDto {

    private LogicalSubjectAreaDto() {
    }

    /**
     * 영역 1건 (조회 전용 값 포함). depth : 최상위 = 1, path : 최상위부터 자기까지의 이름 (DB 에 저장하지 않고 조회할 때 만든다).
     * directEntityCount : 이 영역에 직접 속한 엔터티, totalEntityCount : 하위 영역 포함, childCount : 바로 아래 영역 수.
     * modifiedAt : 응답은 지금 수정 일시, 수정·삭제 요청은 화면이 읽은 값 (DEC-08 기준값)
     */
    public record Area(Long id, Long modelId, Long parentId, String areaName, String areaDescription, Integer sortOrder, int depth, List<String> path,
                       long directEntityCount, long totalEntityCount, int childCount, LocalDateTime modifiedAt) {
    }

    /**
     * 모델의 영역 목록 (계층 순서 : 상위 → 하위, 같은 상위 안에서는 순서·이름·ID 순). entityCount : 모델 전체 엔터티, unclassifiedCount : 어느 영역에도 속하지 않은 엔터티.
     * 영역별 수를 더해도 모델 전체 수가 아니다 (상위 영역의 하위 포함 수는 하위 영역의 수를 이미 포함한다)
     */
    public record Tree(Long modelId, long entityCount, long unclassifiedCount, List<Area> areas) {
    }

    /** 등록·수정 요청. parentId 가 null 이면 최상위. sortOrder 가 null 이면 등록은 같은 상위의 맨 뒤, 수정은 그대로 (다른 상위로 옮기면 맨 뒤) */
    public record Save(Long id, Long modelId, Long parentId, String areaName, String areaDescription, Integer sortOrder, LocalDateTime modifiedAt) {
    }

    /**
     * 일괄 분류 요청 (D-20·D-21). subjectAreaId : 옮길 영역 (명시적 null = 미분류로 해제), entities : 엔터티마다 화면이 본 지금 소속.
     * 두 값 모두 JSON 에 반드시 있어야 한다 — 빠진 값(필드 없음)과 명시적 null(미분류)을 구분해, 빠진 요청을 덮어쓰기로 처리하지 않는다
     */
    public record Assignment(JsonNode subjectAreaId, List<AssignmentItem> entities) {
    }

    /** expectedSubjectAreaId : 화면이 본 이 엔터티의 소속 (명시적 null = 미분류) */
    public record AssignmentItem(Long entityId, JsonNode expectedSubjectAreaId) {
    }

    /** 일괄 분류 결과 : 바뀐 엔터티 수, 이미 그 영역이라 바꾸지 않은 수 */
    public record AssignmentResult(int changed, int unchanged) {
    }

    /**
     * 주제 영역별 내 논리 ERD 배치 (D-07, D-26). source : SUBJECT_AREA = 이 영역에 저장한 배치, MODEL = 저장한 적 없어 모델 전체 배치의 좌표를 참고로 준 것
     * (그때 version·viewport 는 null — 저장하면 이 영역의 배치가 새로 생긴다, 모델 전체 배치는 바뀌지 않는다), NONE = 둘 다 없음
     */
    public record Layout(Long version, Map<Long, LogicalModelDto.Point> nodes, LogicalModelDto.Viewport viewport, String source) {
    }

    /**
     * 엔터티·관계 조회 범위 (기존 목록·ERD API 의 선택 조건). null 이면 모델 전체 (예전과 같다).
     * unclassified : 미분류만, 아니면 areaIds (선택 영역 하나, 하위 포함이면 그 아래 영역까지) 중 하나에 속한 엔터티
     */
    public record Scope(boolean unclassified, Collection<Long> areaIds) {
        public static Scope unclassifiedOnly() {
            return new Scope(true, Set.of());
        }
    }
}
