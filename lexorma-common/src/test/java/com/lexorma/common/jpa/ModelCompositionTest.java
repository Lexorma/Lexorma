package com.lexorma.common.jpa;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M8-C2 삭제 구성 기준값 : 결정적(입력 순서 무관), 개수가 아니라 (ID, 수정 일시) 를 본다, 부분 이름이 다르면 다르다 */
class ModelCompositionTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 23, 10, 0, 0, 123_456_000);

    private static ModelComposition.Part part(long id, LocalDateTime at) {
        return new ModelComposition.Part(id, at);
    }

    @Test
    @DisplayName("같은 구성은 입력 순서와 무관하게 같은 값 · 형식 v1:SHA-256")
    void deterministic() {
        String a = ModelComposition.of().add("attributes", List.of(part(2, T), part(1, T))).add("identifiers", List.of()).token();
        String b = ModelComposition.of().add("attributes", List.of(part(1, T), part(2, T))).add("identifiers", List.of()).token();
        assertThat(a).isEqualTo(b).startsWith("v1:").hasSize(3 + 64);
    }

    @Test
    @DisplayName("개수가 같아도 다른 자식·고친 자식·다른 부분이면 다르다 · 마이크로초 아래 차이는 같다")
    void sensitive() {
        String base = ModelComposition.of().add("attributes", List.of(part(1, T))).token();
        assertThat(ModelComposition.of().add("attributes", List.of(part(3, T))).token()).isNotEqualTo(base);
        assertThat(ModelComposition.of().add("attributes", List.of(part(1, T.plusNanos(1_000)))).token()).isNotEqualTo(base);
        assertThat(ModelComposition.of().add("identifiers", List.of(part(1, T))).token()).isNotEqualTo(base);
        assertThat(ModelComposition.of().add("attributes", List.of(part(1, T), part(2, T))).token()).isNotEqualTo(base);
        assertThat(ModelComposition.of().add("attributes", List.of(part(1, T.plusNanos(999)))).token()).isEqualTo(base);
    }

    @Test
    @DisplayName("verify : 다르면 409 MODEL_CONFLICT, 내부 호출(null)은 검사하지 않음 · require : 없거나 빈 값 400 · 미리보기는 부분별 수")
    void verifyAndRequire() {
        ModelComposition composition = ModelComposition.of().add("attributes", List.of(part(1, T))).add("identifiers", List.of());
        composition.verify(composition.token(), "E");
        composition.verify(null, "E");
        assertThatThrownBy(() -> composition.verify("v1:other", "E")).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_CONFLICT));
        assertThatThrownBy(() -> ModelComposition.require(" ")).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_BASE_REQUIRED));
        var deletion = composition.deletion(7L, "E", T);
        assertThat(deletion.cascade()).extracting(c -> c.kind() + "=" + c.count()).containsExactly("attributes=1", "identifiers=0");
        assertThat(deletion.composition()).isEqualTo(composition.token());
    }
}
