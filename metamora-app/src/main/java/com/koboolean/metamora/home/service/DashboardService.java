package com.koboolean.metamora.home.service;

import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.home.domain.dto.DashboardDto;

public interface DashboardService {

    DashboardDto selectDashboardData(AccountDto accountDto);
}
