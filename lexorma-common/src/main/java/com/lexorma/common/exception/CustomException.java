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
