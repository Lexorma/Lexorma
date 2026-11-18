package com.lexorma.data.dictionary.quality;

import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.NameToken;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.TableRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.TokenStatus;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.WordAbbreviation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 테이블명 표준 규칙 (D1). 테이블 등록이 쓰는 생성 규칙({@code TableDesignService.generateTableName})의 역방향이다 :
 * 생성 규칙 = 프로젝트 접두어 + 승인된 표준 단어 약어(대문자)를 '_' 로 연결.
 * <ul>
 * <li>{@link StandardizationRule#TABLE_PREFIX_MISSING} : 접두어가 설정된 프로젝트에서 테이블명이 접두어로 시작하지 않는다 (대소문자 무시).
 *     접두어는 저장할 때 끝에 '_' 를 붙여 두므로({@code Project.normalizeTablePrefix}) 접두어 경계는 '_' 다 — TB_ 접두어에 TBL_X, TBX 는 위반</li>
 * <li>{@link StandardizationRule#TABLE_WORD_NOT_STANDARD} : 접두어를 뗀 나머지(접두어가 없으면 테이블명 전체)를 '_' 로 나눈 약어 중
 *     승인된 표준 단어 약어(대소문자 무시)가 아닌 것이 있다. 같은 약어의 단어가 여럿이면 하나라도 승인되어 있으면 승인으로 본다
 *     (생성 규칙의 "승인된 단어 우선"과 같다). 표준 단어 약어에는 '_' 가 없다({@code StandardWordService.validateNoUnderscore})</li>
 * </ul>
 * 대상은 TB_TBL 의 테이블뿐이다 (VIEW 제외 — D9). 테이블 코멘트와 생성 이름 비교, DBMS 식별자·예약어 검사는 하지 않는다 (D1·D3·D4).
 */
public final class TableNameRules {

    private TableNameRules() {
    }

    /** 판정 결과 1건 : tokens 는 TABLE_WORD_NOT_STANDARD 일 때 약어별 판정 */
    public record TableIssue(TableRow table, Finding finding, List<NameToken> tokens) {
    }

    /** 저장된 접두어를 비교용으로 맞춘다 (대문자, 끝 '_'). 없으면 null — 저장 값이 정규화되지 않은 예전 데이터여도 같은 경계로 본다 */
    public static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        String normalized = prefix.trim().toUpperCase(Locale.ROOT);
        return normalized.endsWith("_") ? normalized : normalized + "_";
    }

    /** 대문자 약어 → 판정 (승인된 단어가 하나라도 있으면 APPROVED) */
    public static Map<String, TokenStatus> wordStatuses(List<WordAbbreviation> words) {
        Map<String, TokenStatus> statuses = new HashMap<>();
        for (WordAbbreviation word : words) {
            if (word.abbreviation() == null || word.abbreviation().isBlank()) {
                continue;
            }
            TokenStatus status = Boolean.TRUE.equals(word.isApproval()) ? TokenStatus.APPROVED : TokenStatus.NOT_APPROVED;
            statuses.merge(StandardNames.physicalNameKey(word.abbreviation()), status,
                    (a, b) -> a == TokenStatus.APPROVED || b == TokenStatus.APPROVED ? TokenStatus.APPROVED : TokenStatus.NOT_APPROVED);
        }
        return statuses;
    }

    /** 테이블마다 규칙별로 최대 1건 (접두어 → 약어 순). rows 순서를 유지한다 */
    public static List<TableIssue> diagnose(long projectId, String tablePrefix, List<TableRow> rows, Map<String, TokenStatus> wordStatuses) {
        String prefix = normalizePrefix(tablePrefix);
        List<TableIssue> issues = new ArrayList<>();
        for (TableRow row : rows) {
            String name = row.tableName() == null ? "" : row.tableName().trim();
            String upper = StandardNames.physicalNameKey(name);
            String rest = upper;
            if (prefix != null) {
                if (upper.startsWith(prefix)) {
                    rest = upper.substring(prefix.length());
                } else {
                    Map<String, String> evidence = new LinkedHashMap<>();
                    evidence.put("prefix", prefix);
                    issues.add(new TableIssue(row, new Finding(StandardizationRule.TABLE_PREFIX_MISSING, List.of(row.tableId()), name, prefix, evidence,
                            FindingFingerprint.of(projectId, StandardizationRule.TABLE_PREFIX_MISSING, List.of(row.tableId()), List.of(prefix))), List.of()));
                }
            }

            List<NameToken> tokens = Arrays.stream(rest.split("_", -1))
                    .map(token -> new NameToken(token, token.isEmpty() ? TokenStatus.EMPTY : wordStatuses.getOrDefault(token, TokenStatus.NOT_FOUND)))
                    .toList();
            List<NameToken> invalid = tokens.stream().filter(token -> token.status() != TokenStatus.APPROVED).toList();
            if (!invalid.isEmpty()) {
                Map<String, String> evidence = new LinkedHashMap<>();
                evidence.put("prefix", prefix == null ? "" : prefix);
                evidence.put("tokens", String.join(",", invalid.stream().map(token -> token.token() + ":" + token.status()).toList()));
                // 핵심 값 : 위반 약어와 판정 (정렬). 다른 약어를 고쳐도 같은 문제는 같은 fingerprint
                List<String> keys = invalid.stream().map(token -> token.token() + ":" + token.status()).sorted().distinct().toList();
                String actual = String.join(", ", invalid.stream().map(token -> token.token().isEmpty() ? "''" : token.token()).distinct().toList());
                issues.add(new TableIssue(row, new Finding(StandardizationRule.TABLE_WORD_NOT_STANDARD, List.of(row.tableId()), actual, null, evidence,
                        FindingFingerprint.of(projectId, StandardizationRule.TABLE_WORD_NOT_STANDARD, List.of(row.tableId()), keys)), tokens));
            }
        }
        return issues;
    }
}
