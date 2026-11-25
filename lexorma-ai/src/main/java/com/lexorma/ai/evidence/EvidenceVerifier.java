package com.lexorma.ai.evidence;

import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.ai.answer.StructuredAnswer;
import com.lexorma.ai.tool.MetadataToolSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 근거 검증 (AI-4 후속) : AI 가 답변의 근거로 든 메타데이터를 서버가 확인한다. 상태는 AI 가 아니라 여기서 정한다.
 * <ol>
 *   <li>실행 컨텍스트를 다시 확인한다 (프로젝트·요청자 권한 — Tool 과 같은 VIEWER). 실패하면 질의 전체를 끝낸다</li>
 *   <li>종류별로 지금 상태를 한 번에 읽는다 ({@link EvidenceCatalog}, 종류마다 ID 조회 한 번)</li>
 *   <li>근거마다 : 없음 → {@link Status#NOT_FOUND} (이 질의에서 조회한 뒤 지워졌으면 {@link Status#STALE}) · 다른 프로젝트 → {@link Status#OUT_OF_SCOPE}
 *       (그 행의 내용은 내지 않는다) · 이 질의의 Tool 결과에 없던 것 → {@link Status#UNVERIFIABLE} · 조회 뒤 수정 → {@link Status#STALE} ·
 *       이름·소속 테이블·관계 종류가 다름 → {@link Status#MISMATCH} · 버전(수정 일시)이 없음 → {@link Status#UNVERIFIABLE} · 모두 맞음 → {@link Status#VERIFIED}.
 *       설명 사용 표시(usesDescription, 논리 속성 — D35-D25)가 있으면 이 질의에서 지금 버전의 설명을 실제로 돌려줬는지까지 본다 (아니면 UNVERIFIABLE, 설명이 없었으면 MISMATCH)</li>
 * </ol>
 * VERIFIED 는 그 근거의 존재·프로젝트 범위·Tool 결과와의 연결·이름·소속·버전을 확인했다는 뜻이다. 답변 문장 전체가 사실이라는 뜻은 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceVerifier {

    /** Tool 과 같은 조회 권한 */
    static final String REQUIRED_ROLE = "VIEWER";

    public enum Status { VERIFIED, NOT_FOUND, OUT_OF_SCOPE, MISMATCH, STALE, UNVERIFIABLE }

    public enum Reason {
        /** 이 질의의 Tool 결과에 없던 근거 (AI 가 조회하지 않은 정보) */
        NOT_RETRIEVED,
        /** 조회 뒤 수정됐다 (수정 일시가 다르다) */
        MODIFIED,
        /** 조회 뒤 지워졌다 */
        DELETED,
        /** 이름이 실제 메타데이터와 다르다 */
        NAME,
        /** 컬럼이 그 테이블 소속이 아니다 */
        PARENT,
        /** 관계 종류(FK / LOGICAL)가 다르다 */
        KIND,
        /** 버전(수정 일시)이 없어 변경 여부를 확인할 수 없다 */
        VERSION_UNKNOWN,
        /** 설명을 근거로 들었지만 이 질의에서 그 설명을 돌려주지 않았다 (설명을 뺀 조회·실패한 호출·다른 버전 — D35-D25) */
        DESCRIPTION_NOT_RETRIEVED,
        /** 설명을 근거로 들었지만 설명이 등록돼 있지 않았다 (조회 결과 설명 없음) */
        DESCRIPTION_NOT_REGISTERED
    }

    /** 답변 전체 : 근거가 모두 확인됨 / 일부만 / 하나도 안 됨 / 근거 없음 */
    public enum Overall { VERIFIED, PARTIAL, UNVERIFIED, NO_EVIDENCE }

    /**
     * @param name   AI 가 든 이름 (서버의 메타데이터 내용이 아니다 — 다른 프로젝트 행의 이름을 내지 않기 위해)
     * @param reason 실패 사유 (VERIFIED·NOT_FOUND·OUT_OF_SCOPE 는 null)
     * @param source 이 질의에서 그 근거를 가져온 방식 (모델의 Tool 호출 / 서버가 모은 맥락). 이 질의에서 조회하지 않은 근거는 null
     */
    public record Result(EvidenceType type, Long id, String name, Status status, Reason reason, MetadataToolSession.Mode source) {
    }

    public record Summary(Overall status, int total, int verified, int invalid) {
    }

    public record Verification(List<Result> evidence, Summary summary) {
    }

    private final ExecutionContextVerifier verifier;
    private final EvidenceCatalog catalog;

    @Transactional(readOnly = true, timeout = 30)
    public Verification verify(MetadataToolSession session, List<StructuredAnswer.Evidence> claims) {
        verifier.verify(session.context(), REQUIRED_ROLE);
        Long projectId = session.context().projectId();

        // 같은 대상을 여러 번 들면 처음 것만 본다
        Map<String, StructuredAnswer.Evidence> distinct = new LinkedHashMap<>();
        for (StructuredAnswer.Evidence claim : claims == null ? List.<StructuredAnswer.Evidence>of() : claims) {
            if (claim != null && claim.type() != null && claim.id() != null) distinct.putIfAbsent(claim.type() + ":" + claim.id(), claim);
        }
        Map<EvidenceType, Map<Long, EvidenceCatalog.Snapshot>> current = new EnumMap<>(EvidenceType.class);
        distinct.values().stream().collect(Collectors.groupingBy(StructuredAnswer.Evidence::type, Collectors.mapping(StructuredAnswer.Evidence::id, Collectors.toList())))
                .forEach((type, ids) -> current.put(type, catalog.load(type, ids)));

        List<Result> results = new ArrayList<>();
        for (StructuredAnswer.Evidence claim : distinct.values()) {
            EvidenceCatalog.Snapshot now = current.getOrDefault(claim.type(), Map.of()).get(claim.id());
            EvidenceCatalog.Snapshot seen = session.seen(claim.type(), claim.id());
            results.add(judge(claim, now, seen, claim.type() == EvidenceType.LOGICAL_ATTRIBUTE ? session.described(claim.type(), claim.id()) : null, projectId,
                    seen == null ? null : session.mode()));
        }
        int verified = (int) results.stream().filter(r -> r.status() == Status.VERIFIED).count();
        Overall overall = results.isEmpty() ? Overall.NO_EVIDENCE : verified == results.size() ? Overall.VERIFIED : verified == 0 ? Overall.UNVERIFIED : Overall.PARTIAL;
        Summary summary = new Summary(overall, results.size(), verified, results.size() - verified);
        log.info("AI 근거 검증 : request={} project={} status={} total={} verified={} {}", session.requestId(), projectId, overall, summary.total(), verified,
                results.stream().filter(r -> r.status() != Status.VERIFIED).map(r -> r.type() + ":" + r.id() + "=" + r.status() + (r.reason() == null ? "" : "/" + r.reason()))
                        .toList());
        return new Verification(results, summary);
    }

    private static Result judge(StructuredAnswer.Evidence claim, EvidenceCatalog.Snapshot now, EvidenceCatalog.Snapshot seen, MetadataToolSession.DescriptionView description,
                                Long projectId, MetadataToolSession.Mode source) {
        if (now == null) return result(claim, seen != null ? Status.STALE : Status.NOT_FOUND, seen != null ? Reason.DELETED : null, source);
        if (!projectId.equals(now.projectId())) return result(claim, Status.OUT_OF_SCOPE, null, source);
        if (seen == null) return result(claim, Status.UNVERIFIABLE, Reason.NOT_RETRIEVED, source);
        if (seen.version() != null && now.version() != null && !seen.version().equals(now.version())) return result(claim, Status.STALE, Reason.MODIFIED, source);
        if (!now.labels().contains(EvidenceCatalog.normalize(claim.name()))) return result(claim, Status.MISMATCH, Reason.NAME, source);
        if (claim.type() == EvidenceType.COLUMN && claim.tableId() != null && !Objects.equals(claim.tableId(), now.parentId())) {
            return result(claim, Status.MISMATCH, Reason.PARENT, source);
        }
        if (claim.type() == EvidenceType.RELATION && claim.kind() != null && !claim.kind().equals(now.kind())) return result(claim, Status.MISMATCH, Reason.KIND, source);
        if (seen.version() == null || now.version() == null) return result(claim, Status.UNVERIFIABLE, Reason.VERSION_UNKNOWN, source);
        if (Boolean.TRUE.equals(claim.usesDescription())) {
            // 설명 사용 (D35-D25) : 이 질의에서 그 속성의 설명을 지금 버전 그대로 실제로 돌려줬어야 한다. 문장이 설명과 맞는지(의미)는 서버가 판정하지 않는다
            MetadataToolSession.DescriptionView view = description;
            if (view == null || !now.version().equals(view.version())) return result(claim, Status.UNVERIFIABLE, Reason.DESCRIPTION_NOT_RETRIEVED, source);
            if (!view.registered()) return result(claim, Status.MISMATCH, Reason.DESCRIPTION_NOT_REGISTERED, source);
        }
        return result(claim, Status.VERIFIED, null, source);
    }

    private static Result result(StructuredAnswer.Evidence claim, Status status, Reason reason, MetadataToolSession.Mode source) {
        String name = claim.name() == null ? null : claim.name().length() > 200 ? claim.name().substring(0, 200) : claim.name();
        return new Result(claim.type(), claim.id(), name, status, reason, source);
    }
}
