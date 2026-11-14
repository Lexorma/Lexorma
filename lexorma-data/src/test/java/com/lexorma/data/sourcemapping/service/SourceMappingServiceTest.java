package com.lexorma.data.sourcemapping.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceMappingServiceTest {

    @Test
    @DisplayName("비교 경로(<길이>:<카탈로그>/<길이>:<스키마>)에서 스키마 비교 이름을 꺼낸다 (원본 스키마가 사라진 예외 TABLE 표시용). 형식이 다르면 null")
    void schemaOfPath() {
        assertThat(SourceMappingService.schemaOfPath("0:/5:audit")).isEqualTo("audit");
        assertThat(SourceMappingService.schemaOfPath("3:a/b/4:x:yz")).isEqualTo("x:yz");
        assertThat(SourceMappingService.schemaOfPath("0:/0:")).isEmpty();
        for (String bad : new String[]{null, "", "audit", "x:/5:audit", "2:/5:audit", "0:/9:audit"}) {
            assertThat(SourceMappingService.schemaOfPath(bad)).as(String.valueOf(bad)).isNull();
        }
    }
}
