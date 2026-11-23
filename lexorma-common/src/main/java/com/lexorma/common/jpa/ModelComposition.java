package com.lexorma.common.jpa;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 하위 객체를 함께 지우는 삭제의 구성 기준값 (M8-C2, M8-D01 — docs/ongoing/modeling-concurrency-m8.md 14장).
 * 부모 행의 수정 일시(MDFCN_DT)는 자식이 더해지거나 바뀌어도 움직이지 않으므로, 삭제 확인 화면이 본 "함께 지워질 자식" 을 따로 비교한다.
 * <ul>
 *   <li>값 : 부분(section)마다 자식 (ID, 마지막 수정 일시) 를 ID 순으로 늘어놓은 정규 문자열의 SHA-256 — {@code v1:} + 16진수. 개수만 비교하지 않는다 (같은 수의 다른 자식, 고친 자식도 다르다)</li>
 *   <li>부분 이름과 순서는 부르는 쪽이 정하고 고정한다 (같은 입력 = 같은 값). 수정 일시는 마이크로초까지 (DB 의 timestamp(6))</li>
 *   <li>원자성 : 부르는 쪽이 자식을 더하는 쓰기와 같은 부모 잠금(논리 모델 행·테이블 행)을 쥔 뒤 다시 계산해 비교한다 — 잠근 동안 새 자식이 들어올 수 없다</li>
 *   <li>다르면 409 {@link ErrorCode#MODEL_CONFLICT} (기준값과 같은 코드), HTTP 요청에 없으면 400 {@link ErrorCode#MODEL_BASE_REQUIRED}</li>
 * </ul>
 */
public final class ModelComposition {

    public static final String VERSION = "v1";

    private final Map<String, List<Part>> sections = new LinkedHashMap<>();

    private ModelComposition() {
    }

    /** 자식 하나 : ID 와 마지막 수정 일시 (수정 일시가 없는 연결 행은 null) */
    public record Part(Long id, LocalDateTime modifiedAt) {
    }

    /**
     * 삭제 확인 화면용 미리보기 : 지울 객체의 지금 기준값(modifiedAt), 함께 지워질 자식 수(cascade — 종류별), 구성 기준값(composition).
     * 삭제 요청은 화면이 처음 읽은 modifiedAt 과 이 composition 을 함께 보낸다
     */
    public record Deletion(Long id, String name, LocalDateTime modifiedAt, List<Cascade> cascade, String composition) {
    }

    /** kind : 자식 종류 (화면 표시용 키 — attributes · identifiers · subjectAreas · indexes · constraints · relations · conversions 등) */
    public record Cascade(String kind, int count) {
    }

    /** 미리보기 : 부분마다 자식 수를 그대로 싣는다 */
    public Deletion deletion(Long id, String name, LocalDateTime modifiedAt) {
        List<Cascade> cascade = new ArrayList<>();
        sections.forEach((kind, parts) -> cascade.add(new Cascade(kind, parts.size())));
        return new Deletion(id, name, modifiedAt, cascade, token());
    }

    public static ModelComposition of() {
        return new ModelComposition();
    }

    /** 부분 하나를 더한다 (같은 이름을 두 번 쓰면 합친다). 순서는 ID 순으로 정규화한다 */
    public ModelComposition add(String section, List<Part> parts) {
        sections.computeIfAbsent(section, s -> new ArrayList<>()).addAll(parts == null ? List.of() : parts);
        return this;
    }

    public <T extends BaseEntity> ModelComposition addEntities(String section, java.util.Collection<T> entities, java.util.function.Function<T, Long> id) {
        return add(section, entities.stream().map(e -> new Part(id.apply(e), e.modifiedAt())).toList());
    }

    public String token() {
        StringBuilder canonical = new StringBuilder(VERSION);
        sections.forEach((name, parts) -> {
            canonical.append('|').append(name).append('=');
            parts.stream().sorted(Comparator.comparing(Part::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .forEach(p -> canonical.append(p.id()).append('@').append(micros(p.modifiedAt())).append(','));
        });
        return VERSION + ":" + sha256(canonical.toString());
    }

    /** 잠근 뒤 다시 계산한 값과 화면이 본 값을 비교한다 (base null = 내부 호출 — 검사하지 않는다, ModelBase 와 같은 경계) */
    public void verify(String base, String objectName) {
        if (base == null) return;
        if (!Objects.equals(token(), base)) {
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", objectName);
        }
    }

    /** HTTP 삭제 요청은 구성 기준값이 필수다 (컨트롤러가 부른다) */
    public static void require(String base) {
        if (base == null || base.isBlank()) throw new CustomException(ErrorCode.MODEL_BASE_REQUIRED);
    }

    private static String micros(LocalDateTime value) {
        return value == null ? "-" : value.truncatedTo(ChronoUnit.MICROS).toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
