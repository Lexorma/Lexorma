package com.lexorma.data.inspection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.project.service.ProjectAuthority;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.repository.InspectionQueryRepository;
import com.lexorma.data.inspection.repository.InspectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프로젝트 검사 규칙 설정 (AI-6 2차, AI6-D05·D06·D14·D19). 조회는 VIEWER, 변경은 프로젝트 관리자·ADMIN (요청 시점에 DB 에서 다시 확인).
 * 바꿀 수 있는 것은 사용 여부뿐이고 기본값은 활성이다 — 끈 규칙만 행으로 남기고, 다시 켜면 행을 지운다.
 * 설정을 바꿔도 검사를 실행하지 않고, 진행 중 실행은 시작할 때 확정한 규칙으로 끝낸다 (AI6-D28). 끈 규칙의 결과는 지우거나 해결하지 않는다 (AI6-D14).
 */
@Service
@RequiredArgsConstructor
public class InspectionRuleSettingService {

    private final InspectionRepository repository;
    private final ExecutionContextVerifier verifier;
    private final ProjectWriteGuard projectWriteGuard;
    private final ProjectAuthority projectAuthority;
    private final InspectionQueryRepository queries;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public InspectionDto.RuleSettings settings(AccountDto principal) {
        long projectId = principal.getProjectId();
        return new InspectionDto.RuleSettings(rules(projectId), projectAuthority.canManage(projectId, Long.valueOf(principal.getId())));
    }

    @Transactional
    public InspectionDto.RuleSettings update(AccountDto principal, InspectionDto.RuleSettingsUpdate request) {
        if (request == null || request.rules() == null || request.rules().isEmpty()) throw new CustomException(ErrorCode.INVALID_INSPECTION_RULE_SETTING);
        Set<StandardizationRule> seen = EnumSet.noneOf(StandardizationRule.class);
        for (InspectionDto.RuleChange change : request.rules()) {
            if (change == null || change.rule() == null || change.active() == null || !seen.add(change.rule())) {
                throw new CustomException(ErrorCode.INVALID_INSPECTION_RULE_SETTING);
            }
            // 화면이 읽은 지금 값 (DEC-08 기준값) : 없으면 400
            if (change.expectedActive() == null) throw new CustomException(ErrorCode.MODEL_BASE_REQUIRED);
        }
        VerifiedExecution execution = verifier.verifyProjectManager(ExecutionContext.user(principal));
        long projectId = execution.projectId();
        // 설정 변경끼리 줄 세운다 (프로젝트 행 배타 잠금 — 표준 도메인 등록과 같은 방식) → 지금 값을 읽고 화면이 읽은 값과 비교한다.
        // 하나라도 다르면 아무것도 바꾸지 않고 409 (자동 재시도·덮어쓰기 없음). 바꿀 값이 지금 값과 같으면 그 규칙은 그대로 둔다
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        projectWriteGuard.lock(projectId);
        Set<StandardizationRule> disabled = repository.disabledRules(projectId);
        for (InspectionDto.RuleChange change : request.rules()) {
            if (change.expectedActive() == disabled.contains(change.rule())) throw new CustomException(ErrorCode.INSPECTION_RULE_SETTING_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        for (InspectionDto.RuleChange change : request.rules()) {
            boolean current = !disabled.contains(change.rule());
            if (change.active() == current) continue;
            if (change.active()) repository.enableRule(projectId, change.rule());
            else repository.disableRule(projectId, change.rule(), now, execution.auditor());
        }
        return new InspectionDto.RuleSettings(rules(projectId), true);
    }

    /**
     * 규칙 카탈로그 + 사용 여부 + 규칙별 마지막 확정 판정 · 재검사 필요 (마지막 확정 실행이 이 켜진 규칙을 판정하지 않았다).
     * 권한을 보지 않는다 — 화면은 {@link #settings}, AI-7 Tool 은 검증된 실행 컨텍스트의 프로젝트로만 부른다
     */
    @Transactional(readOnly = true)
    public List<InspectionDto.RuleSetting> rules(long projectId) {
        Set<StandardizationRule> disabled = repository.disabledRules(projectId);
        Map<StandardizationRule, Object[]> last = queries.lastInspected(projectId);
        Long lastRun = repository.lastRun(projectId, EnumSet.of(RunStatus.COMPLETED)).map(InspectionRepository.RunRow::id).orElse(null);
        List<InspectionDto.RuleSetting> rules = new ArrayList<>();
        for (StandardizationRule rule : StandardizationRule.values()) {
            Object[] inspected = last.get(rule);
            Long runId = inspected == null ? null : (Long) inspected[0];
            boolean active = !disabled.contains(rule);
            rules.add(new InspectionDto.RuleSetting(rule, rule.targetType(), rule.severity(), InspectionJudge.group(rule).name(), active, runId,
                    inspected == null ? null : (LocalDateTime) inspected[1], active && lastRun != null && !lastRun.equals(runId)));
        }
        return rules;
    }
}
