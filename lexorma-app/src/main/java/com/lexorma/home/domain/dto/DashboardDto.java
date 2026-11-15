package com.lexorma.home.domain.dto;


import com.lexorma.operation.board.domain.dto.BoardViewDto;
import lombok.*;

import java.util.List;

/** 대시보드 기본 데이터 (프로젝트 회원 누구나 — NOT_APPROVE 포함) : 프로젝트 공지사항. 통계는 {@link DashboardStatsDto} (VIEWER) */
@Builder
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDto {

    private List<BoardViewDto> notices;

}
