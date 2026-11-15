package com.lexorma.data.ai.dictionary.service;

import com.lexorma.data.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposalHistory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI 설명 제안 → API 응답 */
final class ProposalMapper {

    private static final JsonMapper JSON = JsonMapper.shared();

    private ProposalMapper() {
    }

    static AiDictionaryDto.ProposalSummary summary(AiDescriptionProposal p) {
        int total = 0;
        int verified = 0;
        JsonNode evidence = node(p.getEvidence());
        if (evidence != null) {
            for (JsonNode e : evidence) {
                total++;
                if ("VERIFIED".equals(e.path("status").asString(""))) verified++;
            }
        }
        return new AiDictionaryDto.ProposalSummary(p.getId(), p.getTargetType(), p.getTableId(), p.getColumnId(), p.getTargetName(), p.getCycle(), p.getStatus(),
                p.getCertainty(), p.getProposedDescription(), p.getConfirmedDescription(), p.getModel(), p.getPromptVersion(), p.getCreationMethod(), p.getJobId(),
                p.audit() == null ? null : p.audit().created(), p.getReviewerId(), p.getReviewedAt(), p.getReviewReason(), Boolean.TRUE.equals(p.getRecheckNeeded()),
                p.getVersion() == null ? 0 : p.getVersion(), total, verified, replaces(p));
    }

    /** 생성 때 업무 설명이 있었고 제안 문구와 다르다 (승인하면 교체) */
    private static boolean replaces(AiDescriptionProposal p) {
        String base = baseBizDescription(p);
        return base != null && !base.equals(p.getProposedDescription() == null ? null : p.getProposedDescription().strip());
    }

    /** 생성 때(AI 를 부르기 전) 대상의 업무 설명 (없으면 null) */
    static String baseBizDescription(AiDescriptionProposal p) {
        if (p.getBaseVersions() == null) return null;
        String base = ProposalVersions.read(p.getBaseVersions()).bizDescription();
        return base == null || base.isBlank() ? null : base.strip();
    }

    static AiDictionaryDto.HistoryItem history(AiDescriptionProposalHistory h) {
        return new AiDictionaryDto.HistoryItem(h.getId(), h.getAction(), h.getBefore(), h.getAfter(), h.getProcessorId(), h.getProcessedAt(), h.getReason(),
                h.getConfirmedDescription());
    }

    static JsonNode node(String json) {
        return json == null ? null : JSON.readTree(json);
    }
}
