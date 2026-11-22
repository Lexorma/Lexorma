package com.lexorma.data.mapping.domain.dto;

import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Applicability;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 물리명 추천 일괄 적용 (M-7 물리명 추천 일괄 적용 2차). 미리보기 {@code POST /api/modelMapping/recommendation/rename/preview} →
 * 적용 {@code POST /api/modelMapping/recommendation/rename} (planToken). 대상은 물리 테이블·컬럼 ID 로 보내고, 새 이름은 서버가 지금 추천 규칙으로 다시 계산한다
 * (요청의 recommendedName 은 "화면이 본 추천명" — 서버 계산과 다르면 {@code RECOMMENDATION_CHANGED} 로 막는다. 요청 값으로 이름을 바꾸지 않는다).
 */
public final class PhysicalNameRenameDto {

    private PhysicalNameRenameDto() {
    }

    /** 한 번에 고를 수 있는 대상 수 (테이블 + 컬럼 합, P-07) */
    public static final int MAX_ITEMS = 500;

    public enum TargetType { TABLE, COLUMN }

    /**
     * 대상 1개. id = 물리 테이블 ID(TABLE) 또는 물리 컬럼 ID(COLUMN), recommendedName = 화면이 본 추천명 (필수),
     * baseModifiedAt = 미리보기가 돌려준 대상 행의 수정 시각 (DEC-08 기준값 — 적용에서 필수, 미리보기에서는 보내지 않아도 된다)
     */
    public record Item(TargetType type, Long id, String recommendedName, LocalDateTime baseModifiedAt) {
    }

    /** planToken : 미리보기는 비워 보내고, 적용은 미리보기가 준 값을 보낸다 */
    public record Request(List<Item> items, String planToken) {
    }

    /**
     * 대상 1개의 판정. currentName = 지금 물리명, recommendedName = 서버가 지금 계산한 추천명 (없으면 null), requestedName = 요청이 보낸 추천명,
     * modifiedAt = 대상 행의 지금 수정 시각 (적용 요청의 baseModifiedAt 으로 보낸다), noChange = 지금 이름 = 추천명,
     * applicability = 적용 가능 여부·차단 사유·경고 (추천끼리 충돌은 이 요청의 선택 집합 기준 — P-12)
     */
    public record PlanItem(TargetType type, Long id, Long schemaId, String schemaName, Long tableId, String tableName,
                           Long logicalId, String logicalName, String currentName, String recommendedName, String requestedName,
                           LocalDateTime modifiedAt, boolean noChange, Applicability applicability) {
    }

    /**
     * 미리보기 결과. applicable = 모든 대상을 적용할 수 있다 (차단 사유가 하나라도 있으면 적용 요청은 거절된다 — 막힌 대상을 빼고 다시 미리보기),
     * planToken = 이 선택 집합·지금 상태의 계획 해시 (적용 때 잠근 뒤 다시 계산해 다르면 409)
     */
    public record Plan(List<PlanItem> items, int total, int tables, int columns, int selectable, int blocked, int warned,
                       boolean applicable, String dbType, String planToken) {
    }

    /** 경고가 있었던 대상 (적용 결과 — 경고는 적용을 막지 않는다) */
    public record Warning(TargetType type, Long id, String name, List<ApplyIssue> warnings) {
    }

    /** 적용 결과. versionId·versionName = 적용 직전 상태를 남긴 모델 버전 (RENAME-요청 ID, P-06 — 복원 기능은 없다, 비교용) */
    public record Result(int tables, int columns, int total, Long versionId, String versionName, List<Warning> warnings) {
    }
}
