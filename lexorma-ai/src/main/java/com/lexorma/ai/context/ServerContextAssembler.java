package com.lexorma.ai.context;

import com.lexorma.ai.tool.MetadataToolSession;
import com.lexorma.ai.tool.MetadataToolset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool Calling 을 지원하지 않는 모델의 대체 흐름 (AI-4) : 서버가 질문을 규칙으로 해석해 <b>필요한 최소한의</b> 메타데이터를 조회한다. 모델은 조회 대상을 고르지 않는다.
 * <ul>
 *   <li>해석 : 질문의 낱말에서 의도(컬럼·관계·이력·유사 컬럼·표준 용어/단어/도메인·스키마)와 검색어(물리명·나머지 낱말)를 뽑는다. 조사·안내 낱말은 뺀다.
 *       SQL·프로젝트 ID 같은 것은 해석하지 않는다 — 검색어는 기존 검색 조건으로만 쓰인다</li>
 *   <li>조회 : {@link MetadataToolset#run} 으로 허용 목록의 Tool 을 서버가 실행한다 (모델이 부를 때와 같은 재검증·읽기 전용·크기 제한·기록·근거 스냅숏).
 *       질의당 {@value #MAX_LOOKUPS}번까지. 테이블은 검색 결과의 첫 테이블(물리명이 정확히 같은 것이 있으면 그것) 하나만 더 깊이 본다</li>
 *   <li>해석할 수 없으면(검색어 없음) 아무것도 조회하지 않는다 — 대신 많은 메타데이터를 보내지 않는다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ServerContextAssembler {

    static final int MAX_LOOKUPS = 5;
    static final int KEYWORD_MAX = 100;

    public enum Intent { COLUMNS, RELATIONS, HISTORY, SIMILAR, TERM, WORD, DOMAIN, SCHEMA }

    /**
     * 질문 해석 결과
     *
     * @param intents     의도 (없으면 테이블 찾기)
     * @param keyword     검색어 (남은 낱말을 공백으로 이은 것. 없으면 null)
     * @param identifier  물리명처럼 보이는 낱말 (MBR_SN, TB_MEMBER. 없으면 null)
     */
    public record Analysis(Set<Intent> intents, String keyword, String identifier) {
        public boolean understood() {
            return keyword != null || intents.contains(Intent.SCHEMA);
        }
    }

    /** 서버가 한 조회 하나 (모델에게 무엇을 조회했는지 알린다) */
    public record Lookup(String tool, Map<String, Object> input) {
    }

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
    /** 낱말 끝의 조사 (긴 것부터) */
    private static final List<String> PARTICLES = List.of("으로는", "에서는", "이랑", "에서", "에게", "으로", "하고", "과의", "와의", "이란", "이라는", "라는",
            "의", "은", "는", "이", "가", "을", "를", "에", "와", "과", "로", "도", "만", "랑");
    private static final Map<String, Intent> INTENT_WORDS = new LinkedHashMap<>();
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>();

    static {
        for (String w : List.of("컬럼", "칼럼", "필드", "속성", "column", "columns", "field", "fields")) INTENT_WORDS.put(w, Intent.COLUMNS);
        for (String w : List.of("관계", "참조", "외래키", "fk", "relation", "relations", "relationship", "relationships", "reference", "references", "foreign")) INTENT_WORDS.put(w, Intent.RELATIONS);
        for (String w : List.of("이력", "변경이력", "변경", "history", "changes", "changed", "change")) INTENT_WORDS.put(w, Intent.HISTORY);
        for (String w : List.of("유사", "유사한", "비슷한", "비슷", "similar")) INTENT_WORDS.put(w, Intent.SIMILAR);
        for (String w : List.of("용어", "표준용어", "term", "terms")) INTENT_WORDS.put(w, Intent.TERM);
        for (String w : List.of("단어", "표준단어", "word", "words")) INTENT_WORDS.put(w, Intent.WORD);
        for (String w : List.of("도메인", "표준도메인", "domain", "domains")) INTENT_WORDS.put(w, Intent.DOMAIN);
        for (String w : List.of("스키마", "schema", "schemas")) INTENT_WORDS.put(w, Intent.SCHEMA);
        STOP_WORDS.addAll(List.of("테이블", "표준", "목록", "정보", "알려줘", "알려주세요", "알려", "보여줘", "보여주세요", "찾아줘", "찾아", "뭐야", "뭐", "무엇", "어떤",
                "있어", "있나요", "있는지", "있니", "다른", "전체", "모든", "해줘", "언제", "무슨", "어디", "대해", "대한", "최근", "구조", "그리고", "어떻게", "줘",
                "the", "a", "an", "of", "for", "which", "what", "does", "do", "is", "are", "have", "has", "table", "tables", "show", "list", "tell", "me",
                "about", "to", "in", "with", "and", "recent", "when", "find", "other", "please", "there", "any", "how", "its", "it", "this", "that"));
    }

    private final MetadataToolset toolset;

    /** 질문을 규칙으로 해석한다 */
    public static Analysis analyze(String question) {
        Set<Intent> intents = EnumSet.noneOf(Intent.class);
        List<String> rest = new ArrayList<>();
        String identifier = null;
        Matcher words = WORD.matcher(question == null ? "" : question);
        while (words.find()) {
            String word = words.group();
            if (identifier == null && IDENTIFIER.matcher(word).matches()) identifier = word.toUpperCase(Locale.ROOT);
            String bare = strip(word);
            String lower = bare.toLowerCase(Locale.ROOT);
            Intent intent = INTENT_WORDS.get(lower);
            if (intent == null) intent = suffixIntent(lower);
            if (intent != null) {
                intents.add(intent);
                continue;
            }
            if (STOP_WORDS.contains(lower) || PARTICLES.contains(bare) || bare.isEmpty()) continue;
            rest.add(bare);
        }
        String keyword = rest.isEmpty() ? null : String.join(" ", rest);
        if (keyword != null && keyword.length() > KEYWORD_MAX) keyword = keyword.substring(0, KEYWORD_MAX).strip();
        return new Analysis(intents, keyword, identifier);
    }

    /** "회원테이블" 처럼 붙여 쓴 의도 낱말 (끝이 의도 낱말) */
    private static Intent suffixIntent(String word) {
        for (Map.Entry<String, Intent> entry : INTENT_WORDS.entrySet()) {
            if (entry.getKey().length() >= 2 && word.length() > entry.getKey().length() && word.endsWith(entry.getKey()) && !word.matches("[a-z0-9_]+")) return entry.getValue();
        }
        return null;
    }

    /** 낱말 끝의 조사를 뗀다 (한글 낱말만, 남는 것이 2자 이상일 때 — "길이" 같은 낱말은 그대로) */
    private static String strip(String word) {
        if (!word.matches(".*[가-힣]$")) return word;
        for (String particle : PARTICLES) {
            if (word.length() >= particle.length() + 2 && word.endsWith(particle)) return word.substring(0, word.length() - particle.length());
        }
        return word;
    }

    /**
     * 해석한 질문으로 조회한다. 결과는 세션의 Tool 결과·근거 후보에 남는다 (근거 출처 = 서버 맥락).
     *
     * @return 서버가 한 조회 (순서대로)
     */
    public List<Lookup> assemble(Analysis analysis, MetadataToolSession session) {
        List<Lookup> lookups = new ArrayList<>();
        if (!analysis.understood()) return lookups;
        // "비슷한 컬럼" 의 컬럼은 유사 컬럼 찾기 그 자체다 (테이블 컬럼 목록을 따로 보지 않는다)
        boolean similar = analysis.intents().contains(Intent.SIMILAR);
        Set<Intent> intents = EnumSet.noneOf(Intent.class);
        analysis.intents().stream().filter(i -> !(similar && i == Intent.COLUMNS)).forEach(intents::add);
        String keyword = analysis.keyword();
        if (intents.contains(Intent.SCHEMA)) run(lookups, session, "listSchemas", Map.of());
        if (keyword == null) return lookups;
        if (intents.contains(Intent.SIMILAR)) {
            run(lookups, session, "findSimilarColumns", Map.of("keyword", analysis.identifier() != null ? analysis.identifier() : keyword));
        }
        if (intents.contains(Intent.TERM)) run(lookups, session, "searchStandardTerms", Map.of("keyword", keyword));
        if (intents.contains(Intent.WORD)) run(lookups, session, "searchStandardWords", Map.of("keyword", keyword));
        if (intents.contains(Intent.DOMAIN)) run(lookups, session, "searchStandardDomains", Map.of("keyword", keyword));
        boolean dictionaryOnly = !intents.isEmpty() && EnumSet.of(Intent.SIMILAR, Intent.TERM, Intent.WORD, Intent.DOMAIN, Intent.SCHEMA).containsAll(intents);
        if (dictionaryOnly) return lookups;

        // 테이블 : 찾고, 의도에 따라 그 테이블 하나를 더 본다
        String found = run(lookups, session, "searchTables", Map.of("keyword", analysis.identifier() != null ? analysis.identifier() : keyword));
        Long tableId = firstTable(found, analysis.identifier());
        if (tableId == null) return lookups;
        if (intents.contains(Intent.COLUMNS)) run(lookups, session, "getTableColumns", Map.of("tableId", tableId));
        if (intents.contains(Intent.RELATIONS)) run(lookups, session, "getTableRelations", Map.of("tableId", tableId));
        if (intents.contains(Intent.HISTORY)) run(lookups, session, "getMetadataHistory", Map.of("targetType", "TABLE", "targetId", tableId));
        return lookups;
    }

    private String run(List<Lookup> lookups, MetadataToolSession session, String tool, Map<String, Object> input) {
        if (lookups.size() >= MAX_LOOKUPS) return null;
        lookups.add(new Lookup(tool, input));
        return toolset.run(tool, input, session);
    }

    /** 검색 결과에서 더 볼 테이블 : 물리명이 정확히 같은 것, 없으면 첫 번째 */
    private static Long firstTable(String result, String identifier) {
        if (result == null) return null;
        JsonNode tables = JsonMapper.shared().readTree(result).get("tables");
        if (tables == null || !tables.isArray() || tables.isEmpty()) return null;
        for (JsonNode table : tables) {
            if (identifier != null && identifier.equalsIgnoreCase(table.path("tableName").asString(""))) return table.get("tableId").asLong();
        }
        return tables.get(0).path("tableId").isNumber() ? tables.get(0).get("tableId").asLong() : null;
    }
}
