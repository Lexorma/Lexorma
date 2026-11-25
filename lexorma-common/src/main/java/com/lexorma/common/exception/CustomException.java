package com.lexorma.common.exception;

import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {
    @Getter
    ErrorCode errorCode;
    private final String customMessage;
    /**
     * 409 의 원인 (M8-C4c-2 C4c-D10) : {@link #LOCKED} = 다른 트랜잭션이 행을 쥐고 있어 NOWAIT 잠금을 얻지 못했다 (55P03 · H2 50200 으로 확인된 경로만).
     * null = 원인 구분 없음 (기준값 불일치 등 — 예전 그대로). 응답 JSON 의 선택 칸 reason 으로 나간다 (없으면 칸 자체가 없다)
     */
    private String reason;

    public static final String LOCKED = "LOCKED";

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.customMessage = null;
    }

    /**
     * @param customMessage 사유 (한국어 원문, 값이 들어갈 자리는 {0}, {1})
     * @param args          {0}, {1} 에 들어갈 값
     */
    public CustomException(ErrorCode errorCode, String customMessage, Object... args) {
        this(errorCode, Messages.get(customMessage, args), errorCode.getMessage());
    }

    /**
     * 오류 코드는 그대로 두고, 화면에 보일 문구를 이 문구로만 바꾼다 (오류 코드의 공통 문구를 앞에 붙이지 않는다).
     * 같은 코드라도 화면·행위마다 안내가 달라야 할 때 쓴다 — 예 : 표준 용어 수정·승인·승인 취소의 MODEL_CONFLICT (M-7 후속)
     *
     * @param message 화면 문구 (한국어 원문, {0}, {1} 자리)
     */
    public static CustomException withMessage(ErrorCode errorCode, String message, Object... args) {
        return new CustomException(errorCode, Messages.get(message, args), true);
    }

    /**
     * 잠금 획득 실패 (M8-C4c-2 C4c-D10) : 오류 코드·HTTP 상태는 그대로(예 : 409 MODEL_CONFLICT), 문구는 이 문구만, 원인 {@link #LOCKED}.
     * 실제로 NOWAIT 잠금 실패가 확인된 곳에서만 쓴다 — 원인을 모르는 409 를 잠금 충돌로 분류하지 않는다
     */
    public static CustomException locked(ErrorCode errorCode, String message, Object... args) {
        CustomException e = new CustomException(errorCode, Messages.get(message, args), true);
        e.reason = LOCKED;
        return e;
    }

    private CustomException(ErrorCode errorCode, String message, boolean messageOnly) {
        super(message);
        this.errorCode = errorCode;
        this.customMessage = message;
    }

    private CustomException(ErrorCode errorCode, String reason, String message) {
        super(message + " - " + reason);
        this.errorCode = errorCode;
        this.customMessage = message + " \n[" + reason + "]";
    }

    public String getCustomMessage() {
        return customMessage != null ? customMessage : errorCode.getMessage();
    }
}
