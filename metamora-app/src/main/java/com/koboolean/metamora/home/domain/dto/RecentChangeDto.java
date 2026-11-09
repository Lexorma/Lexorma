package com.koboolean.metamora.home.domain.dto;

import java.time.LocalDateTime;

public record RecentChangeDto(
        String type,
        String name,
        String modifiedBy,
        LocalDateTime modifiedAt
) {}
