package com.lexorma.data.inspection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.inspection.domain.InspectionCodes.FindingStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.HistoryType;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.repository.InspectionRepository;
import com.lexorma.data.inspection.repository.InspectionRepository.HistoryInsert;
import com.lexorma.data.inspection.repository.InspectionRepository.LockedFinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 검사 결과의 예외 처리·해제 (AI-6 2차, AI6-D15·D24·D29). USER 이상.
 * <ul>
 * <li>사유 필수(앞뒤 공백 제거 후 1~1,000자), 화면이 읽은 버전 번호 필수 — 행을 잠근 뒤 비교해 다르면 409 (M-8 충돌 원칙 : 자동 재시도·덮어쓰기 없음)</li>
 * <li>예외 처리는 미해결(OPEN) 결과만 — 해결된 결과는 예외로 둘 문제가 없다. 해제는 상태와 무관하다</li>
 * <li>예외 여부·버전 번호만 바꾼다 : 실제 문제 상태는 검사만 바꾼다 (예외와 상태는 따로다)</li>
 * <li>바뀔 때마다 이력을 남긴다 (사유·사람·시각, 추가만)</li>
 * </ul>
 * 잠금 순서 : 프로젝트(공유) → 결과 행. 검사 저장도 같은 순서이며, 저장은 예외 칼럼을 쓰지 않으므로 어느 쪽이 먼저든 서로의 값을 지우지 않는다.
 */
@Service
@RequiredArgsConstructor
public class InspectionExceptionService {

    public static final int MAX_REASON = 1_000;

    private final InspectionRepository repository;
    private final InspectionQueryService queries;
    private final ExecutionContextVerifier verifier;
    private final ProjectWriteGuard projectWriteGuard;

    @Transactional
    public InspectionDto.FindingDetail exclude(AccountDto principal, long findingId, InspectionDto.ExclusionRequest request) {
        return change(principal, findingId, request, true);
    }

    @Transactional
    public InspectionDto.FindingDetail unexclude(AccountDto principal, long findingId, InspectionDto.ExclusionRequest request) {
        return change(principal, findingId, request, false);
    }

    private InspectionDto.FindingDetail change(AccountDto principal, long findingId, InspectionDto.ExclusionRequest request, boolean exclude) {
        String reason = reason(request);
        if (request.version() == null) throw new CustomException(ErrorCode.MODEL_BASE_REQUIRED);
        VerifiedExecution execution = verifier.verify(ExecutionContext.user(principal), "USER");
        long projectId = execution.projectId();
        projectWriteGuard.lock(projectId);
        LockedFinding finding = repository.lockFinding(projectId, findingId).orElseThrow(() -> new CustomException(ErrorCode.INSPECTION_RESULT_NOT_FOUND));
        if (finding.version() != request.version() || finding.excluded() == exclude) {
            throw new CustomException(ErrorCode.INSPECTION_RESULT_CONFLICT);
        }
        if (exclude && finding.status() != FindingStatus.OPEN) throw new CustomException(ErrorCode.INSPECTION_RESULT_NOT_OPEN);
        LocalDateTime now = LocalDateTime.now();
        repository.updateExclusion(projectId, findingId, exclude, now, execution.auditor());
        repository.insertHistory(projectId, List.of(new HistoryInsert(findingId, null, exclude ? HistoryType.EXCLUDE : HistoryType.UNEXCLUDE,
                finding.status(), exclude, reason, null, null)), now, execution.auditor());
        return queries.finding(projectId, findingId);
    }

    static String reason(InspectionDto.ExclusionRequest request) {
        String reason = request == null || request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty()) throw new CustomException(ErrorCode.INSPECTION_REASON_REQUIRED);
        if (reason.length() > MAX_REASON) throw new CustomException(ErrorCode.INSPECTION_REASON_TOO_LONG);
        return reason;
    }
}
