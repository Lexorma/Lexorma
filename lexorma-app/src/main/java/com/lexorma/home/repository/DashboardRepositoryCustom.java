package com.lexorma.home.repository;

import com.lexorma.operation.board.domain.dto.BoardDto;
import com.lexorma.operation.board.domain.dto.BoardViewDto;
import com.lexorma.home.domain.dto.DashboardDto;
import com.lexorma.home.domain.dto.RecentChangeDto;
import com.lexorma.core.security.domain.entity.Account;

import java.util.List;

public interface DashboardRepositoryCustom {

    List<RecentChangeDto> findRecentChanges(Long projectId, int limit);
    List<BoardViewDto> findNotice(Long projectId, int limit, Account account);

    DashboardDto findDataCount(Long projectId);
}
