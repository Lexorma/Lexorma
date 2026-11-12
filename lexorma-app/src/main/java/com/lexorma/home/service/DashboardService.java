package com.lexorma.home.service;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.home.domain.dto.DashboardDto;

public interface DashboardService {

    DashboardDto selectDashboardData(AccountDto accountDto);
}
