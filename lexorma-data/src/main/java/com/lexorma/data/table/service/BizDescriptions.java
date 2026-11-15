package com.lexorma.data.table.service;

import java.util.Objects;

/**
 * 업무 설명(TASK_EXPLN, AI-5) 직접 수정 규칙 : 테이블 수정·컬럼 일괄 편집이 함께 쓴다.
 * <ul>
 *   <li>요청 값이 null 이면 바꾸지 않는다 (업무 설명을 모르는 화면·엑셀이 지우지 않도록). 빈 값이면 지운다</li>
 *   <li>화면이 읽은 값(base)을 함께 보내면, 그 사이 다른 사용자(또는 AI 설명 제안 승인)가 바꾼 값을 덮어쓰지 않는다 — 지금 값과 같게 바꾸는 것은 충돌이 아니다</li>
 * </ul>
 */
public final class BizDescriptions {

    private BizDescriptions() {
    }

    /** 앞뒤 공백을 빼고, 비면 null */
    public static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 화면이 읽은 뒤 업무 설명이 바뀌었고, 요청은 그와 다른 값을 쓰려 한다 */
    public static boolean conflict(String current, String requested, String base) {
        if (requested == null || base == null) return false;
        String now = normalize(current);
        return !Objects.equals(normalize(base), now) && !Objects.equals(normalize(requested), now);
    }
}
