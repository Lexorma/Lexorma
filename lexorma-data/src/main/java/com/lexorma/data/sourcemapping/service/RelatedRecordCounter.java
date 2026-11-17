package com.lexorma.data.sourcemapping.service;

import java.util.Collection;
import java.util.Map;

/**
 * 원본 삭제 반영(DEC-04)의 영향 표시에 더할, 다른 모듈이 모델 TABLE·컬럼을 가리키는 기록 수. 지우지는 않고 수만 보인다.
 * 이 모듈은 그 모듈을 참조하지 않는다 — 구현은 다른 모듈에 있다 (지금은 lexorma-ai 의 AI 설명 제안, 8차 모듈 분리). 구현이 없으면 0 이다.
 */
public interface RelatedRecordCounter {

    /** @param byTable TABLE ID → 그 TABLE(컬럼 포함)을 가리키는 기록 수, @param byColumn 컬럼 ID → 그 컬럼을 가리키는 기록 수 */
    record Counts(Map<Long, Integer> byTable, Map<Long, Integer> byColumn) {
    }

    /** 지금 트랜잭션에서 센다 (프로젝트 범위) */
    Counts count(Long projectId, Collection<Long> tableIds);
}
