package com.lexorma.home.domain.dto;


import com.lexorma.operation.board.domain.dto.BoardDto;
import com.lexorma.operation.board.domain.dto.BoardViewDto;
import lombok.*;

import java.util.List;

@Builder
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDto {

    private int pendingStandardCount;
    private int pendingTableCount;
    private int pendingColumnCount;

    private List<BoardViewDto> notices;
    private List<RecentChangeDto> recentChanges;

}
