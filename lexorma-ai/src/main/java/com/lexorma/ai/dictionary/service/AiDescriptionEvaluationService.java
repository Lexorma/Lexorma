package com.lexorma.ai.dictionary.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.ExecutionType;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.ai.dictionary.domain.dto.AiEvaluationDto;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluation;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluation.Judgment;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluation.RecommendationJudgment;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluation.UncertaintyJudgment;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget.Category;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget.MeaningStatus;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposalHistory;
import com.lexorma.ai.dictionary.repository.AiDescriptionEvaluationRepository;
import com.lexorma.ai.dictionary.repository.AiDescriptionEvaluationTargetRepository;
import com.lexorma.ai.dictionary.repository.AiDescriptionProposalHistoryRepository;
import com.lexorma.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.ai.evidence.EvidenceCatalog;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * AI 설명 품질 평가 (AI-5 후속). 기준 데이터(평가 대상 + 사람이 확인한 실제 의미)와 사람의 판정을 저장하고 모델·프롬프트 버전별 지표를 낸다.
 * <ul>
 *   <li><b>AI 가 판정하지 않는다</b> : 정확성·근거 없는 주장·누락·불확실성 처리·추천 적절성은 사람이 적은 값만 쓴다. AI 의 확실성이나 근거 검증(VERIFIED)을 정확성으로 보지 않는다.
 *       허구 객체 비율만 서버의 근거 검증 결과(존재하지 않음·범위 밖·불일치)로 센다 — AI 의 판단이 아니라 DB 조회 결과다</li>
 *   <li>실제 의미는 AI 에 보내지 않는다 (생성 경로는 이 테이블을 읽지 않는다)</li>
 *   <li>판정은 실제 의미를 확인한(CONFIRMED) 대상에만 (미확인·확인 불가는 판정 자체를 받지 않는다 — 사용자 결정 2026-11-16). 확인 불가 대상은 정확성 분모에서 빼고 따로 센다</li>
 *   <li>판정 기준 고정 (AI-5-EVAL) : 판정이 하나라도 있는 대상의 실제 의미·확인 상태는 바꿀 수 없다. 판정은 화면이 본 대상 버전과 같을 때만 저장한다.
 *       둘 다 대상 행을 잠그므로 어느 쪽이 먼저든 판정은 그 판정자가 본 의미를 기준으로 남는다</li>
 *   <li>블라인드 (AI-5-EVAL) : 실제 의미를 확인하기 전에는 평가 상세에 AI 제안·판정 내용과 AI 승인으로 들어간 업무 설명을 보내지 않는다</li>
 *   <li>권한 : 조회 VIEWER, 기준 데이터·판정 작성 USER (로그인 실행만). 작성자를 남긴다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiDescriptionEvaluationService {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final int MAX_REGISTER = 100;
    private static final Set<String> INVALID_REFERENCE = Set.of("NOT_FOUND", "OUT_OF_SCOPE", "MISMATCH");
    private static final Set<String> AMOUNT_WORDS = Set.of("AMT", "PRC", "CRNCY", "CURR", "FEE", "TAX", "PAY");
    private static final Set<String> DATE_WORDS = Set.of("DT", "YMD", "DTTM", "TM", "YR", "MM", "DD", "TS");
    private static final Set<String> DATE_TYPES = Set.of("DATE", "TIME", "TIMESTAMP", "DATETIME", "TIMESTAMPTZ");

    private final ExecutionContextVerifier verifier;
    private final AiDescriptionEvaluationTargetRepository targets;
    private final AiDescriptionEvaluationRepository judgments;
    private final com.lexorma.ai.dictionary.repository.AiDescriptionEvaluationHistoryRepository judgmentHistories;
    private final AiDescriptionProposalRepository proposals;
    private final AiDescriptionProposalHistoryRepository histories;
    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;
    private final TableRelationRepository relations;
    private final EvidenceCatalog catalog;
    private final ModelChangeHistoryRepository modelHistories;
    private final ProposalEvaluationGate evaluationGate;

    /** 등록 시점 대상의 메타데이터 버전 */
    public record BaseVersion(LocalDateTime version, Long historyId) {
    }

    // ------------------------------------------------------------------ 후보·등록

    /** 평가 대상 후보 : tableId 가 없으면 테이블, 있으면 그 테이블과 컬럼. 범주는 규칙으로 고른 제안값이다 (사람이 고친다) */
    @Transactional(readOnly = true)
    public List<AiEvaluationDto.Candidate> candidates(ExecutionContext context, Long tableId) {
        Long projectId = verifier.verify(context, "VIEWER").context().projectId();
        Set<String> registered = targets.findAllByProjectIdOrderByIdAsc(projectId).stream().map(t -> key(t.getTableId(), t.getColumnId())).collect(Collectors.toSet());
        Map<String, Integer> proposalCount = new LinkedHashMap<>();
        proposals.findAllByProjectId(projectId).forEach(p -> proposalCount.merge(key(p.getTableId(), p.getColumnId()), 1, Integer::sum));
        List<AiEvaluationDto.Candidate> rows = new ArrayList<>();
        if (tableId == null) {
            for (TableInfo t : tables.findAll().stream().filter(t -> projectId.equals(t.getProjectId())).sorted(Comparator.comparing(TableInfo::getTableName)).toList()) {
                rows.add(new AiEvaluationDto.Candidate(AiDescriptionProposal.TargetType.TABLE, t.getId(), null, t.getTableName(), categories(projectId, t),
                        registered.contains(key(t.getId(), null)), proposalCount.getOrDefault(key(t.getId(), null), 0)));
            }
            return rows;
        }
        TableInfo table = table(projectId, tableId);
        rows.add(new AiEvaluationDto.Candidate(AiDescriptionProposal.TargetType.TABLE, table.getId(), null, table.getTableName(), categories(projectId, table),
                registered.contains(key(table.getId(), null)), proposalCount.getOrDefault(key(table.getId(), null), 0)));
        List<ColumnInfo> cols = table.getColumns().stream().sorted(Comparator.comparing(c -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder())).toList();
        Set<Long> related = relatedColumns(cols.stream().map(ColumnInfo::getId).toList());
        for (ColumnInfo c : cols) {
            rows.add(new AiEvaluationDto.Candidate(AiDescriptionProposal.TargetType.COLUMN, table.getId(), c.getId(), table.getTableName() + "." + c.getColumnName(),
                    categories(c, related.contains(c.getId())), registered.contains(key(table.getId(), c.getId())), proposalCount.getOrDefault(key(table.getId(), c.getId()), 0)));
        }
        return rows;
    }

    /**
     * 평가 대상 등록 (최대 100개). 이미 등록된 대상은 건너뛴다. 범주를 비우면 규칙으로 고른 범주를 쓴다.
     * 등록할 모델 대상 행(테이블 대상은 테이블, 컬럼 대상은 컬럼 — 승인이 잠그는 행과 같다)을 ID 순으로 잠근 뒤 이미 등록됐는지 본다 (AI-5-EVAL 2차) :
     * <ul>
     *   <li>승인과 줄 선다 : 등록이 먼저 커밋되면 그 행을 기다린 승인이 평가 대상을 보고 막히고, 승인이 먼저면 등록이 승인 커밋 뒤에 들어간다 (이미 된 승인은 그대로)</li>
     *   <li>같은 대상의 동시 등록이 둘 다 만들지 않는다 (프로젝트 행은 공유 잠금이라 등록끼리 줄 세우지 못한다 — PostgreSQL)</li>
     * </ul>
     * 잠금 순서 : 테이블 → 컬럼 (ID 순), 프로젝트 행은 다른 모델 저장처럼 커밋 직전에 공유 잠금(ProjectWriteGuard 자동). 승인은 제안 → 테이블 또는 컬럼 하나 → 평가 대상이고
     * 등록은 제안을 잠그지 않아 서로 엇갈리지 않는다. 프로젝트 행을 먼저 잡지 않는다 : H2 는 그 공유 잠금을 배타로 잡아, 같은 행을 쥔 승인이 커밋 때 프로젝트를 기다리면 교착한다
     */
    @Transactional
    public AiEvaluationDto.RegisterResult register(ExecutionContext context, AiEvaluationDto.RegisterRequest request) {
        VerifiedExecution execution = writer(context);
        Long projectId = execution.context().projectId();
        List<AiEvaluationDto.RegisterItem> items = request == null || request.items() == null ? List.of() : request.items();
        if (items.isEmpty() || items.size() > MAX_REGISTER) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        if (items.stream().anyMatch(i -> i.tableId() == null)) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        items.stream().filter(i -> i.columnId() == null).map(AiEvaluationDto.RegisterItem::tableId).distinct().sorted()
                .forEach(id -> tables.findForUpdate(id, projectId).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED)));
        items.stream().map(AiEvaluationDto.RegisterItem::columnId).filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> columns.findForUpdate(id, projectId).orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED)));
        // 잠근 뒤에 읽는다 : 먼저 커밋한 등록이 보인다
        Set<String> registered = targets.findAllByProjectIdOrderByIdAsc(projectId).stream().map(t -> key(t.getTableId(), t.getColumnId())).collect(Collectors.toCollection(HashSet::new));
        int created = 0, skipped = 0;
        for (AiEvaluationDto.RegisterItem item : items) {
            TableInfo table = table(projectId, item.tableId());
            ColumnInfo column = null;
            if (item.columnId() != null) {
                column = table.getColumns().stream().filter(c -> c.getId().equals(item.columnId())).findFirst()
                        .orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED));
            }
            if (!registered.add(key(table.getId(), item.columnId()))) {
                skipped++;
                continue;
            }
            List<Category> categories = parseCategories(item.categories());
            if (categories.isEmpty()) {
                categories = column == null ? categories(projectId, table) : categories(column, !relatedColumns(List.of(column.getId())).isEmpty());
            }
            targets.save(AiDescriptionEvaluationTarget.builder().projectId(projectId)
                    .targetType(column == null ? AiDescriptionProposal.TargetType.TABLE : AiDescriptionProposal.TargetType.COLUMN)
                    .tableId(table.getId()).columnId(column == null ? null : column.getId())
                    .targetName(column == null ? table.getTableName() : table.getTableName() + "." + column.getColumnName())
                    .categories(JSON.writeValueAsString(categories)).meaningStatus(MeaningStatus.PENDING)
                    .baseVersion(JSON.writeValueAsString(currentVersion(projectId, table.getId(), column == null ? null : column.getId()))).build());
            created++;
        }
        return new AiEvaluationDto.RegisterResult(created, skipped);
    }

    /** 실제 의미·확인 상태·범주. CONFIRMED 는 실제 의미가 있어야 한다 */
    @Transactional
    public AiEvaluationDto.TargetDetail updateTarget(ExecutionContext context, Long id, AiEvaluationDto.TargetUpdate request) {
        VerifiedExecution execution = writer(context);
        Long projectId = execution.context().projectId();
        AiDescriptionEvaluationTarget target = targets.findForUpdate(id, projectId).orElseThrow(() -> new CustomException(ErrorCode.AI_EVALUATION_NOT_FOUND));
        if (request == null || request.version() == null || !request.version().equals(target.getVersion())) throw new CustomException(ErrorCode.AI_EVALUATION_CONFLICT);
        MeaningStatus status = request.status() == null ? target.getMeaningStatus() : parse(MeaningStatus.class, request.status());
        String meaning = request.actualMeaning() == null ? target.getActualMeaning() : blank(request.actualMeaning());
        boolean meaningChanged = !Objects.equals(meaning, blank(target.getActualMeaning())) || status != target.getMeaningStatus();
        // 판정은 그때의 실제 의미를 기준으로 했다 : 판정이 있으면 의미 문구·확인 상태를 바꾸지 않는다 (같은 값 저장·범주 수정은 된다). 대상 행을 잠근 뒤 보므로 판정 저장과 엇갈리지 않는다
        if (meaningChanged && judgments.existsByProjectIdAndTargetId(projectId, id)) throw new CustomException(ErrorCode.AI_EVALUATION_MEANING_LOCKED);
        if (status == MeaningStatus.CONFIRMED && meaning == null) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        if (request.categories() != null) target.setCategories(JSON.writeValueAsString(parseCategories(request.categories())));
        target.setActualMeaning(meaning);
        target.setMeaningStatus(status);
        if (meaningChanged) {
            target.setReviewerId(execution.username());
            target.setReviewedAt(LocalDateTime.now());
        }
        targets.flush();
        return detailOf(projectId, target);
    }

    /** 판정이 없는 대상만 지운다 */
    @Transactional
    public void deleteTarget(ExecutionContext context, Long id) {
        Long projectId = writer(context).context().projectId();
        AiDescriptionEvaluationTarget target = targets.findForUpdate(id, projectId).orElseThrow(() -> new CustomException(ErrorCode.AI_EVALUATION_NOT_FOUND));
        if (judgments.existsByProjectIdAndTargetId(projectId, id)) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        targets.delete(target);
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<AiEvaluationDto.TargetRow> list(ExecutionContext context) {
        Long projectId = verifier.verify(context, "VIEWER").context().projectId();
        Map<String, Integer> proposalCount = new LinkedHashMap<>();
        proposals.findAllByProjectId(projectId).forEach(p -> proposalCount.merge(key(p.getTableId(), p.getColumnId()), 1, Integer::sum));
        Map<Long, Integer> judgmentCount = new LinkedHashMap<>();
        judgments.findAllByProjectId(projectId).forEach(j -> judgmentCount.merge(j.getTargetId(), 1, Integer::sum));
        return targets.findAllByProjectIdOrderByIdAsc(projectId).stream()
                .map(t -> row(projectId, t, proposalCount.getOrDefault(key(t.getTableId(), t.getColumnId()), 0), judgmentCount.getOrDefault(t.getId(), 0))).toList();
    }

    @Transactional(readOnly = true)
    public AiEvaluationDto.TargetDetail detail(ExecutionContext context, Long id) {
        Long projectId = verifier.verify(context, "VIEWER").context().projectId();
        return detailOf(projectId, targets.findByIdAndProjectId(id, projectId).orElseThrow(() -> new CustomException(ErrorCode.AI_EVALUATION_NOT_FOUND)));
    }

    private AiEvaluationDto.TargetDetail detailOf(Long projectId, AiDescriptionEvaluationTarget t) {
        List<AiDescriptionProposal> ps = proposals.findTarget(projectId, t.getTableId(), t.getColumnId());
        List<AiDescriptionEvaluation> js = judgments.findAllByProjectIdAndTargetIdOrderByIdAsc(projectId, t.getId());
        AiEvaluationDto.TargetRow row = row(projectId, t, ps.size(), js.size());
        // 블라인드 : 실제 의미를 확인하기 전에는 AI 가 만든 내용(제안·근거·확실성·질문·추천, 그것을 적은 판정)을 보내지 않는다
        boolean blind = ProposalEvaluationGate.blind(t);
        AiEvaluationDto.Metadata metadata = metadata(projectId, t, ps, blind);
        if (blind) return new AiEvaluationDto.TargetDetail(row, t.getActualMeaning(), t.getReviewerId(), t.getReviewedAt(), List.of(), List.of(), true, metadata);
        Set<Long> edited = editedProposals(projectId);
        BaseVersion base = JSON.readValue(t.getBaseVersion(), BaseVersion.class);
        List<AiEvaluationDto.ProposalItem> items = ps.stream().map(p -> new AiEvaluationDto.ProposalItem(p.getId(), p.getModel(), p.getPromptVersion(),
                p.audit() == null ? null : p.audit().created(), p.getStatus(), edited.contains(p.getId()), p.getCertainty(), p.getProposedDescription(),
                p.getConfirmedDescription(), ProposalMapper.node(p.getDetail()), ProposalMapper.node(p.getEvidence()), sameMetadata(base, p), invalidReferences(p),
                approvalHold(p), sameContext(projectId, t, p))).toList();
        return new AiEvaluationDto.TargetDetail(row, t.getActualMeaning(), t.getReviewerId(), t.getReviewedAt(), items,
                js.stream().map(AiDescriptionEvaluationService::judgmentRow).toList(), false, metadata);
    }

    /** 대상의 지금 메타데이터. 업무 설명이 승인된 AI 제안의 확정 문구와 같으면 AI 에서 온 값으로 표시하고, 블라인드에는 보내지 않는다 */
    private AiEvaluationDto.Metadata metadata(Long projectId, AiDescriptionEvaluationTarget t, List<AiDescriptionProposal> ps, boolean blind) {
        TableInfo table = tables.findByIdAndProjectId(t.getTableId(), projectId);
        ColumnInfo column = t.getColumnId() == null ? null : columns.findById(t.getColumnId()).filter(c -> projectId.equals(c.getProjectId())).orElse(null);
        if (table == null || (t.getColumnId() != null && column == null)) {
            return new AiEvaluationDto.Metadata(false, null, null, null, null, null, null, null, null, false, false);
        }
        String biz = blank(column == null ? table.getBizDescription() : column.getBizDescription());
        boolean fromAi = ProposalEvaluationGate.fromAi(biz, ps);
        boolean hidden = blind && fromAi;
        if (column == null) {
            return new AiEvaluationDto.Metadata(true, table.getTableName(), null, table.getTableKorName(), table.getTableDescription(), null, null,
                    table.getSourceComment(), hidden ? null : biz, fromAi, hidden);
        }
        String type = column.getDataType() == null ? null : column.getDataType() + (column.getMaxLength() == null ? "" : "(" + column.getMaxLength() + ")");
        return new AiEvaluationDto.Metadata(true, table.getTableName(), column.getColumnName(), column.getColumnDesc(), null, type,
                column.getStandardTerms() == null ? null : column.getStandardTerms().getCommonStandardTermName(), column.getSourceComment(), hidden ? null : biz, fromAi, hidden);
    }

    private String approvalHold(AiDescriptionProposal p) {
        if (!p.getStatus().open()) return null;
        ProposalEvaluationGate.Hold hold = evaluationGate.hold(p, false);
        return hold == null ? null : hold.name();
    }

    // ------------------------------------------------------------------ 판정

    /** 판정 저장 (검토자당 제안 하나에 하나). 실제 의미를 확인한 대상에만, 화면이 본 대상 버전일 때만 */
    @Transactional
    public AiEvaluationDto.JudgmentRow judge(ExecutionContext context, AiEvaluationDto.JudgmentRequest request) {
        VerifiedExecution execution = writer(context);
        Long projectId = execution.context().projectId();
        if (request == null || request.targetId() == null || request.proposalId() == null) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        // 대상 행을 잠가 확인 상태 변경과 엇갈리지 않게 한다 (잠금 순서 : 대상 → 판정)
        AiDescriptionEvaluationTarget target = targets.findForUpdate(request.targetId(), projectId).orElseThrow(() -> new CustomException(ErrorCode.AI_EVALUATION_NOT_FOUND));
        AiDescriptionProposal proposal = proposals.findByIdAndProjectId(request.proposalId(), projectId).orElseThrow(() -> new CustomException(ErrorCode.AI_PROPOSAL_NOT_FOUND));
        if (!proposal.getTableId().equals(target.getTableId()) || !Objects.equals(proposal.getColumnId(), target.getColumnId())) {
            throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        }
        // 블라인드라 확인 전 대상의 제안은 볼 수 없다 : 판정도 받지 않는다 (평가할 수 없음 포함)
        if (target.getMeaningStatus() != MeaningStatus.CONFIRMED) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID, "실제 의미를 확인한 평가 대상만 판정할 수 있습니다.");
        if (request.targetVersion() == null || !request.targetVersion().equals(target.getVersion())) throw new CustomException(ErrorCode.AI_EVALUATION_CONFLICT);
        Judgment judgment = parse(Judgment.class, request.judgment());
        if (request.unfoundedClaim() == null) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        if ((request.reviewMinutes() != null && request.reviewMinutes() < 0) || (request.writingMinutes() != null && request.writingMinutes() < 0)) {
            throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        }
        AiDescriptionEvaluation j = judgments.findByProposalIdAndReviewerId(proposal.getId(), execution.username()).orElse(null);
        if (j == null) {
            j = AiDescriptionEvaluation.builder().projectId(projectId).targetId(target.getId()).proposalId(proposal.getId()).model(proposal.getModel())
                    .promptVersion(proposal.getPromptVersion()).reviewerId(execution.username()).build();
        } else if (request.version() == null || !request.version().equals(j.getVersion())) {
            throw new CustomException(ErrorCode.AI_EVALUATION_CONFLICT);
        }
        // 판정 변경 이력 (DEC-27) : 고치기 전 판정을 남긴다 (처음 판정이면 null)
        String before = j.getId() == null ? null : judgmentState(j);
        j.setJudgment(judgment);
        j.setUnfoundedClaim(request.unfoundedClaim());
        j.setMissingInformation(blank(request.missingInformation()));
        j.setUncertainty(parse(UncertaintyJudgment.class, request.uncertainty()));
        j.setTermRecommendation(parse(RecommendationJudgment.class, request.termRecommendation()));
        j.setDomainRecommendation(parse(RecommendationJudgment.class, request.domainRecommendation()));
        j.setRequiredEdits(blank(request.requiredEdits()));
        j.setReviewMinutes(request.reviewMinutes());
        j.setWritingMinutes(request.writingMinutes());
        String after = judgmentState(j);
        if (before != null && before.equals(after)) return judgmentRow(j); // 바뀐 값이 없으면 저장·이력 없음
        String changeReason = blank(request.changeReason());
        if (before != null && changeReason == null) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID, "판정을 고친 사유를 입력해주세요.");
        if (changeReason != null && changeReason.length() > 1000) throw new CustomException(ErrorCode.AI_EVALUATION_INVALID, "사유는 {0}자까지 입력할 수 있습니다.", 1000);
        LocalDateTime now = LocalDateTime.now();
        j.setReviewedAt(now);
        j = judgments.saveAndFlush(j);
        judgmentHistories.save(com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationHistory.builder().projectId(projectId).judgmentId(j.getId())
                .targetId(j.getTargetId()).proposalId(j.getProposalId())
                .changeType(before == null ? com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationHistory.ChangeType.INSERT
                        : com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationHistory.ChangeType.UPDATE)
                .reviewerId(execution.username()).before(before).after(after).reason(changeReason).changedAt(now).build());
        return judgmentRow(j);
    }

    /** 판정 변경 이력 (DEC-27, 오래된 것부터). 판정 행의 프로젝트 범위 안에서만 */
    @Transactional(readOnly = true)
    public List<AiEvaluationDto.JudgmentHistoryRow> judgmentHistory(ExecutionContext context, Long judgmentId) {
        Long projectId = verifier.verify(context, "VIEWER").context().projectId();
        List<com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationHistory> rows = judgmentHistories.findAllByProjectIdAndJudgmentIdOrderByIdAsc(projectId, judgmentId);
        if (rows.isEmpty() && judgments.findById(judgmentId).filter(j -> projectId.equals(j.getProjectId())).isEmpty()) {
            throw new CustomException(ErrorCode.AI_EVALUATION_NOT_FOUND);
        }
        return rows.stream().map(h -> new AiEvaluationDto.JudgmentHistoryRow(h.getId(), h.getChangeType().name(), h.getReviewerId(),
                h.getBefore() == null ? null : stateMap(h.getBefore()), stateMap(h.getAfter()), h.getReason(), h.getChangedAt())).toList();
    }

    /** 판정 내용 (이력·비교용, 항목 순서 고정) : 판정자·시각·버전은 넣지 않는다 */
    private static String judgmentState(AiDescriptionEvaluation j) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("judgment", j.getJudgment() == null ? null : j.getJudgment().name());
        m.put("unfoundedClaim", j.getUnfoundedClaim());
        m.put("missingInformation", j.getMissingInformation());
        m.put("uncertainty", j.getUncertainty() == null ? null : j.getUncertainty().name());
        m.put("termRecommendation", j.getTermRecommendation() == null ? null : j.getTermRecommendation().name());
        m.put("domainRecommendation", j.getDomainRecommendation() == null ? null : j.getDomainRecommendation().name());
        m.put("requiredEdits", j.getRequiredEdits());
        m.put("reviewMinutes", j.getReviewMinutes());
        m.put("writingMinutes", j.getWritingMinutes());
        return JSON.writeValueAsString(m);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> stateMap(String json) {
        return JSON.readValue(json, java.util.LinkedHashMap.class);
    }

    // ------------------------------------------------------------------ 지표

    @Transactional(readOnly = true)
    public AiEvaluationDto.Metrics metrics(ExecutionContext context) {
        Long projectId = verifier.verify(context, "VIEWER").context().projectId();
        List<AiDescriptionEvaluationTarget> ts = targets.findAllByProjectIdOrderByIdAsc(projectId);
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (Category c : Category.values()) byCategory.put(c.name(), 0);
        ts.forEach(t -> readCategories(t.getCategories()).forEach(c -> byCategory.merge(c.name(), 1, Integer::sum)));
        AiEvaluationDto.Dataset dataset = new AiEvaluationDto.Dataset(ts.size(), count(ts, t -> t.getMeaningStatus() == MeaningStatus.CONFIRMED),
                count(ts, t -> t.getMeaningStatus() == MeaningStatus.UNCONFIRMABLE), count(ts, t -> t.getMeaningStatus() == MeaningStatus.PENDING), byCategory);
        Map<Long, AiDescriptionEvaluationTarget> targetById = ts.stream().collect(Collectors.toMap(AiDescriptionEvaluationTarget::getId, t -> t));
        List<AiDescriptionEvaluation> js = judgments.findAllByProjectId(projectId);
        Map<Long, AiDescriptionProposal> proposalById = proposals.findAllById(js.stream().map(AiDescriptionEvaluation::getProposalId).distinct().toList()).stream()
                .collect(Collectors.toMap(AiDescriptionProposal::getId, p -> p));
        Set<Long> edited = editedProposals(projectId);
        Map<String, List<AiDescriptionEvaluation>> groups = new LinkedHashMap<>();
        js.stream().sorted(Comparator.comparing(AiDescriptionEvaluation::getId)).forEach(j -> groups.computeIfAbsent(j.getModel() + "\u0000" + j.getPromptVersion(), k -> new ArrayList<>()).add(j));
        List<AiEvaluationDto.ModelResult> models = new ArrayList<>();
        for (List<AiDescriptionEvaluation> group : groups.values()) {
            int correct = 0, partial = 0, incorrect = 0, notEvaluable = 0, unfounded = 0, termFit = 0, termBase = 0, domainFit = 0, domainBase = 0;
            int uncertaintyOk = 0, missed = 0, excessive = 0, uncertaintyBase = 0, same = 0;
            List<Integer> review = new ArrayList<>(), writing = new ArrayList<>();
            Set<String> reviewers = new HashSet<>();
            Set<Long> proposalIds = new java.util.LinkedHashSet<>();
            for (AiDescriptionEvaluation j : group) {
                reviewers.add(j.getReviewerId());
                proposalIds.add(j.getProposalId());
                AiDescriptionEvaluationTarget t = targetById.get(j.getTargetId());
                boolean confirmed = t != null && t.getMeaningStatus() == MeaningStatus.CONFIRMED;
                switch (j.getJudgment()) {
                    case CORRECT -> { if (confirmed) correct++; else notEvaluable++; }
                    case PARTIAL -> { if (confirmed) partial++; else notEvaluable++; }
                    case INCORRECT -> { if (confirmed) incorrect++; else notEvaluable++; }
                    case NOT_EVALUABLE -> notEvaluable++;
                }
                if (Boolean.TRUE.equals(j.getUnfoundedClaim())) unfounded++;
                if (j.getTermRecommendation() == RecommendationJudgment.APPROPRIATE || j.getTermRecommendation() == RecommendationJudgment.INAPPROPRIATE) {
                    termBase++;
                    if (j.getTermRecommendation() == RecommendationJudgment.APPROPRIATE) termFit++;
                }
                if (j.getDomainRecommendation() == RecommendationJudgment.APPROPRIATE || j.getDomainRecommendation() == RecommendationJudgment.INAPPROPRIATE) {
                    domainBase++;
                    if (j.getDomainRecommendation() == RecommendationJudgment.APPROPRIATE) domainFit++;
                }
                if (j.getUncertainty() != UncertaintyJudgment.NOT_APPLICABLE) {
                    uncertaintyBase++;
                    if (j.getUncertainty() == UncertaintyJudgment.APPROPRIATE) uncertaintyOk++;
                    if (j.getUncertainty() == UncertaintyJudgment.MISSED) missed++;
                    if (j.getUncertainty() == UncertaintyJudgment.EXCESSIVE) excessive++;
                }
                if (j.getReviewMinutes() != null && j.getWritingMinutes() != null) {
                    review.add(j.getReviewMinutes());
                    writing.add(j.getWritingMinutes());
                }
                AiDescriptionProposal p = proposalById.get(j.getProposalId());
                // 같은 입력 조건 : 대상 자신의 버전이 같고(sameMetadata) 등록 ~ 생성 사이에 같은 테이블이 바뀌지 않았다 (DEC-29 입력 맥락)
                if (t != null && p != null && sameMetadata(JSON.readValue(t.getBaseVersion(), BaseVersion.class), p) && sameContext(projectId, t, p)) same++;
            }
            int fabricated = 0, approved = 0, rejected = 0, editedCount = 0;
            for (Long id : proposalIds) {
                AiDescriptionProposal p = proposalById.get(id);
                if (p == null) continue;
                if (invalidReferences(p) > 0) fabricated++;
                if (p.getStatus() == AiDescriptionProposal.ReviewStatus.APPROVED) approved++;
                if (p.getStatus() == AiDescriptionProposal.ReviewStatus.REJECTED) rejected++;
                if (edited.contains(id)) editedCount++;
            }
            int accuracyBase = correct + partial + incorrect;
            Double medianReview = median(review), medianWriting = median(writing);
            Double saving = medianReview == null || medianWriting == null || medianWriting == 0 ? null : 1 - medianReview / medianWriting;
            AiDescriptionEvaluation first = group.getFirst();
            models.add(new AiEvaluationDto.ModelResult(first.getModel(), first.getPromptVersion(), group.size(), proposalIds.size(), reviewers.size(),
                    rate(correct, accuracyBase), rate(partial, accuracyBase), notEvaluable, rate(unfounded, group.size()), rate(fabricated, proposalIds.size()),
                    rate(termFit, termBase), rate(domainFit, domainBase), rate(uncertaintyOk, uncertaintyBase), rate(missed, uncertaintyBase), rate(excessive, uncertaintyBase),
                    rate(editedCount, approved), rate(approved, approved + rejected), medianReview, medianWriting, review.size(), saving, same));
        }
        boolean evaluated = models.stream().anyMatch(m -> m.accuracy().base() > 0);
        return new AiEvaluationDto.Metrics(dataset, models, evaluated,
                "정확성·근거 없는 주장·불확실성 처리·추천 적절성은 사람의 판정만 씁니다. AI 의 확실성과 근거 검증(VERIFIED)은 정확성으로 보지 않습니다. 허구 객체는 서버의 근거 검증 결과입니다. 합격 기준은 정하지 않았습니다.");
    }

    // ------------------------------------------------------------------ 범주 규칙

    private List<Category> categories(Long projectId, TableInfo t) {
        Set<Category> c = EnumSet.noneOf(Category.class);
        String biz = blank(t.getBizDescription()), source = blank(t.getSourceComment());
        if (biz != null) c.add(Category.CLEAR);
        if (source == null) c.add(Category.NO_SOURCE_COMMENT);
        if (biz != null && source != null && !biz.equals(source)) c.add(Category.COMMENT_DIFFERS);
        if (!relations.findAllByProjectIdAndSourceTable_Id(projectId, t.getId()).isEmpty() || !relations.findAllByProjectIdAndTargetTable_Id(projectId, t.getId()).isEmpty()) {
            c.add(Category.RELATION);
        }
        if (biz == null && source == null && blank(t.getTableKorName()) == null) c.add(Category.NAME_ONLY);
        return List.copyOf(c);
    }

    private static List<Category> categories(ColumnInfo col, boolean related) {
        Set<Category> c = EnumSet.noneOf(Category.class);
        List<String> words = Arrays.asList(col.getColumnName().toUpperCase(Locale.ROOT).split("_"));
        String type = col.getDataType() == null ? "" : col.getDataType().toUpperCase(Locale.ROOT);
        String biz = blank(col.getBizDescription()), source = blank(col.getSourceComment());
        if (biz != null) c.add(Category.CLEAR);
        if (words.getLast().equals("CD") || words.contains("STTS")) c.add(Category.STATUS_CODE);
        if (words.stream().anyMatch(AMOUNT_WORDS::contains)) c.add(Category.AMOUNT);
        if (words.stream().anyMatch(DATE_WORDS::contains) || DATE_TYPES.stream().anyMatch(type::startsWith)) c.add(Category.DATE_TIME);
        if (col.getStandardTerms() != null) c.add(Category.STANDARD_LINKED);
        if (related) c.add(Category.RELATION);
        if (source == null) c.add(Category.NO_SOURCE_COMMENT);
        if (biz != null && source != null && !biz.equals(source)) c.add(Category.COMMENT_DIFFERS);
        if (biz == null && source == null && col.getStandardTerms() == null) c.add(Category.NAME_ONLY);
        return List.copyOf(c);
    }

    /** FK·LOGICAL 등 관계에 쓰인 컬럼 */
    private Set<Long> relatedColumns(Collection<Long> columnIds) {
        if (columnIds.isEmpty()) return Set.of();
        Set<Long> ids = new HashSet<>(columnIds);
        Set<Long> used = new HashSet<>();
        for (TableRelation r : relations.findAllUsingColumns(ids)) {
            r.getColumns().forEach(pair -> {
                if (ids.contains(pair.getSourceColumn().getId())) used.add(pair.getSourceColumn().getId());
                if (ids.contains(pair.getTargetColumn().getId())) used.add(pair.getTargetColumn().getId());
            });
        }
        return used;
    }

    // ------------------------------------------------------------------ 공통

    private VerifiedExecution writer(ExecutionContext context) {
        if (context == null || context.type() != ExecutionType.USER) throw new CustomException(ErrorCode.EXECUTION_PERMISSION_DENIED);
        return verifier.verify(context, "USER");
    }

    private TableInfo table(Long projectId, Long tableId) {
        TableInfo table = tableId == null ? null : tables.findByIdAndProjectId(tableId, projectId);
        if (table == null) throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        return table;
    }

    private BaseVersion currentVersion(Long projectId, Long tableId, Long columnId) {
        EvidenceType type = columnId == null ? EvidenceType.TABLE : EvidenceType.COLUMN;
        Long id = columnId == null ? tableId : columnId;
        LocalDateTime version = catalog.load(type, List.of(id)).values().stream().findFirst().map(EvidenceCatalog.Snapshot::version).orElse(null);
        Long historyId = modelHistories.lastId(projectId, columnId == null ? ModelChangeHistory.TargetType.PHYSICAL_TABLE : ModelChangeHistory.TargetType.PHYSICAL_COLUMN, id);
        return new BaseVersion(version, historyId);
    }

    /**
     * 평가 입력 맥락 (DEC-29) : 평가 대상 등록과 제안 생성 사이(어느 쪽이 먼저든)에 같은 테이블(자신·컬럼 — 다른 컬럼의 업무 설명 포함)이 바뀌지 않았는지.
     * 모델 변경 이력으로 판단해 편집을 막지 않는다 (기준값 검증). 관계·표준 사전의 변경은 보지 않는다 — 운영 규칙(평가 기간 수정 금지)으로 보완
     */
    private boolean sameContext(Long projectId, AiDescriptionEvaluationTarget t, AiDescriptionProposal p) {
        LocalDateTime registered = t.audit() == null ? null : t.audit().created();
        LocalDateTime generated = p.audit() == null ? null : p.audit().created();
        if (registered == null || generated == null) return false;
        TableInfo table = tables.findByIdAndProjectId(t.getTableId(), projectId);
        if (table == null) return false;
        LocalDateTime from = registered.isBefore(generated) ? registered : generated;
        LocalDateTime to = registered.isBefore(generated) ? generated : registered;
        if (modelHistories.countTableChanges(projectId, table.getId(), table.getTableName() + ".%", from, to) > 0) return false;
        // 테이블에 걸린 관계(나가는·들어오는 FK — AI 입력의 관계 메타데이터) : 지금 있는 관계의 변경 이력 (그 사이 지워진 관계는 테이블·컬럼 이력으로 대개 함께 잡힌다)
        List<Long> relationIds = java.util.stream.Stream.concat(relations.findAllByProjectIdAndSourceTable_Id(projectId, table.getId()).stream(),
                relations.findAllByProjectIdAndTargetTable_Id(projectId, table.getId()).stream()).map(com.lexorma.data.physical.domain.entity.TableRelation::getId).distinct().toList();
        if (!relationIds.isEmpty() && modelHistories.countChanges(projectId, ModelChangeHistory.TargetType.PHYSICAL_RELATION, relationIds, from, to) > 0) return false;
        // 컬럼에 연결된 표준 용어와 그 도메인 (AI 입력의 용어 정의·도메인 타입) : 사이에 수정됐으면 맥락이 바뀌었다. 표준 사전은 모델 변경 이력이 없어 수정 일시로 본다 —
        // 그 뒤에 다시 고쳐지면 사이의 수정은 보이지 않는다 (마지막 수정 일시만 남는다)
        for (ColumnInfo column : table.getColumns()) {
            var term = column.getStandardTerms();
            if (term == null) continue;
            if (within(term.modifiedAt(), from, to)) return false;
            if (term.getStandardDomain() != null && within(term.getStandardDomain().modifiedAt(), from, to)) return false;
        }
        return true;
    }

    private static boolean within(LocalDateTime at, LocalDateTime from, LocalDateTime to) {
        return at != null && at.isAfter(from) && !at.isAfter(to);
    }

    /** 제안이 평가 대상 등록 시점과 같은 메타데이터 버전(대상의 수정 일시·변경 이력)으로 만들어졌는지 */
    private static boolean sameMetadata(BaseVersion base, AiDescriptionProposal p) {
        ProposalVersions.Ref target = ProposalVersions.read(p.getBaseVersions()).target();
        return Objects.equals(base.version(), target.version()) && Objects.equals(base.historyId(), target.historyId());
    }

    /** 근거·추천·연관 객체 중 서버 검증에서 존재하지 않음·범위 밖·불일치로 나온 수 */
    static int invalidReferences(AiDescriptionProposal p) {
        return invalid(ProposalMapper.node(p.getEvidence())) + invalid(ProposalMapper.node(p.getDetail()));
    }

    private static int invalid(JsonNode node) {
        if (node == null) return 0;
        int n = 0;
        if (node.isObject() && node.has("status") && INVALID_REFERENCE.contains(node.get("status").asString(""))) n++;
        for (JsonNode child : node) n += invalid(child);
        return n;
    }

    private Set<Long> editedProposals(Long projectId) {
        return histories.findAllByProjectId(projectId).stream().filter(h -> h.getAction() == AiDescriptionProposalHistory.Action.APPROVE_EDITED)
                .map(AiDescriptionProposalHistory::getProposalId).collect(Collectors.toSet());
    }

    private AiEvaluationDto.TargetRow row(Long projectId, AiDescriptionEvaluationTarget t, int proposalCount, int judgmentCount) {
        boolean exists = t.getColumnId() == null ? tables.findByIdAndProjectId(t.getTableId(), projectId) != null
                : columns.findById(t.getColumnId()).filter(c -> projectId.equals(c.getProjectId())).isPresent();
        return new AiEvaluationDto.TargetRow(t.getId(), t.getTargetType(), t.getTableId(), t.getColumnId(), t.getTargetName(), exists, readCategories(t.getCategories()),
                t.getMeaningStatus(), blank(t.getActualMeaning()) != null, proposalCount, judgmentCount, t.getVersion());
    }

    private static AiEvaluationDto.JudgmentRow judgmentRow(AiDescriptionEvaluation j) {
        return new AiEvaluationDto.JudgmentRow(j.getId(), j.getProposalId(), j.getModel(), j.getPromptVersion(), j.getJudgment(), Boolean.TRUE.equals(j.getUnfoundedClaim()),
                j.getMissingInformation(), j.getUncertainty(), j.getTermRecommendation(), j.getDomainRecommendation(), j.getRequiredEdits(), j.getReviewMinutes(),
                j.getWritingMinutes(), j.getReviewerId(), j.getReviewedAt(), j.getVersion());
    }

    private static List<Category> parseCategories(List<String> values) {
        if (values == null) return List.of();
        Set<Category> c = EnumSet.noneOf(Category.class);
        for (String v : values) c.add(parse(Category.class, v));
        return List.copyOf(c);
    }

    private static List<Category> readCategories(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Category> c = new ArrayList<>();
        for (JsonNode n : JSON.readTree(json)) c.add(Category.valueOf(n.asString()));
        return c;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, Objects.requireNonNull(value).trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCode.AI_EVALUATION_INVALID);
        }
    }

    private static <T> int count(Collection<T> items, Predicate<T> test) {
        return (int) items.stream().filter(test).count();
    }

    private static AiEvaluationDto.Rate rate(int count, int base) {
        return new AiEvaluationDto.Rate(base == 0 ? null : (double) count / base, count, base);
    }

    private static Double median(List<Integer> values) {
        if (values.isEmpty()) return null;
        List<Integer> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String key(Long tableId, Long columnId) {
        return tableId + ":" + columnId;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
