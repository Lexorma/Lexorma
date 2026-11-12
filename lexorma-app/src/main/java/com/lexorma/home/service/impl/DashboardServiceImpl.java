package com.lexorma.home.service.impl;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.UserRepository;
import com.lexorma.home.domain.dto.DashboardDto;
import com.lexorma.home.domain.dto.RecentChangeDto;
import com.lexorma.home.repository.DashboardRepository;
import com.lexorma.home.service.DashboardService;
import com.lexorma.operation.board.domain.dto.BoardViewDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private final UserRepository userRepository;
    private final DashboardRepository dashboardRepository;

    @Override
    public DashboardDto selectDashboardData(AccountDto accountDto) {

        Long projectId = accountDto.getProjectId();

        Account account = userRepository.findById(Long.parseLong(accountDto.getId())).orElse(null);


        List<RecentChangeDto> recentChanges = dashboardRepository.findRecentChanges(projectId, 5);
        List<BoardViewDto> notice = dashboardRepository.findNotice(projectId, 3, account);

        DashboardDto dashboardDto = dashboardRepository.findDataCount(projectId);

        // shortcut: 본문을 읽지 않아 제목만으로 시스템 공지를 판단한다, 등록된 서버 문구와 제목이 같은 일반 공지가 문제 되면 본문까지 확인
        notice.forEach(board -> board.setTitle(Messages.get(board.getTitle())));

        dashboardDto.setRecentChanges(recentChanges);
        dashboardDto.setNotices(notice);

        return dashboardDto;
    }
}
