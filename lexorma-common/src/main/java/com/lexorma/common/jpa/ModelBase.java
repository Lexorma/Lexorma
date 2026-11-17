package com.lexorma.common.jpa;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 모델 편집의 낙관적 충돌 검사 공통 계약 (DEC-08 · DEC-13).
 * <ul>
 *   <li>기준값 : 행의 마지막 수정 일시(MDFCN_DT, {@link BaseEntity#modifiedAt()}). 조회 응답이 주고 저장 요청이 {@code baseModifiedAt} 으로 돌려준다.
 *       모델 쓰기는 모두 JPA 를 거쳐 MDFCN_DT 가 바뀐다 (AI-5 버전 판단과 같은 값 — 새 버전 컬럼을 두지 않는다)</li>
 *   <li>원자성 : 호출하는 쪽이 행을 잠그고({@code findForUpdate} — SELECT … FOR UPDATE) 다시 읽은 뒤 비교한다. 잠근 동안 다른 쓰기는 기다리므로 비교와 반영 사이에 끼어들 수 없다</li>
 *   <li>다르면 409 {@link ErrorCode#MODEL_CONFLICT} — 아무것도 바꾸지 않는다(사용자 입력은 화면에 남는다). 기준값을 보내지 않은 요청(예전 화면·API)은 검사하지 않는다 (하위 호환)</li>
 *   <li>비교는 1 마이크로초 미만 차이를 같게 본다 (DB 의 timestamp(6) 저장 — PostgreSQL 은 반올림, H2 는 버림 — 과 메모리 값·JSON 왕복 값의 자릿수 차이)</li>
 * </ul>
 */
public final class ModelBase {

    private ModelBase() {
    }

    public static void verify(BaseEntity locked, LocalDateTime base, String objectName) {
        if (base == null) return;
        if (!same(locked.modifiedAt(), base)) {
            throw new CustomException(ErrorCode.MODEL_CONFLICT, "충돌한 객체 : {0}", objectName);
        }
    }

    /**
     * HTTP 수정 요청은 기준값이 필수다 (DEC-08 3차 — "기준값 없음 = 검사 안 함" 호환을 화면·API 경계에서 끝냈다).
     * 컨트롤러가 부른다. 서비스를 직접 부르는 내부 작업(원본 가져오기·AI 제안 승인·논리 → 물리 생성·테스트 준비)은 자기 잠금·재검증 규칙을 따르며 이 검사 밖이다
     */
    public static void require(boolean update, Object base) {
        if (update && base == null) throw new CustomException(ErrorCode.MODEL_BASE_REQUIRED);
    }

    private static boolean same(LocalDateTime current, LocalDateTime base) {
        if (current == null || base == null) return Objects.equals(current, base);
        return Math.abs(ChronoUnit.NANOS.between(current, base)) < 1_000;
    }
}
