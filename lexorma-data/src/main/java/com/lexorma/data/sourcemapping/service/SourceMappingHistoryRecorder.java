package com.lexorma.data.sourcemapping.service;

import com.lexorma.data.sourcemapping.domain.entity.SourceColumnMapping;
import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory;
import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory.ChangeType;
import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory.TargetType;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.sourcemapping.repository.SourceMappingHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 원본 매핑 변경 이력 쓰기 (DEC-06). 매핑을 바꾸는 쪽의 트랜잭션 안에서 부른다 (매핑 변경과 이력이 함께 커밋·롤백). 변경 전후는 그 순간의 값을 JSON 으로 남긴다
 * (모델 객체의 이름은 부르는 쪽이 넘긴다 — 나중에 이름이 바뀌어도 그때의 이름이 남는다).
 */
@Component
@RequiredArgsConstructor
public class SourceMappingHistoryRecorder {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SourceMappingHistoryRepository repository;

    /** TABLE 매핑의 그 순간 값 (원본 TABLE·모델 TABLE). 매핑이 없으면 null */
    public static Map<String, Object> table(SourceTableMapping mapping, String sourceTableName, String modelTableName) {
        if (mapping == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceTableId", mapping.getSourceTableId());
        value.put("sourceTableName", sourceTableName == null ? mapping.getCompareName() : sourceTableName);
        value.put("creationCycle", mapping.getCreationCycle());
        value.put("reflectedOrder", mapping.getReflectedOrder());
        value.put("tableId", mapping.getTableId());
        value.put("tableName", modelTableName);
        return value;
    }

    /** 컬럼 매핑의 그 순간 값 */
    public static Map<String, Object> column(SourceColumnMapping mapping, String sourceTableName, String modelTableName, String modelColumnName) {
        if (mapping == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceTableName", sourceTableName);
        value.put("sourceColumnName", mapping.getSourceColumnName());
        value.put("compareName", mapping.getCompareName());
        value.put("reflectedOrder", mapping.getReflectedOrder());
        value.put("tableName", modelTableName);
        value.put("columnId", mapping.getColumnId());
        value.put("columnName", modelColumnName);
        return value;
    }

    public void record(Long projectId, Long connectionId, Long tableMappingId, TargetType targetType, ChangeType changeType, Map<String, Object> before,
                       Map<String, Object> after) {
        repository.save(SourceMappingHistory.builder().projectId(projectId).connectionId(connectionId).tableMappingId(tableMappingId).targetType(targetType)
                .changeType(changeType).before(json(before)).after(json(after)).changedAt(LocalDateTime.now()).build());
    }

    private static String json(Map<String, Object> value) {
        return value == null ? null : JSON.writeValueAsString(value);
    }
}
