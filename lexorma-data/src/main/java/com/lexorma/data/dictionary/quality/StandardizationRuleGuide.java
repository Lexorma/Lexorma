package com.lexorma.data.dictionary.quality;

import com.lexorma.common.i18n.Messages;

import java.util.EnumMap;
import java.util.Map;

/**
 * 품질 진단 규칙 20개의 서버 안내 (AI-7 AI7-D11). 판정 조건·심각도는 {@link StandardizationRule} 과 각 판정 클래스가 정하고, 여기는 설명 문구만 둔다.
 * <ul>
 *   <li>이름·설명 = 화면 규칙 카탈로그({@code lexorma-web/src/features/quality/model.ts} RULES)의 한국어 문구와 <b>같은 문자열</b>
 *       ({@code StandardizationRuleGuideTest} 가 대조한다 — 한쪽만 고치지 않는다)</li>
 *   <li>개선 안내(fixHint) = 일반적인 조치 방향 (서버에만 있다). 결과마다의 판정이 아니라 규칙의 기본 안내다</li>
 *   <li>언어 = 기존 구조 그대로 : 한국어 원문 + {@code EnglishMessages} 영어, {@link Messages#get} 이 요청 언어(Accept-Language)를 따른다</li>
 * </ul>
 */
public final class StandardizationRuleGuide {

    /** 원문(한국어) 이름 · 설명 · 개선 안내 */
    public record Text(String name, String description, String fixHint) {
    }

    /** 요청 언어로 바꾼 안내 */
    public record Guide(StandardizationRule rule, String name, String description, String fixHint) {
    }

    private static final Map<StandardizationRule, Text> TEXTS = new EnumMap<>(StandardizationRule.class);

    static {
        put(StandardizationRule.TERM_NOT_LINKED, "표준 용어 미연결", "컬럼이 표준 용어를 참조하지 않습니다.",
                "컬럼의 뜻에 맞는 표준 용어를 찾아 연결하고, 맞는 용어가 없으면 새 용어 등록을 검토합니다.");
        put(StandardizationRule.COLUMN_NAME_MISMATCH, "컬럼명 불일치", "컬럼명이 연결된 표준 용어의 영문 약어와 다릅니다.",
                "컬럼명을 연결된 표준 용어의 영문 약어로 바꾸거나, 컬럼의 뜻에 맞는 다른 표준 용어를 연결합니다.");
        put(StandardizationRule.DOMAIN_DEFINITION_MISMATCH, "도메인 정의 불일치", "컬럼의 타입·길이·정밀도가 표준 도메인 정의와 다릅니다.",
                "컬럼의 타입·길이·정밀도를 표준 도메인 정의에 맞추거나, 용어의 도메인이 맞는지 확인합니다.");
        put(StandardizationRule.TABLE_PREFIX_MISSING, "테이블 접두어 누락", "테이블명이 프로젝트 접두어로 시작하지 않습니다.",
                "테이블명을 프로젝트 접두어로 시작하도록 바꿉니다. 접두어 설정이 맞는지도 확인합니다.");
        put(StandardizationRule.TABLE_WORD_NOT_STANDARD, "비표준 단어 약어", "테이블명에 승인된 표준 단어 약어가 아닌 약어가 있습니다.",
                "테이블명의 약어를 승인된 표준 단어 약어로 바꾸거나, 필요한 표준 단어의 등록·승인을 검토합니다.");
        put(StandardizationRule.TABLE_COMMENT_MISSING, "테이블 코멘트 누락", "테이블 코멘트가 비어 있습니다.",
                "테이블 코멘트를 입력합니다 (DDL 의 테이블 COMMENT 가 됩니다).");
        put(StandardizationRule.COLUMN_COMMENT_MISSING, "컬럼 코멘트 누락", "컬럼 코멘트(컬럼 설명)가 비어 있습니다.",
                "컬럼 설명을 입력합니다 (DDL 의 컬럼 COMMENT 가 됩니다).");
        put(StandardizationRule.TABLE_BIZ_DESCRIPTION_MISSING, "테이블 업무 설명 누락", "테이블 업무 설명이 비어 있습니다.",
                "테이블 업무 설명을 직접 입력하거나 AI 설명 제안을 검토해 승인합니다.");
        put(StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING, "컬럼 업무 설명 누락", "컬럼 업무 설명이 비어 있습니다.",
                "컬럼 업무 설명을 직접 입력하거나 AI 설명 제안을 검토해 승인합니다.");
        put(StandardizationRule.TERM_ABBR_DUPLICATE, "약어·도메인 중복", "영문 약어와 도메인이 같은 표준 용어가 여럿 있습니다.",
                "같은 뜻이면 하나로 정리하고, 다른 뜻이면 약어나 도메인을 구분합니다. 자동으로 합치지 않습니다.");
        put(StandardizationRule.TERM_NAME_DUPLICATE, "용어명 중복", "공백·_·대소문자를 무시한 용어명이 같은 표준 용어가 여럿 있습니다.",
                "같은 용어가 중복 등록되었는지 확인해 정리하거나, 뜻이 다르면 용어명을 구분합니다.");
        put(StandardizationRule.TERM_ABBR_SHARED, "같은 약어 다른 도메인", "영문 약어는 같고 도메인이 다른 표준 용어가 있습니다.",
                "컬럼명·엑셀 업로드에서 용어가 모호해질 수 있습니다. 약어를 구분할지, 도메인이 맞는지 검토합니다.");
        put(StandardizationRule.TERM_SAME_WORDS, "같은 단어 구성", "구성 표준 단어가 같은 표준 용어가 여럿 있습니다(순서 무시).",
                "같은 뜻의 용어가 순서만 다르게 등록되었는지 확인합니다. 의도한 구분이면 그대로 두어도 됩니다.");
        put(StandardizationRule.TERM_SYNONYM, "이음동의어 유사", "이음동의어로 같은 뜻이 되는 표준 용어가 있습니다.",
                "이음동의어 관계를 확인해 대표 용어로 정리할지 검토합니다.");
        put(StandardizationRule.TERM_RESTRICTED_WORD, "금칙어 사용", "다른 표준 단어의 금칙어를 쓰는 표준 용어입니다.",
                "금칙어 대신 그 금칙어를 가진 표준 단어를 쓰도록 용어를 고칩니다.");
        put(StandardizationRule.IDENTIFIER_LENGTH_EXCEEDED, "식별자 길이 초과", "이름이 프로젝트 DBMS 의 식별자 길이 기준을 넘습니다.",
                "이름을 프로젝트 DBMS 의 길이 기준 안으로 줄입니다.");
        put(StandardizationRule.IDENTIFIER_INVALID_CHARACTER, "DBMS 허용 문자 위반", "프로젝트 DBMS 가 따옴표 없는 식별자로 받지 않는 문자가 있습니다.",
                "따옴표 없이 쓸 수 있는 문자만 쓰도록 이름을 바꿉니다.");
        put(StandardizationRule.IDENTIFIER_NOT_RECOMMENDED, "권장 형식 아님", "DBMS 는 받지만 Lexorma 권장 형식과 다른 이름입니다.",
                "Lexorma 권장 형식(영문 대문자·숫자·_)에 맞추는 것을 검토합니다.");
        put(StandardizationRule.CONSTRAINT_NAME_TRUNCATED, "제약조건명 잘림", "DDL 이 만드는 제약조건명이 길이 기준을 넘어 잘립니다.",
                "테이블·컬럼 이름을 줄이거나 제약조건 이름을 직접 정해 잘리지 않게 합니다.");
        put(StandardizationRule.CONSTRAINT_NAME_COLLISION, "제약조건명 충돌", "DDL 의 최종 제약조건·인덱스 이름이 같은 이름 공간에서 겹칩니다.",
                "겹치는 제약조건·인덱스 중 하나의 이름을 바꿉니다.");
    }

    private StandardizationRuleGuide() {
    }

    /** 원문(한국어)만 담는다 — 정적 초기화가 어느 요청에서 돌지 모르므로 여기서 번역하지 않는다. 번역은 {@link #guide} 에서 (영어 누락은 StandardizationRuleGuideTest 가 본다) */
    private static void put(StandardizationRule rule, String name, String description, String fixHint) {
        TEXTS.put(rule, new Text(name, description, fixHint));
    }

    /** 규칙의 원문(한국어) 안내. 모든 규칙에 있다 */
    public static Text text(StandardizationRule rule) {
        Text text = TEXTS.get(rule);
        if (text == null) throw new IllegalStateException("규칙 안내 없음 : " + rule);
        return text;
    }

    /** 지금 요청 언어의 안내 */
    public static Guide guide(StandardizationRule rule) {
        Text text = text(rule);
        return new Guide(rule, Messages.get(text.name()), Messages.get(text.description()), Messages.get(text.fixHint()));
    }
}
