package com.lexorma.data.table.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;

import java.util.Objects;

/**
 * 업무 설명(TASK_EXPLN, AI-5) 직접 수정 규칙 : 테이블 수정·컬럼 일괄 편집이 함께 쓴다.
 * <ul>
 *   <li>요청 값이 null 이면 바꾸지 않는다 (업무 설명을 모르는 화면·엑셀이 지우지 않도록). 빈 값이면 지운다</li>
 *   <li>화면이 읽은 값(base)을 함께 보내면, 그 사이 다른 사용자(또는 AI 설명 제안 승인)가 바꾼 값을 덮어쓰지 않는다 — 지금 값과 같게 바꾸는 것은 충돌이 아니다</li>
 *   <li>길이는 {@link #MAX} 자까지 (AI-1d) : 앞뒤 공백을 뺀 값의 {@code String.length()} — UTF-16 단위라 화면 입력칸의 maxLength 와 같은 기준이다
 *       (이모지 등 보조 평면 문자는 2, 줄바꿈은 1). 모든 저장 경로(직접 편집·테이블/컬럼 등록·수정·일괄 편집·엑셀 업로드·AI 제안 승인)가 이 값을 쓴다</li>
 * </ul>
 */
public final class BizDescriptions {

    public static final int MAX = 4000;

    private BizDescriptions() {
    }

    /** 앞뒤 공백을 빼고, 비면 null */
    public static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 저장하려는 값이 길이 상한을 넘는다. 지금 값과 같으면(예전에 들어간 긴 값을 그대로 두는 요청) 보지 않는다 — 업무 설명을 바꾸지 않는 저장이 막히지 않도록.
     * 자르지 않는다.
     */
    public static boolean tooLong(String requested, String current) {
        String next = normalize(requested);
        return next != null && next.length() > MAX && !Objects.equals(next, normalize(current));
    }

    /** {@link #tooLong} 이면 저장 전에 거부한다 : 전용 오류 BIZ_DESCRIPTION_TOO_LONG (다른 INVALID_PHYSICAL_MODEL 문구는 그대로) */
    public static void requireLength(String requested, String current) {
        if (tooLong(requested, current)) throw new CustomException(ErrorCode.BIZ_DESCRIPTION_TOO_LONG);
    }

    /** 길이 초과를 대상(테이블명, 테이블.컬럼명)과 함께 알린다 — 엑셀 업로드 (전체 롤백) */
    public static CustomException tooLong(String target) {
        return new CustomException(ErrorCode.BIZ_DESCRIPTION_TOO_LONG, "{0}", target);
    }

    /** 일괄 편집의 행 오류 문구 : "컬럼명 : 업무 설명은 최대 4,000자까지 …" (요청 언어) */
    public static String tooLongRowMessage(String columnName) {
        return columnName + " : " + ErrorCode.BIZ_DESCRIPTION_TOO_LONG.getMessage();
    }

    /** 화면이 읽은 뒤 업무 설명이 바뀌었고, 요청은 그와 다른 값을 쓰려 한다 */
    public static boolean conflict(String current, String requested, String base) {
        if (requested == null || base == null) return false;
        String now = normalize(current);
        return !Objects.equals(normalize(base), now) && !Objects.equals(normalize(requested), now);
    }
}
