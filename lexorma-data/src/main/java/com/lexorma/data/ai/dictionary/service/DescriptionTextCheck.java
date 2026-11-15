package com.lexorma.data.ai.dictionary.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 설명 문구 검사 (AI-5 프롬프트 v2) : 사람이 읽는 문구(설명·이름 해석·불확실한 부분·확인 질문)에 AI 의 자기 평가와 Lexorma 내부 ID 가 들어갔는지 본다.
 * 문구를 고치거나 잘라내지 않는다 — 걸리면 사유를 붙여 다시 요청하고, 그래도 걸리면 제안을 만들지 않는다.
 * <ul>
 *   <li>자기 평가 : "신뢰도: 확정됨" 처럼 라벨(신뢰도·확신도·확실성·confidence) + 값, "(확인됨)" 같은 괄호 꼬리표, "AI 가 검증한", "100% 확실", "정확성이 보장" 등 <b>명백한 형태만</b>.
 *       "확정"·"신뢰"·"검증" 같은 낱말만으로는 걸지 않는다. 같은 표현이 서버가 준 메타데이터(확정 업무 정보 등)에 있으면 걸지 않는다</li>
 *   <li>내부 ID : ID 를 뜻하는 말(id·tableId·아이디·식별자 등) 바로 뒤의 숫자가 <b>서버가 모델에 준 메타데이터의 ID</b> 일 때만.
 *       "주문 ID" 처럼 숫자 없는 업무 표현, 메타데이터에 없는 숫자, 근거·추천·연관 객체 필드의 ID 는 걸지 않는다</li>
 * </ul>
 */
final class DescriptionTextCheck {

    private static final JsonMapper JSON = JsonMapper.shared();

    enum Issue { SELF_ASSESSMENT, INTERNAL_ID }

    /** 걸린 것 하나 : 종류, 필드, 걸린 부분 (모델에 사유로 돌려준다. 로그에는 남기지 않는다) */
    record Finding(Issue issue, String field, String fragment) {
    }

    private static final List<Pattern> SELF_ASSESSMENT = List.of(
            // 라벨 + 값 : 신뢰도: 확정됨 · 확실성 = 높음 · 정확도 95% · confidence: high
            // ("정확도"·"accuracy" 는 업무 속성 이름(위치 정확도 등)일 수 있어 라벨로 보지 않는다)
            Pattern.compile("(신뢰\\s*도|신뢰\\s*수준|확신\\s*도|확실성)\\s*[:：=]\\s*[^\\s,.)）]+"),
            Pattern.compile("(신뢰\\s*도|확신\\s*도)\\s*\\d{1,3}\\s*%"),
            Pattern.compile("(?i)\\b(confidence|certainty)(\\s+(level|score))?\\s*[:=]\\s*\\S+"),
            // 괄호 꼬리표 : (확인됨) · (메타데이터에서 확인됨) · (검증 완료) · (verified)
            Pattern.compile("[(（][^()（）]{0,30}?(확인됨|확정됨|검증됨|검증\\s*완료|확인\\s*완료)\\s*[)）]"),
            Pattern.compile("(?i)[(（]\\s*(ai[- ]?)?(verified|confirmed)\\s*[)）]"),
            // AI 가 검증·확인·보증했다는 주장
            Pattern.compile("(?i)\\bAI\\s*(가|이|에\\s*의해|에서|가\\s*직접)?\\s*(검증|확인|보증)\\s*(한|된|했|함|하였|됨)"),
            Pattern.compile("(?i)\\b(verified|confirmed|guaranteed)\\s+by\\s+(the\\s+)?AI\\b"),
            Pattern.compile("(?i)\\bAI[- ](verified|confirmed)\\b"),
            // 보장·100%
            Pattern.compile("정확(성|함|도)?\\s*(이|을|가)?\\s*보장"),
            Pattern.compile("(?i)\\b(accuracy|correctness)\\s+(is\\s+)?guaranteed\\b|\\bguaranteed\\s+to\\s+be\\s+(accurate|correct)\\b"),
            Pattern.compile("(?i)100\\s*%\\s*(확실|정확|신뢰|틀림없|certain|sure|accurate|correct)"));

    /** ID 를 뜻하는 말 바로 뒤의 숫자 : ID 7001 · (ID 4) · Term ID 6401 · termId=12 · 아이디 3 · 식별자: 15 */
    private static final Pattern ID_MENTION = Pattern.compile(
            "(?i)(?:\\b(?:schema|table|column|relation|term|word|domain|history|provider)?\\s*id\\b|아이디|식별자)\\s*[:=#]?\\s*(\\d{1,18})");

    private DescriptionTextCheck() {
    }

    /**
     * @param contextIds  서버가 모델에 준 메타데이터의 ID (요청 본문의 *Id·id 값)
     * @param contextText 서버가 모델에 준 메타데이터 본문 (같은 표현이 있으면 자기 평가로 보지 않는다)
     */
    static List<Finding> check(DescriptionDraft draft, Set<Long> contextIds, String contextText) {
        List<Finding> findings = new ArrayList<>();
        String context = normalize(contextText);
        scan(findings, "description", draft.description(), contextIds, context);
        scan(findings, "nameInterpretation", draft.nameInterpretation(), contextIds, context);
        if (draft.unknowns() != null) draft.unknowns().forEach(u -> scan(findings, "unknowns", u, contextIds, context));
        if (draft.questions() != null) draft.questions().forEach(q -> scan(findings, "questions", q, contextIds, context));
        return findings;
    }

    private static void scan(List<Finding> findings, String field, String text, Set<Long> contextIds, String context) {
        if (text == null || text.isBlank()) return;
        for (Pattern pattern : SELF_ASSESSMENT) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                if (!context.isEmpty() && context.contains(normalize(m.group()))) continue;
                findings.add(new Finding(Issue.SELF_ASSESSMENT, field, m.group().strip()));
            }
        }
        Matcher m = ID_MENTION.matcher(text);
        while (m.find()) {
            try {
                if (contextIds.contains(Long.parseLong(m.group(1)))) findings.add(new Finding(Issue.INTERNAL_ID, field, m.group().strip()));
            } catch (NumberFormatException ignored) {
                // 18자리를 넘는 숫자는 메타데이터 ID 가 아니다
            }
        }
    }

    /** 다시 요청할 때 모델에 줄 사유 (걸린 부분을 그대로 보여 준다) */
    static String reason(Finding f) {
        return switch (f.issue()) {
            case SELF_ASSESSMENT -> "The " + f.field() + " assesses your own answer (\"" + f.fragment() + "\"). Remove such confidence or verification "
                    + "claims from the text; certainty belongs only in the \"certainty\" field.";
            case INTERNAL_ID -> "The " + f.field() + " shows a Lexorma internal id (\"" + f.fragment() + "\"). Refer to objects by name in the text; "
                    + "ids belong only in evidence, related and the recommendations.";
        };
    }

    /** 요청 본문의 메타데이터에 나온 ID : 필드 이름이 id 이거나 Id 로 끝나는 정수 값 */
    static Set<Long> ids(List<String> toolResults) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String result : toolResults) {
            try {
                collect(JSON.readTree(result), null, ids);
            } catch (RuntimeException ignored) {
                // 도구 오류 문구 등 JSON 이 아니면 건너뛴다
            }
        }
        return ids;
    }

    private static void collect(JsonNode node, String field, Set<Long> ids) {
        if (node == null) return;
        if (node.isObject()) {
            node.properties().forEach(e -> collect(e.getValue(), e.getKey(), ids));
        } else if (node.isArray()) {
            for (JsonNode child : node) collect(child, field, ids);
        } else if (node.isIntegralNumber() && field != null && (field.equals("id") || field.endsWith("Id"))) {
            ids.add(node.asLong());
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
