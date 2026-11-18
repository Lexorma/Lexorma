package com.lexorma.data.dictionary.domain.dto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 표준 사전의 ',' 구분 목록(이음동의어·금칙어) 입력 → 저장 목록 ([DICT-1]).
 * 없거나(null) 비어 있거나 공백뿐이면 빈 목록, 그 밖에는 기존 규칙 그대로 ',' 로만 나눈다 (항목 앞뒤 공백·중복은 손대지 않는다).
 */
public final class DictionaryLists {

    private DictionaryLists() {
    }

    public static List<String> split(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(value.split(",")));
    }
}
