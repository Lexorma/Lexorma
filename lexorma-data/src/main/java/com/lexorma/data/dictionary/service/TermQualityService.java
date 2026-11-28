package com.lexorma.data.dictionary.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermRow;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.TermDuplicateRules;
import com.lexorma.data.dictionary.quality.TermDuplicateRules.TermGroup;
import com.lexorma.data.dictionary.repository.DictionaryUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 표준 용어 중복·유사어 진단 (M-5 잔여, 조회 전용). 판정은 {@link TermDuplicateRules}, 이 서비스는 로그인 프로젝트의 사전을 읽어 문구·사용 컬럼 수를 붙인다.
 * 용어·단어를 병합·삭제·이름 변경하지 않고, 등록·수정 검증도 바꾸지 않는다 (D5). 대시보드는 이 진단을 부르지 않는다 (D2).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TermQualityService {

    private final DictionaryUsageRepository usageRepository;

    // ------------------------------------------------------------------ 진단

    /** SQL 6번 (용어, 용어-단어, 단어, 용어 이음동의어, 단어 이음동의어, 금칙어). terms 는 ID → 용어 */
    private record Diagnosis(Map<Long, TermRow> terms, List<TermGroup> groups) {
    }

    private Diagnosis diagnose(Long projectId) {
        List<TermRow> terms = usageRepository.findQualityTerms(projectId);
        if (terms.isEmpty()) {
            return new Diagnosis(Map.of(), List.of());
        }
        Map<Long, TermRow> byId = new HashMap<>();
        terms.forEach(term -> byId.put(term.termId(), term));
        List<TermGroup> groups = TermDuplicateRules.diagnose(projectId, terms, usageRepository.findQualityTermWords(projectId),
                usageRepository.findTermSynonyms(projectId), usageRepository.findQualityWords(projectId),
                usageRepository.findWordSynonyms(projectId), usageRepository.findRestrictedWords(projectId));
        return new Diagnosis(byId, groups);
    }

    /**
     * 저장 검사(AI-6) : 용어 6규칙의 판정 결과를 그대로 넘긴다 — 화면 진단과 같은 계산({@link #diagnose})이다. 대표 대상은 가장 작은 구성원,
     * 구성원 목록(ID·용어명)은 근거에 둔다. 호출한 트랜잭션(검사 스냅숏) 안에서 읽는다.
     */
    public List<InspectionItem> inspect(long projectId) {
        Diagnosis diagnosis = diagnose(projectId);
        List<InspectionItem> items = new ArrayList<>(diagnosis.groups().size());
        for (TermGroup group : diagnosis.groups()) {
            List<Map<String, Object>> members = new ArrayList<>(group.termIds().size());
            for (Long termId : group.termIds()) {
                TermRow term = diagnosis.terms().get(termId);
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("termId", termId);
                member.put("termName", term == null ? null : term.termName());
                member.put("abbreviation", term == null ? null : term.abbreviation());
                if (group.notes().containsKey(termId)) member.put("note", group.notes().get(termId));
                members.add(member);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("groupKey", group.groupKey());
            details.put("members", members);
            items.add(new InspectionItem(group.finding(), InspectionItem.ObjectType.TERM, group.termIds().get(0), null, group.groupKey(), details));
        }
        return items;
    }

    // ------------------------------------------------------------------ 표시

    private static String note(StandardizationRule rule, boolean wordSynonym, String raw) {
        return switch (rule) {
            case TERM_SYNONYM -> wordSynonym ? Messages.get("바꾼 단어 : {0}", raw) : Messages.get("이음동의어 : {0}", raw);
            case TERM_RESTRICTED_WORD -> Messages.get("금칙어 → 표준 단어 : {0}", raw);
            default -> raw;
        };
    }

    private static String message(StandardizationRule rule, boolean wordSynonym, int size, Map<String, String> evidence) {
        String count = String.valueOf(size);
        return switch (rule) {
            case TERM_ABBR_DUPLICATE -> Messages.get("영문약어와 도메인이 같은 표준 용어가 {0}개 있습니다.", count);
            case TERM_NAME_DUPLICATE -> Messages.get("용어명이 같은 표준 용어가 {0}개 있습니다 (공백·'_'·대소문자 무시).", count)
                    + (Objects.equals(evidence.get("sameDomain"), "true") ? "" : " " + Messages.get("도메인이 서로 다릅니다."));
            case TERM_ABBR_SHARED -> Messages.get("영문약어가 같고 도메인이 다른 표준 용어가 {0}개 있습니다. 컬럼명 생성·엑셀 업로드에서 용어를 구분할 수 없습니다.", count);
            case TERM_SAME_WORDS -> Messages.get("같은 표준 단어로 이루어진 용어가 {0}개 있습니다.", count)
                    + (Objects.equals(evidence.get("orderDiffers"), "true") ? " " + Messages.get("단어 순서가 다릅니다.") : "");
            case TERM_SYNONYM -> wordSynonym
                    ? Messages.get("표준 단어의 이음동의어를 대표 단어로 바꾸면 단어 구성이 같습니다 : {0}", evidence.get("replaced"))
                    : Messages.get("용어명이 다른 표준 용어의 이음동의어({0})와 같습니다.", evidence.get("synonym"));
            case TERM_RESTRICTED_WORD -> Messages.get("다른 표준 단어의 금칙어가 쓰였습니다 : {0}", evidence.get("matches"));
            default -> "";
        };
    }
}
