package com.lexorma.core.logs.util;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시스템 로그에 저장되는 요청/응답 본문·오류 메시지에서 비밀값을 마스킹한다.
 * <ul>
 *   <li><b>key 이름</b> : 대소문자와 '_'·'-'·'.' 표기 차이를 없앤 뒤 비교한다 (apiKey = api_key = API-KEY).
 *       password·passwd·pwd·secret·credential·apiKey·accessKey·secretKey·privateKey·connectionString·authorization 은 이름에 들어 있으면,
 *       token 은 이름이 token 으로 끝날 때만(accessToken, csrf_token) 가린다 — tokenCount 같은 일반 필드는 그대로 둔다</li>
 *   <li><b>값 안의 비밀</b> : key 이름과 상관없이 문자열 값에 들어 있는 URI 인증 정보(scheme://user:pass@host), JDBC URL 의 계정·파라미터
 *       (jdbc:oracle:thin:user/pass@…, ?password=…, ;password=…), Bearer·Basic 인증 값을 가린다</li>
 *   <li><b>실패하면 남기지 않는다</b> : 처리 중 오류가 나면 원문 대신 {@link #FAILED} 를 돌려준다</li>
 * </ul>
 * 경로 단위로 본문을 아예 남기지 않는 API 는 {@link com.lexorma.core.logs.policy.LogBodyPolicy} 가 정한다. 여기는 그 밖의 일반 API 에 적용된다.
 */
public final class SensitiveDataMasker {

    public static final String MASK = "****";
    /** 마스킹에 실패한 본문 대신 남기는 값 (원문을 남기지 않는다) */
    public static final String FAILED = "[MASKING_FAILED]";

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    /** 이름에 들어 있으면 비밀값인 말 (정규화한 key 기준) */
    private static final List<String> CONTAINS = List.of(
            "passw", "pwd", "secret", "credential", "apikey", "accesskey", "privatekey", "connectionstring", "authorization", "passphrase");
    /** 이름이 이 말로 끝날 때만 비밀값인 말. 복수형(tokens)은 넣지 않는다 : maxTokens·promptTokens 는 AI 사용량(개수)이다 */
    private static final List<String> ENDS_WITH = List.of("token");
    /**
     * 비밀값의 이름을 닮았지만 값 자체가 아닌 것 : 개수·길이·종류·설정 여부·정책·만료.
     * (passwordCheck 처럼 비밀번호를 한 번 더 받는 필드가 있을 수 있어 check 는 넣지 않는다. 참·거짓 값은 이름과 상관없이 가리지 않는다)
     */
    private static final List<String> BENIGN_SUFFIX = List.of(
            "count", "length", "size", "type", "configured", "enabled", "required", "policy", "expiresat", "expiresin");

    /** scheme://user:pass@host → 비밀번호 자리 (jdbc:postgresql://user:pass@host 포함) */
    private static final Pattern URI_USERINFO = Pattern.compile("([A-Za-z][A-Za-z0-9+.\\-]*://[^/\\s:@\"']+):([^@\\s/\"']+)@");
    /** jdbc:oracle:thin:user/pass@host */
    private static final Pattern ORACLE_USERINFO = Pattern.compile("(?i)(jdbc:oracle:[a-z]+:[^/@\\s\"']+)/([^@\\s\"']+)@");
    /** URL·JDBC URL 의 파라미터 : ?key=value, &key=value, ;key=value */
    private static final Pattern URL_PARAM = Pattern.compile("([?&;])([A-Za-z0-9_.\\-]+)=([^&;\\s\"']*)");
    /** Authorization 헤더 값 */
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=\\-]{4,}");
    /** JSON 으로 읽지 못한 본문의 "key":"value" / "key":값 */
    private static final Pattern JSON_PAIR = Pattern.compile("\"([^\"\\\\]{1,100})\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"?|[^,}\\]\\s]+)");
    private static final Pattern FORM_BODY = Pattern.compile("^[^=&\\s{}\\[\\]\"]+=[^&\\s]*(?:&[^=&\\s]*(?:=[^&\\s]*)?)*$");

    private static final Set<String> LITERALS = Set.of("null", "true", "false");

    private SensitiveDataMasker() {
    }

    /** 요청·응답 본문 (JSON / form-urlencoded / 그 밖의 글) */
    public static String mask(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            String trimmed = body.trim();

            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    JsonNode root = OBJECT_MAPPER.readTree(trimmed);
                    return OBJECT_MAPPER.writeValueAsString(maskJson(root));
                } catch (Exception e) {
                    // JSON 으로 읽지 못했다 (잘린 본문 등) : 글로 보고 "key":"value" 모양과 값 안의 비밀을 가린다
                    return maskText(maskJsonLikeText(body));
                }
            }

            if (FORM_BODY.matcher(trimmed).matches()) {
                return maskForm(trimmed);
            }
            return maskText(maskJsonLikeText(body));
        } catch (Exception | StackOverflowError e) {
            return FAILED;
        }
    }

    /** 글(오류 메시지, 문자열 값) 안의 비밀 : URI·JDBC URL 의 인증 정보와 파라미터, Bearer·Basic 값 */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            String result = URI_USERINFO.matcher(text).replaceAll("$1:" + MASK + "@");
            result = ORACLE_USERINFO.matcher(result).replaceAll("$1/" + MASK + "@");
            result = AUTH_SCHEME.matcher(result).replaceAll("$1 " + MASK);
            Matcher matcher = URL_PARAM.matcher(result);
            StringBuilder masked = new StringBuilder();
            while (matcher.find()) {
                String replacement = isSensitiveKey(matcher.group(2)) && !matcher.group(3).isEmpty()
                        ? matcher.group(1) + matcher.group(2) + "=" + MASK : matcher.group();
                matcher.appendReplacement(masked, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(masked);
            return masked.toString();
        } catch (Exception | StackOverflowError e) {
            return FAILED;
        }
    }

    /** 비밀값을 담는 key 이름인지 (대소문자, '_'·'-'·'.' 무시) */
    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String name = key.toLowerCase(Locale.ROOT).replaceAll("[_\\-.\\s]", "");
        if (name.isEmpty()) return false;
        if (BENIGN_SUFFIX.stream().anyMatch(name::endsWith)) return false;
        return CONTAINS.stream().anyMatch(name::contains) || ENDS_WITH.stream().anyMatch(name::endsWith);
    }

    private static JsonNode maskJson(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>(objectNode.propertyNames());

            for (String fieldName : fieldNames) {
                JsonNode child = objectNode.get(fieldName);

                if (isSensitiveKey(fieldName)) {
                    // 값이 객체·배열이어도 통째로 가린다 (credentials: {...}). null 과 참·거짓(설정 여부)은 비밀값이 아니다
                    if (!child.isNull() && !child.isBoolean()) objectNode.put(fieldName, MASK);
                } else {
                    objectNode.set(fieldName, maskJson(child));
                }
            }
            return objectNode;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) array.set(i, maskJson(array.get(i)));
            return array;
        }
        if (node.isString()) {
            String value = node.asString();
            String masked = maskText(value);
            return masked.equals(value) ? node : OBJECT_MAPPER.getNodeFactory().stringNode(masked);
        }
        return node;
    }

    /** JSON 으로 읽지 못한 글에서 "비밀 key": 값 을 가린다 */
    private static String maskJsonLikeText(String text) {
        Matcher matcher = JSON_PAIR.matcher(text);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group(2);
            String replacement = isSensitiveKey(matcher.group(1)) && !LITERALS.contains(value)
                    ? "\"" + matcher.group(1) + "\":\"" + MASK + "\"" : matcher.group();
            matcher.appendReplacement(masked, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    /** key=value&key=value : 비밀 key 는 값을 가리고, 그 밖의 값은 안에 든 비밀(URL 의 인증 정보 등)만 가린다 */
    private static String maskForm(String body) {
        StringBuilder result = new StringBuilder();
        for (String pair : body.split("&", -1)) {
            if (!result.isEmpty()) result.append('&');
            int index = pair.indexOf('=');
            if (index < 0) {
                result.append(pair);
                continue;
            }
            String rawKey = pair.substring(0, index);
            String rawValue = pair.substring(index + 1);
            result.append(rawKey).append('=');
            if (isSensitiveKey(decode(rawKey))) {
                result.append(MASK);
            } else {
                String value = decode(rawValue);
                String masked = maskText(value);
                result.append(masked.equals(value) ? rawValue : URLEncoder.encode(masked, StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value; // 잘못된 % 표기 : 원문으로 판단한다
        }
    }
}
