package com.lexorma.data.ai.dictionary.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.data.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal.ReviewStatus;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposalHistory;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalHistoryRepository;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * AI 설명 제안 조회 (AI-5) : 목록·상세(변경 미리 보기)·생성 대상 후보·테이블/컬럼 상태·품질 지표. 모두 로그인 프로젝트만, VIEWER 부터. 쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, timeout = 30)
public class AiDictionaryQueryService {

    private final AiDescriptionProposalRepository proposals;
    private final AiDescriptionProposalHistoryRepository histories;
    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;
    private final ProposalVersions versions;
    private final ExecutionContextVerifier verifier;

    /**
     * @param status  검토 상태 (비면 전체, OPEN 이면 검토 대기 전체)
     * @param keyword 대상 이름 부분 일치
     */
    public Page<AiDictionaryDto.ProposalSummary> list(ExecutionContext context, String status, String targetType, Long tableId, String keyword, Boolean recheck,
                                                      int page, int size) {
        verifier.verify(context, "VIEWER");
        Long projectId = context.projectId();
        String st = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        Specification<AiDescriptionProposal> where = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("projectId"), projectId));
            if (st.equals("OPEN")) p.add(root.get("status").in(ReviewStatus.PROPOSED, ReviewStatus.REVIEWED, ReviewStatus.ON_HOLD, ReviewStatus.REVIEW_REQUIRED));
            else if (!st.isEmpty()) p.add(cb.equal(root.get("status"), code(ReviewStatus.class, st)));
            if (targetType != null && !targetType.isBlank()) p.add(cb.equal(root.get("targetType"), code(AiDescriptionProposal.TargetType.class, targetType)));
            if (tableId != null) p.add(cb.equal(root.get("tableId"), tableId));
            if (keyword != null && !keyword.isBlank()) p.add(cb.like(cb.upper(root.get("targetName")), "%" + keyword.strip().toUpperCase(Locale.ROOT) + "%"));
            if (Boolean.TRUE.equals(recheck)) p.add(cb.isTrue(root.get("recheckNeeded")));
            return cb.and(p.toArray(Predicate[]::new));
        };
        return proposals.findAll(where, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Order.desc("id"))))
                .map(ProposalMapper::summary);
    }

    /** 상세 : 저장한 내용 + 대상의 지금 값 + 생성 뒤의 변경(미리 보기, 상태를 바꾸지 않는다) + 이력 */
    public AiDictionaryDto.ProposalDetail detail(ExecutionContext context, Long id) {
        verifier.verify(context, "VIEWER");
        Long projectId = context.projectId();
        AiDescriptionProposal p = proposals.findByIdAndProjectId(id, projectId).orElseThrow(() -> new CustomException(ErrorCode.AI_PROPOSAL_NOT_FOUND));
        AiDictionaryDto.Current current = current(projectId, p);
        ProposalVersions.Base base = ProposalVersions.read(p.getBaseVersions());
        // 승인된 제안은 승인으로 업무 설명이 바뀌었으므로 업무 설명은 비교하지 않는다. 끝난 제안(반려·대체)은 비교하지 않는다
        List<ProposalVersions.Change> changes = p.getStatus().open() || p.getStatus() == ReviewStatus.APPROVED
                ? versions.changes(projectId, base, current.bizDescription(), p.getStatus().open()) : List.of();
        List<AiDictionaryDto.HistoryItem> history = histories.findAllByProjectIdAndProposalIdOrderByIdAsc(projectId, id).stream().map(ProposalMapper::history).toList();
        return new AiDictionaryDto.ProposalDetail(ProposalMapper.summary(p), ProposalMapper.node(p.getDetail()), ProposalMapper.node(p.getEvidence()), current, changes, history);
    }

    private AiDictionaryDto.Current current(Long projectId, AiDescriptionProposal p) {
        if (p.getColumnId() != null) {
            return columns.findAllByProjectIdAndId(projectId, p.getColumnId()).stream().findFirst()
                    .map(c -> new AiDictionaryDto.Current(true, c.getTableName() + "." + c.getColumnName(), c.getColumnDesc(), c.getSourceComment(), c.getBizDescription(), c.getIsApproval()))
                    .orElse(new AiDictionaryDto.Current(false, p.getTargetName(), null, null, null, null));
        }
        return tables.findById(p.getTableId()).filter(t -> projectId.equals(t.getProjectId()))
                .map(t -> new AiDictionaryDto.Current(true, t.getTableName(), t.getTableKorName(), t.getSourceComment(), t.getBizDescription(), t.getIsApproval()))
                .orElse(new AiDictionaryDto.Current(false, p.getTargetName(), null, null, null, null));
    }

    /**
     * 생성 대상 후보 : tableId 가 없으면 테이블 목록, 있으면 그 테이블과 컬럼. 원본 COMMENT·업무 설명·최근 제안을 구분해 보인다
     * (원본 COMMENT 가 있어도 업무 설명이 있는 것으로 보지 않는다)
     */
    public List<AiDictionaryDto.TargetRow> targets(ExecutionContext context, Long tableId, boolean onlyMissing, String keyword) {
        verifier.verify(context, "VIEWER");
        Long projectId = context.projectId();
        Map<String, AiDescriptionProposal> latest = latestByTarget(projectId, tableId);
        List<AiDictionaryDto.TargetRow> rows = new ArrayList<>();
        String kw = keyword == null || keyword.isBlank() ? null : keyword.strip().toUpperCase(Locale.ROOT);
        if (tableId == null) {
            List<TableInfo> list = tables.findAll((root, q, cb) -> cb.equal(root.get("projectId"), projectId));
            list.stream().sorted(Comparator.comparing(TableInfo::getTableName))
                    .filter(t -> !onlyMissing || blank(t.getBizDescription()))
                    .filter(t -> kw == null || t.getTableName().toUpperCase(Locale.ROOT).contains(kw) || (t.getTableKorName() != null && t.getTableKorName().toUpperCase(Locale.ROOT).contains(kw)))
                    .limit(500)
                    .forEach(t -> rows.add(row(AiDescriptionProposal.TargetType.TABLE, t.getId(), null, t.getTableName(), t.getTableKorName(), t.getSourceComment(),
                            t.getBizDescription(), latest.get(key(t.getId(), null)))));
            return rows;
        }
        TableInfo table = tables.findById(tableId).filter(t -> projectId.equals(t.getProjectId())).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
        if (!onlyMissing || blank(table.getBizDescription())) {
            rows.add(row(AiDescriptionProposal.TargetType.TABLE, tableId, null, table.getTableName(), table.getTableKorName(), table.getSourceComment(), table.getBizDescription(),
                    latest.get(key(tableId, null))));
        }
        columns.findAllByTableInfo_IdIn(List.of(tableId)).stream().filter(c -> projectId.equals(c.getProjectId()))
                .sorted(Comparator.comparing((ColumnInfo c) -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder()))
                .filter(c -> !onlyMissing || blank(c.getBizDescription()))
                .forEach(c -> rows.add(row(AiDescriptionProposal.TargetType.COLUMN, tableId, c.getId(), table.getTableName() + "." + c.getColumnName(), c.getColumnDesc(),
                        c.getSourceComment(), c.getBizDescription(), latest.get(key(tableId, c.getId())))));
        return rows;
    }

    /** 테이블·컬럼 화면과 ERD 상세용 : 그 테이블과 컬럼의 업무 설명과 최근 제안 상태 */
    public List<AiDictionaryDto.ObjectStatus> objectStatus(ExecutionContext context, Long tableId) {
        verifier.verify(context, "VIEWER");
        Long projectId = context.projectId();
        TableInfo table = tables.findById(tableId).filter(t -> projectId.equals(t.getProjectId())).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
        Map<String, AiDescriptionProposal> latest = latestByTarget(projectId, tableId);
        List<AiDictionaryDto.ObjectStatus> rows = new ArrayList<>();
        rows.add(status(AiDescriptionProposal.TargetType.TABLE, tableId, null, table.getBizDescription(), latest.get(key(tableId, null))));
        columns.findAllByTableInfo_IdIn(List.of(tableId)).stream().filter(c -> projectId.equals(c.getProjectId()))
                .forEach(c -> rows.add(status(AiDescriptionProposal.TargetType.COLUMN, tableId, c.getId(), c.getBizDescription(), latest.get(key(tableId, c.getId())))));
        return rows;
    }

    /**
     * 품질 지표 (U-20) : 모델·프롬프트 버전별. 사용자의 승인·수정·반려와 근거 검증 결과에서 계산한다.
     * 표준 용어 추천 적합성은 추천한 용어가 그 컬럼에 지금 연결된 <b>승인된</b> 표준 용어와 같은지로 본다 (용어가 연결된 컬럼만 — 기준 데이터로 쓸 수 있다).
     * 업무적 정확성은 사람이 판정한 기준 데이터가 있어야 해서 계산하지 않는다
     */
    public AiDictionaryDto.Metrics metrics(ExecutionContext context) {
        verifier.verify(context, "VIEWER");
        Long projectId = context.projectId();
        List<AiDescriptionProposal> all = proposals.findAllByProjectId(projectId);
        Map<Long, List<AiDescriptionProposalHistory>> byProposal = new LinkedHashMap<>();
        histories.findAllByProjectId(projectId).forEach(h -> byProposal.computeIfAbsent(h.getProposalId(), k -> new ArrayList<>()).add(h));
        Map<Long, ColumnInfo> columnTerms = new LinkedHashMap<>();
        List<Long> columnIds = all.stream().map(AiDescriptionProposal::getColumnId).filter(Objects::nonNull).distinct().toList();
        if (!columnIds.isEmpty()) columns.findAllById(columnIds).stream().filter(c -> projectId.equals(c.getProjectId())).forEach(c -> columnTerms.put(c.getId(), c));

        Map<String, List<AiDescriptionProposal>> groups = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparing(AiDescriptionProposal::getId))
                .forEach(p -> groups.computeIfAbsent(p.getModel() + "\u0000" + p.getPromptVersion(), k -> new ArrayList<>()).add(p));
        List<AiDictionaryDto.ModelMetrics> models = new ArrayList<>();
        for (List<AiDescriptionProposal> group : groups.values()) {
            int approved = 0, edited = 0, rejected = 0, hold = 0, required = 0, superseded = 0, open = 0, unknown = 0, unsupported = 0;
            int cited = 0, invalid = 0, relations = 0, invalidRelations = 0, termBase = 0, termMatch = 0;
            List<Double> minutes = new ArrayList<>();
            for (AiDescriptionProposal p : group) {
                switch (p.getStatus()) {
                    case APPROVED -> approved++;
                    case REJECTED -> rejected++;
                    case ON_HOLD -> hold++;
                    case REVIEW_REQUIRED -> required++;
                    case SUPERSEDED -> superseded++;
                    default -> open++;
                }
                List<AiDescriptionProposalHistory> h = byProposal.getOrDefault(p.getId(), List.of());
                if (h.stream().anyMatch(x -> x.getAction() == AiDescriptionProposalHistory.Action.APPROVE_EDITED)) edited++;
                h.stream().filter(x -> x.getAction() != AiDescriptionProposalHistory.Action.GENERATE && x.getAction() != AiDescriptionProposalHistory.Action.SUPERSEDE
                                && x.getAction() != AiDescriptionProposalHistory.Action.REVIEW_REQUIRED && x.getAction() != AiDescriptionProposalHistory.Action.RECHECK_FLAGGED)
                        .findFirst().ifPresent(first -> {
                            var created = p.audit() == null ? null : p.audit().created();
                            if (created != null && first.getProcessedAt() != null) minutes.add(Duration.between(created, first.getProcessedAt()).toSeconds() / 60.0);
                        });
                if (p.getCertainty() == AiDescriptionProposal.Certainty.UNKNOWN) unknown++;
                JsonNode detail = ProposalMapper.node(p.getDetail());
                JsonNode evidence = ProposalMapper.node(p.getEvidence());
                int verified = 0;
                if (evidence != null) {
                    for (JsonNode e : evidence) {
                        cited++;
                        String status = e.path("status").asString("");
                        if (status.equals("VERIFIED")) verified++;
                        if (status.equals("NOT_FOUND") || status.equals("OUT_OF_SCOPE") || status.equals("MISMATCH")) invalid++;
                        if ("RELATION".equals(e.path("type").asString(""))) {
                            relations++;
                            if (!status.equals("VERIFIED")) invalidRelations++;
                        }
                    }
                }
                String claimed = detail == null ? "" : detail.path("claimedCertainty").asString("");
                if (verified == 0 && (claimed.equals("VERIFIED") || claimed.equals("INFERRED"))) unsupported++;
                ColumnInfo column = p.getColumnId() == null ? null : columnTerms.get(p.getColumnId());
                if (column != null && column.getStandardTerms() != null && Boolean.TRUE.equals(column.getStandardTerms().getIsApproval())
                        && detail != null && detail.has("termRecommendation")) {
                    termBase++;
                    if (column.getStandardTerms().getId().equals(detail.path("termRecommendation").path("id").asLong(-1))) termMatch++;
                }
            }
            minutes.sort(Comparator.naturalOrder());
            Double median = minutes.isEmpty() ? null : minutes.get(minutes.size() / 2);
            AiDescriptionProposal first = group.getFirst();
            models.add(new AiDictionaryDto.ModelMetrics(first.getModel(), first.getPromptVersion(), group.size(), approved, edited, rejected, hold, required, superseded, open,
                    ratio(approved, approved + rejected), ratio(edited, approved), ratio(rejected, approved + rejected), ratio(invalid, cited),
                    ratio(unsupported, group.size()), ratio(invalidRelations, relations), ratio(unknown, group.size()), ratio(termMatch, termBase), termBase, median));
        }
        return new AiDictionaryDto.Metrics(models, "업무적 정확성은 사람이 판정한 기준 데이터가 있어야 해서 계산하지 않습니다. 목표치는 정하지 않았습니다 (U-20).");
    }

    /** 생성 결과 응답 */
    public AiDictionaryDto.GenerateResult generated(AiDescriptionDraftService.Result result) {
        return new AiDictionaryDto.GenerateResult(result.outcome().name(), ProposalMapper.summary(result.proposal()));
    }

    // ------------------------------------------------------------------ 공통

    /** 대상별 최근 제안 (차수가 가장 큰 것) */
    private Map<String, AiDescriptionProposal> latestByTarget(Long projectId, Long tableId) {
        List<AiDescriptionProposal> list = tableId == null ? proposals.findAllByProjectId(projectId) : proposals.findAllByProjectIdAndTableIdOrderByCycleDesc(projectId, tableId);
        Map<String, AiDescriptionProposal> latest = new LinkedHashMap<>();
        for (AiDescriptionProposal p : list) {
            if (tableId == null && p.getColumnId() != null) continue;
            latest.merge(key(p.getTableId(), p.getColumnId()), p, (a, b) -> a.getCycle() >= b.getCycle() ? a : b);
        }
        return latest;
    }

    private static AiDictionaryDto.TargetRow row(AiDescriptionProposal.TargetType type, Long tableId, Long columnId, String name, String description, String sourceComment,
                                                 String bizDescription, AiDescriptionProposal latest) {
        return new AiDictionaryDto.TargetRow(type, tableId, columnId, name, description, !blank(sourceComment), !blank(bizDescription),
                latest == null ? null : latest.getId(), latest == null ? null : latest.getStatus(), latest != null && Boolean.TRUE.equals(latest.getRecheckNeeded()));
    }

    private static AiDictionaryDto.ObjectStatus status(AiDescriptionProposal.TargetType type, Long tableId, Long columnId, String bizDescription, AiDescriptionProposal latest) {
        return new AiDictionaryDto.ObjectStatus(type, tableId, columnId, bizDescription, latest == null ? null : latest.getId(), latest == null ? null : latest.getStatus(),
                latest != null && Boolean.TRUE.equals(latest.getRecheckNeeded()));
    }

    private static String key(Long tableId, Long columnId) {
        return tableId + ":" + columnId;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Double ratio(int part, int whole) {
        return whole == 0 ? null : Math.round(part * 10000.0 / whole) / 10000.0;
    }

    private static <E extends Enum<E>> E code(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.AI_QUERY_INVALID, "조회 조건이 올바르지 않습니다.");
        }
    }
}
