package com.koboolean.metamora.home.repository;

import com.koboolean.metamora.operation.board.domain.dto.BoardDto;
import com.koboolean.metamora.operation.board.domain.dto.BoardViewDto;
import com.koboolean.metamora.home.domain.dto.DashboardDto;
import com.koboolean.metamora.home.domain.dto.RecentChangeDto;
import com.koboolean.metamora.core.security.domain.entity.Account;

import java.util.List;

public interface DashboardRepositoryCustom {

    List<RecentChangeDto> findRecentChanges(Long projectId, int limit);
    List<BoardViewDto> findNotice(Long projectId, int limit, Account account);

    DashboardDto findDataCount(Long projectId);
}
