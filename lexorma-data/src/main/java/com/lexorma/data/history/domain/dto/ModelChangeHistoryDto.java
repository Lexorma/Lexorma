package com.lexorma.data.history.domain.dto;

import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/** 모델 변경 이력 조회 응답 */
public final class ModelChangeHistoryDto {

    private ModelChangeHistoryDto() {
    }

    /** 목록용 요약 : 변경 전후 JSON 대신 달라진 필드 이름만 준다 */
    public record Summary(Long id, Long projectId, String changeGroupId, String targetType, Long targetId, String targetName,
                          String action, String source, String userId, LocalDateTime changedAt, List<String> changedFields) {

        public static Summary of(ModelChangeHistory history, List<String> changedFields) {
            return new Summary(history.getId(), history.getProjectId(), history.getChangeGroupId(), history.getTargetType().name(), history.getTargetId(),
                    history.getTargetName(), history.getAction().name(), history.getSource().name(), history.getUserId(), history.getChangedAt(), changedFields);
        }
    }

    /** 상세 : 저장된 JSON 을 그대로 준다 (지금은 없는 필드가 들어 있어도 그대로 보인다) */
    public record Detail(Summary summary, JsonNode before, JsonNode after) {
    }
}
