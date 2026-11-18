package com.lexorma.data.dictionary.quality;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 표준 이름 판정의 공통 함수 (M-5 4차). 품질 진단과 등록·생성·추천이 <b>판정 기준이 완전히 같은</b> 부분만 모았다 —
 * 값을 비교·정규화할 뿐, 거절(오류 코드)·진단(Finding)·건너뛰기는 호출하는 쪽이 각자의 기존 정책대로 정한다.
 * <ul>
 * <li>컬럼명 규칙 : 기대 컬럼명 = 연결된 표준 용어의 영문약어(앞뒤 공백 제거), 일치 = 앞뒤 공백 제거 + 대소문자 무시.
 *     컬럼명 불일치 진단·컬럼명 개선 후보·물리명 추천·논리↔물리 정합성 비교가 같은 함수를 쓴다</li>
 * <li>이름 비교 키 : 용어·단어 이름은 공백·'_' 를 빼고 소문자 ({@link #termNameKey}), 영문약어·물리명은 앞뒤 공백 제거 + 대문자 ({@link #physicalNameKey})</li>
 * <li>단어 나누기 : 한글 이름을 '_'·공백으로 나눈 조각 ({@link #splitWords}) — 테이블명 생성과 금칙어 진단의 단어 경계</li>
 * </ul>
 * 등록 경로의 중복 검사(용어 = 약어 대소문자 무시 + 도메인 ID — M-5 5차 {@code StandardTermService.duplicateKey}, 단어 = 단어명 정확 일치, 도메인 = 대소문자 무시)는
 * 정책이 서로 달라 여기에 두지 않는다 ({@code docs/completed/standardization-quality-rules-m5.md} 10·11장). 새 정규화(유니코드 등)를 이 클래스에서 도입하지 않는다.
 */
public final class StandardNames {

    private StandardNames() {
    }

    /** 표준 용어 영문약어 → 기대 컬럼명 (앞뒤 공백 제거). 약어가 없거나 비었으면 null — 기대 컬럼명을 알 수 없다 */
    public static String expectedColumnName(String termAbbreviation) {
        String name = termAbbreviation == null ? "" : termAbbreviation.trim();
        return name.isEmpty() ? null : name;
    }

    /** 컬럼명이 기대 컬럼명과 같은가 (앞뒤 공백 제거, 대소문자 무시). 기대 컬럼명이 없으면 false */
    public static boolean sameColumnName(String current, String expected) {
        return expected != null && expected.trim().equalsIgnoreCase(current == null ? "" : current.trim());
    }

    /** 공백·'_' 를 뺀 이름 (대소문자 유지). null 은 "" */
    public static String compact(String name) {
        return name == null ? "" : name.replaceAll("[_\\s]", "");
    }

    /** 용어·단어 이름 비교 키 : 공백·'_' 제거 + 소문자. null 은 "" */
    public static String termNameKey(String name) {
        return compact(name).toLowerCase(Locale.ROOT);
    }

    /** 영문약어·물리명 비교 키 : 앞뒤 공백 제거 + 대문자. null 은 "" */
    public static String physicalNameKey(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }

    /** 한글 이름을 '_'·공백으로 나눈 단어 ("사용_내역 관리" → 사용, 내역, 관리). 빈 조각은 빼고, null 은 빈 목록 */
    public static List<String> splitWords(String name) {
        return Arrays.stream((name == null ? "" : name).trim().split("[_\\s]+"))
                .filter(word -> !word.isBlank())
                .toList();
    }
}
