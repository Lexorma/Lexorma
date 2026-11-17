package com.lexorma.ai.budget;

/**
 * 토큰 수 추정 (AI-4 토큰 예산). 모델의 토크나이저를 서버가 알 수 없어 <b>많게 잡는</b> 규칙을 쓴다 : ASCII 3자당 1토큰(올림), 그 밖의 글자(한글 등)는 1자당 1토큰.
 * 실제 토큰 수가 아니다 — 보내기 전 판정에만 쓰고, 화면·응답에는 "추정" 으로만 낸다.
 */
public final class TokenEstimator {

    /** 메시지 하나의 형식 비용 (역할·구분자) */
    static final int PER_MESSAGE = 8;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        long ascii = 0;
        long other = 0;
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            if (code < 128) ascii++;
            else other++;
            i += Character.charCount(code);
        }
        return (int) Math.min(Integer.MAX_VALUE, (ascii + 2) / 3 + other);
    }
}
