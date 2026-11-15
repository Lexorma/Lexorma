package com.lexorma.home.service.impl;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.UserRepository;
import com.lexorma.core.security.service.MenuAuthorization;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.home.domain.dto.DashboardDto;
import com.lexorma.home.domain.dto.DashboardStatsDto;
import com.lexorma.home.repository.DashboardRepository;
import com.lexorma.home.service.DashboardService;
import com.lexorma.operation.board.domain.dto.BoardViewDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 대시보드. 기본 데이터(공지사항)는 프로젝트 회원 누구나, 통계는 VIEWER 이상(표준·모델 메뉴를 볼 수 있는 역할)만 — 컨트롤러가 나눈다.
 * 통계는 로그인 프로젝트 범위의 DB 집계이고, 표준 준수·품질 유형별 건수는 표준화 품질 진단(M-5)의 같은 계산을 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    /** 대시보드에 보이는 최근 공지 수 (공지에는 중요 표시가 없어 등록 순) */
    static final int NOTICE_LIMIT = 5;

    private final UserRepository userRepository;
    private final DashboardRepository dashboardRepository;
    private final StandardizationQualityService qualityService;
    private final MenuAuthorization menuAuthorization;

    @Override
    public DashboardDto selectDashboardData(AccountDto accountDto) {

        Long projectId = accountDto.getProjectId();

        Account account = userRepository.findById(Long.parseLong(accountDto.getId())).orElse(null);

        List<BoardViewDto> notice = dashboardRepository.findNotice(projectId, NOTICE_LIMIT, account);

        // shortcut: 본문을 읽지 않아 제목만으로 시스템 공지를 판단한다, 등록된 서버 문구와 제목이 같은 일반 공지가 문제 되면 본문까지 확인
        notice.forEach(board -> board.setTitle(Messages.get(board.getTitle())));

        return DashboardDto.builder().notices(notice).build();
    }

    @Override
    public DashboardStatsDto selectStats(AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        DashboardStatsDto.Counts counts = dashboardRepository.countAll(projectId);
        long[] linked = dashboardRepository.countLinkedColumns(projectId);
        long[] missing = dashboardRepository.countMissingDescriptions(projectId);
        StandardizationQualityDto.Summary diagnosis = qualityService.selectSummary(accountDto);

        DashboardStatsDto.Standard standard = new DashboardStatsDto.Standard(diagnosis.totalColumns(), linked[0], linked[1],
                diagnosis.totalColumns() - diagnosis.issueColumns());
        DashboardStatsDto.Quality quality = new DashboardStatsDto.Quality(diagnosis.issueColumns(), diagnosis.termNotLinked(),
                diagnosis.columnNameMismatch(), diagnosis.domainDefinitionMismatch(), missing[0], missing[1], missing[2], missing[3]);
        DashboardStatsDto.Model model = new DashboardStatsDto.Model(dashboardRepository.countTablesBySchema(projectId),
                buckets(counts.tables(), dashboardRepository.columnCountsPerTable(projectId)), dashboardRepository.countTablesWithoutRelation(projectId));

        // 승인 처리 가능 여부는 /api/auth/me 의 approvalAvailable 과 같은 판정 : 승인 API 의 역할(사전 DBA, 모델 USER) + 승인 권한(ADMIN·MANAGER·프로젝트 관리자)
        boolean approver = AuthUtil.isApprovalAvailable();
        return new DashboardStatsDto(counts, dashboardRepository.countApprovals(projectId), approver && menuAuthorization.hasRole("DBA"),
                approver && menuAuthorization.hasRole("USER"), standard, quality, model, LocalDateTime.now());
    }

    /** 컬럼 수 구간별 테이블 수 : 컬럼이 없는 테이블 = 전체 테이블 − 컬럼이 있는 테이블 */
    static List<DashboardStatsDto.ColumnBucket> buckets(long tables, List<Long> perTable) {
        long[] n = new long[6];
        for (Long c : perTable) {
            if (c <= 5) n[1]++;
            else if (c <= 10) n[2]++;
            else if (c <= 20) n[3]++;
            else if (c <= 50) n[4]++;
            else n[5]++;
        }
        n[0] = Math.max(0, tables - perTable.size());
        String[] labels = {"0", "1-5", "6-10", "11-20", "21-50", "51+"};
        return java.util.stream.IntStream.range(0, 6).mapToObj(i -> new DashboardStatsDto.ColumnBucket(labels[i], n[i])).toList();
    }
}
