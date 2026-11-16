package com.lexorma.home.service;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.home.domain.dto.DashboardDto;
import com.lexorma.home.domain.dto.DashboardStatsDto;

public interface DashboardService {

    DashboardDto selectDashboardData(AccountDto accountDto);

    DashboardStatsDto selectStats(AccountDto accountDto);
}
