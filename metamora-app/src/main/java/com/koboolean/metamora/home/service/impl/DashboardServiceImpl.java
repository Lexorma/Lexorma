package com.koboolean.metamora.home.service.impl;

import com.koboolean.metamora.common.i18n.Messages;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.UserRepository;
import com.koboolean.metamora.home.domain.dto.DashboardDto;
import com.koboolean.metamora.home.domain.dto.RecentChangeDto;
import com.koboolean.metamora.home.repository.DashboardRepository;
import com.koboolean.metamora.home.service.DashboardService;
import com.koboolean.metamora.operation.board.domain.dto.BoardViewDto;
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
