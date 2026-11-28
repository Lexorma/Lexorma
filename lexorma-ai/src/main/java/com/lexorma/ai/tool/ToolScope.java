package com.lexorma.ai.tool;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * AI 메타데이터 질의의 기능 범위 (AI-7 AI7-D04·D24·D26). 범위마다 모델에게 넘기는 Tool 목록·시스템 지시·답변 형식을 서버가 정하고
 * ({@link MetadataToolset#callbacks(ToolScope)}), 클라이언트는 범위 이름만 고른다 — Tool 이름·목록을 직접 정할 수 없다.
 * 범위는 권한을 넓히지 않는다 (Tool 마다 같은 VIEWER 재확인). 선택 상태는 저장하지 않는다 (AI7-D27).
 */
public enum ToolScope {
    /** 기존 메타데이터 조회 (기본값) : 기존 Tool 13개, 기존 시스템 지시 그대로 */
    METADATA,
    /** 저장된 품질 진단 결과 조회 : 품질 Tool 3개 + 원인 설명에 필요한 기존 조회 Tool 6개, 규칙 9 */
    QUALITY;

    /**
     * 요청 본문의 범위 값 : 이름과 정확히 같은 문자열만 받는다 (AI-7 3차 결함 수정). Jackson 기본 동작은 숫자를 순번으로 받아 {@code "scope":1} 을 QUALITY 로
     * 묶었다 — 숫자·true 처럼 문자열로 바뀌는 값, 대소문자가 다른 값, 빈 문자열은 모두 거절한다 (400). JSON null·생략은 Jackson 이 이 함수를 부르지 않고 null(= METADATA)로 둔다
     */
    @JsonCreator
    public static ToolScope of(String value) {
        for (ToolScope scope : values()) {
            if (scope.name().equals(value)) return scope;
        }
        throw new IllegalArgumentException("알 수 없는 기능 범위");
    }
}
