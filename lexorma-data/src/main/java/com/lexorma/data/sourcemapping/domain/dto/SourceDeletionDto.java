package com.lexorma.data.sourcemapping.domain.dto;

import java.util.List;

/**
 * 원본 삭제 후보 선택 반영 (DEC-04, docs/completed/source-sync-dec04-05-07.md). 흐름 : 후보(candidates) → 계획(plan, 읽기 전용) → 확정(apply, 잠금 뒤 다시 계획해 같을 때만).
 * 항목 ID : {@code TABLE:<모델 TABLE ID>} (원본에서 사라진 TABLE 매핑의 모델 TABLE — 그 컬럼·인덱스·제약조건·나가는 관계까지), {@code COLUMN:<모델 컬럼 ID>}
 * (매핑된 TABLE 에서 모델에만 있는 컬럼). 이름 변경 후보(DEC-06)는 삭제 후보가 아니다. 원본 DB 는 바꾸지 않는다.
 */
public final class SourceDeletionDto {

    private SourceDeletionDto() {
    }

    public enum Kind { TABLE, COLUMN }

    /**
     * 지울 수 없는 이유. APPROVED : 승인된 객체 (승인 취소 후) / APPROVED_COLUMN : 승인된 컬럼이 있는 TABLE / LOGICAL_MAPPED : 논리 엔터티·속성에 매핑됨 /
     * VIEW_REFERENCE : VIEW 가 참조 / REFERENCED : 다른 TABLE 의 관계가 이 TABLE 을 참조 / IN_USE : 관계·인덱스·제약조건이 이 컬럼을 쓴다 /
     * NOT_FOUND : 지금 후보에 없다 (그 사이 원본·모델·매핑이 바뀌었다)
     */
    public enum Blocker { APPROVED, APPROVED_COLUMN, LOGICAL_MAPPED, VIEW_REFERENCE, REFERENCED, IN_USE, NOT_FOUND }

    /**
     * 지우면 함께 사라지는 것 (계획 미리보기의 영향 분석).
     *
     * @param relations       TABLE 에서 나가는 관계 (자기 참조 포함) / 컬럼은 그 컬럼에서 자동으로 만든 참조 관계
     * @param bizDescriptions 업무 설명이 있는 객체 수 (TABLE·컬럼)
     * @param termLinks       표준 용어에 연결된 컬럼 수
     * @param aiProposals     그 TABLE·컬럼의 AI 설명 제안 수 (지우지 않는다 — 다음 재검증에서 재검토 필요가 된다)
     */
    public record Impact(int columns, int indexes, int constraints, int relations, int bizDescriptions, int termLinks, int aiProposals) {
    }

    /**
     * @param sourceName 사라진 원본 객체 이름 (TABLE : 확정 때의 비교 이름, 컬럼 : 없음 — 원본에 대응 컬럼이 없다)
     * @param details    지울 수 없는 이유의 대상 이름 (관계·VIEW·엔터티 이름 등)
     */
    public record Candidate(String id, Kind kind, Long tableMappingId, Long tableId, String schemaName, String tableName, Long columnId, String columnName,
                            String sourceName, boolean selectable, List<Blocker> blockers, List<String> details, Impact impact) {
    }

    /**
     * @param dropSuspected   기준 수집이 직전 정상 수집보다 객체가 50% 미만으로 줄었다 (DEC-17) : 확정에 acknowledgeDrop=true 가 필요하다
     * @param renameCandidates 삭제 후보에서 뺀 이름 변경 후보 수 (TABLE·컬럼, DEC-06 에서 확인한다)
     */
    public record Candidates(Long connectionId, Integer reflectedOrder, boolean dropSuspected, int renameCandidates, List<Candidate> items) {
    }

    public record Request(Long connectionId, Integer reflectedOrder, List<String> items, Boolean acknowledgeDrop, String planToken, String requestId) {
    }

    public record Plan(Long connectionId, Integer reflectedOrder, String planToken, String requestId, boolean applicable, boolean dropSuspected, List<Candidate> items) {
    }

    public record Result(Long connectionId, Integer reflectedOrder, String changeGroupId, Long modelVersionId, String modelVersionName, int tablesDeleted, int columnsDeleted) {
    }
}
