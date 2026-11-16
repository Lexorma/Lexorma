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

    /** blind 면 AI 가 만든 내용을 비운다 (식별·상태·검토 정보만) — 판단은 {@link ProposalEvaluationGate#blind} */
    static AiDictionaryDto.ProposalSummary summary(AiDescriptionProposal p, boolean blind) {
        if (blind) {
            return new AiDictionaryDto.ProposalSummary(p.getId(), p.getTargetType(), p.getTableId(), p.getColumnId(), p.getTargetName(), p.getCycle(), p.getStatus(),
                    null, null, null, p.getModel(), p.getPromptVersion(), p.getCreationMethod(), p.getJobId(),
                    p.audit() == null ? null : p.audit().created(), p.getReviewerId(), p.getReviewedAt(), p.getReviewReason(), Boolean.TRUE.equals(p.getRecheckNeeded()),
                    p.getVersion() == null ? 0 : p.getVersion(), 0, 0, false, true);
        }
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
                p.getVersion() == null ? 0 : p.getVersion(), total, verified, replaces(p), false);
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

    /** blind 면 확정 문구(승인한 AI 제안 문구)를 비운다 */
    static AiDictionaryDto.HistoryItem history(AiDescriptionProposalHistory h, boolean blind) {
        return new AiDictionaryDto.HistoryItem(h.getId(), h.getAction(), h.getBefore(), h.getAfter(), h.getProcessorId(), h.getProcessedAt(), h.getReason(),
                blind ? null : h.getConfirmedDescription());
    }

    static JsonNode node(String json) {
        return json == null ? null : JSON.readTree(json);
    }
}
