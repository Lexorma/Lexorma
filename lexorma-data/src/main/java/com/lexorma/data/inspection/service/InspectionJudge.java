package com.lexorma.data.inspection.service;

import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.service.IdentifierQualityService;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.dictionary.service.TermQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 저장 검사의 판정 (AI-6 2차) : M-5 의 16규칙을 <b>그대로</b> 다시 쓴다 — 화면 진단(조회 전용)과 같은 서비스·같은 계산이다 (AI6-D03).
 * 규칙 묶음(모델 5 · 식별자 5 · 용어 6)마다 하나라도 적용하면 그 묶음을 판정하고, 적용하지 않은 규칙의 결과는 버린다.
 * 호출한 트랜잭션(검사 스냅숏, REPEATABLE READ) 안에서 읽는다.
 */
@Component
@RequiredArgsConstructor
public class InspectionJudge {

    private final StandardizationQualityService modelQuality;
    private final IdentifierQualityService identifierQuality;
    private final TermQualityService termQuality;

    public enum Group { MODEL, IDENTIFIER, TERM }

    public static Group group(StandardizationRule rule) {
        return switch (rule.targetType()) {
            case COLUMN, TABLE -> Group.MODEL;
            case IDENTIFIER -> Group.IDENTIFIER;
            case TERM, WORD -> Group.TERM;
        };
    }

    /** 규칙 묶음 하나의 판정 (적용 규칙만 남긴다) */
    public List<InspectionItem> judge(long projectId, Group group, Set<StandardizationRule> rules) {
        List<InspectionItem> items = new ArrayList<>();
        judge(projectId, group, rules, items::add);
        return items;
    }

    /**
     * 같은 판정, 적용 규칙의 결과를 하나씩 넘긴다 (AI-6 FINAL 메모리 개선) : 모델 묶음(결과가 가장 많다)은 진단 서비스가 결과를 만드는 대로 넘겨
     * 판정 결과 전체를 목록으로 들고 있지 않는다. 식별자·용어 묶음은 결과가 적어 목록판을 그대로 쓴다.
     */
    public void judge(long projectId, Group group, Set<StandardizationRule> rules, Consumer<InspectionItem> sink) {
        Consumer<InspectionItem> active = item -> {
            if (rules.contains(item.finding().rule())) sink.accept(item);
        };
        switch (group) {
            case MODEL -> modelQuality.inspect(projectId, active);
            case IDENTIFIER -> identifierQuality.inspect(projectId).forEach(active);
            case TERM -> termQuality.inspect(projectId).forEach(active);
        }
    }
}
