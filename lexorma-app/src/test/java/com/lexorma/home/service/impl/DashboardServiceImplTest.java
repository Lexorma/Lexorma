package com.lexorma.home.service.impl;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.UserRepository;
import com.lexorma.core.support.CoreFixtures;
import com.lexorma.home.domain.dto.DashboardDto;
import com.lexorma.home.domain.dto.DashboardStatsDto;
import com.lexorma.home.repository.DashboardRepository;
import com.lexorma.operation.board.domain.dto.BoardViewDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

    @Mock
    UserRepository userRepository;
    @Mock
    DashboardRepository dashboardRepository;

    @InjectMocks
    DashboardServiceImpl service;

    @Test
    @DisplayName("기본 데이터는 로그인 프로젝트의 최근 공지 5건뿐이다 (최근 활동·승인 대기 건수는 2026-11-15 개편에서 통계 API 로 옮기거나 뺐다)")
    void dashboard() {
        Account account = CoreFixtures.account(1L, "admin");
        List<BoardViewDto> notices = List.of(mock(BoardViewDto.class), mock(BoardViewDto.class));
        when(userRepository.findById(1L)).thenReturn(Optional.of(account));
        when(dashboardRepository.findNotice(7L, 5, account)).thenReturn(notices);

        DashboardDto result = service.selectDashboardData(AccountDto.builder().id("1").projectId(7L).build());

        assertThat(result.getNotices()).isSameAs(notices);
    }

    @Test
    @DisplayName("컬럼 수 구간 : 경계값(5·6·10·11·20·21·50·51)이 맞는 구간에 들고, 컬럼이 없는 테이블은 전체 − 컬럼 있는 테이블")
    void buckets() {
        List<DashboardStatsDto.ColumnBucket> result = DashboardServiceImpl.buckets(12, List.of(1L, 5L, 6L, 10L, 11L, 20L, 21L, 50L, 51L, 300L));
        assertThat(result).extracting(DashboardStatsDto.ColumnBucket::label).containsExactly("0", "1-5", "6-10", "11-20", "21-50", "51+");
        assertThat(result).extracting(DashboardStatsDto.ColumnBucket::tables).containsExactly(2L, 2L, 2L, 2L, 2L, 2L);
        assertThat(DashboardServiceImpl.buckets(0, List.of())).extracting(DashboardStatsDto.ColumnBucket::tables).containsOnly(0L);
    }
}
